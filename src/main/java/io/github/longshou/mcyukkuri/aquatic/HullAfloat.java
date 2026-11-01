package io.github.longshou.mcyukkuri.aquatic;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.headwear.PlacedHatBehavior;
import io.github.longshou.mcyukkuri.headwear.PlacedHatEntity;
import io.github.longshou.mcyukkuri.motor.Attach;
import io.github.longshou.mcyukkuri.motor.AttachState;
import io.github.longshou.mcyukkuri.motor.Motor;
import io.github.longshou.mcyukkuri.motor.action.Climb;
import io.github.longshou.mcyukkuri.motor.surface.HostFrame;
import java.util.Locale;
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
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Fixed-waterline travel and authored rocking. Passenger positions and food never change flotation. */
public final class HullAfloat implements PlacedHatBehavior {
    private static final double DRAG = HullPresentation.number("drag");
    private static final double BRIM_LIP = .8, CONTACT_STEP = .25;
    private static final int RIM_POINTS = 24;
    private final PlacedHatEntity hat;
    private final HullLarder larder = new HullLarder();
    private Vec3 drive = Vec3.ZERO;
    private float turnBy;
    private boolean paddling;
    private double rightProgress = -1;

    private HullAfloat(PlacedHatEntity hat) { this.hat = hat; }
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
                double water = surface(hat.level(), hat.getX(), hat.getY() - 2, hat.getZ());
                double brim = hat.frame().toWorld(new Vec3(0, hat.profile().top(), 0)).y;
                return water >= Math.min(hat.getY(), brim + HullPresentation.KEEL * scale(hat)) - .2;
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

    @Override public void tick(PlacedHatEntity hat) {
        double water = water();
        if (!Double.isFinite(water)) return;
        long now = hat.level().getGameTime();
        float roll = rightProgress >= 0 ? (float) (180 * (1 - rightProgress))
                : hat.upsideDown() ? 180 : HullPresentation.roll(now);
        float pitch = hat.upsideDown() || rightProgress >= 0 ? 0 : HullPresentation.pitch(now);
        float yaw = hat.getYRot() + turnBy;
        if (turnBy != 0 && !open(hat, 0) && walled(hat,
                new HostFrame(hat.position(), hat.unit(), (float) Math.toRadians(180 - yaw), roll, pitch), 0, 0, false, hat.frame()))
            yaw = hat.getYRot();
        Motor.hostPose(hat, yaw, roll, pitch);
        double brim = hat.profile().top() * hat.unit() * Math.cos(Math.toRadians(roll)) * Math.cos(Math.toRadians(pitch));
        double target = water - brim - HullPresentation.WATERLINE * scale(hat) + HullPresentation.bob(now) * scale(hat);
        var v = hat.getDeltaMovement();
        var horizontal = paddling ? new Vec3(v.x * DRAG, 0, v.z * DRAG).add(drive.scale(1 - DRAG))
                : new Vec3(v.x * DRAG, 0, v.z * DRAG);
        var motion = new Vec3(horizontal.x, target - hat.getY(), horizontal.z);
        var free = clear(hat, motion);
        Motor.velocity(hat, free, "hull_drift");
        Motor.move(hat, free, "hull_drift");
        paddling = false; drive = Vec3.ZERO; turnBy = 0;
        if (rightProgress >= 1) rightProgress = -1;
        double reach = HullPresentation.RADIUS * scale(hat) + 1;
        for (var other : hat.level().getEntities(hat, hat.getBoundingBox().inflate(reach),
                e -> e instanceof LivingEntity || e instanceof Boat || e instanceof PlacedHatEntity)) contact(hat, other);
    }

    private static Vec3 rim(PlacedHatEntity hat, HostFrame frame, int i) {
        double r = HullPresentation.RADIUS * scale(hat) / hat.unit(), a = i * 2 * Math.PI / RIM_POINTS;
        return frame.toWorld(new Vec3(r * Math.cos(a), hat.profile().top() + HullPresentation.BRIM * scale(hat) / hat.unit(), r * Math.sin(a)));
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
        double y = hat.frame().toWorld(new Vec3(0, hat.profile().top(), 0)).y;
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
    private void contact(PlacedHatEntity hat, Entity other) {
        if (other.isSpectator() || other.noPhysics || other.isPassenger() || other.isPassengerOfSameVehicle(hat)) return;
        if (other instanceof YukkuriEntity y && (Attach.of(y) instanceof AttachState.Airborne
                || y.motor().current() instanceof Climb climb && climb.host() == hat)) return;
        double brim = hat.frame().toWorld(new Vec3(0, hat.profile().top(), 0)).y;
        var box = other.getBoundingBox();
        if (box.minY >= brim + hat.unit() || box.maxY <= brim + HullPresentation.KEEL * scale(hat)) return;
        double dx = other.getX() - hat.getX(), dz = other.getZ() - hat.getZ(), distance = Math.hypot(dx, dz);
        if (distance < 1e-4) { dx = 1; dz = 0; distance = 1; }
        double depth = Math.min(CONTACT_STEP, HullPresentation.RADIUS * scale(hat) + other.getBbWidth() / 2 - distance);
        if (depth <= 0) return;
        double nx = dx / distance, nz = dz / distance;
        Motor.move(hat, clear(hat, new Vec3(-nx * depth, 0, -nz * depth)), "hull_contact");
        var v = hat.getDeltaMovement();
        double approach = v.subtract(other.getDeltaMovement()).dot(new Vec3(nx, 0, nz));
        if (approach > 0) Motor.velocity(hat, v.add(-nx * approach, 0, -nz * approach), "hull_contact");
    }

    @Override public boolean right(PlacedHatEntity hat, YukkuriEntity by, double progress, String cause) {
        if (rightProgress < 0 && !hat.upsideDown()) return false;
        rightProgress = progress;
        return true;
    }
    @Override public boolean thrust(PlacedHatEntity hat, YukkuriEntity by, Vec3 water, float turn) {
        if (hat.upsideDown() || rightProgress >= 0) return false;
        drive = water; turnBy = turn; paddling = true;
        return true;
    }
    @Override public boolean pushable(PlacedHatEntity hat) { return true; }
    @Override public boolean holds(PlacedHatEntity hat, Item item) { return larder.has(one -> one.is(item)); }
    @Override public boolean store(PlacedHatEntity hat, ItemStack stack, Player by) { return larder.store(hat, stack, by); }
    @Override public int stock(PlacedHatEntity hat, ItemStack food, int count) { return larder.stock(hat, food, count); }
    @Override public void release(PlacedHatEntity hat, String cause) { larder.spill(hat, cause, at -> hat.getDeltaMovement()); }
    @Override public boolean catches(PlacedHatEntity hat) { return false; }
    @Override public boolean steady(PlacedHatEntity hat) { return !hat.upsideDown() && rightProgress < 0; }
    @Override public boolean retrievable(PlacedHatEntity hat, YukkuriEntity owner) {
        return steady(hat) && hat.getPassengers().isEmpty();
    }
    @Override public CompoundTag save() {
        var tag = new CompoundTag();
        tag.put("Stores", larder.save());
        return tag;
    }
    @Override public void load(CompoundTag tag) { larder.load(hat, tag.getCompound("Stores")); }
    @Override public boolean shelters(PlacedHatEntity hat, YukkuriEntity rider) {
        return !hat.upsideDown() && hat.support(hat.frame().toLocal(rider.position()), rider).isPresent();
    }
    @Override public String detail(PlacedHatEntity hat) {
        return String.format(Locale.ROOT, "afloat waterline=%.3f surface=%.2f inverted=%s righting=%.2f %s",
                HullPresentation.WATERLINE * scale(hat), water(), hat.upsideDown(), rightProgress, larder.detail());
    }
    private static double[] local(PlacedHatEntity hat, double x, double z) {
        double yaw = hat.turn(), c = Math.cos(yaw), s = Math.sin(yaw);
        return new double[]{x * c - z * s, x * s + z * c};
    }
}
