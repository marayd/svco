package org.mryd.svco.neoforge;

//? if >=1.21.9
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;
//? if >=1.20.5 {
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
//?} else {
/*import net.neoforged.fml.ModLoadingContext;
import net.neoforged.neoforge.client.ConfigScreenHandler;
import net.neoforged.neoforge.event.TickEvent;
*///?}
import org.mryd.svco.client.SvcoClient;
import org.mryd.svco.client.gui.SvcoConfigScreen;

final class SvcoNeoForgeClient {

	private SvcoNeoForgeClient() {
	}

	static void init(IEventBus modBus, ModContainer container) {
		SvcoClient.init(new NeoForgePlatform());

		modBus.addListener(SvcoNeoForgeClient::registerKeys);

		//? if >=1.20.5 {
		NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> SvcoClient.onClientTick(Minecraft.getInstance()));
		//?} else {
		/*NeoForge.EVENT_BUS.addListener((TickEvent.ClientTickEvent event) -> {
			if (event.phase == TickEvent.Phase.END) {
				SvcoClient.onClientTick(Minecraft.getInstance());
			}
		});
		*///?}
		NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingIn event) -> SvcoClient.onJoin(Minecraft.getInstance()));
		NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> SvcoClient.onDisconnect());

		// "Config" button in the mod list
		//? if >=1.20.5 {
		container.registerExtensionPoint(IConfigScreenFactory.class, (unused, parent) -> new SvcoConfigScreen(parent));
		//?} else {
		/*ModLoadingContext.get().registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
				() -> new ConfigScreenHandler.ConfigScreenFactory((minecraft, parent) -> new SvcoConfigScreen(parent)));
		*///?}
	}

	private static void registerKeys(RegisterKeyMappingsEvent event) {
		//? if >=1.21.9 {
		KeyMapping.Category category = new KeyMapping.Category(SvcoClient.keyCategoryId());
		event.registerCategory(category);
		event.register(SvcoClient.createSettingsKey(category));
		//?} else {
		/*event.register(SvcoClient.createSettingsKey());
		*///?}
	}
}
