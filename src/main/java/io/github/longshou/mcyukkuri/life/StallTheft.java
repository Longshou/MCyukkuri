package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.entity.YukkuriComponents;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.human.Custody;
import io.github.longshou.mcyukkuri.human.HumanNpc;
import io.github.longshou.mcyukkuri.human.ProduceCrateBlock;
import io.github.longshou.mcyukkuri.human.ProduceCrateBlockEntity;
import io.github.longshou.mcyukkuri.motor.Action;
import io.github.longshou.mcyukkuri.motor.ClipMarkers;
import io.github.longshou.mcyukkuri.motor.MotorOwner;
import io.github.longshou.mcyukkuri.motor.MouthOccupancy;
import io.github.longshou.mcyukkuri.pet.BadgeGrade;
import io.github.longshou.mcyukkuri.pet.social.BadgeSocial;
import io.github.longshou.mcyukkuri.registry.ModBlocks;
import io.github.longshou.mcyukkuri.settlement.FoundShelter;
import io.github.longshou.mcyukkuri.shop.ShopProtection;
import io.github.longshou.mcyukkuri.social.SocialDisposition;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.status.Identity;
import io.github.longshou.mcyukkuri.status.Reactions;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.EventHooks;

/** A street meal sought at an open crate, with the pull owned by the normal body driver. */
public final class StallTheft implements MotorOwner {
    private static final MouthOccupancy.Purpose FOOD = MouthOccupancy.Purpose.STALL_FOOD;
    private static final int SEARCH = 6, ROUTE_TICKS = 200;
    // A failed visit releases the food goal. A scold keeps the stray away for an ordinary street-care interval.
    private static final int FAILED_VISIT = 1200, SCOLDED_VISIT = 2400;
    private static final int WATCH_TICKS = ClipMarkers.of(YukkuriAction.LOOK_AROUND.id()).length();

    private record Pause(long until, LivingEntity watcher) {}

    private final YukkuriEntity body;
    private final Map<BlockPos, Pause> pauses = new HashMap<>();
    private BlockPull.Stand stand;
    private Action pull;
    private Vec3 retreat;
    private long routeUntil, watchUntil;
    private BlockPos refusedDrop;

    StallTheft(YukkuriEntity body) { this.body = body; }

    /** Also used by the witness. Dirt, injury and shelter never disqualify an actual sighting. */
    public static boolean maySteal(YukkuriEntity body) {
        return body.status().identity() == Identity.STRAY && !Custody.protectedAnimal(body)
                && BadgeSocial.visibleRank(body).id() < BadgeGrade.BRONZE.id()
                && !body.family().hasDependentYoung();
    }

    private boolean eligible() {
        return body.life().nutrition() < 45 && body.life().canEat() && FoundShelter.streetUser(body)
                && !level().isRainingAt(body.blockPosition()) && maySteal(body)
                && EventHooks.canEntityGrief(level(), body);
    }

    boolean plan() {
        if (!eligible() || !mouth().freeForExternalWork()) return false;
        var person = nearbyPerson();
        if (person != null && tooRisky(person)) return false;
        pauses.values().removeIf(pause -> now() >= pause.until() && (pause.watcher() == null || !watching(pause.watcher())));
        var origin = body.blockPosition();
        double best = Double.POSITIVE_INFINITY;
        for (var pos : BlockPos.betweenClosed(origin.offset(-SEARCH, -1, -SEARCH), origin.offset(SEARCH, 1, SEARCH))) {
            double distance = body.distanceToSqr(pos.getCenter());
            if (distance > SEARCH * SEARCH || distance >= best || pauses.containsKey(pos) || !available(pos)) continue;
            var front = level().getBlockState(pos).getValue(ProduceCrateBlock.FACING);
            var candidate = BlockPull.stand(body, pos, EnumSet.complementOf(EnumSet.of(front)));
            if (candidate == null) continue;
            var sight = level().clip(new ClipContext(body.getEyePosition(), candidate.contact(),
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, body));
            if (sight.getType() != HitResult.Type.MISS && !pos.equals(sight.getBlockPos())) continue;
            stand = candidate;
            best = distance;
        }
        if (stand == null) return false;
        routeUntil = now() + ROUTE_TICKS;
        if (person != null) body.getLookControl().setLookAt(person, 30, 30);
        GameplayLog.event(body, "forage", "stall approach crate={} mode={} nutrition={}",
                stand.block().toShortString(), cautious() ? "sneak" : "rush", body.life().nutrition());
        return true;
    }

    boolean carrying() { return mouth().purpose() == FOOD; }

    boolean resumeCarry() { return !body.isPassenger() && !body.blockPosition().equals(refusedDrop); }

    boolean tick() {
        if (pull != null) {
            if (body.motor().current() == pull) return true;
            pull = null;
            if (!carrying()) return decline("pull_failed", null);
        }
        if (carrying()) return finishCarry();
        if (now() >= routeUntil || !available(stand.block()) || !eligible()) return decline("visit_ended", null);
        // The crate approach starts from actual footing. A route from a falling spawn can finish its first node before landing.
        if (!body.onGround()) return true;
        var person = nearbyPerson();
        if (person != null && tooRisky(person)) return decline("person_near", person);
        if (person != null && cautious() && watching(person)) {
            if (watchUntil == 0) watchUntil = now() + WATCH_TICKS;
            if (now() >= watchUntil) return decline("watched", person);
            SurfaceApproach.halt(body);
            body.getLookControl().setLookAt(person, 30, 30);
            return true;
        }
        if (BlockPull.reaches(body, stand)) {
            pull = BlockPull.action(stand, FOOD, (y, pos) -> validPull(pos),
                    (level, pos) -> ((ProduceCrateBlockEntity) level.getBlockEntity(pos)).takeOne(), this::committed);
            if (!body.motor().run(this, pull)) return decline("pull_refused", null);
            return true;
        }
        if (SurfaceApproach.walkResult(body, stand.feet(), null, cautious() ? .7 : 1.2) == SurfaceApproach.Result.BLOCKED)
            return decline("stand_blocked", null);
        return true;
    }

    private boolean validPull(BlockPos pos) {
        if (!available(pos) || !eligible()) return false;
        var person = nearbyPerson();
        return person == null || !tooRisky(person) && !(cautious() && watching(person));
    }

    private boolean available(BlockPos pos) {
        return level().hasChunkAt(pos) && level().getBlockState(pos).is(ModBlocks.PRODUCE_CRATE.get())
                && level().getBlockEntity(pos) instanceof ProduceCrateBlockEntity crate && crate.stall()
                && !crate.frontItem().isEmpty() && body.life().seeksFood(crate.frontItem())
                && !ShopProtection.guarded(level(), pos);
    }

    private void committed(ItemStack item) {
        var pos = stand.block();
        var keeper = HumanNpc.witnessedStallTake(body, pos, stand.contact());
        if (keeper != null) {
            body.life().personality().relationshipChanged(keeper.getUUID(), -2, 8);
            retreat = Reactions.escapeStand(body, keeper, 5, Set.of());
            routeUntil = now() + ROUTE_TICKS;
        }
        pauses.put(pos, new Pause(now() + (keeper == null ? FAILED_VISIT : SCOLDED_VISIT), null));
        refusedDrop = null;
        GameplayLog.event(body, "forage", "stall pickup crate={} item={} count={} holder=mouth witness={} retreat={}",
                pos.toShortString(), item.getItem(), item.getCount(), keeper == null ? null : keeper.getUUID(),
                retreat == null ? null : GameplayLog.at(retreat));
    }

    private boolean finishCarry() {
        if (retreat != null && now() < routeUntil) {
            var move = SurfaceApproach.walkResult(body, retreat, null, 1.2);
            if (move != SurfaceApproach.Result.ARRIVED && move != SurfaceApproach.Result.BLOCKED) return true;
        }
        if (retreat != null && SurfaceApproach.at(body, retreat))
            GameplayLog.event(body, "forage", "stall retreat arrived at={}", GameplayLog.at(body.position()));
        if (body.onGround() && body.life().beginCarriedMeal(FOOD)) return false;
        if (!mouth().releaseItems(FOOD, 20)) {
            refusedDrop = body.blockPosition();
            GameplayLog.event(body, "forage", "stall release refused at={}", refusedDrop.toShortString());
        }
        return false;
    }

    private LivingEntity nearbyPerson() {
        return level().getEntitiesOfClass(LivingEntity.class, body.getBoundingBox().inflate(SEARCH),
                p -> (p instanceof HumanNpc resident && resident.job() == HumanNpc.Job.RESIDENT && !resident.resting()
                        || p instanceof Player player && !player.isSpectator())
                        && body.distanceToSqr(p) <= SEARCH * SEARCH && body.hasLineOfSight(p))
                .stream().min(Comparator.comparingDouble(body::distanceToSqr)).orElse(null);
    }

    private boolean watching(LivingEntity person) {
        return person.isAlive() && person.distanceToSqr(body) <= SEARCH * SEARCH
                && Vec3.directionFromRotation(0, person.getYHeadRot())
                    .dot(body.position().subtract(person.position()).multiply(1, 0, 1).normalize()) > .5
                && person.hasLineOfSight(body);
    }

    private boolean tooRisky(LivingEntity person) {
        var relation = body.life().personality().relation(person.getUUID());
        return relation.closeness() >= 10 || SocialDisposition.humanWariness(body, person, relation) >
                .2 + .45 * hunger() + .35 * boldness();
    }

    private double hunger() { return 1 - SocialDisposition.ramp(body.life().nutrition(), 15, 45); }
    private double boldness() { return body.life().personality().boldness() / 100.0; }
    private boolean cautious() {
        return .45 * hunger() + .35 * boldness() + .4 * (1 - body.life().personality().selfControl() / 100.0) < .65;
    }

    private boolean decline(String reason, LivingEntity watcher) {
        pauses.put(stand.block(), new Pause(now() + FAILED_VISIT, watcher));
        GameplayLog.event(body, "forage", "stall gave_up crate={} reason={}", stand.block().toShortString(), reason);
        return false;
    }

    String activity() { return retreat != null ? Behaviors.FLEE : Behaviors.SEEK_FOOD; }
    private MouthOccupancy mouth() { return body.component(YukkuriComponents.MOUTH); }
    private ServerLevel level() { return (ServerLevel) body.level(); }
    private long now() { return level().getGameTime(); }
    @Override public String ownerId() { return "stall_theft"; }
    @Override public boolean active() { return stand != null; }

    void stop() {
        if (stand == null) return;
        body.motor().cancel(this, "stall_visit_ended");
        SurfaceApproach.stopWalking(body);
        stand = null;
        pull = null;
        retreat = null;
        watchUntil = 0;
    }
}
