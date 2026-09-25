package org.mryd.svco.client.bridge;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.mryd.svco.Svco;
import org.mryd.svco.client.net.SvcPackets;
import org.mryd.svco.client.net.VoiceSecret;
import org.mryd.svco.client.p2p.P2pCodec;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketAddress;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * An in-process "voice server" for the local SVC client, bound to loopback.
 *
 * <p>The mod points SVC's SecretPacket at {@code 127.0.0.1:<port>}; SVC then
 * runs its normal voice pipeline (mic capture, Opus, playback) against this
 * bridge. The bridge answers the SVC handshake and keepalives, hands captured
 * {@link SvcPackets.Mic} payloads to the P2P layer, and turns remote peers'
 * Opus frames into PlayerSoundPackets that SVC plays back.
 *
 * <p>This replaces per-recipient encryption on a remote relay: the bridge is
 * the only place the SVC wire protocol exists, and it never leaves loopback.
 */
public final class LocalVoiceBridge implements AutoCloseable {

    /** Receives microphone Opus frames captured by the local SVC client. */
    public interface MicListener {
        void onMic(byte[] opus, long sequenceNumber, boolean whispering);
    }

    private final UUID playerUuid;
    private final VoiceSecret secret;
    private final long keepAliveMs;
    private final MicListener micListener;

    private final DatagramSocket socket;
    private final Thread readerThread;
    private final ScheduledExecutorService keepAliveExecutor;

    private volatile SocketAddress clientAddress;
    private volatile boolean authenticated;
    private volatile boolean closed;

    public LocalVoiceBridge(UUID playerUuid, VoiceSecret secret,
                            long keepAliveMs, MicListener micListener) throws IOException {
        this.playerUuid = playerUuid;
        this.secret = secret;
        this.keepAliveMs = keepAliveMs;
        this.micListener = micListener;
        this.socket = new DatagramSocket(0, InetAddress.getLoopbackAddress());
        this.readerThread = new Thread(this::readLoop, "svco-voice-bridge");
        this.readerThread.setDaemon(true);
        this.keepAliveExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "svco-bridge-keepalive");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        readerThread.start();
        keepAliveExecutor.scheduleAtFixedRate(this::sendKeepAlive, keepAliveMs, keepAliveMs, TimeUnit.MILLISECONDS);
    }

    /** The loopback port SVC should send voice to. */
    public int port() {
        return socket.getLocalPort();
    }

    private void readLoop() {
        byte[] buffer = new byte[SvcPackets.MAX_PACKET_SIZE];
        DatagramPacket datagram = new DatagramPacket(buffer, buffer.length);
        while (!closed) {
            try {
                socket.receive(datagram);
                handle(datagram);
            } catch (IOException e) {
                if (!closed) {
                    Svco.LOGGER.warn("Voice bridge read failed", e);
                }
                return;
            } catch (Exception e) {
                Svco.LOGGER.warn("Voice bridge packet handling failed", e);
            }
        }
    }

    private void handle(DatagramPacket datagram) {
        ByteBuf buf = Unpooled.wrappedBuffer(datagram.getData(), datagram.getOffset(), datagram.getLength());
        SvcPackets.Inbound inbound = SvcPackets.decodeClientBound(buf, secret);
        if (inbound == null || !playerUuid.equals(inbound.playerUuid())) {
            return;
        }
        switch (inbound.type()) {
            case SvcPackets.TYPE_AUTHENTICATE -> {
                SvcPackets.Authenticate auth = SvcPackets.Authenticate.read(inbound.body());
                if (!VoiceSecret.fromBytes(auth.secret()).equals(secret)) {
                    return;
                }
                clientAddress = datagram.getSocketAddress();
                authenticated = true;
                send(SvcPackets.TYPE_AUTHENTICATE_ACK, null);
            }
            case SvcPackets.TYPE_CONNECTION_CHECK -> {
                if (authenticated) {
                    clientAddress = datagram.getSocketAddress();
                    send(SvcPackets.TYPE_CONNECTION_CHECK_ACK, null);
                }
            }
            case SvcPackets.TYPE_KEEP_ALIVE -> {
                // The client answering our keepalive; nothing to track, the
                // bridge lives exactly as long as the FallbackManager session.
            }
            case SvcPackets.TYPE_PING -> {
                SvcPackets.Ping ping = SvcPackets.Ping.read(inbound.body());
                send(SvcPackets.TYPE_PING, SvcPackets.pingBody(ping));
            }
            case SvcPackets.TYPE_MIC -> {
                if (authenticated) {
                    SvcPackets.Mic mic = SvcPackets.Mic.read(inbound.body());
                    micListener.onMic(mic.data(), mic.sequenceNumber(), mic.whispering());
                }
            }
            default -> {
            }
        }
    }

    /**
     * Plays a remote peer's Opus frame through the local SVC client.
     * {@code distance} is the sender's authenticated voice distance, already
     * validated against the cap by the P2P layer; it is clamped again here
     * as defense in depth. Safe to call from any thread.
     */
    public void injectSound(UUID sender, byte[] opus, long sequenceNumber, boolean whispering, int distance) {
        if (authenticated) {
            send(SvcPackets.TYPE_PLAYER_SOUND,
                    SvcPackets.playerSoundBody(sender, opus, sequenceNumber,
                            P2pCodec.clampDistance(distance), whispering));
        }
    }

    private void sendKeepAlive() {
        if (authenticated) {
            send(SvcPackets.TYPE_KEEP_ALIVE, null);
        }
    }

    private void send(byte type, ByteBuf body) {
        SocketAddress target = clientAddress;
        if (target == null || closed) {
            if (body != null) {
                body.release();
            }
            return;
        }
        try {
            byte[] bytes = SvcPackets.encodeServerBound(type, body, secret);
            socket.send(new DatagramPacket(bytes, bytes.length, target));
        } catch (IOException e) {
            if (!closed) {
                Svco.LOGGER.warn("Voice bridge send failed", e);
            }
        } finally {
            if (body != null) {
                body.release();
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        keepAliveExecutor.shutdownNow();
        socket.close();
    }
}
