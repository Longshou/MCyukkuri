package io.github.longshou.mcyukkuri.client;

import io.github.longshou.mcyukkuri.network.ReadoutNetworking;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

/** Installs only the display bridge; the payload schema is registered on both distributions. */
public final class ReadoutClientEvents {
    public static void setup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> ReadoutNetworking.installClientReceiver(ReadoutReports::accept));
    }

    private ReadoutClientEvents() {}
}
