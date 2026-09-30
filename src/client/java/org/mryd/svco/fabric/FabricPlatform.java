package org.mryd.svco.fabric;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModOrigin;
import org.mryd.svco.Svco;
import org.mryd.svco.client.platform.Platform;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

final class FabricPlatform implements Platform {

    @Override
    public String loader() {
        return "fabric";
    }

    @Override
    public Path configDir() {
        return FabricLoader.getInstance().getConfigDir();
    }

    @Override
    public String modVersion() {
        return version(Svco.MOD_ID);
    }

    @Override
    public String minecraftVersion() {
        return version("minecraft");
    }

    @Override
    public Optional<Path> modJar() {
        return FabricLoader.getInstance().getModContainer(Svco.MOD_ID)
                .map(ModContainer::getOrigin)
                .filter(origin -> origin.getKind() == ModOrigin.Kind.PATH)
                .map(ModOrigin::getPaths)
                .filter(paths -> paths.size() == 1)
                .map(paths -> paths.get(0))
                .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")
                        && Files.isRegularFile(path));
    }

    private static String version(String modId) {
        return FabricLoader.getInstance().getModContainer(modId)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }
}
