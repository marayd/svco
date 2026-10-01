package org.mryd.svco.fabric;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
//? if >=26.1 {
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
//?} else {
/*import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
*///?}
//? if >=1.21.9
import net.minecraft.client.KeyMapping;
import org.mryd.svco.client.SvcoClient;

public class SvcoFabricClient implements ClientModInitializer {

	@Override
	public void onInitializeClient() {
		SvcoClient.init(new FabricPlatform());

		//? if >=26.1 {
		KeyMappingHelper.registerKeyMapping(SvcoClient.createSettingsKey(
				KeyMapping.Category.register(SvcoClient.keyCategoryId())));
		//?} else if >=1.21.9 {
		/*KeyBindingHelper.registerKeyBinding(SvcoClient.createSettingsKey(
				KeyMapping.Category.register(SvcoClient.keyCategoryId())));
		*///?} else {
		/*KeyBindingHelper.registerKeyBinding(SvcoClient.createSettingsKey());
		*///?}

		ClientPlayConnectionEvents.JOIN.register((handler, sender, minecraft) -> SvcoClient.onJoin(minecraft));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, minecraft) -> SvcoClient.onDisconnect());
		ClientTickEvents.END_CLIENT_TICK.register(SvcoClient::onClientTick);
	}
}
