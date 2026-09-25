package org.mryd.svco.client.signal;

import com.google.gson.Gson;
import org.mryd.svco.Svco;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Signaling connection to the relay: one WebSocket carrying room
 * membership events and candidate exchange. All callbacks fire on the
 * WebSocket's listener thread; consumers hop threads as needed.
 *
 * <p>Handshake: the relay sends a {@code challenge} first; the client
 * registers the challenge's serverId with Mojang (joinServer) and only
 * then sends {@code join}. The relay verifies the session via hasJoined.
 */
public final class SignalingClient implements AutoCloseable {

    /** Room and membership events, plus incoming candidates. */
    public interface Listener {
        void onJoined(SignalMessages.Joined joined);

        void onPeerJoined(String playerUuid, String playerName);

        void onPeerLeft(String playerUuid);

        void onCandidates(String fromUuid, List<SignalMessages.Candidate> candidates);

        void onClosed(String reason);
    }

    /**
     * Registers a relay challenge with the Mojang session service
     * (async; must not block the caller). Completing exceptionally is
     * tolerated: the join is still sent, and a relay running with
     * authentication enabled will reject it with a clear message.
     */
    @FunctionalInterface
    public interface SessionAuthenticator {
        CompletableFuture<Void> joinServer(String serverId);
    }

    private static final Gson GSON = new Gson();

    private final Listener listener;
    private final SessionAuthenticator authenticator;
    private final UUID playerUuid;
    private final String playerName;
    private final String room;

    private volatile WebSocket webSocket;
    private volatile boolean closed;
    private volatile boolean joinSent;
    /** Last relay error message; used as the close reason (the relay
     *  sends an {@code error} then closes with an empty reason). */
    private volatile String lastError;

    private SignalingClient(Listener listener, SessionAuthenticator authenticator,
                            UUID playerUuid, String playerName, String room) {
        this.listener = listener;
        this.authenticator = authenticator;
        this.playerUuid = playerUuid;
        this.playerName = playerName;
        this.room = room;
    }

    /**
     * Connects and waits for the relay's challenge; the join is sent after
     * {@code authenticator} completes. {@code relayUrl} is the configured
     * http(s) URL; the ws(s) endpoint is derived from it.
     */
    public static CompletableFuture<SignalingClient> connect(String relayUrl, UUID playerUuid,
                                                             String playerName, String room,
                                                             SessionAuthenticator authenticator,
                                                             Listener listener) {
        URI wsUri = websocketUri(relayUrl);
        SignalingClient client = new SignalingClient(listener, authenticator, playerUuid, playerName, room);
        return HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(wsUri, client.new Handler())
                .thenApply(ws -> {
                    client.webSocket = ws;
                    return client;
                });
    }

    static URI websocketUri(String relayUrl) {
        String base = relayUrl.replaceAll("/+$", "");
        String ws = base.replaceFirst("^http", "ws"); // http->ws, https->wss
        return URI.create(ws + "/ws");
    }

    /** Sends our candidates to one peer (routed by the relay). */
    public void sendCandidates(String toUuid, List<SignalMessages.Candidate> candidates) {
        WebSocket ws = webSocket;
        if (ws == null || closed) {
            return;
        }
        SignalMessages.Candidates msg = new SignalMessages.Candidates();
        msg.to = toUuid;
        msg.candidates = candidates;
        ws.sendText(GSON.toJson(msg), true);
    }

    @Override
    public void close() {
        closed = true;
        WebSocket ws = webSocket;
        if (ws != null) {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "leaving");
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Runs the Mojang handshake for the received challenge, then sends
     * join. Auth failures are logged but not fatal client-side: the relay
     * decides whether an unverified join is acceptable.
     */
    private void onChallenge(String serverId) {
        if (joinSent) {
            Svco.LOGGER.debug("Ignoring duplicate relay challenge");
            return;
        }
        authenticator.joinServer(serverId)
                .exceptionally(e -> {
                    Svco.LOGGER.warn("Mojang session join failed (offline account?): {}", e.toString());
                    return null;
                })
                .thenRun(() -> sendJoin(serverId));
    }

    private synchronized void sendJoin(String serverId) {
        WebSocket ws = webSocket;
        if (ws == null || closed || joinSent) {
            return;
        }
        joinSent = true;
        SignalMessages.Join join = new SignalMessages.Join();
        join.playerUuid = playerUuid.toString();
        join.playerName = playerName;
        join.room = room;
        join.serverId = serverId;
        ws.sendText(GSON.toJson(join), true);
    }

    private final class Handler implements WebSocket.Listener {

        private final StringBuilder buffer = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String message = buffer.toString();
                buffer.setLength(0);
                try {
                    dispatch(message);
                } catch (Exception e) {
                    Svco.LOGGER.warn("Bad signaling message: {}", message, e);
                }
            }
            ws.request(1);
            return null;
        }

        private void dispatch(String message) {
            SignalMessages.Envelope envelope = GSON.fromJson(message, SignalMessages.Envelope.class);
            if (envelope == null || envelope.type == null) {
                return;
            }
            switch (envelope.type) {
                case "challenge" -> {
                    SignalMessages.Challenge msg = GSON.fromJson(message, SignalMessages.Challenge.class);
                    if (msg.serverId != null && !msg.serverId.isBlank()) {
                        onChallenge(msg.serverId);
                    }
                }
                case "joined" -> listener.onJoined(GSON.fromJson(message, SignalMessages.Joined.class));
                case "peer-joined" -> {
                    SignalMessages.PeerJoined msg = GSON.fromJson(message, SignalMessages.PeerJoined.class);
                    listener.onPeerJoined(msg.playerUuid, msg.playerName);
                }
                case "peer-left" -> {
                    SignalMessages.PeerLeft msg = GSON.fromJson(message, SignalMessages.PeerLeft.class);
                    listener.onPeerLeft(msg.playerUuid);
                }
                case "candidates" -> {
                    SignalMessages.Candidates msg = GSON.fromJson(message, SignalMessages.Candidates.class);
                    if (msg.from != null && msg.candidates != null) {
                        listener.onCandidates(msg.from, msg.candidates);
                    }
                }
                case "error" -> {
                    SignalMessages.Error msg = GSON.fromJson(message, SignalMessages.Error.class);
                    lastError = msg.message;
                    Svco.LOGGER.warn("Relay signaling error: {}", msg.message);
                }
                default -> Svco.LOGGER.debug("Unknown signaling message type {}", envelope.type);
            }
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            if (!closed) {
                String error = lastError;
                String effective = reason != null && !reason.isEmpty() ? reason
                        : error != null ? error
                        : "connection closed";
                listener.onClosed(effective);
            }
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            if (!closed) {
                Svco.LOGGER.warn("Signaling connection failed", error);
                listener.onClosed(error.getMessage() == null ? "connection error" : error.getMessage());
            }
        }
    }
}
