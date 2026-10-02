package org.mryd.svco.client.proxy;

import org.mryd.svco.Svco;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * A small RFC 6455 WebSocket client over a SOCKS5 tunnel. The JDK's
 * {@code java.net.http.WebSocket} cannot be used here: {@code HttpClient}
 * only speaks HTTP CONNECT proxies and would quietly go direct instead.
 *
 * <p>Scope is what signaling needs: text messages (fragmented or not),
 * ping/pong and the close handshake. Binary messages are ignored.
 */
public final class ProxiedWebSocket {

    /** Callbacks run on the reader thread, one at a time. */
    public interface Listener {
        void onText(String message);

        /** The server closed the connection with a close frame. */
        void onClose(int statusCode, String reason);

        /** The connection broke without a close handshake. */
        void onError(Throwable error);
    }

    public static final int NORMAL_CLOSURE = 1000;

    private static final String ACCEPT_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int MAX_MESSAGE_BYTES = 1 << 20;
    private static final int MAX_HEADER_BYTES = 16 * 1024;
    private static final long CLOSE_GRACE_MS = 2000;

    private static final int OP_CONTINUATION = 0x0;
    private static final int OP_TEXT = 0x1;
    private static final int OP_BINARY = 0x2;
    private static final int OP_CLOSE = 0x8;
    private static final int OP_PING = 0x9;
    private static final int OP_PONG = 0xA;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;

    private volatile boolean closeSent;
    private volatile boolean socketClosed;

    private ProxiedWebSocket(Socket socket, InputStream in, OutputStream out) {
        this.socket = socket;
        this.in = in;
        this.out = out;
    }

    /**
     * Opens the tunnel and performs the opening handshake (blocking). Call
     * {@link #start} afterwards to begin delivering messages, so nothing is
     * dispatched before the caller has stored the returned instance.
     */
    public static ProxiedWebSocket connect(ProxySettings proxy, URI uri, int timeoutMs) throws IOException {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        boolean secure = scheme.equals("wss");
        if (!secure && !scheme.equals("ws")) {
            throw new IOException("Not a WebSocket URI: " + uri);
        }
        String host = uri.getHost();
        if (host == null) {
            throw new IOException("WebSocket URI has no host: " + uri);
        }
        int defaultPort = secure ? 443 : 80;
        int port = uri.getPort() == -1 ? defaultPort : uri.getPort();

        Socket socket = Socks5.connect(proxy, host, port, secure, timeoutMs);
        try {
            socket.setSoTimeout(timeoutMs);
            InputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();
            handshake(uri, host, port, defaultPort, in, out);
            socket.setSoTimeout(0); // the relay pings idle connections; no read deadline from here on
            return new ProxiedWebSocket(socket, in, out);
        } catch (IOException | RuntimeException e) {
            Socks5.closeQuietly(socket);
            throw e;
        }
    }

    private static void handshake(URI uri, String host, int port, int defaultPort,
                                  InputStream in, OutputStream out) throws IOException {
        byte[] nonce = new byte[16];
        RANDOM.nextBytes(nonce);
        String key = Base64.getEncoder().encodeToString(nonce);
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        if (uri.getRawQuery() != null) {
            path += "?" + uri.getRawQuery();
        }
        String hostHeader = port == defaultPort ? host : host + ":" + port;
        String request = "GET " + path + " HTTP/1.1\r\n"
                + "Host: " + hostHeader + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n"
                + "\r\n";
        out.write(request.getBytes(StandardCharsets.US_ASCII));
        out.flush();

        HttpHead head = HttpHead.read(in, MAX_HEADER_BYTES);
        if (head.status() != 101) {
            throw new IOException("Relay refused the WebSocket upgrade: HTTP " + head.status());
        }
        String upgrade = head.headers().getOrDefault("upgrade", "");
        String connection = head.headers().getOrDefault("connection", "");
        if (!upgrade.equalsIgnoreCase("websocket")
                || !connection.toLowerCase(Locale.ROOT).contains("upgrade")) {
            throw new IOException("Relay answered the WebSocket upgrade without upgrading");
        }
        if (!expectedAccept(key).equals(head.headers().get("sec-websocket-accept"))) {
            throw new IOException("Relay sent a wrong Sec-WebSocket-Accept");
        }
    }

    static String expectedAccept(String key) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] digest = sha1.digest((key + ACCEPT_GUID).getBytes(StandardCharsets.US_ASCII));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Starts the reader thread that feeds {@code listener}. */
    public void start(Listener listener) {
        Thread reader = new Thread(() -> readLoop(listener), "svco-signal-ws");
        reader.setDaemon(true);
        reader.start();
    }

    // ---- sending -------------------------------------------------------

    /** Sends a text message; a failed write breaks the connection, reported via the listener. */
    public void sendText(String message) {
        sendFrame(OP_TEXT, message.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Starts the close handshake. The socket is closed when the server
     * answers, or after a short grace period if it never does.
     */
    public void sendClose(int statusCode, String reason) {
        if (closeSent) {
            return;
        }
        closeSent = true;
        byte[] reasonBytes = reason.getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[2 + Math.min(reasonBytes.length, 123)];
        payload[0] = (byte) (statusCode >>> 8);
        payload[1] = (byte) statusCode;
        System.arraycopy(reasonBytes, 0, payload, 2, payload.length - 2);
        sendFrame(OP_CLOSE, payload);
        CompletableFuture.delayedExecutor(CLOSE_GRACE_MS, TimeUnit.MILLISECONDS).execute(this::closeSocket);
    }

    private void sendFrame(int opcode, byte[] payload) {
        if (socketClosed) {
            return;
        }
        int length = payload.length;
        int headerLength = 2 + (length < 126 ? 0 : length <= 0xFFFF ? 2 : 8) + 4;
        byte[] frame = new byte[headerLength + length];
        frame[0] = (byte) (0x80 | opcode); // FIN, never fragmented by us
        int pos;
        if (length < 126) {
            frame[1] = (byte) (0x80 | length);
            pos = 2;
        } else if (length <= 0xFFFF) {
            frame[1] = (byte) (0x80 | 126);
            frame[2] = (byte) (length >>> 8);
            frame[3] = (byte) length;
            pos = 4;
        } else {
            frame[1] = (byte) (0x80 | 127);
            for (int i = 0; i < 8; i++) {
                frame[2 + i] = (byte) ((long) length >>> (56 - 8 * i));
            }
            pos = 10;
        }
        byte[] mask = new byte[4];
        RANDOM.nextBytes(mask); // clients must mask every frame
        System.arraycopy(mask, 0, frame, pos, 4);
        pos += 4;
        for (int i = 0; i < length; i++) {
            frame[pos + i] = (byte) (payload[i] ^ mask[i & 3]);
        }
        try {
            synchronized (out) {
                out.write(frame);
                out.flush();
            }
        } catch (IOException e) {
            if (!socketClosed) {
                Svco.LOGGER.debug("WebSocket write failed", e);
                closeSocket(); // the reader notices and reports the failure
            }
        }
    }

    // ---- receiving -----------------------------------------------------

    private void readLoop(Listener listener) {
        DataInputStream data = new DataInputStream(in);
        ByteArrayOutputStream message = new ByteArrayOutputStream();
        boolean inTextMessage = false;
        boolean skippingBinary = false;
        try {
            while (true) {
                int b0 = data.readUnsignedByte();
                int b1 = data.readUnsignedByte();
                boolean fin = (b0 & 0x80) != 0;
                int opcode = b0 & 0x0F;
                long length = b1 & 0x7F;
                if (length == 126) {
                    length = data.readUnsignedShort();
                } else if (length == 127) {
                    length = data.readLong();
                }
                byte[] mask = null;
                if ((b1 & 0x80) != 0) {
                    mask = new byte[4];
                    data.readFully(mask);
                }
                boolean control = (opcode & 0x8) != 0;
                if (length < 0 || length > MAX_MESSAGE_BYTES || (control && (length > 125 || !fin))) {
                    throw new IOException("Relay sent an invalid WebSocket frame");
                }
                byte[] payload = new byte[(int) length];
                data.readFully(payload);
                if (mask != null) {
                    for (int i = 0; i < payload.length; i++) {
                        payload[i] ^= mask[i & 3];
                    }
                }

                switch (opcode) {
                    case OP_TEXT, OP_BINARY -> {
                        message.reset();
                        inTextMessage = opcode == OP_TEXT;
                        skippingBinary = opcode == OP_BINARY;
                        if (inTextMessage) {
                            message.write(payload);
                        }
                    }
                    case OP_CONTINUATION -> {
                        if (inTextMessage) {
                            if (message.size() + payload.length > MAX_MESSAGE_BYTES) {
                                throw new IOException("Relay sent an oversized WebSocket message");
                            }
                            message.write(payload);
                        } else if (!skippingBinary) {
                            throw new IOException("Relay sent a stray WebSocket continuation frame");
                        }
                    }
                    case OP_PING -> sendFrame(OP_PONG, payload);
                    case OP_PONG -> {
                    }
                    case OP_CLOSE -> {
                        int code = payload.length >= 2 ? ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF) : 1005;
                        String reason = payload.length > 2
                                ? new String(payload, 2, payload.length - 2, StandardCharsets.UTF_8) : "";
                        if (!closeSent) {
                            closeSent = true;
                            sendFrame(OP_CLOSE, payload.length >= 2 ? new byte[]{payload[0], payload[1]} : new byte[0]);
                        }
                        closeSocket();
                        listener.onClose(code, reason);
                        return;
                    }
                    default -> throw new IOException("Relay sent unknown WebSocket opcode " + opcode);
                }
                if (fin && (opcode == OP_TEXT || opcode == OP_CONTINUATION || opcode == OP_BINARY)) {
                    if (inTextMessage) {
                        inTextMessage = false;
                        listener.onText(message.toString(StandardCharsets.UTF_8));
                        message.reset();
                    }
                    skippingBinary = false;
                }
            }
        } catch (Throwable e) {
            // After our close frame, the server may drop the connection
            // instead of answering it: that is the end we asked for.
            boolean expected = closeSent;
            closeSocket();
            if (!expected) {
                listener.onError(e instanceof EOFException
                        ? new IOException("Relay connection closed (through the SOCKS5 proxy)")
                        : e);
            }
        }
    }

    private void closeSocket() {
        if (socketClosed) {
            return;
        }
        socketClosed = true;
        Socks5.closeQuietly(socket);
    }

    // ---- HTTP response head ------------------------------------------------

    /** Status line plus headers (names lower-cased, last value wins). */
    record HttpHead(int status, Map<String, String> headers) {

        static HttpHead read(InputStream in, int maxBytes) throws IOException {
            int[] budget = {maxBytes};
            String statusLine = readLine(in, budget);
            String[] parts = statusLine.split(" ", 3);
            if (parts.length < 2 || !parts[0].startsWith("HTTP/")) {
                throw new IOException("Malformed HTTP status line: " + statusLine);
            }
            int status;
            try {
                status = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                throw new IOException("Malformed HTTP status line: " + statusLine);
            }
            Map<String, String> headers = new HashMap<>();
            String line;
            while (!(line = readLine(in, budget)).isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                            line.substring(colon + 1).trim());
                }
            }
            return new HttpHead(status, headers);
        }

        private static String readLine(InputStream in, int[] budget) throws IOException {
            StringBuilder line = new StringBuilder();
            while (true) {
                int c = in.read();
                if (c == -1) {
                    throw new EOFException("Connection closed in the middle of an HTTP header");
                }
                if (--budget[0] < 0) {
                    throw new IOException("HTTP header too large");
                }
                if (c == '\n') {
                    int end = line.length();
                    if (end > 0 && line.charAt(end - 1) == '\r') {
                        line.setLength(end - 1);
                    }
                    return line.toString();
                }
                line.append((char) c);
            }
        }
    }
}
