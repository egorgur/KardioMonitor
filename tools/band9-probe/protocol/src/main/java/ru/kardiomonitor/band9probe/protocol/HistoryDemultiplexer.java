package ru.kardiomonitor.band9probe.protocol;

import java.util.LinkedHashMap;
import java.util.Map;

/** One bounded transfer at a time; unsolicited files are verified, reported as metadata, then wiped. */
final class HistoryDemultiplexer implements AutoCloseable {
    private final Session.HistoryObserver observer;
    private final boolean headerScan, pastOnly, repeatEnabled;
    private byte[] firstDigest;
    private Map<String, Object> firstAggregate;
    private Boolean repeatBytesIdentical, repeatAggregateConsistent;
    private int fetchCount, exactReads;
    private HistoryFile file;
    private HistorySelection past, today;
    private byte[] target;
    private boolean requested, found, candidate;
    private int payloadBytes, waitingFrames, completed, afterRequest, targetBytes;
    private String phase = "index_wait", filePhase, targetParser;

    HistoryDemultiplexer(Session.HistoryObserver observer) { this(observer, false); }
    HistoryDemultiplexer(Session.HistoryObserver observer, boolean headerScan) { this(observer, headerScan, false); }
    HistoryDemultiplexer(Session.HistoryObserver observer, boolean headerScan, boolean pastOnly) {
        this(observer, headerScan, pastOnly, false);
    }
    HistoryDemultiplexer(Session.HistoryObserver observer, boolean headerScan, boolean pastOnly, boolean repeatEnabled) {
        if (repeatEnabled && (headerScan || !pastOnly)) throw new IllegalArgumentException("history_repeat_mode");
        this.observer = observer; this.headerScan = headerScan; this.pastOnly = pastOnly; this.repeatEnabled = repeatEnabled;
    }
    void request(byte[] id, HistorySelection past, HistorySelection today) throws ProbeException {
        if (requested) throw new ProbeException("history_target_already_requested");
        HistoryFile.requireAllowed(id);
        if (HistoryFile.MAX_BYTES - payloadBytes < 17) throw new ProbeException("history_read_byte_budget");
        target = id.clone(); this.past = past; this.today = today;
        requested = true; fetchCount = 1; phase = "target_wait";
    }
    void repeat() throws ProbeException {
        requireBoundary();
        if (!repeatEnabled || !found || exactReads != 1 || fetchCount != 1 || firstDigest == null
                || !"daily_details_v4".equals(targetParser)) throw new ProbeException("history_repeat_target_not_verified");
        if (completed >= 16) throw new ProbeException("history_quarantine_file_limit");
        if (HistoryFile.MAX_BYTES - payloadBytes < 17) throw new ProbeException("history_read_byte_budget");
        found = false; fetchCount = 2; phase = "repeat_wait";
    }
    int exactReads() { return exactReads; }
    Boolean repeatBytesIdentical() { return repeatBytesIdentical; }
    Boolean repeatAggregateConsistent() { return repeatAggregateConsistent; }
    void retention() { phase = "retention_wait"; }
    boolean found() { return found; }
    int downloadedBytes() { return targetBytes; }
    String parserStatus() { return targetParser; }
    void accept(byte[] chunk, boolean waitingForProtobuf) throws ProbeException {
        payloadBytes += chunk.length;
        if (payloadBytes > HistoryFile.MAX_BYTES) throw new ProbeException("history_bulk_byte_limit");
        if (waitingForProtobuf && ++waitingFrames > 32) throw new ProbeException("history_unsolicited_bulk_limit");
        if (file == null) {
            if (completed >= 16) throw new ProbeException("history_quarantine_file_limit");
            candidate = requested && !found && (phase.equals("target_wait") || phase.equals("repeat_wait"));
            filePhase = phase;
            int remaining = HistoryFile.MAX_BYTES - payloadBytes + chunk.length;
            file = candidate ? new HistoryFile(target, remaining) : HistoryFile.quarantine(remaining);
        }
        file.accept(chunk);
        if (!file.complete()) return;
        String route = "invalid";
        try {
            boolean exact = file.verifyHeaderOnly();
            if (candidate && exact) {
                if (!headerScan) file.verifyAndSummarize(observer.referenceTimeMillis());
                targetParser = file.summary().get("parser_status").toString();
                if (repeatEnabled) compareRepeat();
                exactReads++;
                targetBytes += file.size(); found = true; route = "requested_exact";
            } else route = "quarantined";
        } finally {
            try { emit(route); }
            finally { file.close(); file = null; completed++; }
        }
        if (requested && !found && ++afterRequest >= (pastOnly ? 16 : headerScan ? 6 : 2)) throw new ProbeException("history_target_not_received");
    }
    private void compareRepeat() throws ProbeException {
        byte[] digest = null;
        try {
            digest = file.verifiedDigest();
            Map<String, Object> aggregate = new LinkedHashMap<>();
            Map<String, Object> summary = file.summary();
            for (String key : new String[]{"parser_status", "detail_records", "hr_present_records", "hr_missing_records",
                    "step_records", "hr_encoded_zero_records", "hr_encoded_255_records", "step_encoded_zero_records",
                    "step_encoded_nonzero_records", "time_granularity_seconds"})
                if (summary.containsKey(key)) aggregate.put(key, summary.get(key));
            if (fetchCount == 1) {
                firstDigest = digest; digest = null; firstAggregate = aggregate;
            } else {
                repeatBytesIdentical = firstDigest != null && java.security.MessageDigest.isEqual(firstDigest, digest);
                repeatAggregateConsistent = firstAggregate != null && firstAggregate.equals(aggregate);
                Auth.wipe(firstDigest); firstDigest = null; firstAggregate = null;
            }
        } catch (java.security.GeneralSecurityException e) { throw new ProbeException("history_repeat_digest_unavailable"); }
        finally { Auth.wipe(digest); }
    }
    void requireBoundary() throws ProbeException {
        if (file != null) throw new ProbeException("history_quarantine_incomplete");
    }
    private void emit(String route) {
        Map<String, Object> summary = new LinkedHashMap<>(file.summary());
        boolean crc = Boolean.TRUE.equals(summary.get("crc_valid"));
        summary.put("demux_route", route); summary.put("demux_file_phase", filePhase);
        summary.put("demux_file_ordinal", completed + 1);
        summary.put("requested_index_scope", "past");
        summary.put("fetch_command_count", fetchCount);
        summary.put("received_id_in_past_index", crc && past != null ? past.containsReceivedId(file) : null);
        summary.put("received_id_in_today_index", crc && today != null ? today.containsReceivedId(file) : null);
        if (crc && Boolean.TRUE.equals(summary.get("received_padding_zero"))) {
            byte[] id = null;
            try {
                id = file.quarantinedIdCopy();
                if (past != null) summary.putAll(past.identityComparison(id, "past_"));
                if (today != null) summary.putAll(today.identityComparison(id, "today_"));
            } catch (ProbeException e) { throw new IllegalStateException("history_verified_id_unavailable"); }
            finally { Auth.wipe(id); }
        }
        observer.onFile(summary);
    }
    @Override public void close() {
        try { if (file != null) emit("incomplete"); }
        finally {
            if (file != null) { file.close(); file = null; }
            Auth.wipe(target, firstDigest); target = null; firstDigest = null; firstAggregate = null; past = null; today = null;
        }
    }
}
