package org.mryd.svco.neoforge;

import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import org.mryd.svco.Svco;
import org.mryd.svco.client.platform.Platform;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

final class NeoForgePlatform implements Platform {

    @Override
    public String loader() {
        return "neoforge";
    }

    @Override
    public Path configDir() {
        return FMLPaths.CONFIGDIR.get();
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
        return Optional.ofNullable(ModList.get().getModFileById(Svco.MOD_ID))
                .map(info -> info.getFile().getFilePath())
                .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")
                        && Files.isRegularFile(path));
    }

    private static String version(String modId) {
        return ModList.get().getModContainerById(modId)
                .map(container -> container.getModInfo().getVersion().toString())
                .orElse("unknown");
    }
}
