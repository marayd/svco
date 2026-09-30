package org.mryd.svco.forge;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.mryd.svco.Svco;

/**
 * Forge entrypoint. The mod only does anything on the client; the actual
 * wiring lives in {@link SvcoForgeClient} so a dedicated server never loads
 * client classes.
 */
@Mod(Svco.MOD_ID)
public final class SvcoForge {

	//? if >=1.21.1 {
	public SvcoForge(FMLJavaModLoadingContext context) {
		if (FMLEnvironment.dist.isClient()) {
			SvcoForgeClient.init(context);
		}
	}
	//?} else {
	/*public SvcoForge() {
		if (FMLEnvironment.dist.isClient()) {
			SvcoForgeClient.init(FMLJavaModLoadingContext.get());
		}
	}
	*///?}
}
