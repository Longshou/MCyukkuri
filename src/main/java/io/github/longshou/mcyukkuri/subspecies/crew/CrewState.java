package io.github.longshou.mcyukkuri.subspecies.crew;

import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity;
import io.github.longshou.mcyukkuri.subspecies.HatVessel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * The one transfer record a hull synchronizes (spec: {@code CrewState}): for every transfer touching the hull, its id, mode, body
 * and carrier, the anchor hulls, the curve (knots in the frame each belongs to, so a moving hull carries the body with it),
 * the clock, the phase, the contact kind with its target and lifted feet in the body's frame (this tick and the previous one),
 * and the body's synced yaw. Server and client evaluate the same {@link Entry#position} from it; lifted feet and contact are
 * drawn from their paired samples so ordinary entity interpolation cannot separate the grip.
 *
 * <p>The server side keeps the live tickets ({@link CrewTransfer}) and publishes their entries; the client only parses.
 */
public final class CrewState {
    /** A knot of a curve: frame 0 is the world (blocks), 1 the first hull and 2 the second (hull-local hat units). */
    public record Knot(int frame, double x, double y, double z) {
        public Vec3 vec() { return new Vec3(x, y, z); }
    }

    /** What a hull publishes about one transfer. */
    public record Entry(long id, CrewTransfer.Mode mode, CrewTransfer.Phase phase, int bodyId, UUID body, int carrierId, UUID carrier,
            int boatA, int boatB, boolean arc, List<Knot> knots, List<Double> cumulative, String curve, String entry, int slot,
            long phaseStart, long moveStart, int moveTicks, long revertAt, double revertFrom,
            CrewTransfer.Contact contact, int contactFrame, Vec3 contactAt, Vec3 contactPrev, Vec3 bodyAt, Vec3 bodyPrev, float bodyYaw, float prevBodyYaw,
            boolean degraded) {

        public boolean moving() { return phase == CrewTransfer.Phase.MOVE; }
        public Vec3 contactAt() { return contactAt; }
        public boolean carries(UUID id) { return id.equals(body) || id.equals(carrier); }

        /** Progress along the curve at a game time (fractional); forward from the move start, back along it once reverting. */
        public double progress(double now) {
            if (phase.ordinal() < CrewTransfer.Phase.MOVE.ordinal()) return 0;
            if (revertAt >= 0) return Math.max(0, revertFrom - (now - revertAt) / Math.max(1, moveTicks));
            if (phase.ordinal() > CrewTransfer.Phase.MOVE.ordinal()) return 1;
            return Math.clamp((now - moveStart) / Math.max(1, moveTicks), 0, 1);
        }

        /** Frame (0 world, 1/2 a hull) of the knot position that carries the body at this progress. */
        public int frameAt(double u) {
            if (arc) return u < .5 ? knots.getFirst().frame() : knots.getLast().frame();
            double e = ease(u);
            for (int i = 0; i + 1 < knots.size(); i++) {
                double a = cumulative.get(i), b = cumulative.get(i + 1);
                if (e <= b || i + 2 == knots.size()) {
                    var k0 = knots.get(i);
                    var k1 = knots.get(i + 1);
                    if (k0.frame() == k1.frame()) return k0.frame();
                    return e < (a + b) / 2 ? k0.frame() : k1.frame();
                }
            }
            return knots.getFirst().frame();
        }

        /** The hull entity id whose frame carries the body at this progress, or -1 for the world. */
        public int vehicleAt(double u) {
            int frame = frameAt(u);
            return frame == 1 ? boatA : frame == 2 ? boatB : -1;
        }

        /** The body's world position at this progress, or null when a hull of the curve is not loaded. */
        public Vec3 position(Level level, double now) {
            double u = progress(now);
            if (arc) {
                var from = world(level, knots.getFirst());
                var to = world(level, knots.getLast());
                if (from == null || to == null) return null;
                return HatVessel.arc(from, to, moveTicks, Math.clamp(u, 0, 1) * moveTicks);
            }
            double e = ease(u);
            for (int i = 0; i + 1 < knots.size(); i++) {
                double a = cumulative.get(i), b = cumulative.get(i + 1);
                if (e <= b || i + 2 == knots.size()) {
                    var p0 = world(level, knots.get(i));
                    var p1 = world(level, knots.get(i + 1));
                    if (p0 == null || p1 == null) return null;
                    return p0.lerp(p1, b - a < 1e-9 ? 1 : Math.clamp((e - a) / (b - a), 0, 1));
                }
            }
            return world(level, knots.getFirst());
        }

        public Vec3 world(Level level, Knot knot) {
            if (knot.frame() == 0) return knot.vec();
            int id = knot.frame() == 1 ? boatA : boatB;
            return level.getEntity(id) instanceof HatBoatEntity boat ? boat.toWorld(knot.vec()) : null;
        }

        /** The contact target in the world now (the body's hull frame moves it with the hull). */
        public Vec3 contactWorld(Level level, boolean previous) {
            var at = previous ? contactPrev : contactAt;
            if (at == null) return null;
            if (contactFrame == 0) return at;
            int id = contactFrame == 1 ? boatA : boatB;
            return level.getEntity(id) instanceof HatBoatEntity boat ? boat.toWorld(at) : null;
        }

        /** The measured feet, independently of a paused or reversed planning clock. */
        public Vec3 bodyWorld(Level level, boolean previous) {
            var at = previous ? bodyPrev : bodyAt;
            if (at == null || contactFrame == 0) return at;
            int id = contactFrame == 1 ? boatA : boatB;
            return level.getEntity(id) instanceof HatBoatEntity boat ? boat.toWorld(at) : null;
        }

        public CompoundTag save() {
            var tag = new CompoundTag();
            tag.putLong("Id", id);
            tag.putString("Mode", mode.name());
            tag.putString("Phase", phase.name());
            tag.putInt("BodyId", bodyId);
            tag.putUUID("Body", body);
            if (carrier != null) { tag.putInt("CarrierId", carrierId); tag.putUUID("Carrier", carrier); }
            tag.putInt("BoatA", boatA);
            tag.putInt("BoatB", boatB);
            tag.putBoolean("Arc", arc);
            var list = new ListTag();
            for (int i = 0; i < knots.size(); i++) {
                var k = new CompoundTag();
                k.putInt("F", knots.get(i).frame());
                k.putDouble("X", knots.get(i).x());
                k.putDouble("Y", knots.get(i).y());
                k.putDouble("Z", knots.get(i).z());
                k.putDouble("C", cumulative.get(i));
                list.add(k);
            }
            tag.put("Knots", list);
            tag.putString("Curve", curve);
            tag.putString("Entry", entry);
            tag.putInt("Slot", slot);
            tag.putLong("PhaseStart", phaseStart);
            tag.putLong("MoveStart", moveStart);
            tag.putInt("MoveTicks", moveTicks);
            tag.putLong("RevertAt", revertAt);
            tag.putDouble("RevertFrom", revertFrom);
            tag.putString("Contact", contact.name());
            tag.putInt("ContactFrame", contactFrame);
            if (contactAt != null) point(tag, "At", contactAt);
            if (contactPrev != null) point(tag, "Prev", contactPrev);
            if (bodyAt != null) point(tag, "BodyAt", bodyAt);
            if (bodyPrev != null) point(tag, "BodyPrev", bodyPrev);
            tag.putFloat("BodyYaw", bodyYaw);
            tag.putFloat("PrevBodyYaw", prevBodyYaw);
            tag.putBoolean("Degraded", degraded);
            return tag;
        }

        static Entry read(CompoundTag tag) {
            var knots = new ArrayList<Knot>();
            var cumulative = new ArrayList<Double>();
            for (var raw : tag.getList("Knots", Tag.TAG_COMPOUND)) {
                var k = (CompoundTag) raw;
                knots.add(new Knot(k.getInt("F"), k.getDouble("X"), k.getDouble("Y"), k.getDouble("Z")));
                cumulative.add(k.getDouble("C"));
            }
            return new Entry(tag.getLong("Id"), CrewTransfer.Mode.valueOf(tag.getString("Mode")), CrewTransfer.Phase.valueOf(tag.getString("Phase")),
                    tag.getInt("BodyId"), tag.getUUID("Body"), tag.hasUUID("Carrier") ? tag.getInt("CarrierId") : -1,
                    tag.hasUUID("Carrier") ? tag.getUUID("Carrier") : null, tag.getInt("BoatA"), tag.getInt("BoatB"), tag.getBoolean("Arc"),
                    List.copyOf(knots), List.copyOf(cumulative), tag.getString("Curve"), tag.getString("Entry"), tag.getInt("Slot"),
                    tag.getLong("PhaseStart"), tag.getLong("MoveStart"), tag.getInt("MoveTicks"), tag.getLong("RevertAt"), tag.getDouble("RevertFrom"),
                    CrewTransfer.Contact.valueOf(tag.getString("Contact")), tag.getInt("ContactFrame"),
                    tag.contains("AtX") ? point(tag, "At") : null, tag.contains("PrevX") ? point(tag, "Prev") : null,
                    tag.contains("BodyAtX") ? point(tag, "BodyAt") : null, tag.contains("BodyPrevX") ? point(tag, "BodyPrev") : null,
                    tag.getFloat("BodyYaw"), tag.getFloat("PrevBodyYaw"), tag.getBoolean("Degraded"));
        }
    }

    /** Whole-curve easing: the body starts and ends at rest. */
    public static double ease(double u) {
        double q = Math.clamp(u, 0, 1);
        return q * q * (3 - 2 * q);
    }

    private static void point(CompoundTag tag, String key, Vec3 at) {
        tag.putDouble(key + "X", at.x);
        tag.putDouble(key + "Y", at.y);
        tag.putDouble(key + "Z", at.z);
    }
    private static Vec3 point(CompoundTag tag, String key) {
        return new Vec3(tag.getDouble(key + "X"), tag.getDouble(key + "Y"), tag.getDouble(key + "Z"));
    }

    private final Map<Long, CrewTransfer> tickets = new LinkedHashMap<>();
    private List<Entry> entries = List.of();
    private CompoundTag parsedFrom;

    // ---------------------------------------------------------------- server: the live tickets
    public void attach(CrewTransfer ticket) { tickets.put(ticket.id(), ticket); }
    public void detach(long id) { tickets.remove(id); }
    public boolean busy() { return !tickets.isEmpty(); }
    /** The body or the carrier of a live transfer on this hull. */
    public boolean involves(UUID id) {
        for (var t : tickets.values()) if (id.equals(t.bodyId()) || id.equals(t.carrierId())) return true;
        return false;
    }
    /** End every transfer on this hull (it capsizes, is retired or removed): bodies are left where they physically are. */
    public void abortAll(String reason) {
        for (var t : List.copyOf(tickets.values())) t.cancel(reason);
    }
    /** A ticket nobody has driven for a while (its goals were displaced) is ended; called once per hull tick. */
    public void reap(long now) {
        for (var t : List.copyOf(tickets.values())) if (t.stale(now)) t.cancel("goal_lost");
    }
    public List<CrewTransfer> tickets() { return List.copyOf(tickets.values()); }
    /** The ticket holding this hull's single MOVE right now, or null. */
    public CrewTransfer mover() {
        for (var t : tickets.values()) if (t.holdsMove()) return t;
        return null;
    }

    /** The synced form: every ticket past CLAIM, as entries. */
    public CompoundTag publish() {
        var tag = new CompoundTag();
        var list = new ListTag();
        for (var t : tickets.values()) if (t.phase() != CrewTransfer.Phase.CLAIM) list.add(t.entry().save());
        tag.put("T", list);
        return tag;
    }

    // ---------------------------------------------------------------- both sides: the published entries
    /** Client: parse once per received tag. Server: the entries of the live tickets. */
    public List<Entry> entries(CompoundTag synced, boolean server) {
        if (server) {
            var out = new ArrayList<Entry>();
            for (var t : tickets.values()) if (t.phase() != CrewTransfer.Phase.CLAIM) out.add(t.entry());
            return out;
        }
        if (synced != parsedFrom) {
            var out = new ArrayList<Entry>();
            for (var raw : synced.getList("T", Tag.TAG_COMPOUND)) {
                try { out.add(Entry.read((CompoundTag) raw)); } catch (IllegalArgumentException invalid) { /* an unknown mode: ignore the entry */ }
            }
            entries = List.copyOf(out);
            parsedFrom = synced;
        }
        return entries;
    }

    public static Entry entryFor(List<Entry> entries, UUID id) {
        for (var e : entries) if (e.carries(id)) return e;
        return null;
    }
    public static Entry movingBody(List<Entry> entries, UUID id) {
        for (var e : entries) if (e.moving() && e.body().equals(id)) return e;
        return null;
    }
}
