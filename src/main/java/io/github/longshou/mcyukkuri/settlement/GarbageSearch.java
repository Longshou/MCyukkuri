package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.family.FamilyLife.Stage;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.life.Toxins;
import io.github.longshou.mcyukkuri.life.TravelRange;
import io.github.longshou.mcyukkuri.motor.ClipMarkers;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.status.Identity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;

/** One real serving from a marked barrel. The adjacent trapdoor is its physical lid. */
public final class GarbageSearch {
    public static final String MARKER = "YukkuriGarbage";
    private static final String RAM = "lunge_ram";
    private static final int RADIUS = 8, DAILY_LIMIT = 4;
    private enum Phase { WALK, SNIFF, RAM, NOSE_IN, CARRY, RETREAT }

    private record Point(BarrelBlockEntity barrel, BlockPos stand, BlockPos lid) {}
    private final YukkuriEntity body;
    private final LinkedHashMap<BlockPos, Long> refused = new LinkedHashMap<>();
    private Point point;
    private Vec3 aim;
    private Phase phase;
    private long nextScan, nextTake, deadline;
    private int rams;

    public GarbageSearch(YukkuriEntity body) { this.body = body; }
    private ServerLevel level() { return (ServerLevel)body.level(); }
    private long now() { return level().getGameTime(); }
    public boolean targeting(BlockPos pos) { return point != null && point.barrel().getBlockPos().equals(pos) && phase != Phase.RETREAT && phase != Phase.CARRY; }

    private boolean eligible() {
        var identity = body.status().identity();
        var brood = body.family().brood();
        if (body.life().nutrition() >= 40 || body.family().stage() == Stage.BABY
                || brood != null && !brood.cancelled && !brood.complete()
                || body.profile().dangerToYukkuri() || body.profile().houseBody() || body.isPassenger()
                || body.isInWaterOrBubble() || body.isOnFire() || body.settlement().underway()
                || body.settlement().settlementMember() || SettlementEvents.shelterBarred(body)
                || !body.habitat().mouthFreeForLoad()) return false;
        if (identity == Identity.STRAY) return true;
        var family = FamilyData.get(level());
        return identity == Identity.WILD_FAMILY && !hasKin(family, body.family().partner())
                && !hasKin(family, body.family().mother()) && !hasKin(family, body.family().father())
                && family.children(body.getUUID()).stream().noneMatch(child -> hasKin(family, child))
                && (body.habitat().shelter() == null || !body.habitat().shelter().hasFoodFor(body));
    }

    private static boolean hasKin(FamilyData family, UUID id) {
        if (id == null) return false;
        var person = family.person(id);
        return person == null || person.status != FamilyData.Status.DEAD && person.status != FamilyData.Status.REMOVED;
    }

    public boolean plan() {
        if (now() < nextScan || now() < nextTake || !eligible()) return false;
        nextScan = now() + 80;
        var here = body.blockPosition();
        var options = new ArrayList<Point>();
        for (int z = (here.getZ() - RADIUS) >> 4; z <= (here.getZ() + RADIUS) >> 4; z++)
            for (int x = (here.getX() - RADIUS) >> 4; x <= (here.getX() + RADIUS) >> 4; x++) {
                var chunk = level().getChunkSource().getChunkNow(x, z);
                if (chunk == null) continue;
                for (var candidate : chunk.getBlockEntities().values()) {
                    if (!(candidate instanceof BarrelBlockEntity barrel) || barrel.isRemoved()
                            || !barrel.getPersistentData().getBoolean(MARKER)) continue;
                    var pos = barrel.getBlockPos();
                    if (here.distSqr(pos) > RADIUS * RADIUS || refused.getOrDefault(pos, 0L) > now()) continue;
                    var facing = barrel.getBlockState().getValue(BarrelBlock.FACING);
                    if (!facing.getAxis().isHorizontal()) continue;
                    var stand = pos.relative(facing);
                    var lid = stand.above();
                    if (allowedByFamily(stand) && level().hasChunkAt(lid) && body.life().dryStand(stand)
                            && level().getBlockState(lid).getBlock() instanceof TrapDoorBlock
                            && remaining(barrel) > 0 && serving(barrel) >= 0) options.add(new Point(barrel, stand, lid));
                }
            }
        point = options.stream().sorted(Comparator.comparingDouble(p -> here.distSqr(p.stand()))).limit(4)
                .filter(p -> SurfaceApproach.canApproach(body, Vec3.atBottomCenterOf(p.stand()))).findFirst().orElse(null);
        if (point == null) return false;
        aim = Vec3.atBottomCenterOf(point.stand());
        phase = Phase.WALK; deadline = now() + 280;
        GameplayLog.event(body, "forage", "garbage approach point={}", point.barrel().getBlockPos());
        return true;
    }

    private boolean allowedByFamily(BlockPos target) {
        var data = FamilyData.get(level());
        for (var child : data.children(body.getUUID()))
            if (level().getEntity(child) instanceof YukkuriEntity young && !young.family().adult() && young.life().foundShelter() != null
                    && young.blockPosition().distSqr(target) > TravelRange.NURSE_RADIUS * TravelRange.NURSE_RADIUS) return false;
        return true;
    }

    private long day() { return level().getDayTime() / 24000; }
    private int remaining(BarrelBlockEntity barrel) {
        var data = barrel.getPersistentData();
        return data.getLong("YukkuriGarbageDay") == day() ? DAILY_LIMIT - data.getInt("YukkuriGarbageTaken") : DAILY_LIMIT;
    }

    private int serving(BarrelBlockEntity barrel) {
        for (int slot = 0; slot < barrel.getContainerSize(); slot++) {
            var stack = barrel.getItem(slot);
            if (junk(stack) || edible(stack)) return slot;
        }
        return -1;
    }
    private boolean edible(ItemStack stack) {
        return !stack.isEmpty() && !stack.is(Items.ROTTEN_FLESH) && Toxins.dose(stack) == 0 && body.life().acceptsFood(stack);
    }
    private static boolean junk(ItemStack stack) { return stack.is(Items.PAPER) || stack.is(Items.STICK) || stack.is(Items.FEATHER); }

    public boolean tick() {
        if (point == null) return false;
        if (phase == Phase.CARRY || phase == Phase.RETREAT) {
            if (aim != null && now() < deadline) {
                var move = walk();
                if (move == SurfaceApproach.Result.MOVING || move == SurfaceApproach.Result.WAITING) return true;
            }
            stop(); return false;
        }
        var barrel = point.barrel();
        var pos = barrel.getBlockPos();
        if (!level().hasChunkAt(pos) || level().getBlockEntity(pos) != barrel || serving(barrel) < 0) {
            retreat(Vec3.atCenterOf(pos), "emptied_or_broken"); return true;
        }
        var lid = level().getBlockState(point.lid());
        if (!(lid.getBlock() instanceof TrapDoorBlock)) { retreat(Vec3.atCenterOf(pos), "lid_removed"); return true; }
        boolean open = lid.getValue(BlockStateProperties.OPEN);
        if (phase == Phase.NOSE_IN && !open) { retreat(Vec3.atCenterOf(pos), "lid_closed"); return true; }
        if (body.life().personality().boldness() < 45) {
            var person = level().getEntitiesOfClass(LivingEntity.class, body.getBoundingBox().inflate(3),
                    y -> (y instanceof AbstractVillager || y instanceof Player && !y.isSpectator()) && body.distanceToSqr(y) <= 9
                            && body.hasLineOfSight(y)).stream().findFirst().orElse(null);
            if (person != null) { retreat(person.position(), "person_near"); return true; }
        }
        if (!eligible() || remaining(barrel) <= 0 || !allowedByFamily(point.stand())) { stop(); return false; }
        if (phase == Phase.WALK) {
            var winner = level().getEntitiesOfClass(YukkuriEntity.class, new AABB(point.stand()).inflate(1),
                    y -> y != body && y.life().usingGarbage(pos) && y.distanceToSqr(aim) <= 1
                            && (y.distanceToSqr(aim) < body.distanceToSqr(aim) || y.distanceToSqr(aim) == body.distanceToSqr(aim) && y.getId() < body.getId()));
            if (!winner.isEmpty()) { decline("other_at_mouth"); return false; }
            var move = walk();
            if (now() >= deadline || move == SurfaceApproach.Result.BLOCKED) { decline("no_route"); return false; }
            if (move != SurfaceApproach.Result.ARRIVED) return true;
            SurfaceApproach.halt(body);
            SurfaceApproach.face(body, SurfaceApproach.yaw(body.position(), Vec3.atCenterOf(pos)));
            phase = Phase.SNIFF; deadline = now() + clip(Behaviors.HOUSE_SNIFF);
            GameplayLog.event(body, "forage", "garbage sniff point={} open={}", pos, open);
            return true;
        }
        if (body.distanceToSqr(aim) > .25) { retreat(Vec3.atCenterOf(pos), "lost_contact"); return true; }
        if (now() < deadline) return true;
        if (phase == Phase.NOSE_IN) return take(barrel);
        if (open) {
            phase = Phase.NOSE_IN; deadline = now() + clip(Behaviors.HOUSE_SCAVENGE);
            GameplayLog.event(body, "forage", "garbage nose_in point={}", pos);
            return true;
        }
        if (rams == 3) { decline("closed"); return false; }
        phase = Phase.RAM; deadline = now() + clip(RAM);
        GameplayLog.event(body, "forage", "garbage ram point={} count={}", pos, ++rams);
        return true;
    }

    private boolean take(BarrelBlockEntity barrel) {
        int slot = serving(barrel);
        if (slot < 0) { retreat(Vec3.atCenterOf(barrel.getBlockPos()), "emptied"); return true; }
        var one = barrel.getItem(slot).copyWithCount(1);
        boolean food = edible(one);
        if (!(food ? body.life().beginMeal(one, null, false) : body.habitat().receiveMouthLoad(one))) { decline("mouth_busy"); return false; }
        barrel.removeItem(slot, 1);
        int left = remaining(barrel) - 1;
        var data = barrel.getPersistentData();
        data.putLong("YukkuriGarbageDay", day());
        data.putInt("YukkuriGarbageTaken", DAILY_LIMIT - left);
        barrel.setChanged();
        nextTake = now() + 1200;
        GameplayLog.event(body, "forage", "garbage pickup item={} count=1 source={} holder=mouth daily_left={} food={}",
                one.getItem(), barrel.getBlockPos(), left, food);
        if (barrel.isEmpty()) {
            var lid = level().getBlockState(point.lid());
            level().setBlock(point.lid(), lid.setValue(BlockStateProperties.OPEN, false), 3);
        }
        if (food) { stop(); return false; }
        phase = Phase.CARRY;
        deadline = now() + 100;
        aim = DefaultRandomPos.getPosAway(body, 3, 1, Vec3.atCenterOf(barrel.getBlockPos()));
        return true;
    }

    private void retreat(Vec3 from, String reason) {
        remember(reason);
        body.showReaction("surprise", "", 50);
        phase = Phase.RETREAT; deadline = now() + 100;
        aim = DefaultRandomPos.getPosAway(body, 5, 2, from);
    }
    private void decline(String reason) { remember(reason); stop(); }
    private void remember(String reason) {
        var pos = point.barrel().getBlockPos();
        if (refused.size() >= 16) refused.pollFirstEntry();
        refused.put(pos, now() + 2400);
        GameplayLog.event(body, "forage", "garbage gave_up point={} reason={} rams={} cooldown=2400", pos, reason, rams);
    }
    private static int clip(String action) { return Math.max(1, ClipMarkers.of(action).length()); }
    private SurfaceApproach.Result walk() { return SurfaceApproach.walkResult(body, aim, null, 1); }
    public String activity() {
        return switch (phase) {
            case SNIFF -> Behaviors.HOUSE_SNIFF;
            case RAM -> RAM;
            case NOSE_IN -> Behaviors.HOUSE_SCAVENGE;
            case CARRY -> Behaviors.SHELTER_CARRY;
            case null, default -> Behaviors.SEEK_FOOD;
        };
    }
    public void stop() {
        if (point == null) return;
        if (phase == Phase.CARRY) body.habitat().putDownMouthLoad();
        point = null; aim = null; phase = null; rams = 0;
        SurfaceApproach.stopWalking(body);
    }
}
