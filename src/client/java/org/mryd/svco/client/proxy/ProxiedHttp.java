package org.mryd.svco.client.proxy;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

/**
 * Plain HTTP/1.1 GET through a SOCKS5 tunnel, for the update check and the
 * update download ({@code HttpClient} cannot use SOCKS proxies and would go
 * direct). Follows redirects like {@code HttpClient.Redirect.NORMAL}: always,
 * except from https to http.
 */
public final class ProxiedHttp {

    public record Response(int status, Map<String, String> headers, byte[] body) {
    }

    private static final int MAX_REDIRECTS = 5;
    private static final int MAX_HEADER_BYTES = 32 * 1024;
    private static final int MAX_CHUNK_LINE = 1024;

    private ProxiedHttp() {
    }

    public static Response get(ProxySettings proxy, URI uri, String userAgent,
                               int timeoutMs, int maxBodyBytes) throws IOException {
        URI current = uri;
        for (int hop = 0; ; hop++) {
            Response response = once(proxy, current, userAgent, timeoutMs, maxBodyBytes);
            String location = response.headers().get("location");
            if (!isRedirect(response.status()) || location == null) {
                return response;
            }
            URI next = current.resolve(location);
            if ("https".equalsIgnoreCase(current.getScheme()) && "http".equalsIgnoreCase(next.getScheme())) {
                return response; // no downgrade, same as HttpClient's NORMAL policy
            }
            if (hop >= MAX_REDIRECTS) {
                throw new IOException("Too many redirects fetching " + uri);
            }
            current = next;
        }
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static Response once(ProxySettings proxy, URI uri, String userAgent,
                                 int timeoutMs, int maxBodyBytes) throws IOException {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        boolean secure = scheme.equals("https");
        if (!secure && !scheme.equals("http")) {
            throw new IOException("Unsupported URL scheme: " + uri);
        }
        String host = uri.getHost();
        if (host == null) {
            throw new IOException("URL has no host: " + uri);
        }
        int defaultPort = secure ? 443 : 80;
        int port = uri.getPort() == -1 ? defaultPort : uri.getPort();
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        if (uri.getRawQuery() != null) {
            path += "?" + uri.getRawQuery();
        }

        try (Socket socket = Socks5.connect(proxy, host, port, secure, timeoutMs)) {
            socket.setSoTimeout(timeoutMs);
            OutputStream out = socket.getOutputStream();
            String request = "GET " + path + " HTTP/1.1\r\n"
                    + "Host: " + (port == defaultPort ? host : host + ":" + port) + "\r\n"
                    + "User-Agent: " + userAgent + "\r\n"
                    + "Accept-Encoding: identity\r\n"
                    + "Connection: close\r\n"
                    + "\r\n";
            out.write(request.getBytes(StandardCharsets.US_ASCII));
            out.flush();

            InputStream in = new BufferedInputStream(socket.getInputStream());
            ProxiedWebSocket.HttpHead head = ProxiedWebSocket.HttpHead.read(in, MAX_HEADER_BYTES);
            byte[] body = readBody(in, head.headers(), maxBodyBytes);
            return new Response(head.status(), head.headers(), body);
        }
    }

    private static byte[] readBody(InputStream in, Map<String, String> headers, int maxBytes) throws IOException {
        String transferEncoding = headers.getOrDefault("transfer-encoding", "");
        if (transferEncoding.toLowerCase(Locale.ROOT).contains("chunked")) {
            return readChunked(in, maxBytes);
        }
        String contentLength = headers.get("content-length");
        if (contentLength != null) {
            long length;
            try {
                length = Long.parseLong(contentLength.trim());
            } catch (NumberFormatException e) {
                throw new IOException("Bad Content-Length: " + contentLength);
            }
            if (length < 0 || length > maxBytes) {
                throw new IOException("Response too large (" + length + " bytes)");
            }
            byte[] body = in.readNBytes((int) length);
            if (body.length != length) {
                throw new EOFException("Connection closed before the full response body arrived");
            }
            return body;
        }
        // Connection: close without a length — the body runs to EOF
        byte[] body = in.readNBytes(maxBytes + 1);
        if (body.length > maxBytes) {
            throw new IOException("Response too large");
        }
        return body;
    }

    private static byte[] readChunked(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        int[] trailerBudget = {MAX_HEADER_BYTES};
        while (true) {
            String sizeLine = readLine(in, new int[]{MAX_CHUNK_LINE});
            int semicolon = sizeLine.indexOf(';');
            String hex = (semicolon >= 0 ? sizeLine.substring(0, semicolon) : sizeLine).trim();
            int size;
            try {
                size = Integer.parseInt(hex, 16);
            } catch (NumberFormatException e) {
                throw new IOException("Bad chunk size: " + sizeLine);
            }
            if (size < 0 || body.size() + (long) size > maxBytes) {
                throw new IOException("Response too large");
            }
            if (size == 0) {
                // Trailers, then the final empty line
                while (!readLine(in, trailerBudget).isEmpty()) {
                    // ignore trailers
                }
                return body.toByteArray();
            }
            byte[] chunk = in.readNBytes(size);
            if (chunk.length != size) {
                throw new EOFException("Connection closed in the middle of a chunk");
            }
            body.write(chunk);
            if (!readLine(in, new int[]{MAX_CHUNK_LINE}).isEmpty()) {
                throw new IOException("Missing CRLF after a chunk");
            }
        }
    }

    private static String readLine(InputStream in, int[] budget) throws IOException {
        StringBuilder line = new StringBuilder();
        while (true) {
            int c = in.read();
            if (c == -1) {
                throw new EOFException("Connection closed in the middle of a chunked body");
            }
            if (--budget[0] < 0) {
                throw new IOException("Chunked framing too large");
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
