package org.mryd.svco.client;

import com.google.gson.Gson;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModOrigin;
import net.minecraft.client.Minecraft;
import org.mryd.svco.Svco;
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
import java.util.Locale;
import java.util.Optional;

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
 */
public final class UpdateManager {

    private static final Gson GSON = new Gson();
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(15);

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

    /** Defers the check to the first client tick so toasts have a Minecraft
     *  instance to land on; mod init runs too early for that. */
    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(minecraft -> {
            if (!checkStarted) {
                checkStarted = true;
                if (SvcoConfig.get().checkUpdates) {
                    checkAsync(minecraft);
                }
            }
        });
    }

    private static void checkAsync(Minecraft minecraft) {
        try {
            checkNow(minecraft);
        } catch (Exception e) {
            Svco.LOGGER.warn("Update check could not start", e);
        }
    }

    private static void checkNow(Minecraft minecraft) {
        String query = "/api/v1/update?loader=fabric"
                + "&version=" + urlEncode(modVersion())
                + "&protocol=" + SignalMessages.PROTOCOL_VERSION
                + "&mc=" + urlEncode(minecraftVersion());
        // The relay URL accepts ws(s):// forms; the update API is plain HTTP.
        String base = SvcoConfig.RELAY_URL.trim()
                .replaceAll("/+$", "")
                .replaceFirst("^ws", "http"); // ws->http, wss->https
        URI uri = URI.create(base + query);

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(HTTP_TIMEOUT)
                .header("User-Agent", "svco/" + modVersion())
                .GET()
                .build();

        Svco.LOGGER.info("Checking for updates at {}", uri);
        client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> handleResponse(minecraft, client, response))
                .exceptionally(e -> {
                    // The relay being down must never nag: voice reconnects handle that.
                    Svco.LOGGER.info("Update check failed (relay unreachable): {}", e.toString());
                    return null;
                });
    }

    private static void handleResponse(Minecraft minecraft, HttpClient client,
                                       HttpResponse<String> response) {
        if (response.statusCode() != 200) {
            Svco.LOGGER.warn("Update check: relay answered HTTP {}", response.statusCode());
            return;
        }
        UpdateResponse update = GSON.fromJson(response.body(), UpdateResponse.class);
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

        Optional<Path> currentJar = currentJar();
        if (SvcoConfig.get().autoUpdate && currentJar.isPresent() && installable(latest)) {
            downloadAndInstall(minecraft, client, latest, currentJar.get(), mandatory);
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

    private static void downloadAndInstall(Minecraft minecraft, HttpClient client,
                                           Latest latest, Path currentJar, boolean mandatory) {
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
        HttpRequest request = HttpRequest.newBuilder(URI.create(latest.url))
                .timeout(Duration.ofMinutes(2))
                .header("User-Agent", "svco/" + modVersion())
                .GET()
                .build();

        Svco.LOGGER.info("Downloading update {} to {}", latest.version, temp);
        client.sendAsync(request, HttpResponse.BodyHandlers.ofFile(temp))
                .thenAccept(response -> {
                    try {
                        if (response.statusCode() != 200) {
                            throw new IOException("download answered HTTP " + response.statusCode());
                        }
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

    /** The single jar the mod was loaded from; empty in a dev environment. */
    private static Optional<Path> currentJar() {
        return FabricLoader.getInstance().getModContainer(Svco.MOD_ID)
                .map(ModContainer::getOrigin)
                .filter(origin -> origin.getKind() == ModOrigin.Kind.PATH)
                .map(ModOrigin::getPaths)
                .filter(paths -> paths.size() == 1)
                .map(paths -> paths.get(0))
                .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")
                        && Files.isRegularFile(path));
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    private static String modVersion() {
        return FabricLoader.getInstance().getModContainer(Svco.MOD_ID)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }

    private static String minecraftVersion() {
        return FabricLoader.getInstance().getModContainer("minecraft")
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
