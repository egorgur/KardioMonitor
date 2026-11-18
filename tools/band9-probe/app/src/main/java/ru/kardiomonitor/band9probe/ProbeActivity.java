package ru.kardiomonitor.band9probe;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.AtomicFile;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import ru.kardiomonitor.band9probe.protocol.Auth;
import ru.kardiomonitor.band9probe.protocol.HeartRateSettings;
import ru.kardiomonitor.band9probe.protocol.HistoryIndex;
import ru.kardiomonitor.band9probe.protocol.Proto;
import ru.kardiomonitor.band9probe.protocol.ProbeException;
import ru.kardiomonitor.band9probe.protocol.Session;

public final class ProbeActivity extends Activity {
    private static final String TAG = "Band9Probe";
    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb");
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor();
    private TextView status;
    private Button cancel;
    private volatile BluetoothSocket socket;
    private volatile boolean cancelled, live, cleanup, finished;
    private volatile String stage = "idle";
    private Thread worker;
    private String mode, address;
    private int detailOffset = -1, detailCount = 1;
    private boolean pendingPermission;
    private JSONObject report;
    private long started;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(24, 24, 24, 24);
        status = new TextView(this);
        status.setText("Band9 Probe — isolated diagnostic\nNo clinic sync. No pairing, reset, time or settings writes.\n");
        column.addView(status);
        cancel = new Button(this);
        cancel.setText("Cancel and close");
        cancel.setOnClickListener(view -> cancelRun());
        column.addView(cancel);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(column);
        setContentView(scroll);
        if (state != null) {
            status.append("Activity recreated; not restarting the connection. Launch again explicitly.\n");
            cancel.setEnabled(false);
            return;
        }
        acceptIntent(getIntent());
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (pendingPermission || (worker != null && worker.isAlive())) { status.append("busy: new launch ignored\n"); return; }
        if (watchdog.isShutdown()) { status.append("Session closed. Start a fresh Activity.\n"); return; }
        setIntent(intent);
        acceptIntent(intent);
    }
    private void acceptIntent(Intent intent) {
        mode = intent.getStringExtra("mode");
        if (mode == null) mode = "transport";
        address = intent.getStringExtra("address");
        cancelled = false; finished = false;
        started = SystemClock.elapsedRealtime();
        report = new JSONObject();
        put("schema", 20); put("application_id", getPackageName()); put("probe_version", "0.32");
        put("mode", mode.matches("transport|auth|read|live|settings|history_index|history_read|history_sample|history_sample_next|history_repeat|history_batch|history_past_batch|history_correlate|history_target|history_target_scan|history_past_target_scan|history_past_target|history_past_repeat|history_boundary|history_passive|history_past_window|history_today_window") ? mode : "invalid");
        put("status", "running"); put("expected_model", "M2345B1"); put("expected_firmware", "2.3.98");
        put("build_provenance", "host_tests_only_no_build_time_hardware_validation");
        put("hardware_attempted", false); put("auth_key_logged", false); put("auth_packet_logging", false);
        put("version_response_metadata_logging", true);
        put("today_steps", JSONObject.NULL);
        put("today_steps_status", "not_queried_no_proven_read_only_command");
        put("limitations", new JSONArray().put("No phone or band validation during build")
                .put("SPP v2 AES-CTR has no per-message integrity in the published protocol")
                .put("No automatic reconnect/retransmit; NACK or malformed input aborts")
                .put("History read is explicit bounded daily v4/v5; no history ACK, raw persistence or clinical import"));
        if (!mode.matches("transport|auth|read|live|settings|history_index|history_read|history_sample|history_sample_next|history_repeat|history_batch|history_past_batch|history_correlate|history_target|history_target_scan|history_past_target_scan|history_past_target|history_past_repeat|history_boundary|history_passive|history_past_window|history_today_window")) { finishReport("error", "invalid_mode"); return; }
        if (!acceptHistoryRange(intent)) { finishReport("error", "invalid_history_range"); return; }
        if (mode.equals("settings")) {
            put("settings_status", "not_requested"); put("hr_settings", JSONObject.NULL);
        }
        if (mode.equals("history_index")) {
            put("history_index_status", "not_requested"); put("history_index_today", JSONObject.NULL);
            put("history_index_past", JSONObject.NULL); put("history_file_download_attempted", false);
            put("history_file_ack_attempted", false);
        }
        if (isHistoryRead()) {
            put("history_read_status", "not_requested"); put("history_files", new JSONArray());
            put("retention_index_checked", false); put("retention_index_unchanged", JSONObject.NULL);
            put("history_file_download_attempted", false); put("history_file_ack_attempted", false);
            put("raw_history_persisted", false);
            put("today_steps_status", "not_available_until_verified_daily_summary_v5");
        }
        if (Build.VERSION.SDK_INT >= 31 && (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)) {
            pendingPermission = true;
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN}, 9);
        } else {
            startRun();
        }
    }
    private boolean isHistoryRead() {
        return mode.equals("history_read") || mode.equals("history_sample") || mode.equals("history_sample_next")
                || mode.equals("history_repeat") || mode.equals("history_boundary") || mode.equals("history_passive")
                || mode.equals("history_past_window") || mode.equals("history_today_window") || isHistoryBatch();
    }
    private boolean isHistoryBatch() { return mode.equals("history_batch") || mode.equals("history_past_batch") || mode.equals("history_correlate") || mode.equals("history_target") || mode.equals("history_target_scan") || mode.equals("history_past_target_scan") || mode.equals("history_past_target") || mode.equals("history_past_repeat"); }
    private boolean acceptHistoryRange(Intent intent) {
        detailOffset = mode.equals("history_sample_next") ? 1 : -1;
        detailCount = 1;
        if (!isHistoryBatch()) return !intent.hasExtra("detail_offset") && !intent.hasExtra("detail_count");
        Object offset = intent.getExtras() == null ? null : intent.getExtras().get("detail_offset");
        Object count = intent.getExtras() == null ? null : intent.getExtras().get("detail_count");
        if (!(offset instanceof Integer) || !(count instanceof Integer)) return false;
        detailOffset = (Integer) offset; detailCount = (Integer) count;
        if (detailOffset < 0 || detailOffset > 255 || detailCount < 1 || detailCount > 2) return false;
        if ((mode.equals("history_correlate") || mode.equals("history_target") || mode.equals("history_target_scan") || mode.equals("history_past_target_scan") || mode.equals("history_past_target") || mode.equals("history_past_repeat")) && detailCount != 1) return false;
        put("detail_offset", detailOffset); put("detail_count", detailCount);
        put("history_offset_scope", "fresh_index_order_not_durable_cursor");
        put("history_requested_index_scope", mode.equals("history_batch") ? "today" : "past");
        if (mode.equals("history_correlate")) put("history_correlation_header_only", true);
        if (mode.equals("history_target") || mode.equals("history_target_scan") || mode.equals("history_past_target_scan") || mode.equals("history_past_target") || mode.equals("history_past_repeat")) put("history_target_demultiplexed", true);
        if (mode.equals("history_past_target") || mode.equals("history_past_repeat")) {
            put("history_time_values_verified", false); put("history_full_coverage_verified", false);
        }
        if (mode.equals("history_past_repeat")) {
            put("repeat_requested", true); put("repeat_read_count", 0);
            put("repeat_bytes_identical", JSONObject.NULL); put("repeat_aggregate_consistent", JSONObject.NULL);
        }
        if (mode.equals("history_target_scan") || mode.equals("history_past_target_scan")) put("history_correlation_header_only", true);
        return true;
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request != 9 || !pendingPermission) return;
        pendingPermission = false;
        if (grants.length != 2 || grants[0] != PackageManager.PERMISSION_GRANTED || grants[1] != PackageManager.PERMISSION_GRANTED) {
            finishReport("error", "bluetooth_permission_denied");
        } else if (!cancelled) { startRun(); }
        else { finishReport("cancelled", "cancelled_before_start"); }
    }
    private void startRun() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        worker = new Thread(this::runProbe, "Band9Probe-session");
        worker.start();
    }
    private void runProbe() {
        String result = "ok", code = "complete";
        long sessionDeadline = SystemClock.elapsedRealtime() + 75000;
        ScheduledFuture<?> overall = watchdog.schedule(this::closeSocket, 75, TimeUnit.SECONDS);
        Session session = null;
        byte[] key = null;
        try {
            phase("select_bonded_device");
            BluetoothManager manager = getSystemService(BluetoothManager.class);
            BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
            if (adapter == null) throw new ProbeException("bluetooth_not_supported");
            if (!adapter.isEnabled()) throw new ProbeException("bluetooth_disabled");
            BluetoothDevice device = selectBonded(adapter);
            put("bonded", true); put("transport", "secure_rfcomm_spp");
            if (cancelled) throw new InterruptedIOException();
            if (adapter.isDiscovering()) adapter.cancelDiscovery();
            socket = device.createRfcommSocketToServiceRecord(SPP);
            if (cancelled) throw new InterruptedIOException();
            phase("rfcomm_connect");
            ScheduledFuture<?> connectTimeout = watchdog.schedule(this::closeSocket, 12, TimeUnit.SECONDS);
            put("hardware_attempted", true); persist();
            try { socket.connect(); } finally { connectTimeout.cancel(false); }
            put("rfcomm_connected", true);
            DeadlineInput input = new DeadlineInput(socket.getInputStream(), sessionDeadline);
            OutputStream rawOutput = socket.getOutputStream();
            OutputStream output = new OutputStream() {
                @Override public void write(int value) throws IOException { write(new byte[]{(byte) value}); }
                @Override public void write(byte[] bytes, int offset, int count) throws IOException {
                    if (cancelled && !cleanup) throw new InterruptedIOException();
                    ScheduledFuture<?> timeout = watchdog.schedule(ProbeActivity.this::closeSocket, 3, TimeUnit.SECONDS);
                    try { rawOutput.write(bytes, offset, count); } finally { timeout.cancel(false); }
                }
                @Override public void flush() throws IOException { rawOutput.flush(); }
            };
            session = new Session(input, output);
            phase("version_negotiation"); input.until(6000);
            int version = session.negotiate(metadata -> {
                JSONObject safe = new JSONObject();
                try {
                    safe.put("channel", metadata.channel).put("opcode", metadata.opcode)
                            .put("data_type", metadata.dataType).put("payload_length", metadata.payloadLength);
                    JSONArray prefix = new JSONArray();
                    for (int value : metadata.versionPrefix()) prefix.put(value);
                    safe.put("version_prefix", prefix);
                    put("version_response", safe);
                    JSONObject event = new JSONObject().put("event", "version_response").put("metadata", safe);
                    Log.i(TAG, event.toString());
                    persist();
                } catch (JSONException ignored) {}
            });
            put("spp_version", version);
            if (version != 1 && version != 2) throw new ProbeException("unsupported_spp_version");
            if (!mode.equals("transport")) {
                phase("encrypted_auth");
                key = readKey(); put("auth_key_format_validated", true);
                input.until(12000);
                session.authenticate(key, Build.VERSION.SDK_INT, Build.MODEL);
                Auth.wipe(key); key = null;
                put("authenticated", true); put("watch_hmac_verified", true);
                if (mode.equals("read") || mode.equals("live") || mode.equals("settings") || mode.equals("history_index") || isHistoryRead()) {
                    phase("read_battery"); input.until(6000);
                    try (Proto command = session.battery(); Proto system = command.message(4);
                         Proto power = system.message(2); Proto battery = power.message(1)) {
                        long level = battery.number(1);
                        if (level < 0 || level > 100) throw new ProbeException("battery_level_range");
                        put("battery_percent", level);
                    }
                    phase("read_device_info"); input.until(6000);
                    try (Proto command = session.deviceInfo(); Proto system = command.message(4); Proto info = system.message(3)) {
                        String model = info.text(4), firmware = info.text(2);
                        put("device_model", model); put("device_firmware", firmware);
                        put("target_model_matches", model.equals("M2345B1"));
                        put("target_firmware_matches", firmware.equals("2.3.98"));
                        // Deliberately do not retain the serial number or the MAC address.
                    }
                }
                if (mode.equals("settings")) {
                    if (!report.optBoolean("target_model_matches") || !report.optBoolean("target_firmware_matches")) {
                        put("settings_status", "target_mismatch");
                        throw new ProbeException("settings_target_mismatch");
                    }
                    put("settings_status", "query_pending"); phase("read_hr_settings"); input.until(6000);
                    HeartRateSettings settings = session.heartRateSettings();
                    put("hr_settings", new JSONObject(settings.values));
                    String settingsStatus = (String) settings.values.get("status");
                    put("settings_status", settingsStatus);
                    if (!settingsStatus.equals("present")) { result = "partial"; code = "hr_settings_absent"; }
                }
                if (mode.equals("history_index")) {
                    if (!report.optBoolean("target_model_matches") || !report.optBoolean("target_firmware_matches")) {
                        put("history_index_status", "target_mismatch");
                        throw new ProbeException("history_index_target_mismatch");
                    }
                    put("history_index_status", "query_pending"); phase("read_history_index_today"); input.until(6000);
                    HistoryIndex today = session.historyIndexToday();
                    put("history_index_today", new JSONObject(today.values));
                    phase("read_history_index_past"); input.until(6000);
                    HistoryIndex past = session.historyIndexPast();
                    put("history_index_past", new JSONObject(past.values));
                    boolean bothPresent = today.values.get("status").equals("present") && past.values.get("status").equals("present");
                    put("history_index_status", bothPresent ? "present" : "index_field_absent");
                    if (!bothPresent) { result = "partial"; code = "history_index_field_absent"; }
                }
                if (isHistoryRead()) {
                    if (!report.optBoolean("target_model_matches") || !report.optBoolean("target_firmware_matches")) {
                        put("history_read_status", "target_mismatch");
                        throw new ProbeException("history_read_target_mismatch");
                    }
                    put("history_read_status", "query_pending");
                    JSONArray files = report.getJSONArray("history_files");
                    Session.HistoryObserver historyObserver = new Session.HistoryObserver() {
                        @Override public long referenceTimeMillis() {
                            return mode.equals("history_past_target") || mode.equals("history_past_repeat") ? System.currentTimeMillis() : -1L;
                        }
                        @Override public void onStage(String stage) {
                            if (stage.equals("history_read_file")) put("history_file_download_attempted", true);
                            phase(stage);
                            input.until(stage.equals("history_read_file") ? 30000 : 6000);
                        }
                        @Override public void onSelection(int count) { put("history_selected_file_count", count); }
                        @Override public void onCoverage(int candidates, int omitted, boolean sample) {
                            put("history_candidate_file_count", candidates); put("history_omitted_file_count", omitted);
                            put("history_coverage", sample ? "bounded_sample_not_full_history" : "allowed_daily_files_only");
                            put("history_selection_policy", isHistoryBatch() ? "explicit_unique_details_range_in_fresh_index"
                                    : mode.equals("history_sample_next") ? "next_details_only_in_index" : sample ? "single_summary_then_first_details_in_index" : "all_unique_allowed_up_to_eight");
                        }
                        @Override public void onDetailCoverage(int candidates, Boolean satisfied) {
                            put("history_detail_candidate_file_count", candidates);
                            put("requested_detail_count_satisfied", satisfied == null ? JSONObject.NULL : satisfied);
                        }
                        @Override public void onPassiveWindow(long deadlineMillis) {
                            phase("history_passive_observation"); input.untilAbsolute(deadlineMillis);
                        }
                        @Override public void onPastWindow(long deadlineMillis) {
                            phase("history_past_window_observation"); input.untilAbsolute(deadlineMillis);
                        }
                        @Override public void onTodayWindow(long deadlineMillis) {
                            phase("history_today_window_observation"); input.untilAbsolute(deadlineMillis);
                        }
                        @Override public void onBoundary(java.util.Map<String, Object> safeSummary) {
                            String field = mode.equals("history_today_window") ? "history_today_window" : mode.equals("history_past_window") ? "history_past_window"
                                    : mode.equals("history_passive") ? "history_passive" : "history_boundary";
                            put(field, new JSONObject(safeSummary)); persist();
                        }
                        @Override public void onFile(java.util.Map<String, Object> safeSummary) {
                            files.put(new JSONObject(safeSummary));
                            if (mode.equals("history_past_repeat") && "requested_exact".equals(safeSummary.get("demux_route")))
                                put("repeat_read_count", report.optInt("repeat_read_count") + 1);
                            Object steps = safeSummary.get("today_steps");
                            if (steps instanceof Long) {
                                put("today_steps", steps); put("today_steps_status", "verified_daily_summary_v5");
                            }
                            persist();
                        }
                    };
                    java.util.Map<String, Object> history = mode.equals("history_today_window")
                            ? session.historyTodayWindow(historyObserver, SystemClock::elapsedRealtime)
                            : mode.equals("history_past_repeat") ? session.historyPastRepeat(historyObserver, detailOffset)
                            : mode.equals("history_past_target") ? session.historyPastTarget(historyObserver, detailOffset)
                            : mode.equals("history_past_target_scan") ? session.historyPastTargetScan(historyObserver, detailOffset)
                            : mode.equals("history_target_scan") ? session.historyTargetScan(historyObserver, detailOffset)
                            : mode.equals("history_past_window") ? session.historyPastWindow(historyObserver, SystemClock::elapsedRealtime)
                            : mode.equals("history_passive") ? session.historyPassive(historyObserver, SystemClock::elapsedRealtime)
                            : mode.equals("history_boundary") ? session.historyBoundary(historyObserver)
                            : mode.equals("history_target") ? session.historyTarget(historyObserver, detailOffset)
                            : mode.equals("history_correlate") ? session.historyCorrelate(historyObserver, detailOffset)
                            : session.historyRead(historyObserver, !mode.equals("history_read"), mode.equals("history_repeat"), detailOffset, detailCount, mode.equals("history_past_batch"));
                    put("history_detail_candidate_file_count", history.get("detail_candidate_file_count"));
                    put("requested_detail_count_satisfied", history.get("requested_detail_count_satisfied"));
                    if (history.containsKey("retention_index_checked")) put("retention_index_checked", history.get("retention_index_checked"));
                    Object unchanged = history.get("retention_index_unchanged");
                    put("retention_index_unchanged", unchanged == null ? JSONObject.NULL : unchanged);
                    if (history.containsKey("downloaded_bytes")) put("history_downloaded_bytes", history.get("downloaded_bytes"));
                    if (history.containsKey("repeat_requested")) put("repeat_requested", history.get("repeat_requested"));
                    if (history.containsKey("repeat_read_count")) put("repeat_read_count", history.get("repeat_read_count"));
                    if (history.containsKey("repeat_aggregate_consistent")) put("repeat_aggregate_consistent", history.get("repeat_aggregate_consistent"));
                    if (history.containsKey("repeat_bytes_identical")) put("repeat_bytes_identical", history.get("repeat_bytes_identical"));
                    String historyStatus = (String) history.get("status");
                    put("history_read_status", historyStatus);
                    if (!historyStatus.equals("verified")) { result = "partial"; code = historyStatus; }
                }
                if (mode.equals("live")) {
                    if (!report.optBoolean("target_model_matches") || !report.optBoolean("target_firmware_matches"))
                        throw new ProbeException("live_target_mismatch");
                    phase("live_30_seconds");
                    JSONArray readings = new JSONArray();
                    put("live_readings", readings); put("live_duration_ms", 30000);
                    live = true;
                    try {
                        input.until(30000);
                        put("live_start_attempted", true);
                        session.liveStart();
                        put("live_start_written", true);
                        while (!cancelled) {
                            try (Proto command = session.nextLive(); Proto health = command.message(10); Proto stats = health.message(39)) {
                                JSONObject sample = new JSONObject();
                                sample.put("elapsed_ms", SystemClock.elapsedRealtime() - started);
                                if (stats.has(4)) {
                                    long hr = stats.number(4);
                                    if (hr >= 25 && hr <= 240) sample.put("heart_rate_bpm", hr);
                                    else sample.put("heart_rate_status", "not_ready_or_out_of_range");
                                } else sample.put("heart_rate_status", "field_absent");
                                if (stats.has(1)) {
                                    long steps = stats.number(1);
                                    if (steps >= 0 && steps <= 1000000) sample.put("realtime_steps", steps);
                                }
                                if (readings.length() < 32) readings.put(sample);
                            }
                        }
                    } catch (SocketTimeoutException end) {
                        put("live_window_complete", true);
                    } finally {
                        cleanup = true;
                        put("live_stop_attempted", true);
                        ScheduledFuture<?> stopTimeout = watchdog.schedule(this::closeSocket, 2500, TimeUnit.MILLISECONDS);
                        try { session.liveStop(); put("live_stop_written", true); }
                        catch (Exception ignored) { put("live_stop_written", false); }
                        finally { stopTimeout.cancel(false); cleanup = false; live = false; }
                    }
                    put("live_stop_acknowledged", false);
                    if (!report.optBoolean("live_stop_written")) throw new ProbeException("live_stop_failed_connection_closed");
                    int heartRateCount = 0;
                    for (int i = 0; i < readings.length(); i++) {
                        if (readings.getJSONObject(i).has("heart_rate_bpm")) heartRateCount++;
                    }
                    put("heart_rate_sample_count", heartRateCount);
                    put("live_result", heartRateCount == 0 ? "no_valid_heart_rate_samples" : "heart_rate_samples_received");
                    if (heartRateCount == 0) { result = "partial"; code = "no_valid_heart_rate_samples"; }
                }
                if (mode.equals("read")) { result = "partial"; code = "today_steps_no_proven_read_only_command"; }
            }
        } catch (ProbeException e) { result = "error"; code = e.code; }
        catch (SocketTimeoutException e) { result = "error"; code = "read_deadline"; }
        catch (InterruptedIOException e) { result = "cancelled"; code = "cancelled"; }
        catch (SecurityException e) { result = "error"; code = "bluetooth_permission_error"; }
        catch (GeneralSecurityException e) { result = "error"; code = "crypto_validation_failed"; }
        catch (IOException e) { result = "error"; code = "io_failure_or_socket_deadline"; }
        catch (Exception e) { result = "error"; code = "internal_error"; }
        finally {
            Auth.wipe(key);
            if (session != null) {
                put("transmitted_frames", session.transmittedFrames); put("received_frames", session.receivedFrames);
                session.close();
            }
            closeSocket(); overall.cancel(false);
            put("socket_closed", true);
            if (cancelled) { result = "cancelled"; code = "cancelled"; }
            if (mode.equals("settings") && report.optString("settings_status").equals("query_pending"))
                put("settings_status", cancelled ? "cancelled" : "query_failed");
            if (mode.equals("history_index") && report.optString("history_index_status").equals("query_pending"))
                put("history_index_status", cancelled ? "cancelled" : "query_failed");
            if (isHistoryRead() && report.optString("history_read_status").equals("query_pending"))
                put("history_read_status", cancelled ? "cancelled" : "query_failed");
            finishReport(result, code);
            watchdog.shutdown();
        }
    }
    private BluetoothDevice selectBonded(BluetoothAdapter adapter) throws ProbeException {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            throw new ProbeException("bluetooth_permission_denied");
        if (address != null && !BluetoothAdapter.checkBluetoothAddress(address.toUpperCase(java.util.Locale.ROOT)))
            throw new ProbeException("invalid_address");
        List<BluetoothDevice> candidates = new ArrayList<>();
        for (BluetoothDevice device : adapter.getBondedDevices()) {
            if (address != null) {
                if (address.equalsIgnoreCase(device.getAddress())) candidates.add(device);
            } else {
                String name = device.getName();
                if (name != null && name.matches("^Xiaomi Smart Band 9 [0-9A-Fa-f]{4}$")) candidates.add(device);
            }
        }
        if (candidates.isEmpty()) throw new ProbeException("bonded_band_not_found");
        if (candidates.size() != 1) throw new ProbeException("ambiguous_bonded_band_use_address_extra");
        return candidates.get(0);
    }
    private byte[] readKey() throws IOException {
        byte[] hex = new byte[33];
        try (FileInputStream input = new FileInputStream(new File(getFilesDir(), "auth-key"))) {
            int count = 0, n;
            while (count < hex.length && (n = input.read(hex, count, hex.length - count)) > 0) count += n;
            if (count != 32) throw new ProbeException("auth_key_requires_exactly_32_hex_characters");
            byte[] exact = java.util.Arrays.copyOf(hex, 32);
            try { return Auth.decodeKeyHex(exact); } finally { Auth.wipe(exact); }
        } catch (java.io.FileNotFoundException e) {
            throw new ProbeException("auth_key_missing_or_unreadable");
        } finally { Auth.wipe(hex); }
    }
    private final class DeadlineInput extends InputStream {
        private final InputStream delegate;
        private final long sessionDeadline;
        private long deadline;
        DeadlineInput(InputStream delegate, long sessionDeadline) { this.delegate = delegate; this.sessionDeadline = sessionDeadline; }
        void until(long millis) { deadline = Math.min(SystemClock.elapsedRealtime() + millis, sessionDeadline); }
        void untilAbsolute(long millis) { deadline = Math.min(millis, sessionDeadline); }
        @Override public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1); return n == -1 ? -1 : one[0] & 255;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            if (length == 0) return 0;
            while (true) {
                if (cancelled) throw new InterruptedIOException();
                if (SystemClock.elapsedRealtime() >= deadline) throw new SocketTimeoutException();
                int available = delegate.available();
                if (available > 0) return delegate.read(bytes, offset, Math.min(length, available));
                try { Thread.sleep(10); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new InterruptedIOException(); }
            }
        }
    }
    private void cancelRun() {
        cancelled = true;
        if (!live) closeSocket();
    }
    private void closeSocket() {
        BluetoothSocket current = socket;
        if (current != null) try { current.close(); } catch (IOException ignored) {}
    }
    private void phase(String value) {
        stage = value; put("stage", value);
        JSONObject event = new JSONObject();
        try { event.put("event", "phase").put("stage", value); } catch (JSONException ignored) {}
        Log.i(TAG, event.toString());
        runOnUiThread(() -> status.append(value + "\n"));
        persist();
    }
    private synchronized void put(String key, Object value) {
        try { report.put(key, value); } catch (JSONException ignored) {}
    }
    private synchronized void persist() {
        AtomicFile file = new AtomicFile(new File(getFilesDir(), "report.json"));
        FileOutputStream output = null;
        try {
            output = file.startWrite();
            output.write(report.toString(2).getBytes(StandardCharsets.UTF_8));
            file.finishWrite(output);
        } catch (Exception ignored) {
            if (output != null) file.failWrite(output);
            Log.e(TAG, "{\"event\":\"report_write_failed\"}");
        }
    }
    private void finishReport(String result, String code) {
        put("status", result); put("code", code); put("final_stage", stage);
        put("elapsed_ms", SystemClock.elapsedRealtime() - started);
        persist();
        synchronized (this) { Log.i(TAG, report.toString()); }
        finished = true;
        runOnUiThread(() -> {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            status.append(result + ": " + code + "\nReport: private files/report.json\n"); cancel.setEnabled(false);
        });
    }
    @Override protected void onStop() {
        super.onStop();
        if (!pendingPermission && worker != null && worker.isAlive() && !finished) cancelRun();
    }
    @Override protected void onDestroy() {
        cancelRun();
        if (worker == null || !worker.isAlive()) watchdog.shutdown();
        super.onDestroy();
    }
}
