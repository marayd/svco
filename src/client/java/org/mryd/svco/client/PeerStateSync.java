package org.mryd.svco.client;

import de.maxhenkel.voicechat.VoicechatClient;
import de.maxhenkel.voicechat.gui.volume.AdjustVolumeList;
import de.maxhenkel.voicechat.voice.client.ClientManager;
import de.maxhenkel.voicechat.voice.client.ClientVoicechat;
import de.maxhenkel.voicechat.voice.common.PlayerState;
import net.minecraft.client.Minecraft;
import org.mryd.svco.client.mixin.ClientPlayerStateManagerAccessor;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Mirrors the relay room's peer list into SVC's {@code ClientPlayerStateManager}.
 *
 * <p>On a real server that map is filled by PlayerState(s)Packets; without
 * server-side voice chat it stays empty, so SVC treats everyone as "not on
 * voice chat": the volume-adjust screen lists nobody and per-player voice
 * settings are unreachable. Registering a {@link PlayerState} per peer makes
 * SVC recognize them exactly like on a regular server — they appear in the
 * volume GUI, get username-cache entries, and per-player volume from
 * {@code PLAYER_VOLUME_CONFIG} applies to their audio channels.
 *
 * <p>All map access is scheduled onto the client thread: SVC's own packet
 * handlers and render code touch the (plain HashMap) states there too.
 */
public final class PeerStateSync {

    /** States this session inserted, so teardown removes only its own entries. */
    private final Map<UUID, PlayerState> added = new HashMap<>();

    public void peerJoined(UUID uuid, String name) {
        onClientThread(() -> {
            PlayerState state = new PlayerState(uuid, name, false, false);
            added.put(uuid, state);
            states().put(uuid, state);
            VoicechatClient.USERNAME_CACHE.updateUsername(uuid, name);
            AdjustVolumeList.update();
        });
    }

    public void peerLeft(UUID uuid) {
        onClientThread(() -> {
            PlayerState state = added.remove(uuid);
            if (state == null) {
                return;
            }
            states().remove(uuid, state);
            ClientVoicechat client = ClientManager.getClient();
            if (client != null) {
                client.closeAudioChannel(uuid);
            }
            AdjustVolumeList.update();
        });
    }

    /**
     * Removes every state this session added. Removal is by (key, value), so
     * states from a real server that replaced the map meanwhile are untouched.
     */
    public void clear() {
        onClientThread(() -> {
            Map<UUID, PlayerState> states = states();
            added.forEach(states::remove);
            added.clear();
            AdjustVolumeList.update();
        });
    }

    private static Map<UUID, PlayerState> states() {
        return ((ClientPlayerStateManagerAccessor) ClientManager.getPlayerStateManager()).svco$getStates();
    }

    private void onClientThread(Runnable task) {
        Minecraft.getInstance().execute(task);
    }
}
