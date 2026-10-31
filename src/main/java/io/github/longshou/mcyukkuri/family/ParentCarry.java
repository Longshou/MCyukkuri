package io.github.longshou.mcyukkuri.family;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.longshou.mcyukkuri.body.MouthPlacement;
import io.github.longshou.mcyukkuri.body.WoundSurface;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import io.github.longshou.mcyukkuri.entity.YukkuriView;
import io.github.longshou.mcyukkuri.entity.component.SyncSlot;
import io.github.longshou.mcyukkuri.headwear.HatGeometry;
import io.github.longshou.mcyukkuri.headwear.PlacedHatEntity;
import io.github.longshou.mcyukkuri.life.CareBlockEntity;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.motor.Attach;
import io.github.longshou.mcyukkuri.motor.AttachState;
import io.github.longshou.mcyukkuri.motor.BodyAbility;
import io.github.longshou.mcyukkuri.motor.BodyDriver;
import io.github.longshou.mcyukkuri.motor.ClipMarkers;
import io.github.longshou.mcyukkuri.motor.ContactGeometry;
import io.github.longshou.mcyukkuri.motor.ContactGeometry.Reach;
import io.github.longshou.mcyukkuri.motor.MotorOwner;
import io.github.longshou.mcyukkuri.motor.Outcome;
import io.github.longshou.mcyukkuri.motor.Reason;
import io.github.longshou.mcyukkuri.motor.action.Grab;
import io.github.longshou.mcyukkuri.motor.action.Release;
import io.github.longshou.mcyukkuri.motor.action.Walk;
import io.github.longshou.mcyukkuri.motor.geom.CarryGeometry;
import io.github.longshou.mcyukkuri.motor.geom.Vec;
import io.github.longshou.mcyukkuri.motor.surface.Occupancy;
import io.github.longshou.mcyukkuri.motor.surface.SurfaceHost;
import io.github.longshou.mcyukkuri.social.Bonds;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * A parent carries a dependent baby over land as a real passenger, in the hold the shared Grab chose for it:
 * a hatted Marisa's braid lifts it onto her brim while the brim has free room, then one more goes in her mouth; Chen holds
 * one on each tail; Reimu holds several in her mouth within a volume budget; a hatless Marisa takes one in her mouth or at
 * her braid tip; everyone else one in the mouth (project design). The walk home is the shared Walk; the put-down is the
 * shared Release at the child's own position for a real reason (a brim or tail child is first lowered to the ground with
 * the braid or tail), never copied, never teleported to a goal. The only record is {@link #HOLDS}: which hold each child is
 * in. Client code reads it with the synced riders through {@link #rawSeat} / {@link #mouthLane} / {@link #brimSeat}; the
 * drawn pose follows the live carrier model in client ParentCarryPresentation.
 */
public final class ParentCarry {
    public enum Purpose { MIGRATE, RETRIEVE, NEST_RIM }
    public enum Step { WORKING, DONE, FAILED }
    /** How one carried child is held. */
    public enum Hold {
        MOUTH, BRIM, TAIL_LEFT, TAIL_RIGHT, BRAID;
        /** What reaches the child for this hold: the braid for the brim and the braid tip, the mouth's rule for the rest. */
        public Reach reach() { return this == BRIM || this == BRAID ? Reach.BRAID : Reach.MOUTH; }
        public boolean tail() { return this == TAIL_LEFT || this == TAIL_RIGHT; }
        public String key() { return name().toLowerCase(Locale.ROOT); }
    }
    /**
     * The one record of how each carried child is held, keyed by child UUID: {@code H} the hold, {@code X}/{@code Z} its spot
     * on the brim (a braid-tip child's set-down beside the carrier), {@code D} the tick its set-down began (with the reason
     * {@code R} and re-lift pause {@code P}), and for a Grab's target still being lifted its entity id {@code I} and low lean
     * {@code L}; a braid-tip child's way out ({@link #braided}) adds {@code T}, {@code Y}, {@code A}, {@code PX}/{@code PY}/{@code PZ} and {@code PH}.
     * A mouth lift shares the start and height keys, with duration {@code U}; a lowering's duration is {@code N}.
     * Synced, and saved with the family.
     */
    public static final SyncSlot<CompoundTag> HOLDS = new SyncSlot<>(EntityDataSerializers.COMPOUND_TAG, CompoundTag::new);
    /** Pause before the same child is picked up again after an interruption; feeding gets longer. */
    static final int HOLD_TICKS = 100, FEED_HOLD_TICKS = 200;
    /** After the carrier has settled at the destination the child is kept at most this long while it inherits the home. */
    static final int ARRIVE_GRACE_TICKS = 600;
    private static final int MAX_CARRY_TICKS = 12000;
    /** A mouth or braid-tip hold takes a child up to this share of the carrier's volume, scaled by strength (project design). */
    static final double HOLD_FRACTION = .20;
    /** Reimu holds several babies in her mouth (project design): their summed volume stays within this fraction of her own, scaled by strength. */
    static final double MOUTH_LOAD_FRACTION = .45;
    static final int MAX_MOUTH_BABIES = 4;
    /** Chen's tails: one baby per tail, each up to this fraction of her volume times strength (project design). */
    static final double TAIL_BABY_FRACTION = .26;
    /** Tail seat: the shelf of the curled tail behind and beside the body (model units, idle pose, full curl). */
    private record Tail(float x, float y, float z) {}
    /** One seat per tail (left, right), model units; matches the curl drawn by ParentCarryPresentation (the client follows the live tail). */
    private static final Map<String, Tail[]> TAILS = Map.of(
            "chen", new Tail[]{new Tail(-3.09f, 6.46f, 8.6f), new Tail(3.09f, 6.46f, 8.6f)},
            "chen_longtail", new Tail[]{new Tail(-2.93f, 6.82f, 9.05f), new Tail(2.93f, 6.82f, 9.05f)},
            "chen_roundtail", new Tail[]{new Tail(-2.95f, 4.99f, 8.78f), new Tail(2.95f, 4.99f, 8.78f)});
    /**
     * Mouth lanes (project design): children hang side by side across the lips in boarding order, each gripped at the
     * top-rear of its head; the closed lips stretch a little for each extra child, and a child whose lane lies past them is
     * held by its inner side lock instead (lower on the head, so it rides a little higher and fans outward). Carrier model units.
     */
    static final double LANE_GAP = .12, MOUTH_HALF = 1.6, LIP_STRETCH = .25, GRIP_SIDE = 5.2, GRIP_DROP = 3.2, LANE_FAN = 12;
    /** The grip on a child's head (child model units): top-rear of the head, the scruff; the braid tip closes there too. */
    public static final double GRIP_Y = 8.2, GRIP_Z = 4.0;
    /** The bite sits this far in front of the lip plane (carrier model units), so the hanging back clears the face; lanes
     *  out at the cheeks come forward a little more (per squared lane offset) to clear the side locks. */
    public static final double LIP_GAP = .8, LANE_FORWARD = .035;
    /** Forward offset of a lane's bite from the lip plane (carrier model units). */
    public static double biteForward(MouthLane lane) { return LIP_GAP + LANE_FORWARD * lane.side() * lane.side(); }
    /** The lowest a hanging child's feet are above the carrier's feet (carrier model units). */
    public static final double MOUTH_FLOOR = .2;
    private record Pause(long until, String reason) {}

    private final YukkuriEntity carrier;
    private final Map<UUID, Pause> pauses = new HashMap<>();
    /** Grip-time diagnostics only, bounded by this carrier's passengers; never saved. */
    private final Map<UUID, String> holdChoices = new HashMap<>();
    private Purpose purpose;
    private long since, arrivedAt;
    private int homeFailures, walkFailures, setTries;
    private UUID lifting, settingDown;
    /** Where the carry's own Walk was last sent; why the retrieval gave up; why the child being set down comes down. */
    private Vec3 walkTo;
    private String giveUp, settingWhy;
    /** The set-down goal is running. */
    private boolean setting;
    /** Refused set-downs (no room, no support) before the child is let go where it is. */
    static final int SET_DOWN_TRIES = 4;
    /** The carry as the motor owner: active while a lift runs, a set-down runs, or the family carry task walks the child home. */
    private final MotorOwner owner = new MotorOwner() {
        @Override public String ownerId() { return "family_carry"; }
        @Override public boolean active() { return lifting != null || setting || purpose != null && carrier.family().task() == FamilyLife.Task.CARRY; }
    };
    /** NEST_RIM: the nest whose rim the child lay on, and the free cushion spot being walked to. */
    private BlockPos rimNest;
    private Vec3 rimSpot;

    ParentCarry(YukkuriEntity carrier) { this.carrier = carrier; }

    // ------------------------------------------------------------------------------------------ the hold record

    private static CompoundTag entry(YukkuriView carrier, UUID child) { return carrier.carryHolds().getCompound(child.toString()); }
    private static Hold holdOf(CompoundTag e) { return e.contains("H") ? Hold.values()[Math.clamp(e.getByte("H"), 0, Hold.values().length - 1)] : Hold.MOUTH; }
    /** How this carrier holds this child (a passenger without a record hangs in the mouth). */
    public static Hold hold(YukkuriView carrier, YukkuriView child) { return holdOf(entry(carrier, child.getUUID())); }
    private static void write(YukkuriEntity carrier, UUID child, CompoundTag e) {
        var all = carrier.carryHolds().copy();
        if (e == null) all.remove(child.toString()); else all.put(child.toString(), e);
        carrier.syncComponentData(HOLDS, all);
    }
    /** A Grab of this carrier starts lifting {@code child} into {@code hold}: its brim spot is chosen now, among the brim's children. */
    public static void plan(YukkuriEntity carrier, YukkuriEntity child, Hold hold, boolean lean) {
        var e = new CompoundTag();
        e.putByte("H", (byte) hold.ordinal());
        e.putInt("I", child.getId());
        if (lean) e.putBoolean("L", true);
        var spot = hold == Hold.BRIM ? freeBrimSpot(carrier, child, brimDiscs(carrier, child)) : null;
        if (spot != null) { e.putFloat("X", spot[0]); e.putFloat("Z", spot[1]); }
        // The host the child is lifted off (or the one beside it then), kept for this carry: its surface holds the child up.
        var on = Attach.of(child) instanceof AttachState.OnHost ride ? ride.host().entity() : null;
        var host = on != null ? on : carrier.level().getEntities(child, child.getBoundingBox().inflate(2, 1, 2), near -> near instanceof SurfaceHost)
                .stream().min(Comparator.comparingDouble(child::distanceToSqr)).orElse(null);
        if (host != null) { e.putInt("S", host.getId()); if (on != null) e.putBoolean("SO", true); }
        write(carrier, child.getUUID(), e);
    }
    /** How far (blocks) under the carry's host surface held feet are still pinned to it when the carry did not begin on it. */
    private static final double PIN = .3;
    /** The lowest a held child's feet may be here: on its carry host's surface under them (Occupancy.surfaceUnder), always for
     *  the host it was lifted off, within {@link #PIN} for the one beside it then, so it goes over a brim, never through it. */
    public static double pinned(YukkuriView carrier, YukkuriView child, Vec3 feet) {
        var e = entry(carrier, child.getUUID());
        if (!e.contains("S") || !(carrier.level().getEntity(e.getInt("S")) instanceof SurfaceHost host)) return feet.y;
        double top = Occupancy.surfaceUnder(host, child.getBbWidth(), feet);
        return top > feet.y && (e.getBoolean("SO") || top - feet.y <= PIN) ? top : feet.y;
    }
    /** The host this carry began on or beside, while it is in the world (the client's lift path reads its surface). */
    public static SurfaceHost carryHost(YukkuriView carrier, YukkuriView child) {
        var e = entry(carrier, child.getUUID());
        return e.contains("S") && carrier.level().getEntity(e.getInt("S")) instanceof SurfaceHost host ? host : null;
    }
    /** The riders in one hold, in boarding order. */
    static List<YukkuriView> riders(YukkuriView carrier, Hold hold) {
        var out = new ArrayList<YukkuriView>();
        for (var rider : carrier.passengerViews()) if (hold(carrier, rider) == hold) out.add(rider);
        return out;
    }
    /** The body this carrier's Grab is lifting into a braid hold (the brim or the braid tip), or null. */
    public static YukkuriView braidTarget(YukkuriView carrier) {
        if (!carrier.motorAction().equals(Grab.ID)) return null;
        var all = carrier.carryHolds();
        for (var key : all.getAllKeys()) {
            var e = all.getCompound(key);
            if (e.contains("I") && holdOf(e).reach() == Reach.BRAID && !carrier.hasPassengerId(UUID.fromString(key))) return carrier.yukkuriView(e.getInt("I"));
        }
        return null;
    }
    /** Whether the braid reaches for this target in its low lean (clip braid_reach_low). */
    public static boolean braidLean(YukkuriView carrier, YukkuriView target) { return entry(carrier, target.getUUID()).getBoolean("L"); }
    /** Mouth carry closes the carrier's mouth on the child. */
    public static boolean mouthCarrying(YukkuriView carrier) { return carrier.isVehicle() && !riders(carrier, Hold.MOUTH).isEmpty(); }
    /** A child sits on the brim or hangs from the braid: the braid cannot carry the hat on or off. */
    public static boolean braidBusy(YukkuriView carrier) {
        if (!carrier.isVehicle()) return false;
        for (var rider : carrier.passengerViews()) { var hold = hold(carrier, rider); if (hold == Hold.BRIM || hold == Hold.BRAID) return true; }
        return false;
    }
    /** A child sits on the brim: the carrier keeps a steady idle or walk, the brim pose the server places it on. */
    public static boolean brimCarrying(YukkuriView carrier) { return carrier.isVehicle() && !riders(carrier, Hold.BRIM).isEmpty(); }
    /** The steady pose a brim carrier is drawn and measured in. */
    public static String carryAction(YukkuriView carrier) { return carrier.getAnimationId().equals("waddle") ? "waddle" : "idle"; }
    /** Ticks a brim or tail child takes to come down to the ground: the braid clip's set-down, its place to release markers. */
    public static int lowerTicks() {
        var clip = ClipMarkers.of(Grab.BRAID_LIFT);
        return Math.max(1, clip.ticks("release", 46) - clip.ticks("place", 38));
    }
    /** The tick a lowered child's set-down began, or -1 when it is not being set down. */
    public static long setDownTick(YukkuriView carrier, YukkuriView child) { var e = entry(carrier, child.getUUID()); return e.contains("D") ? e.getLong("D") : -1; }
    /** How far a lowered child has come down (0 seated .. 1 on the ground), or -1 when it is not being set down. */
    public static float lowering(YukkuriView carrier, YukkuriView child, float partial) {
        var e = entry(carrier, child.getUUID());
        return e.contains("D") ? Math.clamp((carrier.level().getGameTime() - e.getLong("D") + partial) / e.getInt("N"), 0, 1) : -1;
    }
    public static boolean riding(YukkuriEntity carrier, Entity child) { return child instanceof YukkuriEntity && carrier.hasPassenger(child); }
    private static double size(YukkuriView v) { return v.growthScale() * v.getScale(); }
    /** Reimu carries babies in her mouth, several at once; every other mouth carrier holds one. */
    public static boolean multi(YukkuriView carrier) { return carrier.getVariant() == YukkuriVariant.REIMU; }
    private static float ease(float q) { q = Math.clamp(q, 0, 1); return q * q * (3 - 2 * q); }

    // ------------------------------------------------------------------------------------------ hold geometry (both sides)

    private static float yaw(YukkuriView carrier, float partial) {
        return carrier.level().isClientSide ? Mth.rotLerp(partial, carrier.previousBodyYaw(), carrier.bodyYaw()) : carrier.bodyYaw();
    }
    /** A point in the carrier's model units as a world position, the carrier turned to {@code yaw}. */
    private static Vec3 world(YukkuriView carrier, float partial, Vector3f model, float yaw) {
        model.mul((float) size(carrier) / 16).rotateY((180 - yaw) * Mth.DEG_TO_RAD);
        var base = carrier.level().isClientSide ? carrier.getPosition(partial) : carrier.position();
        return base.add(model.x, model.y, model.z);
    }
    /** The same on the server, the point left unchanged. */
    private static Vec3 worldAt(YukkuriEntity carrier, float yaw, Vector3f model) { return world(carrier, 1, new Vector3f(model), yaw); }
    /** A world point in the carrier's model units with it turned to {@code yaw}. */
    private static Vector3f modelAt(YukkuriEntity carrier, float yaw, Vec3 world) {
        var d = world.subtract(carrier.position());
        return new Vector3f((float) d.x, (float) d.y, (float) d.z).rotateY(-(180 - yaw) * Mth.DEG_TO_RAD).mul(16 / (float) size(carrier));
    }

    /** Feet of the carried child before any collision adjustment, by its hold; a child being set down on its way to the ground. */
    public static Vec3 rawSeat(YukkuriView carrier, YukkuriView child, float partial) {
        var hold = hold(carrier, child);
        Vector3f model;
        switch (hold) {
            case BRIM -> {
                var seat = brimSeat(carrier, child);
                model = seat == null ? ground(carrier, child, hold) : hatPose(carrier, partial).transformPosition(new Vector3f(seat[0], seat[1], seat[2]));
            }
            case TAIL_LEFT, TAIL_RIGHT -> model = tail(carrier, hold);
            case BRAID -> {
                // On its way out it is drawn at the yaw the way was recorded with, the same on server and client.
                var e = entry(carrier, child.getUUID());
                var way = wayPoint(carrier, e, partial);
                if (way != null) return pinnedAt(carrier, child, world(carrier, partial, way, e.getFloat("Y")));
                model = setDown(e);
            }
            default -> model = mouthSeat(carrier, child);
        }
        if (hold == Hold.MOUTH && mouthLiftProgress(carrier, child, partial) < 1) model = mouthPoint(entry(carrier, child.getUUID()), model,
                carrier.level().getGameTime() + partial);
        float down = lowering(carrier, child, partial);
        if (down >= 0) model.lerp(ground(carrier, child, hold), ease(down));
        return pinnedAt(carrier, child, world(carrier, partial, model, yaw(carrier, partial)));
    }
    private static Vec3 pinnedAt(YukkuriView carrier, YukkuriView child, Vec3 at) {
        return new Vec3(at.x, pinned(carrier, child, at), at.z);
    }
    private static Vector3f mouthSeat(YukkuriView carrier, YukkuriView child) {
        var lane = mouthLane(carrier, child);
        var mouth = MouthPlacement.anchor(carrier);
        double rho = size(child) / size(carrier);
        return new Vector3f((float) lane.side(), (float) Math.max(MOUTH_FLOOR, mouth.y - lane.gripY() * rho),
                (float) (mouth.z + .35 - biteForward(lane) - lane.gripZ() * rho));
    }

    /** Shared passenger/render path, timed by path length and the carrier's ability. */
    public static float mouthLiftProgress(YukkuriView carrier, YukkuriView child, float partial) {
        var e = entry(carrier, child.getUUID());
        return e.contains("T") ? Math.clamp((carrier.level().getGameTime() - e.getLong("T") + partial) / e.getInt("U"), 0, 1) : 1;
    }
    private static Vec vector(Vector3f p) { return new Vec(p.x, p.y, p.z); }
    private static Vector3f mouthPoint(CompoundTag e, Vector3f to, double tick) {
        var from = new Vec(e.getFloat("PX"), e.getFloat("PY"), e.getFloat("PZ"));
        var at = CarryGeometry.liftPoint(from, vector(to), e.getFloat("PH"), Math.clamp((tick - e.getLong("T")) / e.getInt("U"), 0, 1));
        return new Vector3f((float) at.x(), (float) at.y(), (float) at.z());
    }
    /** Records a checked mouth lift just before mounting; -1 means the real route is obstructed. */
    public static int mouthLift(YukkuriEntity carrier, YukkuriEntity child) {
        float yaw = carrier.bodyYaw();
        var from = modelAt(carrier, yaw, child.position());
        var to = mouthSeat(carrier, child);
        var host = carryHost(carrier, child);
        float high = Math.max(from.y, to.y);
        for (int i = 0; host != null && i <= WAY_SAMPLES; i++) {
            var p = new Vector3f(from).lerp(to, i / (float) WAY_SAMPLES);
            double top = Occupancy.surfaceUnder(host, child.getBbWidth(), worldAt(carrier, yaw, p).add(0, 1, 0));
            if (top > carrier.getY()) high = Math.max(high, (float) ((top - carrier.getY() + child.getBbHeight()) * 16 / size(carrier)));
        }
        int ticks = CarryGeometry.motionTicks(CarryGeometry.liftLength(vector(from), vector(to), high) * size(carrier) / 16,
                BodyAbility.of(carrier).walkSpeed());
        var e = entry(carrier, child.getUUID()).copy();
        e.putLong("T", carrier.level().getGameTime()); e.putInt("U", ticks); e.putFloat("PH", high);
        e.putFloat("PX", from.x); e.putFloat("PY", from.y); e.putFloat("PZ", from.z);
        for (int i = 1; i <= ticks; i++) if (!clear(carrier, child, worldAt(carrier, yaw, mouthPoint(e, to, e.getLong("T") + i)))) return -1;
        write(carrier, child.getUUID(), e);
        return ticks;
    }
    /** The ground endpoint in model units: in front of the mouth, at the braid's checked set-down, or under the tail. */
    private static Vector3f ground(YukkuriView carrier, YukkuriView child, Hold hold) {
        if (hold.tail()) return tail(carrier, hold).mul(1, 0, 1);
        if (hold == Hold.MOUTH) {
            var point = mouthSeat(carrier, child);
            double apart = (Occupancy.foot(carrier.getBbWidth()) + Occupancy.foot(child.getBbWidth())) * 16 / size(carrier);
            point.z = (float) Math.min(point.z, -apart);
            return point.mul(1, 0, 1);
        }
        return (hold == Hold.BRAID ? setDown(entry(carrier, child.getUUID())) : restTip()).mul(1, 0, 1);
    }
    private static Vector3f restTip() { var tip = ContactGeometry.braidTipModel(); return new Vector3f((float) tip.x, (float) MOUTH_FLOOR, (float) tip.z); }
    private static Vector3f setDown(CompoundTag e) { return e.contains("X") ? new Vector3f(e.getFloat("X"), (float) MOUTH_FLOOR, e.getFloat("Z")) : restTip(); }

    // ---- the braid tip: the least turn, a set-down clear of the host, the blocks and the body, and the way there

    /** Degrees of one step of the least braid turn; share of the braid's length the set-down search keeps for the scruff's offset. */
    private static final float TURN_STEP = 5, REACH_KEEP = .95f;
    /** Points of the lift and swing tested against blocks and the carrier's body before a way out is taken. */
    private static final int WAY_SAMPLES = 6;
    /**
     * The yaw a Grab turns the carrier to before its braid reaches for {@code child}: from where it faces toward the bearing that
     * puts the braid side on the child, the least turn at which the real braid root (upright, or in the low lean) reaches it and,
     * off a host, a way out exists ({@link #wayOut}); else that full bearing (Grab.check found one there). The carrier turns only
     * as much as the braid needs, and never turns away after.
     */
    public static float braidTurn(YukkuriEntity carrier, YukkuriEntity child, boolean lean) {
        float from = carrier.bodyYaw(), span = Mth.wrapDegrees(ContactGeometry.braidYaw(carrier, child) - from);
        boolean onHost = Attach.of(child) instanceof AttachState.OnHost;
        for (float turn = 0; turn < Math.abs(span); turn += TURN_STEP) {
            float yaw = from + Math.copySign(turn, span);
            if (ContactGeometry.braidGap(carrier, yaw, child, lean) <= 0 && (!onHost || wayOut(carrier, child, yaw, lean).path() != null)) return yaw;
        }
        return from + span;
    }
    /** Grab.check's one set-down test for a braid-tip child lifted off a host: a way out exists with the braid side turned to it. */
    public static boolean braidCanSetDown(YukkuriEntity carrier, YukkuriEntity child, boolean lean) {
        return !(Attach.of(child) instanceof AttachState.OnHost) || wayOut(carrier, child, ContactGeometry.braidYaw(carrier, child), lean).path() != null;
    }
    private record Way(CompoundTag path, String frontCandidates) {}
    /**
     * The braid's way out for {@code child} with the carrier turned to {@code yaw}, as hold-record keys, or null when there is none.
     * The set-down (carrier model units, feet at MOUTH_FLOOR) is, of the rest tip and points round the braid root within its reach
     * at the child's scruff, ranked by facing angle then distance, whose footprint clears the carrier's own, the brim of the hat it is lifted
     * off (the stand rule, {@code HatGeometry.overlaps}) and every block. The way rises from the child clear of the host plus a
     * child's height, swings in front of the root and comes down; only a blocked front arc permits the other arc.
     */
    private static Way wayOut(YukkuriEntity carrier, YukkuriEntity child, float yaw, boolean lean) {
        var root = ContactGeometry.braidRootModel(lean);
        double s = size(carrier), lift = root.y - MOUTH_FLOOR - GRIP_Y * size(child) / s, length = ContactGeometry.braidLength() * REACH_KEEP;
        double reach = lift < length ? Math.sqrt(length * length - lift * lift) : 0;
        double apart = (Occupancy.foot(carrier.getBbWidth()) + Occupancy.foot(child.getBbWidth())) * 16 / s, body = (carrier.getBbWidth() + child.getBbWidth()) * 8 / s;
        var host = Attach.of(child) instanceof AttachState.OnHost ride ? ride.host() : carryHost(carrier, child);
        var hat = host instanceof PlacedHatEntity on ? on : null;
        var tip = restTip();
        var seats = new ArrayList<Vector3f>();
        seats.add(tip);
        for (int ring = 1; ring <= 3; ring++) for (int k = 0; k < 24; k++) {
            double a = k * Math.PI / 12, r = reach * ring / 3;
            seats.add(new Vector3f((float) (root.x + r * Math.cos(a)), (float) MOUTH_FLOOR, (float) (root.z + r * Math.sin(a))));
        }
        seats.sort((a, b) -> CarryGeometry.compareEndpoints(a.x, a.z, b.x, b.z));
        var from = modelAt(carrier, yaw, child.position());
        var fronts = new StringJoiner(";", "[", "]");
        for (var seat : seats) {
            var at = worldAt(carrier, yaw, seat);
            String why = Math.hypot(seat.x - root.x, seat.z - root.z) > reach + 1e-4 ? "unreachable:reach"
                    : Math.hypot(seat.x, seat.z) < apart ? "blocked:carrier"
                    : hat != null && HatGeometry.overlaps(HatGeometry.poseOf(hat), hat.unit(), at, Occupancy.foot(child.getBbWidth())) ? "blocked:host"
                    : !clear(carrier, child, at) ? "blocked:terrain" : null;
            if (why == null) {
                var occupants = carrier.level().getEntities(child, child.getDimensions(child.getPose()).makeBoundingBox(at),
                        other -> other instanceof YukkuriEntity && other != carrier && !carrier.hasPassenger(other));
                why = !occupants.isEmpty() ? "occupied:" + occupants.getFirst().getUUID()
                        : !SurfaceApproach.supported(child, at, other -> false) ? "unsupported" : null;
            }
            var e = new CompoundTag();
            e.putFloat("X", seat.x); e.putFloat("Z", seat.z); e.putFloat("Y", yaw);
            e.putFloat("PX", from.x); e.putFloat("PY", from.y); e.putFloat("PZ", from.z);
            if (lean) e.putBoolean("L", true);
            double turn = CarryGeometry.frontTurn(from.x - root.x, from.z - root.z, seat.x - root.x, seat.z - root.z);
            if (why == null && !CarryGeometry.chooseArc(turn, arc -> {
                e.putFloat("A", (float) arc);
                return clearWay(carrier, child, yaw, host, e, from, seat, body, s);
            })) why = "blocked:route";
            if (seat.z <= 0) fronts.add(String.format(Locale.ROOT, "f=%.3f,r=%.3f:%s", -seat.z * s / 16, seat.x * s / 16,
                    why == null ? "chosen" : why));
            if (why == null) return new Way(e, fronts.toString());
        }
        return new Way(null, fronts.toString());
    }

    private static boolean clearWay(YukkuriEntity carrier, YukkuriEntity child, float yaw, SurfaceHost host, CompoundTag e,
                                    Vector3f from, Vector3f seat, double body, double s) {
        float high = Math.max(from.y, seat.y);
        for (int i = 0; host != null && i <= 8; i++) {
            double top = Occupancy.surfaceUnder(host, child.getBbWidth(), worldAt(carrier, yaw, swing(e, i / 8f, high)).add(0, 1, 0));
            if (top > carrier.getY()) high = Math.max(high, (float) ((top - carrier.getY()) * 16 / s));
        }
        high += (float) (child.getBbHeight() * 16 / s);
        e.putFloat("PH", high);
        boolean free = clear(carrier, child, worldAt(carrier, yaw, new Vector3f(from.x, high, from.z)));
        for (int i = 1; free && i <= WAY_SAMPLES; i++) {
            float t = i / (float) WAY_SAMPLES;
            var q = sweepPoint(e, t);
            free = Math.hypot(q.x, q.z) >= body && clear(carrier, child, worldAt(carrier, yaw, q))
                    && clear(carrier, child, worldAt(carrier, yaw, new Vector3f(from.x, Mth.lerp(t, from.y, high), from.z)))
                    && clear(carrier, child, worldAt(carrier, yaw, new Vector3f(seat.x, Mth.lerp(t, high, seat.y), seat.z)));
        }
        return free;
    }
    /**
     * The braid has closed on {@code child} (just before it is mounted): its way out ({@link #wayOut}, with the carrier's yaw now,
     * which both sides then draw it with) and the tick it starts go into the hold record. False when there is no way out any more:
     * the Grab then ends without lifting it.
     */
    public static boolean braided(YukkuriEntity carrier, YukkuriEntity child, boolean lean) {
        var choice = wayOut(carrier, child, carrier.bodyYaw(), lean);
        var way = choice.path();
        GameplayLog.event(carrier, "family", "braid candidates child={} yaw={} parent={} selected={} frontCandidates={}", child.getUUID(),
                carrier.bodyYaw(), GameplayLog.at(carrier.position()), way == null ? "none" : GameplayLog.at(worldAt(carrier, carrier.bodyYaw(), setDown(way))),
                choice.frontCandidates());
        if (way == null) return false;
        var e = entry(carrier, child.getUUID()).copy().merge(way);
        e.putLong("T", carrier.level().getGameTime());
        write(carrier, child.getUUID(), e);
        GameplayLog.event(carrier, "family", "braid way child={} from={} setDown={} yaw={} high={}", child.getUUID(), GameplayLog.at(child.position()),
                GameplayLog.at(worldAt(carrier, way.getFloat("Y"), setDown(way))), String.format(Locale.ROOT, "%.1f", way.getFloat("Y")),
                String.format(Locale.ROOT, "%.2f", way.getFloat("PH")));
        return true;
    }
    /** A braid-tip child's place on its way out (carrier model units, drawn at the recorded yaw {@code Y}), or null once it is
     *  done or there is none: then it is at its set-down, beside the carrier as it is now. */
    private static Vector3f wayPoint(YukkuriView carrier, CompoundTag e, float partial) {
        if (!e.contains("T")) return null;
        var clip = ClipMarkers.of(Grab.BRAID_LIFT);
        int start = clip.ticks("lift_start", 21), up = Math.max(1, clip.ticks("lift_end", 30) - start), over = Math.max(1, clip.ticks("place", 38) - start - up);
        float t = carrier.level().getGameTime() - e.getLong("T") + partial, high = e.getFloat("PH");
        if (t >= up + over) return null;
        if (t < up) return new Vector3f(e.getFloat("PX"), Mth.lerp(ease(t / up), e.getFloat("PY"), high), e.getFloat("PZ"));
        return sweepPoint(e, (t - up) / over);
    }
    private static Vector3f sweepPoint(CompoundTag e, float u) {
        var at = swing(e, ease(u / .7f), e.getFloat("PH"));
        at.y = Mth.lerp(ease((u - .6f) / .4f), e.getFloat("PH"), (float) MOUTH_FLOOR);
        return at;
    }
    /** The checked arc stored with the hold, shared by the passenger and the rendered braid. */
    private static Vector3f swing(CompoundTag e, float q, float high) {
        var root = ContactGeometry.braidRootModel(e.getBoolean("L"));
        var p = CarryGeometry.swing(root.x, root.z, e.getFloat("PX"), e.getFloat("PZ"), e.getFloat("X"), e.getFloat("Z"), e.getFloat("A"), q);
        return new Vector3f((float) p.x(), high, (float) p.z());
    }
    private static Vector3f tail(YukkuriView carrier, Hold hold) {
        var tail = TAILS.getOrDefault(carrier.assetId(), TAILS.get("chen"))[hold == Hold.TAIL_LEFT ? 0 : 1];
        return new Vector3f(tail.x(), tail.y(), tail.z());
    }
    /** Where one mouth-held child hangs: lane centre across the lips, grip on its head, outward fan and side tilt (degrees). */
    public record MouthLane(double side, double gripX, double gripY, double gripZ, float fan, float roll) {}
    /** The lane of this child among the carrier's mouth-held riders (boarding order; a child not yet aboard comes last). */
    public static MouthLane mouthLane(YukkuriView carrier, YukkuriView child) {
        var riders = riders(carrier, Hold.MOUTH);
        if (riders.stream().noneMatch(rider -> rider.getUUID().equals(child.getUUID()))) riders.add(child);
        int n = riders.size(), me = 0;
        double carrierSize = Math.max(1e-6, size(carrier)), width = (n - 1) * LANE_GAP;
        double[] half = new double[n];
        for (int i = 0; i < n; i++) {
            half[i] = HatGeometry.formHalfWidth(riders.get(i).assetId()) * size(riders.get(i)) / carrierSize;
            width += half[i] * 2;
            if (riders.get(i).getUUID().equals(child.getUUID())) me = i;
        }
        double x = -width / 2, side = 0;
        for (int i = 0; i <= me; i++) { side = x + half[i]; x += half[i] * 2 + LANE_GAP; }
        double rho = size(child) / carrierSize, lips = MOUTH_HALF * (1 + LIP_STRETCH * Math.max(0, n - 1));
        double grip = Mth.clamp((Mth.clamp(side, -lips, lips) - side) / Math.max(1e-6, rho), -GRIP_SIDE, GRIP_SIDE), edge = grip / GRIP_SIDE;
        float fan = n < 2 ? 0 : (float) (side / Math.max(1e-6, width / 2) * LANE_FAN);
        return new MouthLane(side, grip, GRIP_Y - GRIP_DROP * edge * edge, GRIP_Z - .8 * edge * edge, fan, (float) (edge * 3));
    }

    // ---- the brim: free room on the hat's brim top, by the shared footprint rule

    /** The worn hat of a hatted form with a brim, or null. */
    private static JsonObject brim(YukkuriView carrier) { var hat = HatGeometry.worn(carrier.assetId()); return hat != null && hat.has("ring") ? hat : null; }
    /** A brim takes children only while the hat is visibly worn. */
    public static boolean brimmed(YukkuriView carrier) { return carrier.hasVisibleAccessory() && brim(carrier) != null; }
    /** The hat bone of the carrier's steady carry pose (idle or walk), model units. */
    public static Matrix4f hatPose(YukkuriView carrier, float partial) {
        float elapsed = Math.max(0, (carrier.level().getGameTime() - carrier.getAnimationStartTick() + partial) / 20f);
        return WoundSurface.get(carrier.assetId()).pose(carryAction(carrier), elapsed)[brim(carrier).get("bone").getAsInt()];
    }
    /** The orientation of a brim child: the carrier, its hat bone, then the child's own outward facing (degrees). */
    public static Matrix4f brimRotation(YukkuriView carrier, float partial, float facing) {
        return new Matrix4f().rotationY((180 - yaw(carrier, partial)) * Mth.DEG_TO_RAD).mul(hatPose(carrier, partial)).setTranslation(0, 0, 0)
                .rotateY(facing * Mth.DEG_TO_RAD);
    }
    private static float[] floats(JsonArray a) { var out = new float[a.size()]; for (int i = 0; i < out.length; i++) out[i] = a.get(i).getAsFloat(); return out; }
    private static final Map<String, List<float[]>> BRIM_TOPS = new ConcurrentHashMap<>();
    /** The brim's top faces (x0, z0, x1, z1, top; hat-bone model units). */
    private static List<float[]> tops(JsonObject hat, String id) {
        return BRIM_TOPS.computeIfAbsent(id, key -> { var out = new ArrayList<float[]>(); for (var s : hat.getAsJsonObject("ring").getAsJsonArray("supports")) out.add(floats(s.getAsJsonArray())); return out; });
    }
    /** The highest brim face under a point, inset by {@code margin}; NaN off the brim. */
    private static float top(List<float[]> faces, float x, float z, float margin) {
        float top = Float.NaN;
        for (var s : faces) if (x >= s[0] + margin && x <= s[2] - margin && z >= s[1] + margin && z <= s[3] - margin) top = Float.isNaN(top) ? s[4] : Math.max(top, s[4]);
        return top;
    }
    private static final Map<String, float[][]> BRIM_SPOTS = new ConcurrentHashMap<>();
    /**
     * The brim spots (hat-bone model units x, z, top) where a footprint disc of radius {@code r} stands on the brim top, clear of
     * the crown, band and bow boxes up to {@code h} above it, in the order children take them: from the front middle outward
     * round the crown to the back (project design). Pure, cached per form and size.
     */
    private static float[][] brimSpots(JsonObject hat, String id, float r, float h) {
        String key = id + "|" + Math.round(r * 20) + "|" + Math.round(h * 4);
        var cached = BRIM_SPOTS.get(key);
        if (cached != null) return cached;
        var faces = tops(hat, id);
        float margin = hat.get("margin").getAsFloat();
        var boxes = new ArrayList<float[]>();
        for (var b : hat.getAsJsonArray("obstacles")) boxes.add(floats(b.getAsJsonArray()));
        var out = new ArrayList<float[]>();
        for (float x = -12; x <= 12; x += .25f) for (float z = -10; z <= 10; z += .25f) {
            float top = top(faces, x, z, margin);
            boolean ok = !Float.isNaN(top);
            for (int k = 0; k < 12 && ok; k++) ok = !Float.isNaN(top(faces, x + r * Mth.cos(k * Mth.PI / 6), z + r * Mth.sin(k * Mth.PI / 6), margin));
            for (var b : boxes) if (ok && b[1] < top + h && b[4] > top) {
                float dx = Math.max(Math.max(b[0] - x, 0), x - b[3]), dz = Math.max(Math.max(b[2] - z, 0), z - b[5]);
                ok = dx * dx + dz * dz >= r * r;
            }
            if (ok) out.add(new float[]{x, z, top + .02f});
        }
        out.sort(Comparator.comparingDouble((float[] p) -> Math.abs(Math.atan2(p[0], -p[1]))).thenComparingDouble(p -> -p[0]));
        if (BRIM_SPOTS.size() > 256) BRIM_SPOTS.clear();
        var spots = out.toArray(float[][]::new);
        BRIM_SPOTS.put(key, spots);
        return spots;
    }
    /** A child's footprint on the brim: the shared footprint radius ({@link Occupancy#foot}), in the carrier's model units. */
    private static float radius(YukkuriView carrier, YukkuriView child) { return (float) (Occupancy.foot(child.getBbWidth()) * 16 / size(carrier)); }
    /** The first brim spot this child fits beside the given discs (x, z, radius), or null when the brim has no room for it. */
    private static float[] freeBrimSpot(YukkuriView carrier, YukkuriView child, List<float[]> taken) {
        var hat = brim(carrier);
        if (hat == null) return null;
        float r = radius(carrier, child);
        for (var p : brimSpots(hat, carrier.assetId(), r, (float) (child.getBbHeight() * 16 / size(carrier)))) {
            boolean free = true;
            for (var t : taken) if ((p[0] - t[0]) * (p[0] - t[0]) + (p[1] - t[1]) * (p[1] - t[1]) < (r + t[2]) * (r + t[2])) { free = false; break; }
            if (free) return p;
        }
        return null;
    }
    /** The footprints the brim's children take (a child being set down has left them), the given child excepted. */
    private static List<float[]> brimDiscs(YukkuriView carrier, YukkuriView except) {
        var out = new ArrayList<float[]>();
        var all = carrier.carryHolds();
        for (var key : all.getAllKeys()) {
            var e = all.getCompound(key);
            if (holdOf(e) != Hold.BRIM || !e.contains("X") || e.contains("D") || key.equals(except.getUUID().toString())) continue;
            YukkuriView rider = null;
            for (var p : carrier.passengerViews()) if (p.getUUID().toString().equals(key)) rider = p;
            // An id with no passenger counts only for the Grab still lifting it (not a stale one after a reload or an aborted grab).
            if (rider == null && e.contains("I") && carrier.motorAction().equals(Grab.ID)) rider = carrier.yukkuriView(e.getInt("I"));
            if (rider != null) out.add(new float[]{e.getFloat("X"), e.getFloat("Z"), radius(carrier, rider)});
        }
        return out;
    }
    /** A brim child's spot (hat-bone model units x, y, z) and its outward facing (degrees), or null when it has none. */
    public static float[] brimSeat(YukkuriView carrier, YukkuriView child) {
        var hat = brim(carrier);
        var e = entry(carrier, child.getUUID());
        if (hat == null || !e.contains("X")) return null;
        float x = e.getFloat("X"), z = e.getFloat("Z"), top = top(tops(hat, carrier.assetId()), x, z, 0);
        return new float[]{x, Float.isNaN(top) ? hat.getAsJsonArray("seat").get(1).getAsFloat() : top + .02f, z, (float) Math.toDegrees(Math.atan2(-x, -z))};
    }

    /** Seat used by the passenger position update: the raw seat (a mouth, tail or braid seat pulled toward the carrier's own cell until the child fits). */
    public static Vec3 seat(YukkuriEntity carrier, YukkuriEntity child) {
        var hold = hold(carrier, child);
        // The client has already moved the carrier this tick: use its current pose (partial 1) so the child does not trail it.
        var raw = rawSeat(carrier, child, hold == Hold.BRIM ? 0 : 1);
        if (hold == Hold.BRIM && lowering(carrier, child, 0) < 0) return raw;
        for (double t : new double[]{0, .35, .7, 1}) {
            var at = new Vec3(Mth.lerp(t, raw.x, carrier.getX()), raw.y, Mth.lerp(t, raw.z, carrier.getZ()));
            if (clear(carrier, child, at)) return at;
        }
        return null;
    }
    private static boolean clear(YukkuriEntity carrier, YukkuriEntity child, Vec3 at) {
        var box = child.getDimensions(child.getPose()).makeBoundingBox(at).deflate(.02);
        return !carrier.level().getBlockCollisions(child, box).iterator().hasNext() && !carrier.level().containsAnyLiquid(box);
    }

    // ------------------------------------------------------------------------------------------ capacity and rules

    /** Dependents that cannot keep up on their own: babies, and young crawlers (hurt foot, not yet able to jump). */
    public static boolean needsCarry(YukkuriEntity child) {
        return child.isAlive() && !child.family().adult() && !child.family().awaitingLanding() && child.childhood().shouldStayWithParents()
                && (child.family().stage() == FamilyLife.Stage.BABY || child.canCrawlNow());
    }
    private static double volume(YukkuriView e) { return e.getBbWidth() * e.getBbWidth() * e.getBbHeight(); }
    private static double strength(YukkuriEntity carrier) {
        return Math.clamp((carrier.life().energy() - 8) / 60.0, .2, 1) * Math.clamp(carrier.species().traits().might(), .8, 1.5);
    }
    private static boolean mouthFree(YukkuriEntity carrier) {
        return carrier.getMouthItem().isEmpty() && carrier.getMouthBodyFood().isEmpty() && carrier.habitat().carrying().isEmpty();
    }
    /** Why this carrier could not hold this child at all, whatever the hold (stage, kind, an occupied body); null when it might. */
    private static String common(YukkuriEntity carrier, YukkuriEntity child) {
        if (carrier == child) return "self";
        if (!carrier.isAlive() || !child.isAlive()) return "dead";
        if (carrier.level() != child.level()) return "level";
        if (!carrier.family().adult() || child.family().adult()) return "stage";
        if (carrier.profile().houseBody() || carrier.profile().dangerToYukkuri()) return "carrier_kind";
        // A carrier standing on a host (her own boat) holds as on the ground; one carried or riding anything else does not.
        if (carrier.isPassenger() && !(carrier.getVehicle() instanceof SurfaceHost)) return "carrier_boarded";
        if (child.isVehicle()) return "child_carrying";
        // A body standing on a host (the placed catch hat) is lifted straight off it (OnHost -> Held).
        if (child.isPassenger() && child.getVehicle() != carrier && !(child.getVehicle() instanceof SurfaceHost)) return "child_boarded";
        if (carrier.livePregnancyProgress() > 0) return "pregnant";
        return null;
    }
    /**
     * Why this carrier could not hold this child in {@code hold}; null when it can. For a rider already held ({@code ongoing})
     * only the riders ahead of it in the same hold count, and a brim child keeps its spot.
     */
    public static String holdProblem(YukkuriEntity carrier, YukkuriEntity child, Hold hold, boolean ongoing) {
        var problem = common(carrier, child);
        if (problem != null) return problem;
        double ratio = volume(child) / Math.max(1e-6, volume(carrier)), strength = strength(carrier);
        switch (hold) {
            case TAIL_LEFT, TAIL_RIGHT -> {
                if (carrier.getVariant() != YukkuriVariant.CHEN) return "no_tail";
                if (ratio > TAIL_BABY_FRACTION * strength) return "too_big";
                for (var rider : riders(carrier, hold)) if (rider != child) return "occupied";
                return null;
            }
            case BRIM -> {
                if (!brimmed(carrier)) return "no_brim";
                return ongoing || freeBrimSpot(carrier, child, brimDiscs(carrier, child)) != null ? null : "brim_full";
            }
            case BRAID -> {
                if (!ContactGeometry.braided(carrier)) return "no_braid";
                // A hatted Marisa's braid lifts onto her brim instead.
                if (brimmed(carrier)) return "brim";
                if (child.family().stage() != FamilyLife.Stage.BABY) return "not_baby";
                if (ratio > HOLD_FRACTION * strength) return "too_big";
                for (var rider : riders(carrier, hold)) if (rider != child) return "occupied";
                return null;
            }
            default -> {
                if (carrier.getVariant() == YukkuriVariant.CHEN) return "tails";
                if (ratio > HOLD_FRACTION * strength) return "too_big";
                if (!mouthFree(carrier)) return "mouth_full";
                // Babies already in the mouth (for a rider: those ahead of it) share it.
                double load = volume(child);
                int count = 1;
                for (var rider : riders(carrier, Hold.MOUTH)) {
                    if (rider == child) { if (ongoing) break; continue; }
                    load += volume(rider);
                    count++;
                }
                if (!multi(carrier)) return count > 1 ? "occupied" : null;
                if (count > MAX_MOUTH_BABIES) return "occupied";
                return load > MOUTH_LOAD_FRACTION * strength * volume(carrier) ? "mouth_load" : null;
            }
        }
    }
    /** Prefer the mouth whenever it can take the child; Chen uses her two tails. */
    private static Hold[] order(YukkuriEntity carrier) {
        if (carrier.getVariant() == YukkuriVariant.CHEN) return new Hold[]{Hold.TAIL_LEFT, Hold.TAIL_RIGHT};
        return new Hold[]{Hold.MOUTH, brimmed(carrier) ? Hold.BRIM : Hold.BRAID};
    }
    /**
     * All holds with capacity, in preference order; Grab takes the first with a reachable, clear route.
     */
    public static List<Hold> holdsFor(YukkuriEntity carrier, YukkuriEntity child) {
        var out = new ArrayList<Hold>(2);
        for (var hold : order(carrier)) if (holdProblem(carrier, child, hold, false) == null) out.add(hold);
        return out;
    }
    /** Why none of the carrier's holds could take this child now (the first hold's reason, the last one's beside it); null when one can. */
    public static String capacityProblem(YukkuriEntity carrier, YukkuriEntity child) {
        if (!holdsFor(carrier, child).isEmpty()) return null;
        var order = order(carrier);
        String first = holdProblem(carrier, child, order[0], false), last = holdProblem(carrier, child, order[order.length - 1], false);
        return first.equals(last) || last.equals("no_braid") ? first : first + "/" + last;
    }
    /** Vanilla seat acceptance for forced and unforced boarding (YukkuriEntity.canAddPassenger): a child in the water only by the
     *  lift out of the water this carrier is running for it (a rescue's {@code Grab.outOfWater}, read from its action). */
    public static boolean canSeat(YukkuriEntity carrier, Entity passenger) {
        return passenger instanceof YukkuriEntity child && capacityProblem(carrier, child) == null && !carrier.isInWaterOrBubble()
                && (!child.isInWaterOrBubble() || carrier.motor().current() instanceof Grab grab && grab.target() == child && grab.outOfWater())
                && !carrier.operations().active() && !child.operations().active();
    }

    /** The carrier is not busy with something that needs its body, mouth or attention right now. */
    private boolean ready() {
        var life = carrier.life();
        return FamilyLife.ordinaryLandFree(carrier) && !carrier.isPanicking() && !carrier.isOnFire() && !life.isSleeping()
                && life.mealTicks() == 0 && (!life.hasUrgentNeed() || carrier.settlement().marchTiredOnly());
    }
    /** Structural ability only (size, form, a free hold); the child waits for such a carrier instead of walking off. */
    public boolean capable(YukkuriEntity child) { return capacityProblem(carrier, child) == null; }

    /** A dependent baby waits for a parent who can carry it while the family travels, instead of crawling after it. */
    public static boolean awaitsCarrier(YukkuriEntity child) {
        if (!needsCarry(child) || !child.family().familyTravelling() || !(child.level() instanceof ServerLevel level)) return false;
        for (var id : new UUID[]{child.family().mother(), child.family().father()})
            if (id != null && level.getEntity(id) instanceof YukkuriEntity parent && parent.isAlive() && parent.family().adult()
                    && parent.settlement().familyMarching() && child.life().canRecognize(parent) && parent.life().canRecognize(child)
                    && (parent.family().carry().capable(child) || parent.hasPassenger(child)))
                return true;
        return false;
    }
    /** Migration escorts must be able to carry a child that cannot walk with them. */
    public static boolean mayEscort(YukkuriEntity parent, YukkuriEntity child) {
        return !needsCarry(child) || parent.family().carry().capable(child) || parent.hasPassenger(child);
    }
    /** Escort preference for a baby that needs carrying: how many more babies like it this parent can still take (0: none). */
    public static int carryRank(YukkuriEntity parent, YukkuriEntity child) {
        return needsCarry(child) ? parent.family().carry().room(child) : 0;
    }
    /**
     * A mouth-carrier with babies to carry keeps the mouth for them and packs no food: the mother always does, unless her
     * brim, tails or braid alone take them; the father only when the mother cannot take them all (project design, so one
     * parent still brings the rations).
     */
    public static boolean reservesMouth(YukkuriEntity parent) {
        if (!parent.family().adult() || !(parent.level() instanceof ServerLevel level)) return false;
        var kids = level.getEntitiesOfClass(YukkuriEntity.class, parent.getBoundingBox().inflate(24),
                c -> c != parent && needsCarry(c) && Bonds.parentOf(parent, c));
        if (kids.size() <= parent.family().carry().seatsFor(kids, false)) return false;
        if (kids.stream().anyMatch(c -> parent.getUUID().equals(c.family().mother()))) return true;
        var first = kids.get(0);
        var mother = first.family().mother() != null && level.getEntity(first.family().mother()) instanceof YukkuriEntity m && m.isAlive()
                && m.family().adult() ? m : null;
        return kids.size() > (mother == null ? 0 : mother.family().carry().seatsFor(kids, true));
    }

    /** Whether this carrier holds {@code kid} in {@code hold} already, or could take it there from now. */
    private boolean fits(YukkuriEntity kid, Hold hold) {
        return carrier.hasPassenger(kid) ? hold(carrier, kid) == hold : holdProblem(carrier, kid, hold, false) == null;
    }
    /** Babies like {@code child} this carrier could take from now on, this one included: 0 if it cannot take it. */
    int room(YukkuriEntity child) {
        if (common(carrier, child) != null) return 0;
        if (carrier.getVariant() == YukkuriVariant.CHEN)
            return (holdProblem(carrier, child, Hold.TAIL_LEFT, false) == null ? 1 : 0) + (holdProblem(carrier, child, Hold.TAIL_RIGHT, false) == null ? 1 : 0);
        int mouth = 0;
        if (holdProblem(carrier, child, Hold.MOUTH, false) == null) {
            double load = 0;
            var riders = riders(carrier, Hold.MOUTH);
            for (var rider : riders) load += volume(rider);
            int byVolume = (int) Math.floor((MOUTH_LOAD_FRACTION * strength(carrier) * volume(carrier) - load) / Math.max(1e-6, volume(child)));
            mouth = multi(carrier) ? Math.max(1, Math.min(byVolume, MAX_MOUTH_BABIES - riders.size())) : 1;
        }
        if (!brimmed(carrier)) return mouth + (holdProblem(carrier, child, Hold.BRAID, false) == null ? 1 : 0);
        // Copies of this baby added round the brim beside the children already there, until it has no more room.
        var taken = brimDiscs(carrier, child);
        int fit = 0;
        for (float[] spot; fit < 12 && (spot = freeBrimSpot(carrier, child, taken)) != null; fit++) taken.add(new float[]{spot[0], spot[1], radius(carrier, child)});
        return fit + mouth;
    }

    /** How many of these babies this carrier could hold at once from empty, with or without its mouth (the father's share is what is left). */
    int seatsFor(List<YukkuriEntity> kids, boolean mouth) {
        if (carrier.getVariant() == YukkuriVariant.CHEN) {
            // Chen keeps her mouth free: only her two tails carry.
            int tails = (int) kids.stream().filter(kid -> fits(kid, Hold.TAIL_LEFT) || fits(kid, Hold.TAIL_RIGHT)).count();
            return mouth ? Math.min(2, tails) : Integer.MAX_VALUE;
        }
        if (multi(carrier)) {
            if (!mouth) return 0;
            double budget = MOUTH_LOAD_FRACTION * strength(carrier) * volume(carrier), load = 0;
            int count = 0;
            for (var kid : kids) {
                if (count >= MAX_MOUTH_BABIES || !fits(kid, Hold.MOUTH) || load + volume(kid) > budget) continue;
                load += volume(kid);
                count++;
            }
            return count;
        }
        int other = 0;
        if (brimmed(carrier)) {
            var taken = new ArrayList<float[]>();
            for (var kid : kids) {
                var spot = freeBrimSpot(carrier, kid, taken);
                if (spot != null) { taken.add(new float[]{spot[0], spot[1], radius(carrier, kid)}); other++; }
            }
        } else if (kids.stream().anyMatch(kid -> fits(kid, Hold.BRAID))) other = 1;
        return Math.min(kids.size(), other + (mouth && kids.stream().anyMatch(kid -> fits(kid, Hold.MOUTH)) ? 1 : 0));
    }

    // ------------------------------------------------------------------------------------------ pick-up (server)

    /** The first rider (the one a retrieval walk and the readout follow). */
    public YukkuriEntity carried() { return carrier.getFirstPassenger() instanceof YukkuriEntity y ? y : null; }
    public boolean carrying() { return carried() != null; }
    public Purpose purpose() { return purpose; }
    /** Every yukkuri riding this carrier. */
    public List<YukkuriEntity> riders() {
        var out = new ArrayList<YukkuriEntity>();
        for (var rider : carrier.getPassengers()) if (rider instanceof YukkuriEntity y) out.add(y);
        return out;
    }
    /** The mouth is taken by a child, so mash feeding and food packing cannot use it. */
    public boolean mouthBusy() { return mouthCarrying(carrier); }

    private boolean blocked(YukkuriEntity child) {
        var pause = pauses.get(child.getUUID());
        if (pause == null) return false;
        long now = carrier.level().getGameTime();
        if (pause.reason().equals("feeding")
                && (child.life().nutrition() >= 40 || carrier.family().task() == FamilyLife.Task.FEED_CHILD && carrier.family().target() == child)) {
            if (child.life().nutrition() >= 40) { pauses.remove(child.getUUID()); return false; }
            return true;
        }
        if (now >= pause.until()) { pauses.remove(child.getUUID()); return false; }
        return true;
    }

    /** Why this child should be carried now: during a family march, or home after straying out of the nest. */
    Purpose purposeFor(YukkuriEntity child) {
        if (!needsCarry(child) || child.isPassenger() || blocked(child)) return null;
        if (carrier.settlement().familyMarching() && child.family().familyTravelling()) return Purpose.MIGRATE;
        if (FamilyNest.rimNest(child) != null) return Purpose.NEST_RIM;
        var home = child.family().carryHome();
        if (home != null && child.distanceToSqr(Vec3.atBottomCenterOf(home)) > 4 * 4) return Purpose.RETRIEVE;
        return null;
    }
    /** A search for this child should end in a lift rather than in waiting for it to walk over. */
    boolean pickupWanted(YukkuriEntity child) { return (purposeFor(child) != null || child.getUUID().equals(lifting)) && capable(child); }
    /** During a march a baby near the parent still has to be lifted before the parent walks on. */
    boolean pickupDue(YukkuriEntity child) {
        var why = purposeFor(child);
        return (why == Purpose.MIGRATE || why == Purpose.NEST_RIM) && capable(child) && ready();
    }

    /** Standing over a child and counting down its lift (shown as the lean-down of carry_lift, or the braid's reach). */
    boolean lifting() { return lifting != null; }
    /** One tick of walking to the child, standing over it and lifting it with the shared Grab (into the hold it chooses). */
    Step tickPickup(YukkuriEntity child, int taskTicks) {
        var intent = purposeFor(child);
        if (child.getUUID().equals(lifting)) {
            // This carrier's own Grab is the pickup: still lifting, or finished with an outcome to read here.
            if (carrier.motor().current() instanceof Grab) return Step.WORKING;
            lifting = null;
            return grabOutcome(child);
        }
        var problem = capacityProblem(carrier, child);
        boolean ready = ready();
        if (problem != null || !ready) {
            GameplayLog.limited(carrier, "family", "carry.refused", 400, "carry pickup refused child={} reason={} ready={}", child.getUUID(),
                    problem == null ? "carrier_busy" : problem, ready);
            return Step.FAILED;
        }
        var grab = new Grab(child);
        var feasibility = grab.check(carrier);
        if (feasibility.reason() == Reason.NO_CONTACT) {
            // Nothing reaches yet: the stand beside the child (the braid turns its side to it on the lift).
            var stand = SurfaceApproach.beside(carrier, child, (carrier.getBbWidth() + child.getBbWidth()) * .5 + .15, 1.2);
            if (stand == null) {
                GameplayLog.limited(carrier, "family", "carry.approach", 200, "carry pickup refused child={} reason=no_supported_contact_stand", child.getUUID());
                return Step.FAILED;
            }
            SurfaceApproach.walkAround(carrier, stand, child, 1.1);
            return Step.WORKING;
        }
        carrier.getNavigation().stop();
        carrier.getLookControl().setLookAt(child, 30, 30);
        if (child.life().mealTicks() > 0 || child.isPanicking() || child.isInWaterOrBubble() || child.operations().active()) return Step.WORKING;
        if (!feasibility.ok()) {
            GameplayLog.limited(carrier, "family", "carry.grab", 100, "carry grab refused child={} reason={} {}",
                    child.getUUID(), feasibility.reason(), feasibility.detail());
            return Step.WORKING;
        }
        lifting = child.getUUID();
        // The lift commits the carry: the purpose and its clock start with the Grab, so the passive tick cannot re-adopt it.
        if (purpose == null) begin(intent, child);
        GameplayLog.event(carrier, "family", "carry lift started hold={} reach={} purpose={} child={} childPos={} carrierPos={} nutrition={}",
                grab.hold().key(), grab.hold().reach().name().toLowerCase(Locale.ROOT), intent, child.getUUID(), child.blockPosition().toShortString(),
                carrier.blockPosition().toShortString(), child.life().nutrition());
        // A lift another owner's action refuses this tick is retried on the next one; the task budget bounds it.
        if (!carrier.motor().run(owner, grab)) { lifting = null; }
        return Step.WORKING;
    }

    /** The lift commits the carry: the purpose and its clock start when the Grab starts, so the passive tick cannot re-adopt it. */
    private void begin(Purpose intent, YukkuriEntity child) {
        purpose = intent == null ? Purpose.RETRIEVE : intent;
        since = carrier.level().getGameTime();
        arrivedAt = 0;
        homeFailures = walkFailures = 0;
        walkTo = rimSpot = null;
        giveUp = null;
        var rim = purpose == Purpose.NEST_RIM ? FamilyNest.rimNest(child) : null;
        rimNest = rim == null ? null : rim.getBlockPos();
    }

    /** The outcome of this carrier's own Grab: a moved or slipped target is approached again, anything else ends the task. */
    private Step grabOutcome(YukkuriEntity child) {
        var outcome = carrier.motor().last();
        if (outcome instanceof Outcome.Done && carrier.hasPassenger(child)) return aboard(child) ? Step.DONE : Step.FAILED;
        var reason = outcome == null ? null : outcome.reason();
        if (reason == Reason.NO_CONTACT || reason == Reason.TARGET_MOVED) {
            GameplayLog.limited(carrier, "family", "carry.grab.retry", 40, "carry grab retry child={} reason={} shortfall={}",
                    child.getUUID(), reason, outcome.shortfall());
            return Step.WORKING;
        }
        GameplayLog.event(carrier, "family", "carry grab failed child={} outcome={} reason={}", child.getUUID(),
                outcome == null ? "none" : outcome.label(), reason);
        return Step.FAILED;
    }

    /** Called once Grab has mounted the child: the child's own tasks stop and the carry bookkeeping starts. */
    private boolean aboard(YukkuriEntity child) {
        // From here the carry looks after this child ({@code C}); a body another intent lifted (the hat unload) stays that intent's.
        var e = entry(carrier, child.getUUID()).copy();
        e.putBoolean("C", true);
        e.remove("I");
        e.remove("L");
        write(carrier, child.getUUID(), e);
        child.life().interrupt();
        child.family().stop();
        child.social().stop();
        child.dailyLife().stop();
        child.getNavigation().stop();
        carrier.setPersistenceRequired();
        child.setPersistenceRequired();
        child.fallDistance = 0;
        lifting = null;
        // The purpose already started with the Grab; only a carry that lost it meanwhile begins it here.
        if (purpose == null) begin(null, child);
        pauses.remove(child.getUUID());
        GameplayLog.event(carrier, "family", "carry pickup hold={} purpose={} child={} childStage={} seat={} nutrition={} volume={}/{} aboard={}",
                hold(carrier, child).key(), purpose, child.getUUID(), child.family().stage(), child.position(), child.life().nutrition(),
                String.format(Locale.ROOT, "%.3f", volume(child)), String.format(Locale.ROOT, "%.3f", volume(carrier)), carrier.getPassengers().size());
        carrier.showReaction("happy", "gentle", 40);
        return true;
    }

    // ------------------------------------------------------------------------------------------ carrying (server)

    /**
     * Per tick on the carrier (a passive clock): it only watches. A hard reason (water, fire, panic, an operation, death) goes
     * through the MOTOR interrupt bridge, which lets the children go where they are; every ordinary put-down is the set-down
     * goal's Release ({@link #setDownGoal}).
     */
    void tick() {
        prune();
        var all = riders();
        if (all.isEmpty()) {
            if (purpose != null && lifting == null) purpose = null;
            giveUp = null;
            return;
        }
        long now = carrier.level().getGameTime();
        var ours = ours();
        if (purpose == null && !ours.isEmpty()) adopt(ours.get(0), now);
        String hard = !carrier.isAlive() ? "carrier_died" : carrier.isInWaterOrBubble() ? "water" : carrier.isOnFire() ? "carrier_burning"
                : carrier.isPanicking() ? "carrier_panic" : carrier.operations().active() ? "operation" : null;
        if (hard != null) { carrier.motor().interrupt(hard); return; }
        for (var child : all) {
            // In the water is where its hold is wet: one just lifted out of the water hangs at a dry seat.
            String childHard = !child.isAlive() ? "died" : child.isInWaterOrBubble() && seat(carrier, child) == null ? "water"
                    : child.operations().active() ? "operation" : null;
            if (childHard != null) child.motor().interrupt(childHard);
        }
        if (!ours.isEmpty() && (purpose == Purpose.RETRIEVE || purpose == Purpose.NEST_RIM)) carrier.family().beginCarry();
    }

    /** The riders this carry looks after: lifted by its own pick-up (and kept so over a reload), or found with no record at all. */
    private List<YukkuriEntity> ours() {
        var out = new ArrayList<YukkuriEntity>();
        for (var child : riders()) { var e = entry(carrier, child.getUUID()); if (!e.contains("H") || e.getBoolean("C")) out.add(child); }
        return out;
    }

    /** Drops records of bodies no longer held (released elsewhere, gone, a Grab that did not mount); the Grab still lifting keeps its own. */
    private void prune() {
        var all = carrier.carryHolds();
        if (all.isEmpty()) return;
        String lifted = carrier.motor().current() instanceof Grab grab ? grab.target().getUUID().toString() : null;
        CompoundTag next = null;
        for (var key : all.getAllKeys())
            if (!key.equals(lifted) && !carrier.hasPassengerId(UUID.fromString(key))) { if (next == null) next = all.copy(); next.remove(key); }
        if (next != null) carrier.syncComponentData(HOLDS, next);
    }

    /** A passenger found without live state (reloaded save, another pick-up such as the hat unload): carry on with the rule that applies now. */
    private void adopt(YukkuriEntity child, long now) {
        purpose = carrier.settlement().familyMarching() ? Purpose.MIGRATE : Purpose.RETRIEVE;
        since = now;
        arrivedAt = 0;
        homeFailures = walkFailures = 0;
        walkTo = null;
        giveUp = null;
        GameplayLog.event(carrier, "family", "carry resumed hold={} purpose={} child={}", hold(carrier, child).key(), purpose, child.getUUID());
        if (purpose == Purpose.RETRIEVE) carrier.family().beginCarry();
    }

    /** Ordinary reasons that end the whole carry: the carrier's own state, or the shared destination rule. */
    private String carrierReason(long now) {
        var life = carrier.life();
        if (life.isSleeping()) return "carrier_sleeping";
        if (life.mealTicks() > 0 && mouthBusy()) return "carrier_eating";
        if (life.hasUrgentNeed() && !carrier.settlement().marchTiredOnly()) return "carrier_need";
        if (now - since > MAX_CARRY_TICKS) return "timeout";
        if (giveUp != null) return giveUp;
        if (purpose == Purpose.MIGRATE) {
            boolean landed = carrier.settlement().migrationLanded();
            if (landed && arrivedAt == 0) {
                arrivedAt = now;
                GameplayLog.event(carrier, "family", "carry destination reached aboard={}", carrier.getPassengers().size());
            }
            if (landed) {
                // The baby inherits the home only beside the parent who holds it: put them down once the parent stands at her
                // own bed (the census then finds them together), or the wait has run out. A child that already has the home
                // is released on its own in childReason.
                var stand = carrier.life().homeStand();
                if (stand == null || carrier.distanceToSqr(Vec3.atBottomCenterOf(stand)) <= 9) return "arrived_home";
                if (now - arrivedAt > ARRIVE_GRACE_TICKS) return "arrival_timeout";
            }
            if (!landed && !carrier.settlement().familyMarching()) return "migration_ended";
        }
        return null;
    }

    /** Ordinary reasons that end the ride of one child only, its own home or cushion reached among them. */
    private String childReason(YukkuriEntity child, long now) {
        var hold = hold(carrier, child);
        var problem = holdProblem(carrier, child, hold, true);
        if (problem != null) return problem;
        if (now % 5 == 0 && (hold == Hold.BRIM ? !clear(carrier, child, rawSeat(carrier, child, 0)) : seat(carrier, child) == null)) return "seat_blocked";
        if (child.life().nutrition() < 30 && carrier.family().feeding().hasCarriedOpportunity(child)) return "feeding";
        if (purpose == Purpose.MIGRATE && carrier.settlement().migrationLanded() && child.settlement().migrationLanded()) return "arrived_destination";
        var home = purpose == Purpose.RETRIEVE ? child.family().carryHome() : null;
        if (home != null && carrier.distanceToSqr(Vec3.atBottomCenterOf(home)) <= 2.5 * 2.5) return "arrived_home";
        if (purpose == Purpose.NEST_RIM && rimSpot != null) {
            var floor = SurfaceApproach.restingFloor(carrier, rimSpot.add(0, .1, 0));
            var at = floor == null ? rimSpot : floor;
            double dx = at.x - carrier.getX(), dz = at.z - carrier.getZ(), near = dx * dx + dz * dz;
            if (near <= .45 * .45 || near <= .9 * .9 && FamilyNest.cushions(carrier)) return "nest_rim_to_cushion";
        }
        return null;
    }

    /** The next child to set down and why (the one being set down first), or null: a pure read of the carry's state. */
    private Map.Entry<YukkuriEntity, String> due() {
        var all = ours();
        if (all.isEmpty() || lifting != null || carrier.isPassenger()) return null;
        for (var child : all) if (child.getUUID().equals(settingDown)) return Map.entry(child, settingWhy);
        // A set-down already under way (resumed after a reload or a stopped goal) is finished first.
        for (var child : all) if (entry(carrier, child.getUUID()).contains("D")) return Map.entry(child, "release");
        long now = carrier.level().getGameTime();
        String why = carrierReason(now);
        if (why != null) return Map.entry(all.get(0), why);
        for (var child : all) { String childWhy = childReason(child, now); if (childWhy != null) return Map.entry(child, childWhy); }
        return null;
    }

    /** The set-down goal (priority 2, above daily life): whenever the carry has an ordinary reason, it runs the shared Release. */
    public Goal setDownGoal() {
        return new Goal() {
            { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
            @Override public boolean canUse() { return due() != null; }
            @Override public boolean canContinueToUse() { return due() != null; }
            @Override public boolean requiresUpdateEveryTick() { return true; }
            @Override public void start() { setting = true; }
            @Override public void tick() { var next = due(); if (next != null) tickSetDown(next.getKey(), next.getValue()); }
            @Override public void stop() {
                if (carrier.motor().current() instanceof Release || carrier.motor().current() instanceof Walk) carrier.motor().cancel(owner, "set_down_stopped");
                setting = false;
                settingDown = null;
                setTries = 0;
            }
        };
    }

    /**
     * One decision tick of setting {@code child} down with the shared Release (a brim or tail child is lowered first). A
     * refusal (no room, no support where it would be let go) steps the carrier one body width aside, right, left, back, then
     * forward, and tries again; after {@link #SET_DOWN_TRIES} refusals it lets go where the child is, with the refusal logged.
     */
    private void tickSetDown(YukkuriEntity child, String reason) {
        var motor = carrier.motor();
        if (motor.current() instanceof Release release && release.child() == child) return;
        if (motor.current() instanceof Walk && motor.owner() == owner) return;
        settingDown = child.getUUID();
        settingWhy = reason;
        if (motor.run(owner, new Release(child)) || motor.refusedBusy() || motor.refusal() == null) return;
        var refusal = motor.refusal();
        if (++setTries > SET_DOWN_TRIES) {
            GameplayLog.event(carrier, "family", "carry put down forced reason={} refusal={} {} tries={} child={} at={}", reason, refusal.reason(),
                    refusal.label(), SET_DOWN_TRIES, child.getUUID(), GameplayLog.at(child.position()));
            Release.now(carrier, child, true, "carry_" + reason);
            return;
        }
        float yaw = carrier.getYRot() + new float[]{90, -90, 180, 0}[(setTries - 1) % 4];
        var aside = SurfaceApproach.floor(carrier, carrier.position().add(Vec3.directionFromRotation(0, yaw).scale(carrier.getBbWidth())));
        GameplayLog.limited(carrier, "family", "carry.put_down.refused", 20, "carry put down refused reason={} why={} try={}/{} child={} step={}",
                reason, refusal.reason(), setTries, SET_DOWN_TRIES, child.getUUID(), aside == null ? "none" : GameplayLog.at(aside));
        if (aside != null) motor.run(owner, Walk.stand(aside, 1));
    }

    /** Walk home with the child (retrieval) on the shared Walk; the set-down goal puts it down on arrival. False once the walk is over. */
    boolean tickHome(int taskTicks) {
        var child = carried();
        if (child == null || giveUp != null) return false;
        if (purpose == Purpose.NEST_RIM) return tickRim(child, taskTicks);
        var home = child.family().carryHome();
        if (home == null) { giveUp = "no_home"; return false; }
        var centre = Vec3.atBottomCenterOf(home);
        if (carrier.distanceToSqr(centre) <= 2.5 * 2.5) return true;
        var floor = SurfaceApproach.restingFloor(carrier, centre.add(0, .1, 0));
        return walk(floor == null ? centre : floor, taskTicks, "home_unreachable");
    }

    /** Carry a baby that lay on the nest rim onto the cushion, to a free spot by the nest's own placement (never moved by teleport). */
    private boolean tickRim(YukkuriEntity child, int taskTicks) {
        var nest = rimNest != null && carrier.level().getBlockEntity(rimNest) instanceof CareBlockEntity c ? c : null;
        if (nest == null) { giveUp = "no_nest"; return false; }
        if (rimSpot == null || taskTicks % 20 == 1) {
            var spot = nest.freeSpot(child);
            if (spot == null) {
                if (++homeFailures >= 3) {
                    GameplayLog.event(carrier, "family", "carry nest_rim refused child={} reason=no_free_cushion_spot nest={}", child.getUUID(), rimNest.toShortString());
                    giveUp = "nest_full";
                    return false;
                }
            } else {
                if (rimSpot == null) GameplayLog.event(carrier, "family", "carry nest_rim target child={} nest={} spot={}", child.getUUID(), rimNest.toShortString(), spot);
                rimSpot = spot;
                homeFailures = 0;
            }
        }
        if (taskTicks > 600) { giveUp = "nest_unreachable"; return false; }
        if (rimSpot == null) return true;
        var floor = SurfaceApproach.restingFloor(carrier, rimSpot.add(0, .1, 0));
        return walk(floor == null ? rimSpot : floor, taskTicks, "nest_unreachable");
    }

    /**
     * The carry's Walk to {@code at} (Carry: the shared Walk, slowed by the load). A walk that ended short of the
     * same spot, or would not start, is tried again every ten ticks; the third such try ends the carry where the child is.
     */
    private boolean walk(Vec3 at, int taskTicks, String failure) {
        var motor = carrier.motor();
        boolean same = walkTo != null && at.distanceToSqr(walkTo) < .0625;
        if (same && motor.current() instanceof Walk && motor.owner() == owner) return true;
        if (same && taskTicks % 10 != 1) return true;
        if (same && ++walkFailures >= 3) { giveUp = failure; return false; }
        walkTo = at;
        motor.run(owner, new Walk(at));
        return true;
    }

    /** Where this child would be let go, with its carrier facing the given yaw. */
    public static Vec3 letGoPoint(YukkuriEntity carrier, YukkuriEntity child, float yaw) {
        return worldAt(carrier, yaw, ground(carrier, child, hold(carrier, child)));
    }
    public static Vec3 letGoPoint(YukkuriEntity carrier, YukkuriEntity child) {
        return letGoPoint(carrier, child, carrier.bodyYaw());
    }

    public static Vec3 mouthAt(YukkuriEntity carrier, YukkuriEntity child, float yaw) {
        return pinnedAt(carrier, child, worldAt(carrier, yaw, mouthSeat(carrier, child)));
    }

    /** Release starts lowering from the hold to the ground; a braid-tip hold already there opens over its clip markers. */
    public static void lower(YukkuriEntity carrier, YukkuriEntity child) {
        var e = entry(carrier, child.getUUID());
        if (e.contains("D")) return;
        var next = e.copy();
        next.putLong("D", carrier.level().getGameTime());
        int ticks = holdOf(e) == Hold.MOUTH ? CarryGeometry.motionTicks(child.position().distanceTo(letGoPoint(carrier, child)),
                BodyAbility.of(carrier).walkSpeed()) : lowerTicks();
        next.putInt("N", ticks);
        write(carrier, child.getUUID(), next);
        GameplayLog.event(carrier, "family", "carry lowering hold={} child={} from={} ticks={}", holdOf(e).key(), child.getUUID(),
                GameplayLog.at(child.position()), ticks);
    }

    /** Every dismount from a carrier ends here (the Release action, the interrupt bridge, an ended intent): the record goes at once. */
    public static void released(YukkuriEntity carrier, YukkuriEntity child, String cause) { carrier.family().carry().letGone(child, cause); }

    public static void gripped(YukkuriEntity carrier, YukkuriEntity child, String rejected, int travelTicks) {
        var choices = carrier.family().carry().holdChoices;
        choices.keySet().removeIf(id -> !carrier.hasPassengerId(id));
        choices.put(child.getUUID(), rejected);
        GameplayLog.event(carrier, "family", "carry grip child={} uuid={} travelTicks={} {}", child.getId(), child.getUUID(), travelTicks,
                carryReadout(carrier, child, letGoPoint(carrier, child), rejected));
    }

    private static String carryReadout(YukkuriEntity carrier, YukkuriEntity child, Vec3 endpoint, String rejected) {
        double yaw = Math.toRadians(carrier.bodyYaw()), dx = endpoint.x - carrier.getX(), dz = endpoint.z - carrier.getZ();
        return String.format(Locale.ROOT, "hold=%s parent=(%.3f,%.3f,%.3f) yaw=%.2f entityYaw=%.2f endpoint=(%.3f,%.3f,%.3f) forward=%.3f right=%.3f rejected=%s",
                hold(carrier, child).key(), carrier.getX(), carrier.getY(), carrier.getZ(), carrier.bodyYaw(), carrier.getYRot(),
                endpoint.x, endpoint.y, endpoint.z, -dx * Math.sin(yaw) + dz * Math.cos(yaw), -dx * Math.cos(yaw) - dz * Math.sin(yaw), rejected);
    }

    /** The bookkeeping of one let-go: the hold record, the re-lift pause, the log, and the end of the carry when it was the last. */
    private void letGone(YukkuriEntity child, String cause) {
        var hold = hold(carrier, child);
        String readout = carryReadout(carrier, child, child.position(), holdChoices.remove(child.getUUID()));
        write(carrier, child.getUUID(), null);
        boolean ours = child.getUUID().equals(settingDown);
        String reason = ours ? settingWhy : cause;
        if (ours) { settingDown = null; settingWhy = null; setTries = 0; }
        if (child.getUUID().equals(lifting)) lifting = null;
        child.fallDistance = 0;
        long now = carrier.level().getGameTime();
        pauses.put(child.getUUID(), new Pause(now + (reason.equals("feeding") ? FEED_HOLD_TICKS : HOLD_TICKS), reason));
        GameplayLog.event(carrier, "family", "carry put down reason={} hold={} cause={} purpose={} child={} at={} carriedTicks={} nutrition={} remaining={} {}",
                reason, hold.key(), cause, purpose, child.getUUID(), GameplayLog.at(child.position()), now - since, child.life().nutrition(),
                carrier.getPassengers().size(), readout);
        if (!carrying()) {
            purpose = null;
            giveUp = null;
            if (carrier.motor().current() instanceof Walk && carrier.motor().owner() == owner) carrier.motor().cancel(owner, "carry_ended");
            carrier.family().endCarry();
        }
    }

    /** The hold record with the family save, so a reload keeps every child in its hold. */
    CompoundTag save() { return carrier.carryHolds().copy(); }
    /** Entity ids do not survive a reload: the carry's host is left out of what comes back. */
    void load(CompoundTag tag) {
        var all = tag.copy();
        for (var key : all.getAllKeys()) { all.getCompound(key).remove("S"); all.getCompound(key).remove("SO"); }
        carrier.syncComponentData(HOLDS, all);
    }

    String describe() {
        var all = riders();
        if (!all.isEmpty()) {
            var ids = new StringBuilder();
            for (var child : all) ids.append(ids.length() == 0 ? "" : ",").append(child.getUUID()).append('/').append(hold(carrier, child).key());
            return "carrying count=" + all.size() + " children=" + ids + " purpose=" + purpose + " ticks="
                    + (carrier.level().getGameTime() - since) + " landed=" + carrier.settlement().migrationLanded()
                    + (settingDown == null ? "" : " setting_down=" + settingDown + "(" + settingWhy + ") tries=" + setTries);
        }
        if (carrier.getVehicle() instanceof YukkuriEntity parent && riding(parent, carrier))
            return "carried_by=" + parent.getUUID() + " hold=" + hold(parent, carrier).key();
        return "none pauses=" + pauses.size();
    }
}
