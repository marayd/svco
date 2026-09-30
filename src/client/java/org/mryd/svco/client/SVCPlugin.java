package org.mryd.svco.client;

import de.maxhenkel.voicechat.api.ForgeVoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.EventRegistration;

/**
 * Fabric finds this through the "voicechat" entrypoint; Forge and NeoForge
 * builds of SVC scan for the annotation instead.
 */
@ForgeVoicechatPlugin
public class SVCPlugin implements VoicechatPlugin {
    @Override
    public String getPluginId() {
        return "svc_online";
    }

    public void initialize(VoicechatApi api) {
        
    }

    public void registerEvents(EventRegistration registration) {

    }
}
