package io.github.longshou.mcyukkuri.client;

import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.network.ReadoutNetworking;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

/** Installs only the display bridge; the payload schema is registered on both distributions. */
@EventBusSubscriber(modid = MCYukkuri.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ReadoutClientEvents {
    @SubscribeEvent public static void setup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> ReadoutNetworking.installClientReceiver(ReadoutReports::accept));
    }

    private ReadoutClientEvents() {}
}
