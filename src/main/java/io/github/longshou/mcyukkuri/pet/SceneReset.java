package io.github.longshou.mcyukkuri.pet;

import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.shop.ShopDate;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/** Transient scene bookkeeping never outlives its server (singleplayer world switches), like ShopPerformance's own tasks. */
@EventBusSubscriber(modid = MCYukkuri.MOD_ID)
public final class SceneReset {
    private SceneReset() {}
    private static void reset() { ShopDate.reset(); PetScenes.reset(); WildEncounter.reset(); io.github.longshou.mcyukkuri.predator.PredatorLife.resetAlerts(); }
    @SubscribeEvent public static void started(ServerStartingEvent event) { reset(); }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event) { reset(); }
}
