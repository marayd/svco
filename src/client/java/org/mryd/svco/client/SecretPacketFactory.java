package org.mryd.svco.client;

import de.maxhenkel.voicechat.net.SecretPacket;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.mryd.svco.client.net.VoiceSecret;

import java.util.UUID;

/**
 * Fabricates an SVC SecretPacket pointing at the mod's local loopback
 * voice bridge, as if the Minecraft server itself had sent it.
 *
 * Wire layout of SecretPacket (SVC 2.6.x):
 * [secret 16 bytes][int serverPort][UUID playerUUID][byte codec ordinal]
 * [int mtuSize][double distance][int keepAlive][bool groupsEnabled]
 * [Utf voiceHost][bool allowRecording]
 */
public class SecretPacketFactory {

    private static final int MTU_SIZE = 1024;
    private static final int KEEP_ALIVE_MS = 1000;
    private static final int CODEC_VOIP = 0;

    public static SecretPacket create(VoiceSession session, SvcoConfig config, UUID playerUuid) {
        VoiceSecret secret = session.bridgeSecret();

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buf.writeBytes(secret.bytes());
            buf.writeInt(session.bridgePort());
            buf.writeUUID(playerUuid);
            buf.writeByte(CODEC_VOIP);
            buf.writeInt(MTU_SIZE);
            buf.writeDouble(config.voiceDistance);
            buf.writeInt(KEEP_ALIVE_MS);
            buf.writeBoolean(false); // groupsEnabled
            buf.writeUtf("127.0.0.1:" + session.bridgePort());
            buf.writeBoolean(false); // allowRecording
            return new SecretPacket().fromBytes(buf);
        } finally {
            buf.release();
        }
    }
}
