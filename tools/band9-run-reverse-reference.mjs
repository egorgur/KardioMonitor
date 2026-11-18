import { extractBand9Key } from './band9-prepare-key.mjs';

const testPackage = 'ru.kardiomonitor.bandprotocol.test';
const testClass = 'ru.kardiomonitor.bandprotocol.xiaomi.XiaomiReverseReferenceHardwareTest#testSampleBeforeReference';
const lockDirectory = 'files/production-check.lock';
const resultFile = 'files/reverse-result.json';
const privateFiles = ['files/auth-key', 'files/reverse-request.json', 'files/reverse-capture.signal'];
const stages = new Set(['prepare', 'open', 'history', 'sample_ready', 'await_reference', 'capture', 'complete', 'failed']);
const failureStages = new Set(['prepare', 'open', 'history', 'close', 'await_reference', 'capture', 'comparison', 'cleanup']);
const booleanChecks = ['test_passed', 'sample_verified', 'sample_repeat_verified', 'sample_retention_unchanged',
  'client_closed_before_reference', 'key_removed', 'request_removed', 'signal_removed', 'reference_captured',
  'clinical_import_blocked', 'sample_memory_cleared'];
const nullableChecks = ['reference_record_located', 'reference_timezone_matches', 'reference_heart_rate_matches', 'reference_steps_match'];
const readyChecks = ['sample_verified', 'sample_repeat_verified', 'sample_retention_unchanged',
  'client_closed_before_reference', 'key_removed', 'request_removed', 'clinical_import_blocked'];
const reportKeys = ['schema_version', 'stage', 'failure_stage', ...booleanChecks, ...nullableChecks];
const windowRelations = new Set(['before', 'inside', 'after', 'empty', 'unsupported_time']);

function validDay(day) {
  if (typeof day !== 'string' || !/^[0-9]{4}-[0-9]{2}-[0-9]{2}$/.test(day)) return false;
  const [year, month, date] = day.split('-').map(Number);
  const leap = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0);
  const days = [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
  return year >= 1 && month >= 1 && month <= 12 && date >= 1 && date <= days[month - 1];
}

function parseReport(text, latest) {
  if (typeof text !== 'string' || Buffer.byteLength(text) > 4096) throw new Error('invalid_report');
  const report = JSON.parse(text);
  const keys = latest ? [...reportKeys, 'sample_selection', 'reference_window_relation'] : reportKeys;
  if (!report || Array.isArray(report) || typeof report !== 'object' ||
      Object.keys(report).length !== keys.length || Object.keys(report).some(key => !keys.includes(key)) ||
      report.schema_version !== (latest ? 2 : 1) ||
      (latest && (report.sample_selection !== 'latest' ||
        (report.reference_window_relation !== null && !windowRelations.has(report.reference_window_relation)))) ||
      !stages.has(report.stage) ||
      (report.failure_stage !== null && !failureStages.has(report.failure_stage)) ||
      booleanChecks.some(key => typeof report[key] !== 'boolean') ||
      nullableChecks.some(key => report[key] !== null && typeof report[key] !== 'boolean')) throw new Error('invalid_report');
  return report;
}

function instrumentationPassed(output) {
  return typeof output === 'string' && (output.match(/^\s*OK \(1 test\)\s*$/gm)?.length === 1) &&
    !/FAILURES|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|shortMsg=|Process crashed|Error:|Exception/.test(output) &&
    !/^INSTRUMENTATION_STATUS_CODE:\s*-[2-9][0-9]*\s*$/m.test(output) &&
    [...output.matchAll(/^INSTRUMENTATION_CODE:\s*([^\r\n]+)$/gm)].every(match => match[1].trim() === '-1');
}

/**
 * Two-stage, isolated, RAM-only hardware check. The caller supplies the isolated
 * band-protocol androidTest APK and owns serial/transport selection. adb is sync;
 * both transports MUST capture, never print, stdout/stderr (including exceptions).
 * startInstrumentation(args) returns { completed: Promise<string>, stop() }.
 * stop() terminates only that host transport, not unrelated adb processes.
 * No keys, dates, readings, IDs, or raw child output are returned or logged here.
 *
 * onSampleReady(safeStatus) runs once, after exact Mi Fitness restoration/launch.
 * Returning from it does NOT request capture or finish the check. Once the user
 * opens the tooltip, the owner must explicitly send ASCII 'capture' over stdin:
 * adb(['shell', 'run-as', 'ru.kardiomonitor.bandprotocol.test', 'sh', '-c',
 *   "'umask 077; cat > files/reverse-capture.signal && chmod 600 files/reverse-capture.signal'"], 'capture');
 * Only do this while this run owns the lock and has called onSampleReady. Never
 * create the signal before readiness, in another run, or automatically on a timer.
 * Startup is bounded to 90s; readiness allows 300s user wait + 30s capture/cleanup.
 * historySelection defaults to 'offset' with historyOffset defaulting to 0.
 * Opt-in 'latest' forbids any own historyOffset property (even 0/undefined), sends
 * only {day, selection:'latest'}, and requires schema2 with matching selection.
 * Its safe window relation may be null at readiness; success requires 'inside'.
 * Offset mode retains the exact schema1 report and output shape.
 * Invalid options throw a generic error before adb; other failures return safe
 * metadata. A comparison failure remains distinct from host infrastructure failure.
 */
export async function runReverseReference(options = {}) {
  if (options === null || typeof options !== 'object' || Array.isArray(options))
    throw new Error('reverse_reference_invalid_arguments');
  const { adb, startInstrumentation, apkPath, expectedDay, historyOffset = 0,
    historySelection = 'offset', pauseMiFitness = false, onSampleReady } = options;
  const latest = historySelection === 'latest';
  if ((historySelection !== 'offset' && !latest) || (latest && Object.hasOwn(options, 'historyOffset')) ||
      typeof adb !== 'function' || typeof startInstrumentation !== 'function' || !validDay(expectedDay) ||
      typeof apkPath !== 'string' || !apkPath.trim() || apkPath.startsWith('-') || /[\0\r\n]/.test(apkPath) ||
      !Number.isInteger(historyOffset) || historyOffset < 0 || historyOffset > 255 ||
      typeof pauseMiFitness !== 'boolean' || (onSampleReady !== undefined && typeof onSampleReady !== 'function'))
    throw new Error('reverse_reference_invalid_arguments');

  const privateCommand = (...args) => adb(['shell', 'run-as', testPackage, ...args]);
  const fitnessSetting = () => {
    const state = adb(['shell', 'dumpsys', 'package', 'com.xiaomi.wearable']);
    const match = /^\s*User 0:[^\r\n]*\benabled=([0-9]+)\b/m.exec(state);
    return match ? Number(match[1]) : null;
  };
  let owned = false;
  let attempted = false;
  let fitnessTouched = false;
  let originalSetting;
  let restored = false;
  let cleanup = true;
  let failure = 'prepare';
  let report = null;
  let child;
  let completion = null;
  let completed;
  let ready = false;
  let callbackFailure = false;
  let wakeCallback;
  const callbackFailed = new Promise(resolve => { wakeCallback = resolve; });
  const restoreFitness = () => {
    let ok = true;
    if (pauseMiFitness) {
      try { adb(['shell', 'pm', originalSetting === 0 ? 'default-state' : 'enable', '--user', '0', 'com.xiaomi.wearable']); }
      catch { ok = false; }
      try { if (fitnessSetting() !== originalSetting) ok = false; } catch { ok = false; }
    }
    try { adb(['shell', 'monkey', '-p', 'com.xiaomi.wearable', '-c', 'android.intent.category.LAUNCHER', '1']); }
    catch { ok = false; }
    try { if (!adb(['shell', 'pidof', 'com.xiaomi.wearable']).trim()) ok = false; } catch { ok = false; }
    restored = ok;
    if (!ok) cleanup = false;
    return ok;
  };
  try {
    if (adb(['get-state']).trim() !== 'device') throw new Error();
    const policy = adb(['shell', 'dumpsys', 'window', 'policy']);
    if (!/\bshowing=false\b/.test(policy) || /\bshowing=true\b|\binputRestricted=true\b/.test(policy)) throw new Error();
    let probe;
    try { probe = JSON.parse(adb(['shell', 'run-as', 'ru.kardiomonitor.band9probe', 'cat', 'files/report.json'])); }
    catch { /* Optional diagnostic package/report may be absent. */ }
    if (probe?.status === 'running') throw new Error();
    let locked = false;
    try { privateCommand('test', '-e', lockDirectory); locked = true; }
    catch { /* Atomic mkdir below remains the ownership gate. */ }
    if (locked) throw new Error();
    originalSetting = fitnessSetting();
    if (originalSetting !== 0 && originalSetting !== 1) throw new Error();
    adb(['install', '-r', '-g', apkPath]);
    privateCommand('sh', '-c', "'umask 077; mkdir -p files && mkdir files/production-check.lock'");
    owned = true;
    privateCommand('rm', '-f', resultFile, ...privateFiles);
    const key = extractBand9Key(adb(['shell', 'cat', '/sdcard/Android/data/com.xiaomi.wearable/files/log/XiaomiFit.main.log']));
    adb(['shell', 'run-as', testPackage, 'sh', '-c',
      "'umask 077; cat > files/auth-key && chmod 600 files/auth-key'"], key);
    adb(['shell', 'run-as', testPackage, 'sh', '-c',
      "'umask 077; cat > files/reverse-request.json && chmod 600 files/reverse-request.json'"],
    JSON.stringify(latest ? { day: expectedDay, selection: 'latest' } : { day: expectedDay, offset: historyOffset }));
    fitnessTouched = true;
    if (pauseMiFitness) {
      adb(['shell', 'pm', 'disable-user', '--user', '0', 'com.xiaomi.wearable']);
      if (fitnessSetting() !== 3) throw new Error();
    } else adb(['shell', 'am', 'force-stop', 'com.xiaomi.wearable']);
    attempted = true;
    child = startInstrumentation(['shell', 'am', 'instrument', '-w', '-e', 'class', testClass,
      `${testPackage}/android.test.InstrumentationTestRunner`]);
    if (!child || !(child.completed instanceof Promise)) throw new Error();
    completed = child.completed.then(output => { completion = { ok: instrumentationPassed(output) }; },
      () => { completion = { ok: false }; });
    if (typeof child.stop !== 'function') throw new Error();
    let deadline = Date.now() + 90_000;
    while (true) {
      await Promise.resolve();
      if (callbackFailure) { failure = 'await_reference'; throw new Error(); }
      if (Date.now() >= deadline) { failure = ready ? 'await_reference' : 'history'; throw new Error(); }
      let text;
      try { text = privateCommand('cat', resultFile); } catch { /* Not yet published. */ }
      if (text !== undefined) report = parseReport(text, latest);
      if (completion !== null) {
        if (!ready) throw new Error();
        if (!report || !['complete', 'failed'].includes(report.stage)) throw new Error();
        failure = report.failure_stage ?? (!completion.ok ? 'capture' : report.stage === 'complete' ? null : 'comparison');
        break;
      }
      if (report && ['sample_ready', 'await_reference'].includes(report.stage) && !ready) {
        if (report.failure_stage !== null || !readyChecks.every(key => report[key] === true) ||
            report.sample_memory_cleared !== false) throw new Error();
        failure = 'cleanup';
        if (!restoreFitness()) throw new Error();
        ready = true;
        deadline = Date.now() + 330_000;
        failure = 'await_reference';
        if (onSampleReady) {
          const status = Object.freeze({ schema_version: latest ? 2 : 1, stage: 'sample_ready',
            ...(latest ? { sample_selection: 'latest', reference_window_relation: report.reference_window_relation } : {}),
            hardware_checks: Object.freeze(Object.fromEntries([...booleanChecks, ...nullableChecks].map(key => [key, report[key]]))),
            mi_fitness_process_restored: true, mi_fitness_bluetooth_reconnection: 'not_verified' });
          Promise.resolve().then(() => onSampleReady(status)).catch(() => { callbackFailure = true; wakeCallback(); });
        }
      }
      let timer;
      try {
        await Promise.race([completed, callbackFailed, new Promise(resolve => { timer = setTimeout(resolve, 200); })]);
      } finally { clearTimeout(timer); }
    }
  } catch {
    // Never propagate an adb, child, JSON, or callback error containing private data.
    if (failure === null) failure = 'prepare';
  } finally {
    if (owned) {
      if (attempted && completion === null) {
        try { adb(['shell', 'am', 'force-stop', testPackage]); } catch { cleanup = false; }
      }
      if (attempted && child && typeof child.stop === 'function') {
        try { child.stop(); } catch { cleanup = false; }
      }
      for (const file of privateFiles) {
        try { privateCommand('rm', '-f', file); } catch { cleanup = false; }
        try { privateCommand('test', '!', '-e', file); } catch { cleanup = false; }
      }
      try { privateCommand('rmdir', lockDirectory); } catch { cleanup = false; }
      try { privateCommand('test', '!', '-e', lockDirectory); } catch { cleanup = false; }
    }
    if (fitnessTouched && !restored) restoreFitness();
  }
  const checks = report ? Object.fromEntries([...booleanChecks, ...nullableChecks].map(key => [key, report[key]])) : null;
  const passed = owned && ready && !callbackFailure && completion?.ok === true && failure === null &&
    report?.stage === 'complete' && report.failure_stage === null && booleanChecks.every(key => report[key] === true) &&
    nullableChecks.slice(0, 3).every(key => report[key] === true) && report.reference_steps_match !== false &&
    (!latest || report.reference_window_relation === 'inside') && cleanup && restored;
  return { schema_version: latest ? 2 : 1, reverse_reference_check_passed: passed, cleanup_verified: owned && cleanup,
    ...(latest ? { sample_selection: 'latest', reference_window_relation: report?.reference_window_relation ?? null } : {}),
    mi_fitness_process_restored: fitnessTouched && restored, mi_fitness_bluetooth_reconnection: 'not_verified',
    hardware_checks: checks, failure_stage: passed ? null : !cleanup ? 'cleanup' : failure ?? 'comparison' };
}
