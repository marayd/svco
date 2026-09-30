package org.mryd.svco.forge;

//? if >=1.21.9
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
//? if <1.21.6
/*import net.minecraftforge.common.MinecraftForge;*/
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.mryd.svco.client.SvcoClient;
import org.mryd.svco.client.gui.SvcoConfigScreen;

final class SvcoForgeClient {

	private SvcoForgeClient() {
	}

	static void init(FMLJavaModLoadingContext context) {
		SvcoClient.init(new ForgePlatform());

		// Forge 1.21.6 moved to EventBus 7: every event type has its own bus
		//? if >=26.1 {
		RegisterKeyMappingsEvent.BUS.addListener(SvcoForgeClient::registerKeys);
		//?} else if >=1.21.6 {
		/*RegisterKeyMappingsEvent.getBus(context.getModBusGroup()).addListener(SvcoForgeClient::registerKeys);
		*///?} else {
		/*context.getModEventBus().addListener(SvcoForgeClient::registerKeys);
		*///?}

		//? if >=1.21.6 {
		TickEvent.ClientTickEvent.Post.BUS.addListener(event -> SvcoClient.onClientTick(Minecraft.getInstance()));
		ClientPlayerNetworkEvent.LoggingIn.BUS.addListener(event -> SvcoClient.onJoin(Minecraft.getInstance()));
		ClientPlayerNetworkEvent.LoggingOut.BUS.addListener(event -> SvcoClient.onDisconnect());
		//?} else {
		/*MinecraftForge.EVENT_BUS.addListener((TickEvent.ClientTickEvent event) -> {
			if (event.phase == TickEvent.Phase.END) {
				SvcoClient.onClientTick(Minecraft.getInstance());
			}
		});
		MinecraftForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingIn event) -> SvcoClient.onJoin(Minecraft.getInstance()));
		MinecraftForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> SvcoClient.onDisconnect());
		*///?}

		// "Config" button in the mod list
		context.registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
				() -> new ConfigScreenHandler.ConfigScreenFactory((minecraft, parent) -> new SvcoConfigScreen(parent)));
	}

	private static void registerKeys(RegisterKeyMappingsEvent event) {
		//? if >=1.21.9 {
		event.register(SvcoClient.createSettingsKey(KeyMapping.Category.register(SvcoClient.keyCategoryId())));
		//?} else {
		/*event.register(SvcoClient.createSettingsKey());
		*///?}
	}
}
