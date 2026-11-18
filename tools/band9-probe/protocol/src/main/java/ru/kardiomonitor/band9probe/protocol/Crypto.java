package ru.kardiomonitor.band9probe.protocol;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** JCA primitives plus RFC 3610 CCM (Android does not guarantee a CCM provider). */
public final class Crypto {
    private Crypto() {}
    public static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }
    public static byte[] join(byte[] a, byte[] b) {
        byte[] result = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }
    public static byte[] hkdf(byte[] salt, byte[] ikm, byte[] info, int length) throws GeneralSecurityException {
        if (length < 1 || length > 255 * 32) throw new GeneralSecurityException("hkdf_length");
        byte[] key = hmac(salt, ikm);
        byte[] previous = new byte[0];
        byte[] result = new byte[length];
        try {
            for (int offset = 0, block = 1; offset < length; block++) {
                byte[] input = Arrays.copyOf(previous, previous.length + info.length + 1);
                System.arraycopy(info, 0, input, previous.length, info.length);
                input[input.length - 1] = (byte) block;
                byte[] next = hmac(key, input);
                Arrays.fill(input, (byte) 0);
                Arrays.fill(previous, (byte) 0);
                previous = next;
                int count = Math.min(32, length - offset);
                System.arraycopy(previous, 0, result, offset, count);
                offset += count;
            }
            return result;
        } finally {
            Arrays.fill(key, (byte) 0); Arrays.fill(previous, (byte) 0);
        }
    }
    public static byte[] ctr(byte[] key, byte[] iv, byte[] input) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return cipher.doFinal(input);
    }

    public static byte[] ccmEncrypt(byte[] key, byte[] nonce, byte[] aad, byte[] plain, int tagSize)
            throws GeneralSecurityException {
        Cipher aes = aes(key);
        byte[] mac = ccmMac(aes, nonce, aad, plain, tagSize);
        byte[] output = counterCrypt(aes, nonce, plain);
        byte[] s0 = aes.doFinal(counter(nonce, 0));
        byte[] result = Arrays.copyOf(output, output.length + tagSize);
        for (int i = 0; i < tagSize; i++) result[plain.length + i] = (byte) (mac[i] ^ s0[i]);
        Arrays.fill(mac, (byte) 0); Arrays.fill(s0, (byte) 0); Arrays.fill(output, (byte) 0);
        return result;
    }
    public static byte[] ccmDecrypt(byte[] key, byte[] nonce, byte[] encrypted, int tagSize)
            throws GeneralSecurityException {
        if (encrypted.length < tagSize) throw new GeneralSecurityException("ccm_truncated");
        Cipher aes = aes(key);
        byte[] ciphertext = Arrays.copyOf(encrypted, encrypted.length - tagSize);
        byte[] plain = counterCrypt(aes, nonce, ciphertext);
        byte[] computed = ccmEncrypt(key, nonce, new byte[0], plain, tagSize);
        boolean matches = MessageDigest.isEqual(encrypted, computed);
        Arrays.fill(computed, (byte) 0); Arrays.fill(ciphertext, (byte) 0);
        if (!matches) { Arrays.fill(plain, (byte) 0); throw new GeneralSecurityException("ccm_mac"); }
        return plain;
    }
    private static Cipher aes(byte[] key) throws GeneralSecurityException {
        if (key.length != 16) throw new GeneralSecurityException("aes_key_length");
        Cipher aes = Cipher.getInstance("AES/ECB/NoPadding");
        aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
        return aes;
    }
    private static void validate(byte[] nonce, int length, int tagSize) throws GeneralSecurityException {
        if (nonce.length < 7 || nonce.length > 13 || tagSize < 4 || tagSize > 16 || tagSize % 2 != 0
                || length > 4096) throw new GeneralSecurityException("ccm_parameters");
    }
    private static byte[] counter(byte[] nonce, int value) throws GeneralSecurityException {
        validate(nonce, 0, 4);
        int q = 15 - nonce.length;
        byte[] result = new byte[16];
        result[0] = (byte) (q - 1);
        System.arraycopy(nonce, 0, result, 1, nonce.length);
        for (int i = 15; i > nonce.length; i--) { result[i] = (byte) value; value >>>= 8; }
        return result;
    }
    private static byte[] counterCrypt(Cipher aes, byte[] nonce, byte[] input) throws GeneralSecurityException {
        validate(nonce, input.length, 4);
        byte[] result = new byte[input.length];
        for (int offset = 0, block = 1; offset < input.length; offset += 16, block++) {
            byte[] mask = aes.doFinal(counter(nonce, block));
            for (int i = 0; i < Math.min(16, input.length - offset); i++) result[offset + i] = (byte) (input[offset + i] ^ mask[i]);
            Arrays.fill(mask, (byte) 0);
        }
        return result;
    }
    private static byte[] ccmMac(Cipher aes, byte[] nonce, byte[] aad, byte[] plain, int tagSize)
            throws GeneralSecurityException {
        validate(nonce, plain.length, tagSize);
        if (aad.length >= 0xff00) throw new GeneralSecurityException("ccm_aad_length");
        byte[] first = counter(nonce, plain.length);
        first[0] |= (byte) (((tagSize - 2) / 2) << 3);
        if (aad.length > 0) first[0] |= 0x40;
        byte[] state = aes.doFinal(first);
        if (aad.length > 0) {
            byte[] padded = new byte[((aad.length + 2 + 15) / 16) * 16];
            padded[0] = (byte) (aad.length >>> 8); padded[1] = (byte) aad.length;
            System.arraycopy(aad, 0, padded, 2, aad.length);
            state = macBlocks(aes, state, padded);
            Arrays.fill(padded, (byte) 0);
        }
        return macBlocks(aes, state, plain);
    }
    private static byte[] macBlocks(Cipher aes, byte[] state, byte[] input) throws GeneralSecurityException {
        for (int offset = 0; offset < input.length; offset += 16) {
            for (int i = 0; i < Math.min(16, input.length - offset); i++) state[i] ^= input[offset + i];
            byte[] next = aes.doFinal(state);
            Arrays.fill(state, (byte) 0); state = next;
        }
        return state;
    }
}
