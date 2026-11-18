import { test } from 'node:test';
import assert from 'node:assert/strict';
import { captureMiFitnessReference } from './band9-capture-reference.mjs';

const testPackage = 'ru.kardiomonitor.bandprotocol.test';
const captureTest = 'ru.kardiomonitor.bandprotocol.xiaomi.XiaomiMiFitnessReferenceCaptureTest#testCaptureReference';
const requestFile = 'files/capture-request.json';
const referenceFile = 'files/mi-fitness-reference.json';
const resultFile = 'files/reference-capture-result.json';
const lockDirectory = 'files/production-check.lock';
const syntheticReference = { epoch_seconds: 1893456000, utc_offset_seconds: 25200, heart_rate: 77, steps: 1234 };

function harness(options = {}) {
  const calls = [];
  const inputs = [];
  const files = new Map([
    [resultFile, '{"captured":true,"stage":"write"}'],
    [referenceFile, JSON.stringify(syntheticReference)],
    [requestFile, '2030-01-01'],
  ]);
  let lock = options.lockActive === true;
  const adb = (args, input) => {
    calls.push([...args]);
    if (input !== undefined) inputs.push(input);
    const command = args.join(' ');
    const fail = () => { throw new Error('synthetic private error: 2032-02-29, heart rate 77'); };
    if (options.fail?.(command)) fail();
    if (command === 'get-state') return options.state ?? 'device';
    if (command === 'shell dumpsys window policy') return options.policy ?? 'showing=false inputRestricted=false';
    if (command === 'shell run-as ru.kardiomonitor.band9probe cat files/report.json') {
      if (options.probeAbsent) fail();
      return JSON.stringify({ status: options.probeActive ? 'running' : 'complete' });
    }
    if (args[0] === 'install') return 'Success';
    if (command.includes('am instrument')) {
      files.delete(requestFile);
      if (!options.noReport) files.set(resultFile, options.report ?? '{"captured":true,"stage":"write"}');
      files.set(referenceFile, options.reference ?? JSON.stringify(syntheticReference));
      return options.instrumentation ?? 'OK (1 test)\nINSTRUMENTATION_CODE: -1\n';
    }
    if (command === `shell am force-stop ${testPackage}`) return '';
    assert.deepEqual(args.slice(0, 3), ['shell', 'run-as', testPackage]);
    const action = args.slice(3);
    if (action[0] === 'sh' && action[2].includes('mkdir')) {
      if (lock || options.racingLock) fail();
      assert.equal(action[2], "'umask 077; mkdir -p files && mkdir files/production-check.lock'");
      lock = true;
      return '';
    }
    if (action[0] === 'sh' && action[2].includes('cat >')) {
      assert.equal(action[2], "'umask 077; cat > files/capture-request.json && chmod 600 files/capture-request.json'");
      files.set(requestFile, input);
      return '';
    }
    if (action[0] === 'rm') {
      for (const file of action.slice(2)) {
        if (file === options.removalFailure) fail();
        if (file !== options.ignoredRemoval) files.delete(file);
      }
      return '';
    }
    if (action[0] === 'cat') {
      if (!files.has(action[1])) fail();
      return files.get(action[1]);
    }
    if (action[0] === 'test') {
      const exists = action.at(-1) === lockDirectory ? lock : files.has(action.at(-1));
      if (action[1] === '!' ? exists : !exists) fail();
      return '';
    }
    if (action[0] === 'rmdir') {
      assert.equal(action[1], lockDirectory);
      lock = false;
      return '';
    }
    assert.fail(`Unexpected synthetic adb command: ${command}`);
  };
  return {
    calls, inputs, files, lockHeld: () => lock,
    options: { adb, apkPath: '/synthetic/isolated-test.apk', expectedDay: '2032-02-29' },
  };
}

function assertPrivateCleanup(h) {
  const commands = h.calls.map(args => args.join(' '));
  for (const file of [requestFile, referenceFile]) {
    assert.ok(commands.includes(`shell run-as ${testPackage} rm -f ${file}`));
    assert.ok(commands.includes(`shell run-as ${testPackage} test ! -e ${file}`));
  }
  assert.ok(commands.includes(`shell run-as ${testPackage} rmdir ${lockDirectory}`));
  assert.equal(h.lockHeld(), false);
}

function assertSafeFailure(result) {
  assert.equal(result.captured, false);
  assert.equal(result.reference, null);
  assert.deepEqual(Object.keys(result), ['captured', 'stage', 'cleanup_verified']);
  assert.ok(!JSON.stringify(result).includes('2032-02-29'));
  assert.ok(!JSON.stringify(result).includes('synthetic private'));
}

test('invalid ISO calendar dates and arguments reject generically before any adb', () => {
  for (const expectedDay of [undefined, null, 20320229, '', '2032-2-29', '2032-02-29\n',
    '2031-02-29', '1900-02-29', '2032-04-31', '2032-00-01', '2032-13-01', '2032-01-00',
    '0000-01-01', '２０３２-02-29', '2032-02-29; echo private', '"2032-02-29"']) {
    const h = harness();
    assert.throws(() => captureMiFitnessReference({ ...h.options, expectedDay }), { message: 'reference_capture_failed' });
    assert.deepEqual(h.calls, []);
  }
  for (const change of [{ apkPath: '' }, { apkPath: '-invalid' }, { adb: null }]) {
    const h = harness();
    assert.throws(() => captureMiFitnessReference({ ...h.options, ...change }), { message: 'reference_capture_failed' });
    assert.deepEqual(h.calls, []);
  }
});

test('installApk rejects non-booleans before any adb', () => {
  for (const installApk of [null, 0, 1, 'false', 'true', {}, [], () => true, new Boolean(false)]) {
    const h = harness();
    assert.throws(() => captureMiFitnessReference({ ...h.options, installApk }), { message: 'reference_capture_failed' });
    assert.deepEqual(h.calls, []);
  }
});

test('installApk false skips only installation, preserving capture, privacy and cleanup', () => {
  for (const flags of [{}, { fail: command => command.includes('am instrument') }, { ignoredRemoval: referenceFile }]) {
    const installed = harness(flags);
    const existing = harness(flags);
    const installedResult = captureMiFitnessReference(installed.options);
    const existingResult = captureMiFitnessReference({ ...existing.options, installApk: false });
    assert.deepEqual(existing.calls, installed.calls.filter(args => args[0] !== 'install'));
    assert.deepEqual(existing.inputs, installed.inputs);
    assert.deepEqual({ ...existingResult }, { ...installedResult });
    assert.deepEqual(existingResult.reference, installedResult.reference);
    assert.equal(Object.getOwnPropertyDescriptor(existingResult, 'reference').enumerable, false);
    assert.deepEqual(JSON.parse(JSON.stringify(existingResult)), { ...existingResult });
    assertPrivateCleanup(existing);
    if (flags.fail || flags.ignoredRemoval) assertSafeFailure(existingResult);
    else {
      assert.equal(existingResult.captured, true);
      assert.equal(existingResult.cleanup_verified, true);
      assert.deepEqual(existingResult.reference, syntheticReference);
    }
  }
});

test('unauthorized, locked, active probe and existing lock abort without side effects', () => {
  for (const flags of [{ state: 'unauthorized' }, { policy: 'showing=true' },
    { policy: 'showing=false inputRestricted=true' }, { policy: 'unknown' },
    { probeActive: true }, { lockActive: true }]) {
    for (const installApk of [true, false]) {
      const h = harness(flags);
      const before = [...h.files];
      const result = captureMiFitnessReference({ ...h.options, installApk });
      assertSafeFailure(result);
      assert.equal(result.cleanup_verified, false);
      assert.ok(!h.calls.some(args => /install|mkdir|chmod|rm|instrument|force-stop/.test(args.join(' '))));
      assert.deepEqual([...h.files], before);
      assert.equal(h.lockHeld(), flags.lockActive === true);
    }
  }
});

test('success sends the plain date only through stdin and exposes private reference only explicitly', () => {
  const h = harness({ probeAbsent: true });
  const result = captureMiFitnessReference(h.options);
  const metadata = { captured: true, stage: 'write', cleanup_verified: true };
  assert.deepEqual({ ...result }, metadata);
  assert.deepEqual(JSON.parse(JSON.stringify(result)), metadata);
  assert.deepEqual(result.reference, syntheticReference);
  assert.equal(Object.getOwnPropertyDescriptor(result, 'reference').enumerable, false);
  assert.deepEqual(h.inputs, ['2032-02-29']);
  assert.ok(!h.calls.some(args => args.some(arg => arg.includes('2032-02-29'))));
  assert.deepEqual(h.calls.filter(args => args[0] === 'install'), [['install', '-r', '-g', h.options.apkPath]]);
  assert.deepEqual(h.calls.find(args => args.includes('instrument')), [
    'shell', 'am', 'instrument', '-w', '-e', 'class', captureTest,
    `${testPackage}/android.test.InstrumentationTestRunner`,
  ]);
  const clearIndex = h.calls.findIndex(args => args.includes('rm') && args.includes(resultFile));
  const stageIndex = h.calls.findIndex(args => args.some(arg => arg.includes('cat >')));
  assert.ok(clearIndex >= 0 && clearIndex < stageIndex);
  assertPrivateCleanup(h);
  assert.equal(h.files.has(requestFile), false);
  assert.equal(h.files.has(referenceFile), false);
  assert.deepEqual(h.calls.filter(args => args.includes('force-stop')), [['shell', 'am', 'force-stop', testPackage]]);
  assert.ok(!h.calls.some(args => /com\.xiaomi\.wearable|auth-key|history-reference|XiaomiFit|bluetooth|monkey|disable|\bkey\b/i.test(args.join(' '))));
});

test('stale, unknown, malformed and unproved status reports never read private reference', () => {
  for (const flags of [
    { noReport: true }, { report: 'not JSON: synthetic private' }, { report: 'null' }, { report: '[]' },
    { report: '{"captured":true,"stage":"private-source-value"}' },
    { report: '{"captured":1,"stage":"write"}' },
    { report: '{"captured":true,"stage":"write","source":"synthetic private"}' },
    { report: '{"captured":false,"stage":"write"}' },
    { report: '{"captured":true,"stage":"stability"}' },
    { report: ' '.repeat(1025) },
  ]) {
    const h = harness(flags);
    const result = captureMiFitnessReference(h.options);
    assertSafeFailure(result);
    assert.equal(result.cleanup_verified, true);
    assert.ok(!h.calls.some(args => args.join(' ') === `shell run-as ${testPackage} cat ${referenceFile}`));
    assertPrivateCleanup(h);
  }
  for (const stage of ['root_missing', 'date_mismatch', 'timezone_ambiguous', 'invalid_heart_rate']) {
    const h = harness({ report: JSON.stringify({ captured: false, stage }) });
    const result = captureMiFitnessReference(h.options);
    assertSafeFailure(result);
    assert.equal(result.stage, stage);
  }
});

test('wrong or failing instrumentation and invalid private JSON refuse capture', () => {
  for (const instrumentation of ['', 'OK (2 tests)', 'prefix OK (1 test)', 'OK (1 test)\nFAILURES!!!',
    'OK (1 test)\nINSTRUMENTATION_FAILED', 'OK (1 test)\nshortMsg=synthetic private',
    'OK (1 test)\nINSTRUMENTATION_CODE: 0', 'OK (1 test)\nINSTRUMENTATION_STATUS_CODE: -2',
    'OK (1 test)\nOK (1 test)']) {
    const h = harness({ instrumentation });
    const result = captureMiFitnessReference(h.options);
    assertSafeFailure(result);
    assert.ok(!h.calls.some(args => args.join(' ') === `shell run-as ${testPackage} cat ${referenceFile}`));
    assertPrivateCleanup(h);
  }
  for (const reference of ['not JSON: synthetic private', '{}', 'null',
    JSON.stringify({ ...syntheticReference, heart_rate: 0 }),
    JSON.stringify({ ...syntheticReference, source: 'synthetic private' }), ' '.repeat(1025)]) {
    const h = harness({ reference });
    assertSafeFailure(captureMiFitnessReference(h.options));
    assertPrivateCleanup(h);
  }
});

test('stage, instrumentation and report errors still clean both private files and release lock', () => {
  for (const target of ['cat > files/capture-request.json', 'am instrument', `cat ${resultFile}`, `cat ${referenceFile}`]) {
    const h = harness({ fail: command => command.includes(target) });
    const result = captureMiFitnessReference(h.options);
    assertSafeFailure(result);
    assert.equal(result.cleanup_verified, true);
    assertPrivateCleanup(h);
    const stopped = h.calls.some(args => args.includes('force-stop'));
    assert.equal(stopped, !target.startsWith('cat >'));
  }
});

test('cleanup failures discard validated reference, independently delete and verify files, and release lock', () => {
  for (const flags of [
    { removalFailure: requestFile },
    { ignoredRemoval: referenceFile },
    { fail: command => command === `shell am force-stop ${testPackage}` },
    { fail: command => command === `shell run-as ${testPackage} test ! -e ${requestFile}` },
  ]) {
    const h = harness(flags);
    const result = captureMiFitnessReference(h.options);
    assertSafeFailure(result);
    assert.equal(result.cleanup_verified, false);
    assertPrivateCleanup(h);
    assert.ok(h.calls.some(args => args.join(' ') === `shell run-as ${testPackage} cat ${referenceFile}`));
  }
  const h = harness({ fail: command => command.includes('cat >'), removalFailure: requestFile });
  assertSafeFailure(captureMiFitnessReference(h.options));
  assertPrivateCleanup(h);
  assert.equal(h.files.has(referenceFile), false);
});

test('failed stale-output removal or racing lock cannot use old success or clean an unowned lock', () => {
  const stale = harness({ fail: command => command === `shell run-as ${testPackage} rm -f ${resultFile} ${referenceFile}` });
  assertSafeFailure(captureMiFitnessReference(stale.options));
  assertPrivateCleanup(stale);
  assert.ok(!stale.calls.some(args => args.includes('instrument') || args.includes('force-stop')));
  const race = harness({ racingLock: true });
  assertSafeFailure(captureMiFitnessReference(race.options));
  assert.ok(!race.calls.some(args => args.includes('rm') || args.includes('rmdir') || args.includes('force-stop')));
});
