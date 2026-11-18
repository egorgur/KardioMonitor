import { test } from 'node:test';
import assert from 'node:assert/strict';
import { Readable } from 'node:stream';
import { spawnSync } from 'node:child_process';
import { parseProductionReadOnlyArguments, readHistoryReference, runProductionReadOnly,
  validateHistoryReference } from './band9-run-production-readonly.mjs';

const testPackage = 'ru.kardiomonitor.bandprotocol.test';
function harness({ locked = false, active = false, productionActive = false, keyFailure = false, stageFailure = false,
  instrumentation = 'OK (1 test)', reportOverride = {}, cleanupFailure = false, restoreFailure = false, connectFailure = false,
  fitnessState = 0, disableFailure = false, enableFailure = false, disableIgnored = false, enableIgnored = false,
  referenceStageFailure = false, reportFailure = false, referenceCleanupFailure = false, keyRemoveFailure = false } = {}) {
  const calls = [];
  const inputs = [];
  const adb = (args, input) => {
    const command = args.join(' ');
    calls.push(command);
    if (input !== undefined) inputs.push(input);
    if (command === 'shell dumpsys package com.xiaomi.wearable') return `User 0: installed=true enabled=${fitnessState}`;
    if (command === 'shell pm disable-user --user 0 com.xiaomi.wearable') {
      if (!disableIgnored) fitnessState = 3;
      if (disableFailure) throw new Error('failed after disabling');
    }
    if (/^shell pm (enable|default-state) --user 0 com.xiaomi.wearable$/.test(command)) {
      if (enableFailure) throw new Error('restore failed');
      if (!enableIgnored) fitnessState = command.includes('default-state') ? 0 : 1;
    }
    if (command === 'get-state') return 'device';
    if (command === 'shell dumpsys window policy') return locked ? 'showing=true' : 'showing=false';
    if (command.includes('cat files/report.json')) return JSON.stringify({ status: active ? 'running' : 'ok' });
    if (command.includes('test -d files/production-check.lock')) {
      if (productionActive) return '';
      throw new Error('no lock');
    }
    if (command.includes('XiaomiFit.main.log')) {
      if (keyFailure) throw new Error('private source log');
      return 'Xiaomi Smart Band 9 authKey=112233445566778899aabbccddeeff00';
    }
    if (command.includes('cat > files/auth-key') && stageFailure) throw new Error('partial stage');
    if (command.includes('cat > files/history-reference.json') && referenceStageFailure) throw new Error('private partial reference');
    if (command.includes('cat files/readonly-result.json') && reportFailure) throw new Error('private report failure');
    if (command.includes('rm -f files/auth-key') && keyRemoveFailure) throw new Error('key removal failed');
    if (command.includes('test ! -e files/history-reference.json') && referenceCleanupFailure) throw new Error('reference cleanup failed');
    if (command.includes('cat files/readonly-result.json')) return JSON.stringify({
      schema_version: 1, failure_phase: null, open_failure_phase: null, test_passed: true, key_consumed: true,
      permissions_granted: true, bond_unique: true, client_created: true, open_completed: true,
      guard_verified: true, battery_completed: true, settings_completed: true,
      client_close_completed: true, key_removed: true, battery_present: true,
      settings_disabled_present: true, settings_interval_present: true, ...reportOverride,
    });
    if (command.includes('am instrument')) {
      if (connectFailure) throw new Error('timeout with sensitive stdout');
      return instrumentation;
    }
    if (command.includes('test ! -e files/auth-key') && cleanupFailure) throw new Error('cleanup');
    if (command === 'shell pidof com.xiaomi.wearable') return restoreFailure ? '' : '1234';
    return '';
  };
  return { calls, inputs, options: { adb, apkPath: '/test/library-test.apk' } };
}

test('only library instrumentation is installed; key goes over stdin and cleanup restores Mi Fitness', () => {
  const h = harness();
  const report = runProductionReadOnly(h.options);
  assert.equal(report.production_readonly_test_passed, true);
  assert.equal(report.cleanup_verified, true);
  assert.equal(report.mi_fitness_bluetooth_reconnection, 'not_verified');
  assert.equal(report.history_live_settings_write_tested, false);
  assert.equal(h.inputs.length, 1);
  assert.ok(!h.calls.some(call => call.includes(h.inputs[0])));
  assert.ok(h.calls.some(call => call.includes(`force-stop ${testPackage}`)));
  assert.ok(h.calls.some(call => call.includes('rm -f files/auth-key')));
  assert.ok(h.calls.some(call => call.includes('test ! -e files/auth-key')));
  assert.ok(!h.calls.some(call => /pm clear|uninstall|ru\.kardiomonitor\.staff|installDebug/.test(call)));
});

test('locked phone and active diagnostic session are rejected before installation or key access', () => {
  for (const flags of [{ locked: true }, { active: true }, { productionActive: true }]) {
    const h = harness(flags);
    assert.throws(() => runProductionReadOnly(h.options));
    assert.ok(!h.calls.some(call => /install|XiaomiFit|force-stop/.test(call)));
  }
});

test('key extraction and partial staging failures remove key but do not stop Mi Fitness', () => {
  for (const flags of [{ keyFailure: true }, { stageFailure: true }]) {
    const h = harness(flags);
    assert.equal(runProductionReadOnly(h.options).production_readonly_test_passed, false);
    assert.ok(h.calls.some(call => call.includes('rm -f files/auth-key')));
    assert.ok(!h.calls.some(call => call.includes('force-stop com.xiaomi.wearable')));
  }
});

test('instrumentation failure or timeout cannot pass and still cleans up the read-only process', () => {
  for (const flags of [{ instrumentation: 'FAILURES!!!' }, { instrumentation: '' }, { connectFailure: true }]) {
    const h = harness(flags);
    const report = runProductionReadOnly(h.options);
    assert.equal(report.production_readonly_test_passed, false);
    assert.equal(report.cleanup_verified, true);
    assert.ok(h.calls.some(call => call.includes(`force-stop ${testPackage}`)));
    assert.ok(h.calls.some(call => call.includes('monkey -p com.xiaomi.wearable')));
  }
});

test('hardware report is whitelisted and requires every verified check even if instrumentation says OK', () => {
  const h = harness({ reportOverride: { failure_phase: 'sensitive untrusted message', raw_packets: 'never print', guard_verified: false } });
  const report = runProductionReadOnly(h.options);
  assert.equal(report.production_readonly_test_passed, false);
  assert.equal(report.hardware_failure_phase, null);
  assert.ok(!JSON.stringify(report).includes('never print'));
  assert.ok(!JSON.stringify(report).includes('sensitive untrusted message'));
  for (const reportOverride of [
    { schema_version: 99 }, { failure_phase: 'unknown' }, { open_failure_phase: 'unknown' },
    { battery_present: false }, { settings_disabled_present: false }, { settings_interval_present: false },
  ]) {
    assert.equal(runProductionReadOnly(harness({ reportOverride }).options).production_readonly_test_passed, false);
  }
});

test('bounded library history is explicit and requires dedicated schema and all equality and import guards', () => {
  const checks = { schema_version: 2, history_requested: true, history_completed: true,
    history_retention_unchanged: true, history_repeated_bytes_identical: true,
    history_aggregates_consistent: true, history_import_blocked: true };
  const h = harness({ reportOverride: { ...checks, raw_history: 'private', digest: 'private' } });
  const report = runProductionReadOnly({ ...h.options, historyOffset: 1 });
  assert.equal(report.production_readonly_test_passed, true);
  assert.equal(report.bounded_history_verification_requested, true);
  assert.equal(report.clinical_history_import_tested, false);
  assert.equal(report.history_live_settings_write_tested, false);
  assert.ok(h.calls.some(call => call.includes('-e verify_past_repeat true -e history_offset 1')));
  assert.ok(!JSON.stringify(report).includes('private'));
  for (const change of [{ schema_version: 1 }, ...Object.keys(checks).filter(key => key !== 'schema_version').map(key => ({ [key]: false }))]) {
    const current = harness({ reportOverride: { ...checks, ...change } });
    assert.equal(runProductionReadOnly({ ...current.options, historyOffset: 0 }).production_readonly_test_passed, false);
  }
  assert.equal(runProductionReadOnly(harness({ reportOverride: checks }).options).production_readonly_test_passed, false);
});

test('invalid library history offsets are rejected before installation or private key access', () => {
  for (const historyOffset of [-1, 256, 0.5, '0', null]) {
    const h = harness();
    assert.throws(() => runProductionReadOnly({ ...h.options, historyOffset }));
    assert.deepEqual(h.calls, []);
  }
});

test('Mi Fitness pause is opt-in and restores the exact original enabled setting', () => {
  const defaultRun = harness();
  runProductionReadOnly(defaultRun.options);
  assert.ok(!defaultRun.calls.some(call => /pm disable-user|dumpsys package/.test(call)));
  for (const fitnessState of [0, 1]) {
    const h = harness({ fitnessState });
    const report = runProductionReadOnly({ ...h.options, pauseMiFitness: true });
    assert.equal(report.production_readonly_test_passed, true);
    assert.equal(report.cleanup_verified, true);
    assert.equal(report.mi_fitness_pause_requested, true);
    const disable = h.calls.indexOf('shell pm disable-user --user 0 com.xiaomi.wearable');
    const instrument = h.calls.findIndex(call => call.includes('am instrument'));
    const restore = h.calls.indexOf(`shell pm ${fitnessState === 0 ? 'default-state' : 'enable'} --user 0 com.xiaomi.wearable`);
    const launch = h.calls.findIndex(call => call.includes('monkey -p com.xiaomi.wearable'));
    assert.ok(disable >= 0 && disable < instrument && instrument < restore && restore < launch);
  }
});

test('pause refuses pre-disabled or unknown packages without installation or key access', () => {
  for (const fitnessState of [2, 3, 4, 99, 'unknown']) {
    const h = harness({ fitnessState });
    assert.throws(() => runProductionReadOnly({ ...h.options, pauseMiFitness: true }));
    assert.ok(!h.calls.some(call => /install -r|XiaomiFit|pm disable-user/.test(call)));
  }
});

test('pause restores Mi Fitness after failed or ignored disable and instrumentation timeout', () => {
  for (const flags of [{ disableFailure: true }, { disableIgnored: true }, { connectFailure: true }]) {
    const h = harness(flags);
    const report = runProductionReadOnly({ ...h.options, pauseMiFitness: true });
    assert.equal(report.production_readonly_test_passed, false);
    assert.equal(report.cleanup_verified, true);
    assert.ok(h.calls.includes('shell pm default-state --user 0 com.xiaomi.wearable'));
    assert.ok(h.calls.some(call => call.includes('test ! -e files/auth-key')));
    if (flags.disableIgnored) assert.ok(!h.calls.some(call => call.includes('am instrument')));
  }
  for (const flags of [{ enableFailure: true }, { enableIgnored: true }]) {
    const h = harness(flags);
    assert.equal(runProductionReadOnly({ ...h.options, pauseMiFitness: true }).cleanup_verified, false);
  }
});

test('CLI requires an explicit trailing pause option and validates history ranges before ADB', () => {
  assert.deepEqual(parseProductionReadOnlyArguments(['serial']), { serial: 'serial', pauseMiFitness: false });
  assert.deepEqual(parseProductionReadOnlyArguments(['serial', 'history_repeat', '0', '--pause-mi-fitness']),
    { serial: 'serial', historyOffset: 0, pauseMiFitness: true });
  assert.deepEqual(parseProductionReadOnlyArguments(['serial', '--pause-mi-fitness']), { serial: 'serial', pauseMiFitness: true });
  for (const args of [[], ['serial', '--unknown'], ['serial', 'history_repeat', '256'],
    ['serial', '--pause-mi-fitness', 'history_repeat', '0'], ['serial', 'history_repeat', '-1'],
    ['serial', 'history_repeat', '0.5'], ['serial', '--pause-mi-fitness', '--pause-mi-fitness']]) {
    assert.throws(() => parseProductionReadOnlyArguments(args));
  }
});

test('cleanup or Mi Fitness restoration failure cannot be reported as accepted', () => {
  for (const flags of [{ cleanupFailure: true }, { restoreFailure: true }]) {
    assert.equal(runProductionReadOnly(harness(flags).options).cleanup_verified, false);
  }
});

const reference = { epoch_seconds: 1735689600, utc_offset_seconds: 25200, heart_rate: 72, steps: 1234 };
const referenceChecks = { schema_version: 3, history_requested: true, history_completed: true,
  history_retention_unchanged: true, history_repeated_bytes_identical: true,
  history_aggregates_consistent: true, history_import_blocked: true,
  history_reference_requested: true, history_reference_matched: true, history_reference_removed: true };

const invalidReferences = [null, [], JSON.stringify(reference), {}, { ...reference, extra: 1 },
  ...Object.keys(reference).map(key => Object.fromEntries(Object.entries(reference).filter(([name]) => name !== key))),
  { ...reference, [Symbol('extra')]: true },
  ...[-1, 2147483648, 1, 1.5, NaN, Infinity, Number.MAX_SAFE_INTEGER + 1, '1735689600']
    .map(epoch_seconds => ({ ...reference, epoch_seconds })),
  ...[-43201, 50401, 1, 0.5, NaN, -Infinity, '0'].map(utc_offset_seconds => ({ ...reference, utc_offset_seconds })),
  ...[0, 255, 1.5, NaN, Infinity, undefined, '72', true].map(heart_rate => ({ ...reference, heart_rate })),
  ...[-1, 16384, 0.5, NaN, Infinity, undefined, '0', false].map(steps => ({ ...reference, steps })),
  { ...reference, heart_rate: null, steps: null }];

test('independent reference validation enforces exact keys, bounds, units and nullable aggregates before any ADB', () => {
  for (const valid of [reference,
    { epoch_seconds: 0, utc_offset_seconds: -43200, heart_rate: 1, steps: null },
    { epoch_seconds: 2147483640, utc_offset_seconds: 50400, heart_rate: null, steps: 0 },
    { ...reference, heart_rate: 254, steps: 16383 }]) {
    assert.deepEqual(validateHistoryReference(valid), valid);
  }
  for (const historyReference of invalidReferences) {
    assert.throws(() => validateHistoryReference(historyReference), /invalid_history_reference/);
    const h = harness();
    assert.throws(() => runProductionReadOnly({ ...h.options, historyOffset: 0, historyReference }));
    assert.deepEqual(h.calls, []);
  }
  const h = harness();
  assert.throws(() => runProductionReadOnly({ ...h.options, historyReference: reference }), /requires_offset/);
  assert.deepEqual(h.calls, []);
});

test('stdin reference is bounded in bytes, parsed privately and never exposed in errors', async () => {
  const json = JSON.stringify(reference);
  assert.deepEqual(await readHistoryReference(Readable.from([json.slice(0, 9), json.slice(9)])), reference);
  assert.deepEqual(await readHistoryReference(Readable.from([json.padEnd(1024)])), reference);
  for (const input of [json.padEnd(1025), `${json}${' '.repeat(1024)}`, '', 'private malformed JSON',
    JSON.stringify({ ...reference, extra: 'private' }), JSON.stringify([]),
    json.replace('72', '1e999'), ` ${'é'.repeat(512)}`]) {
    await assert.rejects(readHistoryReference(Readable.from([Buffer.from(input)])),
      error => error.message === 'invalid_history_reference');
  }
  let reads = 0;
  async function* oversized() {
    reads++;
    yield Buffer.alloc(1025);
    reads++;
    yield Buffer.from(json);
  }
  await assert.rejects(readHistoryReference(oversized()), /invalid_history_reference/);
  assert.equal(reads, 1);
  const failing = Readable.from((async function* () { throw new Error('private stream error'); })());
  await assert.rejects(readHistoryReference(failing), error => error.message === 'invalid_history_reference');
});

test('reference CLI mode preserves pause and offset parsing without accepting JSON arguments', () => {
  for (const pause of [false, true]) {
    assert.deepEqual(parseProductionReadOnlyArguments(['serial', 'history_reference', '255',
      ...(pause ? ['--pause-mi-fitness'] : [])]),
    { serial: 'serial', historyOffset: 255, pauseMiFitness: pause, referenceRequested: true });
  }
  for (const args of [['serial', 'history_reference'], ['serial', 'history_reference', '256'],
    ['serial', 'history_reference', '-1'], ['serial', 'history_reference', '01'],
    ['serial', 'history_reference', '0.5'], ['serial', 'history_reference', '0', JSON.stringify(reference)]]) {
    assert.throws(() => parseProductionReadOnlyArguments(args));
  }
  for (const input of ['private invalid reference', JSON.stringify(reference).padEnd(1025)]) {
    const result = spawnSync(process.execPath, [new URL('./band9-run-production-readonly.mjs', import.meta.url).pathname,
      'serial', 'history_reference', '0'], { input, encoding: 'utf8', timeout: 5000 });
    assert.equal(result.status, 1);
    assert.equal(result.stdout, '');
    assert.equal(result.stderr,
      'Production read-only check blocked or failed; no keys, device addresses or raw responses printed.\n');
  }
});

test('reference is staged only over stdin with private permissions and combined history instrumentation flags', async () => {
  const historyReference = await readHistoryReference(Readable.from([JSON.stringify(reference)]));
  const h = harness({ reportOverride: referenceChecks });
  const report = runProductionReadOnly({ ...h.options, historyOffset: 0, historyReference, pauseMiFitness: true });
  assert.equal(report.production_readonly_test_passed, true);
  assert.equal(report.history_reference_requested, true);
  assert.equal(report.cleanup_verified, true);
  assert.equal(report.mi_fitness_process_restored, true);
  assert.equal(h.inputs.length, 2);
  assert.deepEqual(JSON.parse(h.inputs[1]), reference);
  assert.ok(h.calls.some(call => call.includes('umask 077; cat > files/history-reference.json && chmod 600 files/history-reference.json')));
  assert.ok(h.calls.some(call => call.includes('-e verify_past_repeat true -e history_offset 0 -e verify_history_reference true')));
  for (const input of h.inputs) assert.ok(!h.calls.some(call => call.includes(input)));
  assert.ok(!h.calls.some(call => call.includes(String(reference.epoch_seconds))));
});

test('reference schema requires all history and reference checks and projects no raw or extra fields', () => {
  const h = harness({ reportOverride: { ...referenceChecks, ...reference,
    raw_history: 'private', history_reference: reference, history_reference_digest: 'private',
    history_reference_extra: 'private' } });
  const report = runProductionReadOnly({ ...h.options, historyOffset: 1, historyReference: reference });
  assert.equal(report.production_readonly_test_passed, true);
  const expectedChecks = [...Object.keys(runProductionReadOnly(harness().options).hardware_checks),
    ...Object.keys(referenceChecks).filter(key => key !== 'schema_version')];
  assert.deepEqual(Object.keys(report.hardware_checks), expectedChecks);
  assert.ok(Object.values(report.hardware_checks).every(value => value === true));
  assert.ok(!JSON.stringify(report).includes('private'));
  for (const key of Object.keys(reference)) assert.ok(!JSON.stringify(report).includes(`"${key}":`));
  for (const change of [{ schema_version: 1 }, { schema_version: 2 }, { schema_version: 99 },
    ...Object.keys(referenceChecks).filter(key => key !== 'schema_version').flatMap(key =>
      [{ [key]: false }, { [key]: undefined }, { [key]: 'true' }])]) {
    const current = harness({ reportOverride: { ...referenceChecks, ...change } });
    assert.equal(runProductionReadOnly({ ...current.options, historyOffset: 0, historyReference: reference })
      .production_readonly_test_passed, false);
  }
  for (const historyOffset of [undefined, 0]) {
    const current = harness({ reportOverride: referenceChecks });
    assert.equal(runProductionReadOnly({ ...current.options, historyOffset }).production_readonly_test_passed, false);
  }
});

test('reference cleanup and lock release survive key, stage, instrumentation and report errors', () => {
  for (const flags of [{ keyFailure: true }, { stageFailure: true }, { referenceStageFailure: true },
    { connectFailure: true }, { instrumentation: 'FAILURES!!!' }, { reportFailure: true },
    { reportOverride: { history_reference_matched: false } }]) {
    const h = harness({ ...flags, reportOverride: { ...referenceChecks, ...flags.reportOverride } });
    const report = runProductionReadOnly({ ...h.options, historyOffset: 0, historyReference: reference, pauseMiFitness: true });
    assert.equal(report.production_readonly_test_passed, false);
    assert.equal(report.cleanup_verified, true);
    for (const file of ['auth-key', 'history-reference.json']) {
      assert.ok(h.calls.includes(`shell run-as ${testPackage} rm -f files/${file}`));
      assert.ok(h.calls.includes(`shell run-as ${testPackage} test ! -e files/${file}`));
    }
    assert.ok(h.calls.includes(`shell run-as ${testPackage} rmdir files/production-check.lock`));
    if (flags.connectFailure || flags.instrumentation || flags.reportFailure || flags.reportOverride) {
      assert.equal(report.mi_fitness_process_restored, true);
      assert.ok(h.calls.includes('shell pm default-state --user 0 com.xiaomi.wearable'));
    }
  }
});

test('reference cleanup failure is rejected without skipping other cleanup or Mi Fitness restoration', () => {
  for (const flags of [{ keyRemoveFailure: true }, { cleanupFailure: true }, { referenceCleanupFailure: true }]) {
    const h = harness({ ...flags, reportOverride: referenceChecks });
    const report = runProductionReadOnly({ ...h.options, historyOffset: 0, historyReference: reference, pauseMiFitness: true });
    assert.equal(report.cleanup_verified, false);
    assert.ok(h.calls.includes(`shell run-as ${testPackage} rm -f files/history-reference.json`));
    assert.ok(h.calls.includes(`shell run-as ${testPackage} test ! -e files/history-reference.json`));
    assert.ok(h.calls.includes(`shell run-as ${testPackage} rmdir files/production-check.lock`));
    assert.ok(h.calls.includes('shell pm default-state --user 0 com.xiaomi.wearable'));
    assert.ok(h.calls.includes('shell pidof com.xiaomi.wearable'));
  }
});

const unknownComparison = { record_located: null, timezone_matches: null, heart_rate_matches: null, steps_match: null };

test('nullable reference comparison diagnostics preserve strict booleans without becoming pass requirements', () => {
  for (const comparison of [unknownComparison,
    { record_located: true, timezone_matches: true, heart_rate_matches: true, steps_match: null },
    { record_located: true, timezone_matches: true, heart_rate_matches: null, steps_match: true },
    { record_located: false, timezone_matches: false, heart_rate_matches: false, steps_match: false }]) {
    const diagnostics = Object.fromEntries(Object.entries(comparison).map(([name, value]) => [`history_reference_${name}`, value]));
    const h = harness({ reportOverride: { ...referenceChecks, ...diagnostics } });
    const report = runProductionReadOnly({ ...h.options, historyOffset: 0, historyReference: reference });
    assert.equal(report.production_readonly_test_passed, true);
    assert.deepEqual(report.history_reference_comparison, comparison);
    assert.ok(!Object.keys(comparison).some(name => Object.hasOwn(report.hardware_checks, `history_reference_${name}`)));
  }
  const h = harness({ reportOverride: { ...referenceChecks, history_reference_matched: false,
    history_reference_record_located: true, history_reference_timezone_matches: false,
    history_reference_heart_rate_matches: true, history_reference_steps_match: null } });
  const report = runProductionReadOnly({ ...h.options, historyOffset: 0, historyReference: reference });
  assert.equal(report.production_readonly_test_passed, false);
  assert.deepEqual(report.history_reference_comparison,
    { record_located: true, timezone_matches: false, heart_rate_matches: true, steps_match: null });
});

test('comparison diagnostics are schema3-only, nullable and do not export raw or unexpected fields', () => {
  for (const value of [undefined, null, 'private diagnostic', 'true', 0, 1, [], { secret: 'private' }]) {
    const h = harness({ reportOverride: { ...referenceChecks,
      ...Object.fromEntries(Object.keys(unknownComparison).map(name => [`history_reference_${name}`, value])),
      history_reference_comparison: { secret: 'private' }, history_reference_raw: 'private',
      history_reference_record_located_extra: 'private' } });
    const report = runProductionReadOnly({ ...h.options, historyOffset: 0, historyReference: reference });
    assert.equal(report.production_readonly_test_passed, true);
    assert.deepEqual(report.history_reference_comparison, unknownComparison);
    assert.ok(!JSON.stringify(report).includes('private'));
  }
  const diagnostics = Object.fromEntries(Object.keys(unknownComparison).map(name => [`history_reference_${name}`, true]));
  for (const schema_version of [1, 2, 99, '3']) {
    const h = harness({ reportOverride: { ...referenceChecks, ...diagnostics, schema_version } });
    const report = runProductionReadOnly({ ...h.options, historyOffset: 0, historyReference: reference });
    assert.equal(report.production_readonly_test_passed, false);
    assert.deepEqual(report.history_reference_comparison, unknownComparison);
  }
  for (const historyOffset of [undefined, 0]) {
    const h = harness({ reportOverride: { ...referenceChecks, ...diagnostics, schema_version: historyOffset === undefined ? 1 : 2 } });
    const report = runProductionReadOnly({ ...h.options, historyOffset });
    assert.equal(report.production_readonly_test_passed, true);
    assert.equal(Object.hasOwn(report, 'history_reference_comparison'), false);
  }
  const h = harness({ reportFailure: true });
  const report = runProductionReadOnly({ ...h.options, historyOffset: 0, historyReference: reference });
  assert.equal(report.production_readonly_test_passed, false);
  assert.deepEqual(report.history_reference_comparison, unknownComparison);
});

test('auto reference CLI selection is explicit, reference-only and preserves existing parse shapes', () => {
  for (const pauseMiFitness of [false, true]) {
    const args = ['serial', 'history_reference', 'auto', ...(pauseMiFitness ? ['--pause-mi-fitness'] : [])];
    const parsed = parseProductionReadOnlyArguments(args);
    assert.deepEqual(parsed, { serial: 'serial', pauseMiFitness, referenceRequested: true, selectReferenceTime: true });
    assert.equal(Object.hasOwn(parsed, 'historyOffset'), false);
  }
  for (const args of [['serial', 'history_repeat', 'auto'], ['serial', 'auto'],
    ['serial', 'history_reference', 'AUTO'], ['serial', 'history_reference', 'auto', '0'],
    ['serial', '--pause-mi-fitness', 'history_reference', 'auto']]) {
    assert.throws(() => parseProductionReadOnlyArguments(args));
  }
  for (const args of [['serial'], ['serial', 'history_repeat', '0'], ['serial', 'history_reference', '0']]) {
    assert.equal(Object.hasOwn(parseProductionReadOnlyArguments(args), 'selectReferenceTime'), false);
  }
});

test('reference time selection validates option type, reference and mutually exclusive offset before any ADB', () => {
  const options = [
    ...[null, 'true', 'false', 0, 1, {}, []].map(selectReferenceTime => ({ selectReferenceTime, historyReference: reference })),
    { selectReferenceTime: true },
    ...[0, 1, 255, null, 'auto'].map(historyOffset => ({ selectReferenceTime: true, historyOffset, historyReference: reference })),
    ...invalidReferences.map(historyReference => ({ selectReferenceTime: true, historyReference })),
  ];
  for (const option of options) {
    const h = harness();
    assert.throws(() => runProductionReadOnly({ ...h.options, ...option }));
    assert.deepEqual(h.calls, []);
  }
});

test('auto reference selection stages privately and passes selection flags without a numeric offset', () => {
  for (const pauseMiFitness of [false, true]) {
    const h = harness({ reportOverride: referenceChecks });
    const report = runProductionReadOnly({ ...h.options, selectReferenceTime: true, historyReference: reference, pauseMiFitness });
    assert.equal(report.production_readonly_test_passed, true);
    assert.equal(report.bounded_history_verification_requested, true);
    assert.equal(report.history_reference_requested, true);
    assert.equal(report.cleanup_verified, true);
    assert.equal(report.mi_fitness_process_restored, true);
    const instruments = h.calls.filter(call => call.includes('am instrument'));
    assert.equal(instruments.length, 1);
    assert.ok(instruments[0].includes('-e verify_past_repeat true -e verify_history_reference true -e history_select_reference_time true'));
    assert.ok(!instruments[0].includes('history_offset'));
    assert.deepEqual(JSON.parse(h.inputs[1]), reference);
    assert.ok(!h.calls.some(call => call.includes(JSON.stringify(reference))));
    assert.ok(h.calls.some(call => call.includes('test ! -e files/history-reference.json')));
  }
});

test('auto reference selection requires schema3 and primary match and cleans up on failures without fallback', () => {
  for (const flags of [{ reportOverride: { schema_version: 2 } }, { reportOverride: { history_reference_matched: false } },
    { referenceStageFailure: true }, { connectFailure: true }, { reportFailure: true }]) {
    const h = harness({ ...flags, reportOverride: { ...referenceChecks, ...flags.reportOverride } });
    const report = runProductionReadOnly({ ...h.options, selectReferenceTime: true, historyReference: reference, pauseMiFitness: true });
    assert.equal(report.production_readonly_test_passed, false);
    assert.equal(report.cleanup_verified, true);
    assert.ok(h.calls.filter(call => call.includes('am instrument')).length <= 1);
    assert.ok(!h.calls.some(call => call.includes('history_offset')));
    assert.ok(h.calls.includes(`shell run-as ${testPackage} rm -f files/history-reference.json`));
    assert.ok(h.calls.includes(`shell run-as ${testPackage} test ! -e files/history-reference.json`));
    assert.ok(h.calls.includes(`shell run-as ${testPackage} rmdir files/production-check.lock`));
    if (!flags.referenceStageFailure) assert.equal(report.mi_fitness_process_restored, true);
  }
});

test('history failure diagnostics expose only schema3 fixed stages and selection categories', () => {
  const stages = ['index', 'selection', 'first_transfer', 'repeat_transfer', 'retention', 'boundary', 'complete'];
  const selections = ['selected', 'empty', 'unsupported_epoch', 'unaligned_epoch', 'timezone_unavailable',
    'no_preceding_file', 'outside_window', 'ambiguous'];
  for (const stage of stages) {
    for (const selection of selections) {
      const h = harness({ reportOverride: { ...referenceChecks, test_passed: false, failure_phase: 'history',
        history_reference_matched: false, history_stage: stage, history_selection: selection } });
      const report = runProductionReadOnly({ ...h.options, selectReferenceTime: true, historyReference: reference });
      assert.equal(report.production_readonly_test_passed, false);
      assert.deepEqual(report.history_diagnostics, { stage, selection });
      assert.equal(report.cleanup_verified, true);
    }
  }
  for (const value of ['private timestamp or exception', 1, true, {}, [], null]) {
    const h = harness({ reportOverride: { ...referenceChecks, history_stage: value, history_selection: value,
      history_details: { secret: 'private' } } });
    const report = runProductionReadOnly({ ...h.options, selectReferenceTime: true, historyReference: reference });
    assert.deepEqual(report.history_diagnostics, { stage: null, selection: null });
    assert.equal(report.production_readonly_test_passed, true);
    assert.ok(!JSON.stringify(report).includes('private'));
  }
  const h = harness({ reportOverride: { ...referenceChecks, schema_version: 2,
    history_stage: 'selection', history_selection: 'empty' } });
  const report = runProductionReadOnly({ ...h.options, selectReferenceTime: true, historyReference: reference });
  assert.deepEqual(report.history_diagnostics, { stage: null, selection: null });
  assert.equal(report.production_readonly_test_passed, false);
});

test('today reference CLI accepts only explicit numeric or auto selection and preserves legacy parse shapes', () => {
  for (const pauseMiFitness of [false, true]) {
    for (const offset of ['0', '255', 'auto']) {
      const args = ['serial', 'history_reference_today', offset, ...(pauseMiFitness ? ['--pause-mi-fitness'] : [])];
      assert.deepEqual(parseProductionReadOnlyArguments(args), {
        serial: 'serial', pauseMiFitness, referenceRequested: true, referenceIndex: 'today',
        ...(offset === 'auto' ? { selectReferenceTime: true } : { historyOffset: Number(offset) }),
      });
    }
  }
  for (const args of [['serial', 'history_reference_today'],
    ...['-1', '256', '01', '0.5', 'AUTO', 'past', '--pause-mi-fitness'].map(offset => ['serial', 'history_reference_today', offset]),
    ['serial', 'history_reference_today', 'auto', '0'], ['serial', 'history_reference_today', '0', JSON.stringify(reference)],
    ['serial', '--pause-mi-fitness', 'history_reference_today', 'auto']]) {
    assert.throws(() => parseProductionReadOnlyArguments(args));
  }
  for (const args of [['serial'], ['serial', 'history_repeat', '0'],
    ['serial', 'history_reference', '0'], ['serial', 'history_reference', 'auto']]) {
    assert.equal(Object.hasOwn(parseProductionReadOnlyArguments(args), 'referenceIndex'), false);
  }
});

test('today index is reference-only and index options are validated before any ADB operation', () => {
  const invalidOptions = [
    ...[null, true, 0, 'TODAY', '', 'private index', {}, []].map(referenceIndex => ({ referenceIndex, historyOffset: 0, historyReference: reference })),
    { referenceIndex: 'today' }, { referenceIndex: 'today', historyOffset: 0 },
    { referenceIndex: 'today', selectReferenceTime: true },
    { referenceIndex: 'today', historyReference: reference },
    ...invalidReferences.map(historyReference => ({ referenceIndex: 'today', historyOffset: 0, historyReference })),
  ];
  for (const options of invalidOptions) {
    const h = harness();
    assert.throws(() => runProductionReadOnly({ ...h.options, ...options }));
    assert.deepEqual(h.calls, []);
  }
});

test('today numeric and auto reference modes add only the explicit today index flag', () => {
  for (const selection of [{ historyOffset: 0 }, { historyOffset: 255 }, { selectReferenceTime: true }]) {
    const h = harness({ reportOverride: { ...referenceChecks, history_index: 'today', history_stage: 'complete', history_selection: 'selected' } });
    const report = runProductionReadOnly({ ...h.options, ...selection, historyReference: reference, referenceIndex: 'today', pauseMiFitness: true });
    assert.equal(report.production_readonly_test_passed, true);
    assert.equal(report.history_reference_index, 'today');
    assert.equal(report.cleanup_verified, true);
    assert.equal(report.mi_fitness_process_restored, true);
    assert.deepEqual(report.history_diagnostics, { stage: 'complete', selection: 'selected' });
    const instruments = h.calls.filter(call => call.includes('am instrument'));
    assert.equal(instruments.length, 1);
    assert.ok(instruments[0].includes('-e verify_past_repeat true'));
    assert.ok(instruments[0].includes('-e verify_history_reference true'));
    assert.ok(instruments[0].includes('-e history_reference_index today'));
    assert.equal(instruments[0].includes('history_select_reference_time'), selection.selectReferenceTime === true);
    if (selection.selectReferenceTime) assert.ok(!instruments[0].includes('history_offset'));
    else assert.ok(instruments[0].includes(`-e history_offset ${selection.historyOffset}`));
    assert.deepEqual(JSON.parse(h.inputs[1]), reference);
    assert.ok(!h.calls.some(call => call.includes(JSON.stringify(reference))));
    assert.ok(!h.calls.some(call => call.includes('summary')));
  }
});

test('reference schema source must match the explicitly requested index without exporting untrusted source', () => {
  for (const referenceIndex of ['past', 'today']) {
    for (const history_index of [undefined, null, 'past', 'today', 'private source', false, 1, [], {}]) {
      const h = harness({ reportOverride: { ...referenceChecks, history_index, history_stage: 'selection', history_selection: 'empty' } });
      const report = runProductionReadOnly({ ...h.options, selectReferenceTime: true, historyReference: reference, referenceIndex });
      const sourceMatches = referenceIndex === 'today' ? history_index === 'today' :
        history_index === undefined || history_index === null || history_index === 'past';
      assert.equal(report.production_readonly_test_passed, sourceMatches);
      assert.equal(report.cleanup_verified, true);
      assert.deepEqual(report.history_diagnostics, { stage: 'selection', selection: 'empty' });
      assert.equal(Object.hasOwn(report, 'history_reference_index'), referenceIndex === 'today');
      assert.ok(!JSON.stringify(report).includes('private source'));
      assert.ok(!Object.hasOwn(report, 'history_index'));
      const instruments = h.calls.filter(call => call.includes('am instrument'));
      assert.equal(instruments.length, 1);
      assert.equal(instruments[0].includes('history_reference_index'), referenceIndex === 'today');
    }
  }
});

test('today reference failures clean up and restore Mi Fitness without retry, fallback or raw output', () => {
  for (const flags of [{ referenceStageFailure: true }, { connectFailure: true }, { reportFailure: true },
    { reportOverride: { schema_version: 2 } }, { reportOverride: { history_reference_matched: false } },
    { reportOverride: { history_index: 'past' } }]) {
    const h = harness({ ...flags, reportOverride: { ...referenceChecks, history_index: 'today',
      history_stage: 'private details', history_selection: 'private details', raw_history: 'private details', ...flags.reportOverride } });
    const report = runProductionReadOnly({ ...h.options, selectReferenceTime: true, historyReference: reference,
      referenceIndex: 'today', pauseMiFitness: true });
    assert.equal(report.production_readonly_test_passed, false);
    assert.equal(report.cleanup_verified, true);
    assert.equal(report.history_reference_index, 'today');
    assert.ok(!JSON.stringify(report).includes('private'));
    assert.ok(h.calls.filter(call => call.includes('am instrument')).length <= 1);
    assert.ok(!h.calls.some(call => /history_offset|summary|history_reference_index past/.test(call)));
    for (const file of ['auth-key', 'history-reference.json']) {
      assert.ok(h.calls.includes(`shell run-as ${testPackage} rm -f files/${file}`));
      assert.ok(h.calls.includes(`shell run-as ${testPackage} test ! -e files/${file}`));
    }
    assert.ok(h.calls.includes(`shell run-as ${testPackage} rmdir files/production-check.lock`));
    if (!flags.referenceStageFailure) assert.equal(report.mi_fitness_process_restored, true);
  }
});

const unavailableSelectionEvidence = { reference_clock_relation: null, candidate_clock_relation: null,
  after_reference_gap: null, after_reference_same_local_day: null };

test('schema3 reference selection evidence preserves only allowed categories and strict nullable booleans', () => {
  const referenceRelations = ['future', 'within_5m', 'within_1h', 'within_24h', 'older'];
  const candidateRelations = ['all_not_future', 'some_future', 'all_future'];
  const gaps = ['under_5m', 'under_1h', 'under_24h', 'at_least_24h'];
  const baseline = harness({ reportOverride: referenceChecks });
  const baselineReport = runProductionReadOnly({ ...baseline.options, historyReference: reference, selectReferenceTime: true });
  assert.deepEqual(baselineReport.history_selection_evidence, unavailableSelectionEvidence);
  for (let index = 0; index < referenceRelations.length; index++) {
    for (const sameDay of [true, false, null]) {
      const expected = { reference_clock_relation: referenceRelations[index],
        candidate_clock_relation: candidateRelations[index % candidateRelations.length],
        after_reference_gap: gaps[index % gaps.length], after_reference_same_local_day: sameDay };
      const h = harness({ reportOverride: { ...referenceChecks,
        history_reference_clock_relation: expected.reference_clock_relation,
        history_candidate_clock_relation: expected.candidate_clock_relation,
        history_after_reference_gap: expected.after_reference_gap,
        history_after_reference_same_local_day: sameDay } });
      const report = runProductionReadOnly({ ...h.options, historyReference: reference, selectReferenceTime: true });
      assert.deepEqual(report.history_selection_evidence, expected);
      assert.equal(report.production_readonly_test_passed, true);
      assert.deepEqual(report.hardware_checks, baselineReport.hardware_checks);
      assert.deepEqual(h.calls, baseline.calls);
    }
  }
  const h = harness({ reportOverride: { ...referenceChecks, history_index: 'today', history_reference_matched: false,
    history_reference_clock_relation: 'older', history_candidate_clock_relation: 'all_not_future',
    history_after_reference_gap: 'at_least_24h', history_after_reference_same_local_day: false } });
  const report = runProductionReadOnly({ ...h.options, historyReference: reference, selectReferenceTime: true, referenceIndex: 'today' });
  assert.equal(report.production_readonly_test_passed, false);
  assert.deepEqual(report.history_selection_evidence, { reference_clock_relation: 'older', candidate_clock_relation: 'all_not_future',
    after_reference_gap: 'at_least_24h', after_reference_same_local_day: false });
});

test('selection evidence drops invalid or raw values and unknown fields without changing acceptance', () => {
  for (const value of [undefined, null, 'private raw timestamp', 'true', 0, 1, [], { secret: 'private' }]) {
    const h = harness({ reportOverride: { ...referenceChecks,
      history_reference_clock_relation: value, history_candidate_clock_relation: value,
      history_after_reference_gap: value, history_after_reference_same_local_day: value,
      history_selection_evidence: { raw: 'private' }, history_after_reference_raw_gap: 'private',
      history_reference_epoch: 'private' } });
    const report = runProductionReadOnly({ ...h.options, historyReference: reference, selectReferenceTime: true });
    assert.equal(report.production_readonly_test_passed, true);
    assert.deepEqual(report.history_selection_evidence, unavailableSelectionEvidence);
    assert.ok(!JSON.stringify(report).includes('private'));
    for (const name of ['history_reference_clock_relation', 'history_candidate_clock_relation',
      'history_after_reference_gap', 'history_after_reference_same_local_day', 'history_reference_epoch']) {
      assert.equal(Object.hasOwn(report, name), false);
    }
  }
  for (const value of [true, false]) {
    const h = harness({ reportOverride: { ...referenceChecks, history_reference_clock_relation: value,
      history_candidate_clock_relation: value, history_after_reference_gap: value, history_after_reference_same_local_day: value } });
    const report = runProductionReadOnly({ ...h.options, historyReference: reference, selectReferenceTime: true });
    assert.deepEqual(report.history_selection_evidence, { ...unavailableSelectionEvidence, after_reference_same_local_day: value });
  }
  const h = harness({ reportOverride: { ...referenceChecks, history_reference_clock_relation: 'all_future',
    history_candidate_clock_relation: 'future', history_after_reference_gap: 'within_5m', history_after_reference_same_local_day: 'false' } });
  const report = runProductionReadOnly({ ...h.options, historyReference: reference, selectReferenceTime: true });
  assert.deepEqual(report.history_selection_evidence, unavailableSelectionEvidence);
});

test('selection evidence is absent outside schema3 reference reports and legacy result shapes stay unchanged', () => {
  const evidence = { history_reference_clock_relation: 'future', history_candidate_clock_relation: 'all_future',
    history_after_reference_gap: 'under_5m', history_after_reference_same_local_day: true };
  for (const schema_version of [1, 2, 99, '3']) {
    const h = harness({ reportOverride: { ...referenceChecks, ...evidence, schema_version } });
    const report = runProductionReadOnly({ ...h.options, historyReference: reference, selectReferenceTime: true });
    assert.equal(report.production_readonly_test_passed, false);
    assert.equal(Object.hasOwn(report, 'history_selection_evidence'), false);
  }
  for (const historyOffset of [undefined, 0]) {
    const oldReport = { ...referenceChecks, schema_version: historyOffset === undefined ? 1 : 2 };
    const baseline = runProductionReadOnly({ ...harness({ reportOverride: oldReport }).options, historyOffset });
    const h = harness({ reportOverride: { ...oldReport, ...evidence } });
    const report = runProductionReadOnly({ ...h.options, historyOffset });
    assert.equal(report.production_readonly_test_passed, true);
    assert.equal(Object.hasOwn(report, 'history_selection_evidence'), false);
    assert.deepEqual(report, baseline);
  }
});

test('original modes never stage or request a reference and preserve their result fields', () => {
  for (const historyOffset of [undefined, 0]) {
    const h = harness({ reportOverride: historyOffset === undefined ? {} : { ...referenceChecks, schema_version: 2 } });
    const report = runProductionReadOnly({ ...h.options, historyOffset });
    assert.equal(report.production_readonly_test_passed, true);
    assert.equal(Object.hasOwn(report, 'history_reference_requested'), false);
    assert.equal(Object.hasOwn(report, 'history_reference_comparison'), false);
    assert.equal(Object.hasOwn(report, 'history_reference_index'), false);
    assert.ok(!Object.keys(report.hardware_checks).some(key => key.startsWith('history_reference')));
    assert.ok(!h.calls.some(call => /history-reference|verify_history_reference|history_select_reference_time|history_reference_index/.test(call)));
    assert.equal(h.inputs.length, 1);
  }
});
