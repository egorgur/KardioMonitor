package ru.kardiomonitor.band9probe.protocol;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.zip.CRC32;

/** Synthetic data only. Values intentionally do not describe a person or a real file identifier. */
final class HistoryTests {
    static final byte[] DETAILS_ID = {1, 2, 3, 4, 0, 4, 0};
    static final byte[] SUMMARY_ID = {1, 2, 3, 4, 0, 5, 1};
    private interface Check { void run() throws Exception; }
    private static int count;
    private static void test(String label, Check check) throws Exception {
        check.run(); count++; System.out.println("PASS " + label);
    }
    static void yes(boolean value) { if (!value) throw new AssertionError("history fixture mismatch"); }
    private static void rejects(Check check) throws Exception {
        try { check.run(); } catch (IOException expected) { return; }
        throw new AssertionError("history fixture should reject");
    }
    private static void rejectsClosed(Check check) throws Exception {
        try { check.run(); } catch (IllegalStateException expected) { return; }
        throw new AssertionError("closed history object should reject");
    }
    static byte[] file(byte[] id, byte[] body) {
        byte[] result = new byte[12 + body.length];
        System.arraycopy(id, 0, result, 0, 7); System.arraycopy(body, 0, result, 8, body.length);
        crc(result); return result;
    }
    static void crc(byte[] bytes) {
        CRC32 crc = new CRC32(); crc.update(bytes, 0, bytes.length - 4);
        for (int i = 0; i < 4; i++) bytes[bytes.length - 4 + i] = (byte) (crc.getValue() >>> (8 * i));
    }
    static byte[] chunk(int total, int number, byte[] data) {
        byte[] result = new byte[data.length + 4];
        result[0] = (byte) total; result[1] = (byte) (total >>> 8);
        result[2] = (byte) number; result[3] = (byte) (number >>> 8);
        System.arraycopy(data, 0, result, 4, data.length); return result;
    }
    static byte[] details() {
        // Group0 steps + extra flag, group4 HR; second record adds the documented extra byte.
        return file(DETAILS_ID, new byte[]{(byte) 0xb0, 0, (byte) 0xc0, 0, 0, 0,
                1, 0, 70, 2, 0x40, 0, 99});
    }
    static byte[] summary() {
        byte[] body = new byte[61]; body[0] = (byte) 0x80;
        body[4] = 0x39; body[5] = 0x30; // uint32 LE 12345, valid slot0.
        return file(SUMMARY_ID, body);
    }
    static byte[] manyRecords(int records) {
        byte[] body = new byte[6 + records]; body[2] = (byte) 0xc0;
        Arrays.fill(body, 6, body.length, (byte) 70); return file(DETAILS_ID, body);
    }
    private static Proto index(byte[] ids) throws Exception { return index(ids, 1); }
    private static Proto index(byte[] ids, int subtype) throws Exception {
        return Proto.parse(new Proto.Writer().number(1, 8).number(2, subtype)
                .bytes(10, new Proto.Writer().bytes(2, ids).build()).build());
    }
    private static Map<String, Object> verified(byte[] id, byte[] bytes) throws Exception {
        return verified(id, bytes, -1L);
    }
    private static Map<String, Object> verified(byte[] id, byte[] bytes, long referenceTimeMillis) throws Exception {
        try (HistoryFile file = new HistoryFile(id, HistoryFile.MAX_BYTES)) {
            file.accept(chunk(1, 1, bytes)); file.verifyAndSummarize(referenceTimeMillis); return file.summary();
        }
    }
    private static Map<String, Object> clockSummary(long epochSeconds, int timezone, int records, long reference) throws Exception {
        byte[] id = {0, 0, 0, 0, (byte) timezone, 4, 0};
        for (int i = 0; i < 4; i++) id[i] = (byte) (epochSeconds >>> (i * 8));
        byte[] body = new byte[6 + records]; body[2] = (byte) 0xc0;
        Arrays.fill(body, 6, body.length, (byte) 70);
        return verified(id, file(id, body), reference);
    }
    static byte[] largeFrame(int sequence, byte[] payload) {
        byte[] result = new byte[8 + payload.length];
        result[0] = (byte) 0xa5; result[1] = (byte) 0xa5; result[2] = 3; result[3] = (byte) sequence;
        result[4] = (byte) payload.length; result[5] = (byte) (payload.length >>> 8);
        int crc = Wire.crc16(payload); result[6] = (byte) crc; result[7] = (byte) (crc >>> 8);
        System.arraycopy(payload, 0, result, 8, payload.length); return result;
    }
    static int run() throws Exception {
        test("Value diagnostics separate validity from encoded zero and 255 without treating them as measured HR", () -> {
            byte[] body = {(byte) 0xb0, 0, (byte) 0xc0, 0, 0, 0,
                    0, (byte) 0x80, 0, -1, -1, -1, 99, 1, 0, 70, 0, 0x40, 90, 99};
            Map<String, Object> result = verified(DETAILS_ID, file(DETAILS_ID, body));
            yes(result.get("detail_records").equals(4) && result.get("hr_present_records").equals(4));
            yes(result.get("hr_encoded_zero_records").equals(1) && result.get("hr_encoded_255_records").equals(1));
            yes(result.get("step_records").equals(4) && result.get("step_encoded_zero_records").equals(2));
            yes(result.get("step_encoded_nonzero_records").equals(2));
            yes(result.get("value_interpretation_status").equals("source_encoding_only_not_measurement_validation"));
        });
        test("Absent validity never turns encoded bytes into zero HR or step observations", () -> {
            Map<String, Object> result = verified(DETAILS_ID,
                    file(DETAILS_ID, new byte[]{(byte) 0xa0, 0, (byte) 0x80, 0, 0, 0, 0, 0, 0}));
            yes(result.get("detail_records").equals(1) && result.get("hr_missing_records").equals(1));
            for (String key : new String[]{"hr_encoded_zero_records", "hr_encoded_255_records", "step_records",
                    "step_encoded_zero_records", "step_encoded_nonzero_records"}) yes(result.get(key).equals(0));
        });
        test("Candidate minute timeline does not apply positive or negative descriptor timezone twice", () -> {
            for (int zone : new int[]{-48, -4, 0, 28, 56, -128, 127}) {
                Map<String, Object> result = clockSummary(60000L, zone, 2, 60060L * 1000L);
                yes(result.get("candidate_last_record_not_future").equals(true));
                yes(result.get("descriptor_timezone_in_civil_range").equals(zone >= -48 && zone <= 56));
                yes(result.get("descriptor_minute_aligned").equals(true));
                yes(result.get("candidate_time_basis").equals("unsigned_epoch_seconds_no_timezone_adjustment"));
                yes(result.get("reference_clock_verified").equals(false));
                yes(result.get("time_mapping_status").equals("source_hypothesis_not_independently_verified"));
            }
            yes(clockSummary(60000L, 0, 2, 60059L * 1000L).get("candidate_last_record_not_future").equals(false));
        });
        test("Unsigned candidate timestamp and final minute use long arithmetic across signed boundary and uint32 ceiling", () -> {
            for (long start : new long[]{0x7fffffffL, 0x80000000L, 0xffffffffL}) {
                Map<String, Object> result = clockSummary(start, 0, 2, (start + 60L) * 1000L);
                yes(result.get("candidate_last_record_not_future").equals(true));
                yes(result.get("descriptor_signed_unsigned_epoch_agree").equals(start <= Integer.MAX_VALUE));
                yes(result.get("descriptor_minute_aligned").equals(start % 60L == 0));
            }
        });
        test("Unavailable clock and empty records leave candidate last time unknown rather than verified", () -> {
            for (long reference : new long[]{-1L, 0L}) {
                Map<String, Object> result = clockSummary(60000L, 0, 2, reference);
                yes(result.get("reference_clock_available").equals(false));
                yes(result.get("candidate_epoch_age_band").equals("reference_unavailable"));
                yes(result.get("candidate_last_record_not_future") == null);
            }
            yes(clockSummary(60000L, 0, 0, 60060L * 1000L).get("candidate_last_record_not_future") == null);
        });
        test("Clock age bands are bounded metadata with exact edges not a proof of absolute time", () -> {
            long start = 60000L;
            long[] ages = {-1L, 0L, 86400L, 86401L, 7L * 86400L, 7L * 86400L + 1L, 31L * 86400L, 31L * 86400L + 1L};
            String[] bands = {"future", "within_24h", "within_24h", "within_7d", "within_7d", "within_31d", "within_31d", "older"};
            for (int i = 0; i < ages.length; i++) {
                Map<String, Object> result = clockSummary(start, 0, 1, (start + ages[i]) * 1000L);
                yes(result.get("candidate_epoch_age_band").equals(bands[i]));
                for (String key : new String[]{"timestamp", "epoch_seconds", "heart_rate", "steps", "file_id", "records"})
                    yes(!result.containsKey(key));
            }
        });
        test("Unsupported detail layout exports no new time or value diagnostics", () -> {
            Map<String, Object> result = verified(DETAILS_ID, file(DETAILS_ID, new byte[]{0}));
            yes(result.get("parser_status").equals("unsupported_details_header"));
            yes(!result.containsKey("hr_encoded_zero_records") && !result.containsKey("candidate_time_basis"));
        });
        test("Past repeat digest and original target are wiped on completion close and observer failure", () -> {
            for (boolean fail : new boolean[]{false, true}) {
                int[] emissions = {0};
                Session.HistoryObserver observer = new Session.HistoryObserver() {
                    @Override public void onStage(String stage) {}
                    @Override public void onSelection(int count) {}
                    @Override public void onFile(Map<String, Object> summary) {
                        if (++emissions[0] == 2 && fail) throw new IllegalStateException("synthetic observer");
                    }
                };
                HistoryDemultiplexer demux = new HistoryDemultiplexer(observer, false, true, true);
                demux.request(DETAILS_ID, null, null); demux.accept(chunk(1, 1, details()), false);
                Field digestField = HistoryDemultiplexer.class.getDeclaredField("firstDigest"); digestField.setAccessible(true);
                Field targetField = HistoryDemultiplexer.class.getDeclaredField("target"); targetField.setAccessible(true);
                byte[] digest = (byte[]) digestField.get(demux), target = (byte[]) targetField.get(demux);
                yes(digest.length == 32 && !Arrays.equals(digest, new byte[32]));
                try {
                    demux.repeat();
                    try { demux.accept(chunk(1, 1, details()), false); yes(!fail); }
                    catch (IllegalStateException expected) { yes(fail); }
                    yes(Arrays.equals(digest, new byte[32]) && digestField.get(demux) == null);
                    yes(demux.repeatBytesIdentical().equals(true) && demux.repeatAggregateConsistent().equals(true));
                    rejects(demux::repeat);
                } finally { demux.close(); }
                yes(Arrays.equals(target, new byte[7]));
            }
            HistoryDemultiplexer cancelled = new HistoryDemultiplexer(new Session.HistoryObserver() {
                @Override public void onStage(String stage) {}
                @Override public void onSelection(int count) {}
                @Override public void onFile(Map<String, Object> summary) {}
            }, false, true, true);
            rejects(cancelled::repeat);
            cancelled.request(DETAILS_ID, null, null); cancelled.accept(chunk(1, 1, details()), false);
            Field digestField = HistoryDemultiplexer.class.getDeclaredField("firstDigest"); digestField.setAccessible(true);
            byte[] digest = (byte[]) digestField.get(cancelled);
            cancelled.repeat(); cancelled.accept(chunk(2, 1, Arrays.copyOfRange(details(), 0, 15)), false);
            cancelled.close(); yes(Arrays.equals(digest, new byte[32]));
        });
        test("History fetch canonical type8/sub3 seven-byte file ID request", () -> {
            yes(Arrays.equals(HistoryFile.request(DETAILS_ID), new byte[]{8, 8, 16, 3, 82, 9, 18, 7, 1, 2, 3, 4, 0, 4, 0}));
            for (byte[] id : new byte[][]{new byte[6], new byte[8], {1, 2, 3, 4, 0, 3, 0},
                    {1, 2, 3, 4, 0, 4, 1}, {1, 2, 3, 4, 0, 5, 0}, {1, 2, 3, 4, 0, 5, (byte) 0x81}})
                rejects(() -> HistoryFile.request(id));
        });
        test("Literal index wire contract preserves seven-byte IDs including high timestamp and timezone bits", () -> {
            byte[] first = {0x78, 0x56, 0x34, 0x12, -4, 4, 0};
            byte[] second = {-17, -51, -85, -119, -128, 5, 1};
            byte[] literal = {8, 8, 16, 2, 82, 16, 18, 14,
                    0x78, 0x56, 0x34, 0x12, -4, 4, 0, -17, -51, -85, -119, -128, 5, 1};
            HistorySelection snapshot, selected;
            try (Proto response = Proto.parse(literal)) {
                snapshot = HistorySelection.pastSnapshot(response);
                selected = HistorySelection.pastDetails(response, 0, 1);
                try (Proto health = response.message(10)) { Arrays.fill(health.bytes(2), (byte) 0); }
            }
            try (snapshot; selected) {
                yes(snapshot.indexedCount() == 2 && snapshot.containsId(first) && snapshot.containsId(second));
                yes(selected.count() == 1 && Arrays.equals(selected.id(0), first));
                byte[] request = {8, 8, 16, 3, 82, 9, 18, 7, 0x78, 0x56, 0x34, 0x12, -4, 4, 0};
                yes(Arrays.equals(HistoryFile.request(selected.id(0)), request));
                byte[] bytes = file(first, new byte[]{1});
                try (HistoryFile file = new HistoryFile(first, 1024)) {
                    file.accept(chunk(1, 1, bytes));
                    yes(file.verifyHeaderOnly() && snapshot.containsReceivedId(file));
                    yes(file.summary().get("expected_id_header_offset").equals(0));
                }
            }
        });
        test("Every ID byte is required for strict file identity and snapshot membership without normalization", () -> {
            try (Proto response = index(DETAILS_ID); HistorySelection snapshot = new HistorySelection(response, false)) {
                for (int position = 0; position < 7; position++) {
                    byte[] changed = DETAILS_ID.clone(); changed[position] ^= 1;
                    try (HistoryFile file = new HistoryFile(DETAILS_ID, 1024)) {
                        file.accept(chunk(1, 1, file(changed, new byte[]{1})));
                        yes(!file.verifyHeaderOnly());
                        yes(!snapshot.containsId(changed) && !snapshot.containsReceivedId(file));
                        rejects(file::verifyAndSummarize);
                        yes(file.summary().get("parser_status").equals("not_parsed"));
                    }
                }
                for (byte[] changed : new byte[][]{{4, 3, 2, 1, 0, 4, 0}, {0, 1, 2, 3, 4, 0, 4}})
                    yes(!snapshot.containsId(changed));
            }
        });
        test("Index identity diagnostics expose only differing-byte counts and positions for all seven bytes", () -> {
            try (Proto response = index(DETAILS_ID); HistorySelection snapshot = new HistorySelection(response, false)) {
                for (int position = 0; position < 7; position++) {
                    byte[] changed = DETAILS_ID.clone(); changed[position] ^= 1;
                    Map<String, Object> diagnostic = snapshot.identityComparison(changed, "today_");
                    yes(diagnostic.size() == 3 && diagnostic.get("today_minimum_differing_id_bytes").equals(1));
                    yes(diagnostic.get("today_nearest_id_difference_mask").equals(1 << position));
                    yes(diagnostic.get("today_nearest_id_mask_ambiguous").equals(false));
                    yes(!snapshot.containsId(changed));
                }
                yes(snapshot.identityComparison(DETAILS_ID, "today_").get("today_nearest_id_difference_mask").equals(0));
            }
        });
        test("Nearest ID mask ambiguity and empty snapshot stay unknown without choosing an ID", () -> {
            byte[] first = DETAILS_ID.clone(), second = DETAILS_ID.clone(); first[0] ^= 1; second[4] ^= 1;
            try (Proto response = index(Crypto.join(first, second)); HistorySelection snapshot = new HistorySelection(response, false)) {
                Map<String, Object> diagnostic = snapshot.identityComparison(DETAILS_ID, "past_");
                yes(diagnostic.get("past_minimum_differing_id_bytes").equals(1));
                yes(diagnostic.get("past_nearest_id_difference_mask") == null && diagnostic.get("past_nearest_id_mask_ambiguous").equals(true));
            }
            HistorySelection closed;
            try (Proto response = index(new byte[0]); HistorySelection empty = new HistorySelection(response, false)) {
                closed = empty;
                yes(empty.identityComparison(DETAILS_ID, "past_").values().stream().allMatch(java.util.Objects::isNull));
            }
            rejectsClosed(() -> closed.identityComparison(DETAILS_ID, "past_"));
        });
        test("Boundary repeat diagnostic compares private verified IDs then wipes every retained ID", () -> {
            java.util.List<Map<String, Object>> files = new java.util.ArrayList<>();
            Session.HistoryObserver observer = new Session.HistoryObserver() {
                public void onStage(String stage) {} public void onSelection(int count) {}
                public void onFile(Map<String, Object> summary) { files.add(summary); }
            };
            HistoryBoundary boundary = new HistoryBoundary(observer);
            boundary.begin(1);
            try (Proto response = index(DETAILS_ID)) { boundary.response(response, 1, true); }
            boundary.accept(chunk(1, 1, details()), true); boundary.accept(chunk(1, 1, details()), true);
            java.lang.reflect.Field entries = HistoryBoundary.class.getDeclaredField("entries"); entries.setAccessible(true);
            Object entry = ((java.util.List<?>) entries.get(boundary)).get(0);
            java.lang.reflect.Field retainedId = entry.getClass().getDeclaredField("id"); retainedId.setAccessible(true);
            byte[] owned = (byte[]) retainedId.get(entry);
            boundary.close();
            yes(files.size() == 2 && files.get(0).get("received_id_repeated_in_window").equals(false));
            yes(files.get(1).get("received_id_repeated_in_window").equals(true));
            yes(files.get(0).get("today_minimum_differing_id_bytes").equals(0));
            yes(Arrays.equals(owned, new byte[7]));
        });
        test("Timestamp relation classifies only exact byte order timezone and same minute without exporting time", () -> {
            byte[] a = {0x10, 0x20, 0x30, 0x40, 28, 4, 0};
            yes(HistoryFile.timestampRelation(a, a).equals("exact"));
            byte[] reversed = {0x40, 0x30, 0x20, 0x10, 28, 4, 0};
            yes(HistoryFile.timestampRelation(a, reversed).equals("byte_order_reversed"));
            for (int tz : new int[]{-48, 28, 56}) {
                for (int direction : new int[]{-1, 1}) {
                    byte[] expected = {0, 0, 0x10, 0, (byte) tz, 4, 0}, received = expected.clone();
                    long target = DailyFileSummary.u32(expected, 0) + (long) tz * 900 * direction;
                    for (int i = 0; i < 4; i++) received[i] = (byte) (target >>> (8 * i));
                    yes(HistoryFile.timestampRelation(expected, received).equals(direction > 0 ? "plus_descriptor_timezone" : "minus_descriptor_timezone"));
                    try (HistoryFile file = new HistoryFile(expected, 1024)) {
                        file.accept(chunk(1, 1, file(received, new byte[]{1})));
                        rejects(file::verifyAndSummarize);
                        yes(file.summary().get("parser_status").equals("not_parsed"));
                        yes(file.summary().get("file_id_timestamp_relation").equals(direction > 0 ? "plus_descriptor_timezone" : "minus_descriptor_timezone"));
                    }
                    received[4]++;
                    yes(HistoryFile.timestampRelation(expected, received).equals("unclassified"));
                }
            }
            byte[] minute = {120, 0, 0, 0, 0, 4, 0}, second = minute.clone(); second[0]++;
            yes(HistoryFile.timestampRelation(minute, second).equals("same_minute"));
            second[0] = (byte) 180;
            yes(HistoryFile.timestampRelation(minute, second).equals("unclassified"));
        });
        test("Header-only diagnostic never parses even exact file and cannot weaken strict API", () -> {
            for (boolean exact : new boolean[]{true, false}) {
                byte[] expected = DETAILS_ID.clone(); if (!exact) expected[0]++;
                try (HistoryFile file = new HistoryFile(expected, 1024)) {
                    file.accept(chunk(1, 1, details()));
                    yes(file.verifyHeaderOnly() == exact && file.summary().get("parser_status").equals("not_parsed"));
                    if (!exact) rejects(file::verifyAndSummarize);
                }
            }
            for (boolean badCrc : new boolean[]{true, false}) {
                byte[] data = details();
                if (badCrc) data[data.length - 1] ^= 1;
                else { data[7] = 1; crc(data); }
                try (HistoryFile file = new HistoryFile(DETAILS_ID, 1024)) {
                    file.accept(chunk(1, 1, data)); rejects(file::verifyHeaderOnly);
                    yes(file.summary().get("parser_status").equals("not_parsed"));
                }
            }
        });
        test("Quarantine cannot parse even an exact known ID and wipes its buffer", () -> {
            HistoryFile file = HistoryFile.quarantine(1024);
            Field bufferField = HistoryFile.class.getDeclaredField("buffer"); bufferField.setAccessible(true);
            byte[] buffer = (byte[]) bufferField.get(file);
            try {
                file.accept(chunk(1, 1, details()));
                yes(!file.verifyHeaderOnly() && file.summary().get("file_id_match") == null);
                rejects(file::verifyAndSummarize);
                yes(file.summary().get("parser_status").equals("not_parsed") && file.summary().get("version") == null);
            } finally { file.close(); }
            yes(Arrays.equals(buffer, new byte[buffer.length]));
        });
        test("Deferred boundary IDs require CRC and zero padding and are owned wipeable copies", () -> {
            try (HistoryFile file = HistoryFile.quarantine(1024)) {
                rejects(file::quarantinedIdCopy);
                file.accept(chunk(1, 1, details())); file.verifyHeaderOnly();
                byte[] id = file.quarantinedIdCopy(); yes(Arrays.equals(id, DETAILS_ID));
                id[0] = 42; yes(file.receivedIdMatches(DETAILS_ID, 0)); Auth.wipe(id);
                file.close(); rejects(file::quarantinedIdCopy);
            }
            for (boolean crcError : new boolean[]{true, false}) {
                byte[] bytes = details();
                if (crcError) bytes[bytes.length - 1] ^= 1;
                else { bytes[7] = 1; crc(bytes); }
                try (HistoryFile file = HistoryFile.quarantine(1024)) {
                    file.accept(chunk(1, 1, bytes)); rejects(file::verifyHeaderOnly); rejects(file::quarantinedIdCopy);
                }
            }
        });
        test("Boundary wipes deferred IDs snapshots and unfinished payload even when observer fails", () -> {
            for (boolean fail : new boolean[]{false, true}) {
                Session.HistoryObserver observer = new Session.HistoryObserver() {
                    @Override public void onStage(String stage) {}
                    @Override public void onSelection(int count) {}
                    @Override public void onFile(Map<String, Object> summary) { if (fail) throw new IllegalStateException("synthetic observer"); }
                };
                HistoryBoundary boundary = new HistoryBoundary(observer);
                boundary.begin(2); boundary.accept(chunk(1, 1, details()), true);
                try (Proto snapshot = index(DETAILS_ID, 2)) { boundary.response(snapshot, 2, true); }
                boundary.begin(1); boundary.accept(chunk(2, 1, Arrays.copyOfRange(details(), 0, 15)), true);
                Field entriesField = HistoryBoundary.class.getDeclaredField("entries"); entriesField.setAccessible(true);
                Object entry = ((java.util.List<?>) entriesField.get(boundary)).get(0);
                Field idField = entry.getClass().getDeclaredField("id"); idField.setAccessible(true);
                byte[] id = (byte[]) idField.get(entry);
                Field snapshotField = HistoryBoundary.class.getDeclaredField("past"); snapshotField.setAccessible(true);
                HistorySelection snapshot = (HistorySelection) snapshotField.get(boundary);
                Field idsField = HistorySelection.class.getDeclaredField("ids"); idsField.setAccessible(true);
                byte[] ids = (byte[]) idsField.get(snapshot);
                Field fileField = HistoryBoundary.class.getDeclaredField("file"); fileField.setAccessible(true);
                HistoryFile file = (HistoryFile) fileField.get(boundary);
                Field bufferField = HistoryFile.class.getDeclaredField("buffer"); bufferField.setAccessible(true);
                byte[] buffer = (byte[]) bufferField.get(file);
                try { boundary.close(); yes(!fail); }
                catch (IllegalStateException expected) { yes(fail); }
                yes(Arrays.equals(id, new byte[id.length]) && Arrays.equals(ids, new byte[ids.length]));
                yes(Arrays.equals(buffer, new byte[buffer.length]));
            }
        });
        test("Passive quarantine retains no private IDs and wipes partial payload on observer failure", () -> {
            for (boolean fail : new boolean[]{false, true}) {
                Session.HistoryObserver observer = new Session.HistoryObserver() {
                    @Override public void onStage(String stage) {}
                    @Override public void onSelection(int count) { throw new AssertionError("passive selection"); }
                    @Override public void onFile(Map<String, Object> summary) {
                        yes(summary.get("received_id_in_today_index") == null && summary.get("received_id_in_past_index") == null);
                        if (fail) throw new IllegalStateException("synthetic observer");
                    }
                };
                HistoryBoundary boundary = new HistoryBoundary(observer, false);
                boundary.begin(0); boundary.accept(chunk(1, 1, details()), null);
                Field entriesField = HistoryBoundary.class.getDeclaredField("entries"); entriesField.setAccessible(true);
                Object entry = ((java.util.List<?>) entriesField.get(boundary)).get(0);
                Field idField = entry.getClass().getDeclaredField("id"); idField.setAccessible(true);
                yes(idField.get(entry) == null);
                boundary.accept(chunk(2, 1, Arrays.copyOfRange(details(), 0, 15)), null);
                Field fileField = HistoryBoundary.class.getDeclaredField("file"); fileField.setAccessible(true);
                HistoryFile file = (HistoryFile) fileField.get(boundary);
                Field bufferField = HistoryFile.class.getDeclaredField("buffer"); bufferField.setAccessible(true);
                byte[] buffer = (byte[]) bufferField.get(file);
                try { boundary.close(); yes(!fail); }
                catch (IllegalStateException expected) { yes(fail); }
                yes(Arrays.equals(buffer, new byte[buffer.length]) && ((java.util.List<?>) entriesField.get(boundary)).isEmpty());
            }
        });
        test("Closed file cannot compare wiped IDs or digest wiped payload as verified evidence", () -> {
            HistoryFile file = new HistoryFile(DETAILS_ID, 1024);
            file.accept(chunk(1, 1, details())); file.verifyAndSummarize();
            yes(file.receivedIdMatches(DETAILS_ID, 0));
            file.close();
            rejectsClosed(() -> file.receivedIdMatches(new byte[7], 0));
            rejectsClosed(file::verifiedDigest);
            rejectsClosed(file::verifyHeaderOnly); rejectsClosed(file::verifyAndSummarize);
            rejectsClosed(file::summary);
            rejectsClosed(() -> file.accept(chunk(1, 1, details())));
            file.close();
        });
        test("Closed index cannot report membership or retention equality from wiped snapshots", () -> {
            try (Proto first = index(DETAILS_ID); Proto second = index(DETAILS_ID)) {
                HistorySelection a = new HistorySelection(first, false), b = new HistorySelection(second, false);
                yes(a.containsId(DETAILS_ID) && a.unchanged(b));
                a.close();
                rejectsClosed(() -> a.containsId(new byte[7]));
                rejectsClosed(() -> a.unchanged(b)); rejectsClosed(() -> b.unchanged(a));
                rejectsClosed(a::count); rejectsClosed(a::indexedCount); rejectsClosed(() -> a.id(0));
                rejectsClosed(a::candidateCount); rejectsClosed(a::detailCandidateCount); rejectsClosed(a::omittedCount);
                try (HistoryFile file = HistoryFile.quarantine(1024)) {
                    file.accept(chunk(1, 1, details())); file.verifyHeaderOnly();
                    rejectsClosed(() -> a.containsReceivedId(file));
                    file.close(); rejectsClosed(() -> b.containsReceivedId(file));
                }
                b.close(); rejectsClosed(() -> a.unchanged(b)); a.close();
            }
            try (Proto response = index(new byte[0]); HistorySelection empty = new HistorySelection(response, false);
                 HistoryFile file = HistoryFile.quarantine(1024)) {
                file.accept(chunk(1, 1, details())); file.verifyHeaderOnly();
                yes(empty.indexedCount() == 0 && !empty.containsReceivedId(file));
                file.close(); rejectsClosed(() -> empty.containsReceivedId(file));
                empty.close(); rejectsClosed(empty::indexedCount); rejectsClosed(() -> empty.unchanged(empty));
            }
        });
        test("File ID mismatch metadata exposes only equality flags not original IDs", () -> {
            byte[] next = DETAILS_ID.clone(); next[0]++;
            try (HistoryFile file = new HistoryFile(next, 1024)) {
                file.accept(chunk(1, 1, details())); rejects(file::verifyAndSummarize);
                Map<String, Object> safe = file.summary();
                yes(safe.get("file_id_timestamp_match").equals(false));
                yes(safe.get("file_id_timezone_match").equals(true));
                yes(safe.get("file_id_version_match").equals(true));
                yes(safe.get("file_id_kind_match").equals(true));
                yes(safe.get("parser_status").equals("not_parsed"));
            }
        });
        test("Header offset diagnosis never accepts a shifted file ID or parses unverified data", () -> {
            byte[] raw = details(), shifted = new byte[raw.length + 1];
            System.arraycopy(raw, 0, shifted, 1, raw.length); crc(shifted);
            try (HistoryFile file = new HistoryFile(DETAILS_ID, 1024)) {
                file.accept(chunk(1, 1, shifted)); rejects(file::verifyAndSummarize);
                Map<String, Object> safe = file.summary();
                yes(safe.get("expected_id_header_offset").equals(1));
                yes(safe.get("file_id_match").equals(false));
                yes(safe.get("parser_status").equals("not_parsed"));
            }
            Map<String, Object> safe = verified(DETAILS_ID, details());
            yes(safe.get("expected_id_header_offset").equals(0));
            yes(safe.get("received_file_category").equals("daily_details_v4"));
        });
        test("CRC32 ISO-HDLC standard vector and little-endian file checksum", () -> {
            CRC32 crc = new CRC32(); crc.update("123456789".getBytes(StandardCharsets.US_ASCII));
            yes(crc.getValue() == 0xcbf43926L);
            Map<String, Object> summary = verified(SUMMARY_ID, summary());
            yes(summary.get("crc_valid").equals(true) && summary.get("file_id_match").equals(true));
        });
        test("History CRC corruption truncated CRC and short files abort without parser", () -> {
            byte[] bad = details(); bad[14] ^= 1;
            for (byte[] bytes : new byte[][]{bad, Arrays.copyOf(details(), details().length - 1), new byte[12]}) {
                try (HistoryFile file = new HistoryFile(DETAILS_ID, 1024)) {
                    file.accept(chunk(1, 1, bytes)); rejects(file::verifyAndSummarize);
                    yes(file.summary().get("parser_status").equals("not_parsed"));
                }
            }
        });
        test("History exact requested ID and zero padding mandatory after valid CRC", () -> {
            byte[] wrongId = details(); wrongId[0] ^= 1; crc(wrongId);
            byte[] wrongPad = details(); wrongPad[7] = 1; crc(wrongPad);
            rejects(() -> verified(DETAILS_ID, wrongId)); rejects(() -> verified(DETAILS_ID, wrongPad));
        });
        test("History chunk numbering starts1 stable total no duplicates gaps or resets", () -> {
            for (int scenario = 0; scenario < 7; scenario++) {
                try (HistoryFile file = new HistoryFile(DETAILS_ID, 1024)) {
                    final int selected = scenario;
                    rejects(() -> {
                        if (selected == 0) file.accept(chunk(2, 0, new byte[]{1}));
                        if (selected == 1) file.accept(chunk(2, 2, new byte[]{1}));
                        if (selected == 2) file.accept(chunk(0, 1, new byte[]{1}));
                        if (selected == 3) file.accept(chunk(2049, 1, new byte[]{1}));
                        if (selected >= 4) {
                            file.accept(chunk(2, 1, new byte[]{1}));
                            file.accept(chunk(selected == 4 ? 3 : 2, selected == 5 ? 3 : selected == 6 ? 1 : 2, new byte[]{1}));
                        }
                    });
                }
            }
            try (HistoryFile file = new HistoryFile(DETAILS_ID, 1024)) {
                file.accept(chunk(1, 1, details()));
                rejects(() -> file.accept(chunk(1, 1, details())));
            }
        });
        test("History byte budget boundary short chunks and incomplete file rejection", () -> {
            rejects(() -> new HistoryFile(DETAILS_ID, 12));
            rejects(() -> new HistoryFile(DETAILS_ID, HistoryFile.MAX_BYTES + 1));
            try (HistoryFile file = new HistoryFile(DETAILS_ID, 13)) {
                rejects(() -> file.accept(new byte[4]));
                rejects(() -> file.accept(chunk(1, 1, new byte[14])));
                file.accept(chunk(2, 1, new byte[13])); rejects(file::verifyAndSummarize);
            }
        });
        test("Daily details v4 count records HR field presence steps and60s only", () -> {
            Map<String, Object> summary = verified(DETAILS_ID, details());
            yes(summary.get("detail_records").equals(2)); yes(summary.get("hr_present_records").equals(2));
            yes(summary.get("hr_missing_records").equals(0)); yes(summary.get("step_records").equals(2));
            yes(summary.get("time_granularity_seconds").equals(60));
            yes(!summary.containsKey("heart_rate") && !summary.containsKey("timestamp"));
        });
        test("Daily v4 unused final nibble does not create a twelfth field or reject valid records", () -> {
            for (int reserved = 0; reserved < 16; reserved++) {
                byte[] raw = details(); raw[13] = (byte) reserved; crc(raw);
                Map<String, Object> safe = verified(DETAILS_ID, raw);
                yes(safe.get("parser_status").equals("daily_details_v4"));
                yes(safe.get("detail_records").equals(2) && safe.get("hr_present_records").equals(2));
                yes(safe.get("reserved_header_nibble_ignored").equals(reserved != 0));
            }
            byte[] raw = details(), extra = new byte[raw.length + 1];
            System.arraycopy(raw, 0, extra, 0, raw.length - 4); extra[13] = 15; crc(extra);
            Map<String, Object> safe = verified(DETAILS_ID, extra);
            yes(safe.get("parser_status").equals("unsupported_truncated_records"));
            yes(!safe.containsKey("detail_records"));
        });
        test("Missing HR validity is counted not converted into measured zero", () -> {
            byte[] body = {(byte) 0x90, 0, (byte) 0x80, 0, 0, 0, 1, 0, 88};
            Map<String, Object> summary = verified(DETAILS_ID, file(DETAILS_ID, body));
            yes(summary.get("hr_present_records").equals(0) && summary.get("hr_missing_records").equals(1));
            yes(summary.get("step_records").equals(1));
        });
        test("Details unsupported truncated spare nibble zero width and record bound", () -> {
            byte[] truncated = file(DETAILS_ID, new byte[]{(byte) 0x90, 0, 0, 0, 0, 0, 1});
            byte[] spare = file(DETAILS_ID, new byte[]{0, 0, 0, 0, 0, 1, 1});
            byte[] noWidth = file(DETAILS_ID, new byte[]{0, 0, 0, 0, 0, 0, 1});
            for (byte[] bytes : new byte[][]{truncated, spare, noWidth, manyRecords(2881)}) {
                Map<String, Object> summary = verified(DETAILS_ID, bytes);
                yes(summary.get("parser_status").toString().startsWith("unsupported"));
                yes(!summary.containsKey("detail_records"));
            }
        });
        test("Daily summary v5 exact fixed layout MSB validity and unsigned steps", () -> {
            Map<String, Object> safe = verified(SUMMARY_ID, summary());
            yes(safe.get("today_steps").equals(12345L) && safe.get("steps_present").equals(true));
            byte[] unsigned = summary(); Arrays.fill(unsigned, 12, 16, (byte) 0xff); crc(unsigned);
            yes(verified(SUMMARY_ID, unsigned).get("today_steps").equals(4294967295L));
            byte[] missing = summary(); missing[8] = 1; crc(missing);
            safe = verified(SUMMARY_ID, missing); yes(safe.get("steps_present").equals(false) && safe.get("today_steps") == null);
        });
        test("Unsupported summary length and unknown version never decode or invent fields", () -> {
            Map<String, Object> safe = verified(SUMMARY_ID, file(SUMMARY_ID, new byte[60]));
            yes(safe.get("parser_status").equals("unsupported_summary_length") && !safe.containsKey("today_steps"));
            yes(DailyFileSummary.parse(summary(), summary().length, 6, 1).get("parser_status").equals("unsupported_version"));
        });
        test("Index selection permits bounded daily segments and deduplicates exact IDs", () -> {
            byte[] unknown = {9, 8, 7, 6, 0, 9, 0};
            try (Proto response = index(Crypto.join(unknown, Crypto.join(DETAILS_ID, SUMMARY_ID))); HistorySelection selection = new HistorySelection(response, true)) {
                yes(selection.count() == 2); yes(Arrays.equals(selection.id(0), SUMMARY_ID));
            }
            try (Proto response = index(unknown); HistorySelection selection = new HistorySelection(response, true)) { yes(selection.count() == 0); }
            try (Proto response = index(Crypto.join(DETAILS_ID, DETAILS_ID)); HistorySelection selection = new HistorySelection(response, true)) {
                yes(selection.count() == 1);
            }
            byte[] next = DETAILS_ID.clone(); next[0]++;
            try (Proto response = index(Crypto.join(DETAILS_ID, Crypto.join(next, SUMMARY_ID))); HistorySelection selection = new HistorySelection(response, true)) {
                yes(selection.count() == 3);
            }
            for (byte[] ids : new byte[][]{new byte[8], new byte[1799]})
                try (Proto response = index(ids)) { rejects(() -> new HistorySelection(response, true)); }
        });
        test("Daily segment count stays bounded and multiple distinct summaries are rejected", () -> {
            byte[] ids = new byte[(HistorySelection.MAX_FILES + 1) * 7];
            for (int i = 0; i <= HistorySelection.MAX_FILES; i++) {
                System.arraycopy(DETAILS_ID, 0, ids, i * 7, 7); ids[i * 7] = (byte) i;
            }
            try (Proto response = index(Arrays.copyOf(ids, HistorySelection.MAX_FILES * 7)); HistorySelection selection = new HistorySelection(response, true)) {
                yes(selection.count() == HistorySelection.MAX_FILES);
            }
            try (Proto response = index(ids)) { rejects(() -> new HistorySelection(response, true)); }
            byte[] next = SUMMARY_ID.clone(); next[0]++;
            try (Proto response = index(Crypto.join(SUMMARY_ID, next))) { rejects(() -> new HistorySelection(response, true)); }
        });
        test("Explicit sample keeps one summary and one first detail and reports omissions", () -> {
            byte[] ids = new byte[12 * 7];
            for (int i = 0; i < 11; i++) {
                System.arraycopy(DETAILS_ID, 0, ids, i * 7, 7); ids[i * 7] = (byte) i;
            }
            System.arraycopy(SUMMARY_ID, 0, ids, 11 * 7, 7);
            try (Proto response = index(ids); HistorySelection sample = new HistorySelection(response, true, true);
                 HistorySelection all = new HistorySelection(response, false)) {
                yes(sample.count() == 2 && sample.candidateCount() == 12 && sample.omittedCount() == 10);
                yes(Arrays.equals(sample.id(0), SUMMARY_ID)); yes(sample.id(1)[0] == 0);
                yes(sample.unchanged(all));
            }
            try (Proto response = index(Arrays.copyOf(ids, 11 * 7)); HistorySelection sample = new HistorySelection(response, true, true)) {
                yes(sample.count() == 1 && sample.omittedCount() == 10);
            }
            byte[] next = SUMMARY_ID.clone(); next[0]++;
            try (Proto response = index(Crypto.join(SUMMARY_ID, next))) {
                rejects(() -> new HistorySelection(response, true, true));
            }
        });
        test("Explicit next-details sample selects only the next unique details ID", () -> {
            byte[] ids = new byte[3 * 7];
            for (int i = 0; i < 3; i++) {
                System.arraycopy(DETAILS_ID, 0, ids, i * 7, 7); ids[i * 7] = (byte) i;
            }
            try (Proto response = index(ids); HistorySelection sample = new HistorySelection(response, true, true, 1)) {
                yes(sample.count() == 1 && sample.candidateCount() == 3 && sample.omittedCount() == 2);
                yes(sample.id(0)[0] == 1);
            }
        });
        test("Explicit batch deduplicates unique details excludes interleaved summary and unknown IDs", () -> {
            byte[] next = DETAILS_ID.clone(); next[0]++;
            byte[] last = DETAILS_ID.clone(); last[0] += 2;
            byte[] unknown = {9, 8, 7, 6, 0, 9, 0};
            byte[] ids = Crypto.join(Crypto.join(DETAILS_ID, SUMMARY_ID), Crypto.join(DETAILS_ID, unknown));
            ids = Crypto.join(ids, Crypto.join(next, last));
            try (Proto response = index(ids); HistorySelection sample = new HistorySelection(response, true, true, 1, 2)) {
                yes(sample.count() == 2 && sample.candidateCount() == 4 && sample.detailCandidateCount() == 3);
                yes(sample.omittedCount() == 2 && Arrays.equals(sample.id(0), next) && Arrays.equals(sample.id(1), last));
            }
            try (Proto response = index(ids); HistorySelection sample = new HistorySelection(response, true, true, 2, 2)) {
                yes(sample.count() == 1 && Arrays.equals(sample.id(0), last));
            }
            try (Proto a = index(Crypto.join(next, last)); Proto b = index(Crypto.join(last, next));
                 HistorySelection before = new HistorySelection(a, true, true, 0, 1);
                 HistorySelection after = new HistorySelection(b, true, true, 0, 1)) {
                yes(before.unchanged(after)); yes(!Arrays.equals(before.id(0), after.id(0)));
            }
        });
        test("Batch can select final unique detail in maximum sized index without raising caps", () -> {
            byte[] ids = new byte[HistoryIndex.MAX_IDS * 7];
            for (int i = 0; i < HistoryIndex.MAX_IDS; i++) {
                System.arraycopy(DETAILS_ID, 0, ids, i * 7, 7); ids[i * 7] = (byte) i;
            }
            try (Proto response = index(ids); HistorySelection sample = new HistorySelection(response, true, true, 255, 2)) {
                yes(sample.count() == 1 && sample.candidateCount() == 256 && sample.omittedCount() == 255);
                yes((sample.id(0)[0] & 255) == 255);
            }
        });
        test("Batch rejects invalid ranges empty or absent offset and ambiguous summary without download", () -> {
            for (int[] range : new int[][]{{-2, 1}, {-1, 2}, {256, 1}, {0, 0}, {0, 3}, {1, 1}, {255, 2}})
                try (Proto response = index(DETAILS_ID)) {
                    rejects(() -> new HistorySelection(response, true, true, range[0], range[1]));
                }
            for (byte[] ids : new byte[][]{new byte[0], SUMMARY_ID})
                try (Proto response = index(ids)) { rejects(() -> new HistorySelection(response, true, true, 0, 2)); }
            try (Proto response = index(DETAILS_ID)) { rejects(() -> new HistorySelection(response, true, false, 0, 1)); }
            byte[] next = SUMMARY_ID.clone(); next[0]++;
            try (Proto response = index(Crypto.join(DETAILS_ID, Crypto.join(SUMMARY_ID, next)))) {
                rejects(() -> new HistorySelection(response, true, true, 0, 2));
            }
        });
        test("Past snapshot validates subtype bounds presence and status and never selects files", () -> {
            try (Proto response = index(Crypto.join(DETAILS_ID, DETAILS_ID), 2);
                 HistorySelection snapshot = HistorySelection.pastSnapshot(response)) {
                yes(snapshot.indexedCount() == 2 && snapshot.count() == 0);
                rejects(() -> new HistorySelection(response, true));
                Field ids = HistorySelection.class.getDeclaredField("ids"); ids.setAccessible(true);
                byte[] retained = (byte[]) ids.get(snapshot); snapshot.close();
                yes(Arrays.equals(retained, new byte[14]));
            }
            try (Proto response = index(new byte[0], 2); HistorySelection snapshot = HistorySelection.pastSnapshot(response)) {
                yes(snapshot.indexedCount() == 0);
            }
            for (byte[] ids : new byte[][]{new byte[8], new byte[1799]})
                try (Proto response = index(ids, 2)) { rejects(() -> HistorySelection.pastSnapshot(response)); }
            try (Proto response = index(DETAILS_ID)) { rejects(() -> HistorySelection.pastSnapshot(response)); }
            try (Proto response = Proto.parse(Proto.command(8, 2))) { rejects(() -> HistorySelection.pastSnapshot(response)); }
            try (Proto response = Proto.parse(new Proto.Writer().number(1, 8).number(2, 2).number(100, 1).build())) {
                rejects(() -> HistorySelection.pastSnapshot(response));
            }
        });
        test("Past details selection has same strict dedup range and no summary fetch", () -> {
            byte[] next = DETAILS_ID.clone(); next[0]++;
            try (Proto response = index(Crypto.join(DETAILS_ID, Crypto.join(SUMMARY_ID, next)), 2);
                 HistorySelection selection = HistorySelection.pastDetails(response, 0, 2)) {
                yes(selection.count() == 2 && selection.omittedCount() == 1 && selection.detailCandidateCount() == 2);
                yes(Arrays.equals(selection.id(0), DETAILS_ID) && Arrays.equals(selection.id(1), next));
                rejects(() -> HistorySelection.pastDetails(response, -1, 1));
                rejects(() -> HistorySelection.pastDetails(response, 2, 1));
            }
        });
        test("Past membership requires verified CRC but never accepts mismatched requested ID", () -> {
            byte[] other = DETAILS_ID.clone(); other[0]++;
            try (Proto response = index(DETAILS_ID, 2); HistorySelection snapshot = HistorySelection.pastSnapshot(response);
                 HistoryFile file = new HistoryFile(other, 1024)) {
                file.accept(chunk(1, 1, details()));
                yes(!snapshot.containsReceivedId(file));
                rejects(file::verifyAndSummarize);
                yes(snapshot.containsReceivedId(file));
                yes(file.summary().get("file_id_match").equals(false));
                yes(file.summary().get("parser_status").equals("not_parsed"));
            }
            byte[] corrupt = details(); corrupt[corrupt.length - 1] ^= 1;
            try (Proto response = index(DETAILS_ID, 2); HistorySelection snapshot = HistorySelection.pastSnapshot(response);
                 HistoryFile file = new HistoryFile(DETAILS_ID, 1024)) {
                file.accept(chunk(1, 1, corrupt)); rejects(file::verifyAndSummarize);
                yes(!snapshot.containsReceivedId(file));
            }
        });
        test("Retention compares full ID multisets disregards order detects loss or change", () -> {
            try (Proto a = index(Crypto.join(DETAILS_ID, SUMMARY_ID)); Proto b = index(Crypto.join(SUMMARY_ID, DETAILS_ID));
                 Proto c = index(DETAILS_ID); HistorySelection before = new HistorySelection(a, true);
                 HistorySelection same = new HistorySelection(b, false); HistorySelection lost = new HistorySelection(c, false)) {
                yes(before.unchanged(same)); yes(!before.unchanged(lost));
            }
        });
        test("History close wipes all retained file ID raw bytes and index snapshots", () -> {
            HistoryFile file = new HistoryFile(DETAILS_ID, 128); file.accept(chunk(1, 1, details()));
            Field buffer = HistoryFile.class.getDeclaredField("buffer"); buffer.setAccessible(true);
            byte[] retained = (byte[]) buffer.get(file); file.close(); yes(Arrays.equals(retained, new byte[128]));
            try (Proto response = index(DETAILS_ID)) {
                HistorySelection selection = new HistorySelection(response, true); byte[] selected = selection.id(0);
                Field ids = HistorySelection.class.getDeclaredField("ids"); ids.setAccessible(true);
                byte[] snapshot = (byte[]) ids.get(selection); selection.close();
                yes(Arrays.equals(selected, new byte[7]) && Arrays.equals(snapshot, new byte[7]));
            }
        });
        test("Bulk SPP v2 bounded64512 opt-in leaves ordinary4096 cap intact", () -> {
            byte[] frame = largeFrame(7, new byte[64512]);
            rejects(() -> Wire.readV2(new ByteArrayInputStream(frame)));
            try (Wire.Frame parsed = Wire.readV2(new ByteArrayInputStream(frame), 64512)) { yes(parsed.payload.length == 64512); }
            rejects(() -> Wire.readV2(new ByteArrayInputStream(largeFrame(7, new byte[64513])), 64512));
            rejects(() -> Wire.readV2(new ByteArrayInputStream(frame), 65535));
        });
        return count;
    }
}
