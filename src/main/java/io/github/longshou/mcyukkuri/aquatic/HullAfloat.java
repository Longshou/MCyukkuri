package io.github.longshou.mcyukkuri.aquatic;

import io.github.longshou.mcyukkuri.headwear.HullBounds;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.headwear.PlacedHatBehavior;
import io.github.longshou.mcyukkuri.headwear.PlacedHatEntity;
import io.github.longshou.mcyukkuri.motor.Attach;
import io.github.longshou.mcyukkuri.motor.surface.Occupancy;
import io.github.longshou.mcyukkuri.motor.AttachState;
import io.github.longshou.mcyukkuri.motor.Motor;
import io.github.longshou.mcyukkuri.mind.EmotionEvent;
import io.github.longshou.mcyukkuri.motor.action.Climb;
import io.github.longshou.mcyukkuri.motor.action.Right;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.status.StatusEvents;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;

/** Authored waterline travel and rocking. Passenger positions and food never change flotation; shared rider load warns and capsizes. */
public final class HullAfloat implements PlacedHatBehavior {
    private static final double DRAG = HullPresentation.number("drag"), CONTACT_STEP = .25;
    private final PlacedHatEntity hat;
    private final HullLarder larder = new HullLarder();
    /** Observed play events only; no decisions read these counters and they are not saved. */
    private final Map<String, Integer> playCounts = new TreeMap<>(Map.of(Behaviors.KID_BRIM_SPLASH, 0,
            Behaviors.KID_MIMIC_PADDLE, 0, Behaviors.KID_POKE_STORES, 0, Behaviors.KID_HIDE_BEHIND, 0, "fall_ins", 0));
    void playEvent(String kind) { playCounts.merge(kind, 1, Integer::sum); }
    boolean brimFall() { return HullPresentation.brimFall(phase.name()); }
    private enum Phase { UPRIGHT, ROCKING, FLIPPING, INVERTED }
    private Phase phase;
    private final HullTriggers triggers = new HullTriggers();
    private int phaseTicks, shakes, capsizes, thrown;
    private long shakeTick;
    private final Map<Entity, Vec3> contactPositions = new HashMap<>();
    private String cause = "none";
    private double triggerValue;
    private boolean falling;
    private float flipFrom;
    private double impact;
    private Vec3 drive = Vec3.ZERO;
    private float turnBy;
    private double rightProgress = -1;
    private YukkuriEntity righter;
    private float rightStartRoll, rightStartPitch;

    private HullAfloat(PlacedHatEntity hat) {
        this.hat = hat;
        phase = hat.upsideDown() ? Phase.INVERTED : Phase.UPRIGHT;
    }
    HullLarder larder() { return larder; }

    static double brimToward(PlacedHatEntity hat, Vec3 at) {
        var d = local(hat, at.x - hat.getX(), at.z - hat.getZ());
        return hat.profile().reach(Math.atan2(d[1], d[0])) * hat.unit();
    }
    private static double scale(PlacedHatEntity hat) { return hat.hullScale() / HullBounds.SCALE; }

    public static void register() {
        PlacedHatBehavior.register(new Provider() {
            @Override public String id() { return "afloat"; }
            @Override public boolean applies(PlacedHatEntity hat, PlacedHatBehavior current) {
                if (!hat.profile().seated()) return false;
                double water = surface(hat.level(), hat.getX(), hat.getY() - 2, hat.getZ());
                double brim = hat.position().y;
                // Water carries it; a hull it already carried keeps this behaviour where the water ends, so that it comes down on the
                // floor and becomes its owner's item instead of becoming a land hat with its crew still aboard.
                if (Double.isFinite(water) && water >= brim + HullBounds.KEEL * scale(hat)) return true;
                return current instanceof HullAfloat;
            }
            @Override public PlacedHatBehavior create(PlacedHatEntity hat) { return new HullAfloat(hat); }
        });
        PlacedHatBehavior.carrier(HullAfloat::floats);
    }

    /** Actual water lookup; no fluid is a legitimate boundary at the shore. */
    public static double surface(Level level, double x, double from, double z) {
        var pos = BlockPos.containing(x, from, z).mutable();
        double top = Double.NaN;
        for (int i = 0; i < 4 && level.hasChunkAt(pos); i++, pos.move(0, 1, 0)) {
            var fluid = level.getFluidState(pos);
            if (fluid.is(FluidTags.WATER)) top = pos.getY() + fluid.getHeight(level, pos);
            else if (!Double.isNaN(top)) break;
        }
        return top;
    }
    private double water() { return surface(hat.level(), hat.getX(), hat.getY() - 2, hat.getZ()); }

    /** How deep (blocks) the water under the hull must be for it to float: shallower, its box rests on the floor. */
    static double draft(double scale) { return (HullBounds.WATERLINE - HullBounds.KEEL) * scale; }

    /** Whether the water under {@code at} has room for a hull of {@code hullScale}: its top at least a draft above the floor
     *  (the lowest flooded block's own collision top, so water lying half over a slab is no deeper than it looks). */
    public static boolean floats(Level level, Vec3 at, float hullScale) {
        double water = surface(level, at.x, at.y - 2, at.z);
        if (!Double.isFinite(water)) return false;
        var bottom = BlockPos.containing(at.x, water - .01, at.z);
        for (var below = bottom.below(); level.getFluidState(below).is(FluidTags.WATER); below = below.below()) bottom = below;
        var floor = level.getBlockState(bottom).getCollisionShape(level, bottom);
        double bed = bottom.getY() + (floor.isEmpty() ? 0 : floor.max(Direction.Axis.Y));
        return water - bed >= draft(hullScale / HullBounds.SCALE);
    }

    /** The water under her has no room for the hull: it rests on the floor and becomes its owner's one accessory item at once,
     *  its riders stepping off into the shallow water and its stores and stick falling out where they lie. */
    private void ground() {
        for (var p : List.copyOf(hat.getPassengers())) if (p instanceof YukkuriEntity y)
            Motor.dismount(y, y.position(), Vec3.ZERO, "grounded");
        larder.spill(hat, "grounded");
        hat.dropKit(hat.position(), Vec3.ZERO, "grounded");
        hat.dropAsItem("grounded");
    }

    @Override public void waterEntered(PlacedHatEntity hat, double speed) {
        if (speed >= HullPresentation.number("drop_speed")) trigger("impact", speed, false);
    }

    @Override public void tick(PlacedHatEntity hat) {
        double water = water();
        // Out of the water in the air (a fall carried it off the column it rode): it falls as any hat lying there does, and the water
        // below or the floor it lands on decides what it is.
        if (!Double.isFinite(water) && !hat.onGround()) { PlacedHatBehavior.LAND.tick(hat); return; }
        if (!floats(hat.level(), hat.position(), hat.hullScale())) { ground(); return; }
        long now = hat.level().getGameTime();
        impact = 0;
        double loadRatio = hat.loadRatio();
        if (loadRatio > 1) trigger("too_heavy", loadRatio, false);
        var sample = turbulence(water);
        if (triggers.water(sample)) trigger("turbulence", sample.angle(), sample.falling());
        if (phase == Phase.ROCKING && phaseTicks++ >= HullPresentation.number("rock_ticks")) {
            capsize();
            phase = Phase.FLIPPING; phaseTicks = 0; flipFrom = hat.roll();
        } else if (phase == Phase.FLIPPING && phaseTicks++ >= HullPresentation.number("flip_ticks")) {
            phase = Phase.INVERTED; phaseTicks = 0;
            Motor.hostPose(hat, hat.getYRot(), 180, 0);
            if (cause.equals("shake") && thrown == 0) hat.dropAsItem("shaken_empty");
        }
        if (hat.isRemoved()) return;
        double warning = phase == Phase.UPRIGHT ? HullPresentation.loadWarning(loadRatio) : 0;
        double sink = phase == Phase.UPRIGHT ? HullPresentation.loadSink(loadRatio) * scale(hat) : 0;
        if (warning > 0) GameplayLog.limited(hat, "hat", "load_warning", 1200,
                "load warning ratio={} strength={} sink={}", loadRatio, warning, sink);
        hat.inverted(phase == Phase.INVERTED || phase == Phase.FLIPPING);
        float roll = switch (phase) {
            case UPRIGHT -> HullPresentation.roll(now) + HullPresentation.rock(now,
                    shakes * Math.max(0, 1 - (now - shakeTick) / (double) HullTriggers.WINDOW))
                    + HullPresentation.loadRock(now, loadRatio);
            case ROCKING -> HullPresentation.rock(now, HullTriggers.SHAKES + 1);
            case FLIPPING -> HullPresentation.turn(flipFrom, 180, phaseTicks / HullPresentation.number("flip_ticks"));
            case INVERTED -> hat.roll();
        };
        float pitch = phase == Phase.INVERTED ? hat.pitch() : phase == Phase.UPRIGHT ? HullPresentation.pitch(now) : 0;
        Motor.hostPose(hat, hat.getYRot() + turnBy, roll, pitch);
        double target = water - HullPresentation.waterHeight(scale(hat), roll, pitch)
                + HullPresentation.bob(now) * scale(hat) - sink;
        var v = hat.getDeltaMovement();
        var motion = new Vec3(v.x * DRAG + drive.x * (1 - DRAG), target - hat.getY(), v.z * DRAG + drive.z * (1 - DRAG));
        var travel = new Contact(motion, motion);
        double reach = HullBounds.RADIUS * scale(hat) + 1;
        var nearby = hat.level().getEntities(hat, hat.getBoundingBox().inflate(reach),
                e -> e instanceof LivingEntity || e instanceof Boat || e instanceof PlacedHatEntity);
        contactPositions.keySet().retainAll(nearby);
        for (var other : nearby) travel = contact(other, travel, contactPositions.put(other, other.position()));
        Motor.velocity(hat, travel.velocity, "hull_drift");
        var actual = Motor.move(hat, travel.move, "hull_drift");
        var blocked = travel.move.subtract(actual).multiply(1, 0, 1);
        if (blocked.lengthSqr() > 0) impact = Math.max(impact, travel.move.dot(blocked.normalize()));
        if (StatusEvents.trace()) GameplayLog.event(hat, "hat",
                "travel at={} delta={} velocity={} drive={} impact={} riders={}", hat.position(), actual,
                hat.getDeltaMovement(), drive, impact, hat.getPassengers().size());
        drive = Vec3.ZERO; turnBy = 0;
        if (triggers.impact(impact)) trigger("impact", impact, false);
    }

    private static boolean falling(FluidState fluid) {
        return fluid.is(FluidTags.WATER) && fluid.hasProperty(FlowingFluid.FALLING) && fluid.getValue(FlowingFluid.FALLING);
    }
    private HullTriggers.Water turbulence(double water) {
        var cell = BlockPos.containing(hat.getX(), water - .01, hat.getZ());
        var flows = new ArrayList<HullTriggers.Flow>();
        for (var neighbour : List.of(cell.north(), cell.south(), cell.east(), cell.west())) {
            var fluid = hat.level().getFluidState(neighbour);
            if (!fluid.is(FluidTags.WATER)) continue;
            var flow = fluid.getFlow(hat.level(), neighbour);
            flows.add(new HullTriggers.Flow(flow.x, flow.z, falling(fluid)));
        }
        return HullTriggers.classify(falling(hat.level().getFluidState(cell)), flows);
    }
    private void trigger(String kind, double value, boolean falling) {
        if (phase != Phase.UPRIGHT || rightProgress >= 0) return;
        cause = kind; triggerValue = value; this.falling = falling;
        phase = Phase.ROCKING; phaseTicks = 0; shakes = 0;
        startle();
    }

    /** Everyone aboard feels the hull go over: a young one is frightened, an adult merely startled, for as long as the
     *  hard rock and the roll take. Nobody is told what the hull does next. */
    private void startle() {
        int ticks = (int) (HullPresentation.number("rock_ticks") + HullPresentation.number("flip_ticks"));
        for (var passenger : hat.getPassengers()) if (passenger instanceof YukkuriEntity y) {
            boolean adult = y.family().adult();
            if (!adult) y.mind().felt(EmotionEvent.FRIGHTENED);
            y.showReaction(adult ? "surprise" : "teary", adult ? Behaviors.BOAT_ROCK_STARTLE : "afraid", ticks);
            GameplayLog.event(y, "aquatic", "rocked hat={} cause={} phase={} adult={} line={} ticks={}",
                    hat.getUUID(), cause, phase, adult, adult ? Behaviors.BOAT_ROCK_STARTLE : "afraid", ticks);
        }
    }
    @Override public boolean shake(PlacedHatEntity hat, Player by) {
        if (hat.level().isClientSide) return true;
        if (righter != null) {
            double progress = rightProgress;
            righter.motor().interrupt("player_shake");
            GameplayLog.event(hat, "hat", "righting interrupted player={} progress={}", by.getUUID(), progress);
        }
        if (phase != Phase.UPRIGHT) return true;
        shakeTick = hat.level().getGameTime();
        shakes = triggers.shake(shakeTick);
        GameplayLog.event(hat, "hat", "shake player={} count={} window={}", by.getUUID(), shakes, HullTriggers.WINDOW);
        if (shakes == HullTriggers.SHAKES) trigger("shake", shakes, false);
        return true;
    }
    private Vec3 thrownVelocity(Vec3 at) {
        double a = Math.atan2(at.z - hat.getZ(), at.x - hat.getX());
        double speed = HullPresentation.number("throw_horizontal");
        return hat.getDeltaMovement().add(Math.cos(a) * speed, HullPresentation.number("throw_vertical"), Math.sin(a) * speed);
    }
    private void capsize() {
        thrown = hat.getPassengers().size();
        for (var p : List.copyOf(hat.getPassengers())) if (p instanceof YukkuriEntity y)
            Motor.dismount(y, y.position(), thrownVelocity(y.position()), "thrown");
        larder.spill(hat, cause);
        hat.dropKit(hat.position(), hat.getDeltaMovement(), "capsized");
        capsizes++;
        GameplayLog.event(hat, "hat",
                "capsized cause={} value={} falling={} shake_window={} turbulent_ticks={} angle_limit={} impact_limit={} drop_limit={} count={} form={} thrown={} owner={}",
                cause, triggerValue, falling, HullTriggers.WINDOW, HullTriggers.WET_TICKS, HullTriggers.ANGLE,
                HullTriggers.IMPACT, HullPresentation.number("drop_speed"), capsizes, hat.formId(), thrown, hat.ownerYukkuri());
    }

    private record Contact(Vec3 move, Vec3 velocity) {}

    /** Resolve entity contact before the single collision-aware move; only the approaching normal is removed. */
    private Contact contact(Entity other, Contact travel, Vec3 previous) {
        if (other.isSpectator() || other.noPhysics || other.isPassenger() || other.isPassengerOfSameVehicle(hat)) return travel;
        if (other instanceof YukkuriEntity y && (Attach.of(y) instanceof AttachState.Airborne
                || y.motor().current() instanceof Climb climb && climb.host() == hat
                || y.motor().current() instanceof Right right && right.host() == hat)) return travel;
        var hull = hat.getBoundingBox();
        var box = other.getBoundingBox();
        if (box.minY >= hull.maxY || box.maxY <= hull.minY) return travel;
        double dx = other.getX() - hat.getX(), dz = other.getZ() - hat.getZ(), distance = Math.hypot(dx, dz);
        if (distance < 1e-4) { dx = hat.getId() < other.getId() ? 1 : -1; dz = 0; distance = 1; }
        double gap = distance - (hull.getXsize() + Math.max(box.getXsize(), box.getZsize())) / 2;
        var normal = new Vec3(dx / distance, 0, dz / distance);
        var observed = previous == null ? Vec3.ZERO : other.position().subtract(previous).multiply(1, 0, 1);
        double approach = travel.velocity.subtract(observed).dot(normal);
        double normalMove = travel.move.dot(normal);
        // Vanilla boats start their soft push at this margin; observe the same contact instead of letting it hide a slow approach.
        double margin = other instanceof Boat ? .2 : 0;
        if (gap > margin + Math.max(0, normalMove) + 1e-7) return travel;
        double correction = Math.min(CONTACT_STEP, Math.max(0, normalMove - gap)), lost = Math.max(0, approach);
        if (previous != null) impact = Math.max(impact, lost);
        return new Contact(travel.move.subtract(normal.scale(correction)), travel.velocity.subtract(normal.scale(lost)));
    }

    @Override public boolean right(PlacedHatEntity hat, YukkuriEntity by, double progress, String cause) {
        if (phase == Phase.FLIPPING) { phase = Phase.INVERTED; phaseTicks = 0; }
        if (phase != Phase.INVERTED) return false;
        if (righter == null) { righter = by; rightStartRoll = hat.roll(); rightStartPitch = hat.pitch(); }
        if (righter != by) return false;
        rightProgress = progress;
        Motor.hostPose(hat, hat.getYRot(), HullPresentation.turn(rightStartRoll, 0, progress), HullPresentation.turn(rightStartPitch, 0, progress));
        if (progress >= 1) {
            phase = Phase.UPRIGHT; rightProgress = -1; righter = null;
            hat.inverted(false);
            GameplayLog.event(hat, "hat", "righted cause={} by={} owner={} count={}", cause, by.getUUID(), hat.ownerYukkuri(), capsizes);
        }
        return true;
    }
    @Override public void stopRight(PlacedHatEntity hat, YukkuriEntity by) {
        if (righter == by) { righter = null; rightProgress = -1; }
    }
    @Override public boolean thrust(PlacedHatEntity hat, YukkuriEntity by, Vec3 water, float turn) {
        if (phase != Phase.UPRIGHT && phase != Phase.INVERTED || rightProgress >= 0) return false;
        drive = water; turnBy = turn;
        return true;
    }
    @Override public boolean afloat(PlacedHatEntity hat) { return true; }
    @Override public boolean pushable(PlacedHatEntity hat) { return true; }
    @Override public boolean holds(PlacedHatEntity hat, Item item) { return larder.has(one -> one.is(item)); }
    @Override public boolean store(PlacedHatEntity hat, ItemStack stack, Player by) { return larder.store(hat, stack, by); }
    @Override public int stock(PlacedHatEntity hat, ItemStack food, int count) { return larder.stock(hat, food, count); }
    @Override public void release(PlacedHatEntity hat, String cause) { contactPositions.clear(); larder.spill(hat, cause); }
    @Override public boolean catches(PlacedHatEntity hat) { return false; }
    @Override public boolean steady(PlacedHatEntity hat) { return phase == Phase.UPRIGHT && rightProgress < 0; }
    @Override public boolean retrievable(PlacedHatEntity hat, YukkuriEntity owner) {
        return steady(hat) && hat.getPassengers().isEmpty();
    }
    @Override public CompoundTag save() {
        var tag = new CompoundTag();
        tag.putString("Phase", phase.name()); tag.putInt("PhaseTicks", phaseTicks); tag.putFloat("FlipFrom", flipFrom);
        tag.putInt("Capsizes", capsizes); tag.putInt("Thrown", thrown); tag.putString("Trigger", cause);
        tag.putDouble("TriggerValue", triggerValue); tag.putBoolean("Falling", falling);
        tag.put("Stores", larder.save());
        return tag;
    }
    @Override public void load(CompoundTag tag) {
        phase = Phase.valueOf(tag.getString("Phase")); phaseTicks = tag.getInt("PhaseTicks"); flipFrom = tag.getFloat("FlipFrom");
        capsizes = tag.getInt("Capsizes"); thrown = tag.getInt("Thrown"); cause = tag.getString("Trigger");
        triggerValue = tag.getDouble("TriggerValue"); falling = tag.getBoolean("Falling");
        larder.load(hat, tag.getCompound("Stores"));
    }
    @Override public boolean shelters(PlacedHatEntity hat, YukkuriEntity rider) {
        // A rising hop is above the dry cloth even after it leaves standing occupancy at the floor.
        var local = hat.frame().toLocal(rider.position());
        return (phase == Phase.UPRIGHT || phase == Phase.ROCKING) && (hat.hasPassenger(rider)
                || hat.profile().inOpening(local.x, local.z)
                && local.y >= hat.profile().surface(local.x, local.z) - Occupancy.MARGIN / hat.unit());
    }
    @Override public String detail(PlacedHatEntity hat) {
        double load = hat.loadRatio();
        return String.format(Locale.ROOT, "afloat load=%.3f warning=%.3f waterline=%.3f surface=%.2f phase=%s trigger=%s value=%.3f falling=%s capsizes=%d inverted=%s righting=%.2f %s play=%s",
                load, HullPresentation.loadWarning(load), HullBounds.WATERLINE * scale(hat), water(), phase, cause, triggerValue, falling, capsizes,
                phase == Phase.INVERTED, rightProgress, larder.detail(), playCounts);
    }
    private static double[] local(PlacedHatEntity hat, double x, double z) {
        double yaw = hat.turn(), c = Math.cos(yaw), s = Math.sin(yaw);
        return new double[]{x * c - z * s, x * s + z * c};
    }
}
