package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.BehaviorContinuity;
import io.github.longshou.mcyukkuri.life.Duty.End;
import io.github.longshou.mcyukkuri.life.Duty.Kind;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.settlement.DenBarrier;
import io.github.longshou.mcyukkuri.settlement.ShelterBlock;
import io.github.longshou.mcyukkuri.settlement.ShelterBlockEntity;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Keeps the household in sight while a parent is away. */
final class HomeWatch {
    // The longest family forage trip lasts 7200 ticks. One failed watch sits out that absence.
    private static final int WATCH_TICKS = 7400;
    private enum Stance { PATROL, THRESHOLD, BED }
    private record Station(Vec3 stand, boolean inside) {}

    private final FamilyLife family;
    private UUID kin;
    private List<Station> stations;
    private int stationIndex, blockedRun, dwell;
    private boolean sealed, hungry;

    HomeWatch(FamilyLife family) { this.family = family; }

    private YukkuriEntity body() { return family.entity(); }
    private Stance stance() {
        return family.adult() ? Stance.PATROL : family.stage() == FamilyLife.Stage.BABY ? Stance.BED : Stance.THRESHOLD;
    }

    private boolean awayForFood(UUID id) {
        return id != null && family.level().getEntity(id) instanceof YukkuriEntity peer
                && peer.isAlive() && FamilyProvision.outForFood(peer);
    }

    private boolean waitingFor(UUID id) {
        if (id == null || family.absence().overdue(id)) return false;
        if (family.adult() && awayForFood(id)) return true;
        var absence = family.absence();
        return absence.expectingReturn(id) && (family.adult() || absence.away(id)
                >= FamilyDutyMath.watchStart(body().life().personality().attachment()) * absence.expectation());
    }

    private UUID awaited() {
        if (family.adult()) return waitingFor(family.partner()) ? family.partner() : null;
        if (waitingFor(family.mother())) return family.mother();
        return waitingFor(family.father()) ? family.father() : null;
    }

    private String endReason(UUID awaited) {
        var e = body();
        if (!FamilyLife.ordinaryLandFree(e)) return "unavailable";
        if (e.life().isSleeping()) return "asleep";
        if (e.life().nutrition() < FamilyDutyMath.hungerBreak(e.life().personality().selfControl()) + (hungry ? 15 : 0)) return "hungry";
        if (family.adult()) {
            var home = FamilyProvision.home(e);
            if (home == null || e.distanceToSqr(Vec3.atBottomCenterOf(home)) > 12 * 12) return "left_home";
            if (!FamilyProvision.atHomeOf(e).hasChildren()) return "no_dependants";
        } else if (!FamilySeparation.atHome(e)) return "left_home";
        if (awaited == null) return "kin_returned";
        if (family.absence().overdue(awaited)) return "kin_overdue";
        return waitingFor(awaited) ? null : "kin_returned";
    }

    boolean start() {
        var e = body();
        if (e.duty().holds(Kind.WATCH)) {
            finishIfDue();
            return e.duty().holds(Kind.WATCH) && endReason(kin) == null;
        }
        var awaited = awaited();
        if (awaited == null || !FamilyProvision.nearHome(e, e.blockPosition()) || !e.duty().mayTake(Kind.WATCH)
                || e.hurtTime > 0 || BehaviorContinuity.emergency(e) || endReason(awaited) != null) return false;
        var shelter = e.habitat().shelter();
        sealed = shelter != null && DenBarrier.sealed(shelter);
        setStations(shelter);
        if (stations.isEmpty()) return false;
        e.duty().take(Kind.WATCH, WATCH_TICKS, WATCH_TICKS, "kin_away");
        kin = awaited;
        hungry = false;
        var p = e.life().personality();
        GameplayLog.event(e, "family", "home watch started kin={} stance={} stations={} radius={} dwell={}",
                kin, stance(), stations.size(), FamilyDutyMath.patrolRadius(p.boldness(), p.attachment()),
                FamilyDutyMath.stationDwell(p.selfControl()));
        return true;
    }

    boolean active() { return body().duty().holds(Kind.WATCH) && endReason(kin) == null; }

    private void setStations(ShelterBlockEntity shelter) {
        var e = body();
        var mouth = Vec3.atBottomCenterOf(FamilyProvision.entrance(e));
        var chosen = new ArrayList<Station>();
        Vec3 bed = bedStand(shelter);
        if (stance() == Stance.PATROL && !sealed) {
            var p = e.life().personality();
            var facing = shelter == null ? Direction.SOUTH : shelter.facing();
            var axis = Vec3.atLowerCornerOf(facing.getNormal());
            var side = Vec3.atLowerCornerOf(facing.getClockWise().getNormal());
            int outside = 0;
            for (var offset : FamilyDutyMath.patrolOffsets(FamilyDutyMath.patrolRadius(p.boldness(), p.attachment()), shelter != null)) {
                // The burrow's mouth is the first covered cell. Clear the outside notch and its shoulders.
                double forward = shelter != null && shelter.kind() == ShelterBlock.Kind.BURROW
                        ? Math.max(offset[0], 1.5 + e.getBbWidth() / 2) : offset[0];
                double lateral = Math.copySign(Math.min(Math.abs(offset[1]), Math.sqrt(25 - forward * forward)), offset[1]);
                var stand = SurfaceApproach.floor(e, mouth.add(axis.scale(forward)).add(side.scale(lateral)));
                if (stand == null || Math.abs(stand.y - mouth.y) > 1
                        || stand.subtract(mouth).horizontalDistanceSqr() < 4
                        || !SurfaceApproach.canApproach(e, stand)) continue;
                chosen.add(new Station(stand, false));
                if (++outside % FamilyDutyMath.insideCheckEvery(p.attachment()) == 0 && bed != null)
                    chosen.add(new Station(bed, true));
            }
        } else if (bed != null) chosen.add(new Station(bed, true));
        stations = chosen;
        stationIndex = blockedRun = dwell = 0;
    }

    private Vec3 bedStand(ShelterBlockEntity shelter) {
        var e = body();
        if (shelter == null) return reachable(FamilyNest.restPosition(e, family.nestStand()));
        if (stance() != Stance.THRESHOLD || sealed) {
            var bed = shelter.bed(e.getUUID());
            return bed == null ? null : reachable(FamilyNest.restPosition(e, bed));
        }
        var den = Vec3.atBottomCenterOf(shelter.getBlockPos());
        var mouth = Vec3.atBottomCenterOf(shelter.entrance());
        var axis = Vec3.atLowerCornerOf(shelter.facing().getNormal());
        var bed = shelter.beds().stream().min(Comparator.comparingDouble(shelter.entrance()::distSqr))
                .map(pos -> reachable(FamilyNest.restPosition(e, pos))).orElse(null);
        if (bed != null && bed.subtract(den).horizontalDistanceSqr() >= .8 * .8
                && bed.subtract(mouth).dot(axis) <= -.5 - e.getBbWidth() / 2
                && visibleFromMouth(bed, mouth.add(axis))) return bed;
        // Keep the juvenile's whole body on the den side of the screen cell.
        return reachable(mouth.subtract(axis.scale(.5 + e.getBbWidth() / 2 + .1)));
    }

    private Vec3 reachable(Vec3 point) {
        var e = body();
        var stand = point == null ? null : SurfaceApproach.floor(e, point);
        return stand != null && SurfaceApproach.canApproach(e, stand) ? stand : null;
    }

    private boolean visibleFromMouth(Vec3 stand, Vec3 outside) {
        var e = body();
        double height = e.getBbHeight() * .5;
        return e.level().clip(new ClipContext(outside.add(0, height, 0), stand.add(0, height, 0),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, e)).getType() == HitResult.Type.MISS;
    }

    void tick() {
        var e = body();
        var shelter = e.habitat().shelter();
        boolean nowSealed = shelter != null && DenBarrier.sealed(shelter);
        if (nowSealed != sealed) {
            sealed = nowSealed;
            SurfaceApproach.halt(e);
            setStations(shelter);
            if (stations.isEmpty()) {
                end(End.FAILED, "no_reachable_station");
                return;
            }
        }
        var station = stations.get(stationIndex);
        var mouth = Vec3.atBottomCenterOf(FamilyProvision.entrance(e));
        if (dwell == 0) {
            var result = SurfaceApproach.walkResult(e, station.stand(), null, .85);
            if (result == SurfaceApproach.Result.BLOCKED) {
                if (++blockedRun == stations.size()) end(End.FAILED, "no_reachable_station");
                else nextStation();
                return;
            }
            if (result != SurfaceApproach.Result.ARRIVED) return;
            blockedRun = 0;
            SurfaceApproach.halt(e);
            boolean insideCheck = stance() == Stance.PATROL && station.inside();
            GameplayLog.limited(e, "family", insideCheck ? "watch.inside" : "watch.station", 200,
                    insideCheck ? "home watch inside check index={} at={} mouthDistance={} result={}"
                            : "home watch station index={} at={} mouthDistance={} result={}",
                    stationIndex + 1, GameplayLog.at(station.stand()),
                    Math.round(station.stand().subtract(mouth).horizontalDistance() * 10) / 10.0, result);
        }
        var visible = family.level().getEntity(kin);
        var seen = family.absence().lastSeen(kin);
        var trail = visible instanceof YukkuriEntity peer && peer.isAlive() && e.hasLineOfSight(peer)
                ? peer.position() : seen == null ? mouth : Vec3.atBottomCenterOf(seen);
        var facing = stance() == Stance.PATROL && !sealed ? trail : mouth;
        SurfaceApproach.face(e, SurfaceApproach.yaw(e.position(), facing));
        var p = e.life().personality();
        if (++dwell % FamilyDutyMath.lookEvery(p.intelligence()) == 0) {
            if (!station.inside()) e.getLookControl().setLookAt(mouth.x, mouth.y, mouth.z);
            else if (stance() != Stance.PATROL) e.getLookControl().setLookAt(trail.x, trail.y, trail.z);
            else family.level().getEntitiesOfClass(YukkuriEntity.class, e.getBoundingBox().inflate(4), e.family()::caresFor)
                    .stream().filter(e::hasLineOfSight).min(Comparator.comparingDouble(e::distanceToSqr))
                    .ifPresent(e.getLookControl()::setLookAt);
        }
        if (stations.size() > 1 && dwell >= FamilyDutyMath.stationDwell(p.selfControl())) nextStation();
    }

    private void nextStation() {
        stationIndex = (stationIndex + 1) % stations.size();
        dwell = 0;
    }

    void finishIfDue() {
        if (!body().duty().holds(Kind.WATCH)) return;
        var reason = endReason(kin);
        // The overdue search takes over this claim when the family goal next starts.
        if (reason == null || reason.equals("kin_overdue") && !family.absence().confirmed(kin)) return;
        if (reason.equals("hungry")) hungry = true;
        end(reason.equals("kin_returned") || reason.equals("no_dependants") ? End.DONE : End.YIELDED, reason);
    }

    private void end(End end, String reason) {
        body().duty().release(Kind.WATCH, end, reason);
        GameplayLog.event(body(), "family", "home watch ended kin={} outcome={}", kin, reason);
    }
}
