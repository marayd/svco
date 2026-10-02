package org.mryd.svco.client.proxy;

import org.mryd.svco.client.SvcoConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * A validated SOCKS5 proxy the relay and peer traffic must go through.
 *
 * <p>{@link #fromConfig} distinguishes "no proxy" ({@code null}) from "proxy
 * wanted but unusable" (an exception). Callers must treat the latter as a
 * failed connect and never fall back to a direct connection: that would hand
 * the real address to the relay and every peer the player asked to hide it from.
 */
public record ProxySettings(String host, int port, String username, String password) {

    /** RFC 1929 length fields are one byte. */
    private static final int MAX_CREDENTIAL_BYTES = 255;

    /**
     * @return the proxy to use, or {@code null} when proxying is off
     * @throws IOException when the proxy is on but its settings are unusable
     */
    public static ProxySettings fromConfig(SvcoConfig config) throws IOException {
        if (!config.proxyEnabled) {
            return null;
        }
        String host = stripBrackets(config.proxyHost == null ? "" : config.proxyHost.trim());
        if (host.isEmpty()) {
            throw new IOException("SOCKS5 proxy is enabled but no proxy host is set");
        }
        if (config.proxyPort < 1 || config.proxyPort > 65535) {
            throw new IOException("SOCKS5 proxy port " + config.proxyPort + " is out of range");
        }
        String username = config.proxyUsername == null ? "" : config.proxyUsername;
        String password = config.proxyPassword == null ? "" : config.proxyPassword;
        if (username.getBytes(StandardCharsets.UTF_8).length > MAX_CREDENTIAL_BYTES
                || password.getBytes(StandardCharsets.UTF_8).length > MAX_CREDENTIAL_BYTES) {
            throw new IOException("SOCKS5 proxy username and password are limited to 255 bytes each");
        }
        if (username.isEmpty() && !password.isEmpty()) {
            throw new IOException("SOCKS5 proxy password is set without a username");
        }
        return new ProxySettings(host, config.proxyPort, username, password);
    }

    public boolean hasCredentials() {
        return !username.isEmpty();
    }

    /** "[::1]" -> "::1": URIs bracket IPv6 literals, sockets and SOCKS do not. */
    static String stripBrackets(String host) {
        if (host.length() > 2 && host.startsWith("[") && host.endsWith("]")) {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }

    /** Never prints the password: settings end up in logs and error messages. */
    @Override
    public String toString() {
        String address = host.indexOf(':') >= 0 ? "[" + host + "]:" + port : host + ":" + port;
        return hasCredentials() ? username + "@" + address : address;
    }
}
