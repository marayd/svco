package org.mryd.svco.client;

import org.mryd.svco.Svco;
import org.mryd.svco.client.bridge.LocalVoiceBridge;
import org.mryd.svco.client.net.VoiceSecret;
import org.mryd.svco.client.p2p.P2pCodec;
import org.mryd.svco.client.p2p.PeerManager;
import org.mryd.svco.client.p2p.RoomCrypto;
import org.mryd.svco.client.p2p.UdpChannel;
import org.mryd.svco.client.proxy.ProxySettings;
import org.mryd.svco.client.proxy.Socks5;
import org.mryd.svco.client.proxy.Socks5UdpChannel;
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
import java.util.concurrent.atomic.AtomicBoolean;
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
 *
 * <p>With a SOCKS5 proxy configured, both the WebSocket and the UDP socket
 * (relay and direct peer traffic alike) go through it, and a proxy that is
 * unreachable or lacks UDP support fails the connect — there is no silent
 * fallback to a direct connection that would expose the player's address.
 */
public final class VoiceSession implements AutoCloseable {

    private static final int PROXY_TIMEOUT_MS = 10_000;

    private final SignalingClient signaling;
    private final LocalVoiceBridge bridge;
    private final PeerManager peers;
    private final VoiceSecret bridgeSecret;
    private final PeerStateSync stateSync;

    private final AtomicBoolean closed = new AtomicBoolean();

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
        ProxySettings proxy;
        try {
            proxy = ProxySettings.fromConfig(config);
        } catch (IOException e) {
            return CompletableFuture.failedFuture(e); // misconfigured proxy: never go direct instead
        }
        CompletableFuture<VoiceSession> result = new CompletableFuture<>();
        AtomicReference<SignalingClient> clientRef = new AtomicReference<>();
        AtomicReference<UdpChannel> udpRef = new AtomicReference<>();

        // The JDK WebSocket listener delivers messages sequentially, so the
        // session created in onJoined is always visible to later callbacks.
        var listener = new SignalingClient.Listener() {
            volatile VoiceSession session;

            @Override
            public void onJoined(SignalMessages.Joined joined) {
                try {
                    session = assemble(config, playerUuid, joined, clientRef::get, udpRef.get(), proxy,
                            this::onClosed);
                    if (!result.complete(session)) {
                        session.close(); // the connect was abandoned meanwhile
                    }
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
                } else if (s.closeOnce()) {
                    onClosed.accept(reason);
                }
            }
        };

        // Behind a proxy the UDP association comes first: a proxy without
        // UDP support fails here, before the relay ever hears from us.
        CompletableFuture<UdpChannel> udp = proxy == null
                ? CompletableFuture.completedFuture(null)
                : Socks5.async("svco-socks5-udp", () -> Socks5UdpChannel.open(proxy, PROXY_TIMEOUT_MS));
        udp.thenCompose(channel -> {
                    udpRef.set(channel);
                    return SignalingClient.connect(SvcoConfig.RELAY_URL, proxy, playerUuid, playerName, room,
                            new MojangAuth(), listener);
                })
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
        // Until a session owns it, a failed connect must release the association.
        result.whenComplete((session, e) -> {
            UdpChannel channel = udpRef.get();
            if (e != null && channel != null) {
                channel.close();
            }
        });
        return result;
    }

    /**
     * @param proxiedUdp the proxy's UDP association, or null to open a plain socket
     * @param onFailure  reports the UDP side dying (e.g. the proxy dropping the association)
     */
    private static VoiceSession assemble(SvcoConfig config, UUID playerUuid,
                                         SignalMessages.Joined joined,
                                         java.util.function.Supplier<SignalingClient> signaling,
                                         UdpChannel proxiedUdp, ProxySettings proxy,
                                         Consumer<String> onFailure)
            throws IOException {
        byte[] roomKey = Base64.getDecoder().decode(joined.roomKey);
        byte[] token = HexFormat.of().parseHex(joined.sessionToken);
        String relayHost = URI.create(SvcoConfig.RELAY_URL).getHost();
        InetSocketAddress relayUdp = new InetSocketAddress(relayHost, joined.udpPort);
        if (relayUdp.isUnresolved()) {
            if (proxy == null) {
                throw new IOException("Cannot resolve relay host " + relayHost);
            }
            // The proxy resolved it for the WebSocket; let it do the same for UDP
            relayUdp = InetSocketAddress.createUnresolved(relayHost, joined.udpPort);
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

        UdpChannel udp;
        try {
            udp = proxiedUdp != null ? proxiedUdp : UdpChannel.direct();
        } catch (IOException e) {
            bridge.close();
            throw e;
        }
        PeerManager peers = new PeerManager(
                playerUuid, crypto, token, relayUdp, config.punchTimeoutMs, udp,
                bridge::injectSound,
                (toUuid, candidates) -> {
                    SignalingClient s = signaling.get();
                    if (s != null) {
                        s.sendCandidates(toUuid, candidates);
                    }
                },
                onFailure);
        peersRef.set(peers);

        PeerStateSync stateSync = new PeerStateSync();
        VoiceSession session = new VoiceSession(signaling.get(), bridge, peers, bridgeSecret, stateSync);

        bridge.start();
        peers.start();
        for (SignalMessages.PeerInfo peer : joined.peers) {
            session.addPeer(UUID.fromString(peer.playerUuid), peer.playerName);
        }
        Svco.LOGGER.info("Voice session up: bridge on 127.0.0.1:{}, {} peer(s), relay udp {}{}",
                bridge.port(), joined.peers.size(), relayUdp,
                proxy == null ? "" : ", all traffic via SOCKS5 proxy " + proxy);
        return session;
    }

    @Override
    public void close() {
        closeOnce();
    }

    /** Closes the session; true only for the call that actually closed it. */
    private boolean closeOnce() {
        if (!closed.compareAndSet(false, true)) {
            return false;
        }
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
        return true;
    }
}
