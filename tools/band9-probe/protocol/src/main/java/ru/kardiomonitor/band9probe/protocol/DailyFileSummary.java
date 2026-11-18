package ru.kardiomonitor.band9probe.protocol;

import java.util.LinkedHashMap;
import java.util.Map;

/** Aggregate projection only: no timestamps, per-minute values or activity/wear classifications. */
final class DailyFileSummary {
    private DailyFileSummary() {}
    static Map<String, Object> parse(byte[] file, int length, int version, int detail) {
        return parse(file, length, version, detail, -1L);
    }
    static Map<String, Object> parse(byte[] file, int length, int version, int detail, long referenceTimeMillis) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("parser_status", "unsupported_version");
        if (version == 5 && detail == 1) {
            // v5: four bitmap bytes followed by 32 fixed slots totalling 57 bytes.
            if (length != 8 + 4 + 57 + 4) { result.put("parser_status", "unsupported_summary_length"); return result; }
            boolean stepsPresent = (file[8] & 0x80) != 0;
            result.put("parser_status", "daily_summary_v5");
            result.put("summary_records", 1);
            result.put("steps_present", stepsPresent);
            result.put("today_steps", stepsPresent ? u32(file, 12) : null);
            return result;
        }
        if (version != 4 || detail != 0) return result;
        int end = length - 4;
        if (end < 14) { result.put("parser_status", "unsupported_details_header"); return result; }
        // v4 defines eleven groups in six bytes; the final nibble is unused by the layout.
        result.put("reserved_header_nibble_ignored", (file[13] & 15) != 0);
        int[] widths = {2, 1, 1, 2, 1, 1, 2, 1, 1, 2, 2};
        int pos = 14, records = 0, hrPresent = 0, steps = 0, hrZero = 0, hr255 = 0, stepZero = 0;
        while (pos < end) {
            int start = pos;
            boolean extra = false, hasHr = false, hasSteps = false;
            for (int group = 0; group < widths.length; group++) {
                int header = file[8 + group / 2] & 255;
                int nibble = group % 2 == 0 ? header >>> 4 : header & 15;
                if ((nibble & 8) != 0) {
                    int width = widths[group];
                    if (end - pos < width) { result.put("parser_status", "unsupported_truncated_records"); return result; }
                    if (group == 0) {
                        hasSteps = (nibble & 1) != 0;
                        int packed = Wire.le16(file, pos);
                        if (hasSteps && (packed & 0x3fff) == 0) stepZero++;
                        extra = (nibble & 2) != 0 && (packed & 0x4000) != 0;
                    }
                    if (group == 4) {
                        hasHr = (nibble & 4) != 0;
                        if (hasHr) {
                            int hr = file[pos] & 255;
                            if (hr == 0) hrZero++;
                            if (hr == 255) hr255++;
                        }
                    }
                    pos += width;
                }
                // The extra byte is between stress (group 8) and v4 light/momentum groups.
                if (group == 8 && extra) {
                    if (pos == end) { result.put("parser_status", "unsupported_truncated_records"); return result; }
                    pos++;
                }
            }
            if (pos == start || ++records > 2880) { result.put("parser_status", "unsupported_record_layout"); return result; }
            if (hasHr) hrPresent++;
            if (hasSteps) steps++;
        }
        result.put("parser_status", "daily_details_v4");
        result.put("detail_records", records);
        result.put("hr_present_records", hrPresent);
        result.put("hr_missing_records", records - hrPresent);
        result.put("hr_presence_basis", "documented_field_validity_only_not_measurement_validation");
        result.put("step_records", steps);
        result.put("time_granularity_seconds", 60);
        // Encoding categories only: neither zero nor 255 is certified as a measurement or missing-value sentinel.
        result.put("hr_encoded_zero_records", hrZero);
        result.put("hr_encoded_255_records", hr255);
        result.put("step_encoded_zero_records", stepZero);
        result.put("step_encoded_nonzero_records", steps - stepZero);
        result.put("value_interpretation_status", "source_encoding_only_not_measurement_validation");
        long start = u32(file, 0);
        long last = start + Math.max(0L, records - 1L) * 60L;
        result.put("descriptor_minute_aligned", start % 60L == 0);
        result.put("descriptor_timezone_in_civil_range", file[4] >= -48 && file[4] <= 56);
        result.put("descriptor_signed_unsigned_epoch_agree", start <= Integer.MAX_VALUE);
        result.put("candidate_time_basis", "unsigned_epoch_seconds_no_timezone_adjustment");
        result.put("time_mapping_status", "source_hypothesis_not_independently_verified");
        boolean referenceAvailable = referenceTimeMillis > 0;
        long reference = referenceAvailable ? referenceTimeMillis / 1000L : 0;
        result.put("reference_clock_available", referenceAvailable);
        result.put("reference_clock_verified", false);
        result.put("candidate_last_record_not_future", referenceAvailable && records > 0 ? last <= reference : null);
        result.put("candidate_epoch_age_band", !referenceAvailable ? "reference_unavailable"
                : reference < start ? "future" : reference - start <= 86400L ? "within_24h"
                : reference - start <= 7L * 86400L ? "within_7d"
                : reference - start <= 31L * 86400L ? "within_31d" : "older");
        return result;
    }
    static long u32(byte[] bytes, int offset) {
        return (bytes[offset] & 255L) | ((bytes[offset + 1] & 255L) << 8)
                | ((bytes[offset + 2] & 255L) << 16) | ((bytes[offset + 3] & 255L) << 24);
    }
}
