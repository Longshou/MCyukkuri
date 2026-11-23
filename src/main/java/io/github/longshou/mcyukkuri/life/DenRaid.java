package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriComponents;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.mind.EmotionCourse;
import io.github.longshou.mcyukkuri.mind.EmotionEvent;
import io.github.longshou.mcyukkuri.settlement.SettlementData;
import io.github.longshou.mcyukkuri.settlement.ShelterBlockEntity;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.status.Identity;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * A hungry bandit takes food from an occupied, stocked den it can see, bite by bite, through the shelter's own food pile and the normal meal.
 * Group mates within {@link #GANG} that are also hungry follow the same den, so the gang stays visible. A driven-off, emptied or timed-out
 * raid ends with the den avoided for the usual scold spell, so the retry meets a different situation.
 */
final class DenRaid {
    // 45 is the street-theft hunger line (StallTheft); a mate raiding the den lowers the bar to 55; 65 is a full belly for a raider.
    private static final float HUNGRY = 45, GANG_HUNGRY = 55, FULL = 65;
    // Sight 16 and bites of 3 spaced 60 ticks are the old raid scene's numbers; the walk gets 600 ticks, half the old whole-scene limit.
    private static final int SIGHT = 16, GANG = 8, SCAN_EVERY = 100, ROUTE_TICKS = 600, BITE_GAP = 60, MAX_BITES = 3;

    private final YukkuriEntity body;
    private BlockPos den;
    private long nextScan, routeUntil, lastBite;
    private int bites;

    DenRaid(YukkuriEntity body) { this.body = body; }

    BlockPos den() { return den; }
    boolean active() { return den != null; }
    String activity() { return Behaviors.SEEK_FOOD; }

    private boolean bandit() {
        var group = body.settlement().group();
        return body.status().identity() == Identity.WILD_SETTLEMENT && body.family().adult()
                && group != null && group.kind == SettlementData.Kind.BANDITS;
    }

    boolean plan() {
        long now = now();
        if (now < nextScan || !bandit() || body.life().nutrition() >= GANG_HUNGRY || !body.life().canEat() || body.life().mealTicks() > 0
                || !body.component(YukkuriComponents.MOUTH).freeForExternalWork()) return false;
        nextScan = now + SCAN_EVERY;
        var shelter = body.life().nutrition() < HUNGRY ? visibleDen() : gangDen();
        if (shelter == null || !body.life().reachable(shelter.entrance(), false)) return false;
        den = shelter.getBlockPos().immutable();
        routeUntil = now + ROUTE_TICKS;
        bites = 0;
        lastBite = 0;
        body.showReaction("angry", "mine", 50);
        body.mind().feeling().begin(EmotionCourse.Kind.RAGE, .3f, null, body.blockPosition());
        GameplayLog.event(body, "settlement", "den raid approach den={} nutrition={} stock={} residents={}",
                den.toShortString(), body.life().nutrition(), shelter.food(), shelter.occupants());
        return true;
    }

    /** The nearest stocked, occupied den in sight that this body is not already keeping away from. */
    private ShelterBlockEntity visibleDen() {
        ShelterBlockEntity best = null;
        var origin = body.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-SIGHT, -3, -SIGHT), origin.offset(SIGHT, 3, SIGHT))) {
            if (pos.distSqr(origin) > SIGHT * SIGHT || !level().hasChunkAt(pos)) continue;
            if (level().getBlockEntity(pos) instanceof ShelterBlockEntity shelter && raidable(shelter) && sees(shelter)
                    && (best == null || pos.distSqr(origin) < best.getBlockPos().distSqr(origin))) best = shelter;
        }
        return best;
    }

    /** The den a hungry group mate within {@link #GANG} is already raiding. */
    private ShelterBlockEntity gangDen() {
        var group = body.settlement().groupId();
        for (var mate : level().getEntitiesOfClass(YukkuriEntity.class, body.getBoundingBox().inflate(GANG),
                p -> p != body && p.isAlive() && group.equals(p.settlement().groupId()) && p.life().raidDen() != null)) {
            if (level().getBlockEntity(mate.life().raidDen()) instanceof ShelterBlockEntity shelter && raidable(shelter)) return shelter;
        }
        return null;
    }

    private boolean raidable(ShelterBlockEntity shelter) {
        return shelter != body.habitat().shelter() && shelter.occupants() > 0 && shelter.food() > 0 && shelter.sound()
                && !body.life().avoidsField(shelter.getBlockPos());
    }

    private boolean sees(ShelterBlockEntity shelter) {
        var hit = level().clip(new ClipContext(body.getEyePosition(), Vec3.atCenterOf(shelter.entrance()),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, body));
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(shelter.entrance()) || hit.getBlockPos().equals(shelter.getBlockPos());
    }

    boolean tick() {
        if (body.life().nutrition() >= FULL) return end("full", false);
        long now = now();
        if (now >= routeUntil) return end("route_timeout", true);
        if (!(level().hasChunkAt(den) && level().getBlockEntity(den) instanceof ShelterBlockEntity shelter) || !shelter.sound())
            return end("den_gone", true);
        if (body.life().avoidsField(den)) return end("driven_off", true);
        if (bites >= MAX_BITES || bites > 0 && shelter.food() == 0) return end("raided", true);
        var entrance = Vec3.atBottomCenterOf(shelter.entrance());
        if (body.distanceToSqr(entrance) > 6.25) {
            if (SurfaceApproach.walkResult(body, entrance, null, 1.2) == SurfaceApproach.Result.BLOCKED) return end("blocked", true);
            return true;
        }
        SurfaceApproach.halt(body);
        body.getLookControl().setLookAt(entrance.x, entrance.y + .5, entrance.z);
        if (bites > 0 && now - lastBite < BITE_GAP) return true;
        var loot = shelter.raid();
        if (loot.isEmpty()) return end("den_empty", true);
        if (!body.life().beginMeal(loot, null, false)) {
            shelter.returnFood(loot);
            return end("serving_refused", true);
        }
        bites++;
        lastBite = now;
        body.species().narrate("steal");
        GameplayLog.event(body, "settlement", "den raid bite den={} item={} count={} bites={} nutrition={}",
                den.toShortString(), loot.getItem(), loot.getCount(), bites, body.life().nutrition());
        react(shelter);
        return true;
    }

    /** The residents who see it: the ones who stand up fume and the rest are shaken; the nearest who sees the thief drives it off. */
    private void react(ShelterBlockEntity shelter) {
        YukkuriEntity witness = null;
        for (UUID id : shelter.residentIds()) {
            if (!(level().getEntity(id) instanceof YukkuriEntity resident) || !resident.isAlive() || resident.distanceToSqr(body) > 100) continue;
            boolean fights = resident.getRandom().nextFloat() < resident.life().personality().helpProbability(true, false);
            if (!fights) resident.mind().felt(EmotionEvent.FRIGHTENED);
            resident.showReaction(fights ? "angry" : "teary", fights ? "mine" : "afraid", 80);
            resident.mind().feeling().begin(fights ? EmotionCourse.Kind.RAGE : EmotionCourse.Kind.TERROR, .5f, body.getUUID(), resident.blockPosition());
            YukkuriWorldData.get(level()).relate(resident.getUUID(), body.getUUID(), -3);
            if (!resident.inert() && !resident.life().isSleeping() && resident.hasLineOfSight(body)
                    && (witness == null || resident.distanceToSqr(body) < witness.distanceToSqr(body))) witness = resident;
        }
        if (witness != null) Expulsion.driveOff(body, witness, den);
    }

    /** Ends the raid; every end but a full belly keeps the body away from the den afterwards. */
    private boolean end(String reason, boolean avoid) {
        GameplayLog.event(body, "settlement", "den raid ended den={} reason={} bites={} nutrition={}",
                den.toShortString(), reason, bites, body.life().nutrition());
        if (avoid) body.life().avoidField(den);
        return false;
    }

    void stop() {
        if (den == null) return;
        SurfaceApproach.stopWalking(body);
        den = null;
    }

    private ServerLevel level() { return (ServerLevel) body.level(); }
    private long now() { return level().getGameTime(); }
}
