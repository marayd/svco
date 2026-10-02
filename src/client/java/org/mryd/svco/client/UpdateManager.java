package org.mryd.svco.client;

import com.google.gson.Gson;
import net.minecraft.client.Minecraft;
import org.mryd.svco.Svco;
import org.mryd.svco.client.platform.Platform;
import org.mryd.svco.client.proxy.ProxiedHttp;
import org.mryd.svco.client.proxy.ProxySettings;
import org.mryd.svco.client.proxy.Socks5;
import org.mryd.svco.client.signal.SignalMessages;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Checks the relay's update API ({@code /api/v1/update}) once per launch and,
 * when allowed, installs the new jar from Modrinth automatically: download to
 * a temp file next to the current jar, verify the sha512 Modrinth published,
 * delete the running jar (the open handle keeps the mod alive until exit),
 * then move the download into place. The swap becomes effective on restart.
 *
 * <p>The API also reports whether the update is <em>mandatory</em> — i.e. the
 * relay's protocol generation moved past ours and voice will not work until
 * the player updates — which only changes how loudly we announce it.
 *
 * <p>In a development environment (mod not loaded from a single jar) nothing
 * is written; the update is only announced.
 *
 * <p>With a SOCKS5 proxy configured, both requests go through it like all
 * other relay traffic; a misconfigured proxy skips the check rather than
 * contacting the relay directly.
 */
public final class UpdateManager {

    private static final Gson GSON = new Gson();
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes(2);
    private static final int MAX_API_BYTES = 1 << 20;
    private static final int MAX_JAR_BYTES = 64 << 20;

    private static boolean checkStarted;

    private UpdateManager() {
    }

    // ---- relay update API wire format (mirrors internal/update/handler.go) --

    static class UpdateResponse {
        int protocol;
        int minProtocol;
        boolean updateAvailable;
        boolean mandatory;
        Latest latest;
        String error;
    }

    static class Latest {
        String version;
        String pageUrl;
        String fileName;
        String url;
        String sha512;
        long size;
    }

    /** Called every client tick; defers the check to the first one so toasts
     *  have a Minecraft instance to land on, mod init runs too early for that. */
    public static void tick(Minecraft minecraft) {
        if (!checkStarted) {
            checkStarted = true;
            if (SvcoConfig.get().checkUpdates) {
                checkAsync(minecraft);
            }
        }
    }

    private static void checkAsync(Minecraft minecraft) {
        try {
            checkNow(minecraft);
        } catch (Exception e) {
            Svco.LOGGER.warn("Update check could not start", e);
        }
    }

    private static void checkNow(Minecraft minecraft) {
        String query = "/api/v1/update?loader=" + urlEncode(Platform.get().loader())
                + "&version=" + urlEncode(modVersion())
                + "&protocol=" + SignalMessages.PROTOCOL_VERSION
                + "&mc=" + urlEncode(minecraftVersion());
        // The relay URL accepts ws(s):// forms; the update API is plain HTTP.
        String base = SvcoConfig.RELAY_URL.trim()
                .replaceAll("/+$", "")
                .replaceFirst("^ws", "http"); // ws->http, wss->https
        URI uri = URI.create(base + query);

        Svco.LOGGER.info("Checking for updates at {}", uri);
        fetch(uri, HTTP_TIMEOUT, MAX_API_BYTES)
                .thenAccept(response -> handleResponse(minecraft, response))
                .exceptionally(e -> {
                    // The relay being down must never nag: voice reconnects handle that.
                    Svco.LOGGER.info("Update check failed (relay unreachable): {}", e.toString());
                    return null;
                });
    }

    /** A finished GET: status code and the whole body. */
    private record Fetched(int status, byte[] body) {
    }

    /**
     * GET through the configured SOCKS5 proxy, or directly when there is
     * none. Fails, rather than going direct, if the proxy is misconfigured.
     */
    private static CompletableFuture<Fetched> fetch(URI uri, Duration timeout, int maxBytes) {
        ProxySettings proxy;
        try {
            proxy = ProxySettings.fromConfig(SvcoConfig.get());
        } catch (IOException e) {
            return CompletableFuture.failedFuture(e);
        }
        String userAgent = "svco/" + modVersion();
        if (proxy != null) {
            return Socks5.async("svco-update-http", () -> {
                ProxiedHttp.Response response = ProxiedHttp.get(proxy, uri, userAgent,
                        (int) timeout.toMillis(), maxBytes);
                return new Fetched(response.status(), response.body());
            });
        }
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("User-Agent", userAgent)
                .GET()
                .build();
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(response -> new Fetched(response.statusCode(), response.body()));
    }

    private static void handleResponse(Minecraft minecraft, Fetched response) {
        if (response.status() != 200) {
            Svco.LOGGER.warn("Update check: relay answered HTTP {}", response.status());
            return;
        }
        UpdateResponse update = GSON.fromJson(new String(response.body(), StandardCharsets.UTF_8),
                UpdateResponse.class);
        if (update == null) {
            return;
        }
        if (update.error != null && !update.error.isBlank()) {
            Svco.LOGGER.info("Update check: relay has no release info ({})", update.error);
        }

        if (!update.updateAvailable || update.latest == null) {
            if (update.mandatory) {
                // Protocol too old but no release to offer: announce loudly anyway.
                Svco.LOGGER.warn("Relay requires protocol >= {}, we speak {}, and no update is published yet",
                        update.minProtocol, SignalMessages.PROTOCOL_VERSION);
                minecraft.execute(() -> Notifier.error("svco.update.required_missing"));
            } else {
                Svco.LOGGER.info("Simple Voice Online {} is up to date", modVersion());
            }
            return;
        }

        Latest latest = update.latest;
        boolean mandatory = update.mandatory;
        Svco.LOGGER.info("Update available: {} -> {} (mandatory: {}, page: {})",
                modVersion(), latest.version, mandatory, latest.pageUrl);

        Optional<Path> currentJar = Platform.get().modJar();
        if (SvcoConfig.get().autoUpdate && currentJar.isPresent() && installable(latest)) {
            downloadAndInstall(minecraft, latest, currentJar.get(), mandatory);
        } else {
            minecraft.execute(() -> {
                if (mandatory) {
                    Notifier.error("svco.update.required", latest.version);
                } else {
                    Notifier.info("svco.update.available", latest.version);
                }
            });
        }
    }

    /** The download must be a plausible jar we can drop next to the current one. */
    private static boolean installable(Latest latest) {
        if (latest.fileName == null || latest.url == null || latest.sha512 == null) {
            return false;
        }
        // The file name comes from the network: never let it escape the mods dir.
        if (latest.fileName.contains("/") || latest.fileName.contains("\\")
                || latest.fileName.contains("..") || !latest.fileName.endsWith(".jar")) {
            Svco.LOGGER.warn("Refusing suspicious update file name {}", latest.fileName);
            return false;
        }
        try {
            String scheme = URI.create(latest.url).getScheme();
            return "https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static void downloadAndInstall(Minecraft minecraft, Latest latest, Path currentJar,
                                           boolean mandatory) {
        Path modsDir = currentJar.getParent();
        Path target = modsDir.resolve(latest.fileName);

        if (target.getFileName().equals(currentJar.getFileName())) {
            // Same file name as the running jar: swapping in place is not
            // safe while the loader holds it open. Should not happen since
            // versioned releases carry versioned names.
            notifyAvailable(minecraft, latest, mandatory);
            return;
        }
        if (Files.exists(target)) {
            Svco.LOGGER.info("Update {} already downloaded, waiting for a restart", latest.version);
            minecraft.execute(() -> Notifier.success(
                    mandatory ? "svco.update.installed_required" : "svco.update.installed", latest.version));
            return;
        }

        Path temp = modsDir.resolve(latest.fileName + ".svco-download");
        deleteQuietly(temp); // leftover from an interrupted earlier attempt
        Svco.LOGGER.info("Downloading update {} to {}", latest.version, temp);
        fetch(URI.create(latest.url), DOWNLOAD_TIMEOUT, MAX_JAR_BYTES)
                .thenAccept(response -> {
                    try {
                        if (response.status() != 200) {
                            throw new IOException("download answered HTTP " + response.status());
                        }
                        Files.write(temp, response.body());
                        verifySha512(temp, latest.sha512);
                        install(currentJar, temp, target);
                        Svco.LOGGER.info("Update {} installed as {}, restart to apply", latest.version, target);
                        minecraft.execute(() -> Notifier.success(
                                mandatory ? "svco.update.installed_required" : "svco.update.installed",
                                latest.version));
                    } catch (Exception e) {
                        Svco.LOGGER.error("Automatic update failed", e);
                        deleteQuietly(temp);
                        minecraft.execute(() -> Notifier.error("svco.update.failed", e.getMessage()));
                        notifyAvailable(minecraft, latest, mandatory);
                    }
                })
                .exceptionally(e -> {
                    Svco.LOGGER.error("Update download failed", e);
                    deleteQuietly(temp);
                    notifyAvailable(minecraft, latest, mandatory);
                    return null;
                });
    }

    /**
     * Swap order matters: delete the old jar first, then move the verified
     * download into place. The loader's open handle keeps the running mod
     * alive (on Windows the name lingers as delete-pending until exit); if
     * the delete fails we abort rather than risk two svco jars, which would
     * stop the game from launching.
     */
    private static void install(Path currentJar, Path temp, Path target) throws IOException {
        Files.delete(currentJar);
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void verifySha512(Path file, String expectedHex) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-512");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        String actual = HexFormat.of().formatHex(digest.digest());
        if (!actual.equalsIgnoreCase(expectedHex.trim())) {
            throw new IOException("sha512 mismatch: expected " + expectedHex + ", got " + actual);
        }
    }

    private static void notifyAvailable(Minecraft minecraft, Latest latest, boolean mandatory) {
        minecraft.execute(() -> {
            if (mandatory) {
                Notifier.error("svco.update.required", latest.version);
            } else {
                Notifier.info("svco.update.available", latest.version);
            }
        });
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    private static String modVersion() {
        return Platform.get().modVersion();
    }

    private static String minecraftVersion() {
        return Platform.get().minecraftVersion();
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
