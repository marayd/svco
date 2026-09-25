package org.mryd.svco.client.p2p;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;

/**
 * The SVCO P2P/relay UDP wire format (mirrors the Go relay's
 * {@code internal/proto/udp.go}). Every datagram is {@code [0xE5][type][...]}.
 *
 * <pre>
 * BIND_REQ   [magic][0x01][token 8B]                             mod -&gt; relay
 * BIND_RESP  [magic][0x02][token 8B][family][ip][port 2B]        relay -&gt; mod
 * FWD        [magic][0x03][token 8B][target 16B][dist 1B][sealed] mod -&gt; relay
 * VOICE      [magic][0x04][sender 16B][sealed]                   relay/peer -&gt; mod
 * KEEPALIVE  [magic][0x05]                                       peer -&gt; peer
 * PUNCH      [magic][0x10][sender 16B][sealed]                   peer -&gt; peer
 * PUNCH_ACK  [magic][0x11][sender 16B][sealed]                   peer -&gt; peer
 * </pre>
 *
 * Sealed voice plaintext: {@code [seq 8B][flags 1B][dist 1B][opus]},
 * flags bit 0 = whispering.
 *
 * <p>The voice distance is capped at {@link #MAX_VOICE_DISTANCE} blocks and
 * is enforced twice: it rides inside the sealed plaintext, so peers verify
 * an authenticated value, and once more in the clear in the FWD header, so
 * the relay can reject over-limit senders without decrypting.
 */
public final class P2pCodec {

    public static final byte MAGIC = (byte) 0xE5;

    public static final byte TYPE_BIND_REQ = 0x01;
    public static final byte TYPE_BIND_RESP = 0x02;
    public static final byte TYPE_FWD = 0x03;
    public static final byte TYPE_VOICE = 0x04;
    public static final byte TYPE_KEEPALIVE = 0x05;
    public static final byte TYPE_PUNCH = 0x10;
    public static final byte TYPE_PUNCH_ACK = 0x11;

    public static final int TOKEN_SIZE = 8;
    public static final int UUID_SIZE = 16;
    public static final int MAX_PACKET_SIZE = 2048;

    /** Hard cap on the voice distance, in blocks. Enforced by peers and the relay. */
    public static final int MAX_VOICE_DISTANCE = 32;

    private static final byte WHISPER_FLAG = 0b1;

    private P2pCodec() {
    }

    public record BindResp(byte[] token, byte[] ip, int port) {
    }

    public record VoicePayload(long sequenceNumber, boolean whispering, int distance, byte[] opus) {
    }

    // ---- headers / AAD ------------------------------------------------

    /** AAD binding a sealed payload to its packet type and sender. */
    public static byte[] aad(byte type, UUID sender) {
        ByteBuffer buf = ByteBuffer.allocate(2 + UUID_SIZE).order(ByteOrder.BIG_ENDIAN);
        buf.put(MAGIC).put(type);
        putUuid(buf, sender);
        return buf.array();
    }

    // ---- encoding ------------------------------------------------------

    public static byte[] bindReq(byte[] token) {
        ByteBuffer buf = ByteBuffer.allocate(2 + TOKEN_SIZE);
        buf.put(MAGIC).put(TYPE_BIND_REQ).put(token);
        return buf.array();
    }

    public static byte[] fwd(byte[] token, UUID target, int distance, byte[] sealed) {
        ByteBuffer buf = ByteBuffer.allocate(2 + TOKEN_SIZE + UUID_SIZE + 1 + sealed.length).order(ByteOrder.BIG_ENDIAN);
        buf.put(MAGIC).put(TYPE_FWD).put(token);
        putUuid(buf, target);
        buf.put((byte) clampDistance(distance));
        buf.put(sealed);
        return buf.array();
    }

    /** VOICE as sent directly between peers (the relay builds the same for FWD). */
    public static byte[] voice(UUID sender, byte[] sealed) {
        return withSenderHeader(TYPE_VOICE, sender, sealed);
    }

    public static byte[] punch(byte type, UUID sender, byte[] sealed) {
        return withSenderHeader(type, sender, sealed);
    }

    public static byte[] keepAlive() {
        return new byte[]{MAGIC, TYPE_KEEPALIVE};
    }

    public static byte[] voicePlaintext(long sequenceNumber, boolean whispering, int distance, byte[] opus) {
        ByteBuffer buf = ByteBuffer.allocate(8 + 1 + 1 + opus.length).order(ByteOrder.BIG_ENDIAN);
        buf.putLong(sequenceNumber);
        buf.put(whispering ? WHISPER_FLAG : 0);
        buf.put((byte) clampDistance(distance));
        buf.put(opus);
        return buf.array();
    }

    /** The distance actually allowed on the wire: {@code [1, MAX_VOICE_DISTANCE]}. */
    public static int clampDistance(int distance) {
        return Math.max(1, Math.min(MAX_VOICE_DISTANCE, distance));
    }

    private static byte[] withSenderHeader(byte type, UUID sender, byte[] sealed) {
        ByteBuffer buf = ByteBuffer.allocate(2 + UUID_SIZE + sealed.length).order(ByteOrder.BIG_ENDIAN);
        buf.put(MAGIC).put(type);
        putUuid(buf, sender);
        buf.put(sealed);
        return buf.array();
    }

    // ---- decoding ------------------------------------------------------

    /** Returns the type byte, or -1 if this is not an SVCO datagram. */
    public static int packetType(byte[] data, int length) {
        if (length < 2 || length > MAX_PACKET_SIZE || data[0] != MAGIC) {
            return -1;
        }
        return data[1] & 0xFF;
    }

    /** Returns null if malformed. */
    public static BindResp parseBindResp(byte[] data, int length) {
        // [magic][type][token 8][family 1][ip 4|16][port 2]
        int v4Len = 2 + TOKEN_SIZE + 1 + 4 + 2;
        int v6Len = 2 + TOKEN_SIZE + 1 + 16 + 2;
        if (length != v4Len && length != v6Len) {
            return null;
        }
        ByteBuffer buf = ByteBuffer.wrap(data, 2, length - 2).order(ByteOrder.BIG_ENDIAN);
        byte[] token = new byte[TOKEN_SIZE];
        buf.get(token);
        int family = buf.get() & 0xFF;
        byte[] ip = new byte[family == 0x04 ? 4 : 16];
        buf.get(ip);
        int port = buf.getShort() & 0xFFFF;
        return new BindResp(token, ip, port);
    }

    /** Reads the sender UUID of a VOICE / PUNCH / PUNCH_ACK packet. Null if malformed. */
    public static UUID parseSender(byte[] data, int length) {
        if (length < 2 + UUID_SIZE) {
            return null;
        }
        ByteBuffer buf = ByteBuffer.wrap(data, 2, UUID_SIZE).order(ByteOrder.BIG_ENDIAN);
        return new UUID(buf.getLong(), buf.getLong());
    }

    /** The sealed payload after the sender header. */
    public static byte[] sealedPayload(byte[] data, int length) {
        int offset = 2 + UUID_SIZE;
        if (length <= offset) {
            return new byte[0];
        }
        byte[] out = new byte[length - offset];
        System.arraycopy(data, offset, out, 0, out.length);
        return out;
    }

    /** Returns null if malformed or the distance exceeds {@link #MAX_VOICE_DISTANCE}. */
    public static VoicePayload parseVoicePlaintext(byte[] plaintext) {
        if (plaintext.length < 10) {
            return null;
        }
        ByteBuffer buf = ByteBuffer.wrap(plaintext).order(ByteOrder.BIG_ENDIAN);
        long seq = buf.getLong();
        byte flags = buf.get();
        int distance = buf.get() & 0xFF;
        if (distance < 1 || distance > MAX_VOICE_DISTANCE) {
            return null; // a peer trying to shout further than allowed
        }
        byte[] opus = new byte[buf.remaining()];
        buf.get(opus);
        return new VoicePayload(seq, (flags & WHISPER_FLAG) != 0, distance, opus);
    }

    private static void putUuid(ByteBuffer buf, UUID uuid) {
        buf.putLong(uuid.getMostSignificantBits());
        buf.putLong(uuid.getLeastSignificantBits());
    }
}
