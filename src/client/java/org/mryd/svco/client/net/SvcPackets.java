package org.mryd.svco.client.net;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.util.UUID;

/**
 * The subset of the Simple Voice Chat UDP protocol the loopback bridge
 * speaks. Wire-compatible with {@code de.maxhenkel.voicechat.voice.common.*}.
 *
 * <p>Datagram layout:
 * <ul>
 *   <li>client -&gt; server: {@code [0xFF][player UUID][VarInt len][AES-GCM payload]}</li>
 *   <li>server -&gt; client: {@code [0xFF][VarInt len][AES-GCM payload]}</li>
 * </ul>
 * Payload plaintext: {@code [type byte][packet body]}.
 */
public final class SvcPackets {

    public static final int MAGIC_BYTE = 0xFF;
    public static final int MAX_PACKET_SIZE = 2048;
    public static final int MAX_OPUS_PAYLOAD_SIZE = 1500;

    public static final byte TYPE_MIC = 0x1;
    public static final byte TYPE_PLAYER_SOUND = 0x2;
    public static final byte TYPE_GROUP_SOUND = 0x3;
    public static final byte TYPE_AUTHENTICATE = 0x5;
    public static final byte TYPE_AUTHENTICATE_ACK = 0x6;
    public static final byte TYPE_PING = 0x7;
    public static final byte TYPE_KEEP_ALIVE = 0x8;
    public static final byte TYPE_CONNECTION_CHECK = 0x9;
    public static final byte TYPE_CONNECTION_CHECK_ACK = 0xA;

    private static final int WHISPER_MASK = 0b1;

    private SvcPackets() {
    }

    /** A decrypted client->server packet: its type byte and body. */
    public record Inbound(UUID playerUuid, byte type, ByteBuf body) {
    }

    public record Mic(byte[] data, long sequenceNumber, boolean whispering) {
        public static Mic read(ByteBuf buf) {
            return new Mic(
                    VoiceBufs.readByteArray(buf, MAX_OPUS_PAYLOAD_SIZE),
                    buf.readLong(),
                    buf.readBoolean());
        }
    }

    public record Authenticate(UUID playerUuid, byte[] secret) {
        public static Authenticate read(ByteBuf buf) {
            UUID playerUuid = VoiceBufs.readUuid(buf);
            byte[] secret = new byte[VoiceSecret.SECRET_SIZE_BYTES];
            buf.readBytes(secret);
            return new Authenticate(playerUuid, secret);
        }
    }

    public record Ping(UUID id, long timestamp) {
        public static Ping read(ByteBuf buf) {
            return new Ping(VoiceBufs.readUuid(buf), buf.readLong());
        }
    }

    /**
     * Parses a client->server datagram, decrypting with {@code secret}.
     * Returns null on anything malformed or undecryptable — the bridge only
     * ever listens on loopback, but stray traffic must not crash it.
     */
    public static Inbound decodeClientBound(ByteBuf datagram, VoiceSecret secret) {
        try {
            if (datagram.readUnsignedByte() != MAGIC_BYTE) {
                return null;
            }
            UUID playerUuid = VoiceBufs.readUuid(datagram);
            byte[] encrypted = VoiceBufs.readByteArray(datagram, MAX_PACKET_SIZE);
            byte[] decrypted = secret.decrypt(encrypted);
            ByteBuf buf = Unpooled.wrappedBuffer(decrypted);
            return new Inbound(playerUuid, buf.readByte(), buf);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Encodes a server->client datagram: {@code [magic][VarInt len][encrypted payload]}.
     */
    public static byte[] encodeServerBound(byte type, ByteBuf body, VoiceSecret secret) {
        ByteBuf plain = Unpooled.buffer();
        try {
            plain.writeByte(type);
            if (body != null) {
                plain.writeBytes(body);
            }
            byte[] plainBytes = new byte[plain.readableBytes()];
            plain.readBytes(plainBytes);
            byte[] encrypted = secret.encrypt(plainBytes);

            ByteBuf out = Unpooled.buffer(1 + 5 + encrypted.length);
            try {
                out.writeByte(MAGIC_BYTE);
                VoiceBufs.writeByteArray(out, encrypted);
                byte[] bytes = new byte[out.readableBytes()];
                out.readBytes(bytes);
                return bytes;
            } finally {
                out.release();
            }
        } finally {
            plain.release();
        }
    }

    /** Body of a PlayerSoundPacket, the packet SVC plays back as a player's voice. */
    public static ByteBuf playerSoundBody(UUID sender, byte[] opus, long sequenceNumber,
                                          float distance, boolean whispering) {
        ByteBuf buf = Unpooled.buffer();
        VoiceBufs.writeUuid(buf, sender); // channelId: SVC keys the audio channel by it
        VoiceBufs.writeUuid(buf, sender);
        VoiceBufs.writeByteArray(buf, opus);
        buf.writeLong(sequenceNumber);
        buf.writeFloat(distance);
        buf.writeByte(whispering ? WHISPER_MASK : 0);
        return buf;
    }

    /** Body of a PingPacket (also the echo reply). */
    public static ByteBuf pingBody(Ping ping) {
        ByteBuf buf = Unpooled.buffer();
        VoiceBufs.writeUuid(buf, ping.id());
        buf.writeLong(ping.timestamp());
        return buf;
    }
}
