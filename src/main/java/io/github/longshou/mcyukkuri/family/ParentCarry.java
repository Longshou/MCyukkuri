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
import io.github.longshou.mcyukkuri.headwear.HatScale;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.motor.Attach;
import io.github.longshou.mcyukkuri.motor.AttachState;
import io.github.longshou.mcyukkuri.motor.BodyAbility;
import io.github.longshou.mcyukkuri.motor.BodyDriver;
import io.github.longshou.mcyukkuri.motor.ClipMarkers;
import io.github.longshou.mcyukkuri.motor.ContactGeometry;
import io.github.longshou.mcyukkuri.motor.ContactGeometry.Reach;
import io.github.longshou.mcyukkuri.motor.Motor;
import io.github.longshou.mcyukkuri.motor.MotorOwner;
import io.github.longshou.mcyukkuri.motor.Outcome;
import io.github.longshou.mcyukkuri.motor.Reason;
import io.github.longshou.mcyukkuri.motor.action.Grab;
import io.github.longshou.mcyukkuri.motor.action.Hop;
import io.github.longshou.mcyukkuri.motor.action.Release;
import io.github.longshou.mcyukkuri.motor.action.Walk;
import io.github.longshou.mcyukkuri.motor.geom.CarryGeometry;
import io.github.longshou.mcyukkuri.motor.geom.CarryGeometry.Rejection;
import io.github.longshou.mcyukkuri.motor.geom.Vec;
import io.github.longshou.mcyukkuri.motor.geom.PlaceGeometry;
import io.github.longshou.mcyukkuri.motor.geom.FloorSearch;
import io.github.longshou.mcyukkuri.motor.surface.Occupancy;
import io.github.longshou.mcyukkuri.motor.surface.SurfaceHost;
import io.github.longshou.mcyukkuri.motor.surface.SeatTarget;
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
 * her braid tip; everyone else one in the mouth (project design). A retrieval lifts every nearby dependent the live holds can
 * still carry before it leaves, and a hit on a carrying parent can shake one loose ({@link #struck}). The walk home is the shared Walk; the put-down is the
 * shared Release at the child's own position for a real reason (a brim or tail child is first lowered to the ground with
 * the braid or tail), never copied, never teleported to a goal. The only record is {@link #HOLDS}: which hold each child is
 * in. Client code reads it with the synced riders through {@link #rawSeat} / {@link #mouthLane} / {@link #brimSeat}; the
 * drawn pose follows the live carrier model in client ParentCarryPresentation.
 */
public final class ParentCarry {
    public enum Purpose { MIGRATE, RETRIEVE, NEST_RIM }
    public enum Step { WORKING, FAILED }
    /** How one carried child is held. */
    public enum Hold {
        MOUTH, BRIM, TAIL_LEFT, TAIL_RIGHT, BRAID, HEAD;
        /** What reaches the child for this hold: the braid for the brim and the braid tip, the mouth's rule for the rest. */
        public Reach reach() { return this == BRIM || this == BRAID || this == HEAD ? Reach.BRAID : Reach.MOUTH; }
        public boolean tail() { return this == TAIL_LEFT || this == TAIL_RIGHT; }
        public String key() { return name().toLowerCase(Locale.ROOT); }
    }
    /**
     * The one record of how each carried child is held, keyed by child UUID: {@code H} the hold, {@code X}/{@code Z} its spot
     * on the brim (a braid-tip child's set-down beside the carrier), {@code D} the tick its set-down began (with the reason
     * {@code R} and re-lift pause {@code P}), and for a Grab's target still being lifted its entity id {@code I} and low lean
     * {@code L}; a braid-tip child's way out ({@link #braided}) adds {@code T}, {@code Y}, {@code A}, {@code PX}/{@code PY}/{@code PZ}, {@code PH} and the swing start {@code OX}/{@code OZ} (out from under a brim).
     * A mouth lift shares the start and height keys, with duration {@code U}; a lowering's duration is {@code N}.
     * A lowering uses host {@code S}, local endpoint {@code DX}/{@code DY}/{@code DZ}, start {@code LX}/{@code LY}/{@code LZ}
     * and pace {@code LV}; a nest's world cushion target uses {@code NX}/{@code NY}/{@code NZ}.
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
    /** Reimu's mouth cavity (measured on her model, project design): the summed volume of the babies in it stays within this
     *  fraction of her own, scaled by strength; a baby also has to pass her lips, whose width is this share of her own. */
    static final double MOUTH_LOAD_FRACTION = .05, LIP_WIDTH_FRACTION = .28;
    /** Chen's tails: one baby per tail, each up to this fraction of her volume times strength (project design, measured so the two babies clear each other and her body). */
    static final double TAIL_BABY_FRACTION = .027;
    /** A retrieval takes the dependents this close before it leaves, for at most this many ticks of its walk (project design). */
    private static final double GATHER_RANGE = 8;
    private static final int GATHER_TICKS = 600;
    /** Reimu lets a baby out of her mouth by this clip: it opens, the baby leaves by {@code release} and the lips shut by {@code closed}. */
    public static final String MOUTH_RELEASE = "mouth_release";
    /** Tail seat: the shelf of the curled tail behind and beside the body (model units, idle pose, full curl). */
    private record Tail(float x, float y, float z) {}
    /** One seat per tail (left, right), model units; matches the curl drawn by ParentCarryPresentation (the client follows the live tail). */
    private static final Map<String, Tail[]> TAILS = Map.of(
            "chen", new Tail[]{new Tail(-3.52f, 6.46f, 8.01f), new Tail(3.52f, 6.46f, 8.01f)},
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
    private UUID lifting;
    /** The dependent this retrieval goes for next, recomputed every tick while it still has holds for one (no reservation: the
     *  child's own wait for a carrying parent reads it, {@link #awaitsCarrier}). */
    private UUID gathering;
    private Release settingDown;
    /** Where the carry's own Walk was last sent and started; why the retrieval gave up; why the child being set down comes down. */
    private Vec3 walkTo, walkFrom;
    private String giveUp, settingWhy;
    /** The set-down goal is running. */
    private boolean setting;
    /** The carry as the motor owner: active while a lift runs, a set-down runs, or the family carry task walks the child home. */
    private final MotorOwner owner = new MotorOwner() {
        @Override public String ownerId() { return "family_carry"; }
        @Override public boolean active() { return lifting != null || setting || purpose != null && carrier.family().task() == FamilyLife.Task.CARRY; }
    };
    /** NEST_RIM: the nest whose rim the child lay on; its baby cushion target is synced in the hold record. */
    private BlockPos rimNest;

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
    private static Vec3 nestSpot(YukkuriView carrier, YukkuriView child) {
        var e = entry(carrier, child.getUUID());
        return e.contains("NX") ? new Vec3(e.getFloat("NX"), e.getFloat("NY"), e.getFloat("NZ")) : null;
    }
    public static Vec3 nestDestination(YukkuriView carrier, YukkuriView child) { return nestSpot(carrier, child); }
    public static void nestSpot(YukkuriEntity carrier, YukkuriEntity child, Vec3 spot) {
        var e = entry(carrier, child.getUUID()).copy();
        e.putFloat("NX", (float) spot.x); e.putFloat("NY", (float) spot.y); e.putFloat("NZ", (float) spot.z);
        write(carrier, child.getUUID(), e);
    }
    /** A Grab of this carrier starts lifting {@code child} into {@code hold}: its brim spot is chosen now, among the brim's children. */
    public static void plan(YukkuriEntity carrier, YukkuriEntity child, Hold hold, boolean lean, SurfaceHost source) {
        if (hold(carrier, child) == Hold.HEAD) {
            var head = entry(carrier, child.getUUID()).copy();
            head.putInt("I", child.getId());
            write(carrier, child.getUUID(), head);
            return;
        }
        var e = new CompoundTag();
        e.putByte("H", (byte) hold.ordinal());
        e.putInt("I", child.getId());
        if (lean) e.putBoolean("L", true);
        var spot = hold == Hold.BRIM ? freeBrimSpot(carrier, child, brimDiscs(carrier, child)) : null;
        if (spot != null) { e.putFloat("X", spot[0]); e.putFloat("Z", spot[1]); }
        // The host the child is lifted off (or the one beside it then), kept for this carry: its surface holds the child up.
        var on = Attach.of(child) instanceof AttachState.OnHost ride ? ride.host().entity() : null;
        var host = source != null ? source.entity() : on != null ? on : carrier.level().getEntities(child, child.getBoundingBox().inflate(2, 1, 2), near -> near instanceof SurfaceHost)
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
        if (e.contains("DX") && e.contains("D")) return feet.y;
        double top = Occupancy.surfaceUnder(host, child.getBbWidth(), feet);
        return top > feet.y && (e.getBoolean("SO") || top - feet.y <= PIN) ? top : feet.y;
    }
    /** The host this carry began on or beside, while it is in the world (the client's lift path reads its surface). */
    public static SurfaceHost carryHost(YukkuriView carrier, YukkuriView child) {
        var e = entry(carrier, child.getUUID());
        int id = e.contains("S") ? e.getInt("S") : carrier.vehicleId();
        return carrier.level().getEntity(id) instanceof SurfaceHost host ? host : null;
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
            if (e.contains("I") && holdOf(e).reach() == Reach.BRAID
                    && (holdOf(e) == Hold.HEAD || !carrier.hasPassengerId(UUID.fromString(key)))) return carrier.yukkuriView(e.getInt("I"));
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
        for (var rider : carrier.passengerViews()) if (hold(carrier, rider) != Hold.HEAD && hold(carrier, rider).reach() == Reach.BRAID) return true;
        return false;
    }
    /** A child sits on the brim: the carrier keeps a steady idle or walk, the brim pose the server places it on (not while the braid is still setting one there). */
    public static boolean brimCarrying(YukkuriView carrier) {
        if (!carrier.isVehicle()) return false;
        for (var rider : riders(carrier, Hold.BRIM)) if (!placing(carrier, rider, 0)) return true;
        return false;
    }
    /** The braid is still carrying this brim child over to its spot (the clip's lift and swing): it is drawn on that way, not yet on the brim. */
    public static boolean placing(YukkuriView carrier, YukkuriView child, float partial) {
        var e = entry(carrier, child.getUUID());
        return holdOf(e) == Hold.BRIM && e.contains("T") && timing(carrier, e, partial).active();
    }
    /** The steady pose a brim carrier is drawn and measured in. */
    public static String carryAction(YukkuriView carrier) { return carrier.getAnimationId().equals("waddle") ? "waddle" : "idle"; }
    /** Ticks a brim or tail child takes to come down to the ground: the braid clip's set-down, its place to release markers. */
    public static int lowerTicks() {
        var clip = ClipMarkers.of(Grab.BRAID_LIFT);
        return Math.max(1, clip.ticks("release", 46) - clip.ticks("place", 38));
    }
    /** The tick a lowered child's set-down began, or -1 when it is not being set down. */
    public static long setDownTick(YukkuriView carrier, YukkuriView child) { var e = entry(carrier, child.getUUID()); return e.contains("D") ? e.getLong("D") : -1; }
    /** Ticks into Reimu's release clip for this baby (the clip's own clock), or -1 when she is not letting it out of her mouth. */
    public static float mouthReleaseTicks(YukkuriView carrier, YukkuriView child, float partial) {
        long since = multi(carrier) && hold(carrier, child) == Hold.MOUTH ? setDownTick(carrier, child) : -1;
        return since < 0 ? -1 : carrier.level().getGameTime() - since + partial;
    }
    /**
     * How far a baby Reimu lets out has dropped from her lips to the ground (0..1, falling from rest), or -1 when she is not letting
     * one out: it stays at her lips until her mouth_release clip's release marker, then drops by its closed marker.
     */
    public static float mouthDrop(YukkuriView carrier, YukkuriView child, float partial) {
        float out = mouthReleaseTicks(carrier, child, partial);
        if (out < 0) return -1;
        var clip = ClipMarkers.of(MOUTH_RELEASE);
        int release = clip.ticks("release", 20);
        float q = Math.clamp((out - release) / Math.max(1, clip.ticks("closed", 26) - release), 0, 1);
        return q * q;
    }
    /** Ticks of Reimu's release clip still to play, the lips shutting by its end; 0 for every other hold. */
    public static int mouthReleaseLeft(YukkuriView carrier, YukkuriView child) {
        float played = mouthReleaseTicks(carrier, child, 0);
        return played < 0 ? 0 : Math.max(0, ClipMarkers.of(MOUTH_RELEASE).length() - (int) played);
    }
    /** How much of Reimu's mouth cavity this baby fills (1: the whole cavity), strength aside; the cheeks swell by the sum. */
    public static double cavityShare(YukkuriView carrier, YukkuriView child) {
        return volume(child) / (MOUTH_LOAD_FRACTION * volume(carrier));
    }
    /** How far a lowered child has come down (0 seated .. 1 on the ground), or -1 when it is not being set down. */
    public static float lowering(YukkuriView carrier, YukkuriView child, float partial) {
        var e = entry(carrier, child.getUUID());
        return e.contains("D") ? Math.clamp((carrier.level().getGameTime() - e.getLong("D") + partial) / e.getInt("N"), 0, 1) : -1;
    }
    public static boolean riding(YukkuriEntity carrier, Entity child) { return child instanceof YukkuriEntity && carrier.hasPassenger(child); }
    /** The carry looks after this rider (it lifted it itself, or took it over): a hit draws for it instead of letting it go. */
    public static boolean keeps(YukkuriEntity carrier, YukkuriEntity child) { return entry(carrier, child.getUUID()).getBoolean("C"); }
    /** This young is held in a Reimu's mouth, where it is not drawn. */
    public static boolean hiddenInMouth(YukkuriEntity child) {
        return child.getVehicle() instanceof YukkuriEntity carrier && riding(carrier, child) && multi(carrier) && hold(carrier, child) == Hold.MOUTH;
    }
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
    private static Vector3f modelAt(YukkuriView carrier, float yaw, Vec3 world) {
        var d = world.subtract(carrier.position());
        return new Vector3f((float) d.x, (float) d.y, (float) d.z).rotateY(-(180 - yaw) * Mth.DEG_TO_RAD).mul(16 / (float) size(carrier));
    }

    /** Feet of the carried child before any collision adjustment, by its hold; a child being set down on its way to the ground. */
    public static Vec3 rawSeat(YukkuriView carrier, YukkuriView child, float partial) {
        var record = entry(carrier, child.getUUID());
        if (record.contains("D") && record.contains("LX")) {
            return transferPoint(carrier, child, record, letGoPoint(carrier, child, yaw(carrier, partial)),
                    carrier.level().getGameTime() - record.getLong("D") + partial, partial);
        }
        var hold = hold(carrier, child);
        Vector3f model;
        switch (hold) {
            case HEAD -> model = headPoint(carrier, child, partial);
            case BRIM -> {
                var seat = brimSeat(carrier, child);
                if (seat == null) model = ground(carrier, child, hold);
                else {
                    // On its way over the braid carries it along the recorded sweep, to the live brim spot.
                    model = brimPoint(carrier, child, partial);
                    var e = entry(carrier, child.getUUID());
                    var way = e.contains("T") ? wayPoint(carrier, e, partial, model) : null;
                    if (way != null) return pinnedAt(carrier, child, world(carrier, partial, way, e.getFloat("Y")));
                }
            }
            case TAIL_LEFT, TAIL_RIGHT -> model = tail(carrier, hold);
            case BRAID -> {
                // On its way out it is drawn at the yaw the way was recorded with, the same on server and client.
                var e = entry(carrier, child.getUUID());
                var way = wayPoint(carrier, e, partial, setDown(e));
                if (way != null) return pinnedAt(carrier, child, world(carrier, partial, way, e.getFloat("Y")));
                model = setDown(e);
            }
            default -> model = mouthSeat(carrier, child);
        }
        if (hold == Hold.MOUTH && mouthLiftProgress(carrier, child, partial) < 1) model = mouthPoint(entry(carrier, child.getUUID()), model,
                carrier.level().getGameTime() + partial);
        float down = lowering(carrier, child, partial), drop = mouthDrop(carrier, child, partial);
        var at = world(carrier, partial, model, yaw(carrier, partial));
        if (down >= 0) {
            var to = letGoPoint(carrier, child, yaw(carrier, partial));
            at = at.lerp(to, drop >= 0 ? drop : ease(down));
        }
        return pinnedAt(carrier, child, at);
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
    /** Ticks since a mouth lift brought this child to the lips (negative while it is still on its way; large without a recorded lift). */
    public static float sinceMouthLift(YukkuriView carrier, YukkuriView child, float partial) {
        var e = entry(carrier, child.getUUID());
        return e.contains("T") ? carrier.level().getGameTime() - e.getLong("T") - e.getInt("U") + partial : Float.MAX_VALUE;
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
        if (hold == Hold.BRIM && brimSeat(carrier, child) != null)
            // A brim child comes down outside the brim edge, beyond the braid's rest tip, not back under the brim.
            return outsideBrim(carrier, child, restTip()).mul(1, 0, 1);
        if (hold == Hold.HEAD) {
            var point = restTip();
            double length = Math.hypot(point.x, point.z);
            double apart = (carrier.getBbWidth() / 2 + headwearRadius(child)) * 16 / size(carrier);
            return point.mul((float) (Math.max(length, apart) / length), 0, (float) (Math.max(length, apart) / length));
        }
        return (hold == Hold.BRAID ? setDown(entry(carrier, child.getUUID())) : restTip()).mul(1, 0, 1);
    }
    private static Vector3f restTip() { var tip = ContactGeometry.braidTipModel(); return new Vector3f((float) tip.x, (float) MOUTH_FLOOR, (float) tip.z); }
    /** Support of the actual brim silhouette in this direction, with room for the child's whole worn form. */
    private static Vector3f outsideBrim(YukkuriView carrier, YukkuriView child, Vector3f direction) {
        float length = (float) Math.hypot(direction.x, direction.z), x = direction.x / length, z = direction.z / length;
        float radius = 0;
        var pose = hatPose(carrier, 1);
        for (var face : tops(brim(carrier), carrier.assetId())) for (float px : new float[]{face[0], face[2]}) for (float pz : new float[]{face[1], face[3]}) {
            var p = pose.transformPosition(new Vector3f(px, face[4], pz));
            radius = Math.max(radius, p.x * x + p.z * z);
        }
        radius += (float) (2 * Occupancy.foot(child.getBbWidth()) * 16 / size(carrier));
        return new Vector3f(x * Math.max(length, radius), direction.y, z * Math.max(length, radius));
    }
    private static Vector3f setDown(CompoundTag e) {
        return e.contains("X") ? new Vector3f(e.getFloat("X"), e.contains("RY") ? e.getFloat("RY") : e.getFloat("PH"), e.getFloat("Z")) : restTip();
    }

    // ---- the braid tip: the braid-side turn and a lift clear of the host and blocks

    /** Points of the lift and swing tested against blocks and the carrier's body before a way out is taken. */
    private static final int WAY_SAMPLES = 6;
    /**
     * The yaw a Grab turns the carrier to before its braid reaches for {@code child}: the bearing that puts the braid side on the
     * child (user 2026-11-05: she turns to her braid side), so the braid never reaches across her own body.
     */
    public static float braidTurn(YukkuriEntity carrier, YukkuriEntity child) { return ContactGeometry.braidYaw(carrier, child); }
    private record Way(CompoundTag path, int[] rejected) {}
    /** The worn-brim lift draws the child out from beneath the brim, rises, then swings onto its brim spot. */
    private static Way wayOut(YukkuriEntity carrier, YukkuriEntity child, float yaw, boolean lean) {
        var root = ContactGeometry.braidRootModel(lean);
        double s = size(carrier);
        var host = Attach.of(child) instanceof AttachState.OnHost ride ? ride.host() : carryHost(carrier, child);
        var from = modelAt(carrier, yaw, child.position());
        var e = new CompoundTag();
        var ontoCarrier = brimPoint(carrier, child, 0);
        // A spot on the far side of the brim: the braid sets the child above the mirrored spot on its own side and the child goes on
        // round the front of the brim by itself (user 2026-11-05 accepts this), so the braid never crosses her face.
        var spot = brimSeat(carrier, child);
        if (spot != null && spot[0] < 0) {
            float top = top(tops(brim(carrier), carrier.assetId()), -spot[0], spot[2], 0);
            e.putFloat("RX", -spot[0]); e.putFloat("RY", Float.isNaN(top) ? spot[1] : top + .02f); e.putFloat("RZ", spot[2]);
        }
        // Onto its own brim the braid first draws a child out from under the brim, so it rises past the brim edge, not through it.
        var out = outward(from, ontoCarrier, (float) (2 * Occupancy.foot(child.getBbWidth()) * 16 / s));
        e.putFloat("Y", yaw);
        e.putFloat("PX", from.x); e.putFloat("PY", from.y); e.putFloat("PZ", from.z);
        e.putFloat("OX", out.x); e.putFloat("OZ", out.z);
        if (lean) e.putBoolean("L", true);
        var choice = CarryGeometry.wayOut(root.x, root.z, vector(from),
                vector(ontoCarrier), point -> {
            var seat = new Vector3f((float) point.x(), (float) point.y(), (float) point.z());
            var at = worldAt(carrier, yaw, seat);
            return clear(carrier, child, at, null) ? null : Rejection.TERRAIN;
        }, (point, arc) -> {
            var seat = new Vector3f((float) point.x(), (float) point.y(), (float) point.z());
            e.putFloat("A", arc.floatValue());
            return clearWay(carrier, child, yaw, host, e, from, seat, s);
        });
        return new Way(choice.point() == null ? null : e, choice.rejected());
    }

    private static boolean clearWay(YukkuriEntity carrier, YukkuriEntity child, float yaw, SurfaceHost host, CompoundTag e,
                                    Vector3f from, Vector3f seat, double s) {
        var out = new Vector3f(e.getFloat("OX"), from.y, e.getFloat("OZ"));
        float high = Math.max(from.y, seat.y);
        for (int i = 0; host != null && i <= 8; i++) {
            double top = Occupancy.surfaceUnder(host, child.getBbWidth(), worldAt(carrier, yaw, swing(e, i / 8f, high, seat)).add(0, 1, 0));
            if (top > carrier.getY()) high = Math.max(high, (float) ((top - carrier.getY()) * 16 / s));
        }
        high += (float) (child.getBbHeight() * 16 / s);
        e.putFloat("PH", high);
        if (e.contains("RX")) {
            // The braid's own way ends above the near-side spot; the child's walk round the brim is timed by its own pace.
            var touch = touch(carrier, e, 0);
            var root = ContactGeometry.braidRootModel(e.getBoolean("L"));
            e.putFloat("A", (float) CarryGeometry.frontTurn(out.x - root.x, out.z - root.z, touch.x - root.x, touch.z - root.z));
            var centre = hatPose(carrier, 0).transformPosition(new Vector3f(0, touch.y, 0));
            double arc = Math.abs(CarryGeometry.frontTurn(touch.x - centre.x, touch.z - centre.z, seat.x - centre.x, seat.z - centre.z))
                    * Math.hypot(touch.x - centre.x, touch.z - centre.z), drop = Math.max(0, high - seat.y);
            int walk = CarryGeometry.motionTicks((arc + drop) * s / 16, BodyAbility.of(child).walkSpeed());
            e.putInt("W", walk); e.putInt("WO", (int) Math.round(walk * arc / Math.max(1e-6, arc + drop)));
        }
        boolean free = clear(carrier, child, worldAt(carrier, yaw, new Vector3f(out.x, high, out.z)), null);
        var plan = timing(e, 0);
        for (int i = 1, n = 3 * WAY_SAMPLES; free && i <= n; i++)
            free = clear(carrier, child, worldAt(carrier, yaw, wayAt(carrier, e, timing(e, (plan.span() + plan.own()) * i / (float) n), 0, seat)), null);
        return free;
    }
    /**
     * The braid has closed on {@code child} (just before it is mounted): its way out ({@link #wayOut}, with the carrier's yaw now,
     * which both sides then draw it with) and the tick it starts go into the hold record. False when there is no way out any more:
     * the Grab then ends without lifting it.
     */
    public static boolean braided(YukkuriEntity carrier, YukkuriEntity child, boolean lean) {
        if (hold(carrier, child) == Hold.BRAID || hold(carrier, child) == Hold.HEAD) return liftBraid(carrier, child, lean);
        var choice = wayOut(carrier, child, carrier.bodyYaw(), lean);
        var way = choice.path();
        var counts = new StringJoiner(",", "[", "]");
        for (var reason : Rejection.values()) counts.add(reason + "=" + choice.rejected()[reason.ordinal()]);
        GameplayLog.event(carrier, "family", "braid candidates child={} yaw={} selected={} own={} rejected={}", child.getUUID(),
                String.format(Locale.ROOT, "%.1f", carrier.bodyYaw()),
                way == null ? "none" : GameplayLog.at(worldAt(carrier, carrier.bodyYaw(),
                        hold(carrier, child) == Hold.BRIM ? brimPoint(carrier, child, 0) : setDown(way))), way == null ? 0 : way.getInt("W"), counts);
        if (way == null) return false;
        var e = entry(carrier, child.getUUID()).copy().merge(way);
        e.putLong("T", carrier.level().getGameTime() + BraidFetch.marker(BraidFetch.HOLD, "lift_start") - BraidFetch.marker(BraidFetch.HOLD, "grip"));
        write(carrier, child.getUUID(), e);
        return true;
    }

    /** Rise at the grip; Release chooses the support and its exterior swing only after the child is held. */
    private static boolean liftBraid(YukkuriEntity carrier, YukkuriEntity child, boolean lean) {
        var from = modelAt(carrier, carrier.bodyYaw(), child.position());
        double s = size(carrier);
        var water = child.blockPosition();
        while (!child.level().getFluidState(water.above()).isEmpty()) water = water.above();
        var fluid = child.level().getFluidState(water);
        double floor = Math.max(carrier.getY(), fluid.isEmpty() ? child.getY() : water.getY() + fluid.getHeight(child.level(), water));
        var host = carryHost(carrier, child);
        if (host != null) floor = Math.max(floor, carrier.getY() + host.container().rimY() - host.container().floorY());
        double surface = host == null ? floor : Occupancy.surfaceUnder(host, child.getBbWidth(), child.position().add(0, 1, 0));
        float high = (float) ((CarryGeometry.liftHeight(child.getY(), floor, surface, child.getBbHeight()) - carrier.getY()) * 16 / s);
        var raised = worldAt(carrier, carrier.bodyYaw(), new Vector3f(from.x, high, from.z));
        if (!clear(carrier, child, raised, null) || carrier.level().getBlockCollisions(child,
                child.getBoundingBox().expandTowards(raised.subtract(child.position())).deflate(.02)).iterator().hasNext()) return false;
        var e = entry(carrier, child.getUUID()).copy();
        boolean head = holdOf(e) == Hold.HEAD;
        e.putByte("H", (byte) Hold.BRAID.ordinal());
        if (head) e.putString("LC", "braid_head_take");
        e.putBoolean("L", lean);
        e.putFloat("Y", carrier.bodyYaw());
        e.putFloat("PX", from.x); e.putFloat("PY", from.y); e.putFloat("PZ", from.z);
        e.putFloat("OX", from.x); e.putFloat("OZ", from.z);
        var rest = restTip();
        rest.x = Math.max(rest.x, (float) (CarryGeometry.BODY_HIGH.x() + headwearRadius(child) * 16 / s + .3));
        e.putFloat("X", rest.x); e.putFloat("Z", rest.z); e.putFloat("PH", high); e.putFloat("A", 0);
        e.putFloat("RY", (float) Math.max(MOUTH_FLOOR, (floor + child.getBbHeight() - carrier.getY()) * 16 / s));
        e.putLong("T", carrier.level().getGameTime() + (head ? 0 : BraidFetch.marker(BraidFetch.HOLD, "lift_start") - BraidFetch.marker(BraidFetch.HOLD, "grip")));
        write(carrier, child.getUUID(), e);
        return true;
    }
    /** Where a braid lift stands in its clip: ticks since it began; the clip's rise (up), rise and swing (across) and whole braid way
     *  (span); and the child's own walk round the brim after the braid let it go on the near side (own). */
    private record Timing(float t, int up, int across, int span, int own) { boolean active() { return t < span + own; } }
    private static Timing timing(CompoundTag e, float t) {
        var clip = ClipMarkers.of(e.contains("LC") ? e.getString("LC") : BraidFetch.HOLD);
        int start = clip.ticks("lift_start", 21);
        int up = clip.ticks("lift_end", 30) - start;
        boolean braid = holdOf(e) == Hold.BRAID;
        return new Timing(t, up, braid ? up : clip.ticks("over_end", 34) - start, braid ? up : clip.ticks("place", 38) - start,
                braid ? 0 : e.getInt("W"));
    }
    private static Timing timing(YukkuriView carrier, CompoundTag e, float partial) {
        return timing(e, Math.max(0, carrier.level().getGameTime() - e.getLong("T") + partial));
    }
    /** Ticks still to go of a brim child's way onto the brim (the braid's lift and the child's own walk round it); 0 for any other hold. */
    public static int placingLeft(YukkuriView carrier, YukkuriView child) {
        var e = entry(carrier, child.getUUID());
        if (holdOf(e) != Hold.BRIM || !e.contains("T")) return 0;
        var time = timing(carrier, e, 0);
        return Math.max(0, (int) Math.ceil(time.span() + time.own() - time.t()));
    }
    /** A braid-held child's place on its way to {@code end} (carrier model units, drawn at the recorded yaw {@code Y}), or null once it is
     *  done or there is none: then it is at its set-down or brim spot, as the carrier is now. */
    private static Vector3f wayPoint(YukkuriView carrier, CompoundTag e, float partial, Vector3f end) {
        if (!e.contains("T")) return null;
        var time = timing(carrier, e, partial);
        return time.active() ? wayAt(carrier, e, time, partial, end) : null;
    }
    /** The one continuous way of a braid lift ({@link CarryGeometry#phases}): to the braid tip, or out from under the brim, up, round
     *  and down onto the brim spot; a far-side child is set above the near-side spot and walks round the front of the brim itself. */
    private static Vector3f wayAt(YukkuriView carrier, CompoundTag e, Timing time, float partial, Vector3f end) {
        float t = time.t();
        if (holdOf(e) == Hold.BRAID) return risePoint(e, t / time.up());
        if (!e.contains("RX")) return brimWay(e, t / time.span(), time.up() / (float) time.span(), time.across() / (float) time.span(), end);
        var touch = touch(carrier, e, partial);
        if (t < time.span()) return brimWay(e, t / time.span(), time.up() / (float) time.span(), 1, touch);
        var centre = hatPose(carrier, partial).transformPosition(new Vector3f(0, touch.y, 0));
        var p = CarryGeometry.transfer(vector(touch), vector(end), vector(centre), CarryGeometry.BODY_LOW, CarryGeometry.BODY_HIGH, 0,
                touch.y, t - time.span(), 0, e.getInt("WO"), time.own());
        return new Vector3f((float) p.x(), (float) p.y(), (float) p.z());
    }
    /** The near-side brim spot a far-side child is let go above (carrier model units, at the carry height). */
    private static Vector3f touch(YukkuriView carrier, CompoundTag e, float partial) {
        var at = hatPose(carrier, partial).transformPosition(new Vector3f(e.getFloat("RX"), e.getFloat("RY"), e.getFloat("RZ")));
        at.y = e.getFloat("PH");
        return at;
    }
    /** Where the braid grips this rider: where it is drawn, or above the near-side brim spot it let a far-side child go at. */
    public static Vec3 braidHeld(YukkuriView carrier, YukkuriView child, float partial) {
        var e = entry(carrier, child.getUUID());
        if (holdOf(e) == Hold.BRIM && e.contains("RX") && e.contains("T")) {
            var time = timing(carrier, e, partial);
            if (time.active() && time.t() >= time.span())
                return pinnedAt(carrier, child, world(carrier, partial, touch(carrier, e, partial), e.getFloat("Y")));
        }
        return rawSeat(carrier, child, partial);
    }
    /** The braid's opening off a far-side child it let go above the near-side spot (1 held .. 0 open), or -1 when that is not happening. */
    private static float letGoShare(YukkuriView carrier, CompoundTag e, float partial) {
        if (holdOf(e) != Hold.BRIM || !e.contains("RX") || !e.contains("T")) return -1;
        var time = timing(carrier, e, partial);
        return time.t() < time.span() ? -1 : 1 - ease((time.t() - time.span()) / BraidFetch.marker(BraidFetch.SETTLE, "release"));
    }
    /** The braid-tip lift: up off the host, across, down to the braid's set-down height, in one motion. */
    private static Vector3f risePoint(CompoundTag e, float u) {
        var at = CarryGeometry.liftPoint(new Vec(e.getFloat("PX"), e.getFloat("PY"), e.getFloat("PZ")), vector(setDown(e)), e.getFloat("PH"), u);
        return new Vector3f((float) at.x(), (float) at.y(), (float) at.z());
    }
    /** The brim lift as one motion: drawn out from under the brim over the first half of the rise, raised, swung round the checked
     *  arc and set down on {@code end}, every part overlapping the next ({@link CarryGeometry#phases}). */
    private static Vector3f brimWay(CompoundTag e, float u, float upEnd, float downStart, Vector3f end) {
        var k = CarryGeometry.phases(u, upEnd, downStart);
        var at = swing(e, (float) k[1], 0, end);
        float drawn = upEnd <= 0 ? 1 : ease(u * 2 / upEnd);
        at.add((e.getFloat("PX") - e.getFloat("OX")) * (1 - drawn), 0, (e.getFloat("PZ") - e.getFloat("OZ")) * (1 - drawn));
        at.y = (float) (e.getFloat("PY") + (e.getFloat("PH") - e.getFloat("PY")) * k[0] + (end.y - e.getFloat("PH")) * k[2]);
        return at;
    }
    /** {@code from} pushed straight away from the carrier until it is {@code gap} beyond {@code onto}'s distance (model units). */
    private static Vector3f outward(Vector3f from, Vector3f onto, float gap) {
        float d = (float) Math.hypot(from.x, from.z), r = (float) Math.hypot(onto.x, onto.z) + gap;
        return d >= r ? from : new Vector3f(from.x * r / d, from.y, from.z * r / d);
    }
    /** The checked arc stored with the hold, shared by the passenger and the rendered braid. */
    private static Vector3f swing(CompoundTag e, float q, float high, Vector3f end) {
        var root = ContactGeometry.braidRootModel(e.getBoolean("L"));
        var p = CarryGeometry.swing(root.x, root.z, e.getFloat("OX"), e.getFloat("OZ"), end.x, end.z, e.getFloat("A"), q);
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
        var pose = new Matrix4f(WoundSurface.get(carrier.assetId()).pose(carryAction(carrier), elapsed)[brim(carrier).get("bone").getAsInt()]);
        var anchor = HatGeometry.anchor();
        return pose.translate(anchor).scale(HatScale.factor(carrier.subspeciesFlags())).translate(anchor.negate());
    }
    /** Bare head follows the live head bone, without applying the worn hat's scale. */
    public static Matrix4f headPose(YukkuriView carrier, float partial) {
        float elapsed = Math.max(0, (carrier.level().getGameTime() - carrier.getAnimationStartTick() + partial) / 20f);
        return new Matrix4f(WoundSurface.get(carrier.assetId()).pose(carrier.getAnimationId(), elapsed)[HatGeometry.worn(carrier.assetId()).get("bone").getAsInt()]);
    }
    private static final Map<String, List<double[]>> HEAD_TOPS = new ConcurrentHashMap<>();
    private static List<double[]> headSupports(YukkuriView carrier) {
        return HEAD_TOPS.computeIfAbsent(carrier.assetId(), id -> {
            var out = new ArrayList<double[]>();
            for (var value : HatGeometry.worn(id).getAsJsonObject("head").getAsJsonArray("supports")) {
                var a = value.getAsJsonArray();
                out.add(new double[]{a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble(), a.get(3).getAsDouble(), a.get(4).getAsDouble()});
            }
            return List.copyOf(out);
        });
    }
    public static double headwearRadius(YukkuriView child) {
        double foot = Occupancy.foot(child.getBbWidth());
        if (!child.hasVisibleAccessory()) return foot;
        double half = HatGeometry.worn(child.assetId()) == null ? HatGeometry.formHalfWidth(child.assetId())
                : HatGeometry.wornForm(child).wornBrim()[0] * HatScale.factor(child.subspeciesFlags());
        return Math.max(foot, half * size(child) / 16);
    }
    private static boolean headAvailable(YukkuriEntity carrier) {
        var hat = HatGeometry.worn(carrier.assetId());
        return !carrier.life().hasAccessory() && hat != null && hat.has("head");
    }
    public static List<Vec3> headPlaces(YukkuriEntity carrier, YukkuriEntity child) {
        if (!headAvailable(carrier)) return List.of();
        var supports = headSupports(carrier);
        double x0 = supports.stream().mapToDouble(p -> p[0]).min().orElseThrow(), x1 = supports.stream().mapToDouble(p -> p[2]).max().orElseThrow();
        double z0 = supports.stream().mapToDouble(p -> p[1]).min().orElseThrow(), z1 = supports.stream().mapToDouble(p -> p[3]).max().orElseThrow();
        var out = new ArrayList<Vec3>();
        for (double x = x0; x <= x1; x += FloorSearch.GRID_CELL) for (double z = z0; z <= z1; z += FloorSearch.GRID_CELL) {
            double y = headSupport(carrier, child, x, z);
            if (Double.isFinite(y) && headRoom(carrier, child, new Vec3(x, y, z))) out.add(new Vec3(x, y, z));
        }
        return out;
    }
    private static double headSupport(YukkuriView carrier, YukkuriView child, double x, double z) {
        return PlaceGeometry.head(headSupports(carrier), x, z, Occupancy.foot(child.getBbWidth()) * 16 / size(carrier),
                HatGeometry.worn(carrier.assetId()).getAsJsonObject("head").get("margin").getAsDouble());
    }
    public static boolean headRoom(YukkuriEntity carrier, YukkuriEntity child, Vec3 at) {
        if (!headAvailable(carrier) || !Double.isFinite(headSupport(carrier, child, at.x, at.z))) return false;
        double radius = headwearRadius(child) * 16 / size(carrier);
        for (var other : riders(carrier, Hold.HEAD)) if (!other.getUUID().equals(child.getUUID())) {
            var e = entry(carrier, other.getUUID());
            if (Math.hypot(at.x - e.getFloat("X"), at.z - e.getFloat("Z")) < radius + headwearRadius(other) * 16 / size(carrier) + .3) return false;
        }
        return true;
    }
    private static Vector3f headPoint(YukkuriView carrier, YukkuriView child, float partial) {
        var e = entry(carrier, child.getUUID());
        return headPoint(carrier, new Vec3(e.getFloat("X"), e.getFloat("PH"), e.getFloat("Z")), partial);
    }
    private static Vector3f headPoint(YukkuriView carrier, Vec3 local, float partial) {
        var point = headPose(carrier, partial).transformPosition(new Vector3f((float) local.x, (float) local.y, (float) local.z));
        LiveBirthGeometry.deform(point, carrier.livePregnancyProgress(), 0);
        return point;
    }
    public static Vec3 headWorld(YukkuriView carrier, Vec3 local, float partial) {
        return world(carrier, partial, headPoint(carrier, local, partial), yaw(carrier, partial));
    }
    public static void seatedHead(YukkuriEntity carrier, YukkuriEntity child, Vec3 at) {
        var e = new CompoundTag();
        e.putByte("H", (byte) Hold.HEAD.ordinal());
        e.putFloat("X", (float) at.x); e.putFloat("PH", (float) at.y); e.putFloat("Z", (float) at.z);
        write(carrier, child.getUUID(), e);
    }
    /** The child's fixed brim-local seat in carrier model space. */
    private static Vector3f brimPoint(YukkuriView carrier, YukkuriView child, float partial) {
        var seat = brimSeat(carrier, child);
        return hatPose(carrier, partial).transformPosition(new Vector3f(seat[0], seat[1], seat[2]));
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
     * the crown, band and bow boxes up to {@code h} above it, in the order children take them: the braid side from the front round
     * to the back, then the far side from the back round to the front, so a far-side child walks to its spot past free spots only
     * (project design; user 2026-11-05: the braid side first). Pure, cached per form and size.
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
        out.sort(Comparator.comparingDouble((float[] p) -> p[0] >= 0 ? Math.abs(Math.atan2(p[0], -p[1])) : 2 * Math.PI - Math.abs(Math.atan2(p[0], -p[1]))));
        if (BRIM_SPOTS.size() > 256) BRIM_SPOTS.clear();
        var spots = out.toArray(float[][]::new);
        BRIM_SPOTS.put(key, spots);
        return spots;
    }
    /** A child's footprint on the brim: the shared footprint radius ({@link Occupancy#foot}), in the carrier's model units. */
    private static float radius(YukkuriView carrier, YukkuriView child) {
        return (float) (Occupancy.foot(child.getBbWidth()) * 16 / (size(carrier) * HatScale.factor(carrier.subspeciesFlags())));
    }
    /** The first brim spot this child fits beside the given discs (x, z, radius), or null when the brim has no room for it. */
    private static float[] freeBrimSpot(YukkuriView carrier, YukkuriView child, List<float[]> taken) {
        var hat = brim(carrier);
        if (hat == null) return null;
        float r = radius(carrier, child);
        for (var p : brimSpots(hat, carrier.assetId(), r,
                (float) (child.getBbHeight() * 16 / (size(carrier) * HatScale.factor(carrier.subspeciesFlags()))))) {
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
        if (hold == Hold.BRAID) return clear(carrier, child, raw) ? raw : null;
        if ((hold == Hold.BRIM || hold == Hold.HEAD) && lowering(carrier, child, 0) < 0) return raw;
        for (double t : new double[]{0, .35, .7, 1}) {
            var at = new Vec3(Mth.lerp(t, raw.x, carrier.getX()), raw.y, Mth.lerp(t, raw.z, carrier.getZ()));
            if (clear(carrier, child, at)) return at;
        }
        return null;
    }
    private static boolean clear(YukkuriEntity carrier, YukkuriEntity child, Vec3 at) {
        var e = entry(carrier, child.getUUID());
        var host = carryHost(carrier, child);
        return clear(carrier, child, at, e.contains("D") || e.contains("T") && timing(carrier, e, 0).active()
                || host != null && host.shelters(child) ? host : null);
    }
    private static boolean clear(YukkuriEntity carrier, YukkuriEntity child, Vec3 at, SurfaceHost destination) {
        var box = child.getDimensions(child.getPose()).makeBoundingBox(at).deflate(.02);
        if (carrier.level().getBlockCollisions(child, box).iterator().hasNext()) return false;
        if (destination != null) return true;
        for (var pos : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ), BlockPos.containing(box.maxX, box.maxY, box.maxZ))) {
            var fluid = carrier.level().getFluidState(pos);
            if (!fluid.isEmpty() && box.minY < pos.getY() + fluid.getHeight(carrier.level(), pos)) return false;
        }
        return true;
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
        return holdProblem(carrier, child, hold, ongoing, carryHost(carrier, child));
    }
    public static String holdProblem(YukkuriEntity carrier, YukkuriEntity child, Hold hold, boolean ongoing, SurfaceHost destination) {
        var problem = common(carrier, child);
        if (problem != null) return problem;
        double ratio = volume(child) / Math.max(1e-6, volume(carrier)), strength = strength(carrier);
        switch (hold) {
            case HEAD -> {
                var e = entry(carrier, child.getUUID());
                return headRoom(carrier, child, new Vec3(e.getFloat("X"), e.getFloat("PH"), e.getFloat("Z"))) ? null : "head_full";
            }
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
                if (destination == null && child.family().stage() != FamilyLife.Stage.BABY) return "not_baby";
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
                if (child.getBbWidth() > LIP_WIDTH_FRACTION * carrier.getBbWidth()) return "lips";
                return load > MOUTH_LOAD_FRACTION * strength * volume(carrier) ? "mouth_load" : null;
            }
        }
    }
    /** A worn brim takes the child first while it has room, then the mouth; the others prefer the mouth; Chen uses her two tails. */
    private static Hold[] order(YukkuriEntity carrier) {
        if (carrier.getVariant() == YukkuriVariant.CHEN) return new Hold[]{Hold.TAIL_LEFT, Hold.TAIL_RIGHT};
        return brimmed(carrier) ? new Hold[]{Hold.BRIM, Hold.MOUTH} : new Hold[]{Hold.MOUTH, Hold.BRAID};
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
    /** Vanilla passenger acceptance uses the same carry capacity and operation rules. */
    public static boolean canSeat(YukkuriEntity carrier, Entity passenger) {
        return passenger instanceof YukkuriEntity child && (hold(carrier, child) == Hold.HEAD || capacityProblem(carrier, child) == null)
                && !carrier.operations().active() && !child.operations().active();
    }
    /** The parents of the overload rule (low intelligence, high friendliness): they keep every child through an overload and
     *  ignore the hull's load when they put a young down, so the capsize chance alone answers. */
    public static boolean overloadKeeper(YukkuriEntity parent) {
        var personality = parent.life().personality();
        return personality.intelligence() <= 25 && personality.friendliness() >= 75;
    }

    /** The carrier is not busy with something that needs its body, mouth or attention right now. */
    private boolean ready() {
        var life = carrier.life();
        return FamilyLife.ordinaryLandFree(carrier) && !carrier.isPanicking() && !carrier.isOnFire() && !life.isSleeping()
                && life.mealTicks() == 0 && (!life.hasUrgentNeed() || carrier.settlement().marchTiredOnly());
    }
    /** Structural ability only (size, form, a free hold); the child waits for such a carrier instead of walking off. */
    public boolean capable(YukkuriEntity child) { return capacityProblem(carrier, child) == null; }

    /** A dependent baby waits for a parent who is marching or coming to carry it, instead of crawling after that parent. */
    public static boolean awaitsCarrier(YukkuriEntity child) {
        if (!needsCarry(child) || !(child.level() instanceof ServerLevel level)) return false;
        for (var id : new UUID[]{child.family().mother(), child.family().father()})
            if (id != null && level.getEntity(id) instanceof YukkuriEntity parent && parent.isAlive() && parent.family().adult()) {
                var carry = parent.family().carry();
                boolean incoming = parent.family().task() == FamilyLife.Task.SEARCH && parent.family().target() == child
                        || parent.family().task() == FamilyLife.Task.CARRY && child.getUUID().equals(carry.gathering);
                boolean escort = parent.family().task() != FamilyLife.Task.SEARCH && child.family().familyTravelling()
                        && child.family().migrationEscort() == parent && carry.pickupDue(child);
                if ((incoming || escort) && carry.ready() && carry.pickupWanted(child) && child.life().canRecognize(parent)
                        && parent.life().canRecognize(child))
                    return true;
            }
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
            mouth = multi(carrier) ? Math.max(1, byVolume) : 1;
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
                if (!fits(kid, Hold.MOUTH) || load + volume(kid) > budget) continue;
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
        // The body belongs to another action this tick (a feeding refusal turn, a step aside): the lift waits for it to end
        // instead of re-running the same Grab every tick.
        if (carrier.motor().current() != null) return Step.WORKING;
        var grab = new Grab(child);
        var feasibility = grab.check(carrier);
        if (feasibility.reason() == Reason.NO_CONTACT) {
            // Nothing reaches yet: the stand beside the child (the braid turns its side to it on the lift).
            var stand = grab.contactStand(carrier);
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
        // Only an accepted Grab lifts the child: a refusal here is this pickup's real failure, never an unchanged retry.
        if (!carrier.motor().run(owner, grab)) {
            lifting = null;
            return Step.FAILED;
        }
        // The accepted lift commits the carry: the purpose and its clock start with the Grab, so the passive tick cannot re-adopt it.
        if (purpose == null) begin(intent, child);
        GameplayLog.event(carrier, "family", "carry lift started hold={} reach={} purpose={} child={} childPos={} carrierPos={} nutrition={}",
                grab.hold().key(), grab.hold().reach().name().toLowerCase(Locale.ROOT), intent, child.getUUID(), child.blockPosition().toShortString(),
                carrier.blockPosition().toShortString(), child.life().nutrition());
        return Step.WORKING;
    }

    /** The lift commits the carry: the purpose and its clock start when the Grab starts, so the passive tick cannot re-adopt it. */
    private void begin(Purpose intent, YukkuriEntity child) {
        purpose = intent == null ? Purpose.RETRIEVE : intent;
        since = carrier.level().getGameTime();
        arrivedAt = 0;
        walkTo = null;
        giveUp = null;
        var rim = purpose == Purpose.NEST_RIM ? FamilyNest.rimNest(child) : null;
        rimNest = rim == null ? null : rim.getBlockPos();
    }

    /** The outcome of this carrier's own Grab: lost contact is approached again, anything else ends the task. */
    private Step grabOutcome(YukkuriEntity child) {
        var outcome = carrier.motor().last();
        var reason = outcome == null ? null : outcome.reason();
        if (reason == Reason.NO_CONTACT) {
            var stand = new Grab(child).contactStand(carrier);
            if (stand != null && carrier.position().distanceToSqr(stand) > .01) {
                GameplayLog.event(carrier, "family", "carry contact lost child={} shortfall={} approach={}", child.getUUID(), outcome.shortfall(), stand);
                SurfaceApproach.walkAround(carrier, stand, child, 1.1);
                return Step.WORKING;
            }
        }
        GameplayLog.event(carrier, "family", "carry grab failed child={} outcome={} reason={}", child.getUUID(),
                outcome == null ? "none" : outcome.label(), reason);
        return Step.FAILED;
    }

    /** The physical Grab completion owns this handoff even if the initiating family search has stopped. */
    public static void pickupCompleted(YukkuriEntity carrier, YukkuriEntity child) {
        var carry = carrier.family().carry();
        if (child.getUUID().equals(carry.lifting)) carry.aboard(child);
    }

    private void aboard(YukkuriEntity child) {
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
    }

    // ------------------------------------------------------------------------------------------ carrying (server)

    /**
     * Per tick on the carrier (a passive clock): it only watches. A hard reason (fire, an operation, death) goes
     * through the MOTOR interrupt bridge, which lets the children go where they are; every ordinary put-down is the set-down
     * goal's Release ({@link #setDownGoal}). A hit is no hard reason: the panic it causes keeps the riders, and {@link #struck} draws.
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
        if (purpose == Purpose.MIGRATE && carrier.settlement().migrationLanded() && arrivedAt == 0) {
            arrivedAt = now;
            GameplayLog.event(carrier, "family", "carry destination reached aboard={}", carrier.getPassengers().size());
        }
        String hard = !carrier.isAlive() ? "carrier_died" : carrier.isOnFire() ? "carrier_burning" : carrier.operations().active() ? "operation" : null;
        if (hard != null) { carrier.motor().interrupt(hard); return; }
        for (var child : all) {
            String childHard = !child.isAlive() ? "died" : child.operations().active() ? "operation" : null;
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
        // A brim seat is fixed to the carrier's own hat and follows its route: a wall beside it is a clip, not a reason to let go.
        if (now % 5 == 0 && hold != Hold.BRIM && seat(carrier, child) == null) return "seat_blocked";
        if (child.life().nutrition() < 30 && carrier.family().feeding().hasCarriedOpportunity(child)) return "feeding";
        if (purpose == Purpose.MIGRATE && carrier.settlement().migrationLanded() && child.settlement().migrationLanded()) return "arrived_destination";
        var home = purpose == Purpose.RETRIEVE ? child.family().carryHome() : null;
        if (home != null && carrier.distanceToSqr(Vec3.atBottomCenterOf(home)) <= 2.5 * 2.5) return "arrived_home";
        if (purpose == Purpose.NEST_RIM) return "nest_rim_to_cushion";
        return null;
    }

    /** The next child to set down and why (the one being set down first), or null: a pure read of the carry's state. */
    private Map.Entry<YukkuriEntity, String> due() {
        // A running jump holds the child: the crouch, the flight and its landing offer no place to lower it.
        if (carrier.motorAction().equals(Hop.ID)) return null;
        var all = ours();
        if (all.isEmpty() || lifting != null || carrier.isPassenger() && giveUp == null) return null;
        // A blocked hull placement yields the body so its ordinary landing intent can carry the child ashore.
        if (carrier.isPassenger() && settingDown != null && !settingDown.changed(carrier, null)) return null;
        if (settingDown != null && carrier.motor().current() == settingDown) return Map.entry(settingDown.child(), settingWhy);
        // A set-down already under way (resumed after a reload or a stopped goal) is finished first.
        for (var child : all) if (entry(carrier, child.getUUID()).contains("D"))
            return Map.entry(child, settingDown != null && settingDown.child() == child ? settingWhy : "release");
        long now = carrier.level().getGameTime();
        String why = carrierReason(now);
        if (why != null) return Map.entry(all.get(0), why);
        for (var child : all) { String childWhy = childReason(child, now); if (childWhy != null) return Map.entry(child, childWhy); }
        return null;
    }

    /** The family goal must yield to its own ordinary put-down goal when this reason becomes due. */
    public boolean setDownDue() { return due() != null; }

    /** An ended carry whose hull placement failed still needs the carrier to reach dry ground. */
    public YukkuriEntity childForShore() {
        var all = ours();
        return carrier.isPassenger() && giveUp != null && !all.isEmpty() && !setDownDue() ? all.getFirst() : null;
    }

    /** An ended intent hands its real passenger to the existing set-down goal, never to a forced drop. */
    public void finish(YukkuriEntity child, String why) {
        var e = entry(carrier, child.getUUID()).copy();
        e.putBoolean("C", true);
        write(carrier, child.getUUID(), e);
        if (purpose == null) adopt(child, carrier.level().getGameTime());
        giveUp = why;
    }

    /** The set-down goal (priority 2, above daily life): whenever the carry has an ordinary reason, it runs the shared Release. */
    public Goal setDownGoal() {
        return new Goal() {
            private Map.Entry<YukkuriEntity, String> admitted;
            { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
            @Override public boolean canUse() { admitted = due(); return admitted != null; }
            @Override public boolean canContinueToUse() { return due() != null; }
            @Override public boolean requiresUpdateEveryTick() { return true; }
            @Override public void start() {
                setting = true;
                GameplayLog.event(carrier, "family", "carry set down started reason={} child={}", admitted.getValue(), admitted.getKey().getUUID());
            }
            @Override public void tick() { var next = due(); if (next != null) tickSetDown(next.getKey(), next.getValue()); }
            @Override public void stop() {
                if (carrier.motor().current() instanceof Release || carrier.motor().current() instanceof Walk) carrier.motor().cancel(owner, "set_down_stopped");
                setting = false;
            }
        };
    }

    /** Release owns placement, approach and lowering. A failed action is reported, never rerun unchanged or forced. */
    private void tickSetDown(YukkuriEntity child, String reason) {
        var motor = carrier.motor();
        if (motor.current() instanceof Release release && release.child() == child) return;
        if (motor.current() instanceof Walk && motor.owner() == owner) return;
        var home = purpose == Purpose.NEST_RIM ? rimNest
                : reason.equals("arrived_home") || reason.equals("arrived_destination") ? child.family().carryHome() : null;
        if (settingDown != null && settingDown.child() == child && reason.equals(settingWhy) && !settingDown.changed(carrier, home)) return;
        settingDown = carrier.getVehicle() instanceof SurfaceHost host ? new Release(child, host) : Release.atNest(child, home);
        settingWhy = reason;
        if (!motor.run(owner, settingDown) && motor.refusal() != null) settingDown.finished(carrier, motor.refusal());
    }

    /**
     * Before the retrieval leaves, it lifts every nearby dependent the live holds can still carry (each Grab picks its hold, a
     * failed lift leaves that one for a later trip); then it walks home with the children on the shared Walk, and the set-down
     * goal puts them down on arrival. False once the walk is over.
     */
    boolean tickHome(int taskTicks) {
        var child = carried();
        if (child == null || giveUp != null) return false;
        if (purpose == Purpose.NEST_RIM) return true;
        var more = lifting != null || walkTo == null && taskTicks < GATHER_TICKS ? dependent() : null;
        // The dependent this retrieval goes for next stops its own walking and waits for the lift ({@link #awaitsCarrier}).
        if (more != null && !more.getUUID().equals(gathering)) SurfaceApproach.stopWalking(more);
        gathering = more == null ? null : more.getUUID();
        if (more != null) {
            if (tickPickup(more, taskTicks) == Step.FAILED)
                pauses.put(more.getUUID(), new Pause(carrier.level().getGameTime() + HOLD_TICKS, "pickup_failed"));
            return true;
        }
        var home = child.family().carryHome();
        if (home == null) { giveUp = "no_home"; return false; }
        var centre = Vec3.atBottomCenterOf(home);
        if (carrier.distanceToSqr(centre) <= 2.5 * 2.5) return true;
        var entrance = carrier.life().entrance(home);
        var approach = entrance == null ? centre : Vec3.atBottomCenterOf(entrance);
        var floor = SurfaceApproach.restingFloor(carrier, approach.add(0, .1, 0));
        return walk(floor == null ? approach : floor, taskTicks, "home_unreachable");
    }

    /** The dependent under this carrier's Grab, else the nearest within reach that the retrieval would lift now and the live holds can carry. */
    private YukkuriEntity dependent() {
        var level = (ServerLevel) carrier.level();
        if (lifting != null) {
            if (level.getEntity(lifting) instanceof YukkuriEntity lifted) return lifted;
            lifting = null;
        }
        return level.getEntitiesOfClass(YukkuriEntity.class, carrier.getBoundingBox().inflate(GATHER_RANGE),
                kid -> kid != carrier && Bonds.parentOf(carrier, kid) && purposeFor(kid) == purpose && capable(kid) && carrier.life().canRecognize(kid))
                .stream().min(Comparator.comparingDouble(carrier::distanceToSqr)).orElse(null);
    }

    /** The shared Walk to {@code at}: being there is arrival, and a route that moved the carrier nowhere is not tried again unchanged. */
    private boolean walk(Vec3 at, int taskTicks, String failure) {
        var motor = carrier.motor();
        if (carrier.position().distanceToSqr(at) < .0625) return true;
        boolean same = walkTo != null && at.distanceToSqr(walkTo) < .0625;
        if (same && motor.current() instanceof Walk && motor.owner() == owner) return true;
        if (same && walkFrom != null && carrier.position().distanceToSqr(walkFrom) < .0001) { giveUp = failure; return false; }
        if (same && taskTicks % 10 != 1) return true;
        walkFrom = carrier.position();
        walkTo = at;
        motor.run(owner, new Walk(at));
        return true;
    }

    /** Where this child would be let go, with its carrier facing the given yaw. */
    public static Vec3 letGoPoint(YukkuriView carrier, YukkuriView child, float yaw) {
        var e = entry(carrier, child.getUUID());
        if (e.contains("NX")) return nestSpot(carrier, child);
        if (e.getBoolean("HeadTo")) return headWorld(carrier, new Vec3(e.getFloat("DX"), e.getFloat("DY"), e.getFloat("DZ")), 1);
        if (e.contains("DX")) return carryHost(carrier, child).frame().toWorld(new Vec3(e.getFloat("DX"), e.getFloat("DY"), e.getFloat("DZ")));
        return world(carrier, 1, ground(carrier, child, hold(carrier, child)), yaw);
    }
    public static Vec3 letGoPoint(YukkuriEntity carrier, YukkuriEntity child) {
        return letGoPoint(carrier, child, carrier.bodyYaw());
    }

    /** Natural ground endpoint relative to the carrier, independent of a selected nest cushion. */
    public static Vec3 groundPoint(YukkuriView carrier, YukkuriView child, float yaw) {
        return world(carrier, 1, ground(carrier, child, hold(carrier, child)), yaw);
    }

    private static Vec3 brimLower(YukkuriView carrier, YukkuriView child, float partial, float down, Vec3 origin, float facing, Vec3 endpoint) {
        var from = vector(hold(carrier, child) == Hold.HEAD ? headPoint(carrier, child, partial) : brimPoint(carrier, child, partial));
        var to = vector(modelAt(carrier, facing, endpoint.subtract(origin).add(carrier.position())));
        var root = ContactGeometry.braidRootModel(false);
        int start = BraidFetch.marker(Grab.BRAID_LIFT, "lift_start"), place = BraidFetch.marker(Grab.BRAID_LIFT, "place") - start;
        var p = CarryGeometry.transfer(from, to, new Vec(root.x, root.y, root.z), CarryGeometry.BODY_LOW, CarryGeometry.BODY_HIGH,
                headwearRadius(child) * 16 / size(carrier), from.y() + child.getBbHeight() * 16 / size(carrier), down * place,
                BraidFetch.marker(Grab.BRAID_LIFT, "lift_end") - start, BraidFetch.marker(Grab.BRAID_LIFT, "over_end") - start, place);
        return world(carrier, partial, new Vector3f((float) p.x(), (float) p.y(), (float) p.z()), facing).subtract(carrier.position()).add(origin);
    }

    /** Release checks exactly the same exterior sweep that the rider and braid follow. */
    public static boolean clearLowering(YukkuriEntity carrier, YukkuriEntity child, Vec3 stand, float facing, Vec3 endpoint) {
        var body = carrier.getBoundingBox().move(stand.subtract(carrier.position()));
        for (int i = 1; i <= WAY_SAMPLES; i++) {
            var at = brimLower(carrier, child, 1, i / (float) WAY_SAMPLES, stand, facing, endpoint);
            var box = child.getDimensions(child.getPose()).makeBoundingBox(at);
            if (box.intersects(body) || child.level().getBlockCollisions(child, box).iterator().hasNext()) return false;
        }
        return true;
    }

    public static Vec3 mouthAt(YukkuriEntity carrier, YukkuriEntity child, float yaw) {
        return pinnedAt(carrier, child, worldAt(carrier, yaw, mouthSeat(carrier, child)));
    }

    /** Release starts lowering from the hold to the ground; a braid-tip hold already there opens over its clip markers. */
    public static void lower(YukkuriEntity carrier, YukkuriEntity child) { lower(carrier, child, null); }
    public static void lower(YukkuriEntity carrier, YukkuriEntity child, SeatTarget destination) {
        var e = entry(carrier, child.getUUID());
        if (e.contains("D") && destination == null) return;
        var brim = brimLocal(carrier, child);
        var next = e.copy();
        int ticks;
        if (destination != null || holdOf(e).reach() == Reach.BRAID) {
            var from = modelAt(carrier, carrier.bodyYaw(), child.position());
            if (destination != null) {
                next.putInt("S", destination.host().entity().getId());
                next.putFloat("DX", (float) destination.local().x); next.putFloat("DY", (float) destination.local().y); next.putFloat("DZ", (float) destination.local().z);
                next.putBoolean("HeadTo", destination.kind() == SeatTarget.Kind.HEAD);
            } else {
                var at = letGoPoint(carrier, child);
                next.putDouble("NX", at.x); next.putDouble("NY", at.y); next.putDouble("NZ", at.z);
            }
            next.putFloat("LX", (float) from.x); next.putFloat("LY", (float) from.y); next.putFloat("LZ", (float) from.z);
            String clip = destination != null ? destination.kind() == SeatTarget.Kind.HEAD ? "braid_head_set" : BraidFetch.HOLD
                    : holdOf(e) == Hold.HEAD ? "braid_head_take" : Grab.BRAID_LIFT;
            next.putString("LC", clip);
            next.putByte("H", (byte) Hold.BRAID.ordinal());
            ticks = BraidFetch.marker(clip, "place") - BraidFetch.marker(clip, "lift_start") + BraidFetch.marker(BraidFetch.SETTLE, "release");
        } else if (holdOf(e) != Hold.MOUTH) {
            ticks = lowerTicks();
        } else if (multi(carrier)) {
            // Reimu: the baby slides out to her lips by her release clip's release marker and is on the ground by its closed marker;
            // Release holds the beat to the clip's end.
            ticks = ClipMarkers.of(MOUTH_RELEASE).ticks("closed", 26);
        } else {
            ticks = CarryGeometry.motionTicks(child.position().distanceTo(letGoPoint(carrier, child)), BodyAbility.of(carrier).walkSpeed());
        }
        next.putLong("D", carrier.level().getGameTime());
        if (holdOf(e) == Hold.BRIM) {
            var grip = letGoPoint(carrier, child).add(0, GRIP_Y * size(child) / 16, 0);
            next.putBoolean("L", ContactGeometry.braidGapFrom(carrier, carrier.position(), grip, false) > 0);
        }
        next.putInt("N", ticks);
        write(carrier, child.getUUID(), next);
        GameplayLog.event(carrier, "family", "carry lowering hold={} child={} from={} ticks={}{} clip={}", holdOf(e).key(), child.getUUID(),
                GameplayLog.at(child.position()), ticks, brim, next.getString("LC"));
    }
    public static String lowerClip(YukkuriView carrier, YukkuriView child) { return entry(carrier, child.getUUID()).getString("LC"); }

    private static Vec3 transferPoint(YukkuriView carrier, YukkuriView child, CompoundTag e, Vec3 destination, double tick, float partial) {
        return transferPoint(carrier, child, e, destination, tick, partial, yaw(carrier, partial));
    }
    private static Vec3 transferPoint(YukkuriView carrier, YukkuriView child, CompoundTag e, Vec3 destination, double tick, float partial, float facing) {
        var from = new Vec(e.getFloat("LX"), e.getFloat("LY"), e.getFloat("LZ"));
        var to = vector(modelAt(carrier, facing, destination));
        var root = ContactGeometry.braidRootModel(e.getBoolean("L"));
        double height = Math.max(from.y(), to.y() + child.getBbHeight() * 16 / size(carrier));
        String clip = e.getString("LC");
        int start = BraidFetch.marker(clip, "lift_start");
        var at = CarryGeometry.transfer(from, to, new Vec(root.x, root.y, root.z), CarryGeometry.BODY_LOW, CarryGeometry.BODY_HIGH,
                headwearRadius(child) * 16 / size(carrier) + .3, height, tick,
                BraidFetch.marker(clip, "lift_end") - start, BraidFetch.marker(clip, "over_end") - start, BraidFetch.marker(clip, "place") - start);
        return world(carrier, partial, new Vector3f((float) at.x(), (float) at.y(), (float) at.z()), facing);
    }
    public static boolean transferClear(YukkuriEntity carrier, YukkuriEntity child, SeatTarget target, float facing) {
        var e = new CompoundTag();
        var from = modelAt(carrier, carrier.bodyYaw(), child.position());
        e.putFloat("LX", from.x); e.putFloat("LY", from.y); e.putFloat("LZ", from.z);
        e.putBoolean("L", braidLean(carrier, child));
        String clip = target.kind() == SeatTarget.Kind.HEAD ? "braid_head_set" : BraidFetch.HOLD;
        e.putString("LC", clip);
        int ticks = BraidFetch.marker(clip, "place") - BraidFetch.marker(clip, "lift_start");
        var previous = child.position();
        for (int tick = 0; tick <= ticks; tick++) {
            var at = transferPoint(carrier, child, e, target.world(), tick, 1, facing);
            var box = child.getDimensions(child.getPose()).makeBoundingBox(at);
            var grip = at.add(0, GRIP_Y * size(child) / 16, 0);
            if (!CarryGeometry.clearsBody(vector(modelAt(carrier, facing, at)), child.getBbWidth() * 8 / size(carrier),
                    child.getBbHeight() * 16 / size(carrier))
                    || child.level().getBlockCollisions(child, box.expandTowards(previous.subtract(at))).iterator().hasNext()
                    || transferGap(carrier, grip, facing) > 0) return false;
            previous = at;
        }
        return true;
    }
    /** The actual braid-side roots at this yaw, without turning a hypothetical root toward the destination. */
    public static double transferGap(YukkuriEntity carrier, Vec3 grip, float facing) {
        var point = modelAt(carrier, facing, grip);
        var at = new Vec3(point.x, point.y, point.z);
        return (Math.min(at.distanceTo(ContactGeometry.braidRootModel(false)), at.distanceTo(ContactGeometry.braidRootModel(true)))
                - ContactGeometry.braidLength()) * size(carrier) / 16;
    }
    /** Presentation observes actual arrival before the authored opening beat. */
    public static float braidShare(YukkuriView carrier, YukkuriView child, float partial) {
        var e = entry(carrier, child.getUUID());
        float letGo = letGoShare(carrier, e, partial);
        if (letGo >= 0) return letGo;
        if (!e.contains("D")) return 1;
        if (!e.contains("LC")) return 1 - ease(Math.max(0, lowering(carrier, child, partial)));
        if (child.position().distanceTo(letGoPoint(carrier, child, yaw(carrier, partial))) > .02) return 1;
        int place = BraidFetch.marker(e.getString("LC"), "place") - BraidFetch.marker(e.getString("LC"), "lift_start");
        return 1 - ease((carrier.level().getGameTime() - e.getLong("D") + partial - place) / BraidFetch.marker(BraidFetch.SETTLE, "release"));
    }

    /** Keep the child at its actual height and carrier-relative offset when lowering has no destination left. */
    public static void stopLowering(YukkuriEntity carrier, YukkuriEntity child) {
        var e = entry(carrier, child.getUUID());
        if (!e.contains("LX")) return;
        var next = e.copy();
        var at = modelAt(carrier, carrier.bodyYaw(), child.position());
        next.putFloat("X", at.x); next.putFloat("Z", at.z); next.putFloat("PH", at.y);
        next.remove("D"); next.remove("T"); next.remove("LX"); next.remove("LY"); next.remove("LZ"); next.remove("LV");
        next.remove("RY");
        next.remove("DX"); next.remove("DY"); next.remove("DZ");
        next.remove("LC"); next.remove("HeadTo"); next.remove("NX"); next.remove("NY"); next.remove("NZ");
        write(carrier, child.getUUID(), next);
        GameplayLog.event(carrier, "family", "carry lowering stopped child={} at={}", child.getUUID(), child.position());
    }

    /** Every dismount from a carrier ends here (the Release action, the interrupt bridge, an ended intent): the record goes at once. */
    public static void released(YukkuriEntity carrier, YukkuriEntity child, String cause) { carrier.family().carry().letGone(child, cause); }

    /** Most tilt (degrees, pitch plus roll) a carried body leans within. */
    public static final float HOLD_TILT = 12;

    /** The young on her head lose their hold and fall with {@code velocity}: a real dismount, then the normal escape or rescue. */
    public static void shedHead(YukkuriEntity carrier, Vec3 velocity, String cause) {
        for (var child : carrier.family().carry().riders()) {
            if (hold(carrier, child) != Hold.HEAD) continue;
            released(carrier, child, cause);
            Motor.dismount(child, child.position(), velocity, cause);
            GameplayLog.event(carrier, "family", "head rider shed child={} cause={} velocity={}", child.getUUID(), cause, GameplayLog.at(velocity));
        }
    }

    public static void gripped(YukkuriEntity carrier, YukkuriEntity child, String rejected, int travelTicks) {
        var choices = carrier.family().carry().holdChoices;
        choices.keySet().removeIf(id -> !carrier.hasPassengerId(id));
        choices.put(child.getUUID(), rejected);
        GameplayLog.event(carrier, "family", "carry grip child={} uuid={} travelTicks={} {}", child.getId(), child.getUUID(), travelTicks,
                carryReadout(carrier, child, letGoPoint(carrier, child), rejected));
    }

    private static String carryReadout(YukkuriEntity carrier, YukkuriEntity child, Vec3 endpoint, String rejected) {
        double yaw = Math.toRadians(carrier.bodyYaw()), dx = endpoint.x - carrier.getX(), dz = endpoint.z - carrier.getZ();
        return String.format(Locale.ROOT, "hold=%s parent=(%.3f,%.3f,%.3f) yaw=%.2f entityYaw=%.2f endpoint=(%.3f,%.3f,%.3f) forward=%.3f right=%.3f rejected=%s%s",
                hold(carrier, child).key(), carrier.getX(), carrier.getY(), carrier.getZ(), carrier.bodyYaw(), carrier.getYRot(),
                endpoint.x, endpoint.y, endpoint.z, -dx * Math.sin(yaw) + dz * Math.cos(yaw), -dx * Math.cos(yaw) - dz * Math.sin(yaw), rejected,
                brimLocal(carrier, child));
    }

    private static String brimLocal(YukkuriEntity carrier, YukkuriEntity child) {
        if (hold(carrier, child) != Hold.BRIM) return "";
        var seat = brimSeat(carrier, child);
        return String.format(Locale.ROOT, " brimLocal=(%.3f,%.3f,%.3f)", seat[0], seat[1], seat[2]);
    }

    /** The bookkeeping of one let-go: the hold record, the re-lift pause, the log, and the end of the carry when it was the last. */
    private void letGone(YukkuriEntity child, String cause) {
        var hold = hold(carrier, child);
        String readout = carryReadout(carrier, child, child.position(), holdChoices.remove(child.getUUID()));
        if (hold == Hold.BRAID) {
            float chosenYaw = entry(carrier, child.getUUID()).getFloat("Y"), delta = Mth.wrapDegrees(carrier.bodyYaw() - chosenYaw);
            readout += Math.abs(delta) > 1 ? String.format(Locale.ROOT, " turned=true chosenYaw=%.2f yawDelta=%.2f", chosenYaw, delta) : " turned=false";
        }
        write(carrier, child.getUUID(), null);
        boolean ours = settingDown != null && settingDown.child() == child;
        String reason = ours ? settingWhy : cause;
        if (ours) { settingDown = null; settingWhy = null; }
        if (child.getUUID().equals(lifting)) lifting = null;
        child.fallDistance = 0;
        long now = carrier.level().getGameTime();
        pauses.put(child.getUUID(), new Pause(now + (reason.equals("feeding") ? FEED_HOLD_TICKS : HOLD_TICKS), reason));
        GameplayLog.event(carrier, "family", "carry put down reason={} hold={} cause={} purpose={} child={} at={} carriedTicks={} nutrition={} remaining={} nest={} {}",
                reason, hold.key(), cause, purpose, child.getUUID(), GameplayLog.at(child.position()), now - since, child.life().nutrition(),
                carrier.getPassengers().size(), FamilyNest.bedOccupancy(child, child.family().carryHome()), readout);
        if (!carrying()) {
            purpose = null;
            giveUp = null;
            gathering = null;
            if (carrier.motor().current() instanceof Walk && carrier.motor().owner() == owner) carrier.motor().cancel(owner, "carry_ended");
            carrier.family().endCarry();
        }
    }

    /**
     * One accepted hit on this carrying parent (project design, user 2026-11-04): one draw, at most one young shaken loose. The
     * chance is the hit's share of her max health, scaled down by her self-control; the last one aboard goes through the shared
     * Release where it hangs and falls to its real support. Command and other interruptions keep their own meaning.
     */
    public void struck(float loss) {
        var all = ours();
        if (all.isEmpty()) return;
        int control = carrier.life().personality().selfControl();
        float chance = Math.clamp(loss / carrier.getMaxHealth(), 0, 1) * (1 - control / 100f), roll = carrier.getRandom().nextFloat();
        var child = all.get(all.size() - 1);
        boolean dropped = roll < chance;
        GameplayLog.event(carrier, "family", "carry hit drop chance={} roll={} loss={} selfControl={} dropped={} child={} hold={} aboard={}",
                chance, roll, loss, control, dropped, child.getUUID(), hold(carrier, child).key(), all.size());
        if (dropped) Release.now(carrier, child, true, "hit");
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
            var next = due();
            var ids = new StringBuilder();
            for (var child : all) ids.append(ids.length() == 0 ? "" : ",").append(child.getUUID()).append('/').append(hold(carrier, child).key());
            return "carrying count=" + all.size() + " children=" + ids + " purpose=" + purpose + " ticks="
                    + (carrier.level().getGameTime() - since) + " landed=" + carrier.settlement().migrationLanded()
                    + (next == null ? "" : " due=" + next.getValue() + "/" + next.getKey().getUUID())
                    + (settingDown == null ? "" : " setting_down=" + settingDown.child().getUUID() + "(" + settingWhy + ")");
        }
        if (carrier.getVehicle() instanceof YukkuriEntity parent && riding(parent, carrier))
            return "carried_by=" + parent.getUUID() + " hold=" + hold(parent, carrier).key();
        return "none pauses=" + pauses.size();
    }
}
