package org.mryd.svco.client.platform;

import java.nio.file.Path;
import java.util.Optional;

/**
 * The handful of things the mod needs from its mod loader. Everything else is
 * plain Minecraft/SVC code shared by every loader; the loader-specific
 * entrypoints ({@code org.mryd.svco.fabric}, {@code .forge}, {@code .neoforge})
 * install their implementation before anything else runs.
 */
public interface Platform {

    /** Modrinth loader id: {@code fabric}, {@code forge} or {@code neoforge}. */
    String loader();

    Path configDir();

    String modVersion();

    String minecraftVersion();

    /** The single jar the mod was loaded from; empty in a dev environment. */
    Optional<Path> modJar();

    static Platform get() {
        Platform platform = Holder.instance;
        if (platform == null) {
            throw new IllegalStateException("Platform used before the loader entrypoint ran");
        }
        return platform;
    }

    static void set(Platform platform) {
        Holder.instance = platform;
    }

    final class Holder {
        private static volatile Platform instance;

        private Holder() {
        }
    }
}
