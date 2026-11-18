package ru.kardiomonitor.band9probe.protocol;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

/** Fresh-session Xiaomi challenge/response. Never exposes secret material in results. */
public final class Auth implements AutoCloseable {
    private final byte[] phone = new byte[16];
    private byte[] receiveKey, sendKey, receivePrefix, sendPrefix;
    private boolean confirmed;

    public Auth() { new SecureRandom().nextBytes(phone); }
    public static byte[] decodeKeyHex(byte[] hex) throws ProbeException {
        if (hex.length != 32) throw new ProbeException("auth_key_requires_exactly_32_hex_characters");
        byte[] result = new byte[16];
        for (int i = 0; i < 16; i++) {
            int hi = digit(hex[2 * i]), lo = digit(hex[2 * i + 1]);
            if (hi < 0 || lo < 0) { wipe(result); throw new ProbeException("auth_key_invalid_hex"); }
            result[i] = (byte) ((hi << 4) | lo);
        }
        return result;
    }
    private static int digit(byte value) {
        int b = value & 255;
        if (b >= '0' && b <= '9') return b - '0';
        if (b >= 'a' && b <= 'f') return b - 'a' + 10;
        if (b >= 'A' && b <= 'F') return b - 'A' + 10;
        return -1;
    }
    public byte[] nonceCommand() {
        byte[] verify = new Proto.Writer().bytes(1, phone).build();
        byte[] account = new Proto.Writer().bytes(30, verify).build();
        byte[] command = new Proto.Writer().number(1, 1).number(2, 26).bytes(3, account).build();
        wipe(verify, account);
        return command;
    }
    public byte[] respond(byte[] secret, Proto command, int api, String model) throws GeneralSecurityException, ProbeException {
        if (secret.length != 16) throw new ProbeException("auth_key_length");
        if (receiveKey != null) throw new ProbeException("auth_duplicate_challenge");
        if (command.number(1) != 1 || command.number(2) != 26) throw new ProbeException("auth_challenge_type");
        command.requireSuccess();
        try (Proto account = command.message(3); Proto verify = account.message(31)) {
            byte[] watch = verify.bytes(1);
            byte[] proof = verify.bytes(2);
            if (watch.length != 16 || proof.length != 32) throw new ProbeException("auth_challenge_length");
            byte[] salt = Crypto.join(phone, watch);
            byte[] expanded = Crypto.hkdf(salt, secret, "miwear-auth".getBytes(StandardCharsets.US_ASCII), 64);
            wipe(salt);
            receiveKey = Arrays.copyOfRange(expanded, 0, 16);
            sendKey = Arrays.copyOfRange(expanded, 16, 32);
            receivePrefix = Arrays.copyOfRange(expanded, 32, 36);
            sendPrefix = Arrays.copyOfRange(expanded, 36, 40);
            wipe(expanded);
            byte[] challenge = Crypto.join(watch, phone);
            byte[] expected = Crypto.hmac(receiveKey, challenge);
            wipe(challenge);
            boolean valid = MessageDigest.isEqual(proof, expected);
            wipe(expected);
            if (!valid) throw new ProbeException("auth_watch_hmac_mismatch");
            byte[] info = new Proto.Writer().number(1, 0).float32(2, api)
                    .text(3, model).number(4, 224).text(5, "RU").build();
            byte[] encryptedInfo = Crypto.ccmEncrypt(sendKey, nonce(sendPrefix, 0), new byte[0], info, 4);
            byte[] both = Crypto.join(phone, watch);
            byte[] phoneProof = Crypto.hmac(sendKey, both);
            byte[] confirm = new Proto.Writer().bytes(1, phoneProof).bytes(2, encryptedInfo).build();
            byte[] auth = new Proto.Writer().bytes(32, confirm).build();
            byte[] response = new Proto.Writer().number(1, 1).number(2, 27).bytes(3, auth).build();
            wipe(info, encryptedInfo, both, phoneProof, confirm, auth);
            return response;
        }
    }
    public void confirm(Proto command) throws ProbeException {
        command.requireSuccess();
        if (receiveKey == null || command.number(1) != 1 || command.number(2) != 27)
            throw new ProbeException("auth_confirmation_type");
        if (command.has(3)) {
            try (Proto account = command.message(3)) {
                if (account.has(8) && account.number(8) != 0 && account.number(8) != 1)
                    throw new ProbeException("auth_confirmation_status");
            }
        }
        confirmed = true;
    }
    public byte[] encrypt(int version, byte[] data, int counter) throws GeneralSecurityException {
        requireConfirmed();
        if (version == 2) return Crypto.ctr(sendKey, sendKey, data);
        byte[] encrypted = Crypto.ccmEncrypt(sendKey, nonce(sendPrefix, counter), new byte[0], data, 4);
        byte[] result = new byte[encrypted.length + 2];
        Wire.put16(result, 0, counter); System.arraycopy(encrypted, 0, result, 2, encrypted.length);
        wipe(encrypted); return result;
    }
    public byte[] decrypt(int version, byte[] data) throws GeneralSecurityException {
        requireConfirmed();
        return version == 2 ? Crypto.ctr(receiveKey, receiveKey, data)
                : Crypto.ccmDecrypt(receiveKey, nonce(receivePrefix, 0), data, 4);
    }
    public byte[] decryptHandshakeV2(byte[] data) throws GeneralSecurityException {
        if (receiveKey == null) throw new GeneralSecurityException("auth_no_challenge");
        return Crypto.ctr(receiveKey, receiveKey, data);
    }
    private void requireConfirmed() throws GeneralSecurityException {
        if (!confirmed) throw new GeneralSecurityException("auth_not_confirmed");
    }
    private static byte[] nonce(byte[] prefix, int counter) {
        byte[] nonce = Arrays.copyOf(prefix, 12);
        for (int i = 0; i < 4; i++) nonce[8 + i] = (byte) (counter >>> (i * 8));
        return nonce;
    }
    public static void wipe(byte[]... values) { for (byte[] value : values) if (value != null) Arrays.fill(value, (byte) 0); }
    @Override public void close() { wipe(phone, receiveKey, sendKey, receivePrefix, sendPrefix); confirmed = false; }
}
