package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.body.MouthPlacement;
import io.github.longshou.mcyukkuri.body.PaperCeiling;
import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.entity.YukkuriComponents;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import io.github.longshou.mcyukkuri.family.FamilyLife;
import io.github.longshou.mcyukkuri.headwear.HeadTop;
import io.github.longshou.mcyukkuri.human.HumanNpc;
import io.github.longshou.mcyukkuri.human.NewspaperStackBlock;
import io.github.longshou.mcyukkuri.human.WoodenBenchBlock;
import io.github.longshou.mcyukkuri.interaction.ItemContact;
import io.github.longshou.mcyukkuri.life.BlockPull;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.life.TravelRange;
import io.github.longshou.mcyukkuri.life.YukkuriConfig;
import io.github.longshou.mcyukkuri.motor.Action;
import io.github.longshou.mcyukkuri.motor.ClipMarkers;
import io.github.longshou.mcyukkuri.motor.Feasibility;
import io.github.longshou.mcyukkuri.motor.MotionPose;
import io.github.longshou.mcyukkuri.motor.Motor;
import io.github.longshou.mcyukkuri.motor.MotorOwner;
import io.github.longshou.mcyukkuri.motor.MouthOccupancy;
import io.github.longshou.mcyukkuri.motor.Outcome;
import io.github.longshou.mcyukkuri.motor.Progress;
import io.github.longshou.mcyukkuri.motor.Reason;
import io.github.longshou.mcyukkuri.motor.Running;
import io.github.longshou.mcyukkuri.motor.TravelMode;
import io.github.longshou.mcyukkuri.motor.action.Hop;
import io.github.longshou.mcyukkuri.pose.PaperTent;
import io.github.longshou.mcyukkuri.rare.RareForms;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.status.Identity;
import io.github.longshou.mcyukkuri.subspecies.SubspeciesLife;
import io.github.longshou.mcyukkuri.world.SearchBudget;
import io.github.longshou.mcyukkuri.world.SearchBudget.Purpose;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.EventHooks;

/** Temporary cover competes in the life plan. Only the shelter core records occupants. */
public final class FoundShelter implements MotorOwner {
    private static final int RADIUS = 8, CONTEST_REST = 1200, BLOCKED_REST = 2400;
    private static final float LEAVE_HUNGRY = 25, SEEK_HUNGRY = 30, EXHAUSTED_PAPER_SLEEP_ENERGY = 15;
    private static final double SNIFF_REACH = .4;
    private enum Phase { APPROACH, SNIFF, WAIT, PULL_PAPER, ENTER, PLACE_PAPER, DRAPE_CHILD, REST, EXIT, STEP_DOWN }
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
    private record StackFace(BlockPos block, Direction side) {}
    private final Set<StackFace> triedPaperFaces = new HashSet<>();
    private final Set<UUID> triedGroundPaper = new HashSet<>();
    private final Set<UUID> triedChildDrape = new HashSet<>();
    private long nextPaperScan;
    private BlockPull.Stand paperStand;
    private PlacePaperAction placeAction;
    private CoverPaperAction coverAction;
    private Action pullAction;
    private UUID drapeChild;
    private boolean drapeLayFallback;
    private Action drapeAction;
    private boolean drapedChildInRest;
    private boolean decidedSelfFirst;
    private int babyWaitTicks;
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
    public boolean carryingPaper() { return shelter == null && streetSpot == null && gap != null; }
    public boolean restingInPlainGap() { return shelter == null && streetSpot == null && gap != null && phase == Phase.REST; }
    public boolean restingInPlainGapAt(BlockPos pos) { return restingInPlainGap() && gap.equals(pos); }
    public boolean readyToSleep() {
        if (phase != Phase.REST || now() < deadline || !facingOut() || coverAction != null) return false;
        if (!body.life().temperature().unsafeSleep()) return true;
        if (restingInPlainGapAt(body.blockPosition()) && !body.life().paperCover().isEmpty()
                && !body.life().temperature().hot() && body.life().energy() <= EXHAUSTED_PAPER_SLEEP_ENERGY) {
            GameplayLog.event(body, "habitat", "cold paper exhausted sleep at={} energy={} coldExposure={}",
                    gap, body.life().energy(), body.life().temperature().coldExposure());
            return true;
        }
        return false;
    }
    /** Lying in a box it faces out of the opening, the way a stray peeks out; the shelter's room is measured for that pose too. */
    private float outward() { return shelter.facing().toYRot(); }
    private boolean facingOut() { return shelter == null || Math.abs(Mth.wrapDegrees(body.bodyYaw() - outward())) < 2; }
    public boolean restingAtBench() { return streetSpot == StreetSpot.BENCH && phase == Phase.REST; }
    public boolean ownsStreetCover(BlockPos pos) { return streetSpot != null && phase == Phase.REST && pos.equals(gap); }
    public ShelterBlockEntity inside() {
        if (!(body.level() instanceof ServerLevel)) return null;
        var core = ShelterBlock.foundInterior(level(), body.blockPosition());
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

    private static boolean canPullPaperBody(YukkuriEntity y) {
        return y.level() instanceof ServerLevel level
                && y.profile().landSettler() && y.family().stage() != FamilyLife.Stage.BABY
                && !y.isPassenger() && !y.isInWaterOrBubble() && !y.isOnFire()
                && !y.operations().active() && !y.body().dying()
                && EventHooks.canEntityGrief(level, y);
    }

    public static boolean canPullPaper(YukkuriEntity y) {
        return canPullPaperBody(y)
                && y.component(YukkuriComponents.MOUTH).freeForExternalWork()
                && y.habitat().carrying().isEmpty()
                && y.forageTrip().load().isEmpty()
                && y.independence().carrying().isEmpty();
    }

    /** Winged, tailed, shelled, aquatic, house, kotatsu-table and swaddled/carrying forms skip the rear-arched paper tent. */
    public static boolean canWearPaperCover(YukkuriEntity y) {
        return y.profile().landSettler()
                && y.getVariant() != YukkuriVariant.CHEN
                && y.subspecies().kind() != SubspeciesLife.Kind.SHELL_MARISA
                && !RareForms.kotatsu(y)
                && !y.isVehicle() && !y.isPassenger()
                && !y.pet().care().swaddled() && !y.leafSwaddle().worn();
    }

    public static boolean canWearPaperCoverNow(YukkuriEntity y) {
        if (!(y.level() instanceof ServerLevel level) || !canWearPaperCover(y)) return false;
        BlockPos pos = y.blockPosition();
        return y.life().wetness() < 50 && !level.isRainingAt(pos) && y.life().paperCoverFits();
    }

    private boolean canWearPaperCoverNow() {
        return canWearPaperCover(body)
                && body.life().wetness() < 50
                && !level().isRainingAt(body.blockPosition())
                && !level().isRainingAt(gap) && paperCoverFits();
    }

    public boolean paperCoverFits() {
        Vec3 feet = gap != null && gap.equals(body.blockPosition()) ? body.position()
                : gap != null ? Vec3.atBottomCenterOf(gap) : body.position();
        return PaperTent.fitsBelow(body.getBbHeight(), HeadTop.height(body), PaperCeiling.height(body, feet));
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

    /** An occupant goes to a rest spot or uses the opening, including when it has no saved claim. */
    public boolean planInside(ShelterBlockEntity home, boolean freeToRest) {
        if (now() < nextScan || body.isOnFire() || body.operations().active()) return false;
        nextScan = now() + 80;
        // The rest it joins ends below LEAVE_HUNGRY (tick), so an occupant is not sent out at a hunger it may rest at.
        if (freeToRest && wantsCover() && body.life().nutrition() >= LEAVE_HUNGRY && canTakeFoundShelter(body)
                && !refusing(home.getBlockPos()) && home.foundUsable(body)
                && home.residentIds().stream().allMatch(id -> home.related(body.getUUID(), id))) {
            shelter = home;
            aim = restPoint(home.foundBed(body), null);
            if (aim != null) {
                phase = Phase.ENTER;
                began = now();
                failures = 0;
                GameplayLog.event(body, "habitat", "found shift to spot shelter={} spot={}", home.getBlockPos(), GameplayLog.at(aim));
                return true;
            }
            shelter = null;
        }
        shelter = home;
        // The exit phase tries the guarded opening step before falling back to the route planner.
        leave("no_rest_claim");
        return true;
    }

    private ShelterBlockEntity findUsableShelter(BlockPos here, boolean requireRoute) {
        var nearby = LoadedShelters.within(level(), here.offset(-RADIUS, -2, -RADIUS), here.offset(RADIUS, 2, RADIUS));
        return nearby.stream().filter(s -> s.kind().found() && s.foundUsable(body)
                        && (canContest(body) || s.residentIds().stream().allMatch(id -> s.related(body.getUUID(), id)))
                        && TravelRange.babyDestination(body, s.getBlockPos()) && !refusing(s.getBlockPos())
                        && (!requireRoute || SurfaceApproach.canApproach(body, Vec3.atBottomCenterOf(s.entrance()))))
                .min(Comparator.comparingDouble(s -> body.distanceToSqr(Vec3.atBottomCenterOf(s.entrance())))).orElse(null);
    }

    public boolean plan() {
        if (now() < nextScan || !canTakeFoundShelter(body) || body.life().nutrition() < SEEK_HUNGRY
                || body.isOnFire() || body.operations().active() || !wantsCover() && !seekingStreetRoof()) return false;
        nextScan = now() + 80;
        var here = body.blockPosition();
        var found = findUsableShelter(here, false);
        if (found != null) {
            shelter = found;
            aim = Vec3.atBottomCenterOf(found.entrance());
            phase = Phase.APPROACH;
            began = now();
            GameplayLog.event(body, "habitat", "found approach shelter={} kind={}", found.getBlockPos(), found.kind());
            return true;
        }
        if (now() >= streetAfter && streetUser(body) && planStreet(here)) return true;
        gap = findGap(here);
        if (gap == null) return false;
        triedPaperFaces.clear();
        triedGroundPaper.clear();
        triedChildDrape.clear();
        nextPaperScan = 0;
        paperStand = null;
        pullAction = null;
        placeAction = null;
        coverAction = null;
        drapeChild = null;
        drapeLayFallback = false;
        drapeAction = null;
        drapedChildInRest = false;
        decidedSelfFirst = false;
        babyWaitTicks = 0;
        if (tryAdvancePaperFetch(here) == SearchBudget.Result.FOUND) return true;
        enterGap();
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

    private int paperCountAt(BlockPos pos) {
        int total = 0;
        for (var item : level().getEntitiesOfClass(ItemEntity.class, new AABB(pos),
                item -> item.isAlive() && item.getItem().is(Items.PAPER)))
            total += item.getItem().getCount();
        return total;
    }

    private boolean holdingPaper() {
        var mouth = body.component(YukkuriComponents.MOUTH);
        return mouth.purpose() == MouthOccupancy.Purpose.PAPER && !mouth.items(MouthOccupancy.Purpose.PAPER).isEmpty();
    }

    private boolean needsMouthCoverAtGap() {
        return gap != null && paperAt(gap) && body.life().temperature().cold()
                && body.life().paperCover().isEmpty() && canWearPaperCoverNow();
    }

    private int otherBeddingUsersAt(BlockPos pos) {
        return level().getEntitiesOfClass(YukkuriEntity.class, new AABB(pos).inflate(1),
                other -> other != body && other.isAlive() && other.life().usingPaperBedding(pos)).size();
    }

    private ItemEntity groundSheetForCover(BlockPos pos) {
        return groundSheetForCover(pos, null);
    }

    private ItemEntity groundSheetForCover(BlockPos pos, UUID source) {
        if (triedGroundPaper.size() >= 8 || paperCountAt(pos) <= otherBeddingUsersAt(pos)) return null;
        Vec3 mouth = MouthPlacement.position(body);
        AABB reachBox = body.getBoundingBox().inflate(0.55, 0.3, 0.55);
        return level().getEntitiesOfClass(ItemEntity.class, new AABB(pos),
                        item -> item.isAlive() && item.getItem().is(Items.PAPER) && !item.getItem().isEmpty()
                                && (source == null || source.equals(item.getUUID()))
                                && !triedGroundPaper.contains(item.getUUID())
                                && item.getBoundingBox().intersects(reachBox)
                                && ItemContact.clear(body, mouth, item.position().add(0, 0.06, 0)))
                .stream().min(Comparator.comparingDouble(body::distanceToSqr)).orElse(null);
    }

    private boolean humanNearStack(YukkuriEntity actor, BlockPos stackPos) {
        var lvl = (ServerLevel) actor.level();
        var center = Vec3.atCenterOf(stackPos);
        var box = new AABB(stackPos).inflate(3);
        return !lvl.getEntitiesOfClass(Player.class, box,
                        p -> p.isAlive() && !p.isSpectator() && p.distanceToSqr(center) <= 9 && actor.hasLineOfSight(p)).isEmpty()
                || !lvl.getEntitiesOfClass(HumanNpc.class, box,
                        p -> p.isAlive() && p.distanceToSqr(center) <= 9 && actor.hasLineOfSight(p)).isEmpty()
                || !lvl.getEntitiesOfClass(Villager.class, box,
                        p -> p.isAlive() && p.distanceToSqr(center) <= 9 && actor.hasLineOfSight(p)).isEmpty();
    }

    private boolean canPullFromStack(YukkuriEntity actor, BlockPos pos) {
        if (!canPullPaperBody(actor)) return false;
        var lvl = (ServerLevel) actor.level();
        return lvl.hasChunkAt(pos)
                && NewspaperStackBlock.sheets(lvl.getBlockState(pos)) > 0
                && !lvl.isRainingAt(pos.above())
                && !lvl.isRainingAt(actor.blockPosition())
                && !(actor.life().personality().boldness() < 45 && humanNearStack(actor, pos));
    }

    private Set<Direction> triedSidesAt(BlockPos pos) {
        var sides = EnumSet.noneOf(Direction.class);
        for (var tried : triedPaperFaces) if (tried.block().equals(pos)) sides.add(tried.side());
        return sides;
    }

    private boolean plainGapAt(BlockPos pos) {
        return gapAt(pos) && roofKind(level(), pos) == null;
    }

    private boolean samePlainGap(BlockPos a, BlockPos b) {
        if (a == null || b == null || !plainGapAt(a) || !plainGapAt(b) || Math.abs(a.getY() - b.getY()) > 1
                || a.distManhattan(b) > 6) return false;
        if (a.equals(b)) return true;
        var seen = new HashSet<BlockPos>();
        var queue = new ArrayDeque<BlockPos>();
        seen.add(a);
        queue.add(a);
        while (!queue.isEmpty() && seen.size() < 12) {
            BlockPos cur = queue.poll();
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                for (int dy : new int[]{0, -1, 1}) {
                    BlockPos next = cur.relative(dir).above(dy);
                    if (Math.abs(next.getY() - a.getY()) > 1 || next.distManhattan(a) > 6 || !seen.add(next)) continue;
                    if (!plainGapAt(next)) continue;
                    if (next.equals(b)) return true;
                    queue.add(next);
                }
            }
        }
        return false;
    }

    private BlockPos freeCellInSameGap(BlockPos origin) {
        if (origin != null && plainGapAt(origin) && availableStand(Vec3.atBottomCenterOf(origin))) return origin;
        if (origin == null) return null;
        BlockPos best = null;
        double bestDist = Double.POSITIVE_INFINITY;
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-4, -1, -4), origin.offset(4, 1, 4))) {
            if (!samePlainGap(origin, pos)) continue;
            Vec3 stand = Vec3.atBottomCenterOf(pos);
            if (!availableStand(stand) || !SurfaceApproach.canApproach(body, stand)) continue;
            double dist = body.distanceToSqr(stand);
            if (dist < bestDist) {
                bestDist = dist;
                best = pos.immutable();
            }
        }
        return best;
    }

    private boolean familyDutyPreempts(boolean periodicScan) {
        if (body.family().active() || body.family().carryingOrLifting() || body.woundComfort().active()) return true;
        if (!periodicScan || body.life().hasUrgentNeed()) return false;
        return body.woundComfort().plan() || body.family().hasExecutableChildCare();
    }

    private static double ramp(double value, double low, double high) {
        return Math.clamp((value - low) / (high - low), 0.0, 1.0);
    }

    /** Continuous stat range: attachment and friendliness weigh baby-first against the parent's own chill. */
    private boolean prefersBabyCover() {
        var p = body.life().personality();
        double devotion = 0.55 * ramp(p.attachment(), 15, 85) + 0.45 * ramp(p.friendliness(), 15, 85);
        double coldSpan = Math.max(0.0, YukkuriConfig.COLD_THRESHOLD.get() - body.life().temperature().sample(body.blockPosition()).felt());
        double selfCold = body.life().temperature().cold() && body.life().paperCover().isEmpty()
                ? 0.35 + 0.35 * ramp(coldSpan, 0.0, 1.2) : 0.0;
        return devotion >= selfCold;
    }

    private boolean eligibleOwnColdBaby(YukkuriEntity child) {
        return child != body && child.isAlive() && child.family().stage() == FamilyLife.Stage.BABY
                && body.family().adult() && body.family().caresFor(child) && body.life().canRecognize(child)
                && !triedChildDrape.contains(child.getUUID())
                && child.life().temperature().cold() && child.life().paperCover().isEmpty()
                && canWearPaperCoverNow(child)
                && body.distanceToSqr(child) <= TravelRange.NURSE_RADIUS * TravelRange.NURSE_RADIUS;
    }

    private YukkuriEntity coldUncoveredBabyInSameGap() {
        if (gap == null || !body.family().adult() || familyDutyPreempts(false) || triedChildDrape.size() >= 8) return null;
        return level().getEntitiesOfClass(YukkuriEntity.class, body.getBoundingBox().inflate(TravelRange.NURSE_RADIUS),
                        c -> eligibleOwnColdBaby(c) && c.life().restingInPlainGapAt(c.blockPosition())
                                && samePlainGap(gap, c.blockPosition()))
                .stream().min(Comparator.comparingDouble(body::distanceToSqr)).orElse(null);
    }

    private YukkuriEntity coldUncoveredBabyEnteringSameGap() {
        if (gap == null || !body.family().adult() || familyDutyPreempts(false)) return null;
        return level().getEntitiesOfClass(YukkuriEntity.class, body.getBoundingBox().inflate(TravelRange.NURSE_RADIUS),
                        c -> eligibleOwnColdBaby(c) && samePlainGap(gap, c.blockPosition())
                                || eligibleOwnColdBaby(c) && c.distanceToSqr(Vec3.atBottomCenterOf(gap)) <= 16)
                .stream().min(Comparator.comparingDouble(body::distanceToSqr)).orElse(null);
    }

    private boolean holdsPaperForGapDecision() {
        return coldUncoveredBabyInSameGap() != null || babyWaitTicks < 160 && coldUncoveredBabyEnteringSameGap() != null;
    }

    private boolean coveredOccupantInSameGap() {
        if (gap == null) return false;
        return !level().getEntitiesOfClass(YukkuriEntity.class, new AABB(gap).inflate(4, 1, 4),
                y -> y.isAlive() && !y.life().paperCover().isEmpty()
                        && y.life().restingInPlainGapAt(y.blockPosition())
                        && samePlainGap(gap, y.blockPosition())).isEmpty();
    }

    private Vec3 flankStandForBaby(YukkuriEntity baby) {
        BlockPos babyCell = baby.blockPosition();
        Direction wallSide = null;
        for (Direction side : Direction.Plane.HORIZONTAL) {
            BlockPos wall = babyCell.relative(side);
            if (level().hasChunkAt(wall) && level().getBlockState(wall).isFaceSturdy(level(), wall, side.getOpposite())) {
                wallSide = side;
                break;
            }
        }
        Direction[] flanks = wallSide != null
                ? new Direction[]{wallSide.getClockWise(), wallSide.getCounterClockWise(), wallSide.getOpposite()}
                : new Direction[]{Direction.EAST, Direction.WEST, Direction.SOUTH, Direction.NORTH};
        double spacing = (body.getBbWidth() + baby.getBbWidth()) * 0.5 + 0.068;
        double mouthOffsetY = Math.max(0.05, MouthPlacement.position(body).y - body.getY());
        Vec3 babyBack = baby.position().add(0, baby.getBbHeight() * 0.7, 0);
        Vec3 best = null;
        double bestDist = Double.POSITIVE_INFINITY;
        for (Direction dir : flanks) {
            Vec3 probe = baby.position().add(dir.getStepX() * spacing, 0, dir.getStepZ() * spacing);
            Vec3 stand = SurfaceApproach.floor(body, probe);
            if (stand == null || Math.abs(stand.y - baby.getY()) > 0.18) continue;
            if (!samePlainGap(gap, BlockPos.containing(stand)) || !availableStand(stand)) continue;
            if (HeadTop.height(body) + 0.02 > PaperCeiling.height(body, stand)) continue;
            Vec3 mouthAtStand = stand.add(0, mouthOffsetY, 0);
            if (level().clip(new ClipContext(mouthAtStand, babyBack,
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, body)).getType() != HitResult.Type.MISS)
                continue;
            if (!SurfaceApproach.at(body, stand) && !SurfaceApproach.canApproach(body, stand)) continue;
            double dist = body.position().distanceToSqr(stand);
            if (dist < bestDist) {
                bestDist = dist;
                best = stand;
            }
        }
        return best;
    }

    private Vec3 layBesideBabyStand(YukkuriEntity baby) {
        BlockPos babyCell = baby.blockPosition();
        double mouthOffsetY = Math.max(0.05, MouthPlacement.position(body).y - body.getY());
        Vec3 target = Vec3.atBottomCenterOf(babyCell).add(0, 0.08, 0);
        Vec3 best = null;
        double bestDist = Double.POSITIVE_INFINITY;
        for (Direction side : Direction.Plane.HORIZONTAL) {
            BlockPos adj = babyCell.relative(side);
            Vec3 stand = SurfaceApproach.floor(body, Vec3.atBottomCenterOf(adj));
            if (stand == null || Math.abs(stand.y - baby.getY()) > 0.35 || !availableStand(stand)) continue;
            if (HeadTop.height(body) + 0.02 > PaperCeiling.height(body, stand)) continue;
            if (level().clip(new ClipContext(stand.add(0, mouthOffsetY, 0), target,
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, body)).getType() != HitResult.Type.MISS)
                continue;
            if (!SurfaceApproach.at(body, stand) && !SurfaceApproach.canApproach(body, stand)) continue;
            double dist = body.position().distanceToSqr(stand);
            if (dist < bestDist) {
                bestDist = dist;
                best = stand;
            }
        }
        return best;
    }

    private boolean liveBabyFlankContact(YukkuriEntity actor, YukkuriEntity baby, boolean requireFacing) {
        if (!actor.onGround() || actor.isPassenger() || actor.isInWaterOrBubble() || actor.isOnFire()) return false;
        if (!baby.isAlive() || baby.level() != actor.level() || !baby.life().canAcceptPaperCover()
                || !samePlainGap(gap, baby.blockPosition())) return false;
        Vec3 delta = baby.position().subtract(actor.position()).multiply(1, 0, 1);
        double dist = delta.length();
        double min = Math.max(0.18, (actor.getBbWidth() + baby.getBbWidth()) * 0.5 - 0.03);
        double max = (actor.getBbWidth() + baby.getBbWidth()) * 0.5 + 0.24;
        if (dist < min || dist > max || Math.abs(actor.getY() - baby.getY()) > 0.22) return false;
        if (requireFacing) {
            Vec3 forward = Vec3.directionFromRotation(0, actor.getYHeadRot()).multiply(1, 0, 1).normalize();
            if (forward.dot(delta.scale(1 / dist)) < 0.75) return false;
        }
        Vec3 mouth = MouthPlacement.position(actor);
        Vec3 babyBack = baby.position().add(0, baby.getBbHeight() * 0.7, 0);
        return actor.level().clip(new ClipContext(mouth, babyBack,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, actor)).getType() == HitResult.Type.MISS;
    }

    private YukkuriEntity liveBabyTarget(UUID id) {
        if (id == null || !(level().getEntity(id) instanceof YukkuriEntity baby)) return null;
        return eligibleOwnColdBaby(baby) && baby.life().restingInPlainGapAt(baby.blockPosition())
                && samePlainGap(gap, baby.blockPosition()) ? baby : null;
    }

    private Step tryBeginDrapeChild(YukkuriEntity baby) {
        Vec3 flank = flankStandForBaby(baby);
        if (flank != null) {
            drapeChild = baby.getUUID();
            drapeLayFallback = false;
            drapeAction = null;
            aim = flank;
            phase = Phase.DRAPE_CHILD;
            began = now();
            failures = 0;
            GameplayLog.event(body, "habitat", "baby paper cover approach child={} stand={} gap={}",
                    baby.getUUID(), GameplayLog.at(flank), baby.blockPosition());
            return Step.ACTIVE;
        }
        Vec3 lay = layBesideBabyStand(baby);
        if (lay != null) {
            drapeChild = baby.getUUID();
            drapeLayFallback = true;
            drapeAction = null;
            aim = lay;
            phase = Phase.DRAPE_CHILD;
            began = now();
            failures = 0;
            GameplayLog.event(body, "habitat", "baby paper lay fallback approach child={} stand={} gap={}",
                    baby.getUUID(), GameplayLog.at(lay), baby.blockPosition());
            return Step.ACTIVE;
        }
        triedChildDrape.add(baby.getUUID());
        return null;
    }

    private Step returnFromChildDrape() {
        drapeChild = null;
        drapeLayFallback = false;
        drapeAction = null;
        BlockPos free = freeCellInSameGap(gap);
        if (free == null) {
            leave("no_rest_space");
            return Step.ENDED;
        }
        gap = free;
        return enterGap();
    }

    private String neededPaperFetchEvent() {
        if (holdingPaper() || drapedChildInRest || !body.life().paperCover().isEmpty() || !canPullPaper(body))
            return null;
        if (!paperAt(gap) && !coveredOccupantInSameGap()) return "wind cover paper approach stack={} gap={}";
        if ((phase == Phase.REST || phase == Phase.PULL_PAPER) && body.life().temperature().cold() && canWearPaperCoverNow()) {
            boolean wantSecond = paperCountAt(gap) == 1 && body.life().personality().boldness() >= 50
                    && body.life().energy() >= 25;
            if (wantSecond || groundSheetForCover(gap) == null)
                return "paper cover fetch stack={} gap={}";
        }
        return null;
    }

    private SearchBudget.Result tryAdvancePaperFetch(BlockPos origin) {
        if (now() < nextPaperScan) return SearchBudget.Result.NOT_FOUND;
        String event = neededPaperFetchEvent();
        if (event == null) return SearchBudget.Result.NOT_FOUND;
        if (!SearchBudget.take(level(), body, Purpose.BEDDING)) return SearchBudget.Result.DEFERRED;
        triedPaperFaces.removeIf(face -> level().hasChunkAt(face.block())
                && NewspaperStackBlock.sheets(level().getBlockState(face.block())) == 0);
        var options = new ArrayList<BlockPos>();
        int reach = TravelRange.search(body, RADIUS);
        for (var pos : BlockPos.betweenClosed(origin.offset(-reach, -1, -reach), origin.offset(reach, 2, reach))) {
            if (refusing(pos) || !TravelRange.babyDestination(body, pos) || !canPullFromStack(body, pos)) continue;
            if (triedSidesAt(pos).size() >= 4) continue;
            options.add(pos.immutable());
        }
        options.sort(Comparator.comparingDouble(origin::distSqr));
        for (var pos : options.stream().limit(4).toList()) {
            var stand = BlockPull.stand(body, pos, triedSidesAt(pos));
            if (stand != null) {
                paperStand = stand;
                pullAction = null;
                aim = stand.feet();
                phase = Phase.PULL_PAPER;
                began = now();
                failures = 0;
                GameplayLog.event(body, "habitat", event, stand.block(), gap);
                return SearchBudget.Result.FOUND;
            }
        }
        // Look for newly available sources later. Rejected faces stay excluded for this rest.
        nextPaperScan = now() + 80;
        return SearchBudget.Result.NOT_FOUND;
    }

    private Step enterGap() {
        pullAction = null;
        paperStand = null;
        if (!availableStand(Vec3.atBottomCenterOf(gap))) {
            BlockPos free = freeCellInSameGap(gap);
            if (free != null) gap = free;
        }
        aim = Vec3.atBottomCenterOf(gap);
        phase = Phase.ENTER;
        began = now();
        failures = 0;
        return Step.ACTIVE;
    }

    private Step beginPlacePaper() {
        placeAction = new PlacePaperAction(gap);
        if (body.motor().run(this, placeAction)) {
            phase = Phase.PLACE_PAPER;
            began = now();
            return Step.ACTIVE;
        }
        placeAction = null;
        remember(gap, BLOCKED_REST, "paper_place_refused");
        leave("paper_place_refused");
        return Step.ENDED;
    }

    private Step abortPullAttempt(String reason) {
        triedPaperFaces.add(new StackFace(paperStand.block(), paperStand.side()));
        GameplayLog.event(body, "habitat", "paper fetch declined stack={} side={} reason={}",
                paperStand.block(), paperStand.side(), reason);
        pullAction = null;
        paperStand = null;
        if (tryAdvancePaperFetch(body.blockPosition()) == SearchBudget.Result.FOUND) return Step.ACTIVE;
        return enterGap();
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
            if (coverAction != null) {
                if (!checkRest(false)) return exiting() ? Step.ACTIVE : Step.ENDED;
                if (body.motor().current() == coverAction) return Step.RESTING;
                UUID attemptedGround = coverAction.groundSource;
                boolean succeeded = coverAction.draped;
                coverAction = null;
                deadline = Math.max(deadline, now() + 20);
                if (!succeeded) {
                    if (attemptedGround != null) triedGroundPaper.add(attemptedGround);
                    else if (holdingPaper()) return beginPlacePaper();
                }
            }
            if (shelter != null && !facingOut()) SurfaceApproach.face(body, outward());
            if (!checkRest(false)) return exiting() ? Step.ACTIVE : Step.ENDED;
            if (restingInPlainGap() && !body.life().isSleeping()) {
                if (holdingPaper()) {
                    var baby = coldUncoveredBabyInSameGap();
                    if (baby != null) {
                        if (prefersBabyCover()) {
                            Step childStep = tryBeginDrapeChild(baby);
                            if (childStep != null) return childStep;
                        } else {
                            if (!decidedSelfFirst) {
                                decidedSelfFirst = true;
                                GameplayLog.event(body, "habitat", "paper cover kept for self child={} at={}", baby.getUUID(), gap);
                            }
                            if (body.life().temperature().cold() && body.life().paperCover().isEmpty() && canWearPaperCoverNow()) {
                                coverAction = new CoverPaperAction(gap, null);
                                if (body.motor().run(this, coverAction)) return Step.RESTING;
                                coverAction = null;
                            }
                        }
                    } else if (babyWaitTicks < 160 && coldUncoveredBabyEnteringSameGap() != null) {
                        babyWaitTicks++;
                        return Step.RESTING;
                    }
                    if (!needsMouthCoverAtGap()) return beginPlacePaper();
                    coverAction = new CoverPaperAction(gap, null);
                    if (!body.motor().run(this, coverAction)) {
                        coverAction = null;
                        return beginPlacePaper();
                    }
                    return Step.RESTING;
                }
                if (now() >= deadline) {
                    var fetch = tryAdvancePaperFetch(gap);
                    if (fetch == SearchBudget.Result.FOUND) return Step.ACTIVE;
                    if (fetch == SearchBudget.Result.DEFERRED) return Step.RESTING;
                    if (body.life().temperature().cold() && body.life().paperCover().isEmpty() && canWearPaperCoverNow()) {
                        var sheet = groundSheetForCover(gap);
                        if (sheet != null) {
                            coverAction = new CoverPaperAction(gap, sheet.getUUID());
                            if (!body.motor().run(this, coverAction)) {
                                coverAction = null;
                                triedGroundPaper.add(sheet.getUUID());
                            }
                        }
                    }
                }
            }
            return Step.RESTING;
        }
        if (exiting()) {
            if (!shelter.contains(body.blockPosition())) {
                GameplayLog.event(body, "habitat", "found exited shelter={}", target());
                stop();
                return Step.ENDED;
            }
            if (now() < warnUntil) return Step.ACTIVE;
            if (now() >= deadline || walk() == SurfaceApproach.Result.BLOCKED) {
                GameplayLog.limited(body, "habitat", "exit_blocked", 200,
                        "found exit blocked shelter={} opening={}", target(), shelter.entrance());
                nextScan = now() + 80;
                stop();
                return Step.ENDED;
            }
            return Step.ACTIVE;
        }
        if (!canTakeFoundShelter(body) || body.life().nutrition() < LEAVE_HUNGRY
                || (shelter != null ? shelter.isRemoved() || !shelter.foundUsable(body)
                : streetSpot != null ? !streetSpotValid() : !gapAt(gap))) {
            stop(); return Step.ENDED;
        }
        if (phase == Phase.PULL_PAPER) {
            if (pullAction != null) {
                if (body.motor().current() == pullAction) return Step.ACTIVE;
                if (holdingPaper()) {
                    triedPaperFaces.clear();
                    return enterGap();
                }
                return abortPullAttempt("paper_tug_failed");
            }
            if (!canPullFromStack(body, paperStand.block()))
                return abortPullAttempt("paper_source_unavailable");
            if (now() - began > 240)
                return abortPullAttempt("paper_approach_timeout");
            if ((now() - began) % 20 == 19 && !BlockPull.reaches(body, paperStand)
                    && !level().getEntitiesOfClass(YukkuriEntity.class,
                            body.getBoundingBox().move(paperStand.feet().subtract(body.position())).inflate(0.25, 0.1, 0.25),
                            y -> y != body && y.isAlive()).isEmpty()) {
                var skip = triedSidesAt(paperStand.block());
                skip.add(paperStand.side());
                var alt = BlockPull.stand(body, paperStand.block(), skip);
                if (alt != null) {
                    triedPaperFaces.add(new StackFace(paperStand.block(), paperStand.side()));
                    paperStand = alt;
                    aim = alt.feet();
                    began = now();
                }
            }
            var pullStep = walk();
            if (pullStep == SurfaceApproach.Result.BLOCKED)
                return abortPullAttempt("paper_approach_blocked");
            if (pullStep == SurfaceApproach.Result.ARRIVED && body.onGround() || BlockPull.reaches(body, paperStand)) {
                SurfaceApproach.halt(body);
                pullAction = BlockPull.action(paperStand, MouthOccupancy.Purpose.PAPER,
                        this::canPullFromStack, NewspaperStackBlock::takeSheet,
                        stack -> GameplayLog.event(body, "habitat", "gathered item={} count={} source={}",
                                stack.getItem(), stack.getCount(), paperStand.block()));
                if (!body.motor().run(this, pullAction))
                    return abortPullAttempt("paper_pull_refused");
            }
            return Step.ACTIVE;
        }
        if (phase == Phase.PLACE_PAPER) {
            if (placeAction != null && body.motor().current() == placeAction) return Step.ACTIVE;
            boolean ok = placeAction != null && placeAction.placed;
            placeAction = null;
            if (!ok) {
                remember(gap, BLOCKED_REST, "paper_place_refused");
                leave("paper_place_refused");
                return Step.ENDED;
            }
            aim = Vec3.atBottomCenterOf(gap);
            settle();
            return Step.RESTING;
        }
        if (phase == Phase.DRAPE_CHILD) {
            if (familyDutyPreempts(body.tickCount % 20 == 0)) {
                leave("family_duty");
                return Step.ENDED;
            }
            if (drapeAction != null) {
                if (body.motor().current() == drapeAction) return Step.ACTIVE;
                boolean ok = drapeAction instanceof DrapeChildAction dc ? dc.draped
                        : drapeAction instanceof LayBesideBabyAction lb && lb.placed;
                if (ok) drapedChildInRest = true;
                else if (drapeChild != null) triedChildDrape.add(drapeChild);
                return returnFromChildDrape();
            }
            YukkuriEntity baby = liveBabyTarget(drapeChild);
            if (baby == null || !holdingPaper()) {
                if (drapeChild != null) triedChildDrape.add(drapeChild);
                return returnFromChildDrape();
            }
            var approach = SurfaceApproach.walkToContact(body, aim, baby, 1);
            boolean reached = approach == SurfaceApproach.Result.ARRIVED && body.onGround()
                    || !drapeLayFallback && liveBabyFlankContact(body, baby, false);
            if (approach == SurfaceApproach.Result.BLOCKED || now() - began > 200) {
                if (!drapeLayFallback) {
                    Vec3 lay = layBesideBabyStand(baby);
                    if (lay != null) {
                        drapeLayFallback = true;
                        aim = lay;
                        began = now();
                        GameplayLog.event(body, "habitat", "baby paper lay fallback approach child={} stand={} gap={}",
                                baby.getUUID(), GameplayLog.at(lay), baby.blockPosition());
                        return Step.ACTIVE;
                    }
                }
                triedChildDrape.add(drapeChild);
                return returnFromChildDrape();
            }
            if (!reached) return Step.ACTIVE;
            SurfaceApproach.halt(body);
            drapeAction = drapeLayFallback
                    ? new LayBesideBabyAction(drapeChild, baby.blockPosition())
                    : new DrapeChildAction(drapeChild);
            if (!body.motor().run(this, drapeAction)) {
                drapeAction = null;
                if (!drapeLayFallback) {
                    Vec3 lay = layBesideBabyStand(baby);
                    if (lay != null) {
                        drapeLayFallback = true;
                        aim = lay;
                        began = now();
                        return Step.ACTIVE;
                    }
                }
                triedChildDrape.add(drapeChild);
                return returnFromChildDrape();
            }
            return Step.ACTIVE;
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
        if (shelter != null) {
            if (!clearRestTurn(body.position(), HeadTop.reach(body), HeadTop.height(body))) {
                remember(target(), BLOCKED_REST, "no_rest_space");
                leave("no_rest_space");
                return Step.ACTIVE;
            }
            if (!shelter.claim(body)) {
                remember(target(), CONTEST_REST, "occupied");
                leave("occupied");
                return Step.ACTIVE;
            }
        } else if (streetSpot == null && holdingPaper() && !needsMouthCoverAtGap() && !holdsPaperForGapDecision()) {
            return beginPlacePaper();
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
        double reach = HeadTop.reach(body);
        double height = HeadTop.height(body);
        var stand = restStand(centre, failed, reach, height);
        if (stand != null) return stand;
        for (var side : Direction.Plane.HORIZONTAL) {
            var d = side.getNormal();
            stand = restStand(centre.add(d.getX() * .40, 0, d.getZ() * .40), failed, reach, height);
            if (stand != null) return stand;
        }
        return null;
    }

    private Vec3 restStand(Vec3 at, Vec3 failed, double reach, double height) {
        var stand = SurfaceApproach.stand(body, at);
        if (stand == null || stand.equals(failed) || !shelter.contains(BlockPos.containing(stand)) || !availableStand(stand)) return null;
        double paddedReach = reach + SurfaceApproach.ARRIVAL;
        return clearRestTurn(stand, paddedReach, height) && shelter.clearsResidents(body, stand, paddedReach) ? stand : null;
    }

    private boolean clearRestTurn(Vec3 feet, double reach, double height) {
        // The body can turn at the actual stand, including an offset or a stand moved clear of a placed hat.
        var swept = new AABB(feet.x - reach, feet.y, feet.z - reach, feet.x + reach, feet.y + height, feet.z + reach);
        return !level().getBlockCollisions(body, swept).iterator().hasNext();
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
                        y -> y.isAlive() && y.profile().dangerToYukkuri() && body.hasLineOfSight(y)).isEmpty() ? "predator"
                : restingInPlainGap() && familyDutyPreempts(body.tickCount % 20 == 0) ? "family_duty"
                : restingInPlainGap() && body.life().temperature().cold() && body.tickCount % 40 == 0
                        && findUsableShelter(here, true) != null ? "better_shelter" : null;
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

    public boolean leaveAfterSleep() {
        if (shelter != null && phase == Phase.REST) {
            leave("rested");
            return true;
        }
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
        nextScan = "better_shelter".equals(reason) ? 0 : now() + 100;
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
        body.life().releasePaperCover("left_cover");
        if (shelter != null && !shelter.isRemoved() && (exiting() || !shelter.contains(body.blockPosition())))
            shelter.release(body.getUUID());
        paperStand = null; pullAction = null; placeAction = null; coverAction = null;
        drapeChild = null; drapeLayFallback = false; drapeAction = null; drapedChildInRest = false; decidedSelfFirst = false; babyWaitTicks = 0;
        triedPaperFaces.clear(); triedGroundPaper.clear(); triedChildDrape.clear();
        shelter = null; gap = null; aim = null; phase = null; streetSpot = null; yieldingRival = null; failures = 0; warnUntil = 0;
        benchLaunch = null; benchHop = null; benchStepBlocked = false;
        body.component(YukkuriComponents.MOUTH).releaseItems(MouthOccupancy.Purpose.PAPER, 20);
        SurfaceApproach.stopWalking(body);
    }

    public String activity() {
        if (pullAction != null && body.motor().current() == pullAction) return Behaviors.PAPER_PULL;
        if (phase == Phase.PLACE_PAPER || placeAction != null && body.motor().current() == placeAction
                || drapeAction instanceof LayBesideBabyAction && body.motor().current() == drapeAction)
            return Behaviors.PAPER_PLACE;
        if (drapeAction instanceof DrapeChildAction && body.motor().current() == drapeAction)
            return Behaviors.PAPER_COVER_CHILD;
        if (coverAction != null && body.motor().current() == coverAction) return Behaviors.PAPER_COVER;
        if (phase == Phase.SNIFF) return Behaviors.HOUSE_SNIFF;
        if (phase == Phase.REST && restingInPlainGap() && body.life().temperature().cold()
                && !(body.family().stage() == FamilyLife.Stage.BABY && !body.life().paperCover().isEmpty()))
            return Behaviors.COLD_SHIVER;
        return phase == Phase.REST || warnUntil > body.level().getGameTime() ? Behaviors.WARM_UP
                : phase == Phase.WAIT ? Behaviors.IDLE : Behaviors.SEEK_WARMTH;
    }

    private BlockPos target() { return shelter == null ? gap : shelter.getBlockPos(); }
    private static Vec3 insideOpening(ShelterBlockEntity home) {
        return Vec3.atBottomCenterOf(home.entrance().relative(home.facing().getOpposite()));
    }

    private SurfaceApproach.Result walk() {
        if (!body.onGround() && !body.isInWaterOrBubble()) return SurfaceApproach.Result.WAITING;
        if (shelter == null || !shelter.contains(body.blockPosition())) return SurfaceApproach.walkResult(body, aim, null, 1);
        // A newly placed or lifted body has not landed yet. Its short step can be checked once its feet are down.
        if (!body.onGround()) return SurfaceApproach.Result.WAITING;
        if (SurfaceApproach.at(body, aim)) {
            SurfaceApproach.halt(body);
            return SurfaceApproach.Result.ARRIVED;
        }
        if (SurfaceApproach.tryStraightStep(body, aim, 1)) return SurfaceApproach.Result.MOVING;
        if (phase == Phase.EXIT) {
            var step = insideOpening(shelter);
            if (!SurfaceApproach.at(body, step) && SurfaceApproach.tryStraightStep(body, step, 1)) return SurfaceApproach.Result.MOVING;
        }
        return SurfaceApproach.walkResult(body, aim, null, 1);
    }
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

    private final class PlacePaperAction implements Action {
        private static final String CLIP = YukkuriAction.MOUTH_ITEM_PLACE.id();
        private final BlockPos targetGap;
        private final ClipMarkers.Clip marks = ClipMarkers.of(CLIP);
        private final int length = Math.max(1, marks.length());
        private final int releaseTick = marks.ticks("release", Math.max(1, length / 2));
        private long startTick;
        private int ticks;
        private boolean placed;

        PlacePaperAction(BlockPos targetGap) { this.targetGap = targetGap; }

        @Override public String id() { return CLIP; }

        private boolean inGapContact(YukkuriEntity actor) {
            return actor.onGround() && !actor.isPassenger() && !actor.isInWaterOrBubble() && !actor.isOnFire()
                    && gapAt(targetGap) && targetGap.equals(actor.blockPosition());
        }

        @Override public Feasibility check(YukkuriEntity actor) {
            if (!inGapContact(actor))
                return Feasibility.of(Reason.NO_SUPPORT, 0, "gap_contact_required");
            if (!holdingPaper())
                return Feasibility.of(Reason.NO_CONTACT, 0, "mouth_empty");
            return Feasibility.pass();
        }

        @Override public void start(YukkuriEntity actor) {
            actor.getNavigation().stop();
            startTick = actor.level().getGameTime();
            ticks = 0;
            placed = false;
        }

        @Override public Progress tick(YukkuriEntity actor) {
            if (!inGapContact(actor))
                return Outcome.of(Reason.NO_SUPPORT, 0, "gap_contact_required");
            Vec3 center = Vec3.atBottomCenterOf(targetGap);
            if (actor.position().subtract(center).horizontalDistanceSqr() > 0.01)
                Motor.face(actor, SurfaceApproach.yaw(actor.position(), center));
            Motor.velocity(actor, new Vec3(0, actor.getDeltaMovement().y, 0), CLIP);
            if (!placed && ticks >= releaseTick) {
                var mouth = actor.component(YukkuriComponents.MOUTH);
                if (mouth.purpose() != MouthOccupancy.Purpose.PAPER || mouth.items(MouthOccupancy.Purpose.PAPER).isEmpty())
                    return Outcome.of(Reason.NO_CONTACT, 0, "mouth_empty");
                Vec3 mouthPos = MouthPlacement.position(actor);
                if (!BlockPos.containing(mouthPos.x, actor.getY(), mouthPos.z).equals(targetGap))
                    return Outcome.of(Reason.NO_CONTACT, 0, "mouth_outside_gap");
                ItemStack one = mouth.stack(MouthOccupancy.Purpose.PAPER).copyWithCount(1);
                var drop = ItemContact.releaseSupported(actor, one, 20);
                if (drop == null) return Outcome.of(Reason.NO_ROOM, 0, "drop_refused");
                mouth.takeOne(MouthOccupancy.Purpose.PAPER);
                placed = true;
                GameplayLog.event(actor, "habitat", "paper bedding placed; drop item={} count=1 at={} drop={}",
                        one.getItem(), targetGap, drop.getUUID());
            }
            if (++ticks >= length) return new Outcome.Done("gap=" + targetGap);
            return new Running(length - ticks, TravelMode.PASS);
        }

        @Override public MotionPose pose() { return new MotionPose(CLIP, startTick, false); }
        @Override public String startDetail() { return "gap=" + targetGap + " clipTicks=" + length; }
    }

    private final class CoverPaperAction implements Action {
        private static final String CLIP = YukkuriAction.PAPER_COVER_UNDER.id();
        private final BlockPos targetGap;
        private final UUID groundSource;
        private final ClipMarkers.Clip marks = ClipMarkers.of(CLIP);
        private final int length = Math.max(1, marks.length());
        private final int drapeTick = marks.ticks("drape", Math.max(1, length / 2));
        private long startTick;
        private int ticks;
        private boolean draped;

        CoverPaperAction(BlockPos targetGap, UUID groundSource) {
            this.targetGap = targetGap;
            this.groundSource = groundSource;
        }

        @Override public String id() { return CLIP; }

        private boolean inGapContact(YukkuriEntity actor) {
            return actor.onGround() && !actor.isPassenger() && !actor.isInWaterOrBubble() && !actor.isOnFire()
                    && gapAt(targetGap) && targetGap.equals(actor.blockPosition()) && canWearPaperCoverNow();
        }

        @Override public Feasibility check(YukkuriEntity actor) {
            if (!inGapContact(actor) || !actor.life().paperCover().isEmpty())
                return Feasibility.of(Reason.NO_CONTACT, 0, "cover_unavailable");
            if (groundSource == null ? !holdingPaper() : groundSheetForCover(targetGap, groundSource) == null)
                return Feasibility.of(Reason.NO_CONTACT, 0, "no_paper");
            return Feasibility.pass();
        }

        @Override public void start(YukkuriEntity actor) {
            actor.getNavigation().stop();
            startTick = actor.level().getGameTime();
            ticks = 0;
            draped = false;
        }

        @Override public Progress tick(YukkuriEntity actor) {
            if (!inGapContact(actor))
                return Outcome.of(Reason.NO_CONTACT, 0, "cover_unavailable");
            Motor.velocity(actor, new Vec3(0, actor.getDeltaMovement().y, 0), CLIP);
            if (!draped && ticks >= drapeTick) {
                var mouth = actor.component(YukkuriComponents.MOUTH);
                if (groundSource == null) {
                    if (!mouth.handOne(MouthOccupancy.Purpose.PAPER, one -> {
                        actor.life().setPaperCover(one);
                        GameplayLog.event(actor, "habitat", "paper cover draped source=mouth item={} count=1 at={}",
                                one.getItem(), targetGap);
                        return true;
                    })) return Outcome.of(Reason.NO_CONTACT, 0, "paper_gone");
                } else {
                    var sheetEntity = groundSheetForCover(targetGap, groundSource);
                    if (sheetEntity == null) return Outcome.of(Reason.NO_CONTACT, 0, "paper_gone");
                    ItemStack ground = sheetEntity.getItem();
                    ItemStack one = ground.split(1);
                    if (ground.isEmpty()) sheetEntity.discard();
                    else sheetEntity.setItem(ground.copy());
                    actor.life().setPaperCover(one);
                    GameplayLog.event(actor, "habitat", "paper cover draped source=ground item={} count=1 at={} from={}",
                            one.getItem(), targetGap, sheetEntity.getUUID());
                }
                draped = true;
            }
            if (++ticks >= length) return new Outcome.Done("gap=" + targetGap);
            return new Running(length - ticks, TravelMode.PASS);
        }

        @Override public MotionPose pose() { return new MotionPose(CLIP, startTick, false); }
        @Override public String startDetail() { return "gap=" + targetGap + " clipTicks=" + length; }
    }

    private final class DrapeChildAction implements Action {
        private static final String CLIP = YukkuriAction.PAPER_COVER_CHILD.id();
        private final UUID childId;
        private final ClipMarkers.Clip marks = ClipMarkers.of(CLIP);
        private final int length = Math.max(1, marks.length());
        private final int drapeTick = marks.ticks("drape", Math.max(1, length / 2));
        private long startTick;
        private int ticks;
        private boolean draped;

        DrapeChildAction(UUID childId) { this.childId = childId; }

        @Override public String id() { return CLIP; }

        @Override public Feasibility check(YukkuriEntity actor) {
            YukkuriEntity baby = liveBabyTarget(childId);
            if (baby == null || !liveBabyFlankContact(actor, baby, false))
                return Feasibility.of(Reason.NO_CONTACT, 0, "child_contact_required");
            if (!holdingPaper())
                return Feasibility.of(Reason.NO_CONTACT, 0, "mouth_empty");
            return Feasibility.pass();
        }

        @Override public void start(YukkuriEntity actor) {
            actor.getNavigation().stop();
            startTick = actor.level().getGameTime();
            ticks = 0;
            draped = false;
        }

        @Override public Progress tick(YukkuriEntity actor) {
            Motor.velocity(actor, new Vec3(0, actor.getDeltaMovement().y, 0), CLIP);
            if (!draped) {
                YukkuriEntity baby = liveBabyTarget(childId);
                if (baby == null) return Outcome.of(Reason.NO_CONTACT, 0, "child_unavailable");
                Motor.face(actor, SurfaceApproach.yaw(actor.position(), baby.position()));
                if (!liveBabyFlankContact(actor, baby, ticks >= drapeTick))
                    return Outcome.of(Reason.NO_CONTACT, 0, "child_contact_lost");
                if (ticks >= drapeTick) {
                    var mouth = actor.component(YukkuriComponents.MOUTH);
                    BlockPos babyGap = baby.blockPosition();
                    if (!mouth.handOne(MouthOccupancy.Purpose.PAPER, one -> {
                        if (!baby.life().acceptPaperCover(one)) return false;
                        GameplayLog.event(actor, "habitat", "paper cover draped on child={} item={} count=1 at={}",
                                baby.getUUID(), one.getItem(), babyGap);
                        return true;
                    })) return Outcome.of(Reason.NO_CONTACT, 0, "drape_rejected");
                    draped = true;
                }
            }
            if (++ticks >= length) return new Outcome.Done("child=" + childId);
            return new Running(length - ticks, TravelMode.PASS);
        }

        @Override public MotionPose pose() { return new MotionPose(CLIP, startTick, false); }
        @Override public String startDetail() { return "child=" + childId + " clipTicks=" + length; }
    }

    private final class LayBesideBabyAction implements Action {
        private static final String CLIP = YukkuriAction.MOUTH_ITEM_PLACE.id();
        private final UUID childId;
        private final BlockPos babyGap;
        private final ClipMarkers.Clip marks = ClipMarkers.of(CLIP);
        private final int length = Math.max(1, marks.length());
        private final int releaseTick = marks.ticks("release", Math.max(1, length / 2));
        private long startTick;
        private int ticks;
        private boolean placed;

        LayBesideBabyAction(UUID childId, BlockPos babyGap) {
            this.childId = childId;
            this.babyGap = babyGap.immutable();
        }

        @Override public String id() { return CLIP; }

        private boolean canReachBabyGap(YukkuriEntity actor) {
            if (!actor.onGround() || actor.isPassenger() || actor.isInWaterOrBubble() || actor.isOnFire()
                    || !plainGapAt(babyGap)) return false;
            Vec3 center = Vec3.atBottomCenterOf(babyGap);
            if (actor.position().subtract(center).horizontalDistance() > 1.35 || Math.abs(actor.getY() - center.y) > 0.35)
                return false;
            return actor.level().clip(new ClipContext(MouthPlacement.position(actor), center.add(0, 0.08, 0),
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, actor)).getType() == HitResult.Type.MISS;
        }

        @Override public Feasibility check(YukkuriEntity actor) {
            if (!canReachBabyGap(actor))
                return Feasibility.of(Reason.NO_CONTACT, 0, "baby_gap_unreachable");
            if (!holdingPaper())
                return Feasibility.of(Reason.NO_CONTACT, 0, "mouth_empty");
            return Feasibility.pass();
        }

        @Override public void start(YukkuriEntity actor) {
            actor.getNavigation().stop();
            startTick = actor.level().getGameTime();
            ticks = 0;
            placed = false;
        }

        @Override public Progress tick(YukkuriEntity actor) {
            Motor.velocity(actor, new Vec3(0, actor.getDeltaMovement().y, 0), CLIP);
            Vec3 center = Vec3.atBottomCenterOf(babyGap);
            if (!placed) {
                if (!canReachBabyGap(actor) || liveBabyTarget(childId) == null)
                    return Outcome.of(Reason.NO_CONTACT, 0, "baby_gap_lost");
                Motor.face(actor, SurfaceApproach.yaw(actor.position(), center));
                if (ticks >= releaseTick) {
                    var mouth = actor.component(YukkuriComponents.MOUTH);
                    Vec3 dropPos = center.lerp(new Vec3(actor.getX(), center.y, actor.getZ()), 0.18).add(0, 0.02, 0);
                    if (!mouth.handOne(MouthOccupancy.Purpose.PAPER, one -> {
                        var drop = ItemContact.hasReleaseSupport(actor, dropPos)
                                ? ItemContact.release(actor, one, dropPos, Vec3.ZERO) : null;
                        if (drop == null) return false;
                        drop.setPickUpDelay(20);
                        GameplayLog.event(actor, "habitat", "paper laid beside baby; drop item={} count=1 child={} at={} drop={}",
                                one.getItem(), childId, babyGap, drop.getUUID());
                        return true;
                    })) return Outcome.of(Reason.NO_ROOM, 0, "drop_refused");
                    placed = true;
                }
            }
            if (++ticks >= length) return new Outcome.Done("babyGap=" + babyGap);
            return new Running(length - ticks, TravelMode.PASS);
        }

        @Override public MotionPose pose() { return new MotionPose(CLIP, startTick, false); }
        @Override public String startDetail() { return "babyGap=" + babyGap + " clipTicks=" + length; }
    }
}
