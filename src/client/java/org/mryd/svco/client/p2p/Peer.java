package org.mryd.svco.client.p2p;

import org.mryd.svco.client.signal.SignalMessages;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Connection state for one remote room member.
 *
 * <p>Path selection: while no direct path is established, voice for this
 * peer is sent through the relay ({@code FWD}). A successful hole punch
 * upgrades the peer to {@code DIRECT}; a stale direct path demotes it back
 * to {@code FALLBACK} and punching restarts.
 */
final class Peer {

    enum Path {
        /** No remote candidates yet, or punching in progress: voice via relay. */
        FALLBACK,
        /** Hole punch succeeded: voice directly to {@link #directAddress}. */
        DIRECT,
    }

    final UUID uuid;
    final String name;

    volatile Path path = Path.FALLBACK;
    volatile InetSocketAddress directAddress;

    /** Candidates the peer advertised via signaling. */
    final List<SignalMessages.Candidate> remoteCandidates = new CopyOnWriteArrayList<>();

    /** Last time an authenticated packet arrived over the direct path. */
    volatile long lastHeardMs;

    /** Punch round bookkeeping. */
    volatile long punchRoundStartMs;
    volatile long lastPunchSentMs;
    volatile boolean punching;

    Peer(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
    }

    void establishDirect(InetSocketAddress address, long nowMs) {
        directAddress = address;
        path = Path.DIRECT;
        lastHeardMs = nowMs;
        punching = false;
    }

    void demote() {
        path = Path.FALLBACK;
        directAddress = null;
    }
}
