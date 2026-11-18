import { test } from 'node:test';
import assert from 'node:assert/strict';
import { runReverseReference } from './band9-run-reverse-reference.mjs';

const pkg = 'ru.kardiomonitor.bandprotocol.test';
const secret = '112233445566778899aabbccddeeff00';
const raw = 'private reading timestamp device-id source-output';
const bools = ['test_passed', 'sample_verified', 'sample_repeat_verified', 'sample_retention_unchanged',
  'client_closed_before_reference', 'key_removed', 'request_removed', 'signal_removed', 'reference_captured',
  'clinical_import_blocked', 'sample_memory_cleared'];
const comparisons = ['reference_record_located', 'reference_timezone_matches', 'reference_heart_rate_matches', 'reference_steps_match'];
const finalReport = changes => ({ schema_version: 1, stage: 'complete', failure_stage: null,
  ...Object.fromEntries([...bools, ...comparisons].map(key => [key, true])), ...changes });
const readyReport = changes => finalReport({ stage: 'sample_ready', test_passed: false, sample_memory_cleared: false,
  reference_captured: false, ...Object.fromEntries(comparisons.map(key => [key, null])), ...changes });
const latestFields = { schema_version: 2, sample_selection: 'latest', reference_window_relation: null };

function harness({ initialSetting = 0, locked = false, busy = false, activeProbe = false, race = false,
  initialReport = readyReport(), resultOverride = {}, output = 'OK (1 test)', early = false,
  rejectChild = false, disableIgnored = false, restoreIgnored = false, fault = () => false } = {}) {
  const calls = [];
  const inputs = [];
  const events = [];
  let setting = initialSetting;
  let lock = busy;
  let report = initialReport;
  let settled = false;
  let resolve;
  let reject;
  const completed = new Promise((yes, no) => { resolve = yes; reject = no; });
  const finish = () => {
    report = finalReport(resultOverride);
    settled = true;
    if (rejectChild) reject(new Error(raw)); else resolve(output);
  };
  const adb = (args, input) => {
    const command = args.join(' ');
    calls.push(command);
    if (input !== undefined) inputs.push(input);
    if (command === 'get-state') return 'device';
    if (command === 'shell dumpsys window policy') return locked ? 'showing=true' : 'showing=false';
    if (command.includes('cat files/report.json')) return JSON.stringify({ status: activeProbe ? 'running' : 'idle' });
    if (command === 'shell dumpsys package com.xiaomi.wearable') return `User 0: installed=true enabled=${setting}`;
    if (command.endsWith('test -e files/production-check.lock')) {
      if (!lock) throw new Error(raw);
      return '';
    }
    if (command.includes('mkdir files/production-check.lock')) {
      if (race) { lock = true; throw new Error(raw); }
      lock = true;
    }
    if (command === 'shell pm disable-user --user 0 com.xiaomi.wearable' && !disableIgnored) setting = 3;
    if (command === 'shell pm default-state --user 0 com.xiaomi.wearable' && !restoreIgnored) setting = 0;
    if (command === 'shell pm enable --user 0 com.xiaomi.wearable' && !restoreIgnored) setting = 1;
    if (fault(command)) throw new Error(raw);
    if (command.includes('XiaomiFit.main.log')) return `Xiaomi Smart Band 9 authKey=${secret}`;
    if (command.includes('cat files/reverse-result.json')) {
      if (report === null) throw new Error(raw);
      return typeof report === 'string' ? report : JSON.stringify(report);
    }
    if (command === 'shell pidof com.xiaomi.wearable') return '1234';
    if (command === `shell am force-stop ${pkg}`) events.push('force-stop');
    if (command.includes('rmdir files/production-check.lock')) lock = false;
    return '';
  };
  const startInstrumentation = args => {
    calls.push(args.join(' '));
    events.push('start');
    if (early) finish();
    return { completed, stop: () => events.push('stop-transport') };
  };
  return { calls, inputs, events, finish, setReport: value => { report = value; }, get setting() { return setting; },
    get settled() { return settled; }, options: { adb, startInstrumentation, apkPath: '/isolated/band-protocol-debug-androidTest.apk',
      expectedDay: '2026-11-17', onSampleReady: () => finish() } };
}

async function flush() { for (let i = 0; i < 12; i++) await Promise.resolve(); }
async function timed(t, run, seconds = 430) {
  t.mock.timers.enable({ apis: ['Date', 'setTimeout'], now: 0 });
  let result;
  let error;
  let done = false;
  run().then(value => { result = value; done = true; }, value => { error = value; done = true; });
  await flush();
  for (let i = 0; i < seconds && !done; i++) { t.mock.timers.tick(1000); await flush(); }
  assert.equal(done, true, 'runner must finish within its bounded wait');
  if (error) throw error;
  return result;
}
const assertCleaned = h => {
  for (const file of ['auth-key', 'reverse-request.json', 'reverse-capture.signal']) {
    assert.ok(h.calls.some(command => command.endsWith(`rm -f files/${file}`)));
    assert.ok(h.calls.some(command => command.endsWith(`test ! -e files/${file}`)));
  }
  assert.ok(h.calls.some(command => command.endsWith('rmdir files/production-check.lock')));
};

test('latest is opt-in, rejects every explicitly supplied offset and invalid selection before adb', async () => {
  for (const change of [
    ...[0, 1, undefined, null].map(historyOffset => ({ historySelection: 'latest', historyOffset })),
    ...[null, true, 0, {}, 'newest', raw].map(historySelection => ({ historySelection })),
  ]) {
    const h = harness();
    await assert.rejects(runReverseReference({ ...h.options, ...change }), /^Error: reverse_reference_invalid_arguments$/);
    assert.deepEqual(h.calls, []);
  }
});

test('latest stages only selection through stdin, projects schema2 safely, and preserves exact offset output', async () => {
  const h = harness({ initialReport: readyReport(latestFields),
    resultOverride: { ...latestFields, reference_window_relation: 'inside' } });
  let called = 0;
  const result = await runReverseReference({ ...h.options, historySelection: 'latest', pauseMiFitness: true,
    onSampleReady: status => {
      called++;
      assert.equal(h.setting, 0);
      assert.equal(status.schema_version, 2);
      assert.equal(status.sample_selection, 'latest');
      assert.equal(status.reference_window_relation, null);
      assert.deepEqual(h.events, ['start']);
      h.finish();
    } });
  assert.equal(called, 1);
  assert.equal(result.reverse_reference_check_passed, true);
  assert.equal(result.schema_version, 2);
  assert.equal(result.sample_selection, 'latest');
  assert.equal(result.reference_window_relation, 'inside');
  assert.deepEqual(h.inputs, [secret, JSON.stringify({ day: h.options.expectedDay, selection: 'latest' })]);
  assert.ok(!h.calls.some(command => /selection|latest/.test(command)));
  assertCleaned(h);
  const offset = harness();
  const defaultResult = await runReverseReference(offset.options);
  const explicit = harness();
  const explicitResult = await runReverseReference({ ...explicit.options, historySelection: 'offset' });
  assert.deepEqual(explicitResult, defaultResult);
  assert.deepEqual(explicit.inputs, [secret, JSON.stringify({ day: explicit.options.expectedDay, offset: 0 })]);
  assert.deepEqual(Object.keys(result).filter(key => !['sample_selection', 'reference_window_relation'].includes(key)),
    Object.keys(defaultResult));
  assert.equal(Object.hasOwn(defaultResult, 'sample_selection'), false);
  assert.equal(Object.hasOwn(defaultResult, 'reference_window_relation'), false);
});

test('latest requires the matching exact report schema and whitelisted relation at readiness and completion', async () => {
  const invalidLatest = [
    {}, { ...latestFields, schema_version: 1 }, { ...latestFields, sample_selection: 'offset' },
    ...[raw, 1, false, {}, undefined].map(reference_window_relation => ({ ...latestFields, reference_window_relation })),
    { ...latestFields, unexpected: raw },
  ];
  for (const fields of invalidLatest) {
    for (const atReadiness of [true, false]) {
      const h = harness({ initialReport: readyReport(atReadiness ? fields : latestFields),
        resultOverride: atReadiness ? latestFields : fields });
      let called = 0;
      const result = await runReverseReference({ ...h.options, historySelection: 'latest', onSampleReady: () => {
        called++;
        h.finish();
      } });
      assert.equal(result.reverse_reference_check_passed, false);
      assert.equal(called, atReadiness ? 0 : 1);
      assert.equal(JSON.stringify(result).includes(raw), false);
      assertCleaned(h);
    }
  }
  const offset = harness({ initialReport: readyReport(latestFields) });
  let called = false;
  const result = await runReverseReference({ ...offset.options, onSampleReady: () => { called = true; } });
  assert.equal(called, false);
  assert.equal(result.reverse_reference_check_passed, false);
  const wrongFinal = harness({ resultOverride: { ...latestFields, reference_window_relation: 'inside' } });
  assert.equal((await runReverseReference(wrongFinal.options)).reverse_reference_check_passed, false);
});

test('latest reports safe window mismatches but only inside can pass final acceptance', async () => {
  for (const relation of ['before', 'inside', 'after', 'empty', 'unsupported_time', null]) {
    const h = harness({ initialReport: readyReport(latestFields),
      resultOverride: { ...latestFields, reference_window_relation: relation } });
    const result = await runReverseReference({ ...h.options, historySelection: 'latest' });
    assert.equal(result.reverse_reference_check_passed, relation === 'inside');
    assert.equal(result.reference_window_relation, relation);
    assert.equal(result.failure_stage, relation === 'inside' ? null : 'comparison');
    assert.equal(result.cleanup_verified, true);
  }
});

test('restores exact Mi Fitness state before safe callback, then keeps RAM instrumentation alive', async () => {
  for (const initialSetting of [0, 1]) {
    const h = harness({ initialSetting });
    let called = 0;
    const pending = runReverseReference({ ...h.options, pauseMiFitness: true, onSampleReady: status => {
      called++;
      assert.equal(h.setting, initialSetting);
      assert.ok(h.calls.includes('shell monkey -p com.xiaomi.wearable -c android.intent.category.LAUNCHER 1'));
      assert.ok(h.calls.includes('shell pidof com.xiaomi.wearable'));
      assert.deepEqual(h.events, ['start']);
      assert.equal(status.stage, 'sample_ready');
      assert.equal(status.mi_fitness_process_restored, true);
      assert.equal(Object.isFrozen(status.hardware_checks), true);
      assert.ok(!JSON.stringify(status).includes(h.options.expectedDay));
    } });
    let done = false;
    pending.then(() => { done = true; });
    await flush();
    assert.equal(called, 1);
    assert.equal(done, false, 'callback returning does not finish instrumentation');
    assert.deepEqual(h.events, ['start']);
    h.finish();
    const result = await pending;
    assert.equal(result.reverse_reference_check_passed, true);
    assert.equal(result.cleanup_verified, true);
    assert.equal(result.mi_fitness_bluetooth_reconnection, 'not_verified');
    assert.deepEqual(h.events, ['start', 'stop-transport']);
    assert.ok(h.calls.includes(`shell pm ${initialSetting === 0 ? 'default-state' : 'enable'} --user 0 com.xiaomi.wearable`));
    assertCleaned(h);
  }
});

test('stdin-only private staging and exact instrumentation target; runner never writes capture signal', async () => {
  const h = harness();
  const result = await runReverseReference({ ...h.options, historyOffset: 255 });
  assert.equal(result.reverse_reference_check_passed, true);
  assert.deepEqual(h.inputs, [secret, JSON.stringify({ day: h.options.expectedDay, offset: 255 })]);
  assert.equal(h.calls.filter(command => command.startsWith('install ')).length, 1);
  assert.ok(h.calls.includes(`shell am instrument -w -e class ru.kardiomonitor.bandprotocol.xiaomi.XiaomiReverseReferenceHardwareTest#testSampleBeforeReference ${pkg}/android.test.InstrumentationTestRunner`));
  assert.ok(!h.calls.some(command => /-e verify_|-e history_|cat > files\/reverse-capture.signal/.test(command)));
  assert.ok(!h.calls.some(command => command.includes(secret) || command.includes(h.options.expectedDay)));
  assert.ok(!JSON.stringify(result).includes(raw));
});

test('rejects invalid options before any adb access with generic errors', async () => {
  for (const change of [
    { expectedDay: '2026-02-29' }, { expectedDay: '2024-02-30' }, { expectedDay: '0000-01-01' },
    { expectedDay: raw }, { expectedDay: '2024-13-01' }, { expectedDay: '2024-1-01' },
    { historyOffset: -1 }, { historyOffset: 256 }, { historyOffset: 0.5 }, { historyOffset: '0' },
    { pauseMiFitness: 1 }, { onSampleReady: null }, { adb: null }, { startInstrumentation: null },
    { apkPath: '' }, { apkPath: '-bad' }, { apkPath: 'bad\0apk' },
  ]) {
    const h = harness();
    await assert.rejects(runReverseReference({ ...h.options, ...change }), /^Error: reverse_reference_invalid_arguments$/);
    assert.deepEqual(h.calls, []);
  }
  const h = harness();
  assert.equal((await runReverseReference({ ...h.options, expectedDay: '2024-02-29' })).reverse_reference_check_passed, true);
});

test('locked/busy/probe/predisabled checks and lock race never delete unowned private state', async () => {
  for (const flags of [{ locked: true }, { busy: true }, { activeProbe: true }, { initialSetting: 3 }, { race: true }]) {
    const h = harness(flags);
    const result = await runReverseReference(h.options);
    assert.equal(result.reverse_reference_check_passed, false);
    assert.equal(result.cleanup_verified, false);
    assert.ok(!h.calls.some(command => /rm -f|rmdir|XiaomiFit|force-stop|disable-user/.test(command)));
    assert.deepEqual(h.events, []);
  }
});

test('key/request/stale-state preparation errors independently clean all owned files', async () => {
  for (const part of ['XiaomiFit.main.log', 'cat > files/auth-key', 'cat > files/reverse-request.json', 'rm -f files/reverse-result.json']) {
    const h = harness({ fault: command => command.includes(part) });
    const result = await runReverseReference(h.options);
    assert.equal(result.reverse_reference_check_passed, false);
    assertCleaned(h);
    assert.deepEqual(h.events, []);
    assert.ok(!h.calls.some(command => /force-stop com.xiaomi.wearable|disable-user/.test(command)));
    assert.ok(!JSON.stringify(result).includes(raw));
  }
});

test('rejects invalid or arbitrary reports without leaking their values or false PASS', async () => {
  for (const initialReport of [raw, [], readyReport({ unexpected: raw }), readyReport({ stage: raw }),
    readyReport({ failure_stage: raw }), readyReport({ schema_version: 2 }), readyReport({ sample_verified: raw }),
    readyReport({ reference_steps_match: 42 }), readyReport({ client_closed_before_reference: false }),
    readyReport({ sample_memory_cleared: true }), 'x'.repeat(4097)]) {
    const h = harness({ initialReport });
    const result = await runReverseReference(h.options);
    assert.equal(result.reverse_reference_check_passed, false);
    assert.equal(JSON.stringify(result).includes(raw), false);
    assertCleaned(h);
    assert.deepEqual(h.events, ['start', 'force-stop', 'stop-transport']);
  }
});

test('no ready report times out at startup, stops only owned instrumentation before removing files', async t => {
  const h = harness({ initialReport: null });
  const result = await timed(t, () => runReverseReference(h.options));
  assert.equal(result.reverse_reference_check_passed, false);
  assert.equal(result.failure_stage, 'history');
  assert.equal(Date.now(), 90_000);
  assertCleaned(h);
  assert.ok(h.calls.indexOf(`shell am force-stop ${pkg}`) < h.calls.findIndex(command => command.endsWith('rm -f files/auth-key')));
  assert.equal(result.mi_fitness_process_restored, true);
});

test('ready wait is bounded even for a callback that never settles; no premature force-stop', async t => {
  const h = harness();
  let callbacks = 0;
  const result = await timed(t, () => runReverseReference({ ...h.options, onSampleReady: () => {
    callbacks++;
    h.setReport(readyReport({ stage: 'await_reference' }));
    return new Promise(() => {});
  } }));
  assert.equal(callbacks, 1);
  assert.equal(result.reverse_reference_check_passed, false);
  assert.equal(result.failure_stage, 'await_reference');
  assert.equal(Date.now(), 330_000);
  assert.deepEqual(h.events, ['start', 'force-stop', 'stop-transport']);
  assertCleaned(h);
});

test('sync and async callback failures are sanitized, stopped, and cleaned', async () => {
  for (const onSampleReady of [() => { throw new Error(raw); }, async () => { throw new Error(raw); }]) {
    const h = harness();
    const result = await runReverseReference({ ...h.options, onSampleReady });
    assert.equal(result.reverse_reference_check_passed, false);
    assert.equal(result.failure_stage, 'await_reference');
    assert.equal(JSON.stringify(result).includes(raw), false);
    assert.deepEqual(h.events, ['start', 'force-stop', 'stop-transport']);
    assertCleaned(h);
  }
});

test('early child exit and transport rejection cannot pass and restore Mi Fitness', async () => {
  for (const flags of [{ early: true }, { rejectChild: true }]) {
    const h = harness(flags);
    const result = await runReverseReference({ ...h.options, pauseMiFitness: true });
    assert.equal(result.reverse_reference_check_passed, false);
    assert.equal(result.mi_fitness_process_restored, true);
    assert.equal(h.setting, 0);
    assertCleaned(h);
  }
});

test('every verification and memory cleanup check is necessary; steps may be unavailable', async () => {
  for (const resultOverride of [
    ...bools.map(key => ({ [key]: false })), ...comparisons.map(key => ({ [key]: false })),
    ...comparisons.slice(0, 3).map(key => ({ [key]: null })), { failure_stage: 'comparison' },
    { stage: 'failed', failure_stage: 'capture' }, { sample_memory_cleared: undefined },
  ]) {
    const h = harness({ resultOverride });
    assert.equal((await runReverseReference(h.options)).reverse_reference_check_passed, false, JSON.stringify(resultOverride));
  }
  const h = harness({ resultOverride: { reference_steps_match: null } });
  assert.equal((await runReverseReference(h.options)).reverse_reference_check_passed, true);
});

test('one actual instrumentation test success is required, not just a successful JSON report', async () => {
  for (const output of ['', 'OK (2 tests)', 'OK (1 test)\nFAILURES!!!', 'OK (1 test)\nINSTRUMENTATION_FAILED: private',
    'OK (1 test)\nINSTRUMENTATION_STATUS_CODE: -2', 'OK (1 test)\nINSTRUMENTATION_CODE: 0', 'OK (1 test)\nOK (1 test)']) {
    const h = harness({ output });
    assert.equal((await runReverseReference(h.options)).reverse_reference_check_passed, false);
  }
});

test('completed comparison mismatches stay distinct from infrastructure failures', async () => {
  const h = harness({ resultOverride: { stage: 'complete', test_passed: false, failure_stage: 'comparison', reference_heart_rate_matches: false } });
  const result = await runReverseReference(h.options);
  assert.equal(result.reverse_reference_check_passed, false);
  assert.equal(result.failure_stage, 'comparison');
  assert.equal(result.hardware_checks.reference_heart_rate_matches, false);
  assert.equal(result.cleanup_verified, true);
});

test('restore errors block callback and acceptance yet still attempt setting, process, and all cleanup', async () => {
  for (const part of ['pm default-state', 'monkey -p', 'pidof', 'pm disable-user']) {
    const h = harness({ fault: command => command.includes(part) });
    let callback = false;
    const result = await runReverseReference({ ...h.options, pauseMiFitness: true, onSampleReady: () => { callback = true; } });
    assert.equal(callback, false);
    assert.equal(result.reverse_reference_check_passed, false);
    assert.ok(h.calls.includes('shell pm default-state --user 0 com.xiaomi.wearable'));
    assert.ok(h.calls.includes('shell monkey -p com.xiaomi.wearable -c android.intent.category.LAUNCHER 1'));
    assert.ok(h.calls.includes('shell pidof com.xiaomi.wearable'));
    assertCleaned(h);
  }
});

test('transport start, malformed handle, and stop failures stay generic and clean owned state', async () => {
  for (const startInstrumentation of [
    () => { throw new Error(raw); },
    () => ({ completed: Promise.reject(new Error(raw)) }),
    () => ({ completed: 'invalid', stop() {} }),
  ]) {
    const h = harness();
    const result = await runReverseReference({ ...h.options, startInstrumentation });
    assert.equal(result.reverse_reference_check_passed, false);
    assert.equal(result.mi_fitness_process_restored, true);
    assert.equal(JSON.stringify(result).includes(raw), false);
    assert.ok(h.calls.includes(`shell am force-stop ${pkg}`));
    assertCleaned(h);
  }
  const h = harness();
  const result = await runReverseReference({ ...h.options, startInstrumentation: args => ({
    ...h.options.startInstrumentation(args), stop() { throw new Error(raw); },
  }) });
  assert.equal(result.reverse_reference_check_passed, false);
  assert.equal(result.cleanup_verified, false);
  assertCleaned(h);
});

test('ignored Mi Fitness setting changes cannot pass or invoke the readiness callback', async () => {
  for (const flags of [{ disableIgnored: true }, { restoreIgnored: true }]) {
    const h = harness(flags);
    let called = false;
    const result = await runReverseReference({ ...h.options, pauseMiFitness: true, onSampleReady: () => { called = true; } });
    assert.equal(called, false);
    assert.equal(result.reverse_reference_check_passed, false);
    assertCleaned(h);
  }
});

test('arbitrary final report fields never leak or pass after a valid readiness callback', async () => {
  const h = harness({ resultOverride: { raw_data: raw } });
  const result = await runReverseReference(h.options);
  assert.equal(result.reverse_reference_check_passed, false);
  assert.equal(JSON.stringify(result).includes(raw), false);
  assertCleaned(h);
});

test('each cleanup failure blocks acceptance without skipping remaining cleanup', async () => {
  for (const part of ['rm -f files/auth-key', 'test ! -e files/reverse-request.json', 'rm -f files/reverse-capture.signal', 'rmdir files/production-check.lock']) {
    const h = harness({ fault: command => command.endsWith(part) });
    const result = await runReverseReference(h.options);
    assert.equal(result.reverse_reference_check_passed, false);
    assert.equal(result.cleanup_verified, false);
    assertCleaned(h);
  }
});
