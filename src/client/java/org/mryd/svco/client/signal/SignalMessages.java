package org.mryd.svco.client.signal;

import java.util.List;

/**
 * JSON messages of the relay signaling protocol (mirrors the Go relay's
 * {@code internal/proto/ws.go}). Field names are the wire format.
 */
public final class SignalMessages {

    /**
     * Wire-format generation this client speaks, sent in {@link Join} and
     * compared by the relay against its accepted range. Must move in
     * lockstep with {@code ProtocolVersion} in the relay's
     * {@code internal/proto/ws.go}.
     */
    public static final int PROTOCOL_VERSION = 2;

    private SignalMessages() {
    }

    public static class Envelope {
        public String type;
    }

    /**
     * Server's first message: a nonce the client must register with
     * Mojang's joinServer before sending {@link Join}.
     */
    public static class Challenge {
        public String type;
        public String serverId;
        /** The relay's protocol generation (0 from relays predating it). */
        public int protocol;
    }

    public static class Join {
        public final String type = "join";
        public String playerUuid;
        public String playerName;
        public String room;
        /** Echo of {@link Challenge#serverId} after joinServer succeeded. */
        public String serverId;
        public final int protocol = PROTOCOL_VERSION;
    }

    public static class PeerInfo {
        public String playerUuid;
        public String playerName;
    }

    public static class Joined {
        public String type;
        public String sessionToken; // hex, 8 bytes
        public String roomKey;      // base64, 16 bytes
        public int udpPort;
        public List<PeerInfo> peers;
    }

    public static class PeerJoined {
        public String type;
        public String playerUuid;
        public String playerName;
    }

    public static class PeerLeft {
        public String type;
        public String playerUuid;
    }

    public static class Candidate {
        public String ip;
        public int port;
        public String kind; // "host" | "srflx"

        public Candidate() {
        }

        public Candidate(String ip, int port, String kind) {
            this.ip = ip;
            this.port = port;
            this.kind = kind;
        }
    }

    public static class Candidates {
        public final String type = "candidates";
        public String to;
        public String from;
        public List<Candidate> candidates;
    }

    public static class Error {
        public String type;
        public String message;
    }
}
