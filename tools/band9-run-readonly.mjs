import { execFileSync } from 'node:child_process';
import { fileURLToPath, pathToFileURL } from 'node:url';

const modes = new Set(['settings', 'history_index', 'history_read', 'history_sample', 'history_sample_next', 'history_repeat', 'history_batch', 'history_past_batch', 'history_correlate', 'history_target', 'history_target_scan', 'history_past_target_scan', 'history_past_target', 'history_past_repeat', 'history_boundary', 'history_passive', 'history_past_window', 'history_today_window']);
const isBatch = mode => mode === 'history_batch' || mode === 'history_past_batch' || mode === 'history_correlate' || mode === 'history_target' || mode === 'history_target_scan' || mode === 'history_past_target_scan' || mode === 'history_past_target' || mode === 'history_past_repeat';
const pause = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds));

function validateRange(mode, detailOffset, detailCount) {
  if (!modes.has(mode)) throw new Error('unsupported_read_only_mode');
  if (isBatch(mode)) {
    if (!Number.isInteger(detailOffset) || detailOffset < 0 || detailOffset > 255
        || !Number.isInteger(detailCount) || detailCount < 1 || detailCount > 2
        || ((mode === 'history_correlate' || mode === 'history_target' || mode === 'history_target_scan' || mode === 'history_past_target_scan' || mode === 'history_past_target' || mode === 'history_past_repeat') && detailCount !== 1))
      throw new Error('invalid_history_range');
  } else if (detailOffset !== undefined || detailCount !== undefined) throw new Error('unexpected_history_range');
}

export function parseReadOnlyArguments(args) {
  const [serial, mode, offset, count] = args;
  if (!serial || !modes.has(mode) || args.length !== (isBatch(mode) ? 4 : 2))
    throw new Error('usage_serial_mode_optional_batch_offset_count');
  const decimal = value => /^(0|[1-9][0-9]*)$/.test(value) ? Number(value) : NaN;
  const result = { serial, mode };
  if (isBatch(mode)) {
    result.detailOffset = decimal(offset); result.detailCount = decimal(count);
  }
  validateRange(mode, result.detailOffset, result.detailCount);
  return result;
}

export async function runReadOnlyProbe({ mode, detailOffset, detailCount, adb, prepare, wait = pause }) {
  validateRange(mode, detailOffset, detailCount);
  if (adb(['get-state']).trim() !== 'device') throw new Error('adb_not_authorized');
  const lock = adb(['shell', 'dumpsys', 'window', 'policy']);
  if (!/showing=false/.test(lock) || /inputRestricted=true/.test(lock)) {
    throw new Error('unlock_phone_before_probe');
  }
  let previous = null;
  try {
    previous = JSON.parse(adb(['shell', 'run-as', 'ru.kardiomonitor.band9probe', 'cat', 'files/report.json']));
  } catch { /* Первый запуск может ещё не иметь отчёта. */ }
  if (previous?.status === 'running') throw new Error('another_probe_is_running');
  if (adb(['shell', 'pm', 'path', 'ru.kardiomonitor.bandprotocol.test']).trim()) {
    const state = adb(['shell', 'run-as', 'ru.kardiomonitor.bandprotocol.test', 'sh', '-c',
      "'if [ -d files/production-check.lock ]; then echo locked; else echo idle; fi'"]).trim();
    if (state !== 'idle') throw new Error('production_check_active_or_unreadable');
  }

  let staged = false;
  let fitnessStopped = false;
  let result = null;
  const cleanup = {};
  try {
    // Даже при частично неудачной подготовке файл ключа должен удаляться.
    staged = true;
    prepare();
    adb(['shell', 'run-as', 'ru.kardiomonitor.band9probe', 'rm', '-f', 'files/report.json']);
    fitnessStopped = true;
    adb(['shell', 'am', 'force-stop', 'com.xiaomi.wearable']);
    const rangeExtras = isBatch(mode)
      ? ['--ei', 'detail_offset', String(detailOffset), '--ei', 'detail_count', String(detailCount)] : [];
    adb(['shell', 'am', 'start', '-W', '-S', '-n',
      'ru.kardiomonitor.band9probe/.ProbeActivity', '--es', 'mode', mode, ...rangeExtras]);
    for (let attempt = 0; attempt < 90; attempt++) {
      await wait(1000);
      let report;
      try {
        report = JSON.parse(adb(['shell', 'run-as', 'ru.kardiomonitor.band9probe', 'cat', 'files/report.json']));
      } catch { continue; }
      if (report.schema !== 20 || report.probe_version !== '0.32' || report.mode !== mode || report.status === 'running') continue;
      if (isBatch(mode) && (report.detail_offset !== detailOffset || report.detail_count !== detailCount)) continue;
      if (!['ok', 'partial', 'error', 'cancelled'].includes(report.status) || report.socket_closed !== true) continue;
      result = projectReadOnlyReport(report);
      break;
    }
    if (!result) throw new Error('probe_report_timeout');
  } finally {
    if (!result && fitnessStopped) {
      try { adb(['shell', 'am', 'force-stop', 'ru.kardiomonitor.band9probe']); }
      catch { cleanup.probe_abort_failed = true; }
    }
    if (staged) {
      try {
        adb(['shell', 'run-as', 'ru.kardiomonitor.band9probe', 'rm', '-f', 'files/auth-key']);
        adb(['shell', 'run-as', 'ru.kardiomonitor.band9probe', 'test', '!', '-e', 'files/auth-key']);
        cleanup.temporary_key_absent = true;
      } catch { cleanup.temporary_key_absent = false; }
    }
    if (fitnessStopped) {
      try {
        adb(['shell', 'monkey', '-p', 'com.xiaomi.wearable', '-c', 'android.intent.category.LAUNCHER', '1']);
        cleanup.mi_fitness_running = Boolean(adb(['shell', 'pidof', 'com.xiaomi.wearable']).trim());
      } catch { cleanup.mi_fitness_running = false; }
      cleanup.mi_fitness_bluetooth_reconnected = 'not_verified';
    }
    console.log(JSON.stringify({ cleanup }));
  }
  if (cleanup.temporary_key_absent === false || cleanup.mi_fitness_running === false) {
    throw new Error('probe_cleanup_incomplete');
  }
  return result;
}

const pick = (source, fields) => Object.fromEntries(fields.filter(key => Object.hasOwn(source, key))
  .filter(key => source[key] === null || ['boolean', 'string', 'number'].includes(typeof source[key]))
  .map(key => [key, source[key]]));

export function projectReadOnlyReport(report) {
  const safe = pick(report, [
    'schema', 'probe_version', 'mode', 'status', 'code', 'stage', 'elapsed_ms', 'hardware_attempted',
    'rfcomm_connected', 'authenticated', 'watch_hmac_verified', 'target_model_matches', 'target_firmware_matches',
    'device_model', 'device_firmware', 'battery_percent', 'spp_version', 'socket_closed',
    'settings_status', 'history_index_status', 'history_read_status', 'history_selected_file_count',
    'history_candidate_file_count', 'history_detail_candidate_file_count', 'history_omitted_file_count',
    'history_coverage', 'history_selection_policy', 'history_offset_scope', 'history_requested_index_scope', 'detail_offset', 'detail_count',
    'requested_detail_count_satisfied', 'retention_index_checked', 'retention_index_unchanged',
    'history_downloaded_bytes', 'history_file_download_attempted', 'history_file_ack_attempted', 'raw_history_persisted',
    'repeat_requested', 'repeat_read_count', 'repeat_aggregate_consistent', 'repeat_bytes_identical', 'history_correlation_header_only',
    'history_target_demultiplexed', 'history_time_values_verified', 'history_full_coverage_verified',
  ]);
  for (const name of ['history_boundary', 'history_passive', 'history_past_window', 'history_today_window']) {
    if (report[name] && typeof report[name] === 'object') {
      safe[name] = pick(report[name], ['fetch_command_count', 'bulk_frames', 'bulk_payload_bytes',
        'index_response_count', 'complete_file_count', 'incomplete_file_present', 'status',
        'today_response_transport_ack_seen', 'past_response_transport_ack_seen', 'history_command_count',
        'observation_window_ms', 'window_elapsed', 'transport_boundary_at_deadline', 'absence_of_stream_proven',
        'index_request_subtype', 'bulk_frames_waiting_index', 'bulk_frames_post_index_response']);
    }
  }
  for (const name of ['history_index_today', 'history_index_past']) {
    const index = report[name];
    if (index && typeof index === 'object') {
      safe[name] = pick(index, ['query_type', 'query_subtype', 'status', 'health_present', 'file_ids_present', 'file_id_count']);
      safe[name].groups = (Array.isArray(index.groups) ? index.groups : []).slice(0, 256)
        .filter(item => item && typeof item === 'object').map(item => pick(item, ['type', 'subtype', 'detail', 'version', 'count']));
    }
  }
  if (Array.isArray(report.history_files)) safe.history_files = report.history_files.slice(0, 16)
    .filter(item => item && typeof item === 'object').map(item => pick(item, [
      'type', 'subtype', 'detail', 'version', 'bytes', 'chunks', 'declared_chunks', 'transfer_complete',
      'crc_valid', 'file_id_match', 'file_id_timestamp_match', 'file_id_timestamp_relation', 'file_id_timezone_match', 'file_id_version_match',
      'file_id_kind_match', 'expected_id_header_offset', 'received_file_category', 'received_padding_zero',
      'parser_status', 'reserved_header_nibble_ignored', 'detail_records', 'hr_present_records', 'hr_missing_records',
      'hr_presence_basis', 'step_records', 'summary_records', 'steps_present', 'request_receipt_seen_before_complete',
      'hr_encoded_zero_records', 'hr_encoded_255_records', 'step_encoded_zero_records', 'step_encoded_nonzero_records',
      'value_interpretation_status', 'time_granularity_seconds', 'descriptor_minute_aligned', 'descriptor_timezone_in_civil_range',
      'descriptor_signed_unsigned_epoch_agree', 'candidate_time_basis', 'time_mapping_status', 'reference_clock_available',
      'reference_clock_verified', 'candidate_last_record_not_future', 'candidate_epoch_age_band',
      'received_id_in_original_index', 'past_index_snapshot_checked', 'past_index_file_count', 'received_id_in_past_index',
      'requested_index_scope', 'received_id_in_today_index', 'correlation_header_only', 'correlation_file_ordinal',
      'demux_route', 'demux_file_phase', 'demux_file_ordinal',
      'boundary_observation_phase', 'boundary_observation_phase_last_bulk', 'boundary_subtype_first_bulk', 'boundary_subtype_last_bulk', 'boundary_request_ack_seen_first_bulk',
      'boundary_request_ack_seen_last_bulk', 'boundary_file_ordinal', 'boundary_file_partial',
      'today_minimum_differing_id_bytes', 'today_nearest_id_difference_mask', 'today_nearest_id_mask_ambiguous',
      'past_minimum_differing_id_bytes', 'past_nearest_id_difference_mask', 'past_nearest_id_mask_ambiguous',
      'received_id_repeated_in_window',
      'fetch_command_count', 'bulk_frames_before_request', 'bulk_payload_bytes_before_request', 'request_transport_ack_seen_before_complete', 'first_bulk_before_request_receipt',
    ]));
  if (report.hr_settings && typeof report.hr_settings === 'object') {
    const fields = ['disabled', 'interval_value', 'interval_minutes', 'interval_unit', 'monitoring_mode',
      'alarm_high_enabled', 'alarm_high_threshold_bpm', 'advanced_monitoring_enabled',
      'alarm_low_enabled', 'alarm_low_threshold_bpm', 'breathing_score', 'irregular_rhythm_alert', 'sleep_respiratory_rate'];
    safe.hr_settings = pick(report.hr_settings, [...fields, 'query_type', 'query_subtype', 'command_status_present',
      'command_status', 'status', 'health_present', 'heart_rate_present', 'available_intervals_status', 'available_intervals']);
    if (report.hr_settings.presence) safe.hr_settings.presence = pick(report.hr_settings.presence,
      [...fields, 'advanced_monitoring', 'alarm_low']);
  }
  return safe;
}

async function main() {
  const { serial, ...options } = parseReadOnlyArguments(process.argv.slice(2));
  const adb = args => execFileSync('adb', ['-s', serial, ...args], {
    encoding: 'utf8', maxBuffer: 4 * 1024 * 1024, timeout: 15_000,
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  const prepare = () => execFileSync(process.execPath, [
    fileURLToPath(new URL('./band9-prepare-key.mjs', import.meta.url)), serial, 'stage',
  ], { timeout: 35_000, stdio: ['ignore', 'pipe', 'pipe'] });
  const report = await runReadOnlyProbe({ ...options, adb, prepare });
  console.log(JSON.stringify(report, null, 2));
  if (['error', 'cancelled'].includes(report.status)) process.exitCode = 1;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch(error => {
    const safeCodes = new Set(['unsupported_read_only_mode', 'adb_not_authorized',
      'unlock_phone_before_probe', 'another_probe_is_running', 'probe_report_timeout',
      'probe_cleanup_incomplete', 'invalid_history_range', 'unexpected_history_range',
      'production_check_active_or_unreadable', 'usage_serial_mode_optional_batch_offset_count']);
    console.error(safeCodes.has(error?.message) ? error.message : 'read_only_probe_failed_no_sensitive_output');
    process.exitCode = 1;
  });
}
