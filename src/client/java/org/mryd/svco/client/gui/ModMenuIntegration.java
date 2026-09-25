package org.mryd.svco.client.gui;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** Loaded by ModMenu (when installed) via the "modmenu" entrypoint. */
public class ModMenuIntegration implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return SvcoConfigScreen::new;
    }
}
