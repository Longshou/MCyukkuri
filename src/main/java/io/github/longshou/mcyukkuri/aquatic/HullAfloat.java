package io.github.longshou.mcyukkuri.aquatic;

import com.google.gson.JsonObject;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.LiveBirthGeometry;
import io.github.longshou.mcyukkuri.headwear.HatGeometry;
import io.github.longshou.mcyukkuri.headwear.PlacedHatBehavior;
import io.github.longshou.mcyukkuri.headwear.PlacedHatEntity;
import io.github.longshou.mcyukkuri.motor.Attach;
import io.github.longshou.mcyukkuri.motor.AttachState;
import io.github.longshou.mcyukkuri.motor.Motor;
import io.github.longshou.mcyukkuri.motor.action.Climb;
import io.github.longshou.mcyukkuri.motor.action.LoadedContact;
import io.github.longshou.mcyukkuri.motor.geom.Buoyancy;
import io.github.longshou.mcyukkuri.motor.geom.HullHydro;
import io.github.longshou.mcyukkuri.motor.geom.Vec;
import io.github.longshou.mcyukkuri.motor.geom.Effort;
import io.github.longshou.mcyukkuri.motor.geom.Disc;
import io.github.longshou.mcyukkuri.motor.surface.HostFrame;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.ToDoubleFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * A set-down hat on water, whoever owns it. It floats at the draft its load sinks it to, drifts
 * with the water's flow, yields to what runs into it by mass ratio and leans toward where its riders sit, springing back.
 * Only real causes (the riders' weight, a player's rocking, shoves and knocks) carry it past the angle where its brim
 * meets the water; then every rider is thrown off along the tilt by the turning hull and it floats crown up, no container.
 * The instance is this hat's motion state while it is afloat (angular rates, the turn over, the last outside cause) and the
 * food in its hollow ({@link HullLarder}), which spills when it turns over.
 */
public final class HullAfloat implements PlacedHatBehavior {
    private static final JsonObject DATA = HatGeometry.afloat();
    private static final double SPRING = num("spring"), DAMPING = num("damping"),
            MAX_RISE = num("max_rise"), DRAG = num("drag"), TILT_SPRING = num("tilt_spring"), TILT_DAMPING = num("tilt_damping"),
            SHAKE = num("shake"), PUSH_HEEL = num("push_heel"), BUMP_TILT = num("bump_tilt"), CAPSIZE_HARD = num("capsize_hard"),
            FLIP_RATE = num("flip_rate"), UNSTEADY_RATE = num("unsteady_rate");
    private static final int CAPSIZE_TICKS = (int) num("capsize_ticks");
    /** Walking margin: the share of hull depth a planned draft and heel may consume. */
    static final double STEADY = steady();
    /** The authored brim plate's thickness (hat units; marisa.json's brim parts span 10.49..11.31): the brim rides up onto a bank
     *  no higher than this above its underside and lies on it; a higher one is a wall the brim stops at. */
    private static final double BRIM_LIP = .8;
    /** Afloat it stays afloat until the water is this far below its lowest point; one contact moves a body at most this far a
     *  tick; a thrown rider leaves no faster than this (blocks, blocks per tick). */
    private static final double LEAVE_MARGIN = .2, CONTACT_STEP = .25, MAX_THROW = .6;
    /** An outside cause this recent (ticks) is the one a capsize is put down to. */
    private static final int CAUSE_TICKS = 40;
    /** The brim's reach from the centre (hat units) in 24 directions from local +x toward +z; the hull's outer outline. */
    private static final int RIM_POINTS = 24;

    private double rollRate, pitchRate, heelRoll, heelPitch, mass, draft;
    private int over, flip;
    /** A righter's hold: how far back, about which axis (±1 roll, ±2 pitch), last held when, by whom and why; and a rider's
     *  paddle stroke for the next tick, the water velocity it drives and the yaw it turns. */
    private double rightProgress;
    private int rightAxis;
    private long rightTick = Long.MIN_VALUE;
    private YukkuriEntity righter, paddler;
    private String rightCause = "";
    private Vec3 drive = Vec3.ZERO;
    private float turnBy;
    private String cause = "load";
    private long causeTick = Long.MIN_VALUE, sampled = Long.MIN_VALUE, dryTick = Long.MIN_VALUE;
    private boolean dry, wetRiders, beached, moored, pressing;
    private double surface = Double.NaN;
    private final PlacedHatEntity hat;
    private final HullHydro hydro;
    private final HullLarder larder = new HullLarder();

    private HullAfloat(PlacedHatEntity hat) { this.hat = hat; hydro = HullHydro.of(hat.profile()); }

    HullLarder larder() { return larder; }

    /** How far (blocks) this hull's brim reaches from its middle toward a world point, at its yaw: where another hull meets it. */
    static double brimToward(PlacedHatEntity hat, Vec3 at) {
        var d = local(hat, at.x - hat.getX(), at.z - hat.getZ());
        return hat.profile().reach(Math.atan2(d[1], d[0])) * hat.unit();
    }

    private static double num(String key) { return DATA.get(key).getAsDouble(); }

    /** The registration entry calls this once: a hat whose hull reaches into water runs this behaviour, and keeps it until
     *  the water is clearly below the hull (no flicker at a bank's edge). */
    public static void register() {
        PlacedHatBehavior.register(new Provider() {
            @Override public String id() { return "afloat"; }
            @Override public boolean applies(PlacedHatEntity hat, PlacedHatBehavior current) {
                double low = lowest(hat), water = surface(hat.level(), hat.getX(), low - LEAVE_MARGIN, hat.getZ());
                // At the hull's lowest point counts: a hat set down on the water's surface floats at once.
                return water >= low - (current instanceof HullAfloat ? LEAVE_MARGIN : 0);
            }
            @Override public PlacedHatBehavior create(PlacedHatEntity hat) { return new HullAfloat(hat); }
            /** A calm open water surface (Waters.calm): a hat set down there floats. */
            @Override public boolean carries(Level level, Vec3 at) { return Waters.calm(level, BlockPos.containing(at)); }
        });
    }

    /** The lowest point of the posed outer shell, including during righting. */
    private static double lowest(PlacedHatEntity hat) {
        return hat.getY() + HullHydro.of(hat.profile()).bounds(hat.unit(), hat.roll(), hat.pitch()).bottom();
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
        double water = surface(hat), unit = hat.unit(), top = hat.profile().rimY() * unit;
        if (!Double.isFinite(water)) return;
        var load = configuration(null);
        mass = load.mass();
        larder.sync(hat);
        double roll = hat.roll(), pitch = hat.pitch();
        boolean down = hat.upsideDown(), righting = righting(hat);
        if (righting && !down) rightProgress = 1;  // past the quarter turn the held hull falls the rest of the way upright
        // Gravity and the measured centre of buoyancy supply the moment; the old spring only settles a capsized hull.
        var immersed = hydro.sample(unit, roll, pitch, water - hat.getY());
        var moment = immersed.moment(load, roll, pitch);
        rollRate = down ? Buoyancy.spring(Buoyancy.wrap(Buoyancy.rest(roll, 0) - roll), rollRate, TILT_SPRING, TILT_DAMPING)
                : Buoyancy.angular(moment.x(), load.rollInertia(), immersed.plane().rollInertia(), rollRate, TILT_DAMPING);
        pitchRate = down ? Buoyancy.spring(Buoyancy.wrap(Buoyancy.rest(pitch, 0) - pitch), pitchRate, TILT_SPRING, TILT_DAMPING)
                : Buoyancy.angular(moment.z(), load.pitchInertia(), immersed.plane().pitchInertia(), pitchRate, TILT_DAMPING);
        rollRate += heelRoll * TILT_SPRING; pitchRate += heelPitch * TILT_SPRING;
        heelRoll = heelPitch = 0;
        // held by a righter: the hull turns back as far as it has pushed, no faster than a capsize turns it over
        if (righting) {
            double held = Math.signum(rightAxis) * 180 * (1 - rightProgress);
            if (rightAxis % 2 != 0) rollRate = Mth.clamp(Buoyancy.wrap(held - roll), -FLIP_RATE, FLIP_RATE);
            else pitchRate = Mth.clamp(Buoyancy.wrap(held - pitch), -FLIP_RATE, FLIP_RATE);
        }
        if (flip != 0) { rollRate = flip % 2 != 0 ? flip * FLIP_RATE : rollRate; pitchRate = flip % 2 == 0 ? flip / 2 * FLIP_RATE : pitchRate; }
        roll = Buoyancy.wrap(roll + rollRate);
        pitch = Buoyancy.wrap(pitch + pitchRate);
        if (flip != 0 && Math.abs(flip % 2 != 0 ? roll : pitch) > 90) flip = 0;
        hat.pose((float) roll, (float) pitch);
        if (righting && rightProgress >= 1 && Math.abs(roll) < .5 && Math.abs(pitch) < .5) righted(hat);
        // buoyancy: a spring and damper holds the hull at its draft; vanilla flow pushed it already this tick, drag slows it
        var balanced = hydro.balance(unit, mass, roll, pitch);
        draft = balanced.draft();
        double target = water - draft;
        var v = hat.getDeltaMovement();
        v = v.add(stroke(hat));
        var motion = new Vec3(v.x * DRAG, Mth.clamp(Buoyancy.spring(target - hat.getY(), v.y, SPRING, DAMPING), -MAX_RISE, MAX_RISE), v.z * DRAG);
        var free = clear(hat, motion);
        boolean walled = free.x != motion.x || free.z != motion.z;
        hat.setDeltaMovement(free);
        var from = hat.position();
        hat.move(MoverType.SELF, free);
        // what vanilla's collision took is a knock; the brim meeting a bank wall knocks once, not every tick it lies pressed on it
        if (hat.horizontalCollision) bump(hat, free, hat.position().subtract(from));
        if (walled && !pressing) bump(hat, motion, free);
        pressing = walled;
        rest(hat);
        var actual = hydro.sample(unit, roll, pitch, water - hat.getY());
        double ratio = hat.upsideDown() || flip != 0 || righting ? 0 : actual.ratio();
        over = ratio >= 1 ? over + 1 : 0;
        if (ratio >= CAPSIZE_HARD || over >= CAPSIZE_TICKS) capsize(hat, roll, pitch, ratio, actual.clearance());
        var reach = Math.hypot(hat.profile().brim()[0], hat.profile().brim()[1]) * unit + 1;
        for (var other : hat.level().getEntities(hat, hat.getBoundingBox().inflate(reach, top + .5, reach), e -> e instanceof LivingEntity))
            contact(hat, other);
        boolean against = hat.level().getBlockCollisions(hat, hat.getBoundingBox().inflate(.05, 0, .05)).iterator().hasNext();
        if (against && !moored) GameplayLog.limited(hat, "hat", "moored", 100, "moored at={} speed={} riders={} owner={} beached={}",
                GameplayLog.at(hat.position()), fmt(hat.getDeltaMovement().horizontalDistance()), hat.getPassengers().size(), hat.ownerYukkuri(), beached);
        moored = against;
        shelter(hat);
    }

    /** Actual rider centres, including children held by riders, and the portions where they lie. */
    private Buoyancy.Load configuration(YukkuriEntity moved) {
        double unit = hat.unit();
        var load = hydro.hull(unit);
        for (var p : hat.getPassengers()) if (p != moved)
            load = load.add(tree(p, hat.frame().toWorld(hat.riders().local(p.getUUID())).subtract(p.position())));
        for (var p : larder.portions()) load = load.add(HullLarder.MASS, new Vec(p.x() * unit, p.y() * unit + HullLarder.HEIGHT / 2, p.z() * unit));
        double clearance = hat.getY() + hydro.rimHeight(unit, hat.roll(), hat.pitch()) - surface(hat);
        for (var y : hat.level().getEntitiesOfClass(YukkuriEntity.class, hat.getBoundingBox().inflate(2))) {
            if (y.isPassenger() || y == moved || !(y.motor().current() instanceof LoadedContact grip)) continue;
            var contact = grip.loadedContact(y);
            if (contact == null || contact.host() != hat) continue;
            double covered = Buoyancy.covered(contact.covered(), clearance, y.getBbHeight());
            double fraction = Buoyancy.supported((surface(hat) - y.getY()) / y.getBbHeight(), covered);
            var point = hat.frame().toLocal(contact.point()).scale(unit);
            load = load.add(tree(y, Vec3.ZERO).mass() * fraction, new Vec(point.x, point.y, point.z));
        }
        return load;
    }

    private Buoyancy.Load tree(Entity body, Vec3 offset) {
        var at = hat.frame().toLocal(body.position().add(offset).add(0, body.getBbHeight() / 2, 0)).scale(hat.unit());
        var load = Buoyancy.Load.ZERO.add(mass(body), new Vec(at.x, at.y, at.z));
        for (var child : body.getPassengers()) load = load.add(tree(child, offset));
        return load;
    }

    /** One load snapshot and upright solve per scan; each candidate only translates the moving tree's moments.
     * The finite score consumes hull depth, so even an unstable hull has a least-heel position. */
    static ToDoubleFunction<Vec3> heels(PlacedHatEntity hat, YukkuriEntity body) {
        if (!(hat.behavior() instanceof HullAfloat hull)) return point -> 0;
        var base = hull.configuration(body);
        var moving = hull.tree(body, Vec3.ZERO);
        var origin = hat.frame().toLocal(body.position()).scale(hat.unit());
        var stability = hull.hydro.new Stability(hat.unit(), base.mass() + moving.mass());
        return point -> stability.ratio(base.add(moving.shifted(new Vec(point.x * hat.unit() - origin.x,
                point.y * hat.unit() - origin.y, point.z * hat.unit() - origin.z))));
    }

    private static double steady() {
        double zeta = TILT_DAMPING / (2 * Math.sqrt(TILT_SPRING));
        return 1 / (1 + Math.exp(-Math.PI * zeta / Math.sqrt(1 - zeta * zeta)));
    }

    // ---------------------------------------------------------------- the brim against the bank
    /** The brim's top edge, sample {@code i} of the profile rim, for a pose. */
    private static Vec3 rim(PlacedHatEntity hat, HostFrame frame, int i) {
        return hat.rim(frame).get(i);
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

    /** Turned over (the brim lies in the water then), or no block near the brim's ring (blocks out to {@code more} past it):
     *  nothing for the brim to meet. */
    private static boolean open(PlacedHatEntity hat, double more) {
        if (hat.upsideDown()) return true;
        double r = Math.hypot(hat.profile().brim()[0], hat.profile().brim()[1]) * hat.unit() + more, top = hat.profile().rimY() * hat.unit();
        return !hat.level().getBlockCollisions(hat, new AABB(hat.getX() - r, hat.getY() - top, hat.getZ() - r, hat.getX() + r, hat.getY() + 2 * top, hat.getZ() + r))
                .iterator().hasNext();
    }

    /** A brim point of {@code frame} moved by {@code dx, dz} would end inside a bank reaching above the brim's top (one it cannot
     *  ride onto); {@code leading} counts only the points on the side it moves toward (it may always back out of a bank). */
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

    /** Where the brim overhangs a bank lower than its plate is thick it lies on the bank's top instead of sinking into it (近帽檐搭到
     *  岸上): the hull is held up by the deepest such overlap, the bank bearing that much. */
    private void rest(PlacedHatEntity hat) {
        double lift = 0, lip = BRIM_LIP * hat.unit();
        if (!open(hat, 0)) {
            var frame = hat.frame();
            for (int i = 0; i < RIM_POINTS; i++) {
                var at = rim(hat, frame, i);
                double top = bank(hat.level(), at.x, at.y - lip, at.y, at.z);
                if (top <= at.y + 1e-6) lift = Math.max(lift, top - (at.y - lip));
            }
        }
        boolean was = beached;
        beached = lift > 1e-4;
        if (!beached) return;
        hat.setPos(hat.getX(), hat.getY() + lift, hat.getZ());
        var v = hat.getDeltaMovement();
        if (v.y < 0) hat.setDeltaMovement(v.x, 0, v.z);
        if (!was) GameplayLog.limited(hat, "hat", "beached", 100, "beached cause=brim_on_bank lift={} at={} riders={} owner={}",
                fmt(lift), GameplayLog.at(hat.position()), hat.getPassengers().size(), hat.ownerYukkuri());
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
        // a jump lands by contact; a climber pulls itself over the brim
        if (other instanceof YukkuriEntity y && (Attach.of(y) instanceof AttachState.Airborne
                || y.motor().current() instanceof Climb climb && climb.host() == hat)) return;
        double unit = hat.unit(), up = hat.profile().rimY() * unit * Buoyancy.up(hat.roll(), hat.pitch());
        var box = other.getBoundingBox();
        if (box.minY >= hat.getY() + Math.max(0, up) || box.maxY <= hat.getY() + Math.min(0, up)) return;
        double dx = other.getX() - hat.getX(), dz = other.getZ() - hat.getZ(), distance = Math.hypot(dx, dz);
        if (distance < 1e-4) { dx = 1; dz = 0; distance = 1; }
        var dir = local(hat, dx / distance, dz / distance);
        double depth = Math.min(CONTACT_STEP, hat.profile().reach(Math.atan2(dir[1], dir[0])) * unit + other.getBbWidth() / 2 - distance);
        if (depth <= 0) return;
        double theirs = mass(other), mine = mass;
        double share = other instanceof YukkuriEntity ? theirs / (theirs + mine) : 1;
        double nx = dx / distance, nz = dz / distance, moved = depth * share;
        hat.move(MoverType.SELF, clear(hat, new Vec3(-nx * moved, 0, -nz * moved)));
        // it takes the other's own way into it, by its share: a body that only stands against it (landed on the bank by it)
        // parts from it and leaves it lying there, one that keeps coming pushes it along
        var v = hat.getDeltaMovement();
        var ov = other.getDeltaMovement();
        double along = -(v.x * nx + v.z * nz), toward = -(ov.x * nx + ov.z * nz) * share;
        if (toward > along) hat.setDeltaMovement(v.add(-nx * (toward - along), 0, -nz * (toward - along)));
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
     *  leaves where it stands with the velocity the turning hull gives it there (thrown, then physics). */
    private void capsize(PlacedHatEntity hat, double roll, double pitch, double ratio, double freeboard) {
        boolean byRoll = Math.abs(roll) >= Math.abs(pitch);
        int side = (byRoll ? roll : pitch) < 0 ? -1 : 1;
        flip = byRoll ? side : 2 * side;
        if (byRoll) rollRate = side * FLIP_RATE; else pitchRate = side * FLIP_RATE;
        over = 0;
        String why = hydro.balance(hat.unit(), mass, 0, 0).clearance() <= 0 ? "overload"
                : hat.level().getGameTime() - causeTick <= CAUSE_TICKS ? cause : "load";
        var thrown = new ArrayList<String>();
        for (var p : List.copyOf(hat.getPassengers())) if (p instanceof YukkuriEntity y) {
            Motor.dismount(y, y.position(), carried(hat, y.position()), "thrown");
            thrown.add(y.getUUID().toString());
        }
        larder.spill(hat, why, at -> carried(hat, at));
        hat.dropKit(hat.position().add(0, hat.profile().rimY() * hat.unit(), 0), carried(hat, hat.position()), "capsized");
        GameplayLog.event(hat, "hat", "capsized cause={} tilt={}/{} ratio={} draft={} freeboard={} form={} load={} owner={} thrown={} riders={}",
                why, fmt(roll), fmt(pitch), fmt(ratio), fmt(draft), fmt(freeboard), hat.formId(), fmt(mass),
                hat.ownerYukkuri(), thrown.size(), thrown);
    }

    /** The velocity of a point riding the hull: its own motion plus the turn (Ω × r, rad per tick, about its bottom). */
    private Vec3 carried(PlacedHatEntity hat, Vec3 point) {
        double yaw = hat.turn(), r = Math.toRadians(rollRate), p = Math.toRadians(pitchRate);
        var omega = new Vec3(Math.cos(yaw) * p + Math.sin(yaw) * r, 0, -Math.sin(yaw) * p + Math.cos(yaw) * r);
        var v = hat.getDeltaMovement().add(omega.cross(point.subtract(hat.position())));
        return v.length() > MAX_THROW ? v.normalize().scale(MAX_THROW) : v;
    }

    // ---------------------------------------------------------------- righting and paddling
    /** A righter holds the turned-over hull {@code progress} of the way back, about the axis it lies over on; pushed all the way,
     *  or past the quarter turn, it finishes turning upright on its own ({@link #righted}). */
    @Override public boolean right(PlacedHatEntity hat, YukkuriEntity by, double progress, String cause) {
        boolean held = righting(hat);
        if (flip != 0 || !held && !hat.upsideDown()) return false;
        boolean byRoll = Math.abs(hat.roll()) >= Math.abs(hat.pitch());
        if (!held) rightAxis = (byRoll ? 1 : 2) * ((byRoll ? hat.roll() : hat.pitch()) < 0 ? -1 : 1);
        rightProgress = Mth.clamp(progress, 0, 1);
        rightTick = hat.level().getGameTime();
        righter = by;
        rightCause = cause;
        return true;
    }

    private boolean righting(PlacedHatEntity hat) {
        return rightTick != Long.MIN_VALUE && (rightTick >= hat.level().getGameTime() - 1 || rightProgress >= 1);
    }

    private void righted(PlacedHatEntity hat) {
        rightTick = Long.MIN_VALUE;
        rightProgress = rollRate = pitchRate = 0;
        GameplayLog.event(hat, "hat", "righted cause={} by={} owner={} draft={}", rightCause, righter.getUUID(), hat.ownerYukkuri(), fmt(draft));
    }

    /** A rider's stroke, integrated next tick by its share of the hull's mass against the drag ({@link Effort#drive}). */
    @Override public boolean thrust(PlacedHatEntity hat, YukkuriEntity by, Vec3 water, float turn) {
        if (hat.upsideDown() || flip != 0) return false;
        drive = water;
        turnBy = turn;
        paddler = by;
        return true;
    }

    private Vec3 stroke(PlacedHatEntity hat) {
        if (paddler == null) return Vec3.ZERO;
        // the stroker's share of itself and what it moves: aboard it is part of the hull's mass, from the bank (Beckon) it is not
        double m = mass(paddler), share = Effort.share(m, paddler.getVehicle() == hat ? mass - m : mass), pace = drive.horizontalDistance();
        float yaw = hat.getYRot() + (float) (turnBy * share);
        // the brim does not swing into a bank it cannot ride onto
        if (turnBy != 0 && (open(hat, 0) || !walled(hat, new HostFrame(hat.position(), hat.unit(), (float) Math.toRadians(180 - yaw), hat.roll(), hat.pitch()),
                0, 0, false, hat.frame()))) hat.setYRot(yaw);
        paddler = null;
        return pace < 1e-9 ? Vec3.ZERO : drive.scale(Effort.drive(pace, share, DRAG) / pace);
    }

    @Override public boolean holds(PlacedHatEntity hat, Item item) { return larder.portions().stream().anyMatch(p -> p.item().is(item)); }

    // ---------------------------------------------------------------- what the base asks
    @Override public boolean pushable(PlacedHatEntity hat) { return true; }
    @Override public boolean store(PlacedHatEntity hat, ItemStack stack, Player by) { return larder.store(hat, stack, by); }
    @Override public List<Disc> obstacles(PlacedHatEntity hat) { return larder.discs(hat); }
    @Override public double load(PlacedHatEntity hat) { return larder.load(); }
    /** Taken back, beached or destroyed: the food falls out where it lies, moving with the hull. */
    @Override public void release(PlacedHatEntity hat, String cause) { larder.spill(hat, cause, at -> hat.getDeltaMovement()); }
    @Override public boolean catches(PlacedHatEntity hat) { return false; }
    /** From the synced pose only, so the client agrees: upright and its tilt barely changing (a turn over moves it fast). */
    @Override public boolean steady(PlacedHatEntity hat) { return !hat.upsideDown() && hat.tiltRate() <= UNSTEADY_RATE; }
    /** Upright and without riders its owner may take it back from the water in reach (its food falls out then); crown up it must
     *  be righted first. */
    @Override public boolean retrievable(PlacedHatEntity hat, YukkuriEntity owner) {
        return flip == 0 && !hat.upsideDown() && hat.getPassengers().isEmpty();
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
        shelter(hat);
        return dry && hat.support(hat.frame().toLocal(rider.position()), rider).isPresent();
    }
    private void shelter(PlacedHatEntity hat) {
        long now = hat.level().getGameTime();
        if (dryTick == now) return;
        dryTick = now;
        double water = surface(hat), rim = hat.getY() + hydro.rimHeight(hat.unit(), hat.roll(), hat.pitch());
        dry = !hat.upsideDown() && rim > water;
        boolean wet = !dry && !hat.getPassengers().isEmpty();
        if (wet && !wetRiders) GameplayLog.event(hat, "hat",
                "wet_riders water={} rim={} clearance={} tilt={}/{} draft={} riders={} form={}",
                fmt(water), fmt(rim), fmt(rim-water), fmt(hat.roll()), fmt(hat.pitch()), fmt(draft), hat.getPassengers().size(), hat.formId());
        wetRiders = wet;
    }
    @Override public String detail(PlacedHatEntity hat) {
        double freeboard = hat.getY() + hydro.rimHeight(hat.unit(), hat.roll(), hat.pitch()) - surface(hat);
        return String.format(Locale.ROOT, "afloat draft=%.3f freeboard=%.3f load=%.4f surface=%.2f rate=%.2f/%.2f over=%d flip=%d cause=%s portions=%d",
                draft, freeboard, mass, surface(hat), rollRate, pitchRate, over, flip, cause, larder.portions().size());
    }

    // ---------------------------------------------------------------- measures
    /** A yukkuri as a round body of the WaterDistress density, a mother as her own body and the young she carries (her swell is
     *  her belly stretched round them, not more of her); anything else as its box of water. */
    private static double mass(Entity e) {
        if (!(e instanceof YukkuriEntity y)) return e.getBbWidth() * e.getBbWidth() * e.getBbHeight();
        var brood = y.livePregnancyProgress() > 0 ? y.family().brood() : null;
        if (brood == null) return Buoyancy.bodyMass(y.getBbWidth(), y.getBbHeight(), Buoyancy.BODY_DENSITY);
        var own = LiveBirthGeometry.bodyDimensions(y, y.growthScale(), 0).scale(y.getScale());
        double m = Buoyancy.bodyMass(own.width(), own.height(), Buoyancy.BODY_DENSITY);
        for (var child : brood.children) if (!child.born && !child.eggLost) {
            var d = LiveBirthGeometry.childDimensions(child, y.getScale());
            m += Buoyancy.bodyMass(d.width(), d.height(), Buoyancy.BODY_DENSITY);
        }
        return m;
    }
    /** A world direction in the hull's own x, z (yaw only). */
    private static double[] local(PlacedHatEntity hat, double x, double z) {
        double yaw = hat.turn(), c = Math.cos(yaw), s = Math.sin(yaw);
        return new double[]{x * c - z * s, x * s + z * c};
    }
    private void cause(PlacedHatEntity hat, String why) { cause = why; causeTick = hat.level().getGameTime(); }
    private static String fmt(double v) { return String.format(Locale.ROOT, "%.3f", v); }
}
