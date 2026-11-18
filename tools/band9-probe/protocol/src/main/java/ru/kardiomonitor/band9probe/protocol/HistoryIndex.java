package ru.kardiomonitor.band9probe.protocol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Index counts only. Timestamp, timezone and complete identifiers are never materialized. */
public final class HistoryIndex {
    public static final int MAX_IDS = 256;
    public final Map<String, Object> values;
    private HistoryIndex(Map<String, Object> values) { this.values = Collections.unmodifiableMap(values); }

    public static byte[] todayRequest() {
        byte[] options = new Proto.Writer().number(1, 0).build();
        byte[] health = new Proto.Writer().bytes(5, options).build();
        return new Proto.Writer().number(1, 8).number(2, 1).bytes(10, health).build();
    }
    public static byte[] pastRequest() { return Proto.command(8, 2); }
    public static HistoryIndex parse(Proto command, int expectedSubtype) throws ProbeException {
        if ((expectedSubtype != 1 && expectedSubtype != 2) || command.number(1) != 8
                || command.number(2) != expectedSubtype) throw new ProbeException("history_index_response_type");
        command.requireSuccess();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query_type", 8); result.put("query_subtype", expectedSubtype);
        result.put("health_present", command.has(10)); result.put("file_ids_present", false);
        result.put("status", command.has(10) ? "file_ids_absent" : "health_absent");
        result.put("file_id_count", null); result.put("groups", Collections.emptyList());
        if (command.has(10)) try (Proto health = command.message(10)) {
            if (health.has(2)) {
                byte[] ids = health.bytes(2);
                List<Map<String, Object>> groups = summarizeAndWipe(ids);
                result.put("file_ids_present", true); result.put("status", "present");
                result.put("file_id_count", ids.length / 7); result.put("groups", groups);
            }
        }
        return new HistoryIndex(result);
    }
    /** Always wipes the supplied ID bytes, including rejected inputs. */
    public static List<Map<String, Object>> summarizeAndWipe(byte[] ids) throws ProbeException {
        try {
            if (ids.length > MAX_IDS * 7) throw new ProbeException("history_index_too_large");
            if (ids.length % 7 != 0) throw new ProbeException("history_index_id_length");
            Map<Integer, Integer> counts = new TreeMap<>();
            for (int pos = 0; pos < ids.length; pos += 7) {
                int version = ids[pos + 5] & 255, flags = ids[pos + 6] & 255;
                int group = (flags << 8) | version;
                counts.put(group, counts.getOrDefault(group, 0) + 1);
            }
            List<Map<String, Object>> groups = new ArrayList<>();
            for (Map.Entry<Integer, Integer> entry : counts.entrySet()) {
                int key = entry.getKey(), flags = key >>> 8;
                Map<String, Object> group = new LinkedHashMap<>();
                group.put("type", (flags >>> 7) & 1); group.put("subtype", (flags >>> 2) & 31);
                group.put("detail", flags & 3); group.put("version", key & 255); group.put("count", entry.getValue());
                groups.add(Collections.unmodifiableMap(group));
            }
            return Collections.unmodifiableList(groups);
        } finally { Auth.wipe(ids); }
    }
}
