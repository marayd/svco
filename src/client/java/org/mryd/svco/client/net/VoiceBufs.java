package org.mryd.svco.client.net;

import io.netty.buffer.ByteBuf;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * ByteBuf helpers replicating Minecraft's FriendlyByteBuf semantics used by
 * the Simple Voice Chat wire protocol. Kept free of Minecraft classes so the
 * codec stays testable outside the game.
 */
public final class VoiceBufs {

    private VoiceBufs() {
    }

    public static int readVarInt(ByteBuf buf) {
        int value = 0;
        int position = 0;
        while (true) {
            byte b = buf.readByte();
            value |= (b & 0x7F) << position;
            if ((b & 0x80) == 0) {
                return value;
            }
            position += 7;
            if (position >= 32) {
                throw new IllegalArgumentException("VarInt too big");
            }
        }
    }

    public static void writeVarInt(ByteBuf buf, int value) {
        while ((value & ~0x7F) != 0) {
            buf.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        buf.writeByte(value);
    }

    public static UUID readUuid(ByteBuf buf) {
        return new UUID(buf.readLong(), buf.readLong());
    }

    public static void writeUuid(ByteBuf buf, UUID uuid) {
        buf.writeLong(uuid.getMostSignificantBits());
        buf.writeLong(uuid.getLeastSignificantBits());
    }

    public static byte[] readByteArray(ByteBuf buf, int maxSize) {
        int length = readVarInt(buf);
        if (length < 0 || length > maxSize) {
            throw new IllegalArgumentException("Byte array too big: " + length + " > " + maxSize);
        }
        byte[] bytes = new byte[length];
        buf.readBytes(bytes);
        return bytes;
    }

    public static void writeByteArray(ByteBuf buf, byte[] bytes) {
        writeVarInt(buf, bytes.length);
        buf.writeBytes(bytes);
    }

    public static String readUtf(ByteBuf buf, int maxLength) {
        int length = readVarInt(buf);
        if (length < 0 || length > maxLength * 3) {
            throw new IllegalArgumentException("String too big: " + length);
        }
        byte[] bytes = new byte[length];
        buf.readBytes(bytes);
        String string = new String(bytes, StandardCharsets.UTF_8);
        if (string.length() > maxLength) {
            throw new IllegalArgumentException("String too long: " + string.length());
        }
        return string;
    }

    public static void writeUtf(ByteBuf buf, String string, int maxLength) {
        if (string.length() > maxLength) {
            throw new IllegalArgumentException("String too long: " + string.length());
        }
        byte[] bytes = string.getBytes(StandardCharsets.UTF_8);
        writeVarInt(buf, bytes.length);
        buf.writeBytes(bytes);
    }
}
