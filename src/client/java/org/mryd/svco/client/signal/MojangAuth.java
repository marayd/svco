package org.mryd.svco.client.signal;

import com.mojang.authlib.minecraft.MinecraftSessionService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import org.mryd.svco.Svco;

import java.util.concurrent.CompletableFuture;

/**
 * {@link SignalingClient.SessionAuthenticator} backed by the vanilla
 * session service: proves account ownership to the relay exactly like
 * joining an online-mode server. joinServer is a blocking HTTP call, so
 * it runs on a background thread, never on the WebSocket listener thread.
 */
public final class MojangAuth implements SignalingClient.SessionAuthenticator {

    @Override
    public CompletableFuture<Void> joinServer(String serverId) {
        Minecraft minecraft = Minecraft.getInstance();
        //? if >=1.21.9 {
        MinecraftSessionService sessions = minecraft.services().sessionService();
        //?} else {
        /*MinecraftSessionService sessions = minecraft.getMinecraftSessionService();
        *///?}
        User user = minecraft.getUser();
        return CompletableFuture.runAsync(() -> {
            try {
                //? if >=1.20.2 {
                sessions.joinServer(user.getProfileId(), user.getAccessToken(), serverId);
                //?} else {
                /*sessions.joinServer(user.getGameProfile(), user.getAccessToken(), serverId);
                *///?}
                Svco.LOGGER.debug("Registered relay session with Mojang (serverId {})", serverId);
            } catch (Exception e) {
                // Surfaced to the relay as a failed hasJoined; rethrow so the
                // caller can log it once with context.
                throw new RuntimeException("Mojang joinServer failed", e);
            }
        });
    }
}
