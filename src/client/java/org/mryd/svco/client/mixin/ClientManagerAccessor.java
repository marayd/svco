package org.mryd.svco.client.mixin;

import de.maxhenkel.voicechat.net.SecretPacket;
import de.maxhenkel.voicechat.voice.client.ClientManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(value = ClientManager.class, remap = false)
public interface ClientManagerAccessor {

    /**
     * Feeds a SecretPacket into SVC's client exactly as if the server had sent one.
     * The regular SVC client then handles the whole voice protocol (UDP handshake,
     * encryption, Opus, playback) against whatever host the packet points at.
     */
    @Invoker("authenticate")
    void svco$authenticate(SecretPacket packet);
}
