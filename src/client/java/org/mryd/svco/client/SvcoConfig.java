package org.mryd.svco.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.mryd.svco.Svco;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

public class SvcoConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * Fixed relay address. This is intentionally a {@code static final} constant
     * rather than a config field: Gson never (de)serializes statics, so it can be
     * neither written to nor overridden from {@code svco.json}, and the settings
     * screen shows it read-only. There is deliberately no way to change it.
     */
    public static final String RELAY_URL = "http://relay.svco.mryd.org:7804";

    /**
     * Hard ceiling for {@link #voiceDistance}, in blocks. Mirrors
     * {@code P2pCodec.MAX_VOICE_DISTANCE}; both peers and the relay reject
     * voice claiming a larger radius, so raising only this constant would
     * just get the client's packets dropped.
     */
    public static final double MAX_VOICE_DISTANCE = org.mryd.svco.client.p2p.P2pCodec.MAX_VOICE_DISTANCE;

    public boolean enabled = true;
    public int detectTimeoutTicks = 100; // 5 seconds: how long to wait for a real SecretPacket
    public double voiceDistance = MAX_VOICE_DISTANCE; // distance reported in fabricated sound packets
    public long punchTimeoutMs = 4000; // how long one hole-punch round lasts before staying on relay

    public boolean autoReconnect = true;
    public int maxReconnectAttempts = 5; // 0 = keep retrying forever
    public boolean chatNotifications = true;
    public boolean toastNotifications = true;

    public boolean checkUpdates = true; // ask the relay's update API at startup
    public boolean autoUpdate = true;   // download new versions into mods/ automatically
    public boolean alphaNoticeShown = false; // one-time "this is alpha" screen

    private static SvcoConfig instance;

    public static synchronized SvcoConfig get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    public synchronized void save() {
        clamp();
        try (Writer writer = Files.newBufferedWriter(path())) {
            GSON.toJson(this, writer);
        } catch (IOException e) {
            Svco.LOGGER.error("Failed to write config", e);
        }
    }

    private void clamp() {
        // Math.max/min instead of Math.clamp: the 1.20.x targets compile for Java 17
        detectTimeoutTicks = Math.max(20, Math.min(600, detectTimeoutTicks));
        voiceDistance = Math.max(1.0, Math.min(MAX_VOICE_DISTANCE, voiceDistance));
        punchTimeoutMs = Math.max(1000, Math.min(15_000, punchTimeoutMs));
        maxReconnectAttempts = Math.max(0, Math.min(20, maxReconnectAttempts));
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("svco.json");
    }

    private static SvcoConfig load() {
        Path path = path();
        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path)) {
                SvcoConfig config = GSON.fromJson(reader, SvcoConfig.class);
                if (config != null) {
                    config.clamp();
                    return config;
                }
            } catch (Exception e) {
                Svco.LOGGER.error("Failed to read config, using defaults", e);
            }
        }
        SvcoConfig config = new SvcoConfig();
        config.save();
        return config;
    }
}
