package ru.kardiomonitor.band9probe.protocol;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Presence-aware projection of the documented 8/10 response; no unknown fields escape. */
public final class HeartRateSettings {
    public final Map<String, Object> values;
    private HeartRateSettings(Map<String, Object> values) { this.values = Collections.unmodifiableMap(values); }

    public static HeartRateSettings parse(Proto command) throws ProbeException {
        if (command.number(1) != 8 || command.number(2) != 10) throw new ProbeException("hr_settings_response_type");
        command.requireSuccess();
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> presence = new LinkedHashMap<>();
        result.put("query_type", 8); result.put("query_subtype", 10);
        result.put("command_status_present", command.has(100));
        result.put("command_status", command.has(100) ? command.number(100) : null);
        result.put("health_present", command.has(10));
        result.put("heart_rate_present", false);
        result.put("status", command.has(10) ? "heart_rate_absent" : "health_absent");
        result.put("available_intervals", null);
        result.put("available_intervals_status", "not_in_public_response_schema");
        try (Proto health = command.has(10) ? command.message(10) : Proto.parse(new byte[0]);
             Proto hr = health.has(8) ? health.message(8) : Proto.parse(new byte[0])) {
            result.put("heart_rate_present", health.has(8));
            if (health.has(8)) result.put("status", "present");
            bool(hr, 1, "disabled", result, presence);
            uint(hr, 2, "interval_value", result, presence);
            bool(hr, 3, "alarm_high_enabled", result, presence);
            uint(hr, 4, "alarm_high_threshold_bpm", result, presence);
            presence.put("advanced_monitoring", hr.has(5));
            try (Proto advanced = hr.has(5) ? hr.message(5) : Proto.parse(new byte[0])) {
                bool(advanced, 1, "advanced_monitoring_enabled", result, presence);
            }
            presence.put("alarm_low", hr.has(8));
            try (Proto low = hr.has(8) ? hr.message(8) : Proto.parse(new byte[0])) {
                bool(low, 1, "alarm_low_enabled", result, presence);
                uint(low, 2, "alarm_low_threshold_bpm", result, presence);
            }
            toggle(hr, 9, "breathing_score", result, presence);
            toggle(hr, 10, "irregular_rhythm_alert", result, presence);
            toggle(hr, 11, "sleep_respiratory_rate", result, presence);
        }
        Object interval = result.get("interval_value"), disabled = result.get("disabled");
        boolean smart = Long.valueOf(0).equals(interval);
        result.put("interval_unit", interval == null ? null : smart ? "smart_sentinel" : "minutes");
        result.put("interval_minutes", interval == null || smart ? null : interval);
        String mode = "undetermined";
        if (Boolean.TRUE.equals(disabled)) mode = "disabled";
        else if (Boolean.FALSE.equals(disabled) && interval != null) mode = smart ? "smart" : "periodic";
        result.put("monitoring_mode", mode);
        result.put("presence", Collections.unmodifiableMap(presence));
        return new HeartRateSettings(result);
    }
    private static void bool(Proto message, int field, String name, Map<String, Object> values,
                             Map<String, Object> presence) throws ProbeException {
        presence.put(name, message.has(field));
        if (!message.has(field)) { values.put(name, null); return; }
        long value = message.number(field);
        if (value != 0 && value != 1) throw new ProbeException("hr_settings_boolean_invalid");
        values.put(name, value == 1);
    }
    private static void uint(Proto message, int field, String name, Map<String, Object> values,
                             Map<String, Object> presence) throws ProbeException {
        presence.put(name, message.has(field));
        Long value = message.has(field) ? message.number(field) : null;
        if (value != null && (value < 0 || value > 0xffffffffL)) throw new ProbeException("hr_settings_uint32_invalid");
        values.put(name, value);
    }
    private static void toggle(Proto message, int field, String name, Map<String, Object> values,
                               Map<String, Object> presence) throws ProbeException {
        presence.put(name, message.has(field));
        if (!message.has(field)) { values.put(name, null); return; }
        long value = message.number(field);
        values.put(name, value == 0 ? "unsupported" : value == 1 ? "on" : value == 2 ? "off" : "unrecognized");
    }
}
