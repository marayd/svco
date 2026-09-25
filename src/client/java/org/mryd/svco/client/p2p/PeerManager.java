package org.mryd.svco.client.p2p;

import org.mryd.svco.Svco;
import org.mryd.svco.client.signal.SignalMessages;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * The P2P engine: one UDP socket shared by relay binds, hole punching and
 * voice, so all traffic reuses a single NAT mapping.
 *
 * <p>For every peer it tries to establish a direct path by simultaneous
 * hole punching against the peer's advertised candidates; until that
 * succeeds (and whenever the direct path goes stale) voice falls back to
 * blind forwarding through the relay. Voice is sealed once per frame with
 * the room key — the same ciphertext serves every peer on both paths.
 */
public final class PeerManager implements AutoCloseable {

    /** Receives decrypted voice frames from remote peers. */
    public interface VoiceListener {
        void onVoice(UUID sender, byte[] opus, long sequenceNumber, boolean whispering, int distance);
    }

    private static final long TICK_MS = 200;
    private static final long PUNCH_INTERVAL_MS = 200;
    private static final long BIND_INTERVAL_MS = 15_000;
    private static final long DIRECT_KEEPALIVE_MS = 10_000;
    private static final long DIRECT_STALE_MS = 30_000;
    private static final long REPUNCH_BACKOFF_MS = 30_000;

    private final UUID selfUuid;
    private final RoomCrypto crypto;
    private final byte[] sessionToken;
    private final InetSocketAddress relayAddress;
    private final long punchTimeoutMs;
    private final VoiceListener voiceListener;
    private final BiConsumer<String, List<SignalMessages.Candidate>> candidatePublisher;

    private final DatagramSocket socket;
    private final Thread readerThread;
    private final ScheduledExecutorService scheduler;

    private final Map<UUID, Peer> peers = new ConcurrentHashMap<>();

    private volatile SignalMessages.Candidate reflexiveCandidate;
    private volatile long lastBindMs;
    private volatile boolean closed;

    public PeerManager(UUID selfUuid, RoomCrypto crypto, byte[] sessionToken,
                       InetSocketAddress relayAddress, long punchTimeoutMs,
                       VoiceListener voiceListener,
                       BiConsumer<String, List<SignalMessages.Candidate>> candidatePublisher) throws IOException {
        this.selfUuid = selfUuid;
        this.crypto = crypto;
        this.sessionToken = sessionToken.clone();
        this.relayAddress = relayAddress;
        this.punchTimeoutMs = punchTimeoutMs;
        this.voiceListener = voiceListener;
        this.candidatePublisher = candidatePublisher;

        this.socket = new DatagramSocket();
        this.readerThread = new Thread(this::readLoop, "svco-p2p");
        this.readerThread.setDaemon(true);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "svco-p2p-tick");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        readerThread.start();
        sendBind();
        scheduler.scheduleAtFixedRate(this::tick, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
    }

    // ---- membership ----------------------------------------------------

    /** Registers a peer and starts connecting to it. */
    public void addPeer(UUID uuid, String name) {
        Peer peer = new Peer(uuid, name);
        if (peers.putIfAbsent(uuid, peer) != null) {
            return;
        }
        candidatePublisher.accept(uuid.toString(), localCandidates());
        Svco.LOGGER.info("Peer {} ({}) added, voice via relay until punched", name, uuid);
    }

    public void removePeer(UUID uuid) {
        Peer peer = peers.remove(uuid);
        if (peer != null) {
            Svco.LOGGER.info("Peer {} ({}) removed", peer.name, uuid);
        }
    }

    /** Called when a peer's candidates arrive over signaling. */
    public void onRemoteCandidates(UUID uuid, List<SignalMessages.Candidate> candidates) {
        Peer peer = peers.get(uuid);
        if (peer == null) {
            return;
        }
        peer.remoteCandidates.clear();
        peer.remoteCandidates.addAll(candidates);
        startPunching(peer);
    }

    // ---- voice ---------------------------------------------------------

    /**
     * Broadcasts one mic frame to every peer: sealed once, sent directly
     * where a punched path exists, otherwise forwarded through the relay.
     */
    public void broadcastVoice(byte[] opus, long sequenceNumber, boolean whispering, int distance) {
        if (peers.isEmpty()) {
            return;
        }
        int wireDistance = P2pCodec.clampDistance(distance);
        byte[] plaintext = P2pCodec.voicePlaintext(sequenceNumber, whispering, wireDistance, opus);
        byte[] sealed = crypto.seal(plaintext, P2pCodec.aad(P2pCodec.TYPE_VOICE, selfUuid));
        byte[] directPacket = null; // built lazily: not needed if all peers are on fallback
        for (Peer peer : peers.values()) {
            InetSocketAddress direct = peer.path == Peer.Path.DIRECT ? peer.directAddress : null;
            if (direct != null) {
                if (directPacket == null) {
                    directPacket = P2pCodec.voice(selfUuid, sealed);
                }
                send(directPacket, direct);
            } else {
                send(P2pCodec.fwd(sessionToken, peer.uuid, wireDistance, sealed), relayAddress);
            }
        }
    }

    /** Direct-path peer count, for diagnostics. */
    public long directPeerCount() {
        return peers.values().stream().filter(p -> p.path == Peer.Path.DIRECT).count();
    }

    public int peerCount() {
        return peers.size();
    }

    /** Immutable view of one peer's connection state, for the settings GUI. */
    public record PeerSnapshot(UUID uuid, String name, boolean direct) {
    }

    /** Peers sorted by name, each flagged with its current voice path. */
    public List<PeerSnapshot> snapshotPeers() {
        return peers.values().stream()
                .map(p -> new PeerSnapshot(p.uuid, p.name, p.path == Peer.Path.DIRECT))
                .sorted(java.util.Comparator.comparing(PeerSnapshot::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    // ---- socket loop ---------------------------------------------------

    private void readLoop() {
        byte[] buffer = new byte[P2pCodec.MAX_PACKET_SIZE];
        DatagramPacket datagram = new DatagramPacket(buffer, buffer.length);
        while (!closed) {
            try {
                socket.receive(datagram);
                handle(datagram);
            } catch (IOException e) {
                if (!closed) {
                    Svco.LOGGER.warn("P2P socket read failed", e);
                }
                return;
            } catch (Exception e) {
                Svco.LOGGER.warn("P2P packet handling failed", e);
            }
        }
    }

    private void handle(DatagramPacket datagram) {
        byte[] data = datagram.getData();
        int length = datagram.getLength();
        switch (P2pCodec.packetType(data, length)) {
            case P2pCodec.TYPE_BIND_RESP -> handleBindResp(data, length);
            case P2pCodec.TYPE_VOICE -> handleVoice(data, length, datagram);
            case P2pCodec.TYPE_PUNCH -> handlePunch(data, length, datagram, false);
            case P2pCodec.TYPE_PUNCH_ACK -> handlePunch(data, length, datagram, true);
            default -> {
            }
        }
    }

    private void handleBindResp(byte[] data, int length) {
        P2pCodec.BindResp resp = P2pCodec.parseBindResp(data, length);
        if (resp == null) {
            return;
        }
        try {
            String ip = InetAddress.getByAddress(resp.ip()).getHostAddress();
            SignalMessages.Candidate srflx = new SignalMessages.Candidate(ip, resp.port(), "srflx");
            SignalMessages.Candidate previous = reflexiveCandidate;
            reflexiveCandidate = srflx;
            // First discovery (or a changed mapping): re-advertise to everyone.
            if (previous == null || !previous.ip.equals(ip) || previous.port != resp.port()) {
                List<SignalMessages.Candidate> all = localCandidates();
                for (Peer peer : peers.values()) {
                    candidatePublisher.accept(peer.uuid.toString(), all);
                }
            }
        } catch (Exception e) {
            Svco.LOGGER.debug("Bad BIND_RESP", e);
        }
    }

    private void handleVoice(byte[] data, int length, DatagramPacket datagram) {
        UUID sender = P2pCodec.parseSender(data, length);
        if (sender == null || sender.equals(selfUuid)) {
            return;
        }
        byte[] plaintext = crypto.open(P2pCodec.sealedPayload(data, length),
                P2pCodec.aad(P2pCodec.TYPE_VOICE, sender));
        if (plaintext == null) {
            return; // not our room
        }
        P2pCodec.VoicePayload voice = P2pCodec.parseVoicePlaintext(plaintext);
        if (voice == null) {
            return;
        }
        Peer peer = peers.get(sender);
        if (peer != null && peer.path == Peer.Path.DIRECT
                && datagram.getSocketAddress().equals(peer.directAddress)) {
            peer.lastHeardMs = System.currentTimeMillis();
        }
        voiceListener.onVoice(sender, voice.opus(), voice.sequenceNumber(), voice.whispering(), voice.distance());
    }

    private void handlePunch(byte[] data, int length, DatagramPacket datagram, boolean isAck) {
        UUID sender = P2pCodec.parseSender(data, length);
        if (sender == null || sender.equals(selfUuid)) {
            return;
        }
        byte type = isAck ? P2pCodec.TYPE_PUNCH_ACK : P2pCodec.TYPE_PUNCH;
        if (crypto.open(P2pCodec.sealedPayload(data, length), P2pCodec.aad(type, sender)) == null) {
            return; // not sealed with our room key
        }
        Peer peer = peers.get(sender);
        if (peer == null) {
            return;
        }
        InetSocketAddress source = (InetSocketAddress) datagram.getSocketAddress();
        long now = System.currentTimeMillis();

        // Any authenticated punch traffic from the peer proves the path works
        // in both directions (they sent from their mapping; our reply reuses it).
        boolean wasDirect = peer.path == Peer.Path.DIRECT;
        peer.establishDirect(source, now);
        if (!wasDirect) {
            Svco.LOGGER.info("Direct voice path to {} established ({})", peer.name, source);
        }
        if (!isAck) {
            send(P2pCodec.punch(P2pCodec.TYPE_PUNCH_ACK, selfUuid,
                    crypto.seal(new byte[0], P2pCodec.aad(P2pCodec.TYPE_PUNCH_ACK, selfUuid))), source);
        }
    }

    // ---- periodic work ---------------------------------------------------

    private void tick() {
        try {
            long now = System.currentTimeMillis();
            if (now - lastBindMs >= BIND_INTERVAL_MS) {
                sendBind();
            }
            for (Peer peer : peers.values()) {
                tickPeer(peer, now);
            }
        } catch (Exception e) {
            Svco.LOGGER.warn("P2P tick failed", e);
        }
    }

    private void tickPeer(Peer peer, long now) {
        if (peer.path == Peer.Path.DIRECT) {
            if (now - peer.lastHeardMs > DIRECT_STALE_MS) {
                Svco.LOGGER.info("Direct path to {} went stale, falling back to relay", peer.name);
                peer.demote();
                startPunching(peer);
            } else if (now - peer.lastPunchSentMs >= DIRECT_KEEPALIVE_MS) {
                // PUNCH doubles as the direct-path keepalive; the ACK
                // refreshes lastHeardMs on both ends.
                peer.lastPunchSentMs = now;
                sendPunchTo(peer.directAddress);
            }
            return;
        }
        if (peer.punching) {
            if (now - peer.punchRoundStartMs > punchTimeoutMs) {
                peer.punching = false; // stay on relay; retry later
                Svco.LOGGER.debug("Punch round to {} timed out, staying on relay", peer.name);
            } else if (now - peer.lastPunchSentMs >= PUNCH_INTERVAL_MS) {
                peer.lastPunchSentMs = now;
                punchAllCandidates(peer);
            }
        } else if (!peer.remoteCandidates.isEmpty()
                && now - peer.punchRoundStartMs >= REPUNCH_BACKOFF_MS) {
            startPunching(peer);
        }
    }

    private void startPunching(Peer peer) {
        if (peer.path == Peer.Path.DIRECT || peer.remoteCandidates.isEmpty()) {
            return;
        }
        peer.punching = true;
        peer.punchRoundStartMs = System.currentTimeMillis();
        peer.lastPunchSentMs = 0;
    }

    private void punchAllCandidates(Peer peer) {
        for (SignalMessages.Candidate candidate : peer.remoteCandidates) {
            try {
                sendPunchTo(new InetSocketAddress(InetAddress.getByName(candidate.ip), candidate.port));
            } catch (Exception ignored) {
                // Unresolvable candidate (e.g. IPv6 on an IPv4-only host)
            }
        }
    }

    private void sendPunchTo(InetSocketAddress target) {
        if (target == null) {
            return;
        }
        byte[] sealed = crypto.seal(new byte[0], P2pCodec.aad(P2pCodec.TYPE_PUNCH, selfUuid));
        send(P2pCodec.punch(P2pCodec.TYPE_PUNCH, selfUuid, sealed), target);
    }

    private void sendBind() {
        lastBindMs = System.currentTimeMillis();
        send(P2pCodec.bindReq(sessionToken), relayAddress);
    }

    private void send(byte[] packet, InetSocketAddress target) {
        try {
            socket.send(new DatagramPacket(packet, packet.length, target));
        } catch (IOException e) {
            if (!closed) {
                Svco.LOGGER.debug("P2P send to {} failed", target, e);
            }
        }
    }

    // ---- candidates ------------------------------------------------------

    /** Host candidates (all usable interface addresses) plus the reflexive one. */
    public List<SignalMessages.Candidate> localCandidates() {
        List<SignalMessages.Candidate> out = new ArrayList<>();
        int port = socket.getLocalPort();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (!iface.isUp() || iface.isLoopback()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address.isLoopbackAddress() || address.isLinkLocalAddress()
                            || address.isMulticastAddress()) {
                        continue;
                    }
                    // Site-local IPv4 enables LAN-direct; global IPv6 often
                    // connects even where IPv4 punching fails.
                    if (address instanceof Inet4Address || !address.isSiteLocalAddress()) {
                        out.add(new SignalMessages.Candidate(address.getHostAddress(), port, "host"));
                    }
                }
            }
        } catch (Exception e) {
            Svco.LOGGER.debug("Interface enumeration failed", e);
        }
        SignalMessages.Candidate srflx = reflexiveCandidate;
        if (srflx != null) {
            out.add(srflx);
        }
        return out;
    }

    @Override
    public void close() {
        closed = true;
        scheduler.shutdownNow();
        socket.close();
        peers.clear();
    }
}
