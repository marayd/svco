package org.mryd.svco.client.proxy;

import java.net.Socket;
import java.net.URI;
import java.util.concurrent.CompletableFuture;

/**
 * "Test" button of the proxy settings: proves the proxy can carry a voice
 * session — it accepts our credentials, reaches the relay over TCP and
 * grants a UDP association — without joining a room.
 */
public final class ProxyCheck {

    private static final int TIMEOUT_MS = 10_000;

    /** What the proxy offered for UDP, for the success message. */
    public record Result(long tcpMillis, String udpRelay) {
    }

    private ProxyCheck() {
    }

    public static CompletableFuture<Result> run(ProxySettings proxy, String relayUrl) {
        return Socks5.async("svco-proxy-check", () -> {
            URI uri = URI.create(relayUrl);
            boolean secure = "https".equalsIgnoreCase(uri.getScheme()) || "wss".equalsIgnoreCase(uri.getScheme());
            int port = uri.getPort() != -1 ? uri.getPort() : secure ? 443 : 80;

            long start = System.nanoTime();
            try (Socket ignored = Socks5.connect(proxy, uri.getHost(), port, false, TIMEOUT_MS)) {
                // reaching the relay's port through the proxy is the whole TCP check
            }
            long tcpMillis = (System.nanoTime() - start) / 1_000_000;

            try (Socks5UdpChannel udp = Socks5UdpChannel.open(proxy, TIMEOUT_MS)) {
                return new Result(tcpMillis, udp.proxyRelay().toString());
            }
        });
    }
}
