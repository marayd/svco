package org.mryd.svco.client;

import org.mryd.svco.Svco;
import org.mryd.svco.client.bridge.LocalVoiceBridge;
import org.mryd.svco.client.net.VoiceSecret;
import org.mryd.svco.client.p2p.P2pCodec;
import org.mryd.svco.client.p2p.PeerManager;
import org.mryd.svco.client.p2p.RoomCrypto;
import org.mryd.svco.client.signal.MojangAuth;
import org.mryd.svco.client.signal.SignalMessages;
import org.mryd.svco.client.signal.SignalingClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * One relay session: signaling connection + local voice bridge + P2P engine,
 * created after {@code joined} arrives and torn down as a unit.
 *
 * <pre>
 * SVC client ⇄ LocalVoiceBridge ⇄ PeerManager ⇄ peers (direct UDP)
 *                                             ⇄ relay (FWD fallback)
 *                     SignalingClient ⇄ relay (WebSocket)
 * </pre>
 */
public final class VoiceSession implements AutoCloseable {

    private final SignalingClient signaling;
    private final LocalVoiceBridge bridge;
    private final PeerManager peers;
    private final VoiceSecret bridgeSecret;
    private final PeerStateSync stateSync;

    private volatile boolean closed;

    private VoiceSession(SignalingClient signaling, LocalVoiceBridge bridge,
                         PeerManager peers, VoiceSecret bridgeSecret, PeerStateSync stateSync) {
        this.signaling = signaling;
        this.bridge = bridge;
        this.peers = peers;
        this.bridgeSecret = bridgeSecret;
        this.stateSync = stateSync;
    }

    private void addPeer(UUID uuid, String name) {
        peers.addPeer(uuid, name);
        stateSync.peerJoined(uuid, name);
    }

    private void removePeer(UUID uuid) {
        peers.removePeer(uuid);
        stateSync.peerLeft(uuid);
    }

    /** The loopback port the fabricated SecretPacket must point SVC at. */
    public int bridgePort() {
        return bridge.port();
    }

    /** The secret the fabricated SecretPacket must carry. */
    public VoiceSecret bridgeSecret() {
        return bridgeSecret;
    }

    public int peerCount() {
        return peers.peerCount();
    }

    public long directPeerCount() {
        return peers.directPeerCount();
    }

    public List<PeerManager.PeerSnapshot> snapshotPeers() {
        return peers.snapshotPeers();
    }

    /**
     * Connects to the relay and brings the whole session up. The future
     * completes once {@code joined} is processed and the bridge is listening.
     *
     * @param onClosed invoked (from a network thread) if the signaling
     *                 connection drops after a successful start
     */
    public static CompletableFuture<VoiceSession> connect(SvcoConfig config, UUID playerUuid,
                                                          String playerName, String room,
                                                          Consumer<String> onClosed) {
        CompletableFuture<VoiceSession> result = new CompletableFuture<>();
        AtomicReference<SignalingClient> clientRef = new AtomicReference<>();

        // The JDK WebSocket listener delivers messages sequentially, so the
        // session created in onJoined is always visible to later callbacks.
        var listener = new SignalingClient.Listener() {
            volatile VoiceSession session;

            @Override
            public void onJoined(SignalMessages.Joined joined) {
                try {
                    session = assemble(config, playerUuid, joined, clientRef::get);
                    result.complete(session);
                } catch (Exception e) {
                    result.completeExceptionally(e);
                }
            }

            @Override
            public void onPeerJoined(String uuid, String name) {
                VoiceSession s = session;
                if (s != null) {
                    s.addPeer(UUID.fromString(uuid), name);
                }
            }

            @Override
            public void onPeerLeft(String uuid) {
                VoiceSession s = session;
                if (s != null) {
                    s.removePeer(UUID.fromString(uuid));
                }
            }

            @Override
            public void onCandidates(String fromUuid, List<SignalMessages.Candidate> candidates) {
                VoiceSession s = session;
                if (s != null) {
                    s.peers.onRemoteCandidates(UUID.fromString(fromUuid), candidates);
                }
            }

            @Override
            public void onClosed(String reason) {
                VoiceSession s = session;
                if (s == null) {
                    result.completeExceptionally(new IOException("Relay rejected join: " + reason));
                } else if (!s.closed) {
                    s.close();
                    onClosed.accept(reason);
                }
            }
        };

        SignalingClient.connect(SvcoConfig.RELAY_URL, playerUuid, playerName, room,
                        new MojangAuth(), listener)
                .whenComplete((client, error) -> {
                    if (error != null) {
                        result.completeExceptionally(error);
                        return;
                    }
                    clientRef.set(client);
                    // Whatever kills the future before joining also closes the socket.
                    result.whenComplete((session, e) -> {
                        if (e != null) {
                            client.close();
                        }
                    });
                });
        return result;
    }

    private static VoiceSession assemble(SvcoConfig config, UUID playerUuid,
                                         SignalMessages.Joined joined,
                                         java.util.function.Supplier<SignalingClient> signaling)
            throws IOException {
        byte[] roomKey = Base64.getDecoder().decode(joined.roomKey);
        byte[] token = HexFormat.of().parseHex(joined.sessionToken);
        String relayHost = URI.create(SvcoConfig.RELAY_URL).getHost();
        InetSocketAddress relayUdp = new InetSocketAddress(relayHost, joined.udpPort);
        if (relayUdp.isUnresolved()) {
            throw new IOException("Cannot resolve relay host " + relayHost);
        }

        RoomCrypto crypto = new RoomCrypto(roomKey);
        VoiceSecret bridgeSecret = VoiceSecret.generate();

        // Bridge -> peers: mic frames captured from the local SVC client.
        // Peers -> bridge: remote frames played back through SVC.
        AtomicReference<PeerManager> peersRef = new AtomicReference<>();
        int voiceDistance = P2pCodec.clampDistance((int) Math.round(config.voiceDistance));
        LocalVoiceBridge bridge = new LocalVoiceBridge(
                playerUuid, bridgeSecret, 1000,
                (opus, seq, whispering) -> {
                    PeerManager p = peersRef.get();
                    if (p != null) {
                        p.broadcastVoice(opus, seq, whispering, voiceDistance);
                    }
                });

        PeerManager peers;
        try {
            peers = new PeerManager(
                    playerUuid, crypto, token, relayUdp, config.punchTimeoutMs,
                    bridge::injectSound,
                    (toUuid, candidates) -> {
                        SignalingClient s = signaling.get();
                        if (s != null) {
                            s.sendCandidates(toUuid, candidates);
                        }
                    });
        } catch (IOException e) {
            bridge.close();
            throw e;
        }
        peersRef.set(peers);

        PeerStateSync stateSync = new PeerStateSync();
        VoiceSession session = new VoiceSession(signaling.get(), bridge, peers, bridgeSecret, stateSync);

        bridge.start();
        peers.start();
        for (SignalMessages.PeerInfo peer : joined.peers) {
            session.addPeer(UUID.fromString(peer.playerUuid), peer.playerName);
        }
        Svco.LOGGER.info("Voice session up: bridge on 127.0.0.1:{}, {} peer(s), relay udp {}",
                bridge.port(), joined.peers.size(), relayUdp);
        return session;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            if (signaling != null) {
                signaling.close();
            }
        } catch (Exception ignored) {
        }
        peers.close();
        bridge.close();
        stateSync.clear();
        Svco.LOGGER.info("Voice session closed");
    }
}
