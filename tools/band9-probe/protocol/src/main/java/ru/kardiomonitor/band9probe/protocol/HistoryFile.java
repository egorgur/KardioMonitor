package ru.kardiomonitor.band9probe.protocol;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;

/** Bounded in-memory activity-file assembly. Never sends a history acknowledgement. */
public final class HistoryFile implements AutoCloseable {
    public static final int MAX_BYTES = 512 * 1024;
    public static final int MAX_CHUNKS = 2048;
    private final byte[] expectedId;
    private final byte[] buffer;
    private int size, total, chunks;
    private boolean closed;
    private Boolean crcValid, idMatch, timestampMatch, timezoneMatch, versionMatch, kindMatch, receivedPaddingZero;
    private Integer expectedIdHeaderOffset;
    private String receivedCategory, timestampRelation;
    private Map<String, Object> projection = Collections.emptyMap();

    public HistoryFile(byte[] id, int remainingBytes) throws ProbeException { this(id, remainingBytes, true); }
    static HistoryFile quarantine(int remainingBytes) throws ProbeException { return new HistoryFile(null, remainingBytes, false); }
    private HistoryFile(byte[] id, int remainingBytes, boolean requested) throws ProbeException {
        if (requested) requireAllowed(id);
        if (remainingBytes < 13 || remainingBytes > MAX_BYTES) throw new ProbeException("history_read_byte_budget");
        expectedId = requested ? id.clone() : null; buffer = new byte[remainingBytes];
    }
    static void requireAllowed(byte[] id) throws ProbeException {
        if (id.length != 7 || !((id[5] == 4 && id[6] == 0) || (id[5] == 5 && id[6] == 1)))
            throw new ProbeException("history_read_file_not_allowed");
    }
    public static byte[] request(byte[] id) throws ProbeException {
        requireAllowed(id);
        try (Proto.Writer healthWriter = new Proto.Writer(); Proto.Writer commandWriter = new Proto.Writer()) {
            byte[] health = healthWriter.bytes(2, id).build();
            try { return commandWriter.number(1, 8).number(2, 3).bytes(10, health).build(); }
            finally { Auth.wipe(health); }
        }
    }
    public void accept(byte[] chunk) throws ProbeException {
        requireOpen();
        if (chunk.length <= 4) throw new ProbeException("history_chunk_too_short");
        int declared = Wire.le16(chunk, 0), number = Wire.le16(chunk, 2);
        if (declared < 1 || declared > MAX_CHUNKS || number != chunks + 1 || number > declared
                || (total != 0 && declared != total)) throw new ProbeException("history_chunk_order");
        if (total != 0 && chunks == total) throw new ProbeException("history_chunk_after_complete");
        int count = chunk.length - 4;
        if (count > buffer.length - size) throw new ProbeException("history_file_too_large");
        total = declared; chunks++;
        System.arraycopy(chunk, 4, buffer, size, count); size += count;
    }
    public boolean complete() { return total > 0 && chunks == total; }
    public int size() { return size; }
    public void verifyAndSummarize() throws ProbeException { verifyAndSummarize(-1L); }
    public void verifyAndSummarize(long referenceTimeMillis) throws ProbeException {
        requireOpen();
        if (expectedId == null) throw new ProbeException("history_file_without_request");
        inspectHeader();
        if (!idMatch) throw new ProbeException("history_file_id_mismatch");
        if (buffer[7] != 0) throw new ProbeException("history_file_padding");
        projection = DailyFileSummary.parse(buffer, size, expectedId[5] & 255, expectedId[6] & 3, referenceTimeMillis);
    }
    boolean verifyHeaderOnly() throws ProbeException {
        inspectHeader();
        if (buffer[7] != 0) throw new ProbeException("history_file_padding");
        return Boolean.TRUE.equals(idMatch);
    }
    private void inspectHeader() throws ProbeException {
        requireOpen();
        if (!complete() || size < 13) throw new ProbeException("history_file_truncated");
        CRC32 crc = new CRC32(); crc.update(buffer, 0, size - 4);
        crcValid = crc.getValue() == DailyFileSummary.u32(buffer, size - 4);
        if (!crcValid) throw new ProbeException("history_file_crc");
        receivedCategory = buffer[5] == 4 && buffer[6] == 0 ? "daily_details_v4"
                : buffer[5] == 5 && buffer[6] == 1 ? "daily_summary_v5" : "other";
        receivedPaddingZero = buffer[7] == 0;
        if (expectedId == null) return;
        expectedIdHeaderOffset = -1;
        for (int offset = 0; offset <= Math.min(16, size - 11); offset++) {
            boolean match = true;
            for (int i = 0; i < 7; i++) if (buffer[offset + i] != expectedId[i]) match = false;
            if (match) { expectedIdHeaderOffset = offset; break; }
        }
        timestampMatch = true;
        for (int i = 0; i < 4; i++) if (buffer[i] != expectedId[i]) timestampMatch = false;
        timezoneMatch = buffer[4] == expectedId[4];
        versionMatch = buffer[5] == expectedId[5];
        kindMatch = buffer[6] == expectedId[6];
        idMatch = timestampMatch && timezoneMatch && versionMatch && kindMatch;
        timestampRelation = timestampRelation(expectedId, buffer);
    }
    static String timestampRelation(byte[] expected, byte[] received) {
        long a = DailyFileSummary.u32(expected, 0), b = DailyFileSummary.u32(received, 0);
        if (a == b) return "exact";
        long reversed = 0;
        for (int i = 0; i < 4; i++) reversed = (reversed << 8) | (expected[i] & 255);
        if (b == reversed) return "byte_order_reversed";
        if (expected[4] != received[4]) return "unclassified";
        long zone = (long) expected[4] * 900;
        if (zone != 0 && b - a == zone) return "plus_descriptor_timezone";
        if (zone != 0 && b - a == -zone) return "minus_descriptor_timezone";
        if (a / 60 == b / 60) return "same_minute";
        return "unclassified";
    }
    byte[] verifiedDigest() throws java.security.GeneralSecurityException, ProbeException {
        requireOpen();
        if (!Boolean.TRUE.equals(crcValid) || !Boolean.TRUE.equals(idMatch)) throw new ProbeException("history_digest_unverified");
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        digest.update(buffer, 0, size);
        return digest.digest();
    }
    byte[] quarantinedIdCopy() throws ProbeException {
        if (closed || !Boolean.TRUE.equals(crcValid) || !Boolean.TRUE.equals(receivedPaddingZero))
            throw new ProbeException("history_quarantine_id_unverified");
        return java.util.Arrays.copyOf(buffer, 7);
    }
    boolean receivedIdMatches(byte[] ids, int offset) {
        requireOpen();
        if (!Boolean.TRUE.equals(crcValid) || size < 13) return false;
        for (int i = 0; i < 7; i++) if (buffer[i] != ids[offset + i]) return false;
        return true;
    }
    public Map<String, Object> summary() {
        requireOpen();
        Map<String, Object> safe = new LinkedHashMap<>();
        safe.put("type", expectedId == null ? null : 0); safe.put("subtype", expectedId == null ? null : 0);
        safe.put("detail", expectedId == null ? null : expectedId[6] & 3);
        safe.put("version", expectedId == null ? null : expectedId[5] & 255);
        safe.put("bytes", size); safe.put("chunks", chunks); safe.put("declared_chunks", total);
        safe.put("transfer_complete", complete()); safe.put("crc_valid", crcValid); safe.put("file_id_match", idMatch);
        safe.put("file_id_timestamp_match", timestampMatch); safe.put("file_id_timezone_match", timezoneMatch);
        safe.put("file_id_timestamp_relation", timestampRelation);
        safe.put("file_id_version_match", versionMatch); safe.put("file_id_kind_match", kindMatch);
        safe.put("expected_id_header_offset", expectedIdHeaderOffset);
        safe.put("received_file_category", receivedCategory); safe.put("received_padding_zero", receivedPaddingZero);
        safe.put("parser_status", "not_parsed"); safe.putAll(projection);
        return Collections.unmodifiableMap(safe);
    }
    void requireOpen() { if (closed) throw new IllegalStateException("history_file_closed"); }
    @Override public void close() { closed = true; Auth.wipe(expectedId, buffer); }
}
