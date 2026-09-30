package org.mryd.svco.test;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import org.mryd.svco.client.gui.SvcoConfigScreen;
import org.mryd.svco.client.gui.WelcomeScreen;

/**
 * Smoke test: the mod must survive the title screen, its own screens and a
 * full singleplayer world join (which is where mixin and voice-hook failures show up).
 */
public class SvcoClientGameTest implements FabricClientGameTest {

	@Override
	public void runTest(ClientGameTestContext context) {
		if (!FabricLoader.getInstance().isModLoaded("svco")) {
			throw new AssertionError("svco is not loaded");
		}

		// First launch shows the one-time welcome screen on the title screen.
		context.waitForScreen(WelcomeScreen.class);
		context.takeScreenshot("svco-welcome");
		context.clickScreenButton("svco.welcome.accept");

		context.setScreen(() -> new SvcoConfigScreen(null));
		context.waitForScreen(SvcoConfigScreen.class);
		context.waitTicks(20);
		context.takeScreenshot("svco-config-title");
		context.setScreen(() -> null);

		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			singleplayer.getConnection().waitForChunksRender();
			context.waitTicks(100);

			context.setScreen(() -> new SvcoConfigScreen(null));
			context.waitForScreen(SvcoConfigScreen.class);
			context.takeScreenshot("svco-config-in-world");
			context.setScreen(() -> null);
			context.waitTicks(40);
		}
	}
}
