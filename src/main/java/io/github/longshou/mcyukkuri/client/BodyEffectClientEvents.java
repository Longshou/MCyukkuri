package io.github.longshou.mcyukkuri.client;

import io.github.longshou.mcyukkuri.network.BodyEffectNetworking;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

/** Installs the display-only receiver; the payload schema remains common-side. */
public final class BodyEffectClientEvents {
    public static void setup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> BodyEffectNetworking.installClientReceiver(BodyEffectsRenderer::accept));
    }

    private BodyEffectClientEvents() {}
}
