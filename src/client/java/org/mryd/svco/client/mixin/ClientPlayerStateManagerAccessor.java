package org.mryd.svco.client.mixin;

import de.maxhenkel.voicechat.voice.client.ClientPlayerStateManager;
import de.maxhenkel.voicechat.voice.common.PlayerState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;
import java.util.UUID;

@Mixin(value = ClientPlayerStateManager.class, remap = false)
public interface ClientPlayerStateManagerAccessor {

    /**
     * The client's view of who is on voice chat. On a real server this map is
     * filled by PlayerState(s)Packets; with no server-side voice chat the mod
     * mirrors the relay room's peer list into it, so peers show up in SVC's
     * volume screen and are treated as connected voice chat users.
     */
    @Accessor("states")
    Map<UUID, PlayerState> svco$getStates();
}
