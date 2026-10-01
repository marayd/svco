package org.mryd.svco.fabric;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import org.mryd.svco.client.gui.SvcoConfigScreen;

/** Loaded by ModMenu (when installed) via the "modmenu" entrypoint. */
public class ModMenuIntegration implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return SvcoConfigScreen::new;
    }
}
