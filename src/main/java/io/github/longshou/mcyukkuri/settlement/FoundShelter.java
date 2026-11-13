package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.headwear.HeadTop;
import io.github.longshou.mcyukkuri.human.HumanNpc;
import io.github.longshou.mcyukkuri.human.WoodenBenchBlock;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.life.TravelRange;
import io.github.longshou.mcyukkuri.motor.ClipMarkers;
import io.github.longshou.mcyukkuri.motor.MotorOwner;
import io.github.longshou.mcyukkuri.motor.action.Hop;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.status.Identity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Temporary cover competes in the life plan. Only the shelter core records occupants. */
public final class FoundShelter implements MotorOwner {
    private static final int RADIUS = 8, CONTEST_REST = 1200, BLOCKED_REST = 2400;
    private static final float LEAVE_HUNGRY = 25, SEEK_HUNGRY = 30;
    private static final double SNIFF_REACH = .4;
    private enum Phase { APPROACH, SNIFF, WAIT, ENTER, REST, EXIT, STEP_DOWN }
    public enum Step { ACTIVE, RESTING, ENDED }

    private final YukkuriEntity body;
    private final LinkedHashMap<BlockPos, Long> refused = new LinkedHashMap<>();
    private ShelterBlockEntity shelter;
    private BlockPos gap;
    public enum StreetSpot { BENCH, BARREL, AWNING }
    private StreetSpot streetSpot;
    private Vec3 benchLaunch;
    private Hop benchHop;
    private boolean benchStepBlocked;
    private long streetAfter;
    private Vec3 aim;
    private Phase phase;
    private long nextScan, deadline, began;
    private int failures;
    private UUID yieldingRival;
    private long warnUntil;

    public FoundShelter(YukkuriEntity body) { this.body = body; }
    private ServerLevel level() { return (ServerLevel)body.level(); }
    private long now() { return level().getGameTime(); }
    @Override public boolean active() { return shelter != null || gap != null; }
    @Override public String ownerId() { return Behaviors.SEEK_WARMTH; }
    public boolean targeting(BlockPos at) { return shelter != null && shelter.getBlockPos().equals(at); }
    public boolean exiting() { return phase == Phase.EXIT || phase == Phase.STEP_DOWN; }
    public boolean readyToSleep() { return phase == Phase.REST && now() >= deadline && facingOut() && !body.life().temperature().unsafeSleep(); }
    /** Lying in a box it faces out of the opening, the way a stray peeks out; the shelter's room is measured for that pose too. */
    private float outward() { return shelter.facing().toYRot(); }
    private boolean facingOut() { return shelter == null || Math.abs(Mth.wrapDegrees(body.getYRot() - outward())) < 2; }
    public boolean restingAtBench() { return streetSpot == StreetSpot.BENCH && phase == Phase.REST; }
    public boolean ownsStreetCover(BlockPos pos) { return streetSpot != null && phase == Phase.REST && pos.equals(gap); }
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

    public static boolean streetUser(YukkuriEntity y) {
        return y.status().identity() == Identity.STRAY && y.isAlive() && (y.family().adult() || y.family().subadult())
                && !y.isPassenger() && !y.isVehicle() && !y.isLeashed() && !y.isInWaterOrBubble()
                && !y.profile().aquatic() && !y.profile().dangerToYukkuri() && !y.profile().houseBody()
                && !y.settlement().settlementMember() && !y.settlement().society().slave()
                && !y.operations().active() && !y.body().sick() && y.body().mold() == 0 && y.body().infection() == 0
                && y.body().wounds().isEmpty() && !y.body().footBurned() && !y.body().dying()
                && y.family().brood() == null && !y.family().birthDuty();
    }

    /** The body with what it wears (and anything riding it) clears the shelter's real interior from its lying spot, at any heading. */
    static boolean fits(YukkuriEntity y, ShelterBlock.Kind kind) {
        double height = HeadTop.height(y);
        for (var rider : y.getPassengers()) height = Math.max(height, rider.getBoundingBox().maxY - y.getY());
        return height < kind.innerHeight && HeadTop.reach(y) < kind.innerReach;
    }

    private boolean wantsCover() {
        return body.life().drowsy() || body.life().temperature().cold() || body.life().wetness() >= 25;
    }

    public boolean plan() {
        if (now() < nextScan || !canTakeFoundShelter(body) || body.life().nutrition() < SEEK_HUNGRY
                || body.isOnFire() || body.operations().active() || !wantsCover() && !seekingStreetRoof()) return false;
        nextScan = now() + 80;
        var here = body.blockPosition();
        var nearby = LoadedShelters.within(level(), here.offset(-RADIUS, -2, -RADIUS), here.offset(RADIUS, 2, RADIUS));
        var found = nearby.stream().filter(s -> s.kind().found() && s.foundUsable(body)
                        && (canContest(body) || s.residentIds().stream().allMatch(id -> s.related(body.getUUID(), id)))
                        && TravelRange.babyDestination(body, s.getBlockPos()) && !refusing(s.getBlockPos()))
                .min(Comparator.comparingDouble(s -> body.distanceToSqr(Vec3.atBottomCenterOf(s.entrance())))).orElse(null);
        if (found != null) {
            shelter = found;
            if (found.contains(here) && found.claim(body)) {
                // Already inside (it woke or was put there): lie down on its own spot, not wherever it stands.
                aim = restPoint(found.foundBed(body), null);
                if (aim == null || SurfaceApproach.at(body, aim)) { settle(); return true; }
                phase = Phase.ENTER; began = now(); failures = 0;
                GameplayLog.event(body, "habitat", "found shift to spot shelter={} spot={}", found.getBlockPos(), GameplayLog.at(aim));
                return true;
            }
            aim = Vec3.atBottomCenterOf(found.entrance());
            phase = Phase.APPROACH;
            began = now();
            GameplayLog.event(body, "habitat", "found approach shelter={} kind={}", found.getBlockPos(), found.kind());
            return true;
        }
        if (now() >= streetAfter && streetUser(body) && planStreet(here)) return true;
        gap = findGap(here);
        if (gap == null) return false;
        aim = Vec3.atBottomCenterOf(gap);
        phase = Phase.ENTER;
        began = now();
        GameplayLog.event(body, "habitat", "wind cover approach at={}", gap);
        return true;
    }

    public boolean seekingStreetRoof() {
        return now() >= streetAfter && streetUser(body) && level().isRainingAt(body.blockPosition());
    }

    private BlockPos findGap(BlockPos here) {
        var options = new ArrayList<BlockPos>();
        int reach = TravelRange.search(body, RADIUS);
        for (var pos : BlockPos.betweenClosed(here.offset(-reach, -1, -reach), here.offset(reach, 1, reach)))
            if (!refusing(pos) && TravelRange.babyDestination(body, pos) && gapAt(pos) && roofKind(level(), pos) == null)
                options.add(pos.immutable());
        return options.stream().sorted(Comparator.<BlockPos>comparingInt(p -> paperAt(p) ? 0 : 1)
                        .thenComparingDouble(here::distSqr)).limit(5)
                .filter(pos -> availableStand(Vec3.atBottomCenterOf(pos))
                        && SurfaceApproach.canApproach(body, Vec3.atBottomCenterOf(pos))).findFirst().orElse(null);
    }

    /** A roofed public stand has no saved residents: whoever rests there owns it. Ordinary cover stays open to every body. */
    public static boolean streetCoverFree(YukkuriEntity body, BlockPos pos) {
        if (roofKind((ServerLevel)body.level(), pos) == null) return true;
        return !streetOccupied(body, pos);
    }

    private static boolean streetOccupied(YukkuriEntity body, BlockPos pos) {
        return !body.level().getEntitiesOfClass(YukkuriEntity.class, new AABB(pos).inflate(1),
                other -> other != body && other.isAlive()
                        && (other.blockPosition().equals(pos) || other.life().ownsStreetCover(pos))).isEmpty();
    }

    public static StreetSpot roofKind(ServerLevel level, BlockPos pos) {
        if (!level.hasChunkAt(pos)) return null;
        var overhead = level.getBlockState(pos.above());
        if (overhead.getBlock() instanceof BarrelBlock && level.getBlockEntity(pos.above()) instanceof BarrelBlockEntity barrel
                && barrel.getPersistentData().getBoolean("StreetShelter"))
            return StreetSpot.BARREL;
        for (int height = 1; height <= 3; height++) {
            var roof = pos.above(height);
            if (!level.getBlockState(roof).is(BlockTags.WOOL)) continue;
            for (var side : Direction.Plane.HORIZONTAL) {
                var neighbor = roof.relative(side);
                if (level.hasChunkAt(neighbor) && level.getBlockState(neighbor).is(BlockTags.WOOL)) return StreetSpot.AWNING;
            }
        }
        return null;
    }

    private StreetSpot kindAt(BlockPos pos) {
        return benchFacing(pos) == null ? roofKind(level(), pos) : StreetSpot.BENCH;
    }

    /** A bench seat: any wooden bench piece, or a player-built slab between two like stairs. */
    private Direction benchFacing(BlockPos pos) {
        var seat = level().getBlockState(pos);
        if (seat.getBlock() instanceof WoodenBenchBlock) return WoodenBenchBlock.front(seat);
        if (!(seat.getBlock() instanceof SlabBlock)) return null;
        for (var side : new Direction[]{Direction.EAST, Direction.NORTH}) {
            var a = level().getBlockState(pos.relative(side));
            var b = level().getBlockState(pos.relative(side.getOpposite()));
            if (a.getBlock() instanceof StairBlock && a == b && a.getValue(StairBlock.FACING).getAxis() != side.getAxis())
                return a.getValue(StairBlock.FACING);
        }
        return null;
    }

    private boolean planStreet(BlockPos here) {
        var options = new ArrayList<BlockPos>();
        for (var pos : BlockPos.betweenClosed(here.offset(-RADIUS, -1, -RADIUS), here.offset(RADIUS, 1, RADIUS))) {
            if (!level().hasChunkAt(pos) || refusing(pos) || !body.life().dryStand(pos) && benchFacing(pos) == null
                    || kindAt(pos) == null) continue;
            options.add(pos.immutable());
        }
        boolean rain = level().isRaining();
        options.sort(Comparator.<BlockPos>comparingInt(p -> rain && kindAt(p) == StreetSpot.BENCH ? 1 : 0)
                .thenComparingDouble(here::distSqr));
        for (var pos : options.stream().limit(8).toList()) {
            var kind = kindAt(pos);
            if (kind == StreetSpot.BENCH ? !body.life().drowsy() || body.life().wetness() >= 25
                    || rain && !body.life().covered(pos.above()) : !rain && !body.life().temperature().cold()) continue;
            if (streetOccupied(body, pos)) continue;
            Vec3 stand = kind == StreetSpot.BENCH
                    ? SurfaceApproach.floor(body, Vec3.atBottomCenterOf(pos).add(0, .5, 0)) : Vec3.atBottomCenterOf(pos);
            Vec3 launch = null;
            if (kind == StreetSpot.BENCH) {
                if (stand == null || !availableStand(stand)) stand = benchSide(pos, null, true);
                else if (!SurfaceApproach.at(body, stand)) {
                    launch = benchSide(pos, stand, true);
                    if (launch == null) stand = benchSide(pos, null, true);
                }
                if (stand == null) continue;
            } else if (!availableStand(stand) || !SurfaceApproach.canApproach(body, stand)) continue;
            gap = pos;
            streetSpot = kind;
            benchLaunch = launch;
            aim = stand;
            phase = Phase.ENTER;
            began = now();
            GameplayLog.event(body, "habitat", "street approach spot={} at={}", streetSpot, gap.toShortString());
            return true;
        }
        return false;
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
        if (phase == Phase.STEP_DOWN) {
            if (now() < deadline) {
                var result = benchStepBlocked ? hopToBenchAim() : walk();
                if (result == SurfaceApproach.Result.MOVING || result == SurfaceApproach.Result.WAITING) return Step.ACTIVE;
                if (result == SurfaceApproach.Result.BLOCKED && !benchStepBlocked) {
                    benchStepBlocked = true;
                    return Step.ACTIVE;
                }
                if (result == SurfaceApproach.Result.ARRIVED)
                    GameplayLog.event(body, "habitat", "street bench exited to={}", body.blockPosition().toShortString());
            }
            stop();
            return Step.ENDED;
        }
        if (phase == Phase.REST) {
            if (shelter != null && !facingOut()) SurfaceApproach.face(body, outward());
            if (checkRest(false)) return Step.RESTING;
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
                || (shelter != null ? shelter.isRemoved() || !shelter.foundUsable(body)
                : streetSpot != null ? !streetSpotValid() : !gapAt(gap))) {
            stop(); return Step.ENDED;
        }
        if (phase == Phase.WAIT) {
            boolean rivalInside = level().getEntity(yieldingRival) instanceof YukkuriEntity resident
                    && resident.isAlive() && shelter.contains(resident.blockPosition());
            if (!shelter.owns(yieldingRival) && !rivalInside) return inspect();
            if (now() <= deadline) return Step.ACTIVE;
            remember(target(), CONTEST_REST, "yield_exit_blocked");
            stop(); return Step.ENDED;
        }
        if (phase == Phase.SNIFF) {
            if (now() < deadline) return Step.ACTIVE;
            return inspect();
        }
        if (phase == Phase.APPROACH && body.distanceToSqr(aim) <= SNIFF_REACH * SNIFF_REACH
                && SurfaceApproach.stands(body, body.position())) return sniff();
        var result = streetSpot != null && !streetStandValid() ? SurfaceApproach.Result.BLOCKED
                : benchLaunch == null ? walk() : climbBench();
        if (streetSpot == StreetSpot.BENCH && atBenchSeat()
                && (result == SurfaceApproach.Result.BLOCKED || now() - began > 320) && fallBackToBenchFoot())
            return Step.ACTIVE;
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
        if (streetSpot == StreetSpot.BENCH && !body.onGround()) return Step.ACTIVE;
        if (streetSpot == StreetSpot.BENCH && !SurfaceApproach.at(body, aim)) {
            remember(gap, BLOCKED_REST, "bench_stand_unreached"); stop(); return Step.ENDED;
        }
        if (streetSpot != null && now() - began < 20) {
            SurfaceApproach.halt(body);
            return Step.ACTIVE;
        }
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
            if (yieldingRival == null && canContest(body) && level().getEntity(rival) instanceof YukkuriEntity resident
                    && canContest(resident) && !resident.life().isSleeping()) {
                if (shelter.occupants() == 1 && resolve(body) > resolve(resident)) {
                    long exitDeadline = resident.life().yieldFoundShelter(target());
                    if (exitDeadline > now()) {
                        yieldingRival = rival;
                        body.showReaction("cheek_puff", "", 40);
                        phase = Phase.WAIT; deadline = exitDeadline;
                        GameplayLog.event(body, "habitat", "found contest shelter={} outcome=wait rival={}", target(), rival);
                        return Step.ACTIVE;
                    }
                }
                resident.showReaction("cheek_puff", "", 40);
            }
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
        var centre = shelter.restSpot(pos);
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

    private Vec3 benchSide(BlockPos seat, Vec3 top, boolean climbing) {
        var facing = benchFacing(seat);
        if (facing == null) return null;
        Vec3 best = null;
        for (var side : new Direction[]{facing, facing.getOpposite()}) {
            var stand = SurfaceApproach.floor(body, Vec3.atBottomCenterOf(seat.relative(side)));
            if (stand == null || stand.y > seat.getY() || !availableStand(stand) || level().isRainingAt(BlockPos.containing(stand))
                    || best != null && body.distanceToSqr(stand) >= body.distanceToSqr(best))
                continue;
            if (climbing ? SurfaceApproach.canApproach(body, stand) && (top == null || Hop.reaches(body, stand, top))
                    : SurfaceApproach.canApproach(body, stand) || Hop.reaches(body, body.position(), stand)) best = stand;
        }
        return best;
    }

    private boolean fallBackToBenchFoot() {
        body.motor().cancel(this, "bench_seat_unavailable");
        var foot = benchSide(gap, null, true);
        if (foot == null) return false;
        aim = foot;
        benchLaunch = null;
        benchHop = null;
        began = now();
        return true;
    }

    private boolean atBenchSeat() { return gap.equals(BlockPos.containing(aim)); }

    private SurfaceApproach.Result climbBench() {
        if (benchHop != null) return hopToBenchAim();
        var approach = SurfaceApproach.walkResult(body, benchLaunch, null, 1);
        if (approach != SurfaceApproach.Result.ARRIVED) return approach;
        if (now() - began < 20) {
            SurfaceApproach.face(body, SurfaceApproach.yaw(body.position(), aim));
            return SurfaceApproach.Result.WAITING;
        }
        return hopToBenchAim();
    }

    private SurfaceApproach.Result hopToBenchAim() {
        if (benchHop == null) {
            benchHop = new Hop(aim);
            return body.motor().run(this, benchHop) ? SurfaceApproach.Result.MOVING : SurfaceApproach.Result.BLOCKED;
        }
        if (body.motor().current() == benchHop) return SurfaceApproach.Result.MOVING;
        return body.onGround() && SurfaceApproach.at(body, aim) ? SurfaceApproach.Result.ARRIVED : SurfaceApproach.Result.BLOCKED;
    }

    private void settle() {
        SurfaceApproach.halt(body);
        phase = Phase.REST;
        deadline = now() + 20; // Let the shared temperature sampler observe the reached cover before sleep.
        body.showReaction("closed_smile", "", 45);
        if (shelter != null) GameplayLog.event(body, "habitat", "found settled shelter={} kind={} occupants={}",
                target(), shelter.kind(), shelter.occupants());
        else if (streetSpot != null) {
            streetAfter = now() + BLOCKED_REST;
            GameplayLog.event(body, "habitat", "street rest spot={} at={} stand={} energy={}", streetSpot, gap.toShortString(),
                    streetSpot == StreetSpot.BENCH ? atBenchSeat() ? "seat" : "foot" : "cover", body.life().energy());
        } else GameplayLog.event(body, "habitat", "wind cover settled at={} paper={}", gap, paperAt(gap));
    }

    private boolean streetSpotValid() {
        return streetUser(body) && kindAt(gap) == streetSpot && !streetOccupied(body, gap);
    }

    private boolean streetStandValid() {
        return SurfaceApproach.stands(body, aim)
                && (streetSpot != StreetSpot.BENCH || !level().isRainingAt(BlockPos.containing(aim)));
    }

    public boolean checkRest(boolean waking) {
        if (phase != Phase.REST) return false;
        var here = body.blockPosition();
        boolean displaced = shelter == null ? !BlockPos.containing(aim).equals(here) : !shelter.contains(here);
        String reason = body.life().nutrition() < LEAVE_HUNGRY ? "hungry"
                : !canTakeFoundShelter(body) ? "ineligible"
                : displaced ? "displaced"
                : shelter != null && (shelter.rainStage() > 0 || !shelter.sound()) ? "soaked_or_unsafe"
                : streetSpot != null && (!streetSpotValid() || !streetStandValid()) ? "street_spot_gone"
                : streetSpot == null && gap != null && !gapAt(gap) ? "cover_gone"
                : body.tickCount % 20 == 0 && !level().getEntitiesOfClass(YukkuriEntity.class, body.getBoundingBox().inflate(5),
                        y -> y.isAlive() && y.profile().dangerToYukkuri() && body.hasLineOfSight(y)).isEmpty() ? "predator" : null;
        if (reason == null && (waking || body.life().isSleeping() || wantsCover() || streetSpot != null && level().isRaining())) return true;
        leave(reason == null ? "rested" : reason);
        return false;
    }

    public boolean humanNearBench() {
        return restingAtBench() && (level().isDay() && !level().getEntitiesOfClass(Player.class, body.getBoundingBox().inflate(3),
                person -> person.isAlive() && !person.isSpectator() && body.distanceToSqr(person) <= 9).isEmpty()
                || !level().getEntitiesOfClass(HumanNpc.class, body.getBoundingBox().inflate(3),
                person -> person.isAlive() && body.distanceToSqr(person) <= 9).isEmpty()
                || !level().getEntitiesOfClass(Villager.class, body.getBoundingBox().inflate(3),
                person -> person.isAlive() && body.distanceToSqr(person) <= 9).isEmpty());
    }

    public boolean leaveBench() {
        if (!restingAtBench()) return false;
        var seat = gap;
        if (!atBenchSeat()) {
            GameplayLog.event(body, "habitat", "street bench foot released at={}", seat.toShortString());
            return false;
        }
        var stand = benchSide(seat, null, false);
        if (stand == null) return false;
        phase = Phase.STEP_DOWN;
        aim = stand;
        benchHop = null;
        deadline = now() + 100;
        GameplayLog.event(body, "habitat", "street bench exit at={} to={}", seat.toShortString(), BlockPos.containing(stand).toShortString());
        return true;
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

    /** Deadline for the physical exit, or zero if this resident cannot yield now. */
    public long yield(BlockPos pos) {
        if (!targeting(pos) || phase != Phase.REST || body.life().isSleeping()) return 0;
        remember(pos, CONTEST_REST, "yield"); leave("yield");
        return deadline;
    }

    public void removed(BlockPos pos, boolean wet) {
        if (wet) body.life().applyEnvironment(12, 0, 0);
        body.showReaction("surprise", "", 60);
        if (targeting(pos)) stop();
    }

    public void stop() {
        if (!active()) return;
        body.motor().cancel(this, "found_cover_ended");
        if (shelter != null && !shelter.isRemoved() && (exiting() || !shelter.contains(body.blockPosition())))
            shelter.release(body.getUUID());
        shelter = null; gap = null; aim = null; phase = null; streetSpot = null; yieldingRival = null; failures = 0; warnUntil = 0;
        benchLaunch = null; benchHop = null; benchStepBlocked = false;
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
