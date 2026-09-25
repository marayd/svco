package org.mryd.svco.client;

import de.maxhenkel.voicechat.intercompatibility.ClientCompatibilityManager;
import de.maxhenkel.voicechat.net.SecretPacket;
import de.maxhenkel.voicechat.voice.client.ClientManager;
import de.maxhenkel.voicechat.voice.client.ClientVoicechat;
import de.maxhenkel.voicechat.voice.client.ClientVoicechatConnection;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import org.mryd.svco.Svco;
import org.mryd.svco.client.mixin.ClientManagerAccessor;

import java.util.UUID;

/**
 * Watches every server join: if SVC's client has not established a voice
 * connection within the configured timeout (i.e. the server has no voice chat
 * plugin/mod), brings up a {@link VoiceSession} — relay signaling, P2P links
 * to other addon users on the same server, and a loopback voice bridge — then
 * feeds a fabricated SecretPacket pointing at the bridge into SVC.
 *
 * If a real SecretPacket arrives later, SVC's own authenticate() replaces the
 * loopback connection with the real one; the session is then torn down.
 *
 * When the relay session drops (or the initial connect fails) and auto
 * reconnect is enabled, retries follow an exponential backoff (5s, 10s, 20s,
 * 40s, 60s, 60s...) until {@code maxReconnectAttempts} is exhausted.
 *
 * All state transitions happen on the client thread: tick events run there,
 * and async connect callbacks are marshalled through {@code minecraft.execute}.
 */
public class FallbackManager {

    public enum Status {
        /** Not on a multiplayer server, or the mod is disabled. */
        IDLE,
        /** Waiting to see whether the server provides real voice chat. */
        DETECTING,
        /** The server has its own voice chat; the relay stands down. */
        SERVER_VOICE,
        /** Relay connect in flight. */
        CONNECTING,
        /** Relay session active. */
        CONNECTED,
        /** Waiting out the backoff before the next reconnect attempt. */
        RECONNECTING,
        /** Gave up (auto reconnect off or attempts exhausted). */
        FAILED
    }

    private static FallbackManager instance;

    private Status status = Status.IDLE;
    private int ticksWaited;
    private int retryDelayTicks;
    private int reconnectAttempt;
    private String lastError = "";
    private VoiceSession session;
    /** Bumped whenever in-flight connects must be discarded. */
    private int generation;

    public static FallbackManager instance() {
        return instance;
    }

    public void register() {
        instance = this;
        ClientPlayConnectionEvents.JOIN.register((handler, sender, minecraft) -> onJoin(minecraft));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, minecraft) -> reset());
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    // ---- GUI-facing state ----------------------------------------------

    public synchronized Status status() {
        return status;
    }

    public synchronized VoiceSession session() {
        return session;
    }

    public synchronized int reconnectAttempt() {
        return reconnectAttempt;
    }

    /** Seconds until the next reconnect attempt (RECONNECTING only). */
    public synchronized int retrySeconds() {
        return (retryDelayTicks + 19) / 20;
    }

    public synchronized String lastError() {
        return lastError;
    }

    public synchronized boolean canReconnectNow() {
        return switch (status) {
            case CONNECTED, RECONNECTING, FAILED -> true;
            default -> false;
        };
    }

    /** Manual reconnect from the settings screen: drops the wait/backoff. */
    public synchronized void reconnectNow() {
        if (!canReconnectNow()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        closeSession();
        reconnectAttempt = 0;
        startConnect(minecraft);
    }

    /** Called by the settings screen when the master toggle flips. */
    public synchronized void onEnabledChanged(boolean enabled) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!enabled) {
            if (status != Status.IDLE) {
                generation++;
                closeSession();
                status = Status.IDLE;
                reconnectAttempt = 0;
            }
            return;
        }
        if (status == Status.IDLE && minecraft.player != null && !minecraft.isLocalServer()) {
            if (hasRealVoiceConnection()) {
                status = Status.SERVER_VOICE;
            } else {
                startConnect(minecraft);
            }
        }
    }

    // ---- lifecycle -------------------------------------------------------

    private void onJoin(Minecraft minecraft) {
        reset();
        if (!SvcoConfig.get().enabled) {
            return;
        }
        if (minecraft.isLocalServer()) {
            // Singleplayer/LAN host runs its own SVC server
            return;
        }
        status = Status.DETECTING;
        ticksWaited = 0;
    }

    private synchronized void reset() {
        generation++;
        status = Status.IDLE;
        ticksWaited = 0;
        retryDelayTicks = 0;
        reconnectAttempt = 0;
        lastError = "";
        closeSession();
    }

    private synchronized void closeSession() {
        if (session != null) {
            int bridgePort = session.bridgePort();
            session.close();
            session = null;
            emitSvcDisconnectIfOnBridge(bridgePort);
        }
    }

    /**
     * Tells SVC the voice connection is gone the moment the relay session dies,
     * instead of letting it sit on the dead loopback bridge until its own
     * 10-second keepalive timeout. This flips the HUD icon state immediately
     * (crossed-out microphone), exactly like losing the connection to a real
     * voice chat server. Guarded by the bridge port so a connection to a real
     * server that replaced the bridge is never torn down.
     */
    private static void emitSvcDisconnectIfOnBridge(int bridgePort) {
        ClientVoicechatConnection connection = currentSvcConnection();
        if (connection == null) {
            return;
        }
        if (connection.getData().getServerPort() == bridgePort
                && connection.getAddress().isLoopbackAddress()) {
            ClientCompatibilityManager.INSTANCE.emitVoiceChatDisconnectedEvent();
        }
    }

    private void onTick(Minecraft minecraft) {
        if (minecraft.player == null) {
            return;
        }
        switch (status) {
            case DETECTING -> tickDetecting(minecraft);
            case RECONNECTING -> tickReconnecting(minecraft);
            case CONNECTED -> tickConnected();
            default -> {
            }
        }
    }

    private void tickDetecting(Minecraft minecraft) {
        if (hasRealVoiceConnection()) {
            status = Status.SERVER_VOICE;
            Svco.LOGGER.info("Server provides voice chat, relay fallback not needed");
            return;
        }
        ticksWaited++;
        if (ticksWaited < SvcoConfig.get().detectTimeoutTicks) {
            return;
        }
        startConnect(minecraft);
    }

    private void tickReconnecting(Minecraft minecraft) {
        if (hasRealVoiceConnection()) {
            status = Status.SERVER_VOICE;
            return;
        }
        if (retryDelayTicks > 0) {
            retryDelayTicks--;
            return;
        }
        startConnect(minecraft);
    }

    /**
     * A real SecretPacket may arrive while the relay session is live: SVC's
     * authenticate() then swaps the loopback connection for the real one. Spot
     * the swap (connection no longer points at our bridge) and stand down.
     */
    private synchronized void tickConnected() {
        if (session == null) {
            return;
        }
        ClientVoicechatConnection connection = currentSvcConnection();
        if (connection != null
                && (connection.getData().getServerPort() != session.bridgePort()
                || !connection.getAddress().isLoopbackAddress())) {
            Svco.LOGGER.info("Real voice connection took over, closing relay session");
            VoiceSession old = session;
            session = null; // the real connection must not be torn down
            old.close();
            status = Status.SERVER_VOICE;
        }
    }

    private static ClientVoicechatConnection currentSvcConnection() {
        ClientVoicechat client = ClientManager.getClient();
        return client == null ? null : client.getConnection();
    }

    private static boolean hasRealVoiceConnection() {
        return currentSvcConnection() != null;
    }

    // ---- connect flow ----------------------------------------------------

    private synchronized void startConnect(Minecraft minecraft) {
        String room = currentServerAddress(minecraft);
        if (room == null) {
            Svco.LOGGER.warn("Cannot determine server address, skipping relay fallback");
            status = Status.IDLE;
            return;
        }
        UUID playerUuid = minecraft.player.getUUID();
        //? if >=1.21.9 {
        String playerName = minecraft.player.getGameProfile().name();
        //?} else {
        /*String playerName = minecraft.player.getGameProfile().getName();
        *///?}
        SvcoConfig config = SvcoConfig.get();
        int gen = generation;

        status = Status.CONNECTING;
        Svco.LOGGER.info("No voice chat on server, joining relay {} (room '{}')", SvcoConfig.RELAY_URL, room);

        VoiceSession.connect(config, playerUuid, playerName, room,
                        reason -> minecraft.execute(() -> onSessionLost(gen, reason)))
                .thenAccept(newSession -> minecraft.execute(() -> activate(minecraft, gen, newSession, config, playerUuid)))
                .exceptionally(e -> {
                    minecraft.execute(() -> onConnectFailed(gen, e));
                    return null;
                });
    }

    private synchronized void activate(Minecraft minecraft, int gen, VoiceSession newSession,
                                       SvcoConfig config, UUID playerUuid) {
        // The player may have disconnected or a real secret may have arrived while joining
        if (gen != generation || status != Status.CONNECTING || minecraft.player == null
                || !minecraft.player.getUUID().equals(playerUuid)) {
            newSession.close();
            return;
        }
        if (hasRealVoiceConnection()) {
            Svco.LOGGER.info("Real voice connection appeared, discarding relay session");
            newSession.close();
            status = Status.SERVER_VOICE;
            return;
        }
        try {
            session = newSession;
            SecretPacket packet = SecretPacketFactory.create(newSession, config, playerUuid);
            ((ClientManagerAccessor) ClientManager.instance()).svco$authenticate(packet);
            status = Status.CONNECTED;
            boolean recovered = reconnectAttempt > 0;
            reconnectAttempt = 0;
            lastError = "";
            Notifier.success(recovered ? "svco.msg.reconnected" : "svco.msg.connected");
        } catch (Exception e) {
            Svco.LOGGER.error("Failed to inject relay secret", e);
            closeSession();
            lastError = rootMessage(e);
            scheduleRetryOrFail("svco.msg.handshake_failed");
        }
    }

    private synchronized void onConnectFailed(int gen, Throwable e) {
        if (gen != generation || status != Status.CONNECTING) {
            return;
        }
        Svco.LOGGER.error("Relay connection failed", e);
        lastError = rootMessage(e);
        scheduleRetryOrFail("svco.msg.unavailable");
    }

    private synchronized void onSessionLost(int gen, String reason) {
        if (gen != generation || status != Status.CONNECTED) {
            return;
        }
        closeSession();
        lastError = reason == null ? "" : reason;
        scheduleRetryOrFail("svco.msg.disconnected");
    }

    /**
     * After a failure: either arms the backoff timer for the next attempt or
     * gives up. {@code messageKey} describes what just went wrong; the
     * translated message gets {@link #lastError} appended as its argument.
     */
    private void scheduleRetryOrFail(String messageKey) {
        SvcoConfig config = SvcoConfig.get();
        if (!config.autoReconnect) {
            status = Status.FAILED;
            Notifier.error(messageKey, lastError);
            return;
        }
        reconnectAttempt++;
        if (config.maxReconnectAttempts > 0 && reconnectAttempt > config.maxReconnectAttempts) {
            status = Status.FAILED;
            Notifier.error("svco.msg.gave_up", config.maxReconnectAttempts);
            return;
        }
        int delaySeconds = Math.min(5 << Math.min(reconnectAttempt - 1, 4), 60);
        retryDelayTicks = delaySeconds * 20;
        status = Status.RECONNECTING;
        Notifier.info("svco.msg.reconnecting", lastError, delaySeconds, reconnectAttempt,
                config.maxReconnectAttempts > 0 ? String.valueOf(config.maxReconnectAttempts) : "∞");
    }

    private static String currentServerAddress(Minecraft minecraft) {
        ServerData server = minecraft.getCurrentServer();
        if (server == null || server.ip == null || server.ip.isBlank()) {
            return null;
        }
        return server.ip.toLowerCase();
    }

    private static String rootMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null ? cause.getClass().getSimpleName() : message;
    }
}
