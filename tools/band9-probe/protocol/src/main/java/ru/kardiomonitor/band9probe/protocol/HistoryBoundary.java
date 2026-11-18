package ru.kardiomonitor.band9probe.protocol;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Header-only observation; passive mode retains no IDs, index mode wipes them after deferred membership. */
final class HistoryBoundary implements AutoCloseable {
    private static final class Entry {
        final Map<String, Object> summary;
        final byte[] id;
        Entry(Map<String, Object> summary, byte[] id) { this.summary = summary; this.id = id; }
    }
    private final Session.HistoryObserver observer;
    private final boolean retainIds;
    private final List<Entry> entries = new ArrayList<>();
    private HistoryFile file;
    private HistorySelection today, past;
    private int subtype, startedSubtype, lastSubtype, payloadBytes, frames, responses, waitingFrames, postResponseFrames;
    private String phase, startedPhase, lastPhase;
    private Boolean firstAck, todayAck, pastAck;

    HistoryBoundary(Session.HistoryObserver observer) { this(observer, true); }
    HistoryBoundary(Session.HistoryObserver observer, boolean retainIds) { this.observer = observer; this.retainIds = retainIds; }
    void begin(int subtype) { this.subtype = subtype; phase = subtype == 0 ? "pre_index_passive" : "index_wait"; }
    void response(Proto index, int subtype, boolean acknowledged) throws ProbeException {
        HistorySelection snapshot = subtype == 1 ? new HistorySelection(index, false) : HistorySelection.pastSnapshot(index);
        if (subtype == 1) { if (today != null) today.close(); today = snapshot; todayAck = acknowledged; }
        else { if (past != null) past.close(); past = snapshot; pastAck = acknowledged; }
        responses++; phase = "post_index_response";
    }
    void accept(byte[] chunk, Boolean acknowledged) throws ProbeException {
        payloadBytes += chunk.length;
        if (payloadBytes > HistoryFile.MAX_BYTES) throw new ProbeException("history_bulk_byte_limit");
        if (++frames > 32) throw new ProbeException("history_unsolicited_bulk_limit");
        if ("index_wait".equals(phase)) waitingFrames++;
        if ("post_index_response".equals(phase)) postResponseFrames++;
        if (file == null) {
            if (entries.size() >= 16) throw new ProbeException("history_quarantine_file_limit");
            file = HistoryFile.quarantine(HistoryFile.MAX_BYTES - payloadBytes + chunk.length);
            startedSubtype = subtype; startedPhase = phase; firstAck = acknowledged;
        }
        lastSubtype = subtype; lastPhase = phase;
        file.accept(chunk);
        if (!file.complete()) return;
        byte[] id = null;
        try { file.verifyHeaderOnly(); if (retainIds) id = file.quarantinedIdCopy(); }
        finally {
            try { save(id, acknowledged, false); }
            finally { file.close(); file = null; }
        }
    }
    private void save(byte[] id, Boolean lastAck, boolean partial) {
        Map<String, Object> summary = new LinkedHashMap<>(file.summary());
        summary.put("boundary_observation_phase", startedPhase);
        summary.put("boundary_observation_phase_last_bulk", lastPhase);
        summary.put("boundary_subtype_first_bulk", startedSubtype == 0 ? null : startedSubtype);
        summary.put("boundary_subtype_last_bulk", lastSubtype == 0 ? null : lastSubtype);
        summary.put("boundary_request_ack_seen_first_bulk", firstAck);
        summary.put("boundary_request_ack_seen_last_bulk", partial ? null : lastAck);
        summary.put("boundary_file_ordinal", entries.size() + 1);
        summary.put("boundary_file_partial", partial);
        summary.put("fetch_command_count", 0);
        entries.add(new Entry(summary, id));
    }
    Map<String, Object> result() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("fetch_command_count", 0); result.put("bulk_frames", frames);
        result.put("bulk_frames_waiting_index", waitingFrames); result.put("bulk_frames_post_index_response", postResponseFrames);
        result.put("bulk_payload_bytes", payloadBytes); result.put("index_response_count", responses);
        result.put("today_response_transport_ack_seen", todayAck); result.put("past_response_transport_ack_seen", pastAck);
        result.put("complete_file_count", entries.size()); result.put("incomplete_file_present", file != null);
        result.put("status", file != null ? "diagnostic_index_boundary_incomplete" : "diagnostic_index_boundary");
        return result;
    }
    @Override public void close() {
        try {
            if (file != null) save(null, false, true);
            for (int i = 0; i < entries.size(); i++) {
                Entry entry = entries.get(i);
                entry.summary.put("received_id_in_today_index", entry.id != null && today != null ? today.containsId(entry.id) : null);
                entry.summary.put("received_id_in_past_index", entry.id != null && past != null ? past.containsId(entry.id) : null);
                if (entry.id != null) {
                    if (today != null) entry.summary.putAll(today.identityComparison(entry.id, "today_"));
                    if (past != null) entry.summary.putAll(past.identityComparison(entry.id, "past_"));
                    boolean repeated = false;
                    for (int j = 0; j < i; j++) if (java.util.Arrays.equals(entry.id, entries.get(j).id)) repeated = true;
                    entry.summary.put("received_id_repeated_in_window", repeated);
                }
                observer.onFile(entry.summary);
            }
        } finally {
            if (file != null) { file.close(); file = null; }
            for (Entry entry : entries) Auth.wipe(entry.id);
            entries.clear();
            if (today != null) { today.close(); today = null; }
            if (past != null) { past.close(); past = null; }
        }
    }
}
