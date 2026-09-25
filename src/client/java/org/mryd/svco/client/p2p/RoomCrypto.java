package org.mryd.svco.client.p2p;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * AES-128-GCM under the shared room key handed out by the relay's signaling
 * plane. Everything peers exchange (voice, punches) is sealed with it, so
 * the relay forwards only opaque ciphertext and off-room parties can't
 * inject or read audio.
 *
 * <p>Sealed layout: {@code [12-byte IV][ciphertext + 16-byte tag]}. The AAD
 * binds the ciphertext to the packet header (magic, type, sender UUID) so a
 * ciphertext can't be replayed as a different packet type or sender.
 */
public final class RoomCrypto {

    public static final int KEY_SIZE_BYTES = 16;
    private static final int IV_SIZE_BYTES = 12;
    private static final int TAG_LEN_BITS = 128;
    private static final String CIPHER = "AES/GCM/NoPadding";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec keySpec;

    public RoomCrypto(byte[] roomKey) {
        if (roomKey.length != KEY_SIZE_BYTES) {
            throw new IllegalArgumentException("Room key must be " + KEY_SIZE_BYTES + " bytes");
        }
        this.keySpec = new SecretKeySpec(roomKey, "AES");
    }

    public byte[] seal(byte[] plaintext, byte[] aad) {
        try {
            byte[] iv = new byte[IV_SIZE_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(TAG_LEN_BITS, iv));
            cipher.updateAAD(aad);
            byte[] encrypted = cipher.doFinal(plaintext);
            byte[] out = new byte[IV_SIZE_BYTES + encrypted.length];
            System.arraycopy(iv, 0, out, 0, IV_SIZE_BYTES);
            System.arraycopy(encrypted, 0, out, IV_SIZE_BYTES, encrypted.length);
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("Room encryption failed", e);
        }
    }

    /** Returns null if the payload is malformed or not sealed with this room's key. */
    public byte[] open(byte[] sealed, byte[] aad) {
        if (sealed.length <= IV_SIZE_BYTES) {
            return null;
        }
        try {
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.DECRYPT_MODE, keySpec,
                    new GCMParameterSpec(TAG_LEN_BITS, sealed, 0, IV_SIZE_BYTES));
            cipher.updateAAD(aad);
            return cipher.doFinal(sealed, IV_SIZE_BYTES, sealed.length - IV_SIZE_BYTES);
        } catch (Exception e) {
            return null;
        }
    }

    public static byte[] copyOf(byte[] key) {
        return Arrays.copyOf(key, key.length);
    }
}
