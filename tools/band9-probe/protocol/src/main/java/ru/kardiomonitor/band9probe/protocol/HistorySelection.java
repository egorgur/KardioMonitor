package ru.kardiomonitor.band9probe.protocol;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/** Private, wipeable index snapshot, deliberately without a string or public ID representation. */
final class HistorySelection implements AutoCloseable {
    static final int MAX_FILES = 8;
    private final byte[] ids;
    private final List<byte[]> selected = new ArrayList<>();
    private int candidates, detailCandidates;
    private boolean closed;
    HistorySelection(Proto response, boolean select) throws ProbeException { this(response, select, false, -1); }
    HistorySelection(Proto response, boolean select, boolean sample) throws ProbeException { this(response, select, sample, -1); }
    HistorySelection(Proto response, boolean select, boolean sample, int sampleDetailOffset) throws ProbeException {
        this(response, select, sample, sampleDetailOffset, 1);
    }
    static void validateSample(boolean sample, int offset, int count) throws ProbeException {
        if (offset < -1 || offset >= HistoryIndex.MAX_IDS || count < 1 || count > 2
                || (!sample && offset >= 0) || (count != 1 && offset < 0))
            throw new ProbeException("history_sample_invalid_range");
    }
    static HistorySelection pastSnapshot(Proto response) throws ProbeException {
        return new HistorySelection(response, false, false, -1, 1, 2);
    }
    static HistorySelection pastDetails(Proto response, int offset, int count) throws ProbeException {
        if (offset < 0) throw new ProbeException("history_sample_invalid_range");
        return new HistorySelection(response, true, true, offset, count, 2);
    }
    HistorySelection(Proto response, boolean select, boolean sample, int sampleDetailOffset, int sampleDetailCount) throws ProbeException {
        this(response, select, sample, sampleDetailOffset, sampleDetailCount, 1);
    }
    private HistorySelection(Proto response, boolean select, boolean sample, int sampleDetailOffset, int sampleDetailCount, int subtype) throws ProbeException {
        validateSample(sample, sampleDetailOffset, sampleDetailCount);
        response.requireSuccess();
        if (response.number(1) != 8 || response.number(2) != subtype) throw new ProbeException("history_read_index_type");
        try (Proto health = response.message(10)) { ids = health.bytes(2).clone(); }
        try {
            if (ids.length > HistoryIndex.MAX_IDS * 7 || ids.length % 7 != 0)
                throw new ProbeException("history_read_index_length");
            if (select) {
                boolean summarySelected = false;
                for (int pos = 0; pos < ids.length; pos += 7) {
                    int version = ids[pos + 5] & 255, flags = ids[pos + 6] & 255;
                    if ((version == 4 && flags == 0) || (version == 5 && flags == 1)) {
                        boolean duplicate = false;
                        for (byte[] previous : selected) {
                            boolean equal = true;
                            for (int i = 0; i < 7; i++) if (previous[i] != ids[pos + i]) equal = false;
                            if (equal) { duplicate = true; break; }
                        }
                        if (duplicate) continue;
                        if (flags == 1 && summarySelected) throw new ProbeException("history_read_ambiguous_summary");
                        if (flags == 1) {
                            summarySelected = true;
                            selected.add(0, Arrays.copyOfRange(ids, pos, pos + 7));
                        } else {
                            selected.add(Arrays.copyOfRange(ids, pos, pos + 7));
                        }
                    }
                }
                candidates = selected.size();
                detailCandidates = candidates - (summarySelected ? 1 : 0);
                if (sample) {
                    if (sampleDetailOffset < 0) {
                        int limit = summarySelected ? 2 : 1;
                        while (selected.size() > limit) Auth.wipe(selected.remove(selected.size() - 1));
                    } else {
                        if (sampleDetailOffset >= detailCandidates)
                            throw new ProbeException("history_sample_offset_out_of_range");
                        int target = (summarySelected ? 1 : 0) + sampleDetailOffset;
                        for (int i = selected.size() - 1; i >= 0; i--) {
                            if (i < target || i >= target + sampleDetailCount) Auth.wipe(selected.remove(i));
                        }
                    }
                } else if (selected.size() > MAX_FILES) throw new ProbeException("history_read_file_count_limit");
            }
        } catch (ProbeException e) { close(); throw e; }
    }
    int indexedCount() { requireOpen(); return ids.length / 7; }
    int count() { requireOpen(); return selected.size(); }
    int candidateCount() { requireOpen(); return candidates; }
    int detailCandidateCount() { requireOpen(); return detailCandidates; }
    int omittedCount() { requireOpen(); return candidates - selected.size(); }
    byte[] id(int index) { requireOpen(); return selected.get(index); }
    boolean containsId(byte[] id) {
        requireOpen();
        if (id == null || id.length != 7) return false;
        for (int offset = 0; offset < ids.length; offset += 7) {
            boolean equal = true;
            for (int i = 0; i < 7; i++) if (ids[offset + i] != id[i]) equal = false;
            if (equal) return true;
        }
        return false;
    }
    Map<String, Object> identityComparison(byte[] id, String prefix) {
        requireOpen();
        if (id == null || id.length != 7) throw new IllegalArgumentException("history_id_length");
        int minimum = 8, nearestMask = 0;
        boolean ambiguousMask = false;
        for (int offset = 0; offset < ids.length; offset += 7) {
            int mask = 0;
            for (int i = 0; i < 7; i++) if (ids[offset + i] != id[i]) mask |= 1 << i;
            int differences = Integer.bitCount(mask);
            if (differences < minimum) { minimum = differences; nearestMask = mask; ambiguousMask = false; }
            else if (differences == minimum && mask != nearestMask) ambiguousMask = true;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(prefix + "minimum_differing_id_bytes", minimum == 8 ? null : minimum);
        result.put(prefix + "nearest_id_difference_mask", minimum == 8 || ambiguousMask ? null : nearestMask);
        result.put(prefix + "nearest_id_mask_ambiguous", minimum == 8 ? null : ambiguousMask);
        return result;
    }
    boolean containsReceivedId(HistoryFile file) {
        requireOpen(); file.requireOpen();
        for (int offset = 0; offset < ids.length; offset += 7) if (file.receivedIdMatches(ids, offset)) return true;
        return false;
    }
    boolean unchanged(HistorySelection other) {
        requireOpen(); other.requireOpen();
        if (ids.length != other.ids.length) return false;
        // Compare multisets without exporting or sorting ID/timestamp objects.
        boolean[] used = new boolean[other.ids.length / 7];
        for (int pos = 0; pos < ids.length; pos += 7) {
            boolean found = false;
            for (int j = 0; j < used.length && !found; j++) {
                if (used[j]) continue;
                boolean equal = true;
                for (int k = 0; k < 7; k++) if (ids[pos + k] != other.ids[j * 7 + k]) equal = false;
                if (equal) { used[j] = true; found = true; }
            }
            if (!found) return false;
        }
        return true;
    }
    private void requireOpen() { if (closed) throw new IllegalStateException("history_index_closed"); }
    @Override public void close() {
        closed = true;
        Auth.wipe(ids);
        for (byte[] id : selected) Auth.wipe(id);
        selected.clear();
    }
}
