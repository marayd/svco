package org.mryd.svco.forge;

import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLPaths;
import org.mryd.svco.Svco;
import org.mryd.svco.client.platform.Platform;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

final class ForgePlatform implements Platform {

    @Override
    public String loader() {
        return "forge";
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
        //? if >=26.1 {
        return Optional.ofNullable(ModList.getModFileById(Svco.MOD_ID))
        //?} else
        /*return Optional.ofNullable(ModList.get().getModFileById(Svco.MOD_ID))*/
                .map(info -> info.getFile().getFilePath())
                .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")
                        && Files.isRegularFile(path));
    }

    private static String version(String modId) {
        //? if >=26.1 {
        return ModList.getModContainerById(modId)
        //?} else
        /*return ModList.get().getModContainerById(modId)*/
                .map(container -> container.getModInfo().getVersion().toString())
                .orElse("unknown");
    }
}
