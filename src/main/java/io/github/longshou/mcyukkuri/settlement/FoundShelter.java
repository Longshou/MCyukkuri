package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.life.TravelRange;
import io.github.longshou.mcyukkuri.motor.ClipMarkers;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.status.Identity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Temporary cover competes in the life plan. Only the shelter core records occupants. */
public final class FoundShelter {
    private static final int RADIUS = 8, CONTEST_REST = 1200, BLOCKED_REST = 2400;
    private static final float LEAVE_HUNGRY = 25, SEEK_HUNGRY = 30;
    private static final double SNIFF_REACH = .4;
    private enum Phase { APPROACH, SNIFF, WAIT, ENTER, REST, EXIT }
    public enum Step { ACTIVE, RESTING, ENDED }

    private final YukkuriEntity body;
    private final LinkedHashMap<BlockPos, Long> refused = new LinkedHashMap<>();
    private ShelterBlockEntity shelter;
    private BlockPos gap;
    private Vec3 aim;
    private Phase phase;
    private long nextScan, deadline, began;
    private int failures;
    private boolean offeredContest;
    private long warnUntil;

    public FoundShelter(YukkuriEntity body) { this.body = body; }
    private ServerLevel level() { return (ServerLevel)body.level(); }
    private long now() { return level().getGameTime(); }
    public boolean active() { return shelter != null || gap != null; }
    public boolean targeting(BlockPos at) { return shelter != null && shelter.getBlockPos().equals(at); }
    public boolean exiting() { return phase == Phase.EXIT; }
    public boolean readyToSleep() { return phase == Phase.REST && now() >= deadline && !body.life().temperature().unsafeSleep(); }
    public ShelterBlockEntity inside() {
        if (!(body.level() instanceof ServerLevel)) return null;
        if (!(level().getBlockState(body.blockPosition()).getBlock() instanceof ShelterBlock block) || !block.kind().found()) return null;
        var core = ShelterBlock.shelter(level(), body.blockPosition());
        return core != null && core.owns(body.getUUID()) ? core : null;
    }

    public static ShelterBlockEntity atOpening(YukkuriEntity y) {
        if (!(y.level() instanceof ServerLevel level)) return null;
        var at = y.blockPosition();
        return LoadedShelters.within(level, at.offset(-2, 0, -2), at.offset(2, 0, 2)).stream()
                .filter(home -> home.kind().found() && home.owns(y.getUUID()) && at.equals(home.entrance()))
                .findFirst().orElse(null);
    }

    /** A temporary refuge must never compete with a valid bed or with another domain's custody. */
    public static boolean canTakeFoundShelter(YukkuriEntity y) {
        var identity = y.status().identity();
        if (!y.isAlive() || (identity != Identity.STRAY && identity != Identity.WILD_FAMILY) || y.settlement().settlementMember()
                || y.profile().houseBody() || y.profile().dangerToYukkuri() || y.isPassenger() || y.isInWaterOrBubble()
                || y.settlement().underway() || SettlementEvents.shelterBarred(y)) return false;
        var group = y.settlement().group();
        if (group != null && group.kind == SettlementData.Kind.BANDITS) return false;
        var home = y.habitat().shelter();
        if (home != null) {
            if (home.kind().found()) return true;
            if (home.usable(y)) return false;
        }
        return y.life().homeStand() == null;
    }

    static boolean fits(YukkuriEntity y, ShelterBlock.Kind kind) {
        double height = y.getBbHeight() * (y.life().hasAccessory() ? 1.22 : 1);
        for (var rider : y.getPassengers()) height = Math.max(height, rider.getBoundingBox().maxY - y.getY());
        return height < kind.openingHeight && y.getBbWidth() < 1.7;
    }

    private boolean wantsCover() {
        return body.life().drowsy() || body.life().temperature().cold() || body.life().wetness() >= 25;
    }

    public boolean plan() {
        if (now() < nextScan || !canTakeFoundShelter(body) || body.life().nutrition() < SEEK_HUNGRY
                || body.isOnFire() || body.operations().active() || !wantsCover()) return false;
        nextScan = now() + 80;
        var here = body.blockPosition();
        var nearby = LoadedShelters.within(level(), here.offset(-RADIUS, -2, -RADIUS), here.offset(RADIUS, 2, RADIUS));
        var found = nearby.stream().filter(s -> s.kind().found() && s.foundUsable(body)
                        && (canContest(body) || s.residentIds().stream().allMatch(id -> s.related(body.getUUID(), id)))
                        && TravelRange.babyDestination(body, s.getBlockPos()) && !refusing(s.getBlockPos()))
                .min(Comparator.comparingDouble(s -> body.distanceToSqr(Vec3.atBottomCenterOf(s.entrance())))).orElse(null);
        if (found != null) {
            shelter = found;
            if (found.contains(here) && found.claim(body)) { settle(); return true; }
            aim = Vec3.atBottomCenterOf(found.entrance());
            phase = Phase.APPROACH;
            began = now();
            GameplayLog.event(body, "habitat", "found approach shelter={} kind={}", found.getBlockPos(), found.kind());
            return true;
        }
        gap = findGap(here);
        if (gap == null) return false;
        aim = Vec3.atBottomCenterOf(gap);
        phase = Phase.ENTER;
        began = now();
        GameplayLog.event(body, "habitat", "wind cover approach at={}", gap);
        return true;
    }

    private BlockPos findGap(BlockPos here) {
        var options = new ArrayList<BlockPos>();
        int reach = TravelRange.search(body, RADIUS);
        for (var pos : BlockPos.betweenClosed(here.offset(-reach, -1, -reach), here.offset(reach, 1, reach)))
            if (!refusing(pos) && TravelRange.babyDestination(body, pos) && gapAt(pos)) options.add(pos.immutable());
        return options.stream().sorted(Comparator.<BlockPos>comparingInt(p -> paperAt(p) ? 0 : 1)
                        .thenComparingDouble(here::distSqr)).limit(5)
                .filter(pos -> availableStand(Vec3.atBottomCenterOf(pos))
                        && SurfaceApproach.canApproach(body, Vec3.atBottomCenterOf(pos))).findFirst().orElse(null);
    }

    private boolean gapAt(BlockPos pos) {
        if (!level().hasChunkAt(pos) || !level().hasChunkAt(pos.above()) || !body.life().dryStand(pos)
                || level().getBlockState(pos).getBlock() instanceof ShelterBlock
                || level().getBlockState(pos.above()).getCollisionShape(level(), pos.above()).isEmpty()) return false;
        for (var side : Direction.Plane.HORIZONTAL) {
            var wall = pos.relative(side);
            if (level().hasChunkAt(wall) && level().getBlockState(wall).isFaceSturdy(level(), wall, side.getOpposite())) return true;
        }
        return false;
    }

    private boolean paperAt(BlockPos pos) {
        return !level().getEntitiesOfClass(ItemEntity.class, new AABB(pos), item -> item.isAlive() && item.getItem().is(Items.PAPER)).isEmpty();
    }
    public boolean usingPaperAt(BlockPos pos) { return phase == Phase.REST && pos.equals(gap) && paperAt(pos); }

    public Step tick() {
        if (!active()) return Step.ENDED;
        if (phase == Phase.REST) {
            if (checkRest()) return Step.RESTING;
            if (!exiting()) return Step.ENDED;
        }
        if (exiting()) {
            if (!shelter.contains(body.blockPosition())) {
                GameplayLog.event(body, "habitat", "found exited shelter={}", target());
                stop();
                return Step.ENDED;
            }
            if (now() < warnUntil) return Step.ACTIVE;
            if (now() >= deadline || walk() == SurfaceApproach.Result.BLOCKED) { stop(); return Step.ENDED; }
            return Step.ACTIVE;
        }
        if (!canTakeFoundShelter(body) || body.life().nutrition() < LEAVE_HUNGRY
                || (shelter != null ? shelter.isRemoved() || !shelter.foundUsable(body) : !gapAt(gap))) {
            stop(); return Step.ENDED;
        }
        if (phase == Phase.SNIFF || phase == Phase.WAIT) {
            if (now() < deadline) return Step.ACTIVE;
            return inspect();
        }
        if (phase == Phase.APPROACH && body.distanceToSqr(aim) <= SNIFF_REACH * SNIFF_REACH
                && SurfaceApproach.stands(body, body.position())) return sniff();
        var result = walk();
        if (result == SurfaceApproach.Result.BLOCKED || now() - began > 320
                || result == SurfaceApproach.Result.ARRIVED && phase == Phase.ENTER && shelter != null
                        && !shelter.contains(body.blockPosition())) {
            if (++failures >= 2 || shelter == null) {
                remember(target(), BLOCKED_REST, "entry_blocked"); stop(); return Step.ENDED;
            }
            if (phase == Phase.ENTER) aim = restPoint(shelter.foundBed(body), aim);
            else {
                var lateral = shelter.facing().getClockWise().getNormal();
                aim = aim.add(lateral.getX() * .32, 0, lateral.getZ() * .32);
            }
            if (aim == null) { remember(target(), BLOCKED_REST, "no_rest_space"); stop(); return Step.ENDED; }
            began = now();
            return Step.ACTIVE;
        }
        if (result != SurfaceApproach.Result.ARRIVED) return Step.ACTIVE;
        if (phase == Phase.APPROACH) return sniff();
        if (shelter != null && !shelter.claim(body)) {
            remember(target(), CONTEST_REST, "occupied"); stop(); return Step.ENDED;
        }
        settle();
        return Step.RESTING;
    }

    private Step sniff() {
        SurfaceApproach.halt(body);
        SurfaceApproach.face(body, SurfaceApproach.yaw(body.position(), Vec3.atBottomCenterOf(shelter.getBlockPos())));
        phase = Phase.SNIFF;
        deadline = now() + Math.max(1, ClipMarkers.of(Behaviors.HOUSE_SNIFF).length());
        GameplayLog.event(body, "habitat", "found sniff shelter={}", target());
        return Step.ACTIVE;
    }

    private Step inspect() {
        UUID rival = shelter.residentIds().stream().filter(id -> !shelter.related(body.getUUID(), id)).findFirst().orElse(null);
        if (rival != null) {
            if (!offeredContest && canContest(body) && level().getEntity(rival) instanceof YukkuriEntity resident
                    && canContest(resident) && !resident.life().isSleeping() && shelter.occupants() == 1
                    && resolve(body) > resolve(resident)) {
                offeredContest = true;
                body.showReaction("cheek_puff", "", 40);
                resident.life().yieldFoundShelter(target());
                phase = Phase.WAIT; deadline = now() + 60;
                GameplayLog.event(body, "habitat", "found contest shelter={} outcome=wait rival={}", target(), rival);
                return Step.ACTIVE;
            }
            if (!offeredContest && canContest(body) && level().getEntity(rival) instanceof YukkuriEntity resident
                    && canContest(resident) && !resident.life().isSleeping())
                resident.showReaction("cheek_puff", "", 40);
            remember(target(), CONTEST_REST, "yield");
            stop(); return Step.ENDED;
        }
        aim = restPoint(shelter.foundBed(body), null);
        if (aim == null) {
            remember(target(), BLOCKED_REST, "no_rest_space"); stop(); return Step.ENDED;
        }
        phase = Phase.ENTER; began = now(); failures = 0;
        return Step.ACTIVE;
    }

    private Vec3 restPoint(BlockPos pos, Vec3 failed) {
        var centre = Vec3.atBottomCenterOf(pos);
        if (restStand(centre, failed)) return centre;
        for (var side : Direction.Plane.HORIZONTAL) {
            var d = side.getNormal();
            var at = centre.add(d.getX() * .40, 0, d.getZ() * .40);
            if (restStand(at, failed)) return at;
        }
        return null;
    }

    private boolean restStand(Vec3 at, Vec3 failed) {
        if (at.equals(failed) || !availableStand(at)) return false;
        var stand = SurfaceApproach.stand(body, at);
        return stand != null && shelter.contains(BlockPos.containing(stand));
    }

    private boolean availableStand(Vec3 at) {
        var box = body.getBoundingBox().move(at.subtract(body.position()));
        return SurfaceApproach.stands(body, at) && level().noCollision(body, box)
                && level().getEntitiesOfClass(YukkuriEntity.class, box, y -> y != body && y.isAlive()).isEmpty();
    }

    private void settle() {
        SurfaceApproach.halt(body);
        phase = Phase.REST;
        deadline = now() + 20; // Let the shared temperature sampler observe the reached cover before sleep.
        body.showReaction("closed_smile", "", 45);
        if (shelter != null) GameplayLog.event(body, "habitat", "found settled shelter={} kind={} occupants={}",
                target(), shelter.kind(), shelter.occupants());
        else GameplayLog.event(body, "habitat", "wind cover settled at={} paper={}", gap, paperAt(gap));
    }

    public boolean checkRest() {
        if (phase != Phase.REST) return false;
        var here = body.blockPosition();
        boolean displaced = shelter == null ? !gap.equals(here) : !shelter.contains(here);
        String reason = body.life().nutrition() < LEAVE_HUNGRY ? "hungry"
                : !canTakeFoundShelter(body) ? "ineligible"
                : displaced ? "displaced"
                : shelter != null && (shelter.rainStage() > 0 || !shelter.sound()) ? "soaked_or_unsafe"
                : gap != null && !gapAt(gap) ? "cover_gone"
                : body.tickCount % 20 == 0 && !level().getEntitiesOfClass(YukkuriEntity.class, body.getBoundingBox().inflate(5),
                        y -> y.isAlive() && y.profile().dangerToYukkuri() && body.hasLineOfSight(y)).isEmpty() ? "predator" : null;
        if (reason == null && (body.life().isSleeping() || wantsCover())) return true;
        leave(reason == null ? "rested" : reason);
        return false;
    }

    private void leave(String reason) {
        GameplayLog.event(body, "habitat", "found leaving target={} reason={}", target(), reason);
        nextScan = now() + 100;
        if (shelter == null) { stop(); return; }
        phase = Phase.EXIT; deadline = now() + 140;
        aim = Vec3.atBottomCenterOf(shelter.entrance());
        if (reason.equals("soaked_or_unsafe")) {
            warnUntil = now() + Math.max(1, ClipMarkers.of(Behaviors.WARM_UP).length());
            deadline += warnUntil - now();
        }
    }

    public void yield(BlockPos pos) {
        if (targeting(pos) && phase == Phase.REST && !body.life().isSleeping()) {
            remember(pos, CONTEST_REST, "yield"); leave("yield");
        }
    }

    public void removed(BlockPos pos, boolean wet) {
        if (wet) body.life().applyEnvironment(12, 0, 0);
        body.showReaction("surprise", "", 60);
        if (targeting(pos)) stop();
    }

    public void stop() {
        if (!active()) return;
        if (shelter != null && !shelter.isRemoved() && (exiting() || !shelter.contains(body.blockPosition())))
            shelter.release(body.getUUID());
        shelter = null; gap = null; aim = null; phase = null; offeredContest = false; failures = 0; warnUntil = 0;
        SurfaceApproach.stopWalking(body);
    }

    public String activity() {
        return phase == Phase.SNIFF ? Behaviors.HOUSE_SNIFF : phase == Phase.REST || warnUntil > body.level().getGameTime() ? Behaviors.WARM_UP
                : phase == Phase.WAIT ? Behaviors.IDLE : Behaviors.SEEK_WARMTH;
    }
    private BlockPos target() { return shelter == null ? gap : shelter.getBlockPos(); }
    private SurfaceApproach.Result walk() { return SurfaceApproach.walkResult(body, aim, null, 1); }
    private boolean refusing(BlockPos pos) { return refused.getOrDefault(pos, 0L) > now(); }
    private void remember(BlockPos pos, int duration, String reason) {
        if (refused.size() >= 16) refused.pollFirstEntry();
        refused.put(pos, now() + duration);
        GameplayLog.event(body, "habitat", "found declined target={} reason={} cooldown={}", pos, reason, duration);
    }
    private static boolean canContest(YukkuriEntity y) {
        var brood = y.family().brood();
        return y.family().adult() && !y.body().sick() && y.body().mold() == 0 && y.body().wound() == 0
                && y.getHealth() >= y.getMaxHealth() && (brood == null || brood.cancelled || brood.complete());
    }
    private static double resolve(YukkuriEntity y) {
        return y.life().personality().boldness() + 40 * y.getBbWidth() + 20 * y.body().healthRatio();
    }
}
