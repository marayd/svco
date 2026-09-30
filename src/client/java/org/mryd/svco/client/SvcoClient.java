package org.mryd.svco.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
//? if >=1.21.11 {
import net.minecraft.resources.Identifier;
//?} else if >=1.21.9 {
/*import net.minecraft.resources.ResourceLocation;
*///?}
//? if <26.3
/*import org.lwjgl.glfw.GLFW;*/
import org.mryd.svco.client.gui.SvcoConfigScreen;
import org.mryd.svco.client.gui.WelcomeScreen;
import org.mryd.svco.client.platform.Platform;

/**
 * Loader-independent client wiring. Each loader entrypoint installs its
 * {@link Platform}, registers {@link #settingsKey()} with its key mapping API
 * and forwards the client tick and server join/leave events here.
 */
public final class SvcoClient {

	private static FallbackManager fallback;
	private static KeyMapping settingsKey;

	private SvcoClient() {
	}

	public static void init(Platform platform) {
		Platform.set(platform);
		fallback = new FallbackManager();
	}

	//? if >=1.21.11 {
	public static Identifier keyCategoryId() {
		return Identifier.fromNamespaceAndPath("svco", "main");
	}
	//?} else if >=1.21.9 {
	/*public static ResourceLocation keyCategoryId() {
		return ResourceLocation.fromNamespaceAndPath("svco", "main");
	}
	*///?}

	//? if >=1.21.9 {
	public static KeyMapping createSettingsKey(KeyMapping.Category category) {
		//? if >=26.3 {
		// 26.3 moved input from GLFW to SDL3: key types and codes changed.
		settingsKey = new KeyMapping("key.svco.settings", InputConstants.Type.KEYBOARD, InputConstants.KEY_O, category);
		//?} else {
		/*settingsKey = new KeyMapping("key.svco.settings", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_O, category);
		*///?}
		return settingsKey;
	}
	//?} else {
	/*public static KeyMapping createSettingsKey() {
		settingsKey = new KeyMapping("key.svco.settings", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_O, "key.categories.svco.main");
		return settingsKey;
	}
	*///?}

	public static KeyMapping settingsKey() {
		return settingsKey;
	}

	public static void onClientTick(Minecraft minecraft) {
		fallback.onTick(minecraft);
		UpdateManager.tick(minecraft);

		// First launch: introduce the mod once.
		//? if >=26.2 {
		if (!SvcoConfig.get().welcomeShown && minecraft.gui.screen() instanceof TitleScreen) {
			minecraft.gui.setScreen(new WelcomeScreen(minecraft.gui.screen()));
		}
		while (settingsKey != null && settingsKey.consumeClick()) {
			minecraft.gui.setScreen(new SvcoConfigScreen(null));
		}
		//?} else {
		/*if (!SvcoConfig.get().welcomeShown && minecraft.screen instanceof TitleScreen) {
			minecraft.setScreen(new WelcomeScreen(minecraft.screen));
		}
		while (settingsKey != null && settingsKey.consumeClick()) {
			minecraft.setScreen(new SvcoConfigScreen(null));
		}
		*///?}
	}

	public static void onJoin(Minecraft minecraft) {
		fallback.onJoin(minecraft);
	}

	public static void onDisconnect() {
		fallback.onDisconnect();
	}
}
