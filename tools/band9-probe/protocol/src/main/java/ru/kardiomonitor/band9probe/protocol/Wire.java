package ru.kardiomonitor.band9probe.protocol;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/** Strict finite frame codecs. Malformed data is not resynchronized or guessed. */
public final class Wire {
    public static final int MAX_PAYLOAD = 4096;
    private Wire() {}

    public static final class Frame implements AutoCloseable {
        public final int channel, flags, opcode, sequence, dataType, type;
        public final byte[] payload;
        Frame(int channel, int flags, int opcode, int sequence, int dataType, int type, byte[] payload) {
            this.channel = channel; this.flags = flags; this.opcode = opcode; this.sequence = sequence;
            this.dataType = dataType; this.type = type; this.payload = payload;
        }
        @Override public void close() { Arrays.fill(payload, (byte) 0); }
    }
    public static byte[] versionRequest() { return v1(0, 0xc0, 0, 0, 0, new byte[0]); }
    public static byte[] v1(int channel, int flags, int opcode, int seq, int dataType, byte[] payload) {
        if (payload.length > MAX_PAYLOAD) throw new IllegalArgumentException("frame_length");
        byte[] result = new byte[11 + payload.length];
        result[0] = (byte) 0xba; result[1] = (byte) 0xdc; result[2] = (byte) 0xfe;
        result[3] = (byte) channel; result[4] = (byte) flags;
        put16(result, 5, payload.length + 3);
        result[7] = (byte) opcode; result[8] = (byte) seq; result[9] = (byte) dataType;
        System.arraycopy(payload, 0, result, 10, payload.length);
        result[result.length - 1] = (byte) 0xef;
        return result;
    }
    public static Frame readV1(InputStream input) throws IOException {
        byte[] h = exact(input, 10);
        if (u(h[0]) != 0xba || u(h[1]) != 0xdc || u(h[2]) != 0xfe) throw new ProbeException("v1_magic");
        if ((h[3] & 0xf0) != 0 || (h[4] & 0x3f) != 0) throw new ProbeException("v1_reserved_bits");
        int length = le16(h, 5) - 3;
        if (length < 0 || length > MAX_PAYLOAD) throw new ProbeException("v1_length");
        byte[] payload = exact(input, length);
        if (input.read() != 0xef) {
            Arrays.fill(payload, (byte) 0);
            throw new ProbeException("v1_epilogue");
        }
        return new Frame(u(h[3]), u(h[4]), u(h[7]), u(h[8]), u(h[9]), -1, payload);
    }
    /** Negotiation-only snapshot: never retains payloads from protobuf/auth channels. */
    public static final class VersionMetadata {
        public final int channel, opcode, dataType, payloadLength;
        private final int[] prefix;
        private VersionMetadata(Frame frame) {
            channel = frame.channel; opcode = frame.opcode; dataType = frame.dataType;
            payloadLength = frame.payload.length;
            int length = channel == 0 && payloadLength <= 64 ? Math.min(3, payloadLength) : 0;
            prefix = new int[length];
            for (int i = 0; i < length; i++) prefix[i] = u(frame.payload[i]);
        }
        public int[] versionPrefix() { return prefix.clone(); }
    }
    static VersionMetadata versionMetadata(Frame frame) { return new VersionMetadata(frame); }

    public static int parseVersion(Frame frame) throws ProbeException {
        // Upstream routes channel 0 to the version handler without opcode/dataType filtering.
        // Before authentication its payload is passed through unchanged, even for dataType=1.
        if (frame.channel != 0 || frame.payload.length < 1 || frame.payload.length > 64)
            throw new ProbeException("version_response_invalid");
        int version = u(frame.payload[0]);
        if (version < 1) throw new ProbeException("version_zero");
        return version;
    }
    public static byte[] v2(int type, int seq, byte[] payload) {
        if (payload.length > MAX_PAYLOAD) throw new IllegalArgumentException("frame_length");
        byte[] result = new byte[8 + payload.length];
        result[0] = (byte) 0xa5; result[1] = (byte) 0xa5;
        result[2] = (byte) type; result[3] = (byte) seq;
        put16(result, 4, payload.length); put16(result, 6, crc16(payload));
        System.arraycopy(payload, 0, result, 8, payload.length);
        return result;
    }
    public static Frame readV2(InputStream input) throws IOException { return readV2(input, MAX_PAYLOAD); }
    public static Frame readV2(InputStream input, int maxPayload) throws IOException {
        if (maxPayload < 0 || maxPayload > 64512) throw new ProbeException("v2_payload_limit");
        byte[] h = exact(input, 8);
        if (u(h[0]) != 0xa5 || u(h[1]) != 0xa5) throw new ProbeException("v2_magic");
        int type = u(h[2]);
        if (type > 3) throw new ProbeException("v2_type");
        int length = le16(h, 4);
        if (length > maxPayload || ((type == 0 || type == 1) && length != 0)) throw new ProbeException("v2_length");
        byte[] payload = exact(input, length);
        if (crc16(payload) != le16(h, 6)) { Arrays.fill(payload, (byte) 0); throw new ProbeException("v2_crc"); }
        return new Frame(-1, 0, -1, u(h[3]), -1, type, payload);
    }
    public static byte[] sessionStart() {
        // Public SPP v2 TLVs: protocol 1.0.0, 64512-byte peer limit, window 32, timeout 10000 ms.
        byte[] result = new byte[22];
        result[0] = 1;
        result[1] = 1; put16(result, 2, 3); result[4] = 1;
        result[7] = 2; put16(result, 8, 2); put16(result, 10, 64512);
        result[12] = 3; put16(result, 13, 2); put16(result, 15, 32);
        result[17] = 4; put16(result, 18, 2); put16(result, 20, 10000);
        return v2(2, 0, result);
    }
    public static void validateSession(byte[] payload) throws ProbeException {
        if (payload.length < 1 || payload[0] != 2) throw new ProbeException("v2_session_opcode");
        int pos = 1;
        while (pos < payload.length) {
            if (payload.length - pos < 3) throw new ProbeException("v2_session_tlv");
            int key = u(payload[pos]); int size = le16(payload, pos + 1); pos += 3;
            if (size > payload.length - pos) throw new ProbeException("v2_session_tlv");
            // Версия конфигурации — сведения peer, а не требование равенства версии запроса.
            if (key == 1 && size != 3) throw new ProbeException("v2_session_version_length");
            if (key >= 2 && key <= 4 && size != 2) throw new ProbeException("v2_session_tlv");
            if (key == 2 && le16(payload, pos) < 256) throw new ProbeException("v2_peer_frame_limit");
            if (key == 3 && le16(payload, pos) < 1) throw new ProbeException("v2_peer_window");
            pos += size;
        }
    }
    public static int crc16(byte[] input) {
        int crc = 0;
        for (byte b : input) {
            crc ^= u(b);
            for (int i = 0; i < 8; i++) crc = (crc >>> 1) ^ ((crc & 1) == 0 ? 0 : 0xa001);
        }
        return crc;
    }
    public static int u(byte value) { return value & 255; }
    public static int le16(byte[] in, int offset) { return u(in[offset]) | (u(in[offset + 1]) << 8); }
    public static void put16(byte[] out, int offset, int value) { out[offset] = (byte) value; out[offset + 1] = (byte) (value >>> 8); }
    public static byte[] exact(InputStream input, int size) throws IOException {
        byte[] result = new byte[size];
        int pos = 0;
        try {
            while (pos < size) {
                int count = input.read(result, pos, size - pos);
                if (count < 0) throw new EOFException();
                if (count == 0) throw new ProbeException("zero_length_read");
                pos += count;
            }
            return result;
        } catch (IOException e) { Arrays.fill(result, (byte) 0); throw e; }
    }
}
