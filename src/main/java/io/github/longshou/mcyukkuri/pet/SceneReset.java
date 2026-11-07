package io.github.longshou.mcyukkuri.pet;

import io.github.longshou.mcyukkuri.shop.ShopDate;
import io.github.longshou.mcyukkuri.shop.ShopPens;
import io.github.longshou.mcyukkuri.shop.ShopCourier;
import io.github.longshou.mcyukkuri.shop.ShopPerformance;
import io.github.longshou.mcyukkuri.settlement.ForageClaims;
import io.github.longshou.mcyukkuri.headwear.Sanction;
import io.github.longshou.mcyukkuri.intent.AskLedger;
import io.github.longshou.mcyukkuri.intent.Conductor;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.status.StatusEvents;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import io.github.longshou.mcyukkuri.item.CarrierItem;
import io.github.longshou.mcyukkuri.pet.exam.ExamHall;
import io.github.longshou.mcyukkuri.pet.exam.ExamSession;
import io.github.longshou.mcyukkuri.pet.stage.Staging;
import io.github.longshou.mcyukkuri.pet.social.BadgeSocial;
import io.github.longshou.mcyukkuri.predator.PredatorLife;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/** Transient scene bookkeeping never outlives its server (singleplayer world switches), like ShopPerformance's own tasks. */
public final class SceneReset {
    private SceneReset() {}
    private static void reset() {
        ShopDate.reset(); PetScenes.reset(); WildEncounter.reset(); BadgeSocial.reset(); PredatorLife.resetAlerts();
        ExamSession.reset(); Staging.reset(); ShopPens.reset(); ExamHall.reset(); CarrierItem.resetScenes();
    }
    /** Field command only; session performances end before the cleared UUIDs may be summoned again. */
    public static void field(ServerLevel level, AABB box, Set<UUID> ids) {
        reset();
        ShopCourier.reset();
        ShopPerformance.reset();
        ForageClaims.forget(level, box);
        Sanction.forget(level, ids);
        AskLedger.server().forget(ids);
        Conductor.forget(ids);
        GameplayLog.forget(level, ids);
        var clock = level.getServer().tickRateManager();
        clock.stopSprinting(); clock.stopStepping(); clock.setFrozen(false); clock.setTickRate(20);
        StatusEvents.trace(false);
        // Vanilla noon and the vanilla minimum clear-weather duration (five real minutes at normal speed).
        level.setDayTime(6000);
        level.setWeatherParameters(6000, 0, false, false);
    }
    public static void started(ServerStartingEvent event) { reset(); }
    public static void stopped(ServerStoppedEvent event) { reset(); }
}
