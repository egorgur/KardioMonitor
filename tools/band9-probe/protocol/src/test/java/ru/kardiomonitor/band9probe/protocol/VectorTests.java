package ru.kardiomonitor.band9probe.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

/** Dependency-free host tests. All keys and bytes below are synthetic/public vectors. */
public final class VectorTests {
    private static int passed;
    private interface Test { void run() throws Exception; }
    private static void test(String name, Test test) throws Exception {
        test.run(); passed++; System.out.println("PASS " + name);
    }
    private static byte[] hex(String text) {
        byte[] bytes = new byte[text.length() / 2];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) Integer.parseInt(text.substring(2 * i, 2 * i + 2), 16);
        return bytes;
    }
    private static void eq(byte[] a, byte[] b) { if (!Arrays.equals(a, b)) throw new AssertionError("byte vector mismatch"); }
    private static void yes(boolean value) { if (!value) throw new AssertionError("condition failed"); }
    private static void rejects(Test test) throws Exception {
        try { test.run(); } catch (IOException | GeneralSecurityException expected) { return; }
        throw new AssertionError("expected safe rejection");
    }
    private static byte[] ascii(String value) { return value.getBytes(StandardCharsets.US_ASCII); }
    public static void main(String[] args) throws Exception {
        test("SPP v1 canonical version request", () -> eq(Wire.versionRequest(), hex("badcfe00c00300000000ef")));
        test("SPP v1 fragmented version response", () -> {
            byte[] fixture = hex("badcfe00800600020000020000ef");
            InputStream chunks = new ByteArrayInputStream(fixture) {
                @Override public synchronized int read(byte[] b, int o, int n) { return super.read(b, o, Math.min(1, n)); }
            };
            try (Wire.Frame frame = Wire.readV1(chunks)) { yes(Wire.parseVersion(frame) == 2); }
        });
        test("SPP v1 coalesced frames preserve boundaries", () -> {
            byte[] packet = Wire.v1(0, 0x80, 2, 0, 0, new byte[]{1});
            InputStream stream = new ByteArrayInputStream(Crypto.join(packet, packet));
            try (Wire.Frame a = Wire.readV1(stream); Wire.Frame b = Wire.readV1(stream)) {
                yes(Wire.parseVersion(a) == 1 && Wire.parseVersion(b) == 1 && stream.available() == 0);
            }
        });
        test("SPP v1 malformed and truncated rejection", () -> {
            for (int i = 0; i < 11; i++) {
                final int length = i;
                rejects(() -> Wire.readV1(new ByteArrayInputStream(Arrays.copyOf(Wire.versionRequest(), length))));
            }
            rejects(() -> Wire.readV1(new ByteArrayInputStream(hex("badcfe00800200000000ef"))));
            rejects(() -> Wire.readV1(new ByteArrayInputStream(hex("badcfe0080ffff000000ef"))));
            rejects(() -> Wire.readV1(new ByteArrayInputStream(hex("badcfe0080030000000000"))));
            rejects(() -> Wire.readV1(new ByteArrayInputStream(hex("badcfe10800300000000ef"))));
        });
        test("Version channel follows upstream without opcode or datatype assumptions", () -> {
            for (int opcode : new int[]{0, 1, 2, 3, 255}) {
                for (int dataType : new int[]{0, 1, 2, 255}) {
                    byte[] reply = Wire.v1(0, 0x80, opcode, 0, dataType, new byte[]{2, 0, 0});
                    ByteArrayOutputStream output = new ByteArrayOutputStream();
                    try (Session session = new Session(new ByteArrayInputStream(reply), output)) {
                        yes(session.negotiate() == 2);
                        eq(output.toByteArray(), Wire.versionRequest());
                    }
                }
            }
        });
        test("Version diagnostic metadata is bounded and detached from packet memory", () -> {
            byte[] payload = new byte[]{2, 7, 8, 99};
            Wire.VersionMetadata metadata;
            try (Wire.Frame frame = Wire.readV1(new ByteArrayInputStream(Wire.v1(0, 0x80, 1, 0, 2, payload)))) {
                metadata = Wire.versionMetadata(frame);
                yes(metadata.channel == 0 && metadata.opcode == 1 && metadata.dataType == 2 && metadata.payloadLength == 4);
                yes(Arrays.equals(metadata.versionPrefix(), new int[]{2, 7, 8}));
            }
            yes(Arrays.equals(metadata.versionPrefix(), new int[]{2, 7, 8}));
            int[] copy = metadata.versionPrefix(); copy[0] = 255;
            yes(metadata.versionPrefix()[0] == 2);
        });
        test("Negotiation diagnostic never exposes protobuf/auth channel payload", () -> {
            for (int channel : new int[]{1, 2, 3, 5, 7}) {
                byte[] reply = Wire.v1(channel, 0x80, 2, 0, 2, new byte[]{11, 22, 33, 44});
                final Wire.VersionMetadata[] captured = {null};
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                try (Session session = new Session(new ByteArrayInputStream(reply), output)) {
                    rejects(() -> session.negotiate(metadata -> captured[0] = metadata));
                    yes(captured[0].channel == channel && captured[0].versionPrefix().length == 0);
                    eq(output.toByteArray(), Wire.versionRequest());
                }
            }
        });
        test("Malformed version payload exposes no oversized prefix and never falls back", () -> {
            for (int size : new int[]{0, 65}) {
                byte[] reply = Wire.v1(0, 0x80, 1, 0, 2, new byte[size]);
                final Wire.VersionMetadata[] captured = {null};
                try (Session session = new Session(new ByteArrayInputStream(reply), new ByteArrayOutputStream())) {
                    rejects(() -> session.negotiate(metadata -> captured[0] = metadata));
                    yes(captured[0].payloadLength == size && captured[0].versionPrefix().length == 0);
                }
            }
        });
        test("Version never falls back from invalid response", () -> {
            for (int channel : new int[]{1, 2, 3}) {
                try (Wire.Frame f = Wire.readV1(new ByteArrayInputStream(Wire.v1(channel, 0x80, 2, 0, 0, new byte[]{2})))) {
                    rejects(() -> Wire.parseVersion(f));
                }
            }
            try (Wire.Frame f = Wire.readV1(new ByteArrayInputStream(Wire.versionRequest()))) { rejects(() -> Wire.parseVersion(f)); }
        });
        test("CRC-16/ARC check vector", () -> yes(Wire.crc16(ascii("123456789")) == 0xbb3d));
        test("SPP v2 ACK canonical bytes", () -> eq(Wire.v2(1, 7, new byte[0]), hex("a5a5010700000000")));
        test("SPP v2 session canonical TLVs", () -> {
            try (Wire.Frame frame = Wire.readV2(new ByteArrayInputStream(Wire.sessionStart()))) {
                eq(frame.payload, hex("0101030001000002020000fc03020020000402001027"));
                byte[] response = frame.payload.clone(); response[0] = 2; Wire.validateSession(response);
                rejects(() -> Wire.validateSession(Arrays.copyOf(response, response.length - 1)));
                response[4] = 2; response[6] = 97; Wire.validateSession(response);
                response[2] = 2; rejects(() -> Wire.validateSession(response));
            }
        });
        test("SPP v2 CRC, bounds, ACK payload rejection", () -> {
            byte[] frame = Wire.v2(3, 0, new byte[]{1, 1, 8, 1}); frame[8] ^= 1;
            rejects(() -> Wire.readV2(new ByteArrayInputStream(frame)));
            rejects(() -> Wire.readV2(new ByteArrayInputStream(hex("a5a50300ffff0000"))));
            rejects(() -> Wire.readV2(new ByteArrayInputStream(Wire.v2(1, 0, new byte[]{0}))));
        });
        test("proto2 query and explicit zero/fixed32 encoding", () -> {
            eq(Proto.command(2, 1), hex("08021001"));
            eq(Proto.command(8, 45), hex("0808102d"));
            eq(Proto.command(8, 46), hex("0808102e"));
            eq(new Proto.Writer().number(1, 0).float32(2, 42).build(), hex("08001500002842"));
        });
        test("protobuf strict bounds duplicate overflow rejection", () -> {
            rejects(() -> Proto.parse(hex("0a05ff")));
            rejects(() -> Proto.parse(hex("08010802")));
            rejects(() -> Proto.parse(hex("08ffffffffffffffffffff02")));
            rejects(() -> Proto.parse(hex("00")));
            rejects(() -> Proto.parse(hex("0b")));
            try (Proto p = Proto.parse(hex("080210016000"))) { yes(p.number(1) == 2 && p.number(2) == 1); }
        });
        test("32-character private key contract", () -> {
            eq(Auth.decodeKeyHex(ascii("000102030405060708090a0b0c0d0e0f")), hex("000102030405060708090a0b0c0d0e0f"));
            rejects(() -> Auth.decodeKeyHex(ascii("000102030405060708090a0b0c0d0e0f\n")));
            rejects(() -> Auth.decodeKeyHex(ascii("000102030405060708090a0b0c0d0e0g")));
            rejects(() -> Auth.decodeKeyHex(new byte[16]));
        });
        test("RFC 5869 HKDF SHA-256 case 1", () -> {
            byte[] ikm = new byte[22]; Arrays.fill(ikm, (byte) 0x0b);
            eq(Crypto.hkdf(hex("000102030405060708090a0b0c"), ikm, hex("f0f1f2f3f4f5f6f7f8f9"), 42),
                    hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"));
        });
        test("NIST SP 800-38A AES-CTR F.5.1", () -> eq(
                Crypto.ctr(hex("2b7e151628aed2a6abf7158809cf4f3c"), hex("f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff"),
                        hex("6bc1bee22e409f96e93d7e117393172aae2d8a571e03ac9c9eb76fac45af8e51")),
                hex("874d6191b620e3261bef6864990db6ce9806f66b7970fdff8617187bb9fffdff")));
        test("RFC 3610 AES-CCM packet vector 1", () -> eq(
                Crypto.ccmEncrypt(hex("c0c1c2c3c4c5c6c7c8c9cacbcccdcecf"), hex("00000003020100a0a1a2a3a4a5"),
                        hex("0001020304050607"), hex("08090a0b0c0d0e0f101112131415161718191a1b1c1d1e"), 8),
                hex("588c979a61c663d2f066d0c2c0f989806d5f6b61dac38417e8d12cfdf926e0")));
        test("Independent Node OpenSSL Xiaomi HKDF fixture", () -> eq(
                Crypto.hkdf(hex("101112131415161718191a1b1c1d1e1f202122232425262728292a2b2c2d2e2f"),
                        hex("000102030405060708090a0b0c0d0e0f"), ascii("miwear-auth"), 64),
                hex("d738074e6570abb50d001db70f497a37923e295e02aecb7619a8e1b9f574c9888676d22523869a15ee4340ac1bab5e35044aed283e7cace9aad41acb59fe75eb")));
        test("Independent CCM nonce12/tag4 fixture and tamper rejection", () -> {
            byte[] key = hex("000102030405060708090a0b0c0d0e0f"), nonce = hex("000102030405060708090a0b");
            byte[] plain = hex("080015000028421a0550726f626520e0012a025255");
            byte[] encrypted = Crypto.ccmEncrypt(key, nonce, new byte[0], plain, 4);
            eq(encrypted, hex("3b15e667dca886ab7443bb8f608fa5606d7c1b609e50584a16"));
            eq(Crypto.ccmDecrypt(key, nonce, encrypted, 4), plain);
            encrypted[encrypted.length - 1] ^= 1;
            rejects(() -> Crypto.ccmDecrypt(key, nonce, encrypted, 4));
        });
        test("Auth nonce schema and HMAC failure sends no confirmation", () -> {
            try (Auth auth = new Auth(); Proto nonce = Proto.parse(auth.nonceCommand());
                 Proto account = nonce.message(3); Proto app = account.message(30)) {
                yes(nonce.number(1) == 1 && nonce.number(2) == 26 && app.bytes(1).length == 16);
                byte[] verify = new Proto.Writer().bytes(1, new byte[16]).bytes(2, new byte[32]).build();
                byte[] reply = new Proto.Writer().number(1, 1).number(2, 26)
                        .bytes(3, new Proto.Writer().bytes(31, verify).build()).build();
                try (Proto challenge = Proto.parse(reply)) { rejects(() -> auth.respond(new byte[16], challenge, 37, "Probe")); }
                rejects(() -> auth.encrypt(2, new byte[]{1}, 1));
            }
        });
        test("SPP v1 synthetic auth, battery, device info, live start/stop", () -> simulate(1));
        test("SPP v2 synthetic auth, battery, device info, live start/stop", () -> simulate(2));
        test("SPP v2 encrypted auth confirmation", () -> {
            Peer peer = new Peer(2); peer.encryptedConfirmation = true;
            try (Session session = new Session(peer, peer.output)) {
                session.negotiate(); session.authenticate(peer.secret, 37, "Probe");
                yes(peer.commands.equals(List.of("1/26", "1/27")));
            }
        });
        test("SPP v1 and v2 reject bad watch HMAC without confirmation", () -> {
            for (int version : new int[]{1, 2}) {
                Peer peer = new Peer(version); peer.badProof = true;
                try (Session session = new Session(peer, peer.output)) {
                    session.negotiate(); rejects(() -> session.authenticate(peer.secret, 37, "Probe"));
                    yes(peer.commands.equals(List.of("1/26")));
                }
            }
        });
        test("SPP v2 NACK aborts without retransmitting auth", () -> {
            Peer peer = new Peer(2); peer.nack = true;
            try (Session session = new Session(peer, peer.output)) {
                session.negotiate(); rejects(() -> session.authenticate(peer.secret, 37, "Probe"));
                yes(peer.commands.equals(List.of("1/26")));
            }
        });
        test("SPP v2 sequence gap aborts before confirmation", () -> {
            Peer peer = new Peer(2); peer.sequenceGap = true;
            try (Session session = new Session(peer, peer.output)) {
                session.negotiate(); rejects(() -> session.authenticate(peer.secret, 37, "Probe"));
                yes(peer.commands.equals(List.of("1/26")));
            }
        });
        test("Explicit auth failure cannot enable ordinary queries", () -> {
            Peer peer = new Peer(2); peer.rejectConfirmation = true;
            try (Session session = new Session(peer, peer.output)) {
                session.negotiate(); rejects(() -> session.authenticate(peer.secret, 37, "Probe"));
                rejects(session::battery);
                yes(peer.commands.equals(List.of("1/26", "1/27")));
            }
        });
        test("HR settings canonical GET has no mutation body", () -> eq(Proto.command(8, 10), hex("0808100a")));
        test("HR settings literal fixture preserves explicit zero and minutes", () -> {
            // Command 8/10, Health field 8, disabled=false, interval=10 minutes.
            try (Proto command = Proto.parse(hex("0808100a520642040800100a"))) {
                HeartRateSettings settings = HeartRateSettings.parse(command);
                yes(settings.values.get("disabled").equals(false));
                yes(settings.values.get("interval_value").equals(10L));
                yes(settings.values.get("interval_minutes").equals(10L));
                yes(settings.values.get("interval_unit").equals("minutes"));
                yes(settings.values.get("monitoring_mode").equals("periodic"));
                yes(settings.values.get("available_intervals") == null);
            }
        });
        test("HR settings absence never becomes default false or smart", () -> {
            HeartRateSettings absent = settings(new byte[0]);
            yes(absent.values.get("disabled") == null && absent.values.get("interval_value") == null);
            yes(absent.values.get("monitoring_mode").equals("undetermined"));
            yes(((java.util.Map<?, ?>) absent.values.get("presence")).get("disabled").equals(false));
            HeartRateSettings intervalOnly = settings(hex("1000"));
            yes(intervalOnly.values.get("interval_value").equals(0L));
            yes(intervalOnly.values.get("interval_minutes") == null);
            yes(intervalOnly.values.get("monitoring_mode").equals("undetermined"));
        });
        test("HR settings smart disabled and large uint32 intervals", () -> {
            yes(settings(hex("08001000")).values.get("monitoring_mode").equals("smart"));
            yes(settings(hex("0801100a")).values.get("monitoring_mode").equals("disabled"));
            yes(settings(hex("0801")).values.get("monitoring_mode").equals("disabled"));
            HeartRateSettings large = settings(new Proto.Writer().number(1, 0).number(2, 0xffffffffL).build());
            yes(large.values.get("interval_minutes").equals(0xffffffffL));
        });
        test("HR settings nested presence known feature states and unknown-field exclusion", () -> {
            HeartRateSettings result = settings(new Proto.Writer().number(3, 0).number(4, 130)
                    .bytes(5, new Proto.Writer().number(1, 0).build())
                    .bytes(8, new Proto.Writer().number(1, 1).number(2, 45).build())
                    .number(9, 0).number(10, 1).number(11, 2).number(7, 12345)
                    .bytes(123, ascii("must_not_escape")).build());
            java.util.Map<?, ?> presence = (java.util.Map<?, ?>) result.values.get("presence");
            yes(presence.get("advanced_monitoring_enabled").equals(true));
            yes(result.values.get("advanced_monitoring_enabled").equals(false));
            yes(result.values.get("alarm_low_enabled").equals(true));
            yes(result.values.get("alarm_low_threshold_bpm").equals(45L));
            yes(result.values.get("breathing_score").equals("unsupported"));
            yes(result.values.get("irregular_rhythm_alert").equals("on"));
            yes(result.values.get("sleep_respiratory_rate").equals("off"));
            yes(!result.values.toString().contains("must_not_escape") && !result.values.toString().contains("12345"));
            yes(settings(hex("4803")).values.get("breathing_score").equals("unrecognized"));
            HeartRateSettings nestedEmpty = settings(hex("2a004200"));
            yes(((java.util.Map<?, ?>) nestedEmpty.values.get("presence")).get("advanced_monitoring").equals(true));
            yes(nestedEmpty.values.get("advanced_monitoring_enabled") == null);
        });
        test("HR settings missing envelopes and optional success status", () -> {
            try (Proto command = Proto.parse(Proto.command(8, 10))) {
                HeartRateSettings result = HeartRateSettings.parse(command);
                yes(result.values.get("status").equals("health_absent"));
                yes(result.values.get("command_status") == null);
            }
            try (Proto command = Proto.parse(new Proto.Writer().number(1, 8).number(2, 10).bytes(10, new byte[0]).number(100, 0).build())) {
                HeartRateSettings result = HeartRateSettings.parse(command);
                yes(result.values.get("status").equals("heart_rate_absent"));
                yes(result.values.get("command_status").equals(0L));
            }
        });
        test("HR settings rejects invalid known fields response type and status", () -> {
            rejects(() -> settings(hex("0802")));
            rejects(() -> settings(hex("0a00")));
            rejects(() -> settings(new Proto.Writer().number(2, 0x100000000L).build()));
            rejects(() -> settings(hex("2a0108")));
            try (Proto command = Proto.parse(Proto.command(8, 11))) { rejects(() -> HeartRateSettings.parse(command)); }
            try (Proto command = Proto.parse(new Proto.Writer().number(1, 8).number(2, 10).number(100, 1).build())) {
                rejects(() -> HeartRateSettings.parse(command));
            }
        });
        test("SPP v1 and v2 settings allowlist never mutates", () -> {
            for (int version : new int[]{1, 2}) {
                Peer peer = new Peer(version); peer.settingsOnly = true;
                try (Session session = new Session(peer, peer.output)) {
                    session.negotiate(); session.authenticate(peer.secret, 37, "Probe");
                    try (Proto battery = session.battery()) { yes(battery.number(1) == 2); }
                    try (Proto info = session.deviceInfo()) { yes(info.number(2) == 2); }
                    HeartRateSettings result = session.heartRateSettings();
                    yes(result.values.get("interval_minutes").equals(10L));
                    yes(peer.commands.equals(List.of("1/26", "1/27", "2/1", "2/2", "8/10")));
                }
            }
        });
        test("Settings query requires exact device-reported model and firmware", () -> {
            for (int scenario = 0; scenario < 3; scenario++) {
                Peer peer = new Peer(2); peer.settingsOnly = true;
                if (scenario == 1) peer.deviceModel = "M2345B2";
                if (scenario == 2) peer.deviceFirmware = "2.3.99";
                try (Session session = new Session(peer, peer.output)) {
                    session.negotiate(); session.authenticate(peer.secret, 37, "Probe");
                    if (scenario != 0) try (Proto info = session.deviceInfo()) { yes(info.number(2) == 2); }
                    rejects(session::heartRateSettings);
                    yes(!peer.commands.contains("8/10"));
                }
            }
        });
        test("History index canonical today and past requests", () -> {
            eq(HistoryIndex.todayRequest(), hex("0808100152042a020800"));
            eq(HistoryIndex.pastRequest(), hex("08081002"));
        });
        test("History index groups only public descriptor categories and wipes IDs", () -> {
            byte[] ids = hex("01020304fc010599887766000105deadbeefff02ff");
            List<java.util.Map<String, Object>> groups = HistoryIndex.summarizeAndWipe(ids);
            eq(ids, new byte[21]);
            yes(groups.size() == 2);
            yes(groups.get(0).equals(java.util.Map.of("type", 0, "subtype", 1, "detail", 1, "version", 1, "count", 2)));
            yes(groups.get(1).equals(java.util.Map.of("type", 1, "subtype", 31, "detail", 3, "version", 2, "count", 1)));
            yes(!groups.toString().contains("timestamp") && !groups.toString().contains("timezone"));
        });
        test("History index boundaries max 256 empty malformed and oversized", () -> {
            yes(HistoryIndex.summarizeAndWipe(new byte[0]).isEmpty());
            List<java.util.Map<String, Object>> max = HistoryIndex.summarizeAndWipe(new byte[256 * 7]);
            yes(max.size() == 1 && max.get(0).get("count").equals(256));
            for (int size : new int[]{1, 2, 3, 4, 5, 6, 8, 257 * 7}) {
                byte[] ids = new byte[size]; Arrays.fill(ids, (byte) 0xab);
                rejects(() -> HistoryIndex.summarizeAndWipe(ids)); eq(ids, new byte[size]);
            }
        });
        test("History index absent fields differ from empty list and unknown data is excluded", () -> {
            try (Proto command = Proto.parse(Proto.command(8, 1))) {
                HistoryIndex result = HistoryIndex.parse(command, 1);
                yes(result.values.get("status").equals("health_absent"));
                yes(result.values.get("file_id_count") == null);
            }
            byte[] health = new Proto.Writer().bytes(2, new byte[0]).bytes(123, ascii("private_unknown")).build();
            try (Proto command = Proto.parse(new Proto.Writer().number(1, 8).number(2, 2).bytes(10, health).build())) {
                HistoryIndex result = HistoryIndex.parse(command, 2);
                yes(result.values.get("file_id_count").equals(0));
                yes(result.values.get("status").equals("present"));
                yes(!result.values.toString().contains("private_unknown"));
            }
            try (Proto command = Proto.parse(new Proto.Writer().number(1, 8).number(2, 1).bytes(10, new byte[0]).build())) {
                yes(HistoryIndex.parse(command, 1).values.get("status").equals("file_ids_absent"));
            }
        });
        test("History index rejects response mismatches and failure status", () -> {
            try (Proto command = Proto.parse(Proto.command(8, 3))) { rejects(() -> HistoryIndex.parse(command, 1)); }
            try (Proto command = Proto.parse(new Proto.Writer().number(1, 8).number(2, 2).number(100, 1).build())) {
                rejects(() -> HistoryIndex.parse(command, 2));
            }
        });
        test("SPP v1 and v2 index allowlist never requests files ACK settings or live", () -> {
            for (int version : new int[]{1, 2}) {
                Peer peer = new Peer(version); peer.indexOnly = true;
                try (Session session = new Session(peer, peer.output)) {
                    session.negotiate(); session.authenticate(peer.secret, 37, "Probe");
                    try (Proto battery = session.battery()) { yes(battery.number(1) == 2); }
                    try (Proto info = session.deviceInfo()) { yes(info.number(2) == 2); }
                    HistoryIndex today = session.historyIndexToday(), past = session.historyIndexPast();
                    yes(today.values.get("file_id_count").equals(2) && past.values.get("file_id_count").equals(2));
                    yes(peer.commands.equals(List.of("1/26", "1/27", "2/1", "2/2", "8/1", "8/2")));
                }
            }
        });
        test("History index blocks both reads before exact model and firmware verification", () -> {
            for (int scenario = 0; scenario < 3; scenario++) {
                Peer peer = new Peer(2); peer.indexOnly = true;
                if (scenario == 1) peer.deviceModel = "M2345B2";
                if (scenario == 2) peer.deviceFirmware = "2.3.99";
                try (Session session = new Session(peer, peer.output)) {
                    session.negotiate(); session.authenticate(peer.secret, 37, "Probe");
                    if (scenario != 0) try (Proto info = session.deviceInfo()) { yes(info.number(2) == 2); }
                    rejects(session::historyIndexToday); rejects(session::historyIndexPast);
                    yes(!peer.commands.contains("8/1") && !peer.commands.contains("8/2"));
                }
            }
        });
        test("Unknown SPP version stops after one version query", () -> {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (Session session = new Session(new ByteArrayInputStream(Wire.v1(0, 0x80, 2, 0, 0, new byte[]{3})), output)) {
                yes(session.negotiate() == 3);
                rejects(() -> session.authenticate(new byte[16], 37, "Probe"));
                eq(output.toByteArray(), Wire.versionRequest());
            }
        });
        passed += HistoryTests.run();
        test("History read encrypted channel5 multi-file allowlist preserves index and no history ACK", () -> {
            Peer peer = new Peer(2); peer.historyOnly = true; peer.bulkScenario = "duplicate_transport";
            HistoryResults safe = new HistoryResults();
            java.util.Map<String, Object> result = historyRun(peer, safe);
            yes(result.get("status").equals("verified") && result.get("retention_index_checked").equals(true));
            yes(result.get("retention_index_unchanged").equals(true)); yes(safe.files.size() == 2 && safe.selected == 2);
            yes(safe.files.get(1).get("detail_records").equals(2));
            yes(safe.files.get(0).get("today_steps").equals(12345L));
            yes(safe.files.get(0).get("request_receipt_seen_before_complete").equals(true));
            yes(peer.commands.equals(List.of("1/26", "1/27", "2/1", "2/2", "8/1", "8/3", "8/3", "8/1")));
            yes(peer.transportAcks > 10);
            yes(safe.stages.equals(List.of("history_read_index_today", "history_read_file", "history_read_file", "history_read_retention_index_today")));
        });
        test("History sample is explicit partial coverage and preserves the full retention snapshot", () -> {
            Peer peer = new Peer(2); peer.historyOnly = true; peer.bulkScenario = "many_details";
            HistoryResults safe = new HistoryResults();
            java.util.Map<String, Object> result = historyRun(peer, safe, true);
            yes(result.get("status").equals("verified_sample"));
            yes(result.get("candidate_file_count").equals(11) && result.get("omitted_file_count").equals(9));
            yes(safe.selected == 2 && safe.omitted == 9 && safe.sample && safe.files.size() == 2);
            yes(result.get("retention_index_unchanged").equals(true));
            yes(!peer.commands.contains("8/5") && peer.commands.stream().filter("8/2"::equals).count() == 1);
            Peer full = new Peer(2); full.historyOnly = true; full.bulkScenario = "many_details";
            rejects(() -> historyRun(full, new HistoryResults()));
            yes(!full.commands.contains("8/3"));
        });
        test("Explicit details batch fetches two sequential unique IDs without summary or history ACK", () -> {
            for (String scenario : List.of("batch", "batch_retention_changed")) {
                Peer peer = new Peer(2); peer.historyOnly = true; peer.bulkScenario = scenario;
                HistoryResults safe = new HistoryResults();
                java.util.Map<String, Object> result = historyRun(peer, safe, true, false, 2, 2);
                boolean unchanged = scenario.equals("batch");
                yes(result.get("status").equals(unchanged ? "verified_sample" : "retention_index_changed"));
                yes(result.get("retention_index_unchanged").equals(unchanged));
                yes(result.get("candidate_file_count").equals(11) && result.get("detail_candidate_file_count").equals(10));
                yes(result.get("omitted_file_count").equals(9) && result.get("requested_detail_count_satisfied").equals(true));
                yes(safe.files.size() == 2 && peer.fileFetches == 2);
                for (int i = 0; i < 2; i++) {
                    byte[] expected = HistoryTests.DETAILS_ID.clone(); expected[0] = (byte) (11 + i);
                    eq(peer.requestedIds.get(i), expected);
                    yes(safe.files.get(i).get("detail_records").equals(2));
                    yes(!safe.files.get(i).containsKey("today_steps"));
                }
                yes(peer.commands.equals(List.of("1/26", "1/27", "2/1", "2/2", "8/1", "8/2", "8/3", "8/3", "8/1")));
            }
        });
        test("Next-details compatibility fetches exactly the second detail without summary", () -> {
            Peer peer = new Peer(2); peer.historyOnly = true; peer.bulkScenario = "batch";
            HistoryResults safe = new HistoryResults();
            yes(historyRun(peer, safe, true, false, 1, 1).get("status").equals("verified_sample"));
            byte[] expected = HistoryTests.DETAILS_ID.clone(); expected[0] = 10;
            yes(peer.fileFetches == 1 && safe.files.size() == 1); eq(peer.requestedIds.get(0), expected);
            yes(!safe.files.get(0).containsKey("today_steps") && !peer.commands.contains("8/5"));
        });
        test("Explicit details batch checks second file identity padding CRC before claiming retention", () -> {
            for (String scenario : List.of("batch_wrong_id", "batch_pad", "batch_crc")) {
                Peer peer = new Peer(2); peer.historyOnly = true; peer.bulkScenario = scenario;
                HistoryResults safe = new HistoryResults();
                rejects(() -> historyRun(peer, safe, true, false, 2, 2));
                yes(peer.fileFetches == 2 && safe.files.size() == 2 && peer.indexQueries == 2);
                yes(safe.detailCandidates == 10 && Boolean.TRUE.equals(safe.requestedCountSatisfied));
                yes(!peer.commands.contains("8/5"));
            }
        });
        test("Batch tail is honest partial selection and invalid or absent offsets never fetch", () -> {
            Peer tail = new Peer(2); tail.historyOnly = true; tail.bulkScenario = "batch";
            java.util.Map<String, Object> result = historyRun(tail, new HistoryResults(), true, false, 9, 2);
            yes(result.get("status").equals("verified_sample") && result.get("selected_file_count").equals(1));
            yes(result.get("requested_detail_count_satisfied").equals(false) && tail.fileFetches == 1);
            for (int[] range : new int[][]{{-2, 1}, {256, 1}, {-1, 2}, {0, 0}, {0, 3}, {10, 1}}) {
                Peer peer = new Peer(2); peer.historyOnly = true; peer.bulkScenario = "batch";
                rejects(() -> historyRun(peer, new HistoryResults(), true, false, range[0], range[1]));
                yes(!peer.commands.contains("8/3"));
                if (range[0] != 10) yes(!peer.commands.contains("8/1"));
            }
        });
        test("Explicit past batch selects only past details and checks past retention without history ACK", () -> {
            for (String scenario : List.of("archive", "archive_retention_changed")) {
                Peer peer = new Peer(2); peer.historyOnly = true; peer.bulkScenario = scenario;
                HistoryResults safe = new HistoryResults();
                java.util.Map<String, Object> result = historyRun(peer, safe, true, false, 0, 2, true);
                boolean unchanged = scenario.equals("archive");
                yes(result.get("status").equals(unchanged ? "verified_sample" : "retention_index_changed"));
                yes(result.get("retention_index_unchanged").equals(unchanged));
                yes(safe.files.size() == 2 && safe.detailCandidates == 2 && peer.requestedIds.size() == 2);
                eq(peer.requestedIds.get(0), HistoryTests.DETAILS_ID);
                byte[] next = HistoryTests.DETAILS_ID.clone(); next[0] = 10; eq(peer.requestedIds.get(1), next);
                for (java.util.Map<String, Object> file : safe.files) {
                    yes(file.get("requested_index_scope").equals("past") && file.get("file_id_match").equals(true));
                    yes(file.get("received_id_in_past_index").equals(true) && file.get("received_id_in_today_index").equals(false));
                }
                yes(peer.commands.equals(List.of("1/26", "1/27", "2/1", "2/2", "8/2", "8/1", "8/3", "8/3", "8/2")));
            }
        });
        test("Past mismatch never accepted even if ID matches counterpart today index", () -> {
            Peer peer = new Peer(2); peer.historyOnly = true; peer.bulkScenario = "archive_wrong_id";
            HistoryResults safe = new HistoryResults();
            rejects(() -> historyRun(peer, safe, true, false, 0, 1, true));
            java.util.Map<String, Object> file = safe.files.get(0);
            yes(file.get("file_id_match").equals(false) && file.get("parser_status").equals("not_parsed"));
            yes(file.get("received_id_in_past_index").equals(false) && file.get("received_id_in_today_index").equals(true));
            yes(peer.indexQueries == 2 && peer.fileFetches == 1 && !peer.commands.contains("8/5"));
        });
        test("Past batch requires explicit offset sample no repeat and rejects absent detail before fetch", () -> {
            for (int scenario = 0; scenario < 4; scenario++) {
                Peer peer = new Peer(2); peer.historyOnly = true; peer.bulkScenario = "archive";
                int offset = scenario == 0 ? -1 : scenario == 3 ? 2 : 0;
                boolean sample = scenario != 1, repeat = scenario == 2;
                rejects(() -> historyRun(peer, new HistoryResults(), sample, repeat, offset, 1, true));
                yes(peer.fileFetches == 0);
                if (scenario != 3) yes(!peer.commands.contains("8/2") && !peer.commands.contains("8/1"));
            }
        });
        test("Mismatch provenance compares CRC-valid header to past snapshot without accepting or parsing it", () -> {
            for (String scenario : List.of("provenance_present", "provenance_absent", "provenance_bad_crc")) {
                Peer peer = new Peer(2); peer.historyOnly = true; peer.bulkScenario = scenario;
                HistoryResults safe = new HistoryResults();
                rejects(() -> historyRun(peer, safe, true, false, 0, 1));
                yes(peer.fileFetches == 1 && peer.indexQueries == 2 && safe.files.size() == 1);
                java.util.Map<String, Object> file = safe.files.get(0);
                yes(file.get("past_index_snapshot_checked").equals(true));
                yes(file.get("past_index_file_count").equals(scenario.equals("provenance_absent") ? 0 : 1));
                yes(file.get("parser_status").equals("not_parsed") && !file.containsKey("detail_records"));
                if (scenario.equals("provenance_bad_crc")) {
                    yes(file.get("received_id_in_past_index") == null && file.get("received_id_in_original_index") == null);
                } else {
                    yes(file.get("received_id_in_past_index").equals(scenario.equals("provenance_present")));
                    yes(file.get("received_id_in_original_index").equals(false) && file.get("file_id_match").equals(false));
                }
                yes(peer.commands.equals(List.of("1/26", "1/27", "2/1", "2/2", "8/1", "8/2", "8/3")));
            }
        });
        test("Invalid or absent past snapshot aborts sample before fetch without treating unknown as empty", () -> {
            for (String scenario : List.of("provenance_invalid_index", "provenance_missing_index_field")) {
                Peer peer = new Peer(2); peer.historyOnly = true; peer.bulkScenario = scenario;
                HistoryResults safe = new HistoryResults();
                rejects(() -> historyRun(peer, safe, true, false, 0, 1));
                yes(peer.fileFetches == 0 && safe.files.isEmpty() && peer.indexQueries == 2);
            }
        });
        test("One-request correlation observes at most two headers never parses or retries", () -> {
            for (String scenario : List.of("correlate_exact_first", "correlate_then_exact", "correlate_two_unmatched", "correlate_late_receipt", "correlate_prebulk")) {
                Peer peer = new Peer(2); peer.historyOnly = true; peer.bulkScenario = scenario;
                HistoryResults safe = new HistoryResults();
                java.util.Map<String, Object> result = correlateRun(peer, safe, 0);
                boolean first = scenario.equals("correlate_exact_first"), none = scenario.equals("correlate_two_unmatched");
                yes(result.get("status").equals(first ? "diagnostic_exact_first" : none ? "diagnostic_no_exact_within_two" : "diagnostic_exact_after_unmatched"));
                yes(result.get("retention_index_checked").equals(false));
                yes(safe.selected == 1 && safe.files.size() == (first ? 1 : 2) && peer.fileFetches == 1);
                yes(peer.commands.equals(List.of("1/26", "1/27", "2/2", "8/2", "8/1", "8/3")));
                for (int i = 0; i < safe.files.size(); i++) {
                    java.util.Map<String, Object> file = safe.files.get(i);
                    yes(file.get("parser_status").equals("not_parsed") && !file.containsKey("detail_records"));
                    yes(file.get("correlation_header_only").equals(true) && file.get("correlation_file_ordinal").equals(i + 1));
                    yes(file.get("fetch_command_count").equals(1) && file.get("request_transport_ack_seen_before_complete").equals(true));
                    yes(file.get("bulk_frames_before_request").equals(scenario.equals("correlate_prebulk") ? 2 : 0));
                    yes(file.get("bulk_payload_bytes_before_request").equals(scenario.equals("correlate_prebulk") ? HistoryTests.details().length + 8 : 0));
                }
                yes(safe.files.get(0).get("first_bulk_before_request_receipt").equals(scenario.equals("correlate_late_receipt")));
                if (!first) yes(!peer.input.isEmpty());
            }
        });
        test("Unsolicited bulk before fetch is bounded by frames bytes and encrypted-only rule", () -> {
            for (String scenario : List.of("correlate_prebulk_plain", "correlate_prebulk_flood", "correlate_prebulk_bytes")) {
                Peer peer = new Peer(2); peer.historyOnly = true; peer.bulkScenario = scenario;
                HistoryResults safe = new HistoryResults();
                try { correlateRun(peer, safe, 0); throw new AssertionError("unsolicited guard should abort"); }
                catch (ProbeException failure) {
                    String expected = scenario.equals("correlate_prebulk_plain") ? "unexpected_plaintext"
                            : scenario.equals("correlate_prebulk_flood") ? "history_unsolicited_bulk_limit" : "history_bulk_byte_limit";
                    yes(failure.getMessage().equals(expected));
                }
                yes(peer.fileFetches == 0 && safe.files.isEmpty() && !peer.commands.contains("8/5"));
            }
        });
        test("Skipped prefetch bulk shares byte budget with requested transfer including chunk headers", () -> {
            Peer peer = new Peer(2); peer.historyOnly = true; peer.bulkScenario = "correlate_prebulk_combined";
            HistoryResults safe = new HistoryResults();
            try { correlateRun(peer, safe, 0); throw new AssertionError("combined budget should abort"); }
            catch (ProbeException failure) { yes(failure.getMessage().equals("history_bulk_byte_limit")); }
            yes(peer.fileFetches == 1 && safe.files.size() == 1 && !peer.commands.contains("8/5"));
            yes(safe.files.get(0).get("transfer_complete").equals(false));
            yes((Integer) safe.files.get(0).get("bulk_payload_bytes_before_request") > HistoryFile.MAX_BYTES / 2);
        });
        test("Correlation propagates timeout and cancellation without resetting file stage or retrying", () -> {
            for (String scenario : List.of("correlate_timeout", "correlate_cancel")) {
                Peer peer = new Peer(2); peer.historyOnly = true; peer.bulkScenario = scenario;
                HistoryResults safe = new HistoryResults();
                try { correlateRun(peer, safe, 0); throw new AssertionError("deadline should propagate"); }
                catch (java.io.InterruptedIOException failure) {
                    yes((failure instanceof java.net.SocketTimeoutException) == scenario.equals("correlate_timeout"));
                }
                yes(safe.files.size() == 2 && safe.files.get(0).get("file_id_match").equals(false));
                yes(safe.files.get(1).get("transfer_complete").equals(false));
                yes(safe.stages.stream().filter("history_read_file"::equals).count() == 1 && peer.fileFetches == 1);
                yes(!peer.commands.contains("8/5"));
            }
        });
        test("Correlation preserves first mismatch evidence on absent second file and aborts invalid input", () -> {
            for (String scenario : List.of("correlate_silent", "correlate_crc", "correlate_padding", "correlate_channel", "correlate_plain", "correlate_budget")) {
                Peer peer = new Peer(2); peer.historyOnly = true; peer.bulkScenario = scenario;
                HistoryResults safe = new HistoryResults();
                rejects(() -> correlateRun(peer, safe, 0));
                yes(peer.fileFetches == 1 && !peer.commands.contains("8/5") && peer.indexQueries == 2);
                yes(!safe.files.isEmpty());
                yes(safe.files.get(0).get("parser_status").equals("not_parsed"));
                if (scenario.equals("correlate_silent")) {
                    yes(safe.files.size() == 2 && safe.files.get(1).get("transfer_complete").equals(false));
                    yes(safe.files.get(0).get("file_id_match").equals(false));
                }
            }
            Peer peer = new Peer(2); peer.historyOnly = true;
            rejects(() -> correlateRun(peer, new HistoryResults(), -1));
            yes(!peer.commands.contains("8/2"));
        });
        test("Passive pre-index window emits no history commands even with unsolicited files and control traffic", () -> {
            for (String scenario : List.of("passive_silence", "passive_complete", "passive_duplicate", "passive_control", "passive_unknown")) {
                Peer peer = new Peer(2); peer.historyOnly = true; peer.bulkScenario = scenario;
                HistoryResults safe = new HistoryResults();
                java.util.Map<String, Object> result = passiveRun(peer, safe);
                yes(result.equals(safe.boundary) && result.get("status").equals("diagnostic_passive_window_elapsed"));
                yes(result.get("window_elapsed").equals(true) && result.get("observation_window_ms").equals(6000));
                yes(result.get("history_command_count").equals(0) && result.get("index_response_count").equals(0));
                yes(result.get("absence_of_stream_proven").equals(false) && result.get("transport_boundary_at_deadline").equals("not_verified"));
                yes(result.get("today_response_transport_ack_seen") == null && result.get("past_response_transport_ack_seen") == null);
                yes(peer.commands.equals(List.of("1/26", "1/27", "2/2")) && peer.indexQueries == 0 && peer.fileFetches == 0);
                yes(safe.stages.equals(List.of("history_passive_observation")));
                yes(safe.files.size() == (scenario.equals("passive_silence") ? 0 : 1));
                for (java.util.Map<String, Object> file : safe.files) {
                    yes(file.get("boundary_observation_phase").equals("pre_index_passive"));
                    yes(file.get("boundary_subtype_first_bulk") == null && file.get("boundary_subtype_last_bulk") == null);
                    yes(file.get("boundary_request_ack_seen_first_bulk") == null && file.get("boundary_request_ack_seen_last_bulk") == null);
                    yes(file.get("received_id_in_today_index") == null && file.get("received_id_in_past_index") == null);
                    yes(file.get("parser_status").equals("not_parsed") && file.get("crc_valid").equals(true));
                    yes(!file.containsKey("detail_records") && !file.containsKey("today_steps"));
                }
            }
        });
        test("Passive deadline with partial file or SPP header or payload never claims a complete transport boundary", () -> {
            for (String scenario : List.of("passive_partial_file", "passive_half_header", "passive_half_payload")) {
                Peer peer = new Peer(2); peer.bulkScenario = scenario;
                HistoryResults safe = new HistoryResults();
                java.util.Map<String, Object> result = passiveRun(peer, safe);
                yes(result.get("window_elapsed").equals(true) && result.get("complete_file_count").equals(0));
                yes(result.get("transport_boundary_at_deadline").equals("not_verified") && result.get("absence_of_stream_proven").equals(false));
                yes(result.get("incomplete_file_present").equals(scenario.equals("passive_partial_file")));
                yes(safe.files.size() == (scenario.equals("passive_partial_file") ? 1 : 0));
                if (!safe.files.isEmpty()) yes(safe.files.get(0).get("boundary_file_partial").equals(true));
                yes(peer.commands.equals(List.of("1/26", "1/27", "2/2")));
            }
        });
        test("Passive early timeout EOF and cancellation are aborts not elapsed observation windows", () -> {
            for (String scenario : List.of("passive_early_timeout", "passive_eof", "passive_cancel")) {
                Peer peer = new Peer(2); peer.bulkScenario = scenario;
                HistoryResults safe = new HistoryResults();
                rejects(() -> passiveRun(peer, safe));
                yes(safe.boundary.get("status").equals("diagnostic_passive_aborted") && safe.boundary.get("window_elapsed").equals(false));
                yes(peer.commands.equals(List.of("1/26", "1/27", "2/2")));
            }
        });
        test("Passive malformed and bounded flood traffic abort without parser requests or retries", () -> {
            for (String scenario : List.of("passive_crc", "passive_pad", "passive_order", "passive_plain", "passive_channel", "passive_files", "passive_frames", "passive_bytes", "passive_control_flood")) {
                Peer peer = new Peer(2); peer.bulkScenario = scenario;
                HistoryResults safe = new HistoryResults();
                rejects(() -> passiveRun(peer, safe));
                yes(safe.boundary.get("window_elapsed").equals(false) && safe.files.size() <= 16);
                yes(safe.files.stream().allMatch(file -> file.get("parser_status").equals("not_parsed")));
                yes(peer.commands.equals(List.of("1/26", "1/27", "2/2")));
            }
        });
        test("Passive send gate rejects all application commands and wipes resources on observer failure", () -> {
            Peer peer = new Peer(2);
            try (Session session = new Session(peer, peer.output)) {
                session.negotiate(); session.authenticate(peer.secret, 37, "Probe");
                try (Proto info = session.deviceInfo()) { yes(info.number(2) == 2); }
                peer.bulkScenario = "passive_complete"; peer.passiveTraffic();
                Session.HistoryObserver observer = new Session.HistoryObserver() {
                    @Override public void onStage(String stage) {
                        try {
                            rejects(session::historyIndexToday); rejects(session::historyIndexPast);
                            rejects(session::battery); rejects(session::heartRateSettings); rejects(session::liveStart); rejects(session::liveStop);
                            java.lang.reflect.Method send = Session.class.getDeclaredMethod("send", byte[].class, boolean.class); send.setAccessible(true);
                            for (int sub : new int[]{1, 2, 3, 5, 11, 45, 46}) {
                                byte[] command = Proto.command(8, sub);
                                try { send.invoke(session, command, false); throw new AssertionError("passive command accepted"); }
                                catch (java.lang.reflect.InvocationTargetException expected) { yes(expected.getCause() instanceof ProbeException); }
                                yes(Arrays.equals(command, new byte[command.length]));
                            }
                        } catch (Exception e) { throw new AssertionError(e); }
                    }
                    @Override public void onSelection(int count) { throw new AssertionError("passive selection"); }
                    @Override public void onBoundary(java.util.Map<String, Object> summary) { throw new IllegalStateException("synthetic observer"); }
                    @Override public void onFile(java.util.Map<String, Object> summary) { throw new IllegalStateException("synthetic file observer"); }
                };
                try { session.historyPassive(observer, () -> peer.passiveNow); throw new AssertionError("observer accepted"); }
                catch (IllegalStateException expected) { /* Both nested resource cleanups still run. */ }
                for (String field : List.of("historyBoundary", "historyPassiveActive", "historyReadActive")) {
                    java.lang.reflect.Field state = Session.class.getDeclaredField(field); state.setAccessible(true);
                    yes(field.equals("historyBoundary") ? state.get(session) == null : !state.getBoolean(session));
                }
                yes(peer.commands.equals(List.of("1/26", "1/27", "2/2")));
            }
        });
        test("Passive exact model firmware and SPPv2 guards precede observation", () -> {
            for (int scenario = 0; scenario < 4; scenario++) {
                Peer peer = new Peer(scenario == 3 ? 1 : 2);
                if (scenario == 1) peer.deviceModel = "M2345B2";
                if (scenario == 2) peer.deviceFirmware = "2.3.99";
                try (Session session = new Session(peer, peer.output)) {
                    session.negotiate(); session.authenticate(peer.secret, 37, "Probe");
                    if (scenario != 0) try (Proto info = session.deviceInfo()) { yes(info.number(2) == 2); }
                    HistoryResults safe = new HistoryResults();
                    rejects(() -> session.historyPassive(safe, () -> 0));
                    yes(safe.stages.isEmpty() && peer.indexQueries == 0 && peer.fileFetches == 0);
                }
            }
        });
        test("Today-only window independently observes one index without fetch or parser", () -> {
            for (String scenario : List.of("pastwin_silence", "pastwin_after", "pastwin_before", "pastwin_spanning", "pastwin_unknown", "pastwin_empty", "pastwin_hardware_shape")) {
                Peer peer = new Peer(2); peer.historyOnly = true; peer.bulkScenario = scenario;
                HistoryResults safe = new HistoryResults();
                try (Session session = new Session(peer, peer.output)) {
                    session.negotiate(); session.authenticate(peer.secret, 37, "Probe");
                    try (Proto info = session.deviceInfo()) { yes(info.number(2) == 2); }
                    java.util.Map<String, Object> result = session.historyTodayWindow(safe, () -> peer.passiveNow);
                    yes(result.get("status").equals("diagnostic_today_window_elapsed") && result.equals(safe.boundary));
                    yes(result.get("index_request_subtype").equals(1) && result.get("history_command_count").equals(1));
                    yes(result.get("past_response_transport_ack_seen") == null);
                    for (java.util.Map<String, Object> file : safe.files) {
                        yes(file.get("received_id_in_past_index") == null && file.get("parser_status").equals("not_parsed"));
                        yes(file.get("boundary_subtype_first_bulk").equals(1));
                    }
                }
                yes(peer.commands.equals(List.of("1/26", "1/27", "2/2", "8/1")) && peer.fileFetches == 0);
                yes(safe.stages.equals(List.of("history_today_window_observation")));
            }
        });
        test("Today-only window aborts invalid absent duplicate truncated and over-budget input", () -> {
            for (String scenario : List.of("pastwin_missing", "pastwin_wrong_index", "pastwin_absent", "pastwin_invalid", "pastwin_duplicate_index", "pastwin_crc", "pastwin_pad", "pastwin_order", "pastwin_plain", "pastwin_channel", "pastwin_files", "pastwin_frames", "pastwin_bytes", "pastwin_early_timeout", "pastwin_eof", "pastwin_cancel")) {
                Peer peer = new Peer(2); peer.historyOnly = true; peer.bulkScenario = scenario;
                try (Session session = new Session(peer, peer.output)) {
                    session.negotiate(); session.authenticate(peer.secret, 37, "Probe");
                    try (Proto info = session.deviceInfo()) { yes(info.number(2) == 2); }
                    rejects(() -> session.historyTodayWindow(new HistoryResults(), () -> peer.passiveNow));
                }
                yes(peer.commands.equals(List.of("1/26", "1/27", "2/2", "8/1")) && peer.fileFetches == 0);
            }
        });
        test("Past-only aggregate parses just fresh exact target after ten mismatches and checks full past retention", () -> {
            for (String scenario : List.of("pasttarget_valid", "pasttarget_retention_bulk")) {
                Peer peer = new Peer(2); peer.historyOnly = true; peer.bulkScenario = scenario;
                HistoryResults safe = new HistoryResults();
                java.util.Map<String, Object> result = pastTargetRun(peer, safe, 0);
                yes(result.get("status").equals("verified_target_sample"));
                yes(result.get("retention_index_checked").equals(true) && result.get("retention_index_unchanged").equals(true));
                yes(peer.commands.equals(List.of("1/26", "1/27", "2/2", "8/2", "8/3", "8/2")));
                yes(safe.files.size() == (scenario.equals("pasttarget_retention_bulk") ? 12 : 11));
                yes(safe.files.stream().filter(x -> x.get("parser_status").equals("daily_details_v4")).count() == 1);
                java.util.Map<String, Object> exact = safe.files.get(10);
                yes(exact.get("demux_route").equals("requested_exact") && exact.get("demux_file_phase").equals("target_wait"));
                yes(exact.get("file_id_match").equals(true) && exact.get("received_id_in_past_index").equals(true));
                yes(exact.get("detail_records").equals(2) && exact.get("hr_present_records").equals(2) && exact.get("step_records").equals(2));
                yes(exact.get("hr_missing_records").equals(0));
                for (int i = 0; i < safe.files.size(); i++) {
                    java.util.Map<String, Object> file = safe.files.get(i);
                    yes(file.get("received_id_in_today_index") == null && !file.containsKey("today_steps"));
                    if (i != 10) yes(file.get("parser_status").equals("not_parsed") && !file.containsKey("detail_records"));
                }
            }
        });
        test("Observed past aggregate shape parses only ordinal eleven and quarantines three retention files", () -> {
            Peer peer = new Peer(2); peer.historyOnly = true; peer.bulkScenario = "pasttarget_observed_shape";
            HistoryResults safe = new HistoryResults(); safe.referenceTime = 100000000000L;
            java.util.Map<String, Object> result = pastTargetRun(peer, safe, 0);
            yes(safe.clockReads == 1);
            yes(safe.files.get(10).get("reference_clock_available").equals(true));
            yes(safe.files.get(10).get("candidate_epoch_age_band").equals("older"));
            yes(result.get("status").equals("verified_target_sample") && result.get("retention_index_unchanged").equals(true));
            yes(safe.files.size() == 14 && peer.fileFetches == 1 && peer.indexQueries == 2);
            for (int i = 0; i < safe.files.size(); i++) {
                java.util.Map<String, Object> file = safe.files.get(i);
                yes(file.get("demux_file_ordinal").equals(i + 1));
                yes(file.get("demux_file_phase").equals(i < 11 ? "target_wait" : "retention_wait"));
                yes(file.get("parser_status").equals(i == 10 ? "daily_details_v4" : "not_parsed"));
                if (i != 10) yes(!file.containsKey("candidate_time_basis") && !file.containsKey("hr_encoded_zero_records"));
                yes(file.get("crc_valid").equals(true) && file.get("received_padding_zero").equals(true));
            }
        });
        test("Past repeat fetches exactly the same seven-byte ID twice under one file deadline and shared budgets", () -> {
            for (String scenario : List.of("pastrepeat_valid", "pastrepeat_pre_exact")) {
                Peer peer = new Peer(2); peer.historyOnly = true; peer.bulkScenario = scenario;
                HistoryResults safe = new HistoryResults(); safe.referenceTime = 100000000000L;
                java.util.Map<String, Object> result = pastRepeatRun(peer, safe, 0);
                yes(result.get("status").equals("verified_target_repeat_sample"));
                yes(result.get("repeat_requested").equals(true) && result.get("repeat_read_count").equals(2));
                yes(result.get("repeat_bytes_identical").equals(true) && result.get("repeat_aggregate_consistent").equals(true));
                yes(result.get("retention_index_unchanged").equals(true));
                yes(result.get("downloaded_bytes").equals(HistoryTests.details().length * 2));
                yes(peer.commands.equals(List.of("1/26", "1/27", "2/2", "8/2", "8/3", "8/3", "8/2")));
                eq(peer.requestedIds.get(0), peer.requestedIds.get(1)); eq(peer.requestedIds.get(0), HistoryTests.DETAILS_ID);
                yes(safe.files.size() == (scenario.equals("pastrepeat_pre_exact") ? 16 : 15));
                yes(safe.clockReads == 2 && safe.stages.stream().filter("history_read_file"::equals).count() == 1);
                yes(safe.files.stream().filter(x -> x.get("parser_status").equals("daily_details_v4")).count() == 2);
                yes(safe.files.stream().filter(x -> x.get("demux_file_phase").equals("repeat_wait")).count() == 1);
                for (java.util.Map<String, Object> file : safe.files) {
                    if (file.get("parser_status").equals("not_parsed")) yes(!file.containsKey("candidate_time_basis"));
                    for (String key : List.of("digest", "sha256", "file_id", "timestamp", "heart_rate", "steps")) yes(!file.containsKey(key));
                }
            }
        });
        test("Observed repeat shape quarantines three files after first exact and matches second exact at ordinal fifteen", () -> {
            Peer peer = new Peer(2); peer.historyOnly = true; peer.bulkScenario = "pastrepeat_observed_shape";
            HistoryResults safe = new HistoryResults();
            java.util.Map<String, Object> result = pastRepeatRun(peer, safe, 0);
            yes(result.get("status").equals("verified_target_repeat_sample") && safe.files.size() == 15);
            for (int i = 0; i < 15; i++) {
                java.util.Map<String, Object> file = safe.files.get(i);
                yes(file.get("parser_status").equals(i == 10 || i == 14 ? "daily_details_v4" : "not_parsed"));
                yes(file.get("demux_file_phase").equals(i <= 10 ? "target_wait" : "repeat_wait"));
                yes(file.get("fetch_command_count").equals(i <= 10 ? 1 : 2));
            }
        });
        test("Past repeat compares file bytes independently of transport chunk boundaries", () -> {
            Peer peer = new Peer(2); peer.historyOnly = true; peer.bulkScenario = "pastrepeat_rechunked";
            HistoryResults safe = new HistoryResults();
            java.util.Map<String, Object> result = pastRepeatRun(peer, safe, 0);
            yes(result.get("repeat_bytes_identical").equals(true) && result.get("repeat_aggregate_consistent").equals(true));
            List<java.util.Map<String, Object>> exact = safe.files.stream().filter(x -> x.get("demux_route").equals("requested_exact")).toList();
            yes(exact.size() == 2 && !exact.get(0).get("chunks").equals(exact.get(1).get("chunks")));
        });
        test("Past repeat detects changed bytes even when aggregate counts agree", () -> {
            for (String scenario : List.of("pastrepeat_changed_bytes", "pastrepeat_changed_counts")) {
                Peer peer = new Peer(2); peer.historyOnly = true; peer.bulkScenario = scenario;
                java.util.Map<String, Object> result = pastRepeatRun(peer, new HistoryResults(), 0);
                yes(result.get("status").equals("repeat_file_changed") && result.get("retention_index_unchanged").equals(true));
                yes(result.get("repeat_bytes_identical").equals(false));
                yes(result.get("repeat_aggregate_consistent").equals(scenario.equals("pastrepeat_changed_bytes")));
            }
        });
        test("Past repeat does not refetch an unsupported first exact target or certify unsupported second", () -> {
            Peer first = new Peer(2); first.historyOnly = true; first.bulkScenario = "pastrepeat_unsupported";
            rejects(() -> pastRepeatRun(first, new HistoryResults(), 0));
            yes(first.fileFetches == 1 && first.indexQueries == 1);
            Peer second = new Peer(2); second.historyOnly = true; second.bulkScenario = "pastrepeat_second_unsupported";
            java.util.Map<String, Object> result = pastRepeatRun(second, new HistoryResults(), 0);
            yes(result.get("status").equals("parser_unsupported") && result.get("repeat_read_count").equals(2));
            yes(result.get("repeat_bytes_identical").equals(false));
        });
        test("Past repeat errors never exceed two fetches shared bytes files or permit invalid second target", () -> {
            for (String scenario : List.of("pastrepeat_crc", "pastrepeat_pad", "pastrepeat_channel", "pastrepeat_plain",
                    "pastrepeat_missing", "pastrepeat_bytes", "pastrepeat_timeout", "pastrepeat_cancel", "pastrepeat_eof")) {
                Peer peer = new Peer(2); peer.historyOnly = true; peer.bulkScenario = scenario;
                HistoryResults safe = new HistoryResults();
                rejects(() -> pastRepeatRun(peer, safe, 0));
                yes(peer.fileFetches == 2 && peer.indexQueries == 1 && safe.files.size() <= 16);
                yes(safe.files.stream().filter(x -> x.get("demux_route").equals("requested_exact")).count() == 1);
                yes(safe.stages.stream().filter("history_read_file"::equals).count() == 1 && !peer.commands.contains("8/5"));
            }
        });
        test("Past repeat retention is full multiset with loss duplicates partial and flood still guarded", () -> {
            for (String scenario : List.of("pastrepeat_retention_changed", "pastrepeat_retention_duplicate", "pastrepeat_retention_partial", "pastrepeat_retention_flood")) {
                Peer peer = new Peer(2); peer.historyOnly = true; peer.bulkScenario = scenario;
                HistoryResults safe = new HistoryResults();
                if (scenario.endsWith("partial") || scenario.endsWith("flood")) rejects(() -> pastRepeatRun(peer, safe, 0));
                else {
                    java.util.Map<String, Object> result = pastRepeatRun(peer, safe, 0);
                    yes(result.get("status").equals("retention_index_changed") && result.get("repeat_bytes_identical").equals(true));
                }
                yes(peer.fileFetches == 2 && peer.indexQueries == 2 && safe.files.size() <= 16);
            }
        });
        test("Past repeat preserves exact device SPP and explicit single target range guards", () -> {
            for (int scenario = 0; scenario < 5; scenario++) {
                Peer peer = new Peer(scenario == 2 ? 1 : 2); peer.historyOnly = true;
                if (scenario == 0) peer.deviceModel = "Other";
                if (scenario == 1) peer.deviceFirmware = "0.0.0";
                int offset = scenario == 3 ? -1 : scenario == 4 ? 256 : 0;
                rejects(() -> pastRepeatRun(peer, new HistoryResults(), offset));
                yes(peer.indexQueries == 0 && peer.fileFetches == 0);
            }
        });
        test("Past-only aggregate shares prefetch byte and file bounds and propagates timeout cancellation", () -> {
            for (String scenario : List.of("target_combined_budget", "target_flood", "target_bytes", "target_timeout", "target_cancel", "target_eof")) {
                Peer peer = new Peer(2); peer.historyOnly = true; peer.bulkScenario = scenario;
                HistoryResults safe = new HistoryResults();
                rejects(() -> pastTargetRun(peer, safe, 0));
                yes(peer.indexQueries == 1 && peer.fileFetches <= 1 && safe.files.size() <= 16);
                for (java.util.Map<String, Object> file : safe.files) yes(file.get("parser_status").equals("not_parsed"));
            }
        });
        test("Past-only aggregate never parses exact or partial files started before the target request", () -> {
            for (String scenario : List.of("target_pre_exact", "target_pre_partial")) {
                Peer peer = new Peer(2); peer.historyOnly = true; peer.bulkScenario = scenario;
                HistoryResults safe = new HistoryResults();
                java.util.Map<String, Object> result = pastTargetRun(peer, safe, 0);
                yes(result.get("status").equals("verified_target_sample") && safe.files.size() == 2);
                yes(safe.files.get(0).get("parser_status").equals("not_parsed"));
                yes(safe.files.get(0).get("demux_file_phase").equals("index_wait"));
                yes(safe.files.get(1).get("parser_status").equals("daily_details_v4"));
                yes(peer.commands.equals(List.of("1/26", "1/27", "2/2", "8/2", "8/3", "8/2")));
            }
        });
        test("Past-only aggregate retention detects lost or duplicated IDs without claiming verified sample", () -> {
            for (String scenario : List.of("pasttarget_retention_changed", "pasttarget_retention_duplicate")) {
                Peer peer = new Peer(2); peer.historyOnly = true; peer.bulkScenario = scenario;
                HistoryResults safe = new HistoryResults();
                java.util.Map<String, Object> result = pastTargetRun(peer, safe, 0);
                yes(result.get("status").equals("retention_index_changed"));
                yes(result.get("retention_index_checked").equals(true) && result.get("retention_index_unchanged").equals(false));
                yes(peer.indexQueries == 2 && peer.fileFetches == 1);
            }
        });
        test("Past-only aggregate rejects malformed target or missing exact without parsing quarantined files", () -> {
            for (String scenario : List.of("pasttarget_missing", "pasttarget_crc", "pasttarget_pad", "pasttarget_channel", "pasttarget_plain")) {
                Peer peer = new Peer(2); peer.historyOnly = true; peer.bulkScenario = scenario;
                HistoryResults safe = new HistoryResults();
                rejects(() -> pastTargetRun(peer, safe, 0));
                yes(safe.clockReads == 0);
                yes(peer.indexQueries == 1 && peer.fileFetches == 1 && safe.files.size() <= 16);
                for (java.util.Map<String, Object> file : safe.files) yes(file.get("parser_status").equals("not_parsed"));
            }
        });
        test("Past-only aggregate cannot pass retention with invalid incomplete absent or over-budget trailing data", () -> {
            for (String scenario : List.of("pasttarget_retention_crc", "pasttarget_retention_pad", "pasttarget_retention_partial", "pasttarget_retention_flood", "pasttarget_retention_absent", "pasttarget_retention_invalid")) {
                Peer peer = new Peer(2); peer.historyOnly = true; peer.bulkScenario = scenario;
                HistoryResults safe = new HistoryResults();
                rejects(() -> pastTargetRun(peer, safe, 0));
                yes(peer.indexQueries == 2 && peer.fileFetches == 1 && safe.files.size() <= 16);
                yes(safe.files.stream().filter(x -> x.get("parser_status").equals("daily_details_v4")).count() == 1);
            }
        });
        test("Past-only unsupported exact format remains unsupported without invented HR or step zeros", () -> {
            Peer peer = new Peer(2); peer.historyOnly = true; peer.bulkScenario = "pasttarget_unsupported";
            HistoryResults safe = new HistoryResults();
            java.util.Map<String, Object> result = pastTargetRun(peer, safe, 0);
            yes(result.get("status").equals("parser_unsupported") && result.get("retention_index_unchanged").equals(true));
            java.util.Map<String, Object> exact = safe.files.get(10);
            yes(exact.get("file_id_match").equals(true) && exact.get("parser_status").equals("unsupported_details_header"));
            yes(!exact.containsKey("detail_records") && !exact.containsKey("hr_present_records") && !exact.containsKey("step_records"));
        });
        test("Past-only aggregate keeps exact model firmware SPPv2 and range guards before history commands", () -> {
            for (int scenario = 0; scenario < 5; scenario++) {
                Peer peer = new Peer(scenario == 2 ? 1 : 2); peer.historyOnly = true;
                if (scenario == 0) peer.deviceModel = "M2345B2";
                if (scenario == 1) peer.deviceFirmware = "2.3.99";
                HistoryResults safe = new HistoryResults();
                int offset = scenario == 3 ? -1 : scenario == 4 ? 256 : 0;
                rejects(() -> pastTargetRun(peer, safe, offset));
                yes(peer.indexQueries == 0 && peer.fileFetches == 0 && safe.files.isEmpty());
            }
        });
        test("Past-only target scan can route exact after ten unsolicited files without today query", () -> {
            for (String scenario : List.of("target_scan_past_late", "target_exact", "target_pre_exact", "target_pre_partial")) {
                Peer peer = new Peer(2); peer.historyOnly = true; peer.bulkScenario = scenario;
                HistoryResults safe = new HistoryResults();
                try (Session session = new Session(peer, peer.output)) {
                    session.negotiate(); session.authenticate(peer.secret, 37, "Probe");
                    try (Proto info = session.deviceInfo()) { yes(info.number(2) == 2); }
                    java.util.Map<String, Object> result = session.historyPastTargetScan(safe, 0);
                    yes(result.get("status").equals("diagnostic_target_scan_exact") && result.get("retention_index_checked").equals(false));
                }
                yes(peer.commands.equals(List.of("1/26", "1/27", "2/2", "8/2", "8/3")));
                yes(peer.fileFetches == 1 && peer.indexQueries == 1);
                int expectedFiles = scenario.equals("target_scan_past_late") ? 11 : scenario.equals("target_exact") ? 1 : 2;
                if (safe.files.size() != expectedFiles) throw new AssertionError(scenario + " file count " + safe.files.size());
                for (java.util.Map<String, Object> file : safe.files) {
                    yes(file.get("parser_status").equals("not_parsed") && file.get("received_id_in_today_index") == null);
                    yes(!file.containsKey("today_minimum_differing_id_bytes"));
                }
            }
        });
        test("Past-only target scan stops at sixteen files or shared byte limit and rejects malformed traffic", () -> {
            for (String scenario : List.of("target_scan_past_missing", "target_crc", "target_pad", "target_channel", "target_plain", "target_combined_budget")) {
                Peer peer = new Peer(2); peer.historyOnly = true; peer.bulkScenario = scenario;
                HistoryResults safe = new HistoryResults();
                try (Session session = new Session(peer, peer.output)) {
                    session.negotiate(); session.authenticate(peer.secret, 37, "Probe");
                    try (Proto info = session.deviceInfo()) { yes(info.number(2) == 2); }
                    rejects(() -> session.historyPastTargetScan(safe, 0));
                }
                yes(peer.fileFetches == 1 && peer.indexQueries == 1 && safe.files.size() <= 16);
                for (java.util.Map<String, Object> file : safe.files) yes(file.get("parser_status").equals("not_parsed"));
            }
        });
        test("Target scan looks past five mismatches but never parses or requests another index", () -> {
            Peer peer = new Peer(2); peer.historyOnly = true; peer.bulkScenario = "target_scan_sixth_exact";
            HistoryResults safe = new HistoryResults();
            try (Session session = new Session(peer, peer.output)) {
                session.negotiate(); session.authenticate(peer.secret, 37, "Probe");
                try (Proto info = session.deviceInfo()) { yes(info.number(2) == 2); }
                java.util.Map<String, Object> result = session.historyTargetScan(safe, 0);
                yes(result.get("status").equals("diagnostic_target_scan_exact") && result.get("retention_index_checked").equals(false));
            }
            yes(peer.commands.equals(List.of("1/26", "1/27", "2/2", "8/2", "8/1", "8/3")));
            yes(peer.fileFetches == 1 && safe.files.size() == 6);
            for (java.util.Map<String, Object> file : safe.files) yes(file.get("parser_status").equals("not_parsed"));
            yes(safe.files.get(5).get("file_id_match").equals(true) && safe.files.get(5).get("demux_route").equals("requested_exact"));
        });
        test("Target scan rejects sixth mismatch and malformed transfers with one fetch only", () -> {
            for (String scenario : List.of("target_scan_missing", "target_crc", "target_pad", "target_channel", "target_plain")) {
                Peer peer = new Peer(2); peer.historyOnly = true; peer.bulkScenario = scenario;
                HistoryResults safe = new HistoryResults();
                try (Session session = new Session(peer, peer.output)) {
                    session.negotiate(); session.authenticate(peer.secret, 37, "Probe");
                    try (Proto info = session.deviceInfo()) { yes(info.number(2) == 2); }
                    rejects(() -> session.historyTargetScan(safe, 0));
                }
                yes(peer.fileFetches == 1 && peer.indexQueries == 2);
                yes(safe.files.size() <= 6);
                for (java.util.Map<String, Object> file : safe.files) yes(file.get("parser_status").equals("not_parsed"));
            }
        });
        test("Past-only window sends exactly one index and observes before after and spanning files without parsing", () -> {
            for (String scenario : List.of("pastwin_silence", "pastwin_after", "pastwin_before", "pastwin_spanning", "pastwin_unknown", "pastwin_empty", "pastwin_duplicate", "pastwin_late_ack")) {
                Peer peer = new Peer(2); peer.historyOnly = true; peer.bulkScenario = scenario;
                HistoryResults safe = new HistoryResults();
                java.util.Map<String, Object> result = pastWindowRun(peer, safe);
                yes(result.equals(safe.boundary) && result.get("status").equals("diagnostic_past_window_elapsed"));
                yes(result.get("history_command_count").equals(1) && result.get("index_request_subtype").equals(2));
                yes(result.get("index_response_count").equals(1) && result.get("fetch_command_count").equals(0));
                yes(result.get("window_elapsed").equals(true) && result.get("observation_window_ms").equals(6000));
                yes(result.get("transport_boundary_at_deadline").equals("not_verified") && result.get("absence_of_stream_proven").equals(false));
                yes(result.get("today_response_transport_ack_seen") == null);
                yes(result.get("past_response_transport_ack_seen").equals(!scenario.equals("pastwin_late_ack")));
                yes(peer.commands.equals(List.of("1/26", "1/27", "2/2", "8/2")) && peer.fileFetches == 0 && peer.indexQueries == 1);
                yes(safe.stages.equals(List.of("history_past_window_observation")));
                yes(safe.files.size() == (scenario.equals("pastwin_silence") ? 0 : scenario.equals("pastwin_late_ack") ? 2 : 1));
                for (int i = 0; i < safe.files.size(); i++) {
                    java.util.Map<String, Object> file = safe.files.get(i);
                    boolean before = scenario.equals("pastwin_before") || scenario.equals("pastwin_spanning") || scenario.equals("pastwin_late_ack") && i == 0;
                    yes(file.get("boundary_observation_phase").equals(before ? "index_wait" : "post_index_response"));
                    yes(file.get("boundary_observation_phase_last_bulk").equals(before && !scenario.equals("pastwin_spanning") ? "index_wait" : "post_index_response"));
                    yes(file.get("boundary_subtype_first_bulk").equals(2) && file.get("boundary_subtype_last_bulk").equals(2));
                    yes(file.get("received_id_in_past_index").equals(!scenario.equals("pastwin_unknown") && !scenario.equals("pastwin_empty")) && file.get("received_id_in_today_index") == null);
                    yes(file.get("parser_status").equals("not_parsed") && file.get("crc_valid").equals(true));
                    yes(!file.containsKey("detail_records") && !file.containsKey("today_steps"));
                    yes(file.get("boundary_request_ack_seen_first_bulk").equals(!(scenario.equals("pastwin_late_ack") && i == 0)));
                }
                int beforeFrames = scenario.equals("pastwin_spanning") || scenario.equals("pastwin_before") || scenario.equals("pastwin_late_ack") ? 1 : 0;
                yes(result.get("bulk_frames_waiting_index").equals(beforeFrames));
                int postFrames = scenario.equals("pastwin_silence") || scenario.equals("pastwin_before") ? 0 : 1;
                yes(result.get("bulk_frames_post_index_response").equals(postFrames));
            }
        });
        test("Observed past-only shape has ten post-response CRC-valid files outside the snapshot without fetch or parser", () -> {
            Peer peer = new Peer(2); peer.historyOnly = true; peer.bulkScenario = "pastwin_hardware_shape";
            HistoryResults safe = new HistoryResults();
            java.util.Map<String, Object> result = pastWindowRun(peer, safe);
            yes(result.get("status").equals("diagnostic_past_window_elapsed") && result.get("history_command_count").equals(1));
            yes(result.get("bulk_frames_waiting_index").equals(0) && result.get("bulk_frames_post_index_response").equals(10));
            yes(result.get("bulk_frames").equals(10) && result.get("bulk_payload_bytes").equals(908));
            yes(result.get("complete_file_count").equals(10) && result.get("incomplete_file_present").equals(false));
            yes(peer.commands.equals(List.of("1/26", "1/27", "2/2", "8/2")) && peer.fileFetches == 0);
            yes(safe.files.size() == 10 && safe.files.stream().mapToInt(file -> (Integer) file.get("bytes")).sum() == 868);
            for (java.util.Map<String, Object> file : safe.files) {
                yes(file.get("crc_valid").equals(true) && file.get("received_padding_zero").equals(true));
                yes(file.get("received_id_in_past_index").equals(false) && file.get("received_id_in_today_index") == null);
                yes(file.get("boundary_observation_phase").equals("post_index_response") && file.get("boundary_observation_phase_last_bulk").equals("post_index_response"));
                yes(file.get("boundary_request_ack_seen_first_bulk").equals(true) && file.get("boundary_request_ack_seen_last_bulk").equals(true));
                yes(file.get("parser_status").equals("not_parsed") && !file.containsKey("detail_records"));
            }
        });
        test("Past-only deadline preserves partial file and SPP cutoff without resetting after response", () -> {
            for (String scenario : List.of("pastwin_partial", "pastwin_half_header", "pastwin_half_payload")) {
                Peer peer = new Peer(2); peer.historyOnly = true; peer.bulkScenario = scenario;
                HistoryResults safe = new HistoryResults();
                java.util.Map<String, Object> result = pastWindowRun(peer, safe);
                yes(result.get("window_elapsed").equals(true) && result.get("complete_file_count").equals(0));
                yes(result.get("transport_boundary_at_deadline").equals("not_verified"));
                boolean partial = scenario.equals("pastwin_partial");
                yes(result.get("incomplete_file_present").equals(partial) && safe.files.size() == (partial ? 1 : 0));
                if (partial) {
                    java.util.Map<String, Object> file = safe.files.get(0);
                    yes(file.get("boundary_observation_phase_last_bulk").equals("index_wait"));
                    yes(file.get("received_id_in_past_index") == null && file.get("boundary_request_ack_seen_last_bulk") == null);
                }
                yes(safe.stages.size() == 1 && peer.commands.equals(List.of("1/26", "1/27", "2/2", "8/2")));
            }
        });
        test("Past-only missing wrong duplicate or malformed index never becomes successful elapsed observation", () -> {
            for (String scenario : List.of("pastwin_missing", "pastwin_absent", "pastwin_invalid", "pastwin_wrong_index", "pastwin_duplicate_index")) {
                Peer peer = new Peer(2); peer.historyOnly = true; peer.bulkScenario = scenario;
                HistoryResults safe = new HistoryResults();
                rejects(() -> pastWindowRun(peer, safe));
                yes(!safe.boundary.get("status").equals("diagnostic_past_window_elapsed"));
                yes(safe.boundary.get("window_elapsed").equals(scenario.equals("pastwin_missing")));
                yes(peer.commands.equals(List.of("1/26", "1/27", "2/2", "8/2")) && peer.fileFetches == 0);
                if (scenario.equals("pastwin_missing") || scenario.equals("pastwin_absent") || scenario.equals("pastwin_invalid"))
                    yes(safe.files.get(0).get("received_id_in_past_index") == null);
            }
        });
        test("Past-only early timeout EOF cancellation malformed and bounded floods abort without another command", () -> {
            for (String scenario : List.of("pastwin_early_timeout", "pastwin_eof", "pastwin_cancel", "pastwin_crc", "pastwin_pad", "pastwin_order", "pastwin_plain", "pastwin_channel", "pastwin_files", "pastwin_frames", "pastwin_bytes", "pastwin_control_flood")) {
                Peer peer = new Peer(2); peer.historyOnly = true; peer.bulkScenario = scenario;
                HistoryResults safe = new HistoryResults();
                rejects(() -> pastWindowRun(peer, safe));
                yes(safe.boundary.get("window_elapsed").equals(false) && safe.files.size() <= 16);
                yes(safe.files.stream().allMatch(file -> file.get("parser_status").equals("not_parsed")));
                yes(peer.commands.equals(List.of("1/26", "1/27", "2/2", "8/2")) && peer.fileFetches == 0);
            }
        });
        test("Past-only command gate is closed before and after its single permitted send and cleans up failed observers", () -> {
            for (boolean failAtStart : new boolean[]{false, true}) {
                Peer peer = new Peer(2); peer.historyOnly = true; peer.bulkScenario = "pastwin_after";
                try (Session session = new Session(peer, peer.output)) {
                    session.negotiate(); session.authenticate(peer.secret, 37, "Probe");
                    try (Proto info = session.deviceInfo()) { yes(info.number(2) == 2); }
                    Session.HistoryObserver observer = new Session.HistoryObserver() {
                        private void gate() {
                            try {
                                rejects(session::battery); rejects(session::heartRateSettings); rejects(session::liveStart); rejects(session::liveStop);
                                java.lang.reflect.Method send = Session.class.getDeclaredMethod("send", byte[].class, boolean.class); send.setAccessible(true);
                                for (int sub : new int[]{1, 2, 3, 5, 11, 45, 46}) {
                                    byte[] command = Proto.command(8, sub);
                                    try { send.invoke(session, command, false); throw new AssertionError("past-window extra command accepted"); }
                                    catch (java.lang.reflect.InvocationTargetException expected) { yes(expected.getCause() instanceof ProbeException); }
                                    yes(Arrays.equals(command, new byte[command.length]));
                                }
                            } catch (Exception e) { throw new AssertionError(e); }
                        }
                        @Override public void onStage(String stage) { gate(); if (failAtStart) throw new IllegalStateException("synthetic start"); }
                        @Override public void onSelection(int count) { throw new AssertionError("no file selection"); }
                        @Override public void onBoundary(java.util.Map<String, Object> summary) { gate(); }
                        @Override public void onFile(java.util.Map<String, Object> summary) { throw new IllegalStateException("synthetic file observer"); }
                    };
                    try { session.historyPastWindow(observer, () -> peer.passiveNow); throw new AssertionError("observer accepted"); }
                    catch (IllegalStateException expected) { /* Resource cleanup still runs. */ }
                    for (String field : List.of("historyBoundary", "historyPastWindowActive", "historyPastWindowSendAllowed", "historyReadActive", "historySampleActive")) {
                        java.lang.reflect.Field state = Session.class.getDeclaredField(field); state.setAccessible(true);
                        yes(field.equals("historyBoundary") ? state.get(session) == null : !state.getBoolean(session));
                    }
                    yes(peer.commands.equals(failAtStart ? List.of("1/26", "1/27", "2/2") : List.of("1/26", "1/27", "2/2", "8/2")));
                }
            }
        });
        test("Past-only exact model firmware and SPPv2 guards precede any history command", () -> {
            for (int scenario = 0; scenario < 4; scenario++) {
                Peer peer = new Peer(scenario == 3 ? 1 : 2);
                if (scenario == 1) peer.deviceModel = "M2345B2";
                if (scenario == 2) peer.deviceFirmware = "2.3.99";
                try (Session session = new Session(peer, peer.output)) {
                    session.negotiate(); session.authenticate(peer.secret, 37, "Probe");
                    if (scenario != 0) try (Proto info = session.deviceInfo()) { yes(info.number(2) == 2); }
                    HistoryResults safe = new HistoryResults();
                    rejects(() -> session.historyPastWindow(safe, () -> 0));
                    yes(safe.stages.isEmpty() && peer.indexQueries == 0 && peer.fileFetches == 0 && !peer.commands.contains("8/2"));
                }
            }
        });
        test("Index boundary never fetches or parses and computes membership after both index snapshots", () -> {
            for (String scenario : List.of("boundary_normal", "boundary_unknown", "boundary_duplicate", "boundary_late_ack", "boundary_response_before_ack")) {
                Peer peer = new Peer(2); peer.historyOnly = true; peer.indexOnly = true; peer.bulkScenario = scenario;
                HistoryResults safe = new HistoryResults();
                java.util.Map<String, Object> result = boundaryRun(peer, safe);
                yes(result.equals(safe.boundary) && result.get("status").equals("diagnostic_index_boundary"));
                yes(peer.commands.equals(List.of("1/26", "1/27", "2/2", "8/2", "8/1")) && peer.fileFetches == 0);
                yes(safe.files.size() == 2 && result.get("complete_file_count").equals(2) && result.get("index_response_count").equals(2));
                yes(result.get("bulk_frames").equals(7) && result.get("bulk_payload_bytes").equals(HistoryTests.details().length + HistoryTests.summary().length + 28));
                boolean bulkAck = !scenario.equals("boundary_late_ack") && !scenario.equals("boundary_response_before_ack");
                yes(result.get("past_response_transport_ack_seen").equals(!scenario.equals("boundary_response_before_ack")));
                for (int i = 0; i < safe.files.size(); i++) {
                    java.util.Map<String, Object> file = safe.files.get(i);
                    yes(file.get("boundary_subtype_first_bulk").equals(i == 0 ? 2 : 1));
                    yes(file.get("boundary_request_ack_seen_first_bulk").equals(bulkAck));
                    yes(file.get("fetch_command_count").equals(0) && file.get("file_id_match") == null);
                    yes(file.get("parser_status").equals("not_parsed") && !file.containsKey("detail_records"));
                }
                yes(safe.files.get(0).get("received_id_in_past_index").equals(!scenario.equals("boundary_unknown")));
                yes(safe.files.get(0).get("received_id_in_today_index").equals(false));
                yes(safe.files.get(1).get("received_id_in_today_index").equals(true));
                yes(!safe.stages.contains("history_read_file"));
            }
        });
        test("Observed index-only ten-file shape crosses ACK with no index membership and no fetch or parser", () -> {
            Peer peer = new Peer(2); peer.historyOnly = true; peer.indexOnly = true; peer.bulkScenario = "boundary_hardware_shape";
            HistoryResults safe = new HistoryResults();
            java.util.Map<String, Object> result = boundaryRun(peer, safe);
            yes(result.get("fetch_command_count").equals(0) && result.get("bulk_frames").equals(10));
            yes(result.get("bulk_payload_bytes").equals(908) && result.get("complete_file_count").equals(10));
            yes(result.get("today_response_transport_ack_seen").equals(true) && result.get("past_response_transport_ack_seen").equals(true));
            yes(peer.commands.equals(List.of("1/26", "1/27", "2/2", "8/2", "8/1")) && peer.fileFetches == 0);
            yes(safe.files.size() == 10);
            for (int i = 0; i < 10; i++) {
                java.util.Map<String, Object> file = safe.files.get(i);
                yes(file.get("boundary_subtype_first_bulk").equals(1) && file.get("boundary_subtype_last_bulk").equals(1));
                yes(file.get("boundary_request_ack_seen_first_bulk").equals(i >= 7));
                yes(file.get("boundary_request_ack_seen_last_bulk").equals(i >= 7));
                yes(file.get("received_id_in_today_index").equals(false) && file.get("received_id_in_past_index").equals(false));
                yes(file.get("parser_status").equals("not_parsed") && file.get("crc_valid").equals(true));
            }
        });
        test("Boundary file spanning both index requests remains header-only with separate start and finish phases", () -> {
            Peer peer = new Peer(2); peer.historyOnly = true; peer.bulkScenario = "boundary_partial";
            HistoryResults safe = new HistoryResults();
            yes(boundaryRun(peer, safe).get("status").equals("diagnostic_index_boundary"));
            yes(safe.files.size() == 1 && safe.files.get(0).get("boundary_subtype_first_bulk").equals(2));
            yes(safe.files.get(0).get("boundary_subtype_last_bulk").equals(1));
            yes(safe.files.get(0).get("received_id_in_past_index").equals(true));
            yes(safe.files.get(0).get("parser_status").equals("not_parsed") && peer.fileFetches == 0);
        });
        test("Boundary stops at final index response without draining or claiming a quiet stream", () -> {
            for (String scenario : List.of("boundary_silent", "boundary_post_final", "boundary_tail")) {
                Peer peer = new Peer(2); peer.historyOnly = true; peer.bulkScenario = scenario;
                HistoryResults safe = new HistoryResults();
                java.util.Map<String, Object> result = boundaryRun(peer, safe);
                boolean partial = scenario.equals("boundary_tail");
                yes(result.get("status").equals(partial ? "diagnostic_index_boundary_incomplete" : "diagnostic_index_boundary"));
                yes(result.get("incomplete_file_present").equals(partial) && result.get("complete_file_count").equals(0));
                yes(safe.files.size() == (partial ? 1 : 0) && peer.fileFetches == 0);
                if (partial) yes(safe.files.get(0).get("received_id_in_past_index") == null);
                if (scenario.equals("boundary_post_final")) yes(!peer.input.isEmpty());
            }
        });
        test("Boundary fails closed on malformed bulk or bounded file frame and byte floods with zero fetches", () -> {
            for (String scenario : List.of("boundary_crc", "boundary_pad", "boundary_plain", "boundary_channel", "boundary_order", "boundary_files", "boundary_flood", "boundary_bytes")) {
                Peer peer = new Peer(2); peer.historyOnly = true; peer.bulkScenario = scenario;
                HistoryResults safe = new HistoryResults();
                rejects(() -> boundaryRun(peer, safe));
                yes(peer.fileFetches == 0 && peer.indexQueries == 1 && safe.boundary != null);
                yes(safe.files.stream().allMatch(file -> file.get("parser_status").equals("not_parsed")));
                yes(safe.files.size() <= 16 && !peer.commands.contains("8/5"));
            }
        });
        test("Boundary retains nullable partial membership on missing index timeout and cancellation", () -> {
            for (String scenario : List.of("boundary_missing_index", "boundary_invalid_index", "boundary_timeout", "boundary_cancel")) {
                Peer peer = new Peer(2); peer.historyOnly = true; peer.bulkScenario = scenario;
                HistoryResults safe = new HistoryResults();
                rejects(() -> boundaryRun(peer, safe));
                yes(peer.fileFetches == 0 && safe.boundary != null && !peer.commands.contains("8/5"));
                yes(safe.files.get(0).get("received_id_in_today_index") == null);
                yes(safe.files.get(0).get("parser_status").equals("not_parsed"));
                if (scenario.contains("index")) yes(safe.files.get(0).get("received_id_in_past_index").equals(true));
                else yes(safe.boundary.get("incomplete_file_present").equals(true));
            }
        });
        test("Target demux quarantines prefetch partial and postfetch files and parses exactly one requested target", () -> {
            for (String scenario : List.of("target_exact", "target_unmatched", "target_pre", "target_pre_exact", "target_pre_partial", "target_retention_bulk", "target_duplicate_transport")) {
                Peer peer = new Peer(2); peer.historyOnly = true; peer.bulkScenario = scenario;
                HistoryResults safe = new HistoryResults();
                java.util.Map<String, Object> result = targetRun(peer, safe, 0);
                yes(result.get("status").equals("verified_target_sample") && result.get("retention_index_unchanged").equals(true));
                yes(result.get("retention_index_checked").equals(true) && safe.selected == 1 && peer.fileFetches == 1);
                yes(peer.commands.equals(List.of("1/26", "1/27", "2/2", "8/2", "8/1", "8/3", "8/2")));
                int parsed = 0;
                for (java.util.Map<String, Object> summary : safe.files) {
                    if (summary.get("demux_route").equals("requested_exact")) {
                        parsed++;
                        yes(summary.get("file_id_match").equals(true) && summary.get("parser_status").equals("daily_details_v4"));
                        yes(summary.get("detail_records").equals(2) && summary.get("demux_file_phase").equals("target_wait"));
                    } else {
                        yes(summary.get("demux_route").equals("quarantined") && summary.get("parser_status").equals("not_parsed"));
                        yes(!summary.containsKey("detail_records") && !summary.containsKey("today_steps"));
                    }
                }
                yes(parsed == 1 && safe.stages.stream().filter("history_read_file"::equals).count() == 1);
                if (scenario.equals("target_pre_exact") || scenario.equals("target_pre_partial"))
                    yes(safe.files.get(0).get("file_id_match") == null && safe.files.get(0).get("demux_file_phase").equals("index_wait"));
            }
        });
        test("Observed ten-prefetch two-mismatch shape aborts safely without consuming a queued third target", () -> {
            Peer peer = new Peer(2); peer.historyOnly = true; peer.bulkScenario = "target_hardware_shape";
            HistoryResults safe = new HistoryResults();
            try { targetRun(peer, safe, 0); throw new AssertionError("two mismatches should abort"); }
            catch (ProbeException failure) { yes(failure.getMessage().equals("history_target_not_received")); }
            yes(safe.files.size() == 12 && peer.fileFetches == 1 && peer.indexQueries == 2 && !peer.input.isEmpty());
            for (int i = 0; i < safe.files.size(); i++) {
                java.util.Map<String, Object> file = safe.files.get(i);
                yes(file.get("crc_valid").equals(true) && file.get("received_padding_zero").equals(true));
                yes(file.get("demux_route").equals("quarantined") && file.get("parser_status").equals("not_parsed"));
                yes(file.get("demux_file_phase").equals(i < 10 ? "index_wait" : "target_wait"));
            }
            yes(safe.files.get(10).get("bytes").equals(418) && safe.files.get(11).get("bytes").equals(82));
            yes(!peer.commands.contains("8/5") && safe.stages.stream().filter("history_read_file"::equals).count() == 1);
        });
        test("Target demux never upgrades changed retention or unsupported target layout to verified sample", () -> {
            for (String scenario : List.of("target_retention_changed", "target_unsupported")) {
                Peer peer = new Peer(2); peer.historyOnly = true; peer.bulkScenario = scenario;
                HistoryResults safe = new HistoryResults();
                java.util.Map<String, Object> result = targetRun(peer, safe, 0);
                yes(result.get("status").equals(scenario.equals("target_unsupported") ? "parser_unsupported" : "retention_index_changed"));
                yes(result.get("retention_index_checked").equals(true) && peer.fileFetches == 1 && !peer.commands.contains("8/5"));
            }
        });
        test("Target demux refuses two mismatches and invalid requested file without retry retention or parser fallback", () -> {
            for (String scenario : List.of("target_two_unmatched", "target_crc", "target_pad", "target_channel", "target_plain")) {
                Peer peer = new Peer(2); peer.historyOnly = true; peer.bulkScenario = scenario;
                HistoryResults safe = new HistoryResults();
                rejects(() -> targetRun(peer, safe, 0));
                yes(peer.fileFetches == 1 && peer.indexQueries == 2 && !peer.commands.contains("8/5"));
                yes(safe.files.stream().noneMatch(file -> !file.get("parser_status").equals("not_parsed")));
                if (scenario.equals("target_two_unmatched")) yes(safe.files.size() == 2 && !peer.input.isEmpty());
            }
        });
        test("Target demux validates and bounds unsolicited traffic before the first fetch", () -> {
            for (String scenario : List.of("target_pre_crc", "target_pre_pad", "target_pre_plain", "target_pre_channel", "target_flood", "target_bytes")) {
                Peer peer = new Peer(2); peer.historyOnly = true; peer.bulkScenario = scenario;
                HistoryResults safe = new HistoryResults();
                rejects(() -> targetRun(peer, safe, 0));
                yes(peer.fileFetches == 0 && !peer.commands.contains("8/5"));
                yes(safe.files.stream().allMatch(file -> file.get("parser_status").equals("not_parsed")));
            }
        });
        test("Target demux retains header evidence across EOF timeout or cancellation and does not retry", () -> {
            for (String scenario : List.of("target_eof", "target_timeout", "target_cancel")) {
                Peer peer = new Peer(2); peer.historyOnly = true; peer.bulkScenario = scenario;
                HistoryResults safe = new HistoryResults();
                try { targetRun(peer, safe, 0); throw new AssertionError("missing target should abort"); }
                catch (IOException failure) {
                    if (scenario.equals("target_timeout")) yes(failure instanceof java.net.SocketTimeoutException);
                    if (scenario.equals("target_cancel")) yes(failure instanceof java.io.InterruptedIOException && !(failure instanceof java.net.SocketTimeoutException));
                }
                yes(peer.fileFetches == 1 && peer.indexQueries == 2 && safe.files.size() == 1);
                yes(safe.files.get(0).get("demux_route").equals("quarantined"));
                yes(safe.stages.stream().filter("history_read_file"::equals).count() == 1 && !peer.commands.contains("8/5"));
            }
        });
        test("Target demux cannot pass retention with an invalid or incomplete trailing quarantine", () -> {
            for (String scenario : List.of("target_retention_partial", "target_retention_crc")) {
                Peer peer = new Peer(2); peer.historyOnly = true; peer.bulkScenario = scenario;
                HistoryResults safe = new HistoryResults();
                rejects(() -> targetRun(peer, safe, 0));
                yes(peer.fileFetches == 1 && peer.indexQueries == 3 && !peer.commands.contains("8/5"));
                yes(safe.files.get(safe.files.size() - 1).get("demux_file_phase").equals("retention_wait"));
                yes(safe.files.get(safe.files.size() - 1).get("parser_status").equals("not_parsed"));
            }
        });
        test("Target demux counts prefetch and requested chunks against one byte budget", () -> {
            Peer peer = new Peer(2); peer.historyOnly = true; peer.bulkScenario = "target_combined_budget";
            HistoryResults safe = new HistoryResults();
            try { targetRun(peer, safe, 0); throw new AssertionError("combined budget should abort"); }
            catch (ProbeException failure) { yes(failure.getMessage().equals("history_bulk_byte_limit")); }
            yes(peer.fileFetches == 1 && peer.indexQueries == 2 && safe.files.size() == 2);
            yes(safe.files.stream().allMatch(file -> file.get("parser_status").equals("not_parsed")));
        });
        test("Demux closes and wipes target identity assembler identity and unfinished payload", () -> {
            HistoryResults safe = new HistoryResults();
            HistoryDemultiplexer demux = new HistoryDemultiplexer(safe);
            demux.request(HistoryTests.DETAILS_ID, null, null);
            demux.accept(HistoryTests.chunk(2, 1, Arrays.copyOfRange(HistoryTests.details(), 0, 15)), false);
            java.lang.reflect.Field targetField = HistoryDemultiplexer.class.getDeclaredField("target"); targetField.setAccessible(true);
            java.lang.reflect.Field fileField = HistoryDemultiplexer.class.getDeclaredField("file"); fileField.setAccessible(true);
            byte[] target = (byte[]) targetField.get(demux);
            HistoryFile file = (HistoryFile) fileField.get(demux);
            java.lang.reflect.Field bufferField = HistoryFile.class.getDeclaredField("buffer"); bufferField.setAccessible(true);
            java.lang.reflect.Field idField = HistoryFile.class.getDeclaredField("expectedId"); idField.setAccessible(true);
            byte[] buffer = (byte[]) bufferField.get(file), id = (byte[]) idField.get(file);
            demux.close();
            eq(target, new byte[target.length]); eq(id, new byte[id.length]); eq(buffer, new byte[buffer.length]);
            yes(safe.files.size() == 1 && safe.files.get(0).get("demux_route").equals("incomplete"));
            yes(safe.files.get(0).get("file_id_match") == null && safe.files.get(0).get("parser_status").equals("not_parsed"));
        });
        test("Demux cannot restart or interleave an unfinished unsolicited file with a requested file", () -> {
            for (int number : new int[]{1, 3}) {
                HistoryResults safe = new HistoryResults();
                try (HistoryDemultiplexer demux = new HistoryDemultiplexer(safe)) {
                    demux.accept(HistoryTests.chunk(2, 1, Arrays.copyOfRange(HistoryTests.details(), 0, 15)), true);
                    demux.request(HistoryTests.DETAILS_ID, null, null);
                    rejects(() -> demux.accept(HistoryTests.chunk(2, number, HistoryTests.details()), false));
                    yes(!demux.found());
                }
                yes(safe.files.size() == 1 && safe.files.get(0).get("demux_route").equals("incomplete"));
                yes(safe.files.get(0).get("parser_status").equals("not_parsed"));
            }
        });
        test("Demux bounds complete quarantines without parsing or treating early exact ID as a response", () -> {
            HistoryResults safe = new HistoryResults();
            try (HistoryDemultiplexer demux = new HistoryDemultiplexer(safe)) {
                for (int i = 0; i < 16; i++) demux.accept(HistoryTests.chunk(1, 1, HistoryTests.details()), false);
                rejects(() -> demux.accept(HistoryTests.chunk(1, 1, HistoryTests.details()), false));
                yes(safe.files.size() == 16 && !demux.found());
                yes(safe.files.stream().allMatch(file -> file.get("parser_status").equals("not_parsed")));
            }
        });
        test("Demux wipes unfinished buffers even when the observer fails during close", () -> {
            Session.HistoryObserver observer = new Session.HistoryObserver() {
                @Override public void onStage(String stage) {}
                @Override public void onSelection(int count) {}
                @Override public void onFile(java.util.Map<String, Object> summary) { throw new IllegalStateException("synthetic observer"); }
            };
            HistoryDemultiplexer demux = new HistoryDemultiplexer(observer);
            demux.accept(HistoryTests.chunk(2, 1, Arrays.copyOfRange(HistoryTests.details(), 0, 15)), true);
            java.lang.reflect.Field fileField = HistoryDemultiplexer.class.getDeclaredField("file"); fileField.setAccessible(true);
            HistoryFile file = (HistoryFile) fileField.get(demux);
            java.lang.reflect.Field bufferField = HistoryFile.class.getDeclaredField("buffer"); bufferField.setAccessible(true);
            byte[] buffer = (byte[]) bufferField.get(file);
            try { demux.close(); throw new AssertionError("observer failure should propagate"); }
            catch (IllegalStateException expected) { eq(buffer, new byte[buffer.length]); }
        });
        test("Target rejects invalid offset or unverified device before any history command", () -> {
            for (int offset : new int[]{-1, 256}) {
                Peer peer = new Peer(2); peer.historyOnly = true;
                rejects(() -> targetRun(peer, new HistoryResults(), offset));
                yes(!peer.commands.contains("8/2"));
            }
            Peer peer = new Peer(2); peer.historyOnly = true; peer.deviceFirmware = "unknown";
            rejects(() -> targetRun(peer, new HistoryResults(), 0));
            yes(!peer.commands.contains("8/2"));
        });
        test("History repeat requests the same first ID and detects bytes changed despite equal aggregates", () -> {
            for (String scenario : List.of("normal", "repeat_changed")) {
                Peer peer = new Peer(2); peer.historyOnly = true; peer.bulkScenario = scenario;
                HistoryResults safe = new HistoryResults();
                java.util.Map<String, Object> result = historyRun(peer, safe, true, true);
                boolean identical = scenario.equals("normal");
                yes(result.get("status").equals(identical ? "verified_sample" : "repeat_file_changed"));
                yes(result.get("repeat_requested").equals(true) && result.get("repeat_read_count").equals(2));
                yes(result.get("repeat_aggregate_consistent").equals(true));
                yes(result.get("repeat_bytes_identical").equals(identical));
                yes(safe.files.size() == 3 && result.get("retention_index_unchanged").equals(true));
                yes(peer.commands.stream().filter("8/3"::equals).count() == 3 && !peer.commands.contains("8/5"));
            }
            Peer peer = new Peer(2); peer.historyOnly = true;
            rejects(() -> historyRun(peer, new HistoryResults(), false, true));
            yes(!peer.commands.contains("8/1"));
        });
        test("History read incoming sequence wrap with one-byte activity chunks", () -> {
            Peer peer = new Peer(2); peer.historyOnly = true; peer.bulkScenario = "wrap";
            HistoryResults safe = new HistoryResults();
            yes(historyRun(peer, safe).get("status").equals("verified"));
            yes(safe.files.get(1).get("detail_records").equals(300) && peer.transportAcks > 256);
        });
        test("History read CRC ID ordering and wrong channel or plaintext abort no ACK commands", () -> {
            for (String scenario : List.of("crc", "wrong_id", "order", "plain", "wrong_channel", "overflow", "aggregate_budget")) {
                Peer peer = new Peer(2); peer.historyOnly = true; peer.bulkScenario = scenario;
                HistoryResults safe = new HistoryResults();
                rejects(() -> historyRun(peer, safe));
                yes(!peer.commands.contains("8/5") && !peer.commands.contains("8/2") && !peer.commands.contains("8/11"));
                yes(peer.indexQueries == 1); yes(!safe.files.isEmpty());
            }
        });
        test("History read retention change is reported without claiming global preservation", () -> {
            Peer peer = new Peer(2); peer.historyOnly = true; peer.bulkScenario = "retention_changed";
            java.util.Map<String, Object> result = historyRun(peer, new HistoryResults());
            yes(result.get("status").equals("retention_index_changed"));
            yes(result.get("retention_index_checked").equals(true) && result.get("retention_index_unchanged").equals(false));
        });
        test("History read unsupported layout keeps CRC evidence no fabricated detail metrics", () -> {
            Peer peer = new Peer(2); peer.historyOnly = true; peer.bulkScenario = "unsupported_layout";
            HistoryResults safe = new HistoryResults();
            yes(historyRun(peer, safe).get("status").equals("parser_unsupported"));
            yes(safe.files.get(1).get("crc_valid").equals(true) && !safe.files.get(1).containsKey("detail_records"));
        });
        test("History read unknown index categories never fetched no retries or past queries", () -> {
            Peer peer = new Peer(2); peer.historyOnly = true; peer.bulkScenario = "unknown_version";
            HistoryResults safe = new HistoryResults();
            yes(historyRun(peer, safe).get("status").equals("no_allowed_daily_files"));
            yes(safe.selected == 0 && safe.files.isEmpty());
            yes(peer.commands.equals(List.of("1/26", "1/27", "2/1", "2/2", "8/1")));
        });
        test("History active transport send gate allows index GETs and fetch only", () -> {
            Peer peer = new Peer(2); peer.historyOnly = true;
            try (Session session = new Session(peer, peer.output)) {
                session.negotiate(); session.authenticate(peer.secret, 37, "Probe");
                try (Proto info = session.deviceInfo()) { yes(info.number(2) == 2); }
                java.lang.reflect.Field active = Session.class.getDeclaredField("historyReadActive"); active.setAccessible(true);
                active.setBoolean(session, true);
                rejects(session::liveStart); rejects(session::liveStop); rejects(session::heartRateSettings);
                rejects(session::historyIndexPast); rejects(session::battery);
                java.lang.reflect.Method send = Session.class.getDeclaredMethod("send", byte[].class, boolean.class); send.setAccessible(true);
                for (int forbidden : new int[]{2, 5, 10, 11, 45, 46}) {
                    try { send.invoke(session, Proto.command(8, forbidden), false); throw new AssertionError("forbidden command accepted"); }
                    catch (java.lang.reflect.InvocationTargetException rejected) { yes(rejected.getCause() instanceof ProbeException); }
                }
                yes(peer.commands.equals(List.of("1/26", "1/27", "2/2")));
            }
        });
        test("History read exact model firmware and SPPv2 guards before any index or fetch", () -> {
            for (int scenario = 0; scenario < 4; scenario++) {
                Peer peer = new Peer(scenario == 3 ? 1 : 2); peer.historyOnly = true;
                if (scenario == 1) peer.deviceModel = "M2345B2";
                if (scenario == 2) peer.deviceFirmware = "2.3.99";
                try (Session session = new Session(peer, peer.output)) {
                    session.negotiate(); session.authenticate(peer.secret, 37, "Probe");
                    if (scenario != 0) try (Proto info = session.deviceInfo()) { yes(info.number(2) == 2); }
                    rejects(() -> session.historyRead(new HistoryResults()));
                    yes(!peer.commands.contains("8/1") && !peer.commands.contains("8/3"));
                }
            }
        });
        System.out.println("RESULT: " + passed + " tests passed; no hardware or production project touched");
    }
    private static final class HistoryResults implements Session.HistoryObserver {
        final List<java.util.Map<String, Object>> files = new ArrayList<>();
        final List<String> stages = new ArrayList<>();
        java.util.Map<String, Object> boundary;
        int clockReads;
        long referenceTime = -1L;
        @Override public long referenceTimeMillis() { clockReads++; return referenceTime; }
        @Override public void onBoundary(java.util.Map<String, Object> summary) { boundary = summary; }
        int selected, omitted, detailCandidates;
        Boolean requestedCountSatisfied;
        boolean sample;
        @Override public void onStage(String stage) { stages.add(stage); }
        @Override public void onSelection(int count) { selected = count; }
        @Override public void onCoverage(int candidates, int omitted, boolean sample) { this.omitted = omitted; this.sample = sample; }
        @Override public void onDetailCoverage(int candidates, Boolean satisfied) {
            detailCandidates = candidates; requestedCountSatisfied = satisfied;
        }
        @Override public void onFile(java.util.Map<String, Object> summary) { files.add(summary); }
    }
    private static java.util.Map<String, Object> historyRun(Peer peer, HistoryResults safe) throws Exception {
        return historyRun(peer, safe, false);
    }
    private static java.util.Map<String, Object> historyRun(Peer peer, HistoryResults safe, boolean sample) throws Exception {
        return historyRun(peer, safe, sample, false);
    }
    private static java.util.Map<String, Object> historyRun(Peer peer, HistoryResults safe, boolean sample, boolean repeatFirst) throws Exception {
        return historyRun(peer, safe, sample, repeatFirst, -1, 1);
    }
    private static java.util.Map<String, Object> historyRun(Peer peer, HistoryResults safe, boolean sample, boolean repeatFirst, int offset, int count) throws Exception {
        return historyRun(peer, safe, sample, repeatFirst, offset, count, false);
    }
    private static java.util.Map<String, Object> historyRun(Peer peer, HistoryResults safe, boolean sample, boolean repeatFirst, int offset, int count, boolean pastDetails) throws Exception {
        try (Session session = new Session(peer, peer.output)) {
            session.negotiate(); session.authenticate(peer.secret, 37, "Probe");
            try (Proto battery = session.battery()) { yes(battery.number(1) == 2); }
            try (Proto info = session.deviceInfo()) { yes(info.number(2) == 2); }
            return session.historyRead(safe, sample, repeatFirst, offset, count, pastDetails);
        }
    }
    private static java.util.Map<String, Object> correlateRun(Peer peer, HistoryResults safe, int offset) throws Exception {
        try (Session session = new Session(peer, peer.output)) {
            session.negotiate(); session.authenticate(peer.secret, 37, "Probe");
            try (Proto info = session.deviceInfo()) { yes(info.number(2) == 2); }
            return session.historyCorrelate(safe, offset);
        }
    }
    private static java.util.Map<String, Object> passiveRun(Peer peer, HistoryResults safe) throws Exception {
        try (Session session = new Session(peer, peer.output)) {
            session.negotiate(); session.authenticate(peer.secret, 37, "Probe");
            try (Proto info = session.deviceInfo()) { yes(info.number(2) == 2); }
            peer.passiveTraffic();
            return session.historyPassive(safe, () -> peer.passiveNow);
        }
    }
    private static java.util.Map<String, Object> pastWindowRun(Peer peer, HistoryResults safe) throws Exception {
        try (Session session = new Session(peer, peer.output)) {
            session.negotiate(); session.authenticate(peer.secret, 37, "Probe");
            try (Proto info = session.deviceInfo()) { yes(info.number(2) == 2); }
            return session.historyPastWindow(safe, () -> peer.passiveNow);
        }
    }
    private static java.util.Map<String, Object> boundaryRun(Peer peer, HistoryResults safe) throws Exception {
        try (Session session = new Session(peer, peer.output)) {
            session.negotiate(); session.authenticate(peer.secret, 37, "Probe");
            try (Proto info = session.deviceInfo()) { yes(info.number(2) == 2); }
            return session.historyBoundary(safe);
        }
    }
    private static java.util.Map<String, Object> targetRun(Peer peer, HistoryResults safe, int offset) throws Exception {
        try (Session session = new Session(peer, peer.output)) {
            session.negotiate(); session.authenticate(peer.secret, 37, "Probe");
            try (Proto info = session.deviceInfo()) { yes(info.number(2) == 2); }
            return session.historyTarget(safe, offset);
        }
    }
    private static java.util.Map<String, Object> pastTargetRun(Peer peer, HistoryResults safe, int offset) throws Exception {
        try (Session session = new Session(peer, peer.output)) {
            session.negotiate(); session.authenticate(peer.secret, 37, "Probe");
            try (Proto info = session.deviceInfo()) { yes(info.number(2) == 2); }
            return session.historyPastTarget(safe, offset);
        }
    }
    private static java.util.Map<String, Object> pastRepeatRun(Peer peer, HistoryResults safe, int offset) throws Exception {
        try (Session session = new Session(peer, peer.output)) {
            session.negotiate(); session.authenticate(peer.secret, 37, "Probe");
            try (Proto info = session.deviceInfo()) { yes(info.number(2) == 2); }
            return session.historyPastRepeat(safe, offset);
        }
    }
    private static HeartRateSettings settings(byte[] hr) throws Exception {
        byte[] response = new Proto.Writer().number(1, 8).number(2, 10)
                .bytes(10, new Proto.Writer().bytes(8, hr).build()).build();
        try (Proto command = Proto.parse(response)) { return HeartRateSettings.parse(command); }
    }
    private static void simulate(int version) throws Exception {
        Peer peer = new Peer(version);
        try (Session session = new Session(peer, peer.output)) {
            int[] metadataCalls = {0};
            yes(session.negotiate(metadata -> metadataCalls[0]++) == version);
            session.authenticate(peer.secret, 37, "Probe");
            try (Proto battery = session.battery(); Proto system = battery.message(4);
                 Proto power = system.message(2); Proto data = power.message(1)) { yes(data.number(1) == 73); }
            try (Proto info = session.deviceInfo(); Proto system = info.message(4); Proto data = system.message(3)) {
                yes(data.text(2).equals("2.3.98") && data.text(4).equals("M2345B1"));
            }
            try {
                session.liveStart();
                try (Proto sample = session.nextLive(); Proto health = sample.message(10); Proto stats = health.message(39)) {
                    yes(stats.number(4) == 67 && stats.number(1) == 1234);
                }
            } finally { session.liveStop(); }
            yes(peer.commands.equals(List.of("1/26", "1/27", "2/1", "2/2", "8/45", "8/46")));
            yes(peer.companionVerified);
            yes(metadataCalls[0] == 1);
        }
    }

    /** A scripted peer is not hardware evidence; it verifies finite client behavior and command allowlist. */
    private static final class Peer extends InputStream {
        final int version;
        final byte[] secret = hex("000102030405060708090a0b0c0d0e0f");
        final byte[] watch = hex("202122232425262728292a2b2c2d2e2f");
        final Deque<Integer> input = new ArrayDeque<>();
        final List<String> commands = new ArrayList<>();
        final List<byte[]> requestedIds = new ArrayList<>();
        byte[] phone, keys;
        int seq;
        boolean companionVerified, encryptedConfirmation, badProof, nack, sequenceGap, rejectConfirmation, settingsOnly, indexOnly, historyOnly;
        String bulkScenario = "normal";
        int indexQueries, transportAcks, fileFetches;
        boolean passive;
        long passiveNow;
        String deviceModel = "M2345B1", deviceFirmware = "2.3.98";
        final OutputStream output = new OutputStream() {
            @Override public void write(int value) { throw new AssertionError("unexpected scalar write"); }
            @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                try { handle(Arrays.copyOfRange(bytes, offset, offset + length)); }
                catch (GeneralSecurityException e) { throw new IOException("synthetic peer crypto failure"); }
            }
        };
        Peer(int version) { this.version = version; }
        private int emptyRead() throws IOException {
            if (passive || bulkScenario.startsWith("pastwin")) {
                if (bulkScenario.equals("passive_cancel") || bulkScenario.equals("pastwin_cancel")) throw new java.io.InterruptedIOException();
                if (bulkScenario.equals("passive_eof") || bulkScenario.equals("pastwin_eof")) return -1;
                passiveNow = bulkScenario.equals("passive_early_timeout") || bulkScenario.equals("pastwin_early_timeout") ? 5999 : 6000;
                throw new java.net.SocketTimeoutException();
            }
            if (bulkScenario.equals("correlate_timeout") || bulkScenario.equals("target_timeout") || bulkScenario.equals("boundary_timeout")
                    || bulkScenario.equals("pastrepeat_timeout") && fileFetches == 2) throw new java.net.SocketTimeoutException();
            if (bulkScenario.equals("correlate_cancel") || bulkScenario.equals("target_cancel") || bulkScenario.equals("boundary_cancel")
                    || bulkScenario.equals("pastrepeat_cancel") && fileFetches == 2) throw new java.io.InterruptedIOException();
            return -1;
        }
        @Override public int read() throws IOException { return input.isEmpty() ? emptyRead() : input.removeFirst(); }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            if (input.isEmpty()) return emptyRead();
            int count = Math.min(Math.min(3, length), input.size());
            for (int i = 0; i < count; i++) bytes[offset + i] = (byte) (int) input.removeFirst();
            return count;
        }
        private void offer(byte[] frame) { for (byte b : frame) input.addLast(b & 255); }
        private byte[] sendKey() { return Arrays.copyOfRange(keys, 16, 32); }
        private byte[] receiveKey() { return Arrays.copyOfRange(keys, 0, 16); }
        private byte[] nonce(int prefixOffset, int counter) {
            byte[] result = new byte[12]; System.arraycopy(keys, prefixOffset, result, 0, 4);
            for (int i = 0; i < 4; i++) result[8 + i] = (byte) (counter >>> (8 * i));
            return result;
        }
        private void handle(byte[] frameBytes) throws IOException, GeneralSecurityException {
            if (Arrays.equals(frameBytes, Wire.versionRequest())) {
                offer(Wire.v1(0, 0x80, 2, 0, 0, new byte[]{(byte) version, 0, 0})); return;
            }
            try (Wire.Frame frame = version == 1 ? Wire.readV1(new ByteArrayInputStream(frameBytes))
                    : Wire.readV2(new ByteArrayInputStream(frameBytes))) {
                if (version == 2 && frame.type == 2) {
                    byte[] response = frame.payload.clone(); response[0] = 2;
                    offer(Wire.v2(2, 0, response)); return;
                }
                if (version == 2 && frame.type == 1) { transportAcks++; return; }
                boolean encrypted = version == 1 ? frame.dataType == 1 : frame.payload[1] == 2;
                byte[] encoded = version == 1 ? frame.payload : Arrays.copyOfRange(frame.payload, 2, frame.payload.length);
                boolean deferAck = bulkScenario.startsWith("boundary") && commands.size() >= 3
                        && (bulkScenario.equals("boundary_late_ack") || bulkScenario.equals("boundary_response_before_ack")
                        || bulkScenario.equals("boundary_hardware_shape") && commands.size() >= 4)
                        || bulkScenario.equals("pastwin_late_ack") && commands.size() >= 3;
                if (version == 2 && !deferAck) offer(Wire.v2(1, frame.sequence, new byte[0]));
                byte[] bytes = encoded;
                if (encrypted) {
                    bytes = version == 2 ? Crypto.ctr(sendKey(), sendKey(), encoded)
                            : Crypto.ccmDecrypt(sendKey(), nonce(36, Wire.le16(encoded, 0)), Arrays.copyOfRange(encoded, 2, encoded.length), 4);
                }
                try (Proto command = Proto.parse(bytes)) {
                    int type = (int) command.number(1), sub = (int) command.number(2);
                    commands.add(type + "/" + sub);
                    if (settingsOnly && !List.of("1/26", "1/27", "2/1", "2/2", "8/10").contains(type + "/" + sub))
                        throw new AssertionError("settings command outside read-only allowlist");
                    if (type == 8 && sub == 10) eq(bytes, hex("0808100a"));
                    if (indexOnly && !List.of("1/26", "1/27", "2/1", "2/2", "8/1", "8/2").contains(type + "/" + sub))
                        throw new AssertionError("index command outside read-only allowlist");
                    if (historyOnly && !List.of("1/26", "1/27", "2/1", "2/2", "8/1", "8/2", "8/3").contains(type + "/" + sub))
                        throw new AssertionError("history read command outside strict no-history-ACK allowlist");
                    if (type == 8 && sub == 1) eq(bytes, hex("0808100152042a020800"));
                    if (type == 8 && sub == 2) eq(bytes, hex("08081002"));
                    if (type == 1 && sub == 26) {
                        try (Proto account = command.message(3); Proto verify = account.message(30)) { phone = verify.bytes(1).clone(); }
                        keys = Crypto.hkdf(Crypto.join(phone, watch), secret, ascii("miwear-auth"), 64);
                        if (nack) { offer(Wire.v2(0, frame.sequence, new byte[0])); return; }
                        byte[] proof = Crypto.hmac(receiveKey(), Crypto.join(watch, phone));
                        if (badProof) proof[0] ^= 1;
                        if (sequenceGap) seq++;
                        byte[] verify = new Proto.Writer().bytes(1, watch).bytes(2, proof).build();
                        reply(new Proto.Writer().number(1, 1).number(2, 26).bytes(3, new Proto.Writer().bytes(31, verify).build()).build(), false);
                    } else if (type == 1 && sub == 27) {
                        try (Proto account = command.message(3); Proto confirm = account.message(32)) {
                            eq(confirm.bytes(1), Crypto.hmac(sendKey(), Crypto.join(phone, watch)));
                            byte[] info = Crypto.ccmDecrypt(sendKey(), nonce(36, 0), confirm.bytes(2), 4);
                            try (Proto decoded = Proto.parse(info)) { yes(decoded.number(1) == 0 && decoded.number(4) == 224); }
                            companionVerified = true;
                        }
                        byte[] confirmation = rejectConfirmation ? new Proto.Writer().number(1, 1).number(2, 27).number(100, 1).build()
                                : Proto.command(1, 27);
                        reply(confirmation, encryptedConfirmation);
                    } else if (type == 2 && sub == 1) {
                        byte[] level = new Proto.Writer().number(1, 73).build();
                        byte[] power = new Proto.Writer().bytes(1, level).build();
                        reply(new Proto.Writer().number(1, 2).number(2, 1)
                                .bytes(4, new Proto.Writer().bytes(2, power).build()).build(), true);
                    } else if (type == 2 && sub == 2) {
                        byte[] info = new Proto.Writer().text(1, "not-retained").text(2, deviceFirmware).text(4, deviceModel).build();
                        reply(new Proto.Writer().number(1, 2).number(2, 2)
                                .bytes(4, new Proto.Writer().bytes(3, info).build()).build(), true);
                    } else if (type == 8 && (sub == 1 || sub == 2)) {
                        byte[] ids = hex("01020304fc010599887766000105");
                        if (historyOnly) {
                            indexQueries++;
                            ids = Crypto.join(HistoryTests.DETAILS_ID, HistoryTests.SUMMARY_ID);
                            if (bulkScenario.equals("unknown_version")) ids = hex("01020304000900");
                            if (indexQueries > 1) ids = bulkScenario.equals("retention_changed") ? HistoryTests.DETAILS_ID
                                    : Crypto.join(HistoryTests.SUMMARY_ID, HistoryTests.DETAILS_ID);
                            if (bulkScenario.equals("many_details") || bulkScenario.startsWith("batch")) {
                                ids = Crypto.join(HistoryTests.DETAILS_ID, HistoryTests.SUMMARY_ID);
                                for (int i = 0; i < 9; i++) {
                                    byte[] extra = HistoryTests.DETAILS_ID.clone(); extra[0] = (byte) (i + 10);
                                    ids = Crypto.join(ids, extra);
                                }
                                if (bulkScenario.startsWith("batch") && sub == 1 && indexQueries > 2) {
                                    if (bulkScenario.equals("batch_retention_changed")) ids = Arrays.copyOf(ids, ids.length - 7);
                                    else {
                                        byte[] first = Arrays.copyOfRange(ids, 0, 7);
                                        System.arraycopy(ids, 7, ids, 0, ids.length - 7);
                                        System.arraycopy(first, 0, ids, ids.length - 7, 7);
                                    }
                                }
                            }
                        }
                        if (bulkScenario.startsWith("pastwin")) {
                            byte[] data = HistoryTests.details();
                            if (bulkScenario.equals("pastwin_unknown")) { data[0] = 42; HistoryTests.crc(data); }
                            if (bulkScenario.equals("pastwin_crc")) data[data.length - 1] ^= 1;
                            if (bulkScenario.equals("pastwin_pad")) { data[7] = 1; HistoryTests.crc(data); }
                            if (List.of("pastwin_before", "pastwin_late_ack", "pastwin_missing", "pastwin_absent", "pastwin_invalid").contains(bulkScenario))
                                activityChunk(1, 1, data, 5, true);
                            if (bulkScenario.equals("pastwin_spanning") || bulkScenario.equals("pastwin_partial"))
                                activityChunk(2, 1, Arrays.copyOfRange(data, 0, 15), 5, true);
                            if (bulkScenario.equals("pastwin_missing")) return;
                            if (bulkScenario.equals("pastwin_wrong_index")) { reply(Proto.command(8, sub == 2 ? 1 : 2), true); return; }
                            byte[] windowIds = bulkScenario.equals("pastwin_invalid") ? new byte[8]
                                    : bulkScenario.equals("pastwin_empty") ? new byte[0] : HistoryTests.DETAILS_ID;
                            byte[] index = new Proto.Writer().number(1, 8).number(2, sub).bytes(10,
                                    bulkScenario.equals("pastwin_absent") ? new byte[0] : new Proto.Writer().bytes(2, windowIds).build()).build();
                            reply(index, true);
                            if (bulkScenario.equals("pastwin_duplicate_index")) { reply(index, true); return; }
                            if (bulkScenario.equals("pastwin_late_ack")) offer(Wire.v2(1, frame.sequence, new byte[0]));
                            if (bulkScenario.equals("pastwin_hardware_shape")) {
                                for (int i = 0; i < 10; i++) {
                                    byte[] synthetic = HistoryTests.file(new byte[]{42, 2, 3, 4, 0, 4, 0}, new byte[i == 6 ? 118 : 70]);
                                    activityChunk(1, 1, synthetic, 5, true);
                                }
                            } else if (bulkScenario.equals("pastwin_spanning")) activityChunk(2, 2, Arrays.copyOfRange(data, 15, data.length), 5, true);
                            else if (List.of("pastwin_silence", "pastwin_before", "pastwin_partial", "pastwin_early_timeout", "pastwin_eof", "pastwin_cancel").contains(bulkScenario)) return;
                            else if (bulkScenario.equals("pastwin_half_header") || bulkScenario.equals("pastwin_half_payload")) {
                                byte[] packet = Wire.v2(3, seq++, Crypto.join(new byte[]{5, 2}, Crypto.ctr(receiveKey(), receiveKey(), new byte[20])));
                                offer(Arrays.copyOf(packet, bulkScenario.equals("pastwin_half_header") ? 5 : 15));
                            } else if (bulkScenario.equals("pastwin_files")) for (int i = 0; i < 17; i++) activityChunk(1, 1, data, 5, true);
                            else if (bulkScenario.equals("pastwin_frames")) for (int i = 1; i <= 33; i++) activityChunk(34, i, new byte[]{1}, 5, true);
                            else if (bulkScenario.equals("pastwin_bytes")) activityFile(new byte[HistoryFile.MAX_BYTES + 1], 5, true);
                            else if (bulkScenario.equals("pastwin_control_flood")) for (int i = 0; i < 33; i++) reply(Proto.command(2, 3), true);
                            else if (bulkScenario.equals("pastwin_order")) activityChunk(2, 2, data, 5, true);
                            else activityChunk(1, 1, data, bulkScenario.equals("pastwin_channel") ? 2 : 5, !bulkScenario.equals("pastwin_plain"));
                            return;
                        }
                        if (bulkScenario.startsWith("boundary")) {
                            ids = sub == 2 ? HistoryTests.DETAILS_ID : HistoryTests.SUMMARY_ID;
                            if (bulkScenario.equals("boundary_invalid_index") && sub == 1) ids = new byte[8];
                            byte[] data = HistoryTests.details();
                            if (bulkScenario.equals("boundary_unknown")) { data[0] = 42; HistoryTests.crc(data); }
                            if (bulkScenario.equals("boundary_crc")) data[data.length - 1] ^= 1;
                            if (bulkScenario.equals("boundary_pad")) { data[7] = 1; HistoryTests.crc(data); }
                            if (bulkScenario.equals("boundary_hardware_shape") && sub == 1) {
                                for (int i = 0; i < 10; i++) {
                                    if (i == 7) offer(Wire.v2(1, frame.sequence, new byte[0]));
                                    byte[] synthetic = HistoryTests.file(new byte[]{42, 2, 3, 4, 0, 4, 0}, new byte[i == 6 ? 118 : 70]);
                                    activityChunk(1, 1, synthetic, 5, true);
                                }
                            }
                            if (!List.of("boundary_silent", "boundary_post_final", "boundary_hardware_shape").contains(bulkScenario)) {
                                if (bulkScenario.equals("boundary_partial") || bulkScenario.equals("boundary_tail")
                                        || bulkScenario.equals("boundary_timeout") || bulkScenario.equals("boundary_cancel")) {
                                    if (sub == 2) activityChunk(2, 1, Arrays.copyOfRange(data, 0, 15), 5, true);
                                    else if (bulkScenario.equals("boundary_partial")) activityChunk(2, 2, Arrays.copyOfRange(data, 15, data.length), 5, true);
                                } else if (sub == 2) {
                                    if (bulkScenario.equals("boundary_files")) for (int i = 0; i < 17; i++) activityChunk(1, 1, data, 5, true);
                                    else if (bulkScenario.equals("boundary_flood")) {
                                        for (int i = 1; i <= 33; i++) activityChunk(34, i, new byte[]{1}, 5, true);
                                    } else if (bulkScenario.equals("boundary_bytes")) activityFile(new byte[HistoryFile.MAX_BYTES + 1], 5, true);
                                    else if (bulkScenario.equals("boundary_order")) activityChunk(2, 2, data, 5, true);
                                    else activityFile(data, bulkScenario.equals("boundary_channel") ? 2 : 5, !bulkScenario.equals("boundary_plain"));
                                } else activityFile(HistoryTests.summary(), 5, true);
                            }
                            if (sub == 2 && List.of("boundary_timeout", "boundary_cancel").contains(bulkScenario)) return;
                            byte[] health = bulkScenario.equals("boundary_missing_index") && sub == 1 ? new byte[0]
                                    : new Proto.Writer().bytes(2, ids).build();
                            if (deferAck && bulkScenario.equals("boundary_late_ack")) offer(Wire.v2(1, frame.sequence, new byte[0]));
                            reply(new Proto.Writer().number(1, 8).number(2, sub).bytes(10, health).build(), true);
                            if (deferAck && bulkScenario.equals("boundary_response_before_ack")) offer(Wire.v2(1, frame.sequence, new byte[0]));
                            if (sub == 1 && bulkScenario.equals("boundary_post_final")) activityFile(data, 5, true);
                            return;
                        }
                        if (bulkScenario.startsWith("archive")) {
                            byte[] next = HistoryTests.DETAILS_ID.clone(); next[0] = 10;
                            ids = Crypto.join(HistoryTests.DETAILS_ID, Crypto.join(HistoryTests.SUMMARY_ID, next));
                            if (sub == 1) { ids = HistoryTests.DETAILS_ID.clone(); ids[0] = 42; }
                            if (sub == 2 && indexQueries > 2 && bulkScenario.equals("archive_retention_changed")) ids = next;
                        }
                        if (bulkScenario.startsWith("provenance")) {
                            ids = HistoryTests.DETAILS_ID.clone();
                            if (sub == 2) {
                                ids[0] = 42;
                                if (bulkScenario.equals("provenance_absent")) ids = new byte[0];
                                if (bulkScenario.equals("provenance_invalid_index")) ids = new byte[8];
                            }
                        }
                        if (bulkScenario.startsWith("target") && indexQueries > 2 && bulkScenario.equals("target_retention_changed")) ids = HistoryTests.SUMMARY_ID;
                        if (bulkScenario.startsWith("target") && indexQueries == 1) {
                            byte[] unsolicited = HistoryTests.details(); unsolicited[0] = 42; HistoryTests.crc(unsolicited);
                            if (bulkScenario.equals("target_pre_exact")) unsolicited = HistoryTests.details();
                            if (bulkScenario.equals("target_pre_crc")) unsolicited[unsolicited.length - 1] ^= 1;
                            if (bulkScenario.equals("target_pre_pad")) { unsolicited[7] = 1; HistoryTests.crc(unsolicited); }
                            if (bulkScenario.equals("target_pre_partial"))
                                activityChunk(2, 1, Arrays.copyOfRange(unsolicited, 0, 15), 5, true);
                            else if (bulkScenario.startsWith("target_pre"))
                                activityFile(unsolicited, bulkScenario.equals("target_pre_channel") ? 2 : 5, !bulkScenario.equals("target_pre_plain"));
                            if (bulkScenario.equals("target_hardware_shape")) for (int i = 0; i < 10; i++) {
                                byte[] synthetic = HistoryTests.file(new byte[]{42, 2, 3, 4, 0, 4, 0}, new byte[i % 2 == 0 ? 86 : 70]);
                                activityChunk(1, 1, synthetic, 5, true);
                            }
                            if (bulkScenario.equals("target_flood")) for (int i = 0; i < 17; i++) activityFile(unsolicited, 5, true);
                            if (bulkScenario.equals("target_bytes")) activityFile(new byte[HistoryFile.MAX_BYTES + 1], 5, true);
                            if (bulkScenario.equals("target_combined_budget"))
                                activityFile(HistoryTests.file(HistoryTests.DETAILS_ID, new byte[HistoryFile.MAX_BYTES / 2 - 12]), 5, true);
                        }
                        if (bulkScenario.startsWith("target") && indexQueries > 2) {
                            byte[] leftover = HistoryTests.details(); leftover[0] = 42; HistoryTests.crc(leftover);
                            if (bulkScenario.equals("target_retention_partial")) activityChunk(2, 1, Arrays.copyOfRange(leftover, 0, 15), 5, true);
                            if (bulkScenario.equals("target_retention_crc")) { leftover[leftover.length - 1] ^= 1; activityFile(leftover, 5, true); }
                            if (bulkScenario.equals("target_retention_bulk")) { activityFile(leftover, 5, true); activityFile(HistoryTests.details(), 5, true); }
                        }
                        if (bulkScenario.startsWith("pastrepeat")) {
                            byte[] leftover = HistoryTests.file(new byte[]{42, 2, 3, 4, 0, 4, 0}, new byte[]{1});
                            if (indexQueries == 1 && bulkScenario.equals("pastrepeat_pre_exact")) activityFile(HistoryTests.details(), 5, true);
                            if (indexQueries == 2) {
                                if (bulkScenario.equals("pastrepeat_retention_changed")) ids = HistoryTests.SUMMARY_ID;
                                if (bulkScenario.equals("pastrepeat_retention_duplicate")) ids = Crypto.join(HistoryTests.DETAILS_ID, HistoryTests.DETAILS_ID);
                                if (bulkScenario.equals("pastrepeat_retention_partial")) activityChunk(2, 1, Arrays.copyOfRange(leftover, 0, 9), 5, true);
                                else if (!bulkScenario.equals("pastrepeat_observed_shape"))
                                    for (int i = 0; i < (bulkScenario.equals("pastrepeat_retention_flood") ? 5 : 3); i++) activityFile(leftover, 5, true);
                            }
                        }
                        if (bulkScenario.startsWith("pasttarget") && indexQueries == 2) {
                            if (bulkScenario.equals("pasttarget_retention_changed")) ids = HistoryTests.SUMMARY_ID;
                            if (bulkScenario.equals("pasttarget_retention_duplicate")) ids = Crypto.join(HistoryTests.DETAILS_ID, HistoryTests.DETAILS_ID);
                            if (bulkScenario.equals("pasttarget_retention_invalid")) ids = new byte[8];
                            byte[] leftover = HistoryTests.file(new byte[]{42, 2, 3, 4, 0, 4, 0}, new byte[]{1});
                            if (bulkScenario.equals("pasttarget_retention_crc")) { leftover[leftover.length - 1] ^= 1; activityFile(leftover, 5, true); }
                            if (bulkScenario.equals("pasttarget_retention_pad")) { leftover[7] = 1; HistoryTests.crc(leftover); activityFile(leftover, 5, true); }
                            if (bulkScenario.equals("pasttarget_retention_partial")) activityChunk(2, 1, Arrays.copyOfRange(leftover, 0, 9), 5, true);
                            if (bulkScenario.equals("pasttarget_retention_bulk")) activityFile(leftover, 5, true);
                            if (bulkScenario.equals("pasttarget_observed_shape")) for (int i = 0; i < 3; i++) activityFile(leftover, 5, true);
                            if (bulkScenario.equals("pasttarget_retention_flood")) for (int i = 0; i < 6; i++) activityFile(leftover, 5, true);
                        }
                        byte[] health = sub == 2 && (bulkScenario.equals("provenance_missing_index_field")
                                || bulkScenario.equals("pasttarget_retention_absent") && indexQueries == 2) ? new byte[0]
                                : new Proto.Writer().bytes(2, ids).build();
                        if (bulkScenario.equals("correlate_prebulk") && sub == 1) activityFile(HistoryTests.details(), 5, true);
                        if (bulkScenario.equals("correlate_prebulk_plain") && sub == 1) activityFile(HistoryTests.details(), 5, false);
                        if (bulkScenario.equals("correlate_prebulk_flood") && sub == 1)
                            for (int i = 0; i < 17; i++) activityFile(HistoryTests.details(), 5, true);
                        if (bulkScenario.equals("correlate_prebulk_bytes") && sub == 1)
                            activityFile(new byte[HistoryFile.MAX_BYTES + 1], 5, true);
                        if (bulkScenario.equals("correlate_prebulk_combined") && sub == 1)
                            activityFile(new byte[HistoryFile.MAX_BYTES / 2], 5, true);
                        reply(new Proto.Writer().number(1, 8).number(2, sub).bytes(10, health).build(), true);
                    } else if (historyOnly && type == 8 && sub == 3) {
                        fileFetches++;
                        try (Proto health = command.message(10)) {
                            byte[] id = health.bytes(2);
                            requestedIds.add(id.clone());
                            boolean detail = Arrays.equals(id, HistoryTests.DETAILS_ID);
                            if (bulkScenario.startsWith("batch") || bulkScenario.startsWith("archive")) {
                                byte[] expected = HistoryTests.DETAILS_ID.clone(); expected[0] = id[0];
                                detail = Arrays.equals(id, expected) && ((id[0] & 255) == 1 || (id[0] & 255) >= 10 && (id[0] & 255) <= 18);
                            }
                            yes(detail || Arrays.equals(id, HistoryTests.SUMMARY_ID));
                            eq(bytes, HistoryFile.request(id));
                            if (!bulkScenario.equals("correlate_late_receipt")) reply(Proto.command(8, 3), true);
                            byte[] file = detail ? HistoryTests.details() : HistoryTests.summary();
                            if (bulkScenario.startsWith("pastrepeat")) {
                                byte[] unmatched = HistoryTests.file(new byte[]{42, 2, 3, 4, 0, 4, 0}, new byte[]{1});
                                if (fileFetches == 1) for (int i = 0; i < 10; i++) activityFile(unmatched, 5, true);
                                if (bulkScenario.equals("pastrepeat_bytes"))
                                    activityFile(HistoryTests.file(new byte[]{42, 2, 3, 4, 0, 4, 0}, new byte[HistoryFile.MAX_BYTES / 2 - 12]), 5, true);
                                if (bulkScenario.equals("pastrepeat_unsupported") || fileFetches == 2 && bulkScenario.equals("pastrepeat_second_unsupported"))
                                    file = HistoryTests.file(id, new byte[]{1});
                                if (fileFetches == 2) {
                                    if (bulkScenario.equals("pastrepeat_observed_shape")) for (int i = 0; i < 3; i++) activityFile(unmatched, 5, true);
                                    if (bulkScenario.equals("pastrepeat_rechunked")) { activityChunk(1, 1, file, 5, true); return; }
                                    if (bulkScenario.equals("pastrepeat_changed_bytes")) { file[16] = 71; HistoryTests.crc(file); }
                                    if (bulkScenario.equals("pastrepeat_changed_counts")) { file[16] = 0; HistoryTests.crc(file); }
                                    if (bulkScenario.equals("pastrepeat_crc")) file[file.length - 1] ^= 1;
                                    if (bulkScenario.equals("pastrepeat_pad")) { file[7] = 1; HistoryTests.crc(file); }
                                    if (List.of("pastrepeat_timeout", "pastrepeat_cancel", "pastrepeat_eof").contains(bulkScenario)) return;
                                    if (bulkScenario.equals("pastrepeat_missing")) { for (int i = 0; i < 16; i++) activityFile(unmatched, 5, true); return; }
                                }
                                activityFile(file, fileFetches == 2 && bulkScenario.equals("pastrepeat_channel") ? 2 : 5,
                                        !(fileFetches == 2 && bulkScenario.equals("pastrepeat_plain")));
                                return;
                            }
                            if (bulkScenario.startsWith("pasttarget")) {
                                byte[] unmatched = HistoryTests.file(new byte[]{42, 2, 3, 4, 0, 4, 0}, new byte[]{1});
                                for (int i = 0; i < (bulkScenario.equals("pasttarget_missing") ? 16 : 10); i++) activityFile(unmatched, 5, true);
                                if (bulkScenario.equals("pasttarget_crc")) file[file.length - 1] ^= 1;
                                if (bulkScenario.equals("pasttarget_pad")) { file[7] = 1; HistoryTests.crc(file); }
                                if (bulkScenario.equals("pasttarget_unsupported")) file = HistoryTests.file(id, new byte[]{1});
                                activityFile(file, bulkScenario.equals("pasttarget_channel") ? 2 : 5, !bulkScenario.equals("pasttarget_plain"));
                                return;
                            }
                            if (bulkScenario.startsWith("target")) {
                                byte[] unsolicited = HistoryTests.file(new byte[]{42, 2, 3, 4, 0, 9, 0}, new byte[]{1});
                                if (bulkScenario.startsWith("target_scan_")) {
                                    int mismatches = bulkScenario.equals("target_scan_past_missing") ? 16
                                            : bulkScenario.equals("target_scan_past_late") ? 10 : bulkScenario.equals("target_scan_missing") ? 6 : 5;
                                    for (int i = 0; i < mismatches; i++) activityFile(unsolicited, 5, true);
                                    activityFile(HistoryTests.file(id, new byte[]{1}), 5, true);
                                    return;
                                }
                                if (bulkScenario.equals("target_hardware_shape")) {
                                    activityChunk(1, 1, HistoryTests.file(new byte[]{42, 2, 3, 4, 0, 4, 0}, new byte[406]), 5, true);
                                    activityChunk(1, 1, HistoryTests.file(new byte[]{43, 2, 3, 4, 0, 4, 0}, new byte[70]), 5, true);
                                    activityFile(file, 5, true);
                                    return;
                                }
                                if (bulkScenario.equals("target_pre_partial")) {
                                    byte[] partial = HistoryTests.details(); partial[0] = 42; HistoryTests.crc(partial);
                                    activityChunk(2, 2, Arrays.copyOfRange(partial, 15, partial.length), 5, true);
                                } else if (!List.of("target_exact", "target_pre_exact", "target_retention_bulk", "target_combined_budget").contains(bulkScenario))
                                    activityFile(unsolicited, 5, true);
                                if (List.of("target_eof", "target_timeout", "target_cancel").contains(bulkScenario)) return;
                                if (bulkScenario.equals("target_two_unmatched")) file = unsolicited;
                                if (bulkScenario.equals("target_crc")) file[file.length - 1] ^= 1;
                                if (bulkScenario.equals("target_pad")) { file[7] = 1; HistoryTests.crc(file); }
                                if (bulkScenario.equals("target_unsupported")) file = HistoryTests.file(id, new byte[]{1});
                                if (bulkScenario.equals("target_combined_budget")) file = HistoryTests.file(id, new byte[HistoryFile.MAX_BYTES / 2 - 12]);
                                activityFile(file, bulkScenario.equals("target_channel") ? 2 : 5, !bulkScenario.equals("target_plain"));
                                if (bulkScenario.equals("target_two_unmatched")) activityFile(HistoryTests.details(), 5, true);
                                return;
                            }
                            if (bulkScenario.startsWith("correlate")) {
                                byte[] unmatched = file.clone(); unmatched[0] = 42; HistoryTests.crc(unmatched);
                                byte[] first = bulkScenario.equals("correlate_exact_first") ? file : unmatched;
                                if (bulkScenario.equals("correlate_crc")) first[first.length - 1] ^= 1;
                                if (bulkScenario.equals("correlate_padding")) { first[7] = 1; HistoryTests.crc(first); }
                                if (bulkScenario.equals("correlate_budget") || bulkScenario.equals("correlate_prebulk_combined")) {
                                    int fileBytes = bulkScenario.equals("correlate_prebulk_combined") ? HistoryFile.MAX_BYTES / 2 : HistoryFile.MAX_BYTES - 1;
                                    first = HistoryTests.file(id, new byte[fileBytes - 12]); first[0] = 42; HistoryTests.crc(first);
                                }
                                activityFile(first, bulkScenario.equals("correlate_channel") ? 2 : 5, !bulkScenario.equals("correlate_plain"));
                                if (bulkScenario.equals("correlate_late_receipt")) reply(Proto.command(8, 3), true);
                                if (!List.of("correlate_silent", "correlate_timeout", "correlate_cancel", "correlate_exact_first").contains(bulkScenario)) {
                                    byte[] second = bulkScenario.equals("correlate_two_unmatched") ? unmatched : file;
                                    activityFile(second, 5, true);
                                    // Extra third file must never be consumed by the bounded observer.
                                    activityFile(file, 5, true);
                                }
                                return;
                            }
                            if (bulkScenario.startsWith("archive")) {
                                System.arraycopy(id, 0, file, 0, 7); HistoryTests.crc(file);
                                if (bulkScenario.equals("archive_wrong_id")) { file[0] = 42; HistoryTests.crc(file); }
                            }
                            if (bulkScenario.startsWith("batch")) {
                                System.arraycopy(id, 0, file, 0, 7); HistoryTests.crc(file);
                                if (fileFetches == 2) {
                                    if (bulkScenario.equals("batch_wrong_id")) { file[0] ^= 1; HistoryTests.crc(file); }
                                    if (bulkScenario.equals("batch_pad")) { file[7] = 1; HistoryTests.crc(file); }
                                    if (bulkScenario.equals("batch_crc")) file[file.length - 1] ^= 1;
                                }
                            }
                            if (detail && bulkScenario.equals("wrap")) file = HistoryTests.manyRecords(300);
                            if (detail && bulkScenario.equals("unsupported_layout")) file = HistoryTests.file(id, new byte[]{1});
                            if (detail && (bulkScenario.equals("overflow") || bulkScenario.equals("aggregate_budget"))) {
                                int length = HistoryFile.MAX_BYTES + (bulkScenario.equals("overflow") ? 1 : -10);
                                byte[] body = new byte[length - 12]; body[5] = 1; file = HistoryTests.file(id, body);
                            }
                            if (bulkScenario.startsWith("provenance")) {
                                file[0] = 42; HistoryTests.crc(file);
                                if (bulkScenario.equals("provenance_bad_crc")) file[file.length - 1] ^= 1;
                            }
                            if (bulkScenario.equals("repeat_changed") && fileFetches > 2) { file[20] ^= 1; HistoryTests.crc(file); }
                            if (bulkScenario.equals("crc")) file[file.length - 1] ^= 1;
                            if (bulkScenario.equals("wrong_id")) { file[0] ^= 1; HistoryTests.crc(file); }
                            int chunkSize = bulkScenario.equals("wrap") ? 1 : file.length > 4096 ? 20000 : 15;
                            int total = (file.length + chunkSize - 1) / chunkSize;
                            for (int pos = 0, number = 1; pos < file.length; pos += chunkSize, number++) {
                                byte[] data = HistoryTests.chunk(total, bulkScenario.equals("order") ? number + 1 : number,
                                        Arrays.copyOfRange(file, pos, Math.min(pos + chunkSize, file.length)));
                                boolean plain = bulkScenario.equals("plain");
                                byte[] encryptedData = plain ? data : Crypto.ctr(receiveKey(), receiveKey(), data);
                                byte[] envelope = Crypto.join(new byte[]{(byte) (bulkScenario.equals("wrong_channel") ? 2 : 5), (byte) (plain ? 1 : 2)}, encryptedData);
                                byte[] packet = HistoryTests.largeFrame(seq++, envelope);
                                offer(packet);
                                if (number == 1 && bulkScenario.equals("duplicate_transport")) offer(packet);
                            }
                        }
                    } else if (type == 8 && sub == 10) {
                        reply(hex("0808100a520642040800100a"), true);
                    } else if (type == 8 && sub == 45) {
                        byte[] stats = new Proto.Writer().number(1, 1234).number(4, 67).build();
                        reply(new Proto.Writer().number(1, 8).number(2, 47)
                                .bytes(10, new Proto.Writer().bytes(39, stats).build()).build(), true);
                    } else if (type != 8 || sub != 46) { throw new AssertionError("command outside allowlist"); }
                }
            }
        }
        private void passiveTraffic() throws GeneralSecurityException {
            passive = true;
            if (List.of("passive_silence", "passive_early_timeout", "passive_eof", "passive_cancel").contains(bulkScenario)) return;
            if (bulkScenario.equals("passive_half_header") || bulkScenario.equals("passive_half_payload")) {
                byte[] packet = Wire.v2(3, seq++, Crypto.join(new byte[]{5, 2}, Crypto.ctr(receiveKey(), receiveKey(), new byte[20])));
                offer(Arrays.copyOf(packet, bulkScenario.equals("passive_half_header") ? 5 : 15)); return;
            }
            if (bulkScenario.equals("passive_control_flood")) {
                for (int i = 0; i < 33; i++) reply(Proto.command(2, 3), true);
                return;
            }
            if (bulkScenario.equals("passive_frames") || bulkScenario.equals("passive_bytes")) {
                for (int i = 1; i <= 33; i++) activityChunk(40, i, new byte[bulkScenario.equals("passive_bytes") ? 20000 : 1], 5, true);
                return;
            }
            if (bulkScenario.equals("passive_partial_file") || bulkScenario.equals("passive_order")) {
                activityChunk(2, bulkScenario.equals("passive_order") ? 2 : 1, Arrays.copyOf(HistoryTests.details(), 15), 5, true); return;
            }
            byte[] file = HistoryTests.details();
            if (bulkScenario.equals("passive_crc")) file[file.length - 1] ^= 1;
            if (bulkScenario.equals("passive_pad")) { file[7] = 1; HistoryTests.crc(file); }
            if (bulkScenario.equals("passive_unknown")) { file[5] = 99; HistoryTests.crc(file); }
            int count = bulkScenario.equals("passive_files") ? 17 : 1;
            for (int i = 0; i < count; i++) activityChunk(1, 1, file, bulkScenario.equals("passive_channel") ? 2 : 5, !bulkScenario.equals("passive_plain"));
            if (bulkScenario.equals("passive_control")) reply(Proto.command(2, 3), true);
        }
        private void activityFile(byte[] file, int channel, boolean encrypted) throws GeneralSecurityException {
            int chunkSize = file.length > 4096 ? 20000 : 15;
            int total = (file.length + chunkSize - 1) / chunkSize;
            for (int pos = 0, number = 1; pos < file.length; pos += chunkSize, number++) {
                activityChunk(total, number, Arrays.copyOfRange(file, pos, Math.min(pos + chunkSize, file.length)), channel, encrypted);
            }
        }
        private void activityChunk(int total, int number, byte[] data, int channel, boolean encrypted) throws GeneralSecurityException {
            byte[] chunk = HistoryTests.chunk(total, number, data);
            byte[] body = encrypted ? Crypto.ctr(receiveKey(), receiveKey(), chunk) : chunk;
            byte[] packet = HistoryTests.largeFrame(seq++, Crypto.join(new byte[]{(byte) channel, (byte) (encrypted ? 2 : 1)}, body));
            offer(packet);
            if (bulkScenario.equals("target_duplicate_transport") || bulkScenario.equals("boundary_duplicate") || bulkScenario.equals("passive_duplicate") || bulkScenario.equals("pastwin_duplicate")) offer(packet);
        }
        private void reply(byte[] command, boolean encrypted) throws GeneralSecurityException {
            byte[] data = command;
            if (encrypted) data = version == 2 ? Crypto.ctr(receiveKey(), receiveKey(), data)
                    : Crypto.ccmEncrypt(receiveKey(), nonce(32, 0), new byte[0], data, 4);
            if (version == 1) offer(Wire.v1(1, 0x80, 2, seq++, encrypted ? 1 : 2, data));
            else offer(Wire.v2(3, seq++, Crypto.join(new byte[]{1, (byte) (encrypted ? 2 : 1)}, data)));
        }
    }
}
