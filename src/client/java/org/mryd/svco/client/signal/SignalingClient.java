package org.mryd.svco.client.signal;

import com.google.gson.Gson;
import org.mryd.svco.Svco;
import org.mryd.svco.client.proxy.ProxiedWebSocket;
import org.mryd.svco.client.proxy.ProxySettings;
import org.mryd.svco.client.proxy.Socks5;

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
 *
 * <p>Transport: the JDK WebSocket for direct connections, or
 * {@link ProxiedWebSocket} through a SOCKS5 proxy so the relay never sees
 * the player's address.
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
    private static final int CONNECT_TIMEOUT_MS = 10_000;

    /** The two ways a message can reach the relay. */
    private interface Transport {
        void sendText(String text);

        void sendClose();
    }

    private final Listener listener;
    private final SessionAuthenticator authenticator;
    private final UUID playerUuid;
    private final String playerName;
    private final String room;

    private volatile Transport transport;
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
     * http(s) URL; the ws(s) endpoint is derived from it. With a non-null
     * {@code proxy} the connection is tunnelled through it, never direct.
     */
    public static CompletableFuture<SignalingClient> connect(String relayUrl, ProxySettings proxy,
                                                             UUID playerUuid, String playerName, String room,
                                                             SessionAuthenticator authenticator,
                                                             Listener listener) {
        URI wsUri = websocketUri(relayUrl);
        SignalingClient client = new SignalingClient(listener, authenticator, playerUuid, playerName, room);
        if (proxy != null) {
            return Socks5.async("svco-signal-connect", () -> {
                ProxiedWebSocket ws = ProxiedWebSocket.connect(proxy, wsUri, CONNECT_TIMEOUT_MS);
                client.transport = new Transport() {
                    @Override
                    public void sendText(String text) {
                        ws.sendText(text);
                    }

                    @Override
                    public void sendClose() {
                        ws.sendClose(ProxiedWebSocket.NORMAL_CLOSURE, "leaving");
                    }
                };
                // Only now: the challenge may already be waiting in the socket
                ws.start(client.new ProxiedHandler());
                return client;
            });
        }
        return HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
                .buildAsync(wsUri, client.new JdkHandler())
                .thenApply(ws -> client);
    }

    static URI websocketUri(String relayUrl) {
        String base = relayUrl.replaceAll("/+$", "");
        String ws = base.replaceFirst("^http", "ws"); // http->ws, https->wss
        return URI.create(ws + "/ws");
    }

    /** Sends our candidates to one peer (routed by the relay). */
    public void sendCandidates(String toUuid, List<SignalMessages.Candidate> candidates) {
        Transport t = transport;
        if (t == null || closed) {
            return;
        }
        SignalMessages.Candidates msg = new SignalMessages.Candidates();
        msg.to = toUuid;
        msg.candidates = candidates;
        t.sendText(GSON.toJson(msg));
    }

    @Override
    public void close() {
        closed = true;
        Transport t = transport;
        if (t != null) {
            try {
                t.sendClose();
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
        Transport t = transport;
        if (t == null || closed || joinSent) {
            return;
        }
        joinSent = true;
        SignalMessages.Join join = new SignalMessages.Join();
        join.playerUuid = playerUuid.toString();
        join.playerName = playerName;
        join.room = room;
        join.serverId = serverId;
        t.sendText(GSON.toJson(join));
    }

    private void dispatch(String message) {
        SignalMessages.Envelope envelope;
        try {
            envelope = GSON.fromJson(message, SignalMessages.Envelope.class);
        } catch (Exception e) {
            Svco.LOGGER.warn("Bad signaling message: {}", message, e);
            return;
        }
        if (envelope == null || envelope.type == null) {
            return;
        }
        try {
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
        } catch (Exception e) {
            Svco.LOGGER.warn("Bad signaling message: {}", message, e);
        }
    }

    private void handleClose(String reason) {
        if (!closed) {
            String error = lastError;
            String effective = reason != null && !reason.isEmpty() ? reason
                    : error != null ? error
                    : "connection closed";
            listener.onClosed(effective);
        }
    }

    private void handleError(Throwable error) {
        if (!closed) {
            Svco.LOGGER.warn("Signaling connection failed", error);
            listener.onClosed(error.getMessage() == null ? "connection error" : error.getMessage());
        }
    }

    private final class JdkHandler implements WebSocket.Listener {

        private final StringBuilder buffer = new StringBuilder();

        /**
         * Runs before any message is delivered. The transport must be set
         * here, not when buildAsync completes: the challenge can arrive
         * first, and a join answered before then would be dropped.
         */
        @Override
        public void onOpen(WebSocket ws) {
            transport = new Transport() {
                @Override
                public void sendText(String text) {
                    ws.sendText(text, true);
                }

                @Override
                public void sendClose() {
                    ws.sendClose(WebSocket.NORMAL_CLOSURE, "leaving");
                }
            };
            ws.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String message = buffer.toString();
                buffer.setLength(0);
                dispatch(message);
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            handleClose(reason);
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            handleError(error);
        }
    }

    private final class ProxiedHandler implements ProxiedWebSocket.Listener {

        @Override
        public void onText(String message) {
            dispatch(message);
        }

        @Override
        public void onClose(int statusCode, String reason) {
            handleClose(reason);
        }

        @Override
        public void onError(Throwable error) {
            handleError(error);
        }
    }
}
