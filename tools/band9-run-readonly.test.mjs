import { test } from 'node:test';
import assert from 'node:assert/strict';
import { runReadOnlyProbe, parseReadOnlyArguments, projectReadOnlyReport } from './band9-run-readonly.mjs';

function harness({ locked = false, active = false, noFinal = false, prepareFails = false,
  cleanupFails = false, reportStatus = 'ok', mode = 'settings', production = 'absent',
  detailOffset, detailCount, reportOverrides = {}, staleReport } = {}) {
  const calls = [];
  let started = false, finalReads = 0;
  return {
    calls,
    options: {
      mode, detailOffset, detailCount, wait: async () => {},
      prepare: () => {
        calls.push('prepare');
        if (prepareFails) throw new Error('private_prepare_failure');
      },
      adb: args => {
        const command = args.join(' ');
        calls.push(command);
        if (command === 'get-state') return 'device';
        if (command === 'shell dumpsys window policy') {
          return locked ? 'showing=true inputRestricted=true' : 'showing=false inputRestricted=false';
        }
        if (command === 'shell pm path ru.kardiomonitor.bandprotocol.test') return production === 'absent' ? '' : 'package:test.apk';
        if (command.includes('production-check.lock')) {
          if (production === 'unreadable') throw new Error('private_adb_failure');
          return production;
        }
        if (command.includes('cat files/report.json')) {
          if (started && staleReport && finalReads++ === 0) return JSON.stringify(staleReport);
          return JSON.stringify(started
            ? { schema: 20, probe_version: '0.32', mode, detail_offset: detailOffset, detail_count: detailCount,
                status: noFinal ? 'running' : reportStatus, socket_closed: true, ...reportOverrides }
            : { mode: 'live', status: active ? 'running' : 'ok' });
        }
        if (command.includes('am start -W')) started = true;
        if (command.includes('test ! -e files/auth-key') && cleanupFails) throw new Error('still_exists');
        if (command === 'shell pidof com.xiaomi.wearable') return '1234';
        return '';
      },
    },
  };
}

test('read-only runner stages key, reads final report, removes key and restores Mi Fitness', async () => {
  const { options, calls } = harness();
  assert.equal((await runReadOnlyProbe(options)).status, 'ok');
  assert.ok(calls.indexOf('prepare') < calls.indexOf('shell am force-stop com.xiaomi.wearable'));
  assert.ok(calls.some(x => x.includes('rm -f files/auth-key')));
  assert.ok(calls.some(x => x.includes('test ! -e files/auth-key')));
  assert.ok(calls.some(x => x.includes('monkey -p com.xiaomi.wearable')));
  assert.ok(!calls.some(x => x.includes('am force-stop ru.kardiomonitor.band9probe')));
});

test('index and bounded history reading use the same cleanup guarantees', async () => {
  for (const mode of ['history_index', 'history_read', 'history_sample', 'history_sample_next', 'history_repeat']) {
    const { options, calls } = harness({ mode });
    assert.equal((await runReadOnlyProbe(options)).mode, mode);
    assert.ok(calls.some(x => x.endsWith(`--es mode ${mode}`)));
    assert.ok(calls.some(x => x.includes('rm -f files/auth-key')));
    assert.ok(calls.some(x => x.includes('monkey -p com.xiaomi.wearable')));
  }
});

test('locked phone and running probe are rejected before preparing a key', async () => {
  for (const flag of ['locked', 'active']) {
    const { options, calls } = harness({ [flag]: true });
    await assert.rejects(runReadOnlyProbe(options));
    assert.ok(!calls.includes('prepare'));
    assert.ok(!calls.some(x => x.includes('force-stop')));
  }
});

test('write, live and arbitrary modes cannot be launched by read-only runner', async () => {
  for (const mode of ['live', 'set', 'history', 'auth', 'anything']) {
    const { options, calls } = harness();
    await assert.rejects(runReadOnlyProbe({ ...options, mode }), /unsupported_read_only_mode/);
    assert.equal(calls.length, 0);
  }
});

test('explicit batch requires strict range before any adb call and uses typed extras', async () => {
  for (const range of [{}, { detailOffset: 0 }, { detailCount: 2 },
    { detailOffset: '2', detailCount: 2 }, { detailOffset: -1, detailCount: 1 },
    { detailOffset: 256, detailCount: 1 }, { detailOffset: 1.5, detailCount: 1 },
    { detailOffset: 0, detailCount: 0 }, { detailOffset: 0, detailCount: 3 }]) {
    const { options, calls } = harness({ mode: 'history_batch', ...range });
    await assert.rejects(runReadOnlyProbe(options), /invalid_history_range/); assert.equal(calls.length, 0);
  }
  for (const mode of ['settings', 'history_sample_next']) {
    const { options, calls } = harness({ mode, detailOffset: 0 });
    await assert.rejects(runReadOnlyProbe(options), /unexpected_history_range/); assert.equal(calls.length, 0);
  }
  const { options, calls } = harness({ mode: 'history_batch', detailOffset: 2, detailCount: 2 });
  assert.equal((await runReadOnlyProbe(options)).detail_offset, 2);
  assert.ok(calls.some(x => x.endsWith('--es mode history_batch --ei detail_offset 2 --ei detail_count 2')));
  assert.ok(calls.some(x => x.includes('rm -f files/auth-key')));
  assert.ok(calls.some(x => x.includes('monkey -p com.xiaomi.wearable')));
});

test('CLI rejects extra missing malformed range and preserves existing two-argument modes', () => {
  assert.deepEqual(parseReadOnlyArguments(['serial', 'settings']), { serial: 'serial', mode: 'settings' });
  assert.deepEqual(parseReadOnlyArguments(['serial', 'history_batch', '255', '2']),
    { serial: 'serial', mode: 'history_batch', detailOffset: 255, detailCount: 2 });
  for (const args of [[], ['serial'], ['serial', 'settings', '0'], ['serial', 'history_batch'],
    ['serial', 'history_batch', '0'], ['serial', 'history_batch', '0', '1', '2'],
    ['serial', 'history_batch', '02', '1'], ['serial', 'history_batch', '1e2', '1'],
    ['serial', 'history_batch', '0', '1.0'], ['serial', 'history_batch', '256', '1']])
    assert.throws(() => parseReadOnlyArguments(args));
});

test('production lock blocks preparation while missing or idle test package is supported', async () => {
  for (const production of ['locked', 'unreadable']) {
    const { options, calls } = harness({ production });
    await assert.rejects(runReadOnlyProbe(options));
    assert.ok(!calls.includes('prepare') && !calls.some(x => x.includes('force-stop')));
  }
  assert.equal((await runReadOnlyProbe(harness({ production: 'idle' }).options)).status, 'ok');
});

test('stale schema version range and unclosed report cannot be accepted as current batch', async () => {
  const good = { schema: 20, probe_version: '0.32', mode: 'history_batch', detail_offset: 2,
    detail_count: 2, status: 'partial', socket_closed: true };
  for (const change of [{ schema: 5 }, { probe_version: '0.17' }, { schema: 9, probe_version: '0.21' }, { schema: 10, probe_version: '0.22' }, { schema: 11, probe_version: '0.23' }, { schema: 12, probe_version: '0.24' }, { schema: 13, probe_version: '0.25' }, { schema: 14, probe_version: '0.26' }, { schema: 15, probe_version: '0.27' }, { schema: 16, probe_version: '0.28' }, { schema: 17, probe_version: '0.29' }, { schema: 18, probe_version: '0.30' }, { schema: 19, probe_version: '0.31' }, { detail_offset: 0 },
    { detail_count: 1 }, { socket_closed: false }]) {
    const { options } = harness({ mode: 'history_batch', detailOffset: 2, detailCount: 2,
      staleReport: { ...good, ...change }, reportStatus: 'partial' });
    assert.deepEqual(await runReadOnlyProbe(options), good);
    const bad = harness({ mode: 'history_batch', detailOffset: 2, detailCount: 2, reportOverrides: change });
    await assert.rejects(runReadOnlyProbe(bad.options), /probe_report_timeout/);
    assert.ok(bad.calls.some(x => x.includes('rm -f files/auth-key')));
  }
});

test('report projection never publishes numeric history IDs digest packets or unknown fields', () => {
  const report = { mode: 'history_batch', today_steps: 54321, raw: 'private', digest: 'private', live_readings: [75],
    history_files: [{ detail_records: 2, hr_present_records: 2, crc_valid: true, id: 'private', timestamp: 1,
      today_steps: 54321, heart_rate: 75, raw: 'private', digest: 'private' }],
    history_index_today: { file_id_count: 3, ids: ['private'], groups: [{ count: 3, raw: 'private' }] },
    hr_settings: { disabled: false, interval_value: 0, unknown: 'private' } };
  assert.deepEqual(projectReadOnlyReport(report), { mode: 'history_batch',
    history_files: [{ crc_valid: true, detail_records: 2, hr_present_records: 2 }],
    history_index_today: { file_id_count: 3, groups: [{ count: 3 }] },
    hr_settings: { disabled: false, interval_value: 0 } });
});

test('past batch uses explicit mode and same strict CLI range and cleanup', async () => {
  assert.deepEqual(parseReadOnlyArguments(['serial', 'history_past_batch', '0', '2']),
    { serial: 'serial', mode: 'history_past_batch', detailOffset: 0, detailCount: 2 });
  for (const args of [['serial', 'history_past_batch'], ['serial', 'history_past_batch', '-1', '1'],
    ['serial', 'history_past_batch', '0', '3']]) assert.throws(() => parseReadOnlyArguments(args));
  const invalid = harness({ mode: 'history_past_batch', detailOffset: 0 });
  await assert.rejects(runReadOnlyProbe(invalid.options), /invalid_history_range/); assert.equal(invalid.calls.length, 0);
  const { options, calls } = harness({ mode: 'history_past_batch', detailOffset: 0, detailCount: 1,
    reportStatus: 'partial', reportOverrides: { history_requested_index_scope: 'past' } });
  const report = await runReadOnlyProbe(options);
  assert.equal(report.history_requested_index_scope, 'past');
  assert.ok(calls.some(x => x.endsWith('--es mode history_past_batch --ei detail_offset 0 --ei detail_count 1')));
  assert.ok(calls.some(x => x.includes('test ! -e files/auth-key')));
});

test('correlation requires exactly one explicit target and preserves partial header evidence on error', async () => {
  assert.deepEqual(parseReadOnlyArguments(['serial', 'history_correlate', '0', '1']),
    { serial: 'serial', mode: 'history_correlate', detailOffset: 0, detailCount: 1 });
  assert.throws(() => parseReadOnlyArguments(['serial', 'history_correlate', '0', '2']));
  const bad = harness({ mode: 'history_correlate', detailOffset: 0, detailCount: 2 });
  await assert.rejects(runReadOnlyProbe(bad.options), /invalid_history_range/); assert.equal(bad.calls.length, 0);
  const file = { correlation_header_only: true, correlation_file_ordinal: 1, fetch_command_count: 1,
    bulk_frames_before_request: 2, bulk_payload_bytes_before_request: 42, request_transport_ack_seen_before_complete: true,
    first_bulk_before_request_receipt: true, parser_status: 'not_parsed', file_id_match: false };
  const { options, calls } = harness({ mode: 'history_correlate', detailOffset: 0, detailCount: 1, reportStatus: 'error',
    reportOverrides: { code: 'read_deadline', history_correlation_header_only: true,
      history_files: [{ ...file, raw: 'private' }] } });
  const report = await runReadOnlyProbe(options);
  assert.equal(report.status, 'error'); assert.equal(report.history_correlation_header_only, true);
  assert.deepEqual(report.history_files, [file]);
  assert.ok(calls.some(x => x.endsWith('--es mode history_correlate --ei detail_offset 0 --ei detail_count 1')));
  assert.ok(calls.some(x => x.includes('test ! -e files/auth-key')));
});

test('target mode requires exactly one target and exports bounded routes without raw history', async () => {
  assert.deepEqual(parseReadOnlyArguments(['serial', 'history_target', '0', '1']),
    { serial: 'serial', mode: 'history_target', detailOffset: 0, detailCount: 1 });
  for (const args of [['serial', 'history_target'], ['serial', 'history_target', '0', '2'], ['serial', 'history_target', '-1', '1']])
    assert.throws(() => parseReadOnlyArguments(args));
  const bad = harness({ mode: 'history_target', detailOffset: 0, detailCount: 2 });
  await assert.rejects(runReadOnlyProbe(bad.options), /invalid_history_range/); assert.equal(bad.calls.length, 0);
  const files = Array.from({ length: 16 }, (_, i) => ({ demux_route: i === 15 ? 'requested_exact' : 'quarantined',
    demux_file_phase: 'target_wait', demux_file_ordinal: i + 1, parser_status: 'not_parsed', raw: 'private', timestamp: 1 }));
  const { options, calls } = harness({ mode: 'history_target', detailOffset: 0, detailCount: 1, reportStatus: 'partial',
    reportOverrides: { code: 'verified_target_sample', history_target_demultiplexed: true,
      retention_index_checked: true, retention_index_unchanged: true, history_files: [...files, { raw: 'private' }] } });
  const report = await runReadOnlyProbe(options);
  assert.equal(report.status, 'partial'); assert.equal(report.history_target_demultiplexed, true);
  assert.equal(report.retention_index_unchanged, true); assert.equal(report.history_files.length, 16);
  assert.deepEqual(report.history_files[15], { demux_route: 'requested_exact', demux_file_phase: 'target_wait',
    demux_file_ordinal: 16, parser_status: 'not_parsed' });
  assert.ok(calls.some(x => x.endsWith('--es mode history_target --ei detail_offset 0 --ei detail_count 1')));
  assert.ok(calls.some(x => x.includes('test ! -e files/auth-key')));
});

test('index boundary rejects ranges and preserves only bounded ACK and membership diagnostics on failure', async () => {
  assert.deepEqual(parseReadOnlyArguments(['serial', 'history_boundary']), { serial: 'serial', mode: 'history_boundary' });
  assert.throws(() => parseReadOnlyArguments(['serial', 'history_boundary', '0', '1']));
  const bad = harness({ mode: 'history_boundary', detailOffset: 0, detailCount: 1 });
  await assert.rejects(runReadOnlyProbe(bad.options), /unexpected_history_range/); assert.equal(bad.calls.length, 0);
  const boundary = { fetch_command_count: 0, bulk_frames: 2, bulk_payload_bytes: 33, index_response_count: 1,
    complete_file_count: 1, incomplete_file_present: false, status: 'diagnostic_index_boundary',
    today_response_transport_ack_seen: null, past_response_transport_ack_seen: true };
  const file = { boundary_subtype_first_bulk: 2, boundary_subtype_last_bulk: 2, boundary_request_ack_seen_first_bulk: false,
    boundary_request_ack_seen_last_bulk: true, boundary_file_ordinal: 1, boundary_file_partial: false,
    received_id_in_past_index: true, received_id_in_today_index: null, fetch_command_count: 0, parser_status: 'not_parsed' };
  const { options, calls } = harness({ mode: 'history_boundary', reportStatus: 'error', reportOverrides: {
    history_boundary: { ...boundary, id: 'private', bytes: 'private', timestamp: 123 },
    history_files: [{ ...file, id: 'private', raw: 'private', heart_rate: 75 }],
  } });
  const report = await runReadOnlyProbe(options);
  assert.deepEqual(report.history_boundary, boundary); assert.deepEqual(report.history_files, [file]);
  assert.ok(calls.some(x => x.endsWith('--es mode history_boundary')));
  assert.ok(!calls.some(x => x.includes('--ei detail_')));
  assert.ok(calls.some(x => x.includes('test ! -e files/auth-key')));
});

test('passive pre-index mode accepts no ranges and exports nullable ACK and bounded metadata only', async () => {
  assert.deepEqual(parseReadOnlyArguments(['serial', 'history_passive']), { serial: 'serial', mode: 'history_passive' });
  assert.throws(() => parseReadOnlyArguments(['serial', 'history_passive', '0', '1']));
  const bad = harness({ mode: 'history_passive', detailOffset: 0 });
  await assert.rejects(runReadOnlyProbe(bad.options), /unexpected_history_range/); assert.equal(bad.calls.length, 0);
  const passive = { history_command_count: 0, fetch_command_count: 0, index_response_count: 0, complete_file_count: 0,
    bulk_frames: 0, bulk_payload_bytes: 0, incomplete_file_present: false, observation_window_ms: 6000,
    window_elapsed: true, transport_boundary_at_deadline: 'not_verified', absence_of_stream_proven: false,
    today_response_transport_ack_seen: null, past_response_transport_ack_seen: null, status: 'diagnostic_passive_window_elapsed' };
  const file = { boundary_observation_phase: 'pre_index_passive', boundary_subtype_first_bulk: null,
    boundary_request_ack_seen_first_bulk: null, received_id_in_today_index: null, parser_status: 'not_parsed' };
  const { options, calls } = harness({ mode: 'history_passive', reportStatus: 'partial', reportOverrides: {
    history_passive: { ...passive, id: 'private', timestamp: 123, raw: 'private' }, history_files: [{ ...file, raw: 'private' }],
  } });
  const result = await runReadOnlyProbe(options);
  assert.deepEqual(result.history_passive, passive); assert.deepEqual(result.history_files, [file]);
  assert.ok(calls.some(x => x.endsWith('--es mode history_passive')) && !calls.some(x => x.includes('--ei detail_')));
  assert.ok(calls.some(x => x.includes('test ! -e files/auth-key')) && calls.some(x => x.includes('monkey -p com.xiaomi.wearable')));
});

test('past-window mode accepts no ranges and projects post-index phase counters only', async () => {
  assert.deepEqual(parseReadOnlyArguments(['serial', 'history_past_window']), { serial: 'serial', mode: 'history_past_window' });
  assert.throws(() => parseReadOnlyArguments(['serial', 'history_past_window', '0', '1']));
  const bad = harness({ mode: 'history_past_window', detailOffset: 0 });
  await assert.rejects(runReadOnlyProbe(bad.options), /unexpected_history_range/); assert.equal(bad.calls.length, 0);
  const past = { fetch_command_count: 0, bulk_frames: 1, bulk_payload_bytes: 20, index_response_count: 1,
    complete_file_count: 1, incomplete_file_present: false, status: 'diagnostic_past_window_elapsed',
    today_response_transport_ack_seen: null, past_response_transport_ack_seen: true, history_command_count: 1,
    observation_window_ms: 6000, window_elapsed: true, transport_boundary_at_deadline: 'not_verified',
    absence_of_stream_proven: false, index_request_subtype: 2, bulk_frames_waiting_index: 0, bulk_frames_post_index_response: 1 };
  const file = { boundary_observation_phase: 'post_index_response', boundary_observation_phase_last_bulk: 'post_index_response',
    boundary_subtype_first_bulk: 2, boundary_subtype_last_bulk: 2, boundary_request_ack_seen_first_bulk: true,
    received_id_in_past_index: false, received_id_in_today_index: null, parser_status: 'not_parsed' };
  const { options, calls } = harness({ mode: 'history_past_window', reportStatus: 'partial', reportOverrides: {
    history_past_window: { ...past, raw: 'private' }, history_files: [{ ...file, id: 'private' }],
  } });
  const result = await runReadOnlyProbe(options);
  assert.deepEqual(result.history_past_window, past); assert.deepEqual(result.history_files, [file]);
  assert.ok(calls.some(x => x.endsWith('--es mode history_past_window')) && !calls.some(x => x.includes('--ei detail_')));
});

test('today window rejects ranges, projects masks only and preserves cleanup', async () => {
  assert.deepEqual(parseReadOnlyArguments(['serial', 'history_today_window']), { serial: 'serial', mode: 'history_today_window' });
  assert.throws(() => parseReadOnlyArguments(['serial', 'history_today_window', '0', '1']));
  const window = { status: 'diagnostic_today_window_elapsed', index_request_subtype: 1, fetch_command_count: 0 };
  const file = { today_minimum_differing_id_bytes: 1, today_nearest_id_difference_mask: 16,
    today_nearest_id_mask_ambiguous: false, past_minimum_differing_id_bytes: null,
    received_id_repeated_in_window: true, parser_status: 'not_parsed' };
  const { options, calls } = harness({ mode: 'history_today_window', reportStatus: 'partial', reportOverrides: {
    history_today_window: { ...window, raw: 'private' },
    history_files: [{ ...file, id: 'private', nearest_id: 'private', timestamp: 123, digest: 'private' }],
  } });
  const result = await runReadOnlyProbe(options);
  assert.deepEqual(result.history_today_window, window); assert.deepEqual(result.history_files, [file]);
  assert.ok(calls.some(x => x.endsWith('--es mode history_today_window')));
  assert.ok(calls.some(x => x.includes('test ! -e files/auth-key')) && calls.some(x => x.includes('monkey -p com.xiaomi.wearable')));
});

test('target scan requires one explicit target and preserves cleanup on mismatch', async () => {
  assert.deepEqual(parseReadOnlyArguments(['serial', 'history_target_scan', '0', '1']),
    { serial: 'serial', mode: 'history_target_scan', detailOffset: 0, detailCount: 1 });
  for (const args of [['serial', 'history_target_scan'], ['serial', 'history_target_scan', '0', '2']])
    assert.throws(() => parseReadOnlyArguments(args));
  const { options, calls } = harness({ mode: 'history_target_scan', detailOffset: 0, detailCount: 1,
    reportStatus: 'error', reportOverrides: { code: 'history_target_not_received' } });
  assert.equal((await runReadOnlyProbe(options)).code, 'history_target_not_received');
  assert.ok(calls.some(x => x.endsWith('--es mode history_target_scan --ei detail_offset 0 --ei detail_count 1')));
  assert.ok(calls.some(x => x.includes('test ! -e files/auth-key')) && calls.some(x => x.includes('monkey -p com.xiaomi.wearable')));
});

test('past-only target scan enforces one explicit target and safe cleanup', async () => {
  assert.deepEqual(parseReadOnlyArguments(['serial', 'history_past_target_scan', '0', '1']),
    { serial: 'serial', mode: 'history_past_target_scan', detailOffset: 0, detailCount: 1 });
  assert.throws(() => parseReadOnlyArguments(['serial', 'history_past_target_scan', '0', '2']));
  const { options, calls } = harness({ mode: 'history_past_target_scan', detailOffset: 0, detailCount: 1, reportStatus: 'partial' });
  assert.equal((await runReadOnlyProbe(options)).mode, 'history_past_target_scan');
  assert.ok(calls.some(x => x.endsWith('--es mode history_past_target_scan --ei detail_offset 0 --ei detail_count 1')));
  assert.ok(calls.some(x => x.includes('test ! -e files/auth-key')) && calls.some(x => x.includes('monkey -p com.xiaomi.wearable')));
});

test('past aggregate reader requires one target and exports counts and retention without raw history or false coverage', async () => {
  assert.deepEqual(parseReadOnlyArguments(['serial', 'history_past_target', '0', '1']),
    { serial: 'serial', mode: 'history_past_target', detailOffset: 0, detailCount: 1 });
  for (const args of [['serial', 'history_past_target'], ['serial', 'history_past_target', '0', '2']])
    assert.throws(() => parseReadOnlyArguments(args));
  const file = { parser_status: 'daily_details_v4', detail_records: 2, hr_present_records: 2,
    hr_missing_records: 0, step_records: 2, crc_valid: true, file_id_match: true };
  const { options, calls } = harness({ mode: 'history_past_target', detailOffset: 0, detailCount: 1, reportStatus: 'partial', reportOverrides: {
    code: 'verified_target_sample', retention_index_checked: true, retention_index_unchanged: true,
    history_time_values_verified: false, history_full_coverage_verified: false,
    history_files: [{ ...file, raw: 'private', id: 'private', timestamp: 123, heart_rate: 75, steps: 12 }],
  } });
  const result = await runReadOnlyProbe(options);
  assert.deepEqual(result.history_files, [file]);
  assert.equal(result.history_time_values_verified, false); assert.equal(result.history_full_coverage_verified, false);
  assert.equal(result.retention_index_unchanged, true); assert.equal(result.status, 'partial');
  assert.ok(calls.some(x => x.endsWith('--es mode history_past_target --ei detail_offset 0 --ei detail_count 1')));
  assert.ok(calls.some(x => x.includes('test ! -e files/auth-key')) && calls.some(x => x.includes('monkey -p com.xiaomi.wearable')));
});

test('past repeat requires one explicit target and projects equality booleans but no digest or identifiers', async () => {
  assert.deepEqual(parseReadOnlyArguments(['serial', 'history_past_repeat', '0', '1']),
    { serial: 'serial', mode: 'history_past_repeat', detailOffset: 0, detailCount: 1 });
  for (const args of [['serial', 'history_past_repeat'], ['serial', 'history_past_repeat', '0', '2'],
    ['serial', 'history_past_repeat', '-1', '1'], ['serial', 'history_past_repeat', '256', '1']])
    assert.throws(() => parseReadOnlyArguments(args));
  const { options, calls } = harness({ mode: 'history_past_repeat', detailOffset: 0, detailCount: 1,
    reportStatus: 'partial', reportOverrides: { code: 'verified_target_repeat_sample', repeat_requested: true,
      repeat_read_count: 2, repeat_aggregate_consistent: true, repeat_bytes_identical: true,
      history_time_values_verified: false, history_full_coverage_verified: false,
      retention_index_checked: true, retention_index_unchanged: true, digest: 'private',
      history_files: [{ parser_status: 'daily_details_v4', fetch_command_count: 2, demux_file_phase: 'repeat_wait',
        sha256: 'private', file_id: 'private', first_digest: 'private' }] } });
  const report = await runReadOnlyProbe(options);
  assert.equal(report.status, 'partial'); assert.equal(report.repeat_read_count, 2);
  assert.equal(report.repeat_bytes_identical, true); assert.equal(report.repeat_aggregate_consistent, true);
  assert.equal(report.history_time_values_verified, false); assert.equal(report.history_full_coverage_verified, false);
  assert.ok(!Object.hasOwn(report, 'digest'));
  assert.deepEqual(report.history_files, [{ parser_status: 'daily_details_v4', fetch_command_count: 2, demux_file_phase: 'repeat_wait' }]);
  assert.ok(calls.some(x => x.endsWith('--es mode history_past_repeat --ei detail_offset 0 --ei detail_count 1')));
  assert.ok(calls.some(x => x.includes('test ! -e files/auth-key')) && calls.some(x => x.includes('monkey -p com.xiaomi.wearable')));
});

test('failed repeat preserves unknown equality and a completed-read count without a false pass', async () => {
  const { options } = harness({ mode: 'history_past_repeat', detailOffset: 0, detailCount: 1, reportStatus: 'error',
    reportOverrides: { code: 'history_file_crc', repeat_requested: true, repeat_read_count: 1,
      repeat_bytes_identical: null, repeat_aggregate_consistent: null, retention_index_checked: false } });
  const report = await runReadOnlyProbe(options);
  assert.equal(report.status, 'error'); assert.equal(report.repeat_read_count, 1);
  assert.equal(report.repeat_bytes_identical, null); assert.equal(report.repeat_aggregate_consistent, null);
  assert.equal(report.retention_index_checked, false);
});

test('time and value diagnostics project only coarse metadata never dates identifiers or individual readings', () => {
  const safe = { parser_status: 'daily_details_v4', hr_encoded_zero_records: 1, hr_encoded_255_records: 1,
    step_encoded_zero_records: 2, step_encoded_nonzero_records: 2,
    value_interpretation_status: 'source_encoding_only_not_measurement_validation', time_granularity_seconds: 60,
    descriptor_minute_aligned: true, descriptor_timezone_in_civil_range: true, descriptor_signed_unsigned_epoch_agree: true,
    candidate_time_basis: 'unsigned_epoch_seconds_no_timezone_adjustment', time_mapping_status: 'source_hypothesis_not_independently_verified',
    reference_clock_available: false, reference_clock_verified: false, candidate_last_record_not_future: null,
    candidate_epoch_age_band: 'reference_unavailable' };
  const projected = projectReadOnlyReport({ history_files: [{ ...safe, epoch_seconds: 123, timestamp: 123,
    timezone: 28, reference_time_millis: 456, last_record_time: 789, file_id: 'private', raw: 'private',
    heart_rate: 70, steps: 42, records: [{ heart_rate: 70, steps: 42 }] }] });
  assert.deepEqual(projected.history_files, [safe]);
});

test('provenance is exported as membership booleans and counts only even on file mismatch', async () => {
  const file = { crc_valid: true, file_id_match: false, parser_status: 'not_parsed',
    received_id_in_original_index: false, past_index_snapshot_checked: true, past_index_file_count: 10,
    received_id_in_past_index: true };
  const { options } = harness({ mode: 'history_batch', detailOffset: 0, detailCount: 1, reportStatus: 'error',
    reportOverrides: { code: 'history_file_id_mismatch', history_files: [{ ...file, past_ids: ['private'], raw_id: 'private' }] } });
  const report = await runReadOnlyProbe(options);
  assert.equal(report.status, 'error'); assert.deepEqual(report.history_files, [file]);
});

test('partially failed key preparation still deletes the temporary key', async () => {
  const { options, calls } = harness({ prepareFails: true });
  await assert.rejects(runReadOnlyProbe(options), /private_prepare_failure/);
  assert.ok(calls.some(x => x.includes('rm -f files/auth-key')));
  assert.ok(!calls.some(x => x.includes('force-stop com.xiaomi.wearable')));
});

test('timeout aborts only the read-only probe, cleans key and restores Mi Fitness', async () => {
  const { options, calls } = harness({ noFinal: true });
  await assert.rejects(runReadOnlyProbe(options), /probe_report_timeout/);
  assert.ok(calls.some(x => x.includes('force-stop ru.kardiomonitor.band9probe')));
  assert.ok(calls.some(x => x.includes('rm -f files/auth-key')));
  assert.ok(calls.some(x => x.includes('monkey -p com.xiaomi.wearable')));
});

test('error report still triggers cleanup, and cleanup failure cannot look successful', async () => {
  const first = harness({ reportStatus: 'error' });
  assert.equal((await runReadOnlyProbe(first.options)).status, 'error');
  assert.ok(first.calls.some(x => x.includes('rm -f files/auth-key')));
  await assert.rejects(runReadOnlyProbe(harness({ cleanupFails: true }).options), /probe_cleanup_incomplete/);
});
