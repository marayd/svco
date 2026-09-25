package org.mryd.svco.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
//? if >=26.1 {
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
//?} else {
/*import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
*///?}
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.TitleScreen;
//? if >=1.21.11 {
import net.minecraft.resources.Identifier;
//?} else if >=1.21.9 {
/*import net.minecraft.resources.ResourceLocation;
*///?}
import org.lwjgl.glfw.GLFW;
import org.mryd.svco.client.gui.AlphaNoticeScreen;
import org.mryd.svco.client.gui.SvcoConfigScreen;

public class SvcoClient implements ClientModInitializer {

	@Override
	public void onInitializeClient() {
		new FallbackManager().register();
		UpdateManager.register();

		//? if >=1.21.11 {
		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("svco", "main"));
		//?} else if >=1.21.9 {
		/*KeyMapping.Category category = KeyMapping.Category.register(ResourceLocation.fromNamespaceAndPath("svco", "main"));
		*///?}
		//? if >=26.1 {
		KeyMapping openSettings = KeyMappingHelper.registerKeyMapping(
				new KeyMapping("key.svco.settings", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_O, category));
		//?} else if >=1.21.9 {
		/*KeyMapping openSettings = KeyBindingHelper.registerKeyBinding(
				new KeyMapping("key.svco.settings", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_O, category));
		*///?} else {
		/*KeyMapping openSettings = KeyBindingHelper.registerKeyBinding(
				new KeyMapping("key.svco.settings", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_O, "key.categories.svco.main"));
		*///?}
		ClientTickEvents.END_CLIENT_TICK.register(minecraft -> {
			// First launch: warn once that this is alpha software.
			//? if >=26.2 {
			if (!SvcoConfig.get().alphaNoticeShown && minecraft.gui.screen() instanceof TitleScreen) {
				minecraft.gui.setScreen(new AlphaNoticeScreen(minecraft.gui.screen()));
			}
			while (openSettings.consumeClick()) {
				minecraft.gui.setScreen(new SvcoConfigScreen(null));
			}
			//?} else {
			/*if (!SvcoConfig.get().alphaNoticeShown && minecraft.screen instanceof TitleScreen) {
				minecraft.setScreen(new AlphaNoticeScreen(minecraft.screen));
			}
			while (openSettings.consumeClick()) {
				minecraft.setScreen(new SvcoConfigScreen(null));
			}
			*///?}
		});
	}
}
