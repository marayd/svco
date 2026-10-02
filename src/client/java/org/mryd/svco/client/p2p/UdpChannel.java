package org.mryd.svco.client.p2p;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;

/**
 * The one UDP socket {@link PeerManager} speaks through: either a plain
 * socket or a SOCKS5 UDP association, which hides our address from the
 * relay and from peers. Implementations are safe for concurrent sends.
 */
public interface UdpChannel extends AutoCloseable {

    void send(byte[] data, InetSocketAddress target) throws IOException;

    /**
     * Blocks for the next datagram and stores its payload (at offset 0),
     * length and true remote address in {@code packet}.
     */
    void receive(DatagramPacket packet) throws IOException;

    /** Local port of the underlying socket, as advertised in host candidates. */
    int localPort();

    /**
     * True when traffic leaves through a proxy: this machine's own interface
     * addresses must then never be advertised, or peers would learn them.
     */
    boolean proxied();

    @Override
    void close();

    static UdpChannel direct() throws IOException {
        return new Direct(new DatagramSocket());
    }

    final class Direct implements UdpChannel {

        private final DatagramSocket socket;

        Direct(DatagramSocket socket) {
            this.socket = socket;
        }

        @Override
        public void send(byte[] data, InetSocketAddress target) throws IOException {
            socket.send(new DatagramPacket(data, data.length, target));
        }

        @Override
        public void receive(DatagramPacket packet) throws IOException {
            socket.receive(packet);
        }

        @Override
        public int localPort() {
            return socket.getLocalPort();
        }

        @Override
        public boolean proxied() {
            return false;
        }

        @Override
        public void close() {
            socket.close();
        }
    }
}
