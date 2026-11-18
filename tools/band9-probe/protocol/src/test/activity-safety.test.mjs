import { readFileSync } from 'node:fs';
import assert from 'node:assert/strict';
import test from 'node:test';

const root = new URL('../../../', import.meta.url);
const read = path => readFileSync(new URL(path, root), 'utf8');
const activity = read('app/src/main/java/ru/kardiomonitor/band9probe/ProbeActivity.java');
const protocol = name => read(`protocol/src/main/java/ru/kardiomonitor/band9probe/protocol/${name}.java`);

test('screen stays on only between active startRun and finishReport; no system timeout writes', () => {
  const start = activity.slice(activity.indexOf('private void startRun()'), activity.indexOf('private void runProbe()'));
  const finish = activity.slice(activity.indexOf('private void finishReport('), activity.indexOf('@Override protected void onStop()'));
  assert.match(start, /addFlags\(WindowManager\.LayoutParams\.FLAG_KEEP_SCREEN_ON\)/);
  assert.match(finish, /clearFlags\(WindowManager\.LayoutParams\.FLAG_KEEP_SCREEN_ON\)/);
  assert.equal(activity.match(/FLAG_KEEP_SCREEN_ON/g)?.length, 2);
  assert.doesNotMatch(activity, /Settings\.System|SCREEN_OFF_TIMEOUT|WakeLock|newWakeLock/);
});

test('overall75s is absolute and caps stage deadlines; history file30s index6s', () => {
  assert.match(activity, /sessionDeadline = SystemClock\.elapsedRealtime\(\) \+ 75000/);
  assert.match(activity, /watchdog\.schedule\(this::closeSocket, 75, TimeUnit\.SECONDS\)/);
  assert.match(activity, /deadline = Math\.min\(SystemClock\.elapsedRealtime\(\) \+ millis, sessionDeadline\)/);
  assert.match(activity, /input\.until\(stage\.equals\("history_read_file"\) \? 30000 : 6000\)/);
});

test('isolated app identity no Internet or storage permissions; no raw file persistence in codecs', () => {
  const manifest = read('app/src/main/AndroidManifest.xml');
  const gradle = read('app/build.gradle');
  assert.match(gradle, /applicationId 'ru\.kardiomonitor\.band9probe'/);
  assert.match(gradle, /versionCode 32/);
  assert.match(gradle, /versionName '0\.32'/);
  assert.match(activity, /put\("schema", 20\)/);
  assert.match(activity, /put\("probe_version", "0\.32"\)/);
  assert.doesNotMatch(manifest, /permission\.(INTERNET|READ_EXTERNAL_STORAGE|WRITE_EXTERNAL_STORAGE|MANAGE_EXTERNAL_STORAGE)/);
  for (const name of ['HistoryFile', 'HistorySelection', 'DailyFileSummary', 'HistoryDemultiplexer', 'HistoryBoundary', 'Session']) {
    assert.doesNotMatch(protocol(name), /FileOutputStream|Files\.write|android\.util\.Log|System\.out/);
  }
  assert.match(activity, /put\("history_file_ack_attempted", false\)/);
  assert.match(activity, /put\("raw_history_persisted", false\)/);
});

test('explicit batch validates typed mandatory range before permissions and Bluetooth work', () => {
  assert.ok(activity.indexOf('if (!acceptHistoryRange(intent))') < activity.indexOf('requestPermissions('));
  assert.match(activity, /!intent\.hasExtra\("detail_offset"\) && !intent\.hasExtra\("detail_count"\)/);
  assert.match(activity, /offset instanceof Integer/);
  assert.match(activity, /count instanceof Integer/);
  assert.match(activity, /detailOffset < 0 \|\| detailOffset > 255 \|\| detailCount < 1 \|\| detailCount > 2/);
  assert.match(activity, /fresh_index_order_not_durable_cursor/);
  assert.match(activity, /detailOffset, detailCount, mode\.equals\("history_past_batch"\)\)/);
});

test('correlation is explicit single-target and never reaches activity parser or another fetch', () => {
  assert.match(activity, /\(mode\.equals\("history_correlate"\) \|\| mode\.equals\("history_target"\) \|\| mode\.equals\("history_target_scan"\) \|\| mode\.equals\("history_past_target_scan"\) \|\| mode\.equals\("history_past_target"\) \|\| mode\.equals\("history_past_repeat"\)\) && detailCount != 1/);
  assert.match(activity, /session\.historyCorrelate\(historyObserver, detailOffset\)/);
  const session = protocol('Session');
  const correlate = session.slice(session.indexOf('private Map<String, Object> correlateFiles('), session.indexOf('public void liveStart()'));
  assert.equal(correlate.match(/send\(HistoryFile\.request/g)?.length, 1);
  assert.equal(correlate.match(/observer\.onStage\("history_read_file"\)/g)?.length, 1);
  assert.match(correlate, /ordinal <= 2/);
  assert.match(correlate, /verifyHeaderOnly\(\)/);
  assert.doesNotMatch(correlate, /verifyAndSummarize|DailyFileSummary\.parse|catch\s*\(/);
  assert.match(session, /historyBulkPayloadBytes > HistoryFile\.MAX_BYTES/);
  assert.match(session, /\+\+historyBulkBeforeFetch > 32/);
});

test('target modes use one fetch per explicit read with one shared deadline and exact-only parsing', () => {
  assert.match(activity, /session\.historyTarget\(historyObserver, detailOffset\)/);
  assert.match(activity, /put\("history_target_demultiplexed", true\)/);
  const session = protocol('Session');
  const target = session.slice(session.indexOf('public Map<String, Object> historyTarget('), session.indexOf('private Map<String, Object> historyRead('));
  assert.equal(target.match(/send\(HistoryFile\.request/g)?.length, 1);
  assert.equal(target.match(/onStage\("history_read_file"\)/g)?.length, 1);
  assert.match(target, /demux\.requireBoundary\(\)/);
  assert.match(target, /retention_index_unchanged/);
  const demux = protocol('HistoryDemultiplexer');
  assert.match(demux, /if \(candidate && exact\) \{\s*if \(!headerScan\) file\.verifyAndSummarize\(observer\.referenceTimeMillis\(\)\)/);
  assert.equal(demux.match(/file\.verifyAndSummarize\(/g)?.length, 1);
  assert.match(demux, /\+\+afterRequest >= \(pastOnly \? 16 : headerScan \? 6 : 2\)/);
  assert.match(demux, /completed >= 16/);
  assert.match(demux, /\+\+waitingFrames > 32/);
});

test('boundary mode only observes two indices with zero fetch or parser and bounded deferred IDs', () => {
  assert.match(activity, /session\.historyBoundary\(historyObserver\)/);
  assert.match(activity, /String field = mode\.equals\("history_today_window"\) \? "history_today_window" : mode\.equals\("history_past_window"\) \? "history_past_window"/);
  const session = protocol('Session');
  const boundary = session.slice(session.indexOf('public Map<String, Object> historyBoundary('), session.indexOf('public Map<String, Object> historyTarget('));
  assert.match(boundary, /new int\[\]\{2, 1\}/);
  assert.doesNotMatch(boundary, /HistoryFile\.request|history_read_file|verifyAndSummarize|liveStart|liveStop/);
  assert.match(session, /historyBoundary != null && subtype != 1 && subtype != 2/);
  const quarantine = protocol('HistoryBoundary');
  assert.doesNotMatch(quarantine, /verifyAndSummarize|DailyFileSummary\.parse/);
  assert.match(quarantine, /entries\.size\(\) >= 16/);
  assert.match(quarantine, /\+\+frames > 32/);
  assert.match(quarantine, /Auth\.wipe\(entry\.id\)/);
});

test('passive window is absolute pre-index six seconds with zero application sends and no quiet-stream claim', () => {
  assert.match(activity, /session\.historyPassive\(historyObserver, SystemClock::elapsedRealtime\)/);
  assert.match(activity, /onPassiveWindow\(long deadlineMillis\)/);
  assert.match(activity, /input\.untilAbsolute\(deadlineMillis\)/);
  assert.match(activity, /deadline = Math\.min\(millis, sessionDeadline\)/);
  const session = protocol('Session');
  const passive = session.slice(session.indexOf('public Map<String, Object> historyPassive('), session.indexOf('public Map<String, Object> historyPastWindow('));
  assert.equal(passive.match(/observer\.onPassiveWindow\(deadline\)/g)?.length, 1);
  assert.match(passive, /clock\.getAsLong\(\) \+ 6000L/);
  assert.match(passive, /if \(clock\.getAsLong\(\) < deadline\) throw e/);
  assert.match(passive, /new HistoryBoundary\(observer, false\)/);
  assert.match(passive, /"transport_boundary_at_deadline", "not_verified"/);
  assert.match(passive, /"absence_of_stream_proven", false/);
  assert.doesNotMatch(passive, /send\(|historyIndex|HistoryFile\.request|verifyAndSummarize|liveStart|liveStop/);
  assert.match(session, /if \(historyPassiveActive\) throw new ProbeException\("history_passive_command_not_allowed"\)/);
  assert.match(session, /historyPassiveActive \? null : !pending\.contains\(boundaryRequestSequence\)/);
});

test('past-only window sends once and never resets its deadline after index response', () => {
  assert.match(activity, /session\.historyPastWindow\(historyObserver, SystemClock::elapsedRealtime\)/);
  assert.match(activity, /onPastWindow\(long deadlineMillis\)/);
  const session = protocol('Session');
  const past = session.slice(session.indexOf('public Map<String, Object> historyPastWindow('), session.indexOf('public Map<String, Object> historyBoundary('));
  assert.equal(past.match(/observer\.onPastWindow\(deadline\)/g)?.length, 1);
  assert.equal(past.match(/commands = 1; send\(subtype == 2 \? HistoryIndex\.pastRequest\(\) : HistoryIndex\.todayRequest\(\), false\)/g)?.length, 1);
  assert.match(past, /clock\.getAsLong\(\) \+ 6000L/);
  assert.match(past, /if \(clock\.getAsLong\(\) < deadline\) throw e/);
  assert.match(past, /if \(!responseSeen\) throw new ProbeException\(windowCode\("index_not_received"\)\)/);
  assert.match(past, /incoming\.number\(2\) != subtype \|\| responseSeen/);
  assert.match(past, /return historyIndexWindow\(observer, clock, 2\)/);
  assert.match(past, /return historyIndexWindow\(observer, clock, 1\)/);
  assert.doesNotMatch(past, /onStage\(|HistoryFile\.request|verifyAndSummarize|liveStart|liveStop/);
  assert.match(session, /!historyPastWindowSendAllowed \|\| handshake \|\| permitted\.number\(1\) != 8 \|\| subtype != historyWindowSubtype/);
  assert.match(session, /historyPastWindowSendAllowed = false/);
  assert.match(protocol('HistoryBoundary'), /lastSubtype = subtype; lastPhase = phase/);
});

test('target scan stays header-only with six post-request files and unchanged global bounds', () => {
  assert.match(activity, /session\.historyTargetScan\(historyObserver, detailOffset\)/);
  const session = protocol('Session');
  assert.match(session, /return historyTarget\(observer, detailOffset, true, false\)/);
  assert.match(session, /return historyTarget\(observer, detailOffset, true, true\)/);
  assert.match(session, /historyPastTargetScanActive && subtype != 2 && subtype != 3/);
  assert.match(activity, /session\.historyPastTargetScan\(historyObserver, detailOffset\)/);
  assert.match(session, /if \(headerScan\) \{\s*demux\.requireBoundary\(\);\s*result\.put\("status", "diagnostic_target_scan_exact"\);\s*return result;/);
  assert.match(protocol('HistoryDemultiplexer'), /if \(!headerScan\) file\.verifyAndSummarize\(observer\.referenceTimeMillis\(\)\)/);
  assert.match(protocol('HistoryDemultiplexer'), /completed >= 16/);
  assert.match(protocol('HistoryDemultiplexer'), /payloadBytes > HistoryFile\.MAX_BYTES/);
});

test('past aggregate is explicit exact-only and keeps no-today no-ACK gates and unverified value coverage labels', () => {
  assert.match(activity, /session\.historyPastTarget\(historyObserver, detailOffset\)/);
  assert.match(activity, /put\("history_time_values_verified", false\)/);
  assert.match(activity, /put\("history_full_coverage_verified", false\)/);
  const session = protocol('Session');
  assert.match(session, /return historyTarget\(observer, detailOffset, false, true\)/);
  assert.match(session, /historyPastTargetScanActive && subtype != 2 && subtype != 3/);
  assert.match(session, /if \(!pastOnly\) \{\s*observer\.onStage\("history_read_index_today_metadata_only"\)/);
  assert.match(session, /before\.unchanged\(after\)/);
  assert.match(session, /"verified_target_sample"/);
});

test('past repeat is opt-in two reads same ID without extending stage deadline or resetting budgets', () => {
  assert.match(activity, /session\.historyPastRepeat\(historyObserver, detailOffset\)/);
  assert.match(activity, /put\("repeat_bytes_identical", JSONObject\.NULL\)/);
  const session = protocol('Session');
  assert.match(session, /return historyTarget\(observer, detailOffset, false, true, true\)/);
  const target = session.slice(session.indexOf('public Map<String, Object> historyTarget('), session.indexOf('private Map<String, Object> historyRead('));
  assert.match(target, /for \(int read = 0; read < \(repeat \? 2 : 1\); read\+\+\)/);
  assert.match(target, /if \(read == 1\) demux\.repeat\(\);\s*send\(HistoryFile\.request\(before\.id\(0\)\), false\)/);
  assert.equal(target.match(/onStage\("history_read_file"\)/g)?.length, 1);
  assert.match(target, /"verified_target_repeat_sample"/);
  const demux = protocol('HistoryDemultiplexer');
  const repeat = demux.slice(demux.indexOf('void repeat()'), demux.indexOf('int exactReads()'));
  assert.doesNotMatch(repeat, /payloadBytes =|completed =|waitingFrames =|afterRequest =/);
  assert.match(repeat, /fetchCount != 1/);
  assert.match(demux, /MessageDigest\.isEqual\(firstDigest, digest\)/);
  assert.match(demux, /Auth\.wipe\(target, firstDigest\)/);
});

test('history report uses safe projection not raw chunks and foreground cancellation closes socket', () => {
  assert.match(activity, /files\.put\(new JSONObject\(safeSummary\)\)/);
  assert.match(activity, /if \(!live\) closeSocket\(\)/);
  const summary = protocol('HistoryFile').slice(protocol('HistoryFile').indexOf('public Map<String, Object> summary()'));
  assert.doesNotMatch(summary, /safe\.put\("(id|timestamp|date|raw|payload|heart_rate)"/);
  assert.match(protocol('Session'), /subtype != 1 && subtype != 3 && !\(historySampleActive && subtype == 2\)/);
});
