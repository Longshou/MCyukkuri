package io.github.longshou.mcyukkuri.aquatic;

import com.google.gson.JsonObject;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.headwear.HatGeometry;
import io.github.longshou.mcyukkuri.headwear.PlacedHatBehavior;
import io.github.longshou.mcyukkuri.headwear.PlacedHatEntity;
import io.github.longshou.mcyukkuri.motor.Attach;
import io.github.longshou.mcyukkuri.motor.AttachState;
import io.github.longshou.mcyukkuri.motor.Motor;
import io.github.longshou.mcyukkuri.motor.geom.Buoyancy;
import io.github.longshou.mcyukkuri.motor.geom.Disc;
import io.github.longshou.mcyukkuri.motor.surface.Occupancy;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * A set-down hat on water (refactor WP4a, design 8.2), whoever owns it. It floats at the draft its load sinks it to, drifts
 * with the water's flow, yields to what runs into it by mass ratio and leans toward where its riders sit, springing back.
 * Only real causes (the riders' weight, a player's rocking, shoves and knocks) carry it past the angle where its brim
 * meets the water; then every rider is thrown off along the tilt by the turning hull and it floats crown up, no container.
 * The instance is this hat's motion state while it is afloat (angular rates, the turn over, the last outside cause) and the
 * food in its hollow ({@link HullLarder}), which spills when it turns over.
 */
public final class HullAfloat implements PlacedHatBehavior {
    private static final JsonObject DATA = HatGeometry.data().getAsJsonObject("afloat");
    private static final double HULL_MASS = num("hull_mass"), SPRING = num("spring"), DAMPING = num("damping"),
            MAX_RISE = num("max_rise"), DRAG = num("drag"), TILT_SPRING = num("tilt_spring"), TILT_DAMPING = num("tilt_damping"),
            SHAKE = num("shake"), PUSH_HEEL = num("push_heel"), BUMP_TILT = num("bump_tilt"), CAPSIZE_HARD = num("capsize_hard"),
            FLIP_RATE = num("flip_rate"), UNSTEADY_RATE = num("unsteady_rate");
    private static final int CAPSIZE_TICKS = (int) num("capsize_ticks");
    /** Afloat it stays afloat until the water is this far below its lowest point; one contact moves a body at most this far a
     *  tick; a thrown rider leaves no faster than this (blocks, blocks per tick). */
    private static final double LEAVE_MARGIN = .2, CONTACT_STEP = .25, MAX_THROW = .6;
    /** An outside cause this recent (ticks) is the one a capsize is put down to. */
    private static final int CAUSE_TICKS = 40;
    /** The brim's reach from the centre (hat units) in 24 directions from local +x toward +z; the hull's outer outline. */
    private static final double[] REACH = new double[24];
    private static final double MAX_REACH;
    static {
        double max = 0;
        for (int i = 0; i < REACH.length; i++) {
            double a = i * 2 * Math.PI / REACH.length;
            REACH[i] = HatGeometry.extent(0, 0, Math.cos(a), Math.sin(a), 0);
            max = Math.max(max, REACH[i]);
        }
        MAX_REACH = max;
    }

    private double rollRate, pitchRate, heelRoll, heelPitch, mass, draft;
    private int over, flip;
    private String cause = "load";
    private long causeTick = Long.MIN_VALUE, sampled = Long.MIN_VALUE, dryTick = Long.MIN_VALUE;
    private boolean dry;
    private double surface = Double.NaN;
    private final PlacedHatEntity hat;
    private final HullLarder larder = new HullLarder();

    private HullAfloat(PlacedHatEntity hat) { this.hat = hat; }

    private static double num(String key) { return DATA.get(key).getAsDouble(); }

    /** The registration entry calls this once: a hat whose hull reaches into water runs this behaviour, and keeps it until
     *  the water is clearly below the hull (no flicker at a bank's edge). */
    public static void register() {
        PlacedHatBehavior.register(new Provider() {
            @Override public String id() { return "afloat"; }
            @Override public boolean applies(PlacedHatEntity hat, PlacedHatBehavior current) {
                double low = lowest(hat), water = surface(hat.level(), hat.getX(), low - LEAVE_MARGIN, hat.getZ());
                return water >= low - (current instanceof HullAfloat ? LEAVE_MARGIN : 0);
            }
            @Override public PlacedHatBehavior create(PlacedHatEntity hat) { return new HullAfloat(hat); }
        });
    }

    /** The hull's lowest point: its bottom upright, its brim crown up. */
    private static double lowest(PlacedHatEntity hat) {
        return hat.getY() + Math.min(0, HatGeometry.top() * hat.unit() * Buoyancy.up(hat.roll(), hat.pitch()));
    }

    /** The top of the first water in the column at x, z from height {@code from} up (a few blocks), or NaN when there is none. */
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

    private double surface(PlacedHatEntity hat) {
        long now = hat.level().getGameTime();
        if (sampled != now) { sampled = now; surface = surface(hat.level(), hat.getX(), lowest(hat) - LEAVE_MARGIN, hat.getZ()); }
        return surface;
    }

    // ---------------------------------------------------------------- the tick
    @Override public void tick(PlacedHatEntity hat) {
        double water = surface(hat), unit = hat.unit(), top = HatGeometry.top() * unit;
        if (!Double.isFinite(water)) return;
        var plane = plane(hat);
        double moX = 0, moZ = 0;
        mass = HULL_MASS * hat.hullScale() * hat.hullScale();
        for (var p : hat.getPassengers()) if (p instanceof YukkuriEntity y) {
            double m = mass(y);
            var local = hat.riders().local(y.getUUID());
            mass += m; moX += m * local.x * unit; moZ += m * local.z * unit;
        }
        for (var p : larder.portions()) { mass += HullLarder.MASS; moX += HullLarder.MASS * p.x() * unit; moZ += HullLarder.MASS * p.z() * unit; }
        larder.sync(hat);
        draft = Buoyancy.draft(mass, plane);
        double freeboard = top - draft, roll = hat.roll(), pitch = hat.pitch();
        boolean down = hat.upsideDown();
        // tilt: a damped spring back to the riders' heel (plus this tick's shoves), flat crown up once over
        double restRoll = flip == 1 || flip == -1 ? 180 * flip : Buoyancy.rest(roll, down ? 0 : Buoyancy.rollHeel(moX, plane) + heelRoll);
        double restPitch = flip == 2 || flip == -2 ? 90 * flip : Buoyancy.rest(pitch, down ? 0 : Buoyancy.pitchHeel(moZ, plane) + heelPitch);
        heelRoll = heelPitch = 0;
        rollRate = Buoyancy.spring(Buoyancy.wrap(restRoll - roll), rollRate, TILT_SPRING, TILT_DAMPING);
        pitchRate = Buoyancy.spring(Buoyancy.wrap(restPitch - pitch), pitchRate, TILT_SPRING, TILT_DAMPING);
        if (flip != 0) { rollRate = flip % 2 != 0 ? flip * FLIP_RATE : rollRate; pitchRate = flip % 2 == 0 ? flip / 2 * FLIP_RATE : pitchRate; }
        roll = Buoyancy.wrap(roll + rollRate);
        pitch = Buoyancy.wrap(pitch + pitchRate);
        if (flip != 0 && Math.abs(flip % 2 != 0 ? roll : pitch) > 90) flip = 0;
        hat.pose((float) roll, (float) pitch);
        double floodRoll = Buoyancy.flooding(freeboard, HatGeometry.brim()[0] * unit);
        double floodPitch = Buoyancy.flooding(freeboard, HatGeometry.brim()[1] * unit);
        double ratio = down || flip != 0 ? 0 : Buoyancy.floodRatio(roll, pitch, floodRoll, floodPitch);
        over = ratio >= 1 ? over + 1 : 0;
        if (ratio >= CAPSIZE_HARD || over >= CAPSIZE_TICKS) capsize(hat, roll, pitch, floodRoll, floodPitch, ratio, freeboard);
        // buoyancy: a spring and damper holds the hull at its draft; vanilla flow pushed it already this tick, drag slows it
        double target = water + Buoyancy.rise(Buoyancy.up(roll, pitch), draft, top);
        var v = hat.getDeltaMovement();
        var motion = new Vec3(v.x * DRAG, Mth.clamp(Buoyancy.spring(target - hat.getY(), v.y, SPRING, DAMPING), -MAX_RISE, MAX_RISE), v.z * DRAG);
        hat.setDeltaMovement(motion);
        var from = hat.position();
        hat.move(MoverType.SELF, motion);
        if (hat.horizontalCollision) bump(hat, motion, hat.position().subtract(from));
        var reach = MAX_REACH * unit + 1;
        for (var other : hat.level().getEntities(hat, hat.getBoundingBox().inflate(reach, top + .5, reach), e -> e instanceof LivingEntity))
            contact(hat, other);
    }

    /** A block it ran into took this much speed: the hull tips its leading side down. */
    private void bump(PlacedHatEntity hat, Vec3 wanted, Vec3 moved) {
        double speed = Math.hypot(wanted.x, wanted.z), lost = speed - Math.hypot(moved.x, moved.z);
        if (lost <= 1e-4) return;
        var dir = local(hat, wanted.x / speed, wanted.z / speed);
        rollRate -= BUMP_TILT * lost * dir[0];
        pitchRate += BUMP_TILT * lost * dir[1];
        cause(hat, "collision");
    }

    // ---------------------------------------------------------------- contact: bodies and the hull never overlap
    /** Separates an entity overlapping the brim outline, once a tick from the hull's own pass (vanilla's push is off): each
     *  side yields by the other's share of the mass. A yukkuri body yields through Motor; anything else the hull may not move
     *  (a player, another mob) and the hull yields alone. */
    private void contact(PlacedHatEntity hat, Entity other) {
        if (other == hat || !(other instanceof LivingEntity) || other.isSpectator() || other.noPhysics || other.isPassenger()
                || other.isPassengerOfSameVehicle(hat)) return;
        if (other instanceof YukkuriEntity y && Attach.of(y) instanceof AttachState.Airborne) return;  // a jump lands by contact
        double unit = hat.unit(), up = HatGeometry.top() * unit * Buoyancy.up(hat.roll(), hat.pitch());
        var box = other.getBoundingBox();
        if (box.minY >= hat.getY() + Math.max(0, up) || box.maxY <= hat.getY() + Math.min(0, up)) return;
        double dx = other.getX() - hat.getX(), dz = other.getZ() - hat.getZ(), distance = Math.hypot(dx, dz);
        if (distance < 1e-4) { dx = 1; dz = 0; distance = 1; }
        var dir = local(hat, dx / distance, dz / distance);
        double depth = Math.min(CONTACT_STEP, reach(Math.atan2(dir[1], dir[0])) * unit + other.getBbWidth() / 2 - distance);
        if (depth <= 0) return;
        double theirs = mass(other), mine = Math.max(mass, HULL_MASS);
        double share = other instanceof YukkuriEntity ? theirs / (theirs + mine) : 1;
        double nx = dx / distance, nz = dz / distance, moved = depth * share;
        var shove = new Vec3(-nx * moved, 0, -nz * moved);
        hat.move(MoverType.SELF, shove);
        var v = hat.getDeltaMovement();
        double along = -(v.x * nx + v.z * nz);
        if (along < moved) hat.setDeltaMovement(v.add(shove.scale((moved - Math.max(0, along)) / moved)));
        if (other instanceof YukkuriEntity body && share < 1)
            Motor.move(body, new Vec3(nx * depth * (1 - share), 0, nz * depth * (1 - share)), "hull_contact");
        // the shove heels it: its far side goes down this tick
        heelRoll += PUSH_HEEL * moved * dir[0];
        heelPitch -= PUSH_HEEL * moved * dir[1];
        cause(hat, "push");
        GameplayLog.limited(hat, "hat", "pushed", 100, "pushed by={} kind={} moved={} share={} mass={}/{} tilt={}/{}",
                other.getUUID(), other.getType().getDescriptionId(), fmt(moved), fmt(share), fmt(theirs), fmt(mine),
                fmt(hat.roll()), fmt(hat.pitch()));
    }

    // ---------------------------------------------------------------- the player rocks it
    /** An empty-hand click presses the near brim down, or swings along a rocking already going: sustained rocking builds up. */
    @Override public boolean shake(PlacedHatEntity hat, Player by) {
        if (hat.level().isClientSide) return true;
        double dx = by.getX() - hat.getX(), dz = by.getZ() - hat.getZ(), distance = Math.hypot(dx, dz);
        var near = distance < 1e-4 ? new double[]{1, 0} : local(hat, dx / distance, dz / distance);
        double downRoll = -SHAKE * near[0], downPitch = SHAKE * near[1];
        double going = rollRate * downRoll + pitchRate * downPitch, sign = going < -1e-3 ? -1 : 1;
        rollRate += sign * downRoll;
        pitchRate += sign * downPitch;
        cause(hat, "shake");
        GameplayLog.limited(hat, "hat", "shake", 20, "shake player={} impulse={} rate={}/{} tilt={}/{} draft={}",
                by.getUUID(), fmt(sign * SHAKE), fmt(rollRate), fmt(pitchRate), fmt(hat.roll()), fmt(hat.pitch()), fmt(draft));
        return true;
    }

    // ---------------------------------------------------------------- turning over
    /** Past the flooding angle for good: it turns over about the axis that went furthest, toward its low side, and every rider
     *  leaves where it stands with the velocity the turning hull gives it there (spec 5.2: thrown, then physics). */
    private void capsize(PlacedHatEntity hat, double roll, double pitch, double floodRoll, double floodPitch, double ratio, double freeboard) {
        boolean byRoll = Math.abs(roll) * Math.max(floodPitch, 1e-6) >= Math.abs(pitch) * Math.max(floodRoll, 1e-6);
        int side = (byRoll ? roll : pitch) < 0 ? -1 : 1;
        flip = byRoll ? side : 2 * side;
        if (byRoll) rollRate = side * FLIP_RATE; else pitchRate = side * FLIP_RATE;
        over = 0;
        String why = freeboard <= 0 ? "overload" : hat.level().getGameTime() - causeTick <= CAUSE_TICKS ? cause : "load";
        var thrown = new ArrayList<String>();
        for (var p : List.copyOf(hat.getPassengers())) if (p instanceof YukkuriEntity y) {
            Motor.dismount(y, y.position(), carried(hat, y.position()), "thrown");
            thrown.add(y.getUUID().toString());
        }
        larder.spill(hat, why, at -> carried(hat, at));
        GameplayLog.event(hat, "hat", "capsized cause={} tilt={}/{} flood={}/{} ratio={} draft={} freeboard={} owner={} thrown={} riders={}",
                why, fmt(roll), fmt(pitch), fmt(floodRoll), fmt(floodPitch), fmt(ratio), fmt(draft), fmt(freeboard),
                hat.ownerYukkuri(), thrown.size(), thrown);
    }

    /** The velocity of a point riding the hull: its own motion plus the turn (Ω × r, rad per tick, about its bottom). */
    private Vec3 carried(PlacedHatEntity hat, Vec3 point) {
        double yaw = hat.turn(), r = Math.toRadians(rollRate), p = Math.toRadians(pitchRate);
        var omega = new Vec3(Math.cos(yaw) * p + Math.sin(yaw) * r, 0, -Math.sin(yaw) * p + Math.cos(yaw) * r);
        var v = hat.getDeltaMovement().add(omega.cross(point.subtract(hat.position())));
        return v.length() > MAX_THROW ? v.normalize().scale(MAX_THROW) : v;
    }

    // ---------------------------------------------------------------- what the base asks
    @Override public boolean pushable(PlacedHatEntity hat) { return true; }
    @Override public boolean store(PlacedHatEntity hat, ItemStack stack, Player by) { return larder.store(hat, stack, by); }
    @Override public List<Disc> obstacles(PlacedHatEntity hat) { return larder.discs(hat); }
    @Override public double load(PlacedHatEntity hat) { return larder.load(); }
    @Override public boolean catches(PlacedHatEntity hat) { return false; }
    /** From the synced pose only, so the client agrees: upright and its tilt barely changing (a turn over moves it fast). */
    @Override public boolean steady(PlacedHatEntity hat) { return !hat.upsideDown() && hat.tiltRate() <= UNSTEADY_RATE; }
    /** Upright and empty (no rider, no food) its owner may take it back from the water in reach; crown up it must be righted
     *  first (WP5). */
    @Override public boolean retrievable(PlacedHatEntity hat, YukkuriEntity owner) {
        return flip == 0 && !hat.upsideDown() && hat.getPassengers().isEmpty() && larder.empty();
    }
    @Override public CompoundTag save() {
        var tag = new CompoundTag();
        tag.putDouble("RollRate", rollRate); tag.putDouble("PitchRate", pitchRate); tag.putInt("Flip", flip); tag.putInt("Over", over);
        tag.putString("Cause", cause);
        if (causeTick != Long.MIN_VALUE) tag.putLong("CauseAt", causeTick);
        tag.put("Portions", larder.save(hat));
        return tag;
    }
    @Override public void load(CompoundTag tag) {
        rollRate = Mth.clamp(tag.getDouble("RollRate"), -FLIP_RATE, FLIP_RATE);
        pitchRate = Mth.clamp(tag.getDouble("PitchRate"), -FLIP_RATE, FLIP_RATE);
        flip = Mth.clamp(tag.getInt("Flip"), -2, 2);
        over = Math.max(0, tag.getInt("Over"));
        cause = tag.contains("Cause") ? tag.getString("Cause") : cause;
        causeTick = tag.contains("CauseAt") ? tag.getLong("CauseAt") : Long.MIN_VALUE;
        larder.load(hat, tag.getList("Portions", Tag.TAG_COMPOUND));
    }
    /** Dry while the whole brim stands above the water and the rider's feet are supported in the hat. */
    @Override public boolean shelters(PlacedHatEntity hat, YukkuriEntity rider) {
        long now = hat.level().getGameTime();
        if (dryTick != now) {
            dryTick = now;
            double water = surface(hat), top = HatGeometry.top();
            var frame = hat.frame();
            dry = Double.isFinite(water);
            for (int i = 0; dry && i < REACH.length; i++) {
                double a = i * 2 * Math.PI / REACH.length;
                dry = frame.toWorld(new Vec3(Math.cos(a) * REACH[i], top, Math.sin(a) * REACH[i])).y > water;
            }
        }
        return dry && hat.support(hat.frame().toLocal(rider.position()), Occupancy.FOOT * rider.getBbWidth()).isPresent();
    }
    @Override public String detail(PlacedHatEntity hat) {
        double top = HatGeometry.top() * hat.unit();
        return String.format(Locale.ROOT, "afloat draft=%.3f freeboard=%.3f load=%.4f surface=%.2f rate=%.2f/%.2f over=%d flip=%d cause=%s portions=%d/%d",
                draft, top - draft, mass, surface(hat), rollRate, pitchRate, over, flip, cause, larder.portions().size(), HullLarder.MAX);
    }

    // ---------------------------------------------------------------- measures
    /** The crown's waterplane: the opening's rectangle at this hat's size. */
    private static Buoyancy.Plane plane(PlacedHatEntity hat) {
        float[] o = HatGeometry.opening();
        return Buoyancy.Plane.rect((o[1] - o[0]) * hat.unit(), (o[3] - o[2]) * hat.unit());
    }
    /** A yukkuri as a round body of the WaterDistress density; anything else as its box of water. */
    private static double mass(Entity e) {
        return e instanceof YukkuriEntity ? Buoyancy.bodyMass(e.getBbWidth(), e.getBbHeight(), Buoyancy.BODY_DENSITY)
                : e.getBbWidth() * e.getBbWidth() * e.getBbHeight();
    }
    /** A world direction in the hull's own x, z (yaw only). */
    private static double[] local(PlacedHatEntity hat, double x, double z) {
        double yaw = hat.turn(), c = Math.cos(yaw), s = Math.sin(yaw);
        return new double[]{x * c - z * s, x * s + z * c};
    }
    /** The brim's reach (hat units) toward a local angle, between the two nearest samples. */
    private static double reach(double angle) {
        double at = (angle < 0 ? angle + 2 * Math.PI : angle) / (2 * Math.PI) * REACH.length;
        int i = (int) Math.floor(at) % REACH.length;
        return Mth.lerp(at - Math.floor(at), REACH[i], REACH[(i + 1) % REACH.length]);
    }
    private void cause(PlacedHatEntity hat, String why) { cause = why; causeTick = hat.level().getGameTime(); }
    private static String fmt(double v) { return String.format(Locale.ROOT, "%.3f", v); }
}
