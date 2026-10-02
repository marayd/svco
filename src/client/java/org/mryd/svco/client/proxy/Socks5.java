package org.mryd.svco.client.proxy;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Minimal SOCKS5 client (RFC 1928) with username/password auth (RFC 1929).
 *
 * <p>The JDK's built-in SOCKS support is not used: it has no UDP ASSOCIATE,
 * takes credentials only from the JVM-global {@link java.net.Authenticator},
 * and silently retries as SOCKS4 — none of which is acceptable when the
 * point is to keep the real address away from the relay and from peers.
 *
 * <p>TCP destinations are sent as host names whenever we have one, so the
 * proxy resolves them and no DNS query for the relay leaves this machine.
 */
public final class Socks5 {

    static final int VERSION = 0x05;
    static final int CMD_CONNECT = 0x01;
    static final int CMD_UDP_ASSOCIATE = 0x03;
    static final int ATYP_IPV4 = 0x01;
    static final int ATYP_DOMAIN = 0x03;
    static final int ATYP_IPV6 = 0x04;

    private static final int METHOD_NONE = 0x00;
    private static final int METHOD_USER_PASS = 0x02;
    private static final int METHOD_REJECTED = 0xFF;

    private Socks5() {
    }

    /**
     * Opens a TCP connection to {@code host:port} through the proxy.
     *
     * @param tls wrap the tunnel in TLS with hostname verification against {@code host}
     */
    public static Socket connect(ProxySettings proxy, String host, int port, boolean tls,
                                 int timeoutMs) throws IOException {
        String target = ProxySettings.stripBrackets(host);
        Socket socket = openNegotiated(proxy, timeoutMs);
        try {
            sendRequest(socket, CMD_CONNECT, encodeHost(target), port);
            readReply(socket, "CONNECT to " + target + ":" + port);
            if (!tls) {
                return socket;
            }
            return startTls(socket, target, port);
        } catch (IOException | RuntimeException e) {
            closeQuietly(socket);
            throw e;
        }
    }

    /**
     * Runs blocking proxy I/O off the caller's thread. A fresh daemon thread
     * per call: these are rare (connects, update checks) and must never sit
     * in a shared pool that game code also uses.
     */
    public static <T> CompletableFuture<T> async(String threadName, Callable<T> task) {
        CompletableFuture<T> future = new CompletableFuture<>();
        Thread thread = new Thread(() -> {
            try {
                future.complete(task.call());
            } catch (Throwable e) {
                future.completeExceptionally(e instanceof CompletionException ? e.getCause() : e);
            }
        }, threadName);
        thread.setDaemon(true);
        thread.start();
        return future;
    }

    // ---- handshake -------------------------------------------------------

    /** A TCP connection to the proxy that has passed method negotiation and auth. */
    static Socket openNegotiated(ProxySettings proxy, int timeoutMs) throws IOException {
        // NO_PROXY: JVM-wide socksProxyHost settings must not wrap our own SOCKS session
        Socket socket = new Socket(Proxy.NO_PROXY);
        try {
            socket.setSoTimeout(timeoutMs);
            socket.setTcpNoDelay(true);
            try {
                socket.connect(new InetSocketAddress(proxy.host(), proxy.port()), timeoutMs);
            } catch (IOException e) {
                // no cause: the GUI shows the root cause message, which would drop the context
                throw new IOException("SOCKS5 proxy " + proxy + " is unreachable: " + e.getMessage());
            }
            negotiate(socket, proxy);
            return socket;
        } catch (IOException | RuntimeException e) {
            closeQuietly(socket);
            throw e;
        }
    }

    private static void negotiate(Socket socket, ProxySettings proxy) throws IOException {
        OutputStream out = socket.getOutputStream();
        DataInputStream in = new DataInputStream(socket.getInputStream());
        if (proxy.hasCredentials()) {
            out.write(new byte[]{VERSION, 2, METHOD_NONE, METHOD_USER_PASS});
        } else {
            out.write(new byte[]{VERSION, 1, METHOD_NONE});
        }
        out.flush();

        int version = readByte(in);
        int method = readByte(in);
        if (version != VERSION) {
            throw new IOException("SOCKS5 proxy " + proxy + " answered with version " + version
                    + " (not a SOCKS5 proxy?)");
        }
        switch (method) {
            case METHOD_NONE -> {
            }
            case METHOD_USER_PASS -> {
                if (!proxy.hasCredentials()) {
                    throw new IOException("SOCKS5 proxy " + proxy + " requires a username and password");
                }
                authenticate(in, out, proxy);
            }
            case METHOD_REJECTED -> throw new IOException(proxy.hasCredentials()
                    ? "SOCKS5 proxy " + proxy + " accepts none of our auth methods"
                    : "SOCKS5 proxy " + proxy + " requires a username and password");
            default -> throw new IOException("SOCKS5 proxy " + proxy + " picked unsupported auth method " + method);
        }
    }

    private static void authenticate(DataInputStream in, OutputStream out, ProxySettings proxy) throws IOException {
        byte[] user = proxy.username().getBytes(StandardCharsets.UTF_8);
        byte[] pass = proxy.password().getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream request = new ByteArrayOutputStream(3 + user.length + pass.length);
        request.write(0x01); // RFC 1929 sub-negotiation version
        request.write(user.length);
        request.write(user, 0, user.length);
        request.write(pass.length);
        request.write(pass, 0, pass.length);
        out.write(request.toByteArray());
        out.flush();

        readByte(in); // sub-negotiation version; some servers echo 0x05 here
        if (readByte(in) != 0x00) {
            throw new IOException("SOCKS5 proxy " + proxy + " rejected the username or password");
        }
    }

    // ---- requests --------------------------------------------------------

    /** Address part of a request: [ATYP][addr], with domains left to the proxy to resolve. */
    static byte[] encodeHost(String host) throws IOException {
        if (isIpLiteral(host)) {
            return encodeAddress(InetAddress.getByName(host)); // literal: no lookup happens
        }
        byte[] name = host.getBytes(StandardCharsets.US_ASCII);
        if (name.length == 0 || name.length > 255) {
            throw new IOException("Host name too long for SOCKS5: " + host);
        }
        byte[] out = new byte[2 + name.length];
        out[0] = ATYP_DOMAIN;
        out[1] = (byte) name.length;
        System.arraycopy(name, 0, out, 2, name.length);
        return out;
    }

    static byte[] encodeAddress(InetAddress address) {
        byte[] raw = address.getAddress();
        byte[] out = new byte[1 + raw.length];
        out[0] = (byte) (address instanceof Inet4Address ? ATYP_IPV4 : ATYP_IPV6);
        System.arraycopy(raw, 0, out, 1, raw.length);
        return out;
    }

    static void sendRequest(Socket socket, int command, byte[] address, int port) throws IOException {
        byte[] request = new byte[3 + address.length + 2];
        request[0] = VERSION;
        request[1] = (byte) command;
        request[2] = 0x00;
        System.arraycopy(address, 0, request, 3, address.length);
        request[request.length - 2] = (byte) (port >>> 8);
        request[request.length - 1] = (byte) port;
        OutputStream out = socket.getOutputStream();
        out.write(request);
        out.flush();
    }

    /** Reads a reply and returns its BND address, unresolved only for domain replies. */
    static InetSocketAddress readReply(Socket socket, String what) throws IOException {
        DataInputStream in = new DataInputStream(socket.getInputStream());
        int version = readByte(in);
        int reply = readByte(in);
        readByte(in); // reserved
        if (version != VERSION) {
            throw new IOException("SOCKS5 proxy sent a malformed reply to " + what);
        }
        InetSocketAddress bound = readAddress(in);
        if (reply != 0x00) {
            throw new IOException("SOCKS5 proxy refused " + what + ": " + replyMessage(reply));
        }
        return bound;
    }

    static InetSocketAddress readAddress(DataInputStream in) throws IOException {
        int type = readByte(in);
        switch (type) {
            case ATYP_IPV4, ATYP_IPV6 -> {
                byte[] raw = new byte[type == ATYP_IPV4 ? 4 : 16];
                in.readFully(raw);
                int port = in.readUnsignedShort();
                return new InetSocketAddress(InetAddress.getByAddress(raw), port);
            }
            case ATYP_DOMAIN -> {
                byte[] name = new byte[readByte(in)];
                in.readFully(name);
                int port = in.readUnsignedShort();
                return InetSocketAddress.createUnresolved(new String(name, StandardCharsets.US_ASCII), port);
            }
            default -> throw new IOException("SOCKS5 proxy sent unknown address type " + type);
        }
    }

    static String replyMessage(int code) {
        return switch (code) {
            case 0x01 -> "general server failure";
            case 0x02 -> "not allowed by the proxy's rules";
            case 0x03 -> "network unreachable";
            case 0x04 -> "host unreachable";
            case 0x05 -> "connection refused";
            case 0x06 -> "TTL expired";
            case 0x07 -> "command not supported";
            case 0x08 -> "address type not supported";
            default -> "error code " + code;
        };
    }

    // ---- helpers ---------------------------------------------------------

    private static Socket startTls(Socket tunnel, String host, int port) throws IOException {
        SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        SSLSocket ssl = (SSLSocket) factory.createSocket(tunnel, host, port, true);
        SSLParameters params = ssl.getSSLParameters();
        params.setEndpointIdentificationAlgorithm("HTTPS");
        if (!isIpLiteral(host)) {
            params.setServerNames(List.of(new SNIHostName(host)));
        }
        ssl.setSSLParameters(params);
        ssl.startHandshake();
        return ssl;
    }

    /** IPv4 dotted quad or IPv6 literal, decided without any DNS lookup. */
    static boolean isIpLiteral(String host) {
        if (host.indexOf(':') >= 0) {
            return true; // only IPv6 literals contain colons once brackets are stripped
        }
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3 || !part.chars().allMatch(Character::isDigit)
                    || Integer.parseInt(part) > 255) {
                return false;
            }
        }
        return true;
    }

    private static int readByte(DataInputStream in) throws IOException {
        return in.readUnsignedByte();
    }

    static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
