package io.github.longshou.mcyukkuri.aquatic;

import io.github.longshou.mcyukkuri.entity.DomainHooks;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.headwear.HatGeometry;
import io.github.longshou.mcyukkuri.headwear.HatProp;
import io.github.longshou.mcyukkuri.headwear.PlacedHatEntity;
import io.github.longshou.mcyukkuri.motor.BodyAbility;
import io.github.longshou.mcyukkuri.motor.Motor;
import io.github.longshou.mcyukkuri.motor.surface.Occupancy;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * The aquatic life on the water: registers the one aquatic goal and the natural-spawn hook with the
 * base, and holds the reads every aquatic intent shares (her own hat afloat, aboard, her stick, her young and their parent, dusk
 * and rain). Reads only, apart from the spawn seating.
 */
public final class AquaticLife {
    /** How far (blocks) a parent looks for her young and a young for its parent's hat. */
    static final double FAMILY_RANGE = 16;

    private AquaticLife() {}

    /** The registration entry calls this once, after {@link HullAfloat#register}. */
    public static void register() {
        DomainHooks.goal(AquaticGoal.PRIORITY, AquaticGoal::bound);
        DomainHooks.spawn(AquaticLife::spawned);
    }

    /** Her own hat while it is set down (ridden or not), else null. */
    static PlacedHatEntity hat(YukkuriEntity y) { return PlacedHatEntity.find(y); }

    /** Floating upright on the water (its afloat behaviour, not turned over). */
    static boolean afloat(PlacedHatEntity hat) {
        return hat != null && !hat.isRemoved() && hat.behavior() instanceof HullAfloat && !hat.upsideDown();
    }

    static boolean aboard(YukkuriEntity y, PlacedHatEntity hat) { return hat != null && y.getVehicle() == hat; }

    /** On dry footing, not riding anything and not in the water. */
    static boolean ashore(YukkuriEntity y) { return !y.isPassenger() && !y.isInWaterOrBubble() && y.onGround(); }

    /** Her stick: tucked in the worn hat, or kept by the hat she set down. */
    static boolean stick(YukkuriEntity y) {
        var hat = hat(y);
        return y.life().custody().kit().is(Items.STICK) || hat != null && hat.kit().is(Items.STICK);
    }

    /** Keeps one picked-up stick where her hat is: the set-down hat, else the worn one. */
    static boolean stow(YukkuriEntity y, ItemStack one) {
        var hat = hat(y);
        return hat != null ? hat.stow(one) : y.life().custody().stow(one);
    }

    /** The body this one carries in its mouth, or null. */
    static YukkuriEntity carried(YukkuriEntity y) {
        for (var p : y.getPassengers()) if (p instanceof YukkuriEntity held && held.isAlive()) return held;
        return null;
    }

    /** One tick's answer of {@link #young} per parent (weak keys: a removed parent takes its entry with it). */
    private record Young(long tick, List<YukkuriEntity> list) {}
    private static final Map<YukkuriEntity, Young> YOUNG = Collections.synchronizedMap(new WeakHashMap<>());

    /** Her own young (not grown up) within the family range, nearest first; looked up once a tick. */
    static List<YukkuriEntity> young(YukkuriEntity parent) {
        long now = parent.level().getGameTime();
        var kept = YOUNG.get(parent);
        if (kept != null && kept.tick() == now) return kept.list();
        var id = parent.getUUID();
        var out = new ArrayList<>(parent.level().getEntitiesOfClass(YukkuriEntity.class, parent.getBoundingBox().inflate(FAMILY_RANGE),
                y -> y != parent && y.isAlive() && !y.family().adult() && (id.equals(y.family().mother()) || id.equals(y.family().father()))));
        out.sort(Comparator.comparingDouble(parent::distanceToSqr));
        var list = List.copyOf(out);
        YOUNG.put(parent, new Young(now, list));
        return list;
    }

    /** A young that hops by itself (the others are carried). */
    static boolean jumper(YukkuriEntity y) { return BodyAbility.of(y).canJump(); }

    /** The young's parent whose own hat is set down near it, or null. */
    static YukkuriEntity parent(YukkuriEntity young) {
        if (young.family().adult() || !(young.level() instanceof ServerLevel level)) return null;
        for (UUID id : new UUID[]{young.family().mother(), young.family().father()}) {
            if (id != null && level.getEntity(id) instanceof YukkuriEntity p && p.isAlive() && hat(p) != null
                    && p.distanceTo(young) <= FAMILY_RANGE) return p;
        }
        return null;
    }

    /** Its two parents while they are loaded and alive, near or far. */
    static List<YukkuriEntity> parents(YukkuriEntity young) {
        var out = new ArrayList<YukkuriEntity>(2);
        if (!(young.level() instanceof ServerLevel level)) return out;
        for (UUID id : new UUID[]{young.family().mother(), young.family().father()})
            if (id != null && level.getEntity(id) instanceof YukkuriEntity p && p.isAlive()) out.add(p);
        return out;
    }

    /** From dusk to the end of the night (vanilla's sleep window): the hours a hat moors. */
    static boolean dusk(Level level) {
        long t = Math.floorMod(level.getDayTime(), 24000L);
        return t >= 12000 && t < 23460;
    }

    static boolean rain(YukkuriEntity y) { return y.level().isRainingAt(y.blockPosition().above()) || y.level().isThundering(); }

    /** The supported spot (hat units) inside the hollow nearest its middle where this body has room, or null. */
    static Vec3 freeSpot(PlacedHatEntity hat, YukkuriEntity body) { return spot(hat, body, true, Vec3.ZERO); }

    /** The supported spot (hat units) where this body has room nearest {@code near} (hat units), inside the hollow only or anywhere
     *  on the hat (the brim too), where the hull stays upright with it there ({@link HullAfloat#heels}) or that lies nearer the
     *  middle than it stands now; with no such spot (an overloaded hull) the one where it heels least; null when there is none. */
    static Vec3 spot(PlacedHatEntity hat, YukkuriEntity body, boolean inside, Vec3 near) { return spot(hat, body, inside, near, true); }

    /** As {@link #spot(PlacedHatEntity, YukkuriEntity, boolean, Vec3)}; {@code steady} false takes no account of the heel:
     *  leaving toward the bank, or a boarding hop's brim contact before walking inward. Neither promises stability;
     *  actual contact and capsize still follow the hull's motion and load. */
    static Vec3 spot(PlacedHatEntity hat, YukkuriEntity body, boolean inside, Vec3 near, boolean steady) {
        var grid = hat.walkGrid(body, true);
        double foot = Occupancy.foot(body.getBbWidth()), least = Double.POSITIVE_INFINITY;
        var here = body.getVehicle() == hat ? hat.frame().toLocal(body.position()) : null;
        Vec3 best = null, calmest = null;
        var heels = steady ? HullAfloat.heels(hat, body) : null;
        for (int j = 0; j < grid.nz(); j++) for (int i = 0; i < grid.nx(); i++) {
            var local = new Vec3(grid.centreX(i), 0, grid.centreZ(j));
            var height = grid.supported(i, j) ? hat.support(local, body) : null;
            if (height == null || height.isEmpty()) continue;
            var spot = new Vec3(local.x, height.getAsDouble(), local.z);
            if (inside && Occupancy.at(hat.container(), hat.frame().toWorld(spot), body.getBbWidth(), true) != Occupancy.INSIDE_SUPPORTED
                    || !hat.room(body, spot)) continue;
            double heel = steady ? heels.applyAsDouble(spot) : 0;
            if (calmest == null || heel < least) { least = heel; calmest = spot; }
            if (heel > HullAfloat.STEADY && !(here != null && Math.hypot(spot.x, spot.z) < Math.hypot(here.x, here.z))) continue;
            if (best == null || Math.hypot(spot.x - near.x, spot.z - near.z) < Math.hypot(best.x - near.x, best.z - near.z)) best = spot;
        }
        return best != null ? best : calmest;
    }

    /** The hat this body rides while it floats upright (its own, or its parent's), or null. */
    static PlacedHatEntity riding(YukkuriEntity y) { return y.getVehicle() instanceof PlacedHatEntity hat && afloat(hat) ? hat : null; }

    // ---------------------------------------------------------------- natural spawn (P0-1)
    /** Where a calm cell is looked for around a body joining the world (blocks). */
    private static final int SPAWN_REACH = 5;

    /** A natural spawn's cause handed over to the body's own join (weak: a body whose join is cancelled leaves, nothing placed). */
    private static final Map<YukkuriEntity, String> JOINING = Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * P0-1: an aquatic marisa joining the world by nature does so riding her own hat afloat on calm water beside her (a young one
     * aboard its parent's hat); with no such water she stays ashore and says why. A body handed over before it joined the level
     * (a natural spawn from finalizeSpawn) is seated in its own join, after every listener that may refuse it; one that already
     * joined (a generated settlement member) at once. Either way it rides from its first tick (the hat places it on its own first
     * tick, as it places every rider).
     */
    static void spawned(YukkuriEntity body, String cause) {
        if (body.level().isClientSide || !body.profile().aquatic() || body.isPassenger()) return;
        if (cause.equals("chunk_generation")) refuse(body, "chunk_generation", cause);
        else if (body.isAddedToLevel()) place(body, cause);
        else JOINING.put(body, cause);
    }

    /** The lowest-priority join listener (registration entry): a fresh body handed over by {@link #spawned} is seated now. */
    public static void joined(EntityJoinLevelEvent event) {
        if (event.loadedFromDisk() || event.getLevel().isClientSide() || !(event.getEntity() instanceof YukkuriEntity body)) return;
        var cause = JOINING.remove(body);
        if (cause != null) place(body, cause);
    }

    private static void place(YukkuriEntity body, String cause) {
        String refused = seat(body, cause);
        if (refused != null) refuse(body, refused, cause);
    }

    private static void refuse(YukkuriEntity body, String reason, String cause) {
        GameplayLog.event(body, "aquatic", "spawn refused reason={} cause={} at={}", reason, cause, GameplayLog.at(body.position()));
    }

    private static String seat(YukkuriEntity body, String cause) {
        if (!body.family().adult()) {
            var parent = parent(body);
            var hat = parent == null ? null : hat(parent);
            if (!afloat(hat)) return "no_parent_hat";
            var spot = freeSpot(hat, body);
            return spot == null ? "no_room_aboard" : board(body, hat, spot, cause, false);
        }
        if (!HatProp.usable(body) || !body.life().custody().worn()) return "no_hat";
        var water = Waters.edges(body, body.position(), SPAWN_REACH, true).stream().findFirst().orElse(null);
        if (water == null) return "no_water";
        var hat = PlacedHatEntity.setDown(body, Waters.hatAt(water, body), water.yaw() + 90, HatGeometry.Role.SELF);
        if (hat == null) return "set_down_refused";
        var middle = hat.support(Vec3.ZERO, body);
        return middle.isEmpty() ? "no_floor" : board(body, hat, new Vec3(0, middle.getAsDouble(), 0), cause, true);
    }

    private static String board(YukkuriEntity body, PlacedHatEntity hat, Vec3 spot, String cause, boolean own) {
        if (!Motor.mount(body, hat, "spawn")) {
            if (own) { body.life().returnAccessory(body.getUUID(), hat.launchToken(), null); hat.state(PlacedHatEntity.State.RESCUED); }
            return "mount_refused";
        }
        hat.riders().put(body.getUUID(), spot, 0);
        GameplayLog.event(body, "aquatic", "spawn afloat hat={} own={} at={} local={} cause={}", hat.getUUID(), own,
                GameplayLog.at(hat.position()), GameplayLog.at(spot), cause);
        return null;
    }
}
