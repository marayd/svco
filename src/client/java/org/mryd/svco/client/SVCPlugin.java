package org.mryd.svco.client;

import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.EventRegistration;

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
