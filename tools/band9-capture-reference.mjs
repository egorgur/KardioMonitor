import { validateHistoryReference } from './band9-run-production-readonly.mjs';

const testPackage = 'ru.kardiomonitor.bandprotocol.test';
const captureTest = 'ru.kardiomonitor.bandprotocol.xiaomi.XiaomiMiFitnessReferenceCaptureTest#testCaptureReference';
const requestFile = 'files/capture-request.json';
const referenceFile = 'files/mi-fitness-reference.json';
const resultFile = 'files/reference-capture-result.json';
const lockDirectory = 'files/production-check.lock';
const stages = new Set([
  'prepare', 'snapshot', 'stability', 'write', 'root_missing', 'foreign_window', 'node_limit',
  'date_missing', 'date_mismatch', 'no_data', 'heart_rate_tooltip_missing', 'time_tooltip_missing',
  'tooltip_position_mismatch', 'timezone_ambiguous', 'invalid_heart_rate',
]);

function validDay(day) {
  if (typeof day !== 'string' || !/^[0-9]{4}-[0-9]{2}-[0-9]{2}$/.test(day)) return false;
  const [year, month, date] = day.split('-').map(Number);
  const leap = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0);
  const days = [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
  return year >= 1 && month >= 1 && month <= 12 && date >= 1 && date <= days[month - 1];
}

function parsePrivateJson(text) {
  if (typeof text !== 'string' || Buffer.byteLength(text) > 1024) throw new Error('reference_capture_failed');
  return JSON.parse(text);
}

function instrumentationPassed(output) {
  if (typeof output !== 'string') return false;
  const successes = output.match(/^\s*OK \(1 test\)\s*$/gm);
  return successes?.length === 1 &&
    !/FAILURES|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|shortMsg=|Process crashed|Error:|Exception/.test(output) &&
    !/^INSTRUMENTATION_STATUS_CODE:\s*-[2-9][0-9]*\s*$/m.test(output) &&
    [...output.matchAll(/^INSTRUMENTATION_CODE:\s*([^\r\n]+)$/gm)].every(match => match[1].trim() === '-1');
}

/**
 * Capture the already-visible Mi Fitness reference without navigation, keys, or band access.
 * `adb(args, input?)` must synchronously return stdout as UTF-8, throw on failure, and
 * capture (never print) both stdout and stderr, including instrumentation output.
 * Only the supplied isolated test APK is installed by default. Set installApk:false
 * when that APK is already installed to preserve the user's foreground activity.
 * expectedDay is an ASCII calendar date (YYYY-MM-DD), sent only over stdin,
 * never as an adb argument or diagnostic.
 * Invalid arguments throw a generic error before adb; operational failures return
 * safe metadata. Only an explicitly accessed, non-enumerable `result.reference`
 * contains private values; it is null unless capture and all cleanup are verified.
 * JSON.stringify(result) and {...result} contain only captured, stage, cleanup_verified.
 */
export function captureMiFitnessReference({ adb, apkPath, expectedDay, installApk = true } = {}) {
  if (typeof installApk !== 'boolean' || !validDay(expectedDay) || typeof adb !== 'function' || typeof apkPath !== 'string' ||
      !apkPath.trim() || apkPath.startsWith('-') || apkPath.includes('\0')) {
    throw new Error('reference_capture_failed');
  }

  let ownedLock = false;
  let attempted = false;
  let captured = false;
  let stage = 'prepare';
  let reference = null;
  let cleanupVerified = false;
  const privateCommand = (...args) => adb(['shell', 'run-as', testPackage, ...args]);
  try {
    if (adb(['get-state']).trim() !== 'device') throw new Error('reference_capture_failed');
    const policy = adb(['shell', 'dumpsys', 'window', 'policy']);
    if (!/\bshowing=false\b/.test(policy) || /\bshowing=true\b|\binputRestricted=true\b/.test(policy))
      throw new Error('reference_capture_failed');
    let probe;
    try {
      probe = JSON.parse(adb(['shell', 'run-as', 'ru.kardiomonitor.band9probe', 'cat', 'files/report.json']));
    } catch { /* The optional diagnostic package or report may be absent. */ }
    if (probe?.status === 'running') throw new Error('reference_capture_failed');
    let locked = false;
    try {
      privateCommand('test', '-e', lockDirectory);
      locked = true;
    } catch { /* The package or lock may not yet exist. Atomic mkdir checks again. */ }
    if (locked) throw new Error('reference_capture_failed');

    if (installApk) adb(['install', '-r', '-g', apkPath]);
    privateCommand('sh', '-c', "'umask 077; mkdir -p files && mkdir files/production-check.lock'");
    ownedLock = true;
    privateCommand('rm', '-f', resultFile, referenceFile);
    adb(['shell', 'run-as', testPackage, 'sh', '-c',
      "'umask 077; cat > files/capture-request.json && chmod 600 files/capture-request.json'"], expectedDay);
    stage = 'snapshot';
    attempted = true;
    const output = adb(['shell', 'am', 'instrument', '-w', '-e', 'class', captureTest,
      `${testPackage}/android.test.InstrumentationTestRunner`]);
    const report = parsePrivateJson(privateCommand('cat', resultFile));
    if (report === null || typeof report !== 'object' || Array.isArray(report) ||
        Object.keys(report).length !== 2 || typeof report.captured !== 'boolean' || !stages.has(report.stage))
      throw new Error('reference_capture_failed');
    stage = report.stage;
    if (instrumentationPassed(output) && report.captured === true && stage === 'write') {
      reference = validateHistoryReference(parsePrivateJson(privateCommand('cat', referenceFile)));
      captured = true;
    }
  } catch {
    captured = false;
  } finally {
    if (ownedLock) {
      cleanupVerified = true;
      if (attempted) {
        try { adb(['shell', 'am', 'force-stop', testPackage]); } catch { cleanupVerified = false; }
      }
      for (const file of [requestFile, referenceFile]) {
        try { privateCommand('rm', '-f', file); } catch { cleanupVerified = false; }
        try { privateCommand('test', '!', '-e', file); } catch { cleanupVerified = false; }
      }
      try { privateCommand('rmdir', lockDirectory); } catch { cleanupVerified = false; }
      try { privateCommand('test', '!', '-e', lockDirectory); } catch { cleanupVerified = false; }
    }
  }
  captured = captured && cleanupVerified;
  const result = { captured, stage, cleanup_verified: cleanupVerified };
  Object.defineProperty(result, 'reference', { value: captured ? reference : null, enumerable: false });
  return result;
}
