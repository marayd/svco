package org.mryd.svco.client.net;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.UUID;

/**
 * Per-connection AES-GCM secret, wire-compatible with Simple Voice Chat's
 * {@code de.maxhenkel.voicechat.voice.common.Secret}.
 *
 * <p>Encrypted payload layout: [12-byte random IV][ciphertext + 16-byte GCM tag]
 */
public final class VoiceSecret {

    public static final int SECRET_SIZE_BYTES = 16;
    public static final int IV_SIZE_BYTES = 12;
    public static final int TAG_LEN_BITS = 128;
    private static final String CIPHER = "AES/GCM/NoPadding";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final byte[] bytes;
    private final SecretKeySpec keySpec;

    private VoiceSecret(byte[] bytes) {
        this.bytes = bytes;
        this.keySpec = new SecretKeySpec(bytes, "AES");
    }

    public static VoiceSecret generate() {
        byte[] bytes = new byte[SECRET_SIZE_BYTES];
        RANDOM.nextBytes(bytes);
        return new VoiceSecret(bytes);
    }

    public static VoiceSecret fromBytes(byte[] bytes) {
        if (bytes.length != SECRET_SIZE_BYTES) {
            throw new IllegalArgumentException("Secret must be " + SECRET_SIZE_BYTES + " bytes");
        }
        return new VoiceSecret(bytes.clone());
    }

    public byte[] bytes() {
        return bytes.clone();
    }

    /** The secret as a UUID, the form SVC transports it in the SecretPacket. */
    public UUID toUuid() {
        long msb = 0;
        long lsb = 0;
        for (int i = 0; i < 8; i++) {
            msb = (msb << 8) | (bytes[i] & 0xFF);
        }
        for (int i = 8; i < 16; i++) {
            lsb = (lsb << 8) | (bytes[i] & 0xFF);
        }
        return new UUID(msb, lsb);
    }

    public byte[] encrypt(byte[] data) {
        try {
            byte[] iv = new byte[IV_SIZE_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(TAG_LEN_BITS, iv));
            byte[] encrypted = cipher.doFinal(data);
            byte[] out = new byte[IV_SIZE_BYTES + encrypted.length];
            System.arraycopy(iv, 0, out, 0, IV_SIZE_BYTES);
            System.arraycopy(encrypted, 0, out, IV_SIZE_BYTES, encrypted.length);
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("AES-GCM encryption failed", e);
        }
    }

    /**
     * @throws javax.crypto.AEADBadTagException wrapped in IllegalStateException
     *         if the payload was encrypted with a different secret
     */
    public byte[] decrypt(byte[] payload) throws Exception {
        if (payload.length <= IV_SIZE_BYTES) {
            throw new IllegalArgumentException("Payload too short");
        }
        byte[] iv = Arrays.copyOfRange(payload, 0, IV_SIZE_BYTES);
        byte[] data = Arrays.copyOfRange(payload, IV_SIZE_BYTES, payload.length);
        Cipher cipher = Cipher.getInstance(CIPHER);
        cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(TAG_LEN_BITS, iv));
        return cipher.doFinal(data);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof VoiceSecret secret && Arrays.equals(bytes, secret.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }
}
