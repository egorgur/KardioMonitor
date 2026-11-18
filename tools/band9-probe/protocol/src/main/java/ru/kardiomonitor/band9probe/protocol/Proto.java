package ru.kardiomonitor.band9probe.protocol;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** Small bounded proto2 wire codec; no generated or third-party implementation. */
public final class Proto implements AutoCloseable {
    private final Map<Integer, Object> fields = new LinkedHashMap<>();
    private Proto() {}

    public static Proto parse(byte[] input) throws ProbeException {
        if (input.length > 4096) throw new ProbeException("protobuf_too_large");
        Proto result = new Proto();
        int[] pos = {0};
        try {
            while (pos[0] < input.length) {
                long tag = varint(input, pos);
                int field = (int) (tag >>> 3);
                int wire = (int) (tag & 7);
                if (field < 1 || field > 0x1fffffff) throw new ProbeException("protobuf_tag");
                Object value;
                if (wire == 0) {
                    value = varint(input, pos);
                } else if (wire == 2) {
                    long length = varint(input, pos);
                    if (length < 0 || length > input.length - pos[0]) throw new ProbeException("protobuf_length");
                    value = Arrays.copyOfRange(input, pos[0], pos[0] + (int) length);
                    pos[0] += (int) length;
                } else if (wire == 1 || wire == 5) {
                    int size = wire == 1 ? 8 : 4;
                    if (input.length - pos[0] < size) throw new ProbeException("protobuf_truncated");
                    pos[0] += size;
                    continue;
                } else {
                    throw new ProbeException("protobuf_wire_type");
                }
                if (result.fields.putIfAbsent(field, value) != null) {
                    if (value instanceof byte[]) Arrays.fill((byte[]) value, (byte) 0);
                    throw new ProbeException("protobuf_duplicate_field");
                }
                if (result.fields.size() > 64) throw new ProbeException("protobuf_field_limit");
            }
            return result;
        } catch (ProbeException e) {
            result.close();
            throw e;
        }
    }

    private static long varint(byte[] in, int[] pos) throws ProbeException {
        long value = 0;
        for (int i = 0; i < 10; i++) {
            if (pos[0] == in.length) throw new ProbeException("protobuf_truncated");
            int b = in[pos[0]++] & 255;
            if (i == 9 && (b & 254) != 0) throw new ProbeException("protobuf_overflow");
            value |= (long) (b & 127) << (7 * i);
            if ((b & 128) == 0) return value;
        }
        throw new ProbeException("protobuf_overflow");
    }

    public boolean has(int field) { return fields.containsKey(field); }
    public long number(int field) throws ProbeException {
        Object value = fields.get(field);
        if (!(value instanceof Long)) throw new ProbeException("protobuf_missing_number");
        return (Long) value;
    }
    public byte[] bytes(int field) throws ProbeException {
        Object value = fields.get(field);
        if (!(value instanceof byte[])) throw new ProbeException("protobuf_missing_bytes");
        return (byte[]) value;
    }
    public Proto message(int field) throws ProbeException { return parse(bytes(field)); }
    public String text(int field) throws ProbeException {
        byte[] value = bytes(field);
        if (value.length > 100) throw new ProbeException("device_text_length");
        String result = new String(value, StandardCharsets.UTF_8);
        if (!result.matches("[A-Za-z0-9 ._()/+-]{1,100}")) throw new ProbeException("device_text_invalid");
        return result;
    }
    public void requireSuccess() throws ProbeException {
        if (has(100) && number(100) != 0) throw new ProbeException("device_command_rejected");
    }
    @Override public void close() {
        for (Object value : fields.values()) if (value instanceof byte[]) Arrays.fill((byte[]) value, (byte) 0);
        fields.clear();
    }

    public static final class Writer implements AutoCloseable {
        private static final class Buffer extends ByteArrayOutputStream {
            void wipe() { Arrays.fill(buf, (byte) 0); reset(); }
        }
        private final Buffer out = new Buffer();
        @Override public void close() { out.wipe(); }
        private void varint(long value) {
            do {
                int b = (int) (value & 127);
                value >>>= 7;
                out.write(value == 0 ? b : b | 128);
            } while (value != 0);
        }
        public Writer number(int field, long value) {
            varint((long) field << 3); varint(value); return this;
        }
        public Writer bytes(int field, byte[] value) {
            varint(((long) field << 3) | 2); varint(value.length); out.write(value, 0, value.length); return this;
        }
        public Writer text(int field, String value) { return bytes(field, value.getBytes(StandardCharsets.UTF_8)); }
        public Writer float32(int field, float value) {
            varint(((long) field << 3) | 5);
            int bits = Float.floatToIntBits(value);
            for (int i = 0; i < 4; i++) out.write(bits >>> (8 * i));
            return this;
        }
        public byte[] build() { return out.toByteArray(); }
    }
    public static byte[] command(int type, int subtype) {
        return new Writer().number(1, type).number(2, subtype).build();
    }
}
