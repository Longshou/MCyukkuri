package io.github.longshou.mcyukkuri.aquatic;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.headwear.PlacedHatBehavior;
import io.github.longshou.mcyukkuri.headwear.PlacedHatEntity;
import io.github.longshou.mcyukkuri.motor.Attach;
import io.github.longshou.mcyukkuri.motor.AttachState;
import io.github.longshou.mcyukkuri.motor.Motor;
import io.github.longshou.mcyukkuri.motor.action.Climb;
import io.github.longshou.mcyukkuri.motor.action.Right;
import io.github.longshou.mcyukkuri.motor.surface.HostFrame;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Fixed-waterline travel and authored rocking. Passenger positions and food never change flotation. */
public final class HullAfloat implements PlacedHatBehavior {
    private static final double DRAG = HullPresentation.number("drag");
    private static final double BRIM_LIP = .8, CONTACT_STEP = .25;
    private static final int RIM_POINTS = 24;
    private final PlacedHatEntity hat;
    private final HullLarder larder = new HullLarder();
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
    private boolean paddling;
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
    private static double scale(PlacedHatEntity hat) { return hat.hullScale() / HullPresentation.SCALE; }

    public static void register() {
        PlacedHatBehavior.register(new Provider() {
            @Override public String id() { return "afloat"; }
            @Override public boolean applies(PlacedHatEntity hat, PlacedHatBehavior current) {
                if (!hat.profile().seated()) return false;
                double water = surface(hat.level(), hat.getX(), hat.getY() - 2, hat.getZ());
                double brim = hat.position().y;
                return water >= brim + HullPresentation.KEEL * scale(hat);
            }
            @Override public PlacedHatBehavior create(PlacedHatEntity hat) { return new HullAfloat(hat); }
            @Override public boolean carries(Level level, Vec3 at) { return Waters.calm(level, BlockPos.containing(at)); }
        });
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

    @Override public void waterEntered(PlacedHatEntity hat, double speed) {
        if (speed >= HullPresentation.number("drop_speed")) trigger("impact", speed, false);
    }

    @Override public void tick(PlacedHatEntity hat) {
        double water = water();
        if (!Double.isFinite(water)) return;
        long now = hat.level().getGameTime();
        impact = 0;
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
        hat.inverted(phase == Phase.INVERTED || phase == Phase.FLIPPING);
        float roll = switch (phase) {
            case UPRIGHT -> HullPresentation.roll(now) + HullPresentation.rock(now,
                    shakes * Math.max(0, 1 - (now - shakeTick) / (double) HullTriggers.WINDOW));
            case ROCKING -> HullPresentation.rock(now, HullTriggers.SHAKES + 1);
            case FLIPPING -> HullPresentation.turn(flipFrom, 180, phaseTicks / HullPresentation.number("flip_ticks"));
            case INVERTED -> hat.roll();
        };
        float pitch = phase == Phase.INVERTED ? hat.pitch() : phase == Phase.UPRIGHT ? HullPresentation.pitch(now) : 0;
        float yaw = hat.getYRot() + turnBy;
        if (turnBy != 0 && !open(hat, 0) && walled(hat,
                new HostFrame(hat.position(), hat.unit(), (float) Math.toRadians(180 - yaw), roll, pitch), 0, 0, false, hat.frame()))
            yaw = hat.getYRot();
        Motor.hostPose(hat, yaw, roll, pitch);
        double target = water - HullPresentation.waterHeight(scale(hat), roll, pitch)
                + HullPresentation.bob(now) * scale(hat);
        var v = hat.getDeltaMovement();
        var horizontal = paddling ? new Vec3(v.x * DRAG, 0, v.z * DRAG).add(drive.scale(1 - DRAG))
                : new Vec3(v.x * DRAG, 0, v.z * DRAG);
        var motion = new Vec3(horizontal.x, target - hat.getY(), horizontal.z);
        var free = clear(hat, motion);
        Motor.velocity(hat, free, "hull_drift");
        var actual = Motor.move(hat, free, "hull_drift");
        var blocked = motion.subtract(actual).multiply(1, 0, 1);
        if (blocked.lengthSqr() > 0) impact = Math.max(impact, motion.dot(blocked.normalize()));
        paddling = false; drive = Vec3.ZERO; turnBy = 0;
        double reach = HullPresentation.RADIUS * scale(hat) + 1;
        var nearby = hat.level().getEntities(hat, hat.getBoundingBox().inflate(reach),
                e -> e instanceof LivingEntity || e instanceof Boat || e instanceof PlacedHatEntity);
        contactPositions.keySet().retainAll(nearby);
        for (var other : nearby) contact(hat, other, actual, contactPositions.put(other, other.position()));
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

    private static Vec3 rim(PlacedHatEntity hat, HostFrame frame, int i) {
        var p = hat.profile().rimPoints().get(i);
        return frame.toWorld(new Vec3(p.x(), p.y(), p.z()));
    }

    /** The highest top at x, z of the block collision boxes reaching into heights {@code low}..{@code high}, or NaN. */
    private static double bank(Level level, double x, double low, double high, double z) {
        double best = Double.NaN;
        for (int y = Mth.floor(low); y <= Mth.floor(high); y++) {
            var p = BlockPos.containing(x, y, z);
            var shape = level.getBlockState(p).getCollisionShape(level, p);
            if (shape.isEmpty()) continue;
            for (var box : shape.toAabbs()) {
                double top = p.getY() + box.maxY;
                if (x < p.getX() + box.minX || x > p.getX() + box.maxX || z < p.getZ() + box.minZ || z > p.getZ() + box.maxZ
                        || top <= low || p.getY() + box.minY >= high) continue;
                if (Double.isNaN(best) || top > best) best = top;
            }
        }
        return best;
    }


    private static boolean open(PlacedHatEntity hat, double more) {
        double r = HullPresentation.RADIUS * scale(hat) + more;
        double y = hat.position().y;
        return !hat.level().getBlockCollisions(hat, new AABB(hat.getX() - r, y - hat.unit(), hat.getZ() - r,
                hat.getX() + r, y + hat.unit(), hat.getZ() + r)).iterator().hasNext();
    }

    private static boolean walled(PlacedHatEntity hat, HostFrame frame, double dx, double dz, boolean leading, HostFrame before) {
        double lip = BRIM_LIP * hat.unit();
        for (int i = 0; i < RIM_POINTS; i++) {
            var at = rim(hat, frame, i);
            if (leading && (at.x - hat.getX()) * dx + (at.z - hat.getZ()) * dz <= 0) continue;
            if (!(bank(hat.level(), at.x + dx, at.y - lip, at.y, at.z + dz) > at.y)) continue;
            if (before == null) return true;
            var was = rim(hat, before, i);
            if (!(bank(hat.level(), was.x, was.y - lip, was.y, was.z) > was.y)) return true;
        }
        return false;
    }

    /** The horizontal part of {@code motion} the brim can make: a way into a bank it cannot ride onto stops, the other slides on
     *  (the hull's box itself is vanilla's). */
    private static Vec3 clear(PlacedHatEntity hat, Vec3 motion) {
        double dx = motion.x, dz = motion.z;
        if (dx * dx + dz * dz < 1e-10 || open(hat, Math.hypot(dx, dz))) return motion;
        var frame = hat.frame();
        if (!walled(hat, frame, dx, dz, true, null)) return motion;
        boolean x = !walled(hat, frame, dx, 0, true, null), z = !walled(hat, frame, 0, dz, true, null);
        if (x && z) { if (Math.abs(dx) >= Math.abs(dz)) z = false; else x = false; }
        return new Vec3(x ? dx : 0, motion.y, z ? dz : 0);
    }


    /** A contact separates the hull and carries the approaching normal velocity; there is no mass sharing. */
    private void contact(PlacedHatEntity hat, Entity other, Vec3 moved, Vec3 previous) {
        if (other.isSpectator() || other.noPhysics || other.isPassenger() || other.isPassengerOfSameVehicle(hat)) return;
        if (other instanceof YukkuriEntity y && (Attach.of(y) instanceof AttachState.Airborne
                || y.motor().current() instanceof Climb climb && climb.host() == hat
                || y.motor().current() instanceof Right right && right.host() == hat)) return;
        double brim = hat.position().y;
        var box = other.getBoundingBox();
        double tilt = Math.abs(Math.sin(Math.toRadians(hat.roll()))) + Math.abs(Math.sin(Math.toRadians(hat.pitch())));
        if (box.minY >= brim + HullPresentation.RADIUS * scale(hat) * tilt
                || box.maxY <= brim + HullPresentation.KEEL * scale(hat)) return;
        double dx = other.getX() - hat.getX(), dz = other.getZ() - hat.getZ(), distance = Math.hypot(dx, dz);
        if (distance < 1e-4) { dx = 1; dz = 0; distance = 1; }
        double depth = Math.min(CONTACT_STEP, HullPresentation.RADIUS * scale(hat) + other.getBbWidth() / 2 - distance);
        if (depth <= 0) return;
        double nx = dx / distance, nz = dz / distance;
        Motor.move(hat, clear(hat, new Vec3(-nx * depth, 0, -nz * depth)), "hull_contact");
        if (previous == null) return; // A new neighbour has no observed tick of travel yet.
        var v = hat.getDeltaMovement();
        double approach = moved.subtract(other.position().subtract(previous)).dot(new Vec3(nx, 0, nz));
        impact = Math.max(impact, approach);
        if (approach > 0) Motor.velocity(hat, v.add(-nx * approach, 0, -nz * approach), "hull_contact");
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
        if (phase != Phase.UPRIGHT || rightProgress >= 0) return false;
        drive = water; turnBy = turn; paddling = true;
        return true;
    }
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
        return phase == Phase.UPRIGHT && hat.support(hat.frame().toLocal(rider.position()), rider).isPresent();
    }
    @Override public String detail(PlacedHatEntity hat) {
        return String.format(Locale.ROOT, "afloat waterline=%.3f surface=%.2f phase=%s trigger=%s value=%.3f falling=%s capsizes=%d inverted=%s righting=%.2f %s",
                HullPresentation.WATERLINE * scale(hat), water(), phase, cause, triggerValue, falling, capsizes,
                phase == Phase.INVERTED, rightProgress, larder.detail());
    }
    private static double[] local(PlacedHatEntity hat, double x, double z) {
        double yaw = hat.turn(), c = Math.cos(yaw), s = Math.sin(yaw);
        return new double[]{x * c - z * s, x * s + z * c};
    }
}
