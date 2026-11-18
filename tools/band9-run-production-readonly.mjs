import { execFileSync } from 'node:child_process';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { extractBand9Key } from './band9-prepare-key.mjs';

const testPackage = 'ru.kardiomonitor.bandprotocol.test';
const testClass = 'ru.kardiomonitor.bandprotocol.xiaomi.XiaomiReadOnlyHardwareTest';

function miFitnessEnabledSetting(adb) {
  const state = adb(['shell', 'dumpsys', 'package', 'com.xiaomi.wearable']);
  const match = /^\s*User 0:[^\r\n]*\benabled=([0-9]+)\b/m.exec(state);
  return match ? Number(match[1]) : null;
}

const historyReferenceKeys = ['epoch_seconds', 'utc_offset_seconds', 'heart_rate', 'steps'];
const historyReferenceMaxBytes = 1024;

export function validateHistoryReference(reference) {
  const invalid = () => { throw new Error('invalid_history_reference'); };
  if (reference === null || typeof reference !== 'object' || Array.isArray(reference)) invalid();
  const keys = Reflect.ownKeys(reference);
  if (keys.length !== historyReferenceKeys.length || keys.some(key => !historyReferenceKeys.includes(key))) invalid();
  const result = {};
  for (const key of historyReferenceKeys) {
    const property = Object.getOwnPropertyDescriptor(reference, key);
    if (!property || !('value' in property)) invalid();
    result[key] = property.value;
  }
  const integerInRange = (value, min, max) => Number.isSafeInteger(value) && value >= min && value <= max;
  if (!integerInRange(result.epoch_seconds, 0, 2147483647) || result.epoch_seconds % 60 !== 0 ||
      !integerInRange(result.utc_offset_seconds, -43200, 50400) || result.utc_offset_seconds % 900 !== 0 ||
      (result.heart_rate !== null && !integerInRange(result.heart_rate, 1, 254)) ||
      (result.steps !== null && !integerInRange(result.steps, 0, 16383)) ||
      (result.heart_rate === null && result.steps === null)) invalid();
  if (Buffer.byteLength(JSON.stringify(result)) > historyReferenceMaxBytes) invalid();
  return result;
}

export async function readHistoryReference(input = process.stdin) {
  try {
    const chunks = [];
    let bytes = 0;
    for await (const chunk of input) {
      const buffer = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk);
      bytes += buffer.length;
      if (bytes > historyReferenceMaxBytes) throw new Error('invalid_history_reference');
      chunks.push(buffer);
    }
    return validateHistoryReference(JSON.parse(Buffer.concat(chunks).toString('utf8')));
  } catch {
    throw new Error('invalid_history_reference');
  }
}

export function parseProductionReadOnlyArguments(args) {
  const values = [...args];
  const pauseMiFitness = values.at(-1) === '--pause-mi-fitness';
  if (pauseMiFitness) values.pop();
  const [serial, mode, offset] = values;
  if (!serial || serial.startsWith('--')) throw new Error('serial_required');
  if (values.length === 1) return { serial, pauseMiFitness };
  const referenceMode = mode === 'history_reference' || mode === 'history_reference_today';
  const todayOption = mode === 'history_reference_today' ? { referenceIndex: 'today' } : {};
  if (values.length === 3 && referenceMode && offset === 'auto')
    return { serial, pauseMiFitness, referenceRequested: true, selectReferenceTime: true, ...todayOption };
  if (values.length !== 3 || (!referenceMode && mode !== 'history_repeat') || !/^(0|[1-9][0-9]*)$/.test(offset) || Number(offset) > 255)
    throw new Error('usage_serial_optional_history_repeat_or_reference_offset_and_pause');
  return { serial, historyOffset: Number(offset), pauseMiFitness,
    ...(referenceMode ? { referenceRequested: true } : {}), ...todayOption };
}

/** Isolated library instrumentation; optional bounded aggregate verification, never clinical import/live/SET. */
export function runProductionReadOnly({ adb, apkPath, historyOffset, historyReference, selectReferenceTime = false,
  referenceIndex = 'past', pauseMiFitness = false }) {
  if (typeof pauseMiFitness !== 'boolean') throw new Error('invalid_pause_option');
  if (typeof selectReferenceTime !== 'boolean') throw new Error('invalid_reference_time_selection');
  if (referenceIndex !== 'past' && referenceIndex !== 'today') throw new Error('invalid_reference_index');
  if (historyOffset !== undefined && (!Number.isInteger(historyOffset) || historyOffset < 0 || historyOffset > 255))
    throw new Error('invalid_history_offset');
  const referenceRequested = historyReference !== undefined;
  if (referenceIndex === 'today' && !referenceRequested) throw new Error('today_index_requires_reference');
  if (selectReferenceTime && (!referenceRequested || historyOffset !== undefined))
    throw new Error('reference_time_selection_requires_reference_without_offset');
  if (referenceRequested && historyOffset === undefined && !selectReferenceTime) throw new Error('history_reference_requires_offset');
  const reference = referenceRequested ? validateHistoryReference(historyReference) : null;
  const historyRequested = historyOffset !== undefined || selectReferenceTime;
  if (adb(['get-state']).trim() !== 'device') throw new Error('adb_not_authorized');
  const lock = adb(['shell', 'dumpsys', 'window', 'policy']);
  if (!/showing=false/.test(lock) || /inputRestricted=true/.test(lock)) throw new Error('unlock_phone');
  let previous = null;
  try {
    previous = JSON.parse(adb(['shell', 'run-as', 'ru.kardiomonitor.band9probe', 'cat', 'files/report.json']));
  } catch { /* No diagnostic app/report is allowed. Never print adb failures. */ }
  if (previous?.status === 'running') throw new Error('diagnostic_probe_running');
  let productionCheckRunning = false;
  try {
    adb(['shell', 'run-as', testPackage, 'test', '-d', 'files/production-check.lock']);
    productionCheckRunning = true;
  } catch { /* Missing test package or absent lock: no production check owns it. */ }
  if (productionCheckRunning) throw new Error('production_check_already_running');
  const fitnessState = pauseMiFitness ? miFitnessEnabledSetting(adb) : null;
  if (pauseMiFitness && fitnessState !== 0 && fitnessState !== 1) throw new Error('mi_fitness_not_enabled');

  adb(['install', '-r', '-g', apkPath]);
  let acquired = false;
  let fitnessStopped = false;
  let attempted = false;
  let passed = false;
  let failureStage = 'acquire_lock';
  let hardwarePhase = null;
  let hardwareChecks = null;
  let historyReferenceComparison = { record_located: null, timezone_matches: null, heart_rate_matches: null, steps_match: null };
  let historyDiagnostics = { stage: null, selection: null };
  let historySelectionEvidence = null;
  let openFailurePhase = null;
  let hardwareReportSuccessful = false;
  let cleanupOk = true;
  try {
    adb(['shell', 'run-as', testPackage, 'sh', '-c', "'umask 077; mkdir -p files && mkdir files/production-check.lock'"]);
    acquired = true;
    failureStage = 'prepare_key';
    const key = extractBand9Key(adb([
      'shell', 'cat', '/sdcard/Android/data/com.xiaomi.wearable/files/log/XiaomiFit.main.log',
    ]));
    failureStage = 'stage_key';
    adb(['shell', 'run-as', testPackage, 'sh', '-c',
      "'umask 077; cat > files/auth-key && chmod 600 files/auth-key'"], key);
    if (referenceRequested) {
      failureStage = 'stage_history_reference';
      adb(['shell', 'run-as', testPackage, 'sh', '-c',
        "'umask 077; cat > files/history-reference.json && chmod 600 files/history-reference.json'"], JSON.stringify(reference));
    }
    adb(['shell', 'run-as', testPackage, 'rm', '-f', 'files/readonly-result.json']);
    failureStage = 'pause_mi_fitness';
    fitnessStopped = true;
    if (pauseMiFitness) {
      adb(['shell', 'pm', 'disable-user', '--user', '0', 'com.xiaomi.wearable']);
      if (miFitnessEnabledSetting(adb) !== 3) throw new Error('mi_fitness_pause_failed');
    } else {
      adb(['shell', 'am', 'force-stop', 'com.xiaomi.wearable']);
    }
    attempted = true;
    failureStage = 'production_instrumentation';
    const result = adb(['shell', 'am', 'instrument', '-w', '-e', 'class', testClass,
      ...(historyRequested ? ['-e', 'verify_past_repeat', 'true'] : []),
      ...(historyOffset !== undefined ? ['-e', 'history_offset', String(historyOffset)] : []),
      ...(referenceRequested ? ['-e', 'verify_history_reference', 'true'] : []),
      ...(selectReferenceTime ? ['-e', 'history_select_reference_time', 'true'] : []),
      ...(referenceIndex === 'today' ? ['-e', 'history_reference_index', 'today'] : []),
      `${testPackage}/android.test.InstrumentationTestRunner`]);
    passed = /OK \(1 test\)/.test(result) && !/FAILURES|INSTRUMENTATION_FAILED|shortMsg=/.test(result);
    try {
      const report = JSON.parse(adb(['shell', 'run-as', testPackage, 'cat', 'files/readonly-result.json']));
      const allowed = new Set(['key', 'bond', 'open', 'battery', 'settings', 'history', 'close']);
      hardwarePhase = allowed.has(report.failure_phase) ? report.failure_phase : null;
      const openPhases = new Set(['socket_connect', 'version', 'session', 'auth_challenge',
        'watch_proof', 'auth_confirm', 'device_info', 'identity_guard']);
      openFailurePhase = openPhases.has(report.open_failure_phase) ? report.open_failure_phase : null;
      if (report.schema_version === (referenceRequested ? 3 : historyRequested ? 2 : 1) &&
          (!historyRequested || report.history_requested === true)) {
        const referenceIndexMatches = referenceIndex === 'today' ? report.history_index === 'today' :
          report.history_index === undefined || report.history_index === null || report.history_index === 'past';
        hardwareReportSuccessful = report.failure_phase === null && report.open_failure_phase === null &&
          (!referenceRequested || referenceIndexMatches);
        hardwareChecks = Object.fromEntries([
          'test_passed', 'key_consumed', 'permissions_granted', 'bond_unique', 'client_created',
          'open_completed', 'guard_verified', 'battery_completed', 'settings_completed',
          'client_close_completed', 'key_removed', 'battery_present',
          'settings_disabled_present', 'settings_interval_present',
          ...(historyRequested ? ['history_requested', 'history_completed', 'history_retention_unchanged',
            'history_repeated_bytes_identical', 'history_aggregates_consistent', 'history_import_blocked'] : []),
          ...(referenceRequested ? ['history_reference_requested', 'history_reference_matched', 'history_reference_removed'] : []),
        ].map(name => [name, report[name] === true]));
        if (referenceRequested) {
          const stages = new Set(['index', 'selection', 'first_transfer', 'repeat_transfer', 'retention', 'boundary', 'complete']);
          const selections = new Set(['selected', 'empty', 'unsupported_epoch', 'unaligned_epoch',
            'timezone_unavailable', 'no_preceding_file', 'outside_window', 'ambiguous']);
          historyDiagnostics = {
            stage: stages.has(report.history_stage) ? report.history_stage : null,
            selection: selections.has(report.history_selection) ? report.history_selection : null,
          };
          historyReferenceComparison = Object.fromEntries(Object.keys(historyReferenceComparison).map(name => {
            const value = report[`history_reference_${name}`];
            return [name, typeof value === 'boolean' ? value : null];
          }));
          const referenceClockRelations = new Set(['future', 'within_5m', 'within_1h', 'within_24h', 'older']);
          const candidateClockRelations = new Set(['all_not_future', 'some_future', 'all_future']);
          const afterReferenceGaps = new Set(['under_5m', 'under_1h', 'under_24h', 'at_least_24h']);
          historySelectionEvidence = {
            reference_clock_relation: referenceClockRelations.has(report.history_reference_clock_relation) ? report.history_reference_clock_relation : null,
            candidate_clock_relation: candidateClockRelations.has(report.history_candidate_clock_relation) ? report.history_candidate_clock_relation : null,
            after_reference_gap: afterReferenceGaps.has(report.history_after_reference_gap) ? report.history_after_reference_gap : null,
            after_reference_same_local_day: typeof report.history_after_reference_same_local_day === 'boolean' ? report.history_after_reference_same_local_day : null,
          };
        }
      }
    } catch { /* Missing report is not proof that hardware operations passed. */ }
    passed = passed && hardwareReportSuccessful && hardwareChecks !== null && Object.values(hardwareChecks).every(Boolean);
  } catch {
    passed = false;
  } finally {
    if (acquired) {
      // This package contains only read-only instrumentation, not a live sensor session.
      if (attempted) {
        try { adb(['shell', 'am', 'force-stop', testPackage]); } catch { cleanupOk = false; }
      }
      for (const file of ['files/auth-key', ...(referenceRequested ? ['files/history-reference.json'] : [])]) {
        try { adb(['shell', 'run-as', testPackage, 'rm', '-f', file]); } catch { cleanupOk = false; }
        try { adb(['shell', 'run-as', testPackage, 'test', '!', '-e', file]); } catch { cleanupOk = false; }
      }
      try { adb(['shell', 'run-as', testPackage, 'rmdir', 'files/production-check.lock']); } catch { cleanupOk = false; }
    }
    if (fitnessStopped) {
      if (pauseMiFitness) {
        try {
          adb(['shell', 'pm', fitnessState === 0 ? 'default-state' : 'enable', '--user', '0', 'com.xiaomi.wearable']);
          if (miFitnessEnabledSetting(adb) !== fitnessState) cleanupOk = false;
        } catch { cleanupOk = false; }
      }
      try {
        adb(['shell', 'monkey', '-p', 'com.xiaomi.wearable', '-c', 'android.intent.category.LAUNCHER', '1']);
        if (!adb(['shell', 'pidof', 'com.xiaomi.wearable']).trim()) cleanupOk = false;
      } catch { cleanupOk = false; }
    }
  }
  return {
    production_readonly_test_passed: passed,
    failure_stage: passed ? null : failureStage,
    hardware_failure_phase: hardwarePhase,
    open_failure_phase: openFailurePhase,
    hardware_checks: hardwareChecks,
    cleanup_verified: cleanupOk && acquired,
    mi_fitness_process_restored: fitnessStopped && cleanupOk,
    mi_fitness_bluetooth_reconnection: 'not_verified',
    mi_fitness_pause_requested: pauseMiFitness,
    history_live_settings_write_tested: false,
    bounded_history_verification_requested: historyRequested,
    ...(referenceRequested ? { history_reference_requested: true, history_reference_comparison: historyReferenceComparison,
      history_diagnostics: historyDiagnostics } : {}),
    ...(referenceIndex === 'today' ? { history_reference_index: 'today' } : {}),
    ...(historySelectionEvidence !== null ? { history_selection_evidence: historySelectionEvidence } : {}),
    clinical_history_import_tested: false,
  };
}

async function main() {
  const { serial, historyOffset, pauseMiFitness, referenceRequested, selectReferenceTime, referenceIndex } = parseProductionReadOnlyArguments(process.argv.slice(2));
  const historyReference = referenceRequested ? await readHistoryReference() : undefined;
  const adb = (args, input) => execFileSync('adb', ['-s', serial, ...args], {
    encoding: 'utf8', maxBuffer: 24 * 1024 * 1024, timeout: 120_000,
    input, stdio: ['pipe', 'pipe', 'pipe'],
  });
  const apkPath = fileURLToPath(new URL(
    '../android/band-protocol/build/outputs/apk/androidTest/debug/band-protocol-debug-androidTest.apk', import.meta.url,
  ));
  const report = runProductionReadOnly({ adb, apkPath, historyOffset, historyReference, selectReferenceTime, referenceIndex, pauseMiFitness });
  console.log(JSON.stringify(report));
  if (!report.production_readonly_test_passed || !report.cleanup_verified) process.exitCode = 1;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try { await main(); } catch {
    console.error('Production read-only check blocked or failed; no keys, device addresses or raw responses printed.');
    process.exitCode = 1;
  }
}
