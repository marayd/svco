package org.mryd.svco.client.proxy;

import org.mryd.svco.Svco;
import org.mryd.svco.client.p2p.P2pCodec;
import org.mryd.svco.client.p2p.UdpChannel;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * A SOCKS5 UDP association (RFC 1928 §7): every datagram goes to the proxy's
 * UDP relay wrapped in a {@code [RSV 2][FRAG][ATYP][DST.ADDR][DST.PORT]}
 * header, and the proxy sends it on from its own address. The relay and
 * peers only ever see the proxy, so they learn nothing about this machine.
 *
 * <p>The association lives exactly as long as its TCP control connection.
 * A watcher thread notices the proxy dropping it; from then on every
 * {@link #receive} and {@link #send} fails, which tears the voice session
 * down instead of leaving it silently dead.
 */
public final class Socks5UdpChannel implements UdpChannel {

    /** Largest header: IPv6 (or a 255-byte domain) plus port. */
    private static final int MAX_HEADER = 4 + 1 + 255 + 2;

    private final Socket control;
    private final DatagramSocket socket;
    /** Where the proxy wants our datagrams (its BND.ADDR/BND.PORT). */
    private final InetSocketAddress proxyRelay;
    /** The proxy host as we reached it over TCP; some proxies answer from it. */
    private final InetAddress proxyHost;
    private final ProxySettings proxy;
    /** Only the single reader thread calls {@link #receive}. */
    private final byte[] receiveBuffer = new byte[P2pCodec.MAX_PACKET_SIZE + MAX_HEADER];

    private volatile boolean closed;
    private volatile String failure;

    private Socks5UdpChannel(Socket control, DatagramSocket socket, InetSocketAddress proxyRelay,
                             ProxySettings proxy) {
        this.control = control;
        this.socket = socket;
        this.proxyRelay = proxyRelay;
        this.proxyHost = control.getInetAddress();
        this.proxy = proxy;
    }

    /**
     * Negotiates a UDP association. Fails (and never falls back to plain
     * UDP) if the proxy is unreachable, rejects our credentials or does not
     * support UDP at all.
     */
    public static Socks5UdpChannel open(ProxySettings proxy, int timeoutMs) throws IOException {
        Socket control = Socks5.openNegotiated(proxy, timeoutMs);
        DatagramSocket socket = null;
        try {
            // All zeros: we cannot know our address as the proxy will see it
            // (NAT), and RFC 1928 says to send zeros in that case.
            Socks5.sendRequest(control, Socks5.CMD_UDP_ASSOCIATE, new byte[]{Socks5.ATYP_IPV4, 0, 0, 0, 0}, 0);
            InetSocketAddress bound;
            try {
                bound = Socks5.readReply(control, "UDP ASSOCIATE");
            } catch (IOException e) {
                throw new IOException(e.getMessage() + " (the proxy must support UDP for voice)");
            }
            InetAddress relayAddress;
            if (bound.isUnresolved() || bound.getAddress().isAnyLocalAddress()) {
                // "Use the address you reached me on" — common for proxies bound to 0.0.0.0
                relayAddress = control.getInetAddress();
            } else {
                relayAddress = bound.getAddress();
            }
            InetSocketAddress proxyRelay = new InetSocketAddress(relayAddress, bound.getPort());
            if (proxyRelay.getPort() == 0) {
                throw new IOException("SOCKS5 proxy returned no UDP relay port");
            }
            socket = new DatagramSocket();
            control.setSoTimeout(0); // the control connection now idles for the whole session

            Socks5UdpChannel channel = new Socks5UdpChannel(control, socket, proxyRelay, proxy);
            channel.startWatcher();
            Svco.LOGGER.info("SOCKS5 UDP association with {} up, proxy relays at {}", proxy, proxyRelay);
            return channel;
        } catch (IOException | RuntimeException e) {
            if (socket != null) {
                socket.close();
            }
            Socks5.closeQuietly(control);
            throw e;
        }
    }

    private void startWatcher() {
        Thread watcher = new Thread(() -> {
            try {
                InputStream in = control.getInputStream();
                // The proxy never sends anything on the control connection
                // after the reply; any read result means it is going away.
                while (in.read() != -1) {
                    // ignore stray bytes
                }
                fail("SOCKS5 proxy closed the UDP association");
            } catch (IOException e) {
                fail("SOCKS5 proxy UDP association lost: " + e.getMessage());
            }
        }, "svco-socks5-udp-control");
        watcher.setDaemon(true);
        watcher.start();
    }

    private void fail(String message) {
        if (closed) {
            return;
        }
        failure = message;
        Svco.LOGGER.warn(message);
        socket.close(); // unblocks receive(), which reports the failure
        Socks5.closeQuietly(control);
    }

    @Override
    public void send(byte[] data, InetSocketAddress target) throws IOException {
        checkAlive();
        byte[] address;
        if (target.isUnresolved()) {
            address = Socks5.encodeHost(target.getHostString()); // the proxy resolves it
        } else {
            address = Socks5.encodeAddress(target.getAddress());
        }
        byte[] packet = new byte[3 + address.length + 2 + data.length];
        // packet[0..1] reserved, packet[2] FRAG = 0 (no fragmentation)
        System.arraycopy(address, 0, packet, 3, address.length);
        int offset = 3 + address.length;
        packet[offset] = (byte) (target.getPort() >>> 8);
        packet[offset + 1] = (byte) target.getPort();
        System.arraycopy(data, 0, packet, offset + 2, data.length);
        socket.send(new DatagramPacket(packet, packet.length, proxyRelay));
    }

    @Override
    public void receive(DatagramPacket packet) throws IOException {
        byte[] raw = receiveBuffer;
        DatagramPacket datagram = new DatagramPacket(raw, raw.length);
        while (true) {
            try {
                socket.receive(datagram);
            } catch (IOException e) {
                checkAlive();
                throw e;
            }
            if (!fromProxy(datagram)) {
                continue; // only the proxy may talk to this socket
            }
            int length = datagram.getLength();
            if (length < 4 || raw[0] != 0 || raw[1] != 0 || raw[2] != 0) {
                continue; // malformed, or a fragment (FRAG != 0), which we do not reassemble
            }
            InetSocketAddress source;
            DataInputStream header = new DataInputStream(new ByteArrayInputStream(raw, 3, length - 3));
            try {
                source = Socks5.readAddress(header);
            } catch (IOException e) {
                continue;
            }
            if (source.isUnresolved()) {
                continue; // peers and the relay are addressed by IP, never by name
            }
            int headerLength = length - header.available();
            int payload = Math.min(length - headerLength, packet.getData().length);
            System.arraycopy(raw, headerLength, packet.getData(), 0, payload);
            packet.setData(packet.getData(), 0, payload);
            packet.setSocketAddress(source);
            return;
        }
    }

    private boolean fromProxy(DatagramPacket datagram) {
        if (datagram.getPort() != proxyRelay.getPort()) {
            return false;
        }
        InetAddress from = datagram.getAddress();
        return from.equals(proxyRelay.getAddress()) || from.equals(proxyHost);
    }

    private void checkAlive() throws IOException {
        String message = failure;
        if (message != null) {
            throw new IOException(message);
        }
        if (closed) {
            throw new IOException("UDP channel closed");
        }
    }

    @Override
    public int localPort() {
        return socket.getLocalPort();
    }

    @Override
    public boolean proxied() {
        return true;
    }

    /** The proxy's relay endpoint, for logs and diagnostics. */
    public InetSocketAddress proxyRelay() {
        return proxyRelay;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        socket.close();
        Socks5.closeQuietly(control);
        Svco.LOGGER.debug("SOCKS5 UDP association with {} closed", proxy);
    }
}
