package org.mryd.svco.neoforge;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import org.mryd.svco.Svco;

/**
 * NeoForge entrypoint. The mod only does anything on the client; the actual
 * wiring lives in {@link SvcoNeoForgeClient} so a dedicated server never
 * loads client classes.
 */
@Mod(Svco.MOD_ID)
public final class SvcoNeoForge {

	public SvcoNeoForge(IEventBus modBus, ModContainer container, Dist dist) {
		if (dist.isClient()) {
			SvcoNeoForgeClient.init(modBus, container);
		}
	}
}
