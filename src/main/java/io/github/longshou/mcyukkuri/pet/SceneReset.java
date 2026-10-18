package io.github.longshou.mcyukkuri.pet;

import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.shop.ShopDate;
import io.github.longshou.mcyukkuri.shop.ShopPens;
import io.github.longshou.mcyukkuri.item.CarrierItem;
import io.github.longshou.mcyukkuri.pet.exam.ExamHall;
import io.github.longshou.mcyukkuri.pet.exam.ExamSession;
import io.github.longshou.mcyukkuri.pet.stage.Staging;
import io.github.longshou.mcyukkuri.pet.social.BadgeSocial;
import io.github.longshou.mcyukkuri.predator.PredatorLife;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/** Transient scene bookkeeping never outlives its server (singleplayer world switches), like ShopPerformance's own tasks. */
@EventBusSubscriber(modid = MCYukkuri.MOD_ID)
public final class SceneReset {
    private SceneReset() {}
    private static void reset() {
        ShopDate.reset(); PetScenes.reset(); WildEncounter.reset(); BadgeSocial.reset(); PredatorLife.resetAlerts();
        ExamSession.reset(); Staging.reset(); ShopPens.reset(); ExamHall.reset(); CarrierItem.resetScenes();
    }
    @SubscribeEvent public static void started(ServerStartingEvent event) { reset(); }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event) { reset(); }
}
