package ru.kardiomonitor.band9probe.protocol;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;

/** A single connection, no automatic initialization, sync, reconnect, or command retries. */
public final class Session implements AutoCloseable {
    private final InputStream in;
    private final OutputStream out;
    private final Auth auth = new Auth();
    private final Deque<Integer> pending = new ArrayDeque<>();
    private int version, serial, encryptionCounter, expectedRx, historyBulkBeforeFetch, historyBulkBeforeFetchBytes, historyBulkPayloadBytes;
    private boolean authenticated, settingsTargetVerified, historyReadActive, historySampleActive, historyPassiveActive;
    private boolean historyPastWindowActive, historyPastWindowSendAllowed, historyPastTargetScanActive;
    private HistoryDemultiplexer historyDemux;
    private HistoryBoundary historyBoundary;
    private int boundaryRequestSequence, historyWindowSubtype;
    public int transmittedFrames, receivedFrames;

    public Session(InputStream in, OutputStream out) { this.in = in; this.out = out; }
    public interface VersionObserver { void onVersionResponse(Wire.VersionMetadata metadata); }
    public int negotiate() throws IOException { return negotiate(null); }
    public int negotiate(VersionObserver observer) throws IOException {
        write(Wire.versionRequest());
        try (Wire.Frame reply = Wire.readV1(in)) {
            receivedFrames++;
            if (observer != null) observer.onVersionResponse(Wire.versionMetadata(reply));
            version = Wire.parseVersion(reply);
            return version;
        }
    }
    public void authenticate(byte[] key, int api, String phoneModel) throws IOException, GeneralSecurityException {
        if (version != 1 && version != 2) throw new ProbeException("unsupported_spp_version");
        if (version == 2) {
            write(Wire.sessionStart());
            boolean started = false;
            for (int i = 0; i < 8; i++) {
                try (Wire.Frame frame = read()) {
                    if (frame.type == 2) { Wire.validateSession(frame.payload); started = true; break; }
                    if (frame.type != 1) throw new ProbeException("v2_session_unexpected_frame");
                }
            }
            if (!started) throw new ProbeException("v2_session_frame_limit");
        }
        send(auth.nonceCommand(), true);
        try (Proto challenge = receive()) {
            send(auth.respond(key, challenge, api, phoneModel), true);
        }
        try (Proto confirm = receive()) { auth.confirm(confirm); }
        authenticated = true;
    }
    public Proto battery() throws IOException, GeneralSecurityException { return query(2, 1); }
    public Proto deviceInfo() throws IOException, GeneralSecurityException {
        settingsTargetVerified = false;
        Proto command = query(2, 2);
        try (Proto system = command.message(4); Proto info = system.message(3)) {
            settingsTargetVerified = info.text(4).equals("M2345B1") && info.text(2).equals("2.3.98");
            return command;
        } catch (IOException e) { command.close(); throw e; }
    }
    public HeartRateSettings heartRateSettings() throws IOException, GeneralSecurityException {
        if (!settingsTargetVerified) throw new ProbeException("settings_target_not_verified");
        try (Proto command = query(8, 10)) { return HeartRateSettings.parse(command); }
    }
    public HistoryIndex historyIndexToday() throws IOException, GeneralSecurityException {
        return historyIndex(1, HistoryIndex.todayRequest());
    }
    public HistoryIndex historyIndexPast() throws IOException, GeneralSecurityException {
        return historyIndex(2, HistoryIndex.pastRequest());
    }
    private HistoryIndex historyIndex(int subtype, byte[] request) throws IOException, GeneralSecurityException {
        if (!settingsTargetVerified) { Auth.wipe(request); throw new ProbeException("history_index_target_not_verified"); }
        send(request, false);
        try (Proto command = await(8, subtype)) { return HistoryIndex.parse(command, subtype); }
    }
    public interface HistoryObserver {
        void onStage(String stage);
        void onSelection(int count);
        default long referenceTimeMillis() { return -1L; }
        default void onCoverage(int candidates, int omitted, boolean sample) {}
        default void onDetailCoverage(int detailCandidates, Boolean requestedCountSatisfied) {}
        default void onBoundary(Map<String, Object> safeSummary) {}
        default void onPassiveWindow(long deadlineMillis) { onStage("history_passive_observation"); }
        default void onPastWindow(long deadlineMillis) { onStage("history_past_window_observation"); }
        default void onTodayWindow(long deadlineMillis) { onStage("history_today_window_observation"); }
        void onFile(Map<String, Object> safeSummary);
    }
    public Map<String, Object> historyRead(HistoryObserver observer) throws IOException, GeneralSecurityException {
        return historyRead(observer, false);
    }
    public Map<String, Object> historyRead(HistoryObserver observer, boolean sample) throws IOException, GeneralSecurityException {
        return historyRead(observer, sample, false);
    }
    public Map<String, Object> historyRead(HistoryObserver observer, boolean sample, boolean repeatFirst) throws IOException, GeneralSecurityException {
        return historyRead(observer, sample, repeatFirst, -1);
    }
    public Map<String, Object> historyRead(HistoryObserver observer, boolean sample, boolean repeatFirst, int sampleDetailOffset) throws IOException, GeneralSecurityException {
        return historyRead(observer, sample, repeatFirst, sampleDetailOffset, 1);
    }
    public Map<String, Object> historyRead(HistoryObserver observer, boolean sample, boolean repeatFirst, int sampleDetailOffset, int sampleDetailCount) throws IOException, GeneralSecurityException {
        return historyRead(observer, sample, repeatFirst, sampleDetailOffset, sampleDetailCount, false);
    }
    public Map<String, Object> historyRead(HistoryObserver observer, boolean sample, boolean repeatFirst, int sampleDetailOffset, int sampleDetailCount, boolean pastDetails) throws IOException, GeneralSecurityException {
        return historyRead(observer, sample, repeatFirst, sampleDetailOffset, sampleDetailCount, pastDetails, false);
    }
    public Map<String, Object> historyCorrelate(HistoryObserver observer, int detailOffset) throws IOException, GeneralSecurityException {
        if (detailOffset < 0) throw new ProbeException("history_sample_invalid_range");
        return historyRead(observer, true, false, detailOffset, 1, true, true);
    }
    /** Passive timeout does not prove silence or a complete transport boundary. */
    public Map<String, Object> historyPassive(HistoryObserver observer, java.util.function.LongSupplier clock) throws IOException, GeneralSecurityException {
        if (!settingsTargetVerified) throw new ProbeException("history_read_target_not_verified");
        if (version != 2) throw new ProbeException("history_read_requires_spp_v2");
        if (historyReadActive) throw new ProbeException("history_read_already_active");
        historyReadActive = true; historyPassiveActive = true; historyBulkPayloadBytes = 0;
        boolean elapsed = false;
        long deadline = clock.getAsLong() + 6000L;
        try (HistoryBoundary boundary = new HistoryBoundary(observer, false)) {
            historyBoundary = boundary; boundary.begin(0);
            try {
                observer.onPassiveWindow(deadline);
                for (int count = 0; count < 32; count++) {
                    try (Proto incoming = receive()) { incoming.requireSuccess(); }
                    catch (java.net.SocketTimeoutException e) {
                        if (clock.getAsLong() < deadline) throw e;
                        elapsed = true;
                        return passiveResult(true);
                    }
                }
                throw new ProbeException("history_passive_message_limit");
            } finally { observer.onBoundary(passiveResult(elapsed)); }
        } finally { historyBoundary = null; historyPassiveActive = false; historyReadActive = false; }
    }
    private Map<String, Object> passiveResult(boolean elapsed) {
        Map<String, Object> result = new LinkedHashMap<>(historyBoundary.result());
        result.put("status", elapsed ? "diagnostic_passive_window_elapsed" : "diagnostic_passive_aborted");
        result.put("observation_window_ms", 6000); result.put("window_elapsed", elapsed);
        result.put("history_command_count", 0); result.put("transport_boundary_at_deadline", "not_verified");
        result.put("absence_of_stream_proven", false);
        return result;
    }
    public Map<String, Object> historyPastWindow(HistoryObserver observer, java.util.function.LongSupplier clock) throws IOException, GeneralSecurityException {
        return historyIndexWindow(observer, clock, 2);
    }
    public Map<String, Object> historyTodayWindow(HistoryObserver observer, java.util.function.LongSupplier clock) throws IOException, GeneralSecurityException {
        return historyIndexWindow(observer, clock, 1);
    }
    /** One index, then header-only observation until the same fixed deadline; never a fetch. */
    private Map<String, Object> historyIndexWindow(HistoryObserver observer, java.util.function.LongSupplier clock, int subtype) throws IOException, GeneralSecurityException {
        if (!settingsTargetVerified) throw new ProbeException("history_read_target_not_verified");
        if (version != 2) throw new ProbeException("history_read_requires_spp_v2");
        if (historyReadActive) throw new ProbeException("history_read_already_active");
        long deadline = clock.getAsLong() + 6000L;
        historyReadActive = true; historySampleActive = true; historyPastWindowActive = true; historyBulkPayloadBytes = 0;
        boolean elapsed = false, responseSeen = false;
        historyWindowSubtype = subtype;
        int commands = 0;
        try (HistoryBoundary boundary = new HistoryBoundary(observer)) {
            historyBoundary = boundary; boundary.begin(subtype);
            try {
                if (subtype == 2) observer.onPastWindow(deadline); else observer.onTodayWindow(deadline);
                historyPastWindowSendAllowed = true;
                commands = 1; send(subtype == 2 ? HistoryIndex.pastRequest() : HistoryIndex.todayRequest(), false);
                boundaryRequestSequence = (serial - 1) & 255;
                for (int count = 0; count < 32; count++) {
                    try (Proto incoming = receive()) {
                        incoming.requireSuccess();
                        if (incoming.number(1) == 8 && (incoming.number(2) == 1 || incoming.number(2) == 2)) {
                            if (incoming.number(2) != subtype || responseSeen) throw new ProbeException(windowCode("unexpected_index"));
                            boundary.response(incoming, subtype, !pending.contains(boundaryRequestSequence)); responseSeen = true;
                        }
                    } catch (java.net.SocketTimeoutException e) {
                        if (clock.getAsLong() < deadline) throw e;
                        elapsed = true;
                        if (!responseSeen) throw new ProbeException(windowCode("index_not_received"));
                        return pastWindowResult(true, commands);
                    }
                }
                throw new ProbeException(windowCode("message_limit"));
            } finally { observer.onBoundary(pastWindowResult(elapsed, commands)); }
        } finally {
            historyBoundary = null; historyPastWindowActive = false; historyPastWindowSendAllowed = false;
            historyReadActive = false; historySampleActive = false;
        }
    }
    private String windowCode(String suffix) { return "history_" + (historyWindowSubtype == 2 ? "past" : "today") + "_window_" + suffix; }
    private Map<String, Object> pastWindowResult(boolean elapsed, int commands) {
        Map<String, Object> result = new LinkedHashMap<>(historyBoundary.result());
        String scope = historyWindowSubtype == 2 ? "past" : "today";
        result.put("status", !elapsed ? "diagnostic_" + scope + "_window_aborted" : result.get("index_response_count").equals(1)
                ? "diagnostic_" + scope + "_window_elapsed" : "diagnostic_" + scope + "_window_index_not_received");
        result.put("index_request_subtype", historyWindowSubtype); result.put("history_command_count", commands);
        result.put("observation_window_ms", 6000); result.put("window_elapsed", elapsed);
        result.put("transport_boundary_at_deadline", "not_verified"); result.put("absence_of_stream_proven", false);
        return result;
    }
    public Map<String, Object> historyBoundary(HistoryObserver observer) throws IOException, GeneralSecurityException {
        if (!settingsTargetVerified) throw new ProbeException("history_read_target_not_verified");
        if (version != 2) throw new ProbeException("history_read_requires_spp_v2");
        if (historyReadActive) throw new ProbeException("history_read_already_active");
        historyReadActive = true; historySampleActive = true;
        historyBulkPayloadBytes = 0;
        try (HistoryBoundary boundary = new HistoryBoundary(observer)) {
            historyBoundary = boundary;
            try {
                for (int subtype : new int[]{2, 1}) {
                    boundary.begin(subtype);
                    observer.onStage(subtype == 2 ? "history_boundary_index_past" : "history_boundary_index_today");
                    send(subtype == 2 ? HistoryIndex.pastRequest() : HistoryIndex.todayRequest(), false);
                    boundaryRequestSequence = (serial - 1) & 255;
                    try (Proto index = await(8, subtype)) {
                        boundary.response(index, subtype, !pending.contains(boundaryRequestSequence));
                    }
                }
                return boundary.result();
            } finally { observer.onBoundary(boundary.result()); }
        } finally { historyBoundary = null; historyReadActive = false; historySampleActive = false; }
    }
    public Map<String, Object> historyTarget(HistoryObserver observer, int detailOffset) throws IOException, GeneralSecurityException {
        return historyTarget(observer, detailOffset, false, false);
    }
    public Map<String, Object> historyTargetScan(HistoryObserver observer, int detailOffset) throws IOException, GeneralSecurityException {
        return historyTarget(observer, detailOffset, true, false);
    }
    public Map<String, Object> historyPastTargetScan(HistoryObserver observer, int detailOffset) throws IOException, GeneralSecurityException {
        return historyTarget(observer, detailOffset, true, true);
    }
    public Map<String, Object> historyPastTarget(HistoryObserver observer, int detailOffset) throws IOException, GeneralSecurityException {
        return historyTarget(observer, detailOffset, false, true);
    }
    public Map<String, Object> historyPastRepeat(HistoryObserver observer, int detailOffset) throws IOException, GeneralSecurityException {
        return historyTarget(observer, detailOffset, false, true, true);
    }
    private Map<String, Object> historyTarget(HistoryObserver observer, int detailOffset, boolean headerScan, boolean pastOnly) throws IOException, GeneralSecurityException {
        return historyTarget(observer, detailOffset, headerScan, pastOnly, false);
    }
    private Map<String, Object> historyTarget(HistoryObserver observer, int detailOffset, boolean headerScan, boolean pastOnly, boolean repeat) throws IOException, GeneralSecurityException {
        HistorySelection.validateSample(true, detailOffset, 1);
        if (detailOffset < 0) throw new ProbeException("history_sample_invalid_range");
        if (!settingsTargetVerified) throw new ProbeException("history_read_target_not_verified");
        if (version != 2) throw new ProbeException("history_read_requires_spp_v2");
        if (historyReadActive) throw new ProbeException("history_read_already_active");
        historyReadActive = true; historySampleActive = true;
        historyBulkBeforeFetch = 0; historyBulkBeforeFetchBytes = 0; historyBulkPayloadBytes = 0;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("retention_index_checked", false); result.put("retention_index_unchanged", null);
        if (repeat) result.put("repeat_requested", true);
        try (HistoryDemultiplexer demux = new HistoryDemultiplexer(observer, headerScan, pastOnly, repeat)) {
            historyDemux = demux; historyPastTargetScanActive = pastOnly;
            observer.onStage("history_read_index_past");
            send(HistoryIndex.pastRequest(), false);
            try (Proto index = await(8, 2); HistorySelection before = HistorySelection.pastDetails(index, detailOffset, 1)) {
                observer.onSelection(before.count());
                observer.onCoverage(before.candidateCount(), before.omittedCount(), true);
                observer.onDetailCoverage(before.detailCandidateCount(), true);
                result.put("detail_candidate_file_count", before.detailCandidateCount());
                result.put("requested_detail_count_satisfied", true);
                if (!pastOnly) {
                    observer.onStage("history_read_index_today_metadata_only");
                    send(HistoryIndex.todayRequest(), false);
                }
                try (Proto counterpart = pastOnly ? null : await(8, 1);
                     HistorySelection today = pastOnly ? null : new HistorySelection(counterpart, false)) {
                    demux.request(before.id(0), before, today);
                    observer.onStage("history_read_file");
                    int messages = 0;
                    for (int read = 0; read < (repeat ? 2 : 1); read++) {
                        if (read == 1) demux.repeat();
                        send(HistoryFile.request(before.id(0)), false);
                        while (!demux.found()) {
                            if (++messages > 4096) throw new ProbeException("history_read_frame_limit");
                            try (Incoming message = receiveV2(true)) {
                                if (message.channel == 5) demux.accept(message.payload, false);
                                else try (Proto response = Proto.parse(message.payload)) { response.requireSuccess(); }
                            }
                        }
                    }
                    if (repeat) {
                        result.put("repeat_read_count", demux.exactReads());
                        result.put("repeat_aggregate_consistent", demux.repeatAggregateConsistent());
                        result.put("repeat_bytes_identical", demux.repeatBytesIdentical());
                    }
                    result.put("downloaded_bytes", demux.downloadedBytes());
                    if (headerScan) {
                        demux.requireBoundary();
                        result.put("status", "diagnostic_target_scan_exact");
                        return result;
                    }
                    demux.retention();
                    observer.onStage("history_read_retention_index_past");
                    send(HistoryIndex.pastRequest(), false);
                    try (Proto afterIndex = await(8, 2); HistorySelection after = HistorySelection.pastSnapshot(afterIndex)) {
                        demux.requireBoundary();
                        boolean unchanged = before.unchanged(after);
                        result.put("retention_index_checked", true); result.put("retention_index_unchanged", unchanged);
                        result.put("status", !unchanged ? "retention_index_changed" : demux.parserStatus().startsWith("unsupported")
                                ? "parser_unsupported" : repeat && !Boolean.TRUE.equals(demux.repeatBytesIdentical())
                                ? "repeat_file_changed" : repeat && !Boolean.TRUE.equals(demux.repeatAggregateConsistent())
                                ? "repeat_aggregate_changed" : repeat ? "verified_target_repeat_sample" : "verified_target_sample");
                    }
                    return result;
                }
            }
        } finally { historyDemux = null; historyPastTargetScanActive = false; historyReadActive = false; historySampleActive = false; }
    }
    private Map<String, Object> historyRead(HistoryObserver observer, boolean sample, boolean repeatFirst, int sampleDetailOffset, int sampleDetailCount, boolean pastDetails, boolean correlate) throws IOException, GeneralSecurityException {
        HistorySelection.validateSample(sample, sampleDetailOffset, sampleDetailCount);
        if (pastDetails && (!sample || repeatFirst || sampleDetailOffset < 0)) throw new ProbeException("history_past_requires_explicit_batch");
        if (!settingsTargetVerified) throw new ProbeException("history_read_target_not_verified");
        if (version != 2) throw new ProbeException("history_read_requires_spp_v2");
        if (historyReadActive) throw new ProbeException("history_read_already_active");
        if (repeatFirst && !sample) throw new ProbeException("history_repeat_requires_sample");
        if (sampleDetailOffset >= 0 && !sample) throw new ProbeException("history_sample_offset_requires_sample");
        historyReadActive = true;
        historySampleActive = sample;
        historyBulkBeforeFetch = 0; historyBulkBeforeFetchBytes = 0; historyBulkPayloadBytes = 0;
        byte[] repeatDigest = null;
        HistorySelection counterpartSnapshot = null;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("retention_index_checked", false); result.put("retention_index_unchanged", null);
        try {
            observer.onStage(pastDetails ? "history_read_index_past" : "history_read_index_today");
            send(pastDetails ? HistoryIndex.pastRequest() : HistoryIndex.todayRequest(), false);
            try (Proto index = await(8, pastDetails ? 2 : 1); HistorySelection before = pastDetails
                    ? HistorySelection.pastDetails(index, sampleDetailOffset, sampleDetailCount)
                    : new HistorySelection(index, true, sample, sampleDetailOffset, sampleDetailCount)) {
                observer.onSelection(before.count());
                observer.onCoverage(before.candidateCount(), before.omittedCount(), sample);
                observer.onDetailCoverage(before.detailCandidateCount(), sampleDetailOffset < 0 ? null : before.count() == sampleDetailCount);
                result.put("selected_file_count", before.count());
                result.put("candidate_file_count", before.candidateCount());
                result.put("detail_candidate_file_count", before.detailCandidateCount());
                result.put("omitted_file_count", before.omittedCount());
                result.put("requested_detail_count_satisfied", sampleDetailOffset < 0 ? null : before.count() == sampleDetailCount);
                if (before.count() == 0) { result.put("status", "no_allowed_daily_files"); return result; }
                if (sample) {
                    observer.onStage(pastDetails ? "history_read_index_today_metadata_only" : "history_read_index_past_metadata_only");
                    send(pastDetails ? HistoryIndex.todayRequest() : HistoryIndex.pastRequest(), false);
                    try (Proto counterpart = await(8, pastDetails ? 1 : 2)) {
                        counterpartSnapshot = pastDetails ? new HistorySelection(counterpart, false) : HistorySelection.pastSnapshot(counterpart);
                    }
                    result.put("past_index_file_count", pastDetails ? before.indexedCount() : counterpartSnapshot.indexedCount());
                }
                if (correlate) {
                    result.putAll(correlateFiles(observer, before, counterpartSnapshot));
                    return result;
                }
                int bytes = 0;
                boolean supported = true;
                Map<String, Object> repeatBaseline = null;
                boolean repeatConsistent = true, repeatBytesIdentical = true;
                int reads = before.count() + (repeatFirst ? 1 : 0);
                result.put("repeat_requested", repeatFirst);
                for (int i = 0; i < reads; i++) {
                    int selectedIndex = i < before.count() ? i : 0;
                    observer.onStage("history_read_file");
                    try (HistoryFile file = new HistoryFile(before.id(selectedIndex), HistoryFile.MAX_BYTES - bytes - historyBulkBeforeFetchBytes)) {
                        boolean receiptSeen = false;
                        try {
                            send(HistoryFile.request(before.id(selectedIndex)), false);
                            for (int frames = 0; !file.complete(); frames++) {
                                if (frames >= 4096) throw new ProbeException("history_read_frame_limit");
                                try (Incoming message = receiveV2(true)) {
                                    if (message.channel == 5) file.accept(message.payload);
                                    else try (Proto response = Proto.parse(message.payload)) {
                                        response.requireSuccess();
                                        if (response.has(1) && response.has(2) && response.number(1) == 8 && response.number(2) == 3)
                                            receiptSeen = true;
                                        // A protobuf 8/3 receipt is not the activity file and does not trigger an ACK.
                                    }
                                }
                            }
                            file.verifyAndSummarize();
                            Map<String, Object> parsedSummary = new LinkedHashMap<>(file.summary());
                            if (repeatFirst && i == 0) { repeatBaseline = parsedSummary; repeatDigest = file.verifiedDigest(); }
                            if (repeatFirst && i == reads - 1) {
                                repeatConsistent = repeatBaseline != null && repeatBaseline.equals(parsedSummary);
                                byte[] repeatedDigest = file.verifiedDigest();
                                try { repeatBytesIdentical = repeatDigest != null && java.security.MessageDigest.isEqual(repeatDigest, repeatedDigest); }
                                finally { Auth.wipe(repeatedDigest); }
                            }
                            bytes += file.size();
                            supported &= !parsedSummary.get("parser_status").toString().startsWith("unsupported");
                        } finally {
                            Map<String, Object> summary = new LinkedHashMap<>(file.summary());
                            summary.put("request_receipt_seen_before_complete", receiptSeen);
                            summary.put("received_id_in_original_index", Boolean.TRUE.equals(summary.get("crc_valid"))
                                    ? before.containsReceivedId(file) : null);
                            HistorySelection pastSnapshot = pastDetails ? before : counterpartSnapshot;
                            HistorySelection todaySnapshot = pastDetails ? counterpartSnapshot : before;
                            summary.put("requested_index_scope", pastDetails ? "past" : "today");
                            summary.put("past_index_snapshot_checked", pastSnapshot != null);
                            summary.put("past_index_file_count", pastSnapshot == null ? null : pastSnapshot.indexedCount());
                            summary.put("received_id_in_past_index", pastSnapshot != null && Boolean.TRUE.equals(summary.get("crc_valid"))
                                    ? pastSnapshot.containsReceivedId(file) : null);
                            summary.put("received_id_in_today_index", todaySnapshot != null && Boolean.TRUE.equals(summary.get("crc_valid"))
                                    ? todaySnapshot.containsReceivedId(file) : null);
                            observer.onFile(summary);
                        }
                    }
                }
                result.put("downloaded_bytes", bytes);
                result.put("repeat_read_count", repeatFirst ? 2 : 0);
                result.put("repeat_aggregate_consistent", repeatFirst ? repeatConsistent : null);
                result.put("repeat_bytes_identical", repeatFirst ? repeatBytesIdentical : null);
                observer.onStage(pastDetails ? "history_read_retention_index_past" : "history_read_retention_index_today");
                send(pastDetails ? HistoryIndex.pastRequest() : HistoryIndex.todayRequest(), false);
                try (Proto indexAfter = await(8, pastDetails ? 2 : 1); HistorySelection after = pastDetails
                        ? HistorySelection.pastSnapshot(indexAfter) : new HistorySelection(indexAfter, false)) {
                    result.put("retention_index_checked", true);
                    boolean unchanged = before.unchanged(after);
                    result.put("retention_index_unchanged", unchanged);
                    result.put("status", !unchanged ? "retention_index_changed" : repeatFirst && !repeatBytesIdentical ? "repeat_file_changed"
                            : !supported ? "parser_unsupported" : sample ? "verified_sample" : "verified");
                }
                return result;
            }
        } finally {
            if (counterpartSnapshot != null) counterpartSnapshot.close();
            Auth.wipe(repeatDigest); historyReadActive = false; historySampleActive = false;
        }
    }
    private Map<String, Object> correlateFiles(HistoryObserver observer, HistorySelection past, HistorySelection today) throws IOException, GeneralSecurityException {
        Map<String, Object> result = new LinkedHashMap<>();
        observer.onStage("history_read_file");
        send(HistoryFile.request(past.id(0)), false);
        int requestSequence = (serial - 1) & 255;
        int bytes = 0;
        boolean receiptSeen = false;
        for (int ordinal = 1; ordinal <= 2; ordinal++) {
            boolean matched = false;
            Boolean firstBulkBeforeReceipt = null;
            try (HistoryFile file = new HistoryFile(past.id(0), HistoryFile.MAX_BYTES - bytes - historyBulkBeforeFetchBytes)) {
                try {
                    for (int frames = 0; !file.complete(); frames++) {
                        if (frames >= 4096) throw new ProbeException("history_read_frame_limit");
                        try (Incoming message = receiveV2(true)) {
                            if (message.channel == 5) {
                                if (firstBulkBeforeReceipt == null) firstBulkBeforeReceipt = !receiptSeen;
                                file.accept(message.payload);
                            } else try (Proto response = Proto.parse(message.payload)) {
                                response.requireSuccess();
                                if (response.has(1) && response.has(2) && response.number(1) == 8 && response.number(2) == 3)
                                    receiptSeen = true;
                            }
                        }
                    }
                    matched = file.verifyHeaderOnly();
                    bytes += file.size();
                } finally {
                    Map<String, Object> summary = new LinkedHashMap<>(file.summary());
                    boolean crc = Boolean.TRUE.equals(summary.get("crc_valid"));
                    summary.put("correlation_header_only", true);
                    summary.put("correlation_file_ordinal", ordinal);
                    summary.put("fetch_command_count", 1);
                    summary.put("bulk_frames_before_request", historyBulkBeforeFetch);
                    summary.put("bulk_payload_bytes_before_request", historyBulkBeforeFetchBytes);
                    summary.put("request_transport_ack_seen_before_complete", !pending.contains(requestSequence));
                    summary.put("request_receipt_seen_before_complete", receiptSeen);
                    summary.put("first_bulk_before_request_receipt", firstBulkBeforeReceipt);
                    summary.put("requested_index_scope", "past");
                    summary.put("past_index_snapshot_checked", true);
                    summary.put("past_index_file_count", past.indexedCount());
                    summary.put("received_id_in_original_index", crc ? past.containsReceivedId(file) : null);
                    summary.put("received_id_in_past_index", crc ? past.containsReceivedId(file) : null);
                    summary.put("received_id_in_today_index", crc ? today.containsReceivedId(file) : null);
                    observer.onFile(summary);
                }
            }
            result.put("downloaded_bytes", bytes);
            if (matched || ordinal == 2) {
                result.put("status", matched ? ordinal == 1 ? "diagnostic_exact_first" : "diagnostic_exact_after_unmatched"
                        : "diagnostic_no_exact_within_two");
                break;
            }
            // Observe a second complete file after the same request; never resend or accept the first mismatch.
        }
        return result;
    }
    public void liveStart() throws IOException, GeneralSecurityException { send(Proto.command(8, 45), false); }
    public void liveStop() throws IOException, GeneralSecurityException { send(Proto.command(8, 46), false); }
    public Proto nextLive() throws IOException, GeneralSecurityException { return await(8, 47); }
    private Proto query(int type, int sub) throws IOException, GeneralSecurityException {
        send(Proto.command(type, sub), false);
        return await(type, sub);
    }
    private Proto await(int type, int sub) throws IOException, GeneralSecurityException {
        for (int i = 0; i < 32; i++) {
            Proto command = receive();
            boolean selected = false;
            try {
                selected = command.number(1) == type && command.number(2) == sub;
                if (selected) {
                    try { command.requireSuccess(); return command; }
                    catch (ProbeException e) { command.close(); throw e; }
                }
            } finally { if (!selected) command.close(); }
        }
        throw new ProbeException("command_response_limit");
    }
    private void send(byte[] command, boolean handshake) throws IOException, GeneralSecurityException {
        byte[] payload = null;
        try {
            if (historyPassiveActive) throw new ProbeException("history_passive_command_not_allowed");
            if (!handshake && !authenticated) throw new ProbeException("not_authenticated");
            if (historyReadActive) try (Proto permitted = Proto.parse(command)) {
                long subtype = permitted.number(2);
                if (historyPastTargetScanActive && subtype != 2 && subtype != 3)
                    throw new ProbeException("history_past_target_scan_command_not_allowed");
                if (historyPastWindowActive) {
                    if (!historyPastWindowSendAllowed || handshake || permitted.number(1) != 8 || subtype != historyWindowSubtype)
                        throw new ProbeException(windowCode("command_not_allowed"));
                    historyPastWindowSendAllowed = false;
                }
                if (historyBoundary != null && subtype != 1 && subtype != 2)
                    throw new ProbeException("history_boundary_command_not_allowed");
                if (handshake || permitted.number(1) != 8 || (subtype != 1 && subtype != 3 && !(historySampleActive && subtype == 2)))
                    throw new ProbeException("history_read_command_not_allowed");
            }
            if (version == 2 && pending.size() >= 8) throw new ProbeException("v2_pending_limit");
            payload = handshake ? command.clone() : auth.encrypt(version, command, ++encryptionCounter);
            int seq = serial++ & 255;
            if (version == 1) {
                write(Wire.v1(2, 0x80, 2, seq, handshake ? 2 : 1, payload));
            } else {
                byte[] envelope = new byte[payload.length + 2];
                envelope[0] = 1; envelope[1] = (byte) (handshake ? 1 : 2);
                System.arraycopy(payload, 0, envelope, 2, payload.length);
                try { write(Wire.v2(3, seq, envelope)); pending.addLast(seq); }
                finally { Auth.wipe(envelope); }
            }
        } finally { Auth.wipe(command, payload); }
    }
    private Wire.Frame read() throws IOException {
        Wire.Frame frame = version == 1 ? Wire.readV1(in) : Wire.readV2(in, historyReadActive ? 64512 : Wire.MAX_PAYLOAD);
        if (++receivedFrames > (historyReadActive ? 8192 : 512)) { frame.close(); throw new ProbeException("session_frame_limit"); }
        return frame;
    }
    private static final class Incoming implements AutoCloseable {
        final int channel;
        final byte[] payload;
        Incoming(int channel, byte[] payload) { this.channel = channel; this.payload = payload; }
        @Override public void close() { Auth.wipe(payload); }
    }
    private Incoming receiveV2(boolean activity) throws IOException, GeneralSecurityException {
        for (int i = 0; i < 64; i++) {
            try (Wire.Frame frame = read()) {
                if (frame.type == 1) { acknowledge(frame.sequence); continue; }
                if (frame.type == 0) throw new ProbeException("v2_nack_no_retry");
                if (frame.type != 3 || frame.payload.length < 2) throw new ProbeException("v2_unexpected_frame");
                if (frame.sequence == ((expectedRx - 1) & 255)) {
                    write(Wire.v2(1, frame.sequence, new byte[0])); continue;
                }
                if (frame.sequence != expectedRx) throw new ProbeException("v2_receive_sequence");
                expectedRx = (expectedRx + 1) & 255;
                write(Wire.v2(1, frame.sequence, new byte[0]));
                int channel = frame.payload[0] & 15;
                if (historyReadActive && channel == 5) {
                    historyBulkPayloadBytes += frame.payload.length - 2;
                    if (historyBulkPayloadBytes > HistoryFile.MAX_BYTES) throw new ProbeException("history_bulk_byte_limit");
                }
                boolean routeActivity = activity || historyDemux != null || historyBoundary != null;
                if (routeActivity && channel != 1 && channel != 5) throw new ProbeException("history_read_unexpected_channel");
                if (channel != 1 && !(routeActivity && channel == 5)) {
                    if (historyReadActive && channel == 5) {
                        if (Wire.u(frame.payload[1]) != 2 || !authenticated) throw new ProbeException("unexpected_plaintext");
                        historyBulkBeforeFetchBytes += frame.payload.length - 2;
                        if (++historyBulkBeforeFetch > 32 || historyBulkBeforeFetchBytes > HistoryFile.MAX_BYTES)
                            throw new ProbeException("history_unsolicited_bulk_limit");
                    }
                    continue;
                }
                int op = Wire.u(frame.payload[1]);
                if (op != 1 && op != 2) throw new ProbeException("v2_data_opcode");
                if ((authenticated || channel == 5) && op != 2) throw new ProbeException("unexpected_plaintext");
                byte[] data = Arrays.copyOfRange(frame.payload, 2, frame.payload.length);
                try {
                    byte[] decoded = op == 1 ? data.clone() : (authenticated ? auth.decrypt(2, data) : auth.decryptHandshakeV2(data));
                    if (channel == 5 && historyBoundary != null) {
                        try { historyBoundary.accept(decoded, historyPassiveActive ? null : !pending.contains(boundaryRequestSequence)); }
                        finally { Auth.wipe(decoded); }
                        continue;
                    }
                    if (channel == 5 && historyDemux != null && !activity) {
                        try { historyDemux.accept(decoded, true); }
                        finally { Auth.wipe(decoded); }
                        continue;
                    }
                    return new Incoming(channel, decoded);
                } finally { Auth.wipe(data); }
            }
        }
        throw new ProbeException("receive_frame_limit");
    }
    private Proto receive() throws IOException, GeneralSecurityException {
        if (version == 2) try (Incoming message = receiveV2(false)) { return Proto.parse(message.payload); }
        for (int i = 0; i < 64; i++) {
            try (Wire.Frame frame = read()) {
                if (frame.channel != 1 && frame.channel != 2) continue;
                if (frame.opcode != 0 && frame.opcode != 2) throw new ProbeException("v1_data_opcode");
                if (authenticated && frame.dataType != 1) throw new ProbeException("unexpected_plaintext");
                if (!authenticated && (frame.dataType < 0 || frame.dataType > 2))
                    throw new ProbeException("v1_handshake_data_type");
                byte[] decoded = authenticated ? auth.decrypt(1, frame.payload) : frame.payload.clone();
                try { return Proto.parse(decoded); } finally { Auth.wipe(decoded); }
            }
        }
        throw new ProbeException("receive_frame_limit");
    }
    private void acknowledge(int seq) {
        if (!pending.contains(seq)) return;
        while (!pending.isEmpty()) if (pending.removeFirst() == seq) break;
    }
    private void write(byte[] frame) throws IOException {
        try { out.write(frame); out.flush(); transmittedFrames++; }
        finally { Auth.wipe(frame); }
    }
    @Override public void close() { auth.close(); pending.clear(); }
}
