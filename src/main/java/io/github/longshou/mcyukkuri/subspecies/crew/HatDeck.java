package io.github.longshou.mcyukkuri.subspecies.crew;

import io.github.longshou.mcyukkuri.subspecies.crew.DeckLayout.Kind;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.phys.Vec3;
import java.util.function.IntConsumer;
import java.util.Arrays;
import java.util.Locale;

/**
 * The one space authority of a hull. Seats come only from the exported {@link DeckLayout}; every temporary need is a
 * {@link Lease}; admission is "the load fits AND a legal seat exists" decided by a small exhaustive search over the
 * precomputed conflict table. The deck never moves a body that was not asked to move, never touches food, and never
 * invents space: making room is an explicit decision-layer call ({@link #planRoom}) that the entity carries out.
 *
 * <p>Load only decides the waterline and stability; space is decided by seats. Bodies are plain {@link Body} values,
 * so nothing here depends on entities, levels or the client.
 */
public final class HatDeck {
    /** The hull facts the deck needs; implemented by the entity. */
    public interface Host {
        DeckLayout layout();
        long now();
        boolean night();
        /** False while the hull is capsizing, capsized, retired or being launched: no admission is possible. */
        boolean stable();
        double portions();
        boolean paddleAboard();
        /** True while the stick lies on its stowage (not in the captain's grip), where it blocks the seats it overlaps. */
        boolean paddleStowed();
        double extraLoad();
        Hop hop(UUID body, Vec3 fromLocal, Vec3 toLocal);
        /** The body's actual feet reached this local point after swept movement. */
        boolean arrived(UUID body, Vec3 localTarget);
        /** Release a failed mover at its actual position; ordinary gravity and water take over. */
        void moveFailed(UUID body, String reason);
        void event(String message, Object... values);
        void limited(String key, int ticks, String message, Object... values);
    }

    /** A short trip inside the hull: a level walk along seat positions, or a hop. Times are game ticks. */
    public record Move(Vec3 from, long start, int ticks, boolean arc, List<Vec3> route) {
        public Move { route = List.copyOf(route); }
        public boolean active(long now) { return now - start < ticks; }
    }
    /** Already bounded by the body's current ability; all curve points are in this hull's frame. */
    public record Hop(int ticks, List<Vec3> route) {
        public Hop { route = List.copyOf(route); }
    }

    /** One body's claim on the deck. Read it through the accessors; only the deck changes it. */
    public static final class Placement {
        private final UUID id;
        private BodyClass cls;
        private boolean jumps, aboard, overflow, returning, carried;
        private long seatedAt;
        private double extra;
        private int slot = DeckLayout.NONE, target = DeckLayout.NONE, home = DeckLayout.NONE;
        private Lease lease;
        private UUID caregiver, pendingCaregiver;
        private Vec3 free;
        private Move move;

        private Placement(Body body) { id = body.id(); update(body); }
        private void update(Body body) { cls = body.cls(); jumps = body.jumps(); extra = body.extraLoad(); }
        public UUID id() { return id; }
        public BodyClass cls() { return cls; }
        public boolean aboard() { return aboard; }
        public boolean captain() { return cls.captain(); }
        public int slot() { return slot; }
        public int target() { return target; }
        public int home() { return home; }
        public Lease lease() { return lease; }
        public UUID caregiver() { return caregiver; }
        public boolean overflow() { return overflow; }
        public boolean returning() { return returning; }
        public Move move() { return move; }
        /** The seat this body occupies or is bound for: the destination while moving or reserved, else where it sits. */
        public int seat() { return target >= 0 ? target : slot; }
        private boolean retained() { return lease != null && lease.purpose() == Purpose.RETAINED; }
        /** Every seat this body presses on right now (for conflicts). */
        private void physical(IntConsumer out) {
            if (aboard && slot >= 0 && !carried) out.accept(slot);
            if (target >= 0) out.accept(target);
            if (!aboard && retained() && home >= 0) out.accept(home);
        }
        private boolean holds(int seat) { return aboard && slot == seat || target == seat || home == seat; }
        private double load(DeckLayout layout) { return layout.classLoad(cls) + extra; }
    }

    /** What a seat is doing: nobody's; promised (a lease, or a body not yet aboard); held by a body; or part of a walk. */
    public enum State { FREE, RESERVED, OCCUPIED, MOVING }

    public record Lift(UUID id, int from, int to) {}
    /** What a reflow did: bodies set walking or hopping, bodies that need a parent to lift them, bodies left without a legal seat. */
    public record Tend(List<UUID> moved, List<Lift> lifts, List<UUID> stuck) {}
    /** A care station: the captain's seat and the young's seat of one authored pairing. */
    public record CarePlan(int captainSlot, int youngSlot, DeckLayout.Care care) {}
    /** A way to seat a newcomer by moving other bodies first: each move is (body, destination seat). */
    public record RoomPlan(int slot, Map<UUID, Integer> moves) {}

    public static final int WALK_MIN_TICKS = 16, MOVE_SLACK_TICKS = 100, REST_ON_STATION_TICKS = 1200;
    public static final double WALK_SPEED = .20;
    /** Search bounds: the seat table is large (127 seats), so each need only tries its best-ranked seats and the whole search has a node budget. */
    private static final int SEARCH_BUDGET = 20000, MAX_DISPLACED = 3, SEARCH_CANDIDATES = 16;
    public static final long BOARD_TTL = 600, SERVICE_TTL = 1600, REFUGE_TTL = 240, OWN_TTL = 240;

    private final Host host;
    private DeckLayout layout;
    private final LinkedHashMap<UUID, Placement> placements = new LinkedHashMap<>();
    private long nextLease = 1;
    private String lastRefusal = "none";
    private long lastRefusalAt = Long.MIN_VALUE;

    public HatDeck(Host host) { this.host = host; this.layout = host.layout(); }

    // ---------------------------------------------------------------- layout and load
    public DeckLayout layout() { ensureLayout(); return layout; }

    /** The hull mode can change when the owner's identity resolves; seats are rebound by id, unmatched ones are dropped. */
    private void ensureLayout() {
        var next = host.layout();
        if (next == layout) return;
        var old = layout;
        layout = next;
        for (var p : placements.values()) {
            p.slot = remap(old, p.slot);
            p.target = remap(old, p.target);
            p.home = remap(old, p.home);
            p.move = null;
            if (p.aboard && p.slot < 0) p.overflow = true;
        }
        host.event("deck layout changed mode={} seats={}", layout.mode, layout.slots().size());
    }
    private int remap(DeckLayout old, int slot) {
        return slot < 0 ? slot : layout.index(old.slot(slot).id());
    }

    /** Total load in units: provisions, the stick, every body aboard or promised a seat, and any hand-over stick. */
    public double load() {
        ensureLayout();
        double sum = host.portions() * layout.foodLoad + (host.paddleAboard() ? layout.paddleLoad : 0) + host.extraLoad();
        for (var p : placements.values()) sum += p.load(layout);
        return sum;
    }
    public double maxLoad() { return layout().maxLoad; }
    public double loadOf(Body body) { return layout().classLoad(body.cls()) + body.extraLoad(); }

    // ---------------------------------------------------------------- reading
    public Placement get(UUID id) { return placements.get(id); }
    public Collection<Placement> all() { return placements.values(); }
    public boolean aboard(UUID id) { var p = placements.get(id); return p != null && p.aboard; }
    public boolean holds(UUID id) { return placements.containsKey(id); }
    public boolean hasLease(UUID id, Purpose purpose) { var p = placements.get(id); return p != null && p.lease != null && p.lease.purpose() == purpose; }
    public Lease lease(UUID id) { var p = placements.get(id); return p == null ? null : p.lease; }
    public UUID caregiverOf(UUID id) { var p = placements.get(id); return p == null ? null : p.caregiver; }
    /** A refuge was booked for this body and its lease has not lapsed: it may board, but is not yet a ward. */
    public boolean refugeReserved(UUID id) {
        var p = placements.get(id);
        return p != null && p.pendingCaregiver != null && p.lease != null && !p.lease.lapsed(host.now());
    }
    public boolean moving(UUID id) { var p = placements.get(id); return p != null && p.move != null; }
    public Move moveOf(UUID id) { var p = placements.get(id); return p == null ? null : p.move; }
    public int seatOf(UUID id) { var p = placements.get(id); return p == null ? DeckLayout.NONE : p.seat(); }
    public DeckLayout.Slot slotOf(UUID id) { int s = seatOf(id); return s < 0 ? null : layout().slot(s); }
    /** Where this body sits or is going, in hull-local hat units, or null when it holds no seat. */
    public Vec3 positionOf(UUID id) {
        var p = placements.get(id);
        if (p == null) return null;
        int s;
        if (p.aboard) s = p.move != null && p.target >= 0 ? p.target : p.slot;
        else s = p.target >= 0 ? p.target : p.retained() ? p.home : DeckLayout.NONE;
        return s < 0 ? p.free : layout().slot(s).pos();
    }
    /** The seat's occupant or the body promised it, or null. */
    public UUID occupant(int slot) {
        for (var p : placements.values()) if (p.holds(slot)) return p.id;
        return null;
    }
    public boolean anyIncoming() {
        for (var p : placements.values()) if (isIncoming(p)) return true;
        return false;
    }
    public boolean incoming(UUID id) { var p = placements.get(id); return p != null && isIncoming(p); }
    private boolean isIncoming(Placement p) {
        return !p.aboard && p.lease != null && (p.lease.purpose() == Purpose.BOARD || p.lease.purpose() == Purpose.RESCUE);
    }
    public boolean anyOverflow() { for (var p : placements.values()) if (p.overflow) return true; return false; }
    public String lastRefusal() { return lastRefusal; }

    /** The readout used by the cargo scenario report: every body's seat, lease and move. */
    public String assignmentText() {
        var out = new StringBuilder();
        long now = host.now();
        for (var p : placements.values()) {
            if (!out.isEmpty()) out.append(' ');
            out.append(p.id.toString(), 0, 4).append(':').append(p.cls.id).append('@')
                    .append(p.slot >= 0 ? layout().slot(p.slot).id() : p.overflow ? "overflow" : "-");
            if (p.target >= 0) out.append("->").append(layout().slot(p.target).id());
            if (p.home >= 0) out.append("(home ").append(layout().slot(p.home).id()).append(')');
            if (p.lease != null) out.append('[').append(p.lease.purpose()).append(']');
            if (!p.aboard) out.append("{ashore}");
            if (p.move != null) out.append("{moving}");
        }
        return out.isEmpty() ? "empty" : out.toString();
    }

    // ---------------------------------------------------------------- the captain
    /** The captain's home seat: the stern seat, or the high stern seat from late pregnancy. */
    public int homeFor(BodyClass cls) {
        var layout = layout();
        for (var s : layout.slots()) if (s.kind() == Kind.CAPTAIN_HIGH && cls == BodyClass.GRAVID) return s.index();
        for (var s : layout.slots()) if (s.kind() == Kind.CAPTAIN) return s.index();
        return DeckLayout.NONE;
    }
    /** True if this hull has a seat made for a gravid captain. */
    public boolean hasGravidSeat() {
        for (var s : layout().slots()) if (s.allows(BodyClass.GRAVID)) return true;
        return false;
    }

    /**
     * Keep the captain's own seat reserved while she is away, so no child is seated where she could not return. Called by the
     * entity every few ticks with her current class; moves her home seat when the class changes.
     */
    public void setCaptain(Body captain) {
        ensureLayout();
        int home = homeFor(captain.cls());
        if (home < 0) return;
        var p = placements.get(captain.id());
        if (p == null) {
            p = new Placement(captain);
            p.home = home;
            p.target = home;
            placements.put(captain.id(), p);
            return;
        }
        boolean changed = p.cls != captain.cls();
        p.update(captain);
        if (!changed && p.home == home) return;
        p.home = home;
        if (!p.aboard) p.target = home;
        host.event("deck captain seat body={} class={} home={}", p.id, p.cls.id, layout.slot(home).id());
    }

    // ---------------------------------------------------------------- the rules of a seat
    /** Static rules only: class, purpose, night, jumping. Occupancy and conflicts are checked separately. */
    private boolean legal(Body b, Purpose p, DeckLayout.Slot s) {
        if (s.kind() == Kind.LARDER || !s.allows(b.cls())) return false;
        boolean seated = p == Purpose.BOARD || p == Purpose.RETAINED || p == Purpose.REFUGE;
        String tag = s.purpose();
        if (b.captain()) {
            boolean stern = s.kind() == Kind.CAPTAIN || s.kind() == Kind.CAPTAIN_HIGH;
            switch (p) {
                case BOARD, RETAINED -> { if (!stern) return false; }
                case SERVICE, OWN, RESCUE -> { if (!stern && s.kind() != Kind.SERVICE && s.kind() != Kind.TOILET_RIM) return false; }
                default -> { return false; }
            }
        } else {
            if (s.kind() == Kind.CAPTAIN || s.kind() == Kind.CAPTAIN_HIGH || s.kind() == Kind.SERVICE) return false;
            if (tag.equals("toilet") && p != Purpose.SERVICE) return false;
            if ((tag.equals("rescue") || s.leaseOnly()) && p != Purpose.RESCUE) return false;
            if (p == Purpose.OWN) return false;
        }
        if (seated && host.night() && !s.night()) return false;
        if (s.requiresJump() && p != Purpose.RESCUE && !b.jumps()) return false;
        return true;
    }

    private double rank(Body b, DeckLayout.Slot s, Vec3 near, int prefer) {
        if (s.index() == prefer) return -1e9;
        double r;
        if (b.captain()) {
            r = switch (s.kind()) { case CAPTAIN -> 0; case CAPTAIN_HIGH -> 1; case SERVICE -> 10 + s.index(); default -> 100 + s.index(); };
        } else r = host.night() ? s.nightRank() : s.dayRank();
        if (s.kind() == Kind.RESCUE_RIM) r += 100000;
        if (near != null) r = (s.kind() == Kind.RESCUE_RIM ? 1e6 : 0) + near.distanceTo(s.pos()) * 1000 + r * .01;
        return r;
    }

    private List<Integer> candidates(Body b, Purpose p, Vec3 near, int prefer) {
        var out = new ArrayList<Integer>();
        for (var s : layout.slots()) if (legal(b, p, s)) out.add(s.index());
        out.sort(Comparator.comparingDouble(i -> rank(b, layout.slot(i), near, prefer)));
        return out;
    }

    /** Which seat holds this one up: an occupant or promise on it, a conflicting body, the larder or the stick; null if free. */
    private String blocker(Body b, int slot, Set<UUID> ignore) {
        return blocker(b, slot, ignore, DeckLayout.tierFor((int) Math.ceil(host.portions())));
    }

    /** As {@link #blocker}, with the larder projected at a specified future portion count. */
    private String blockerAtPortions(Body b, int slot, Set<UUID> ignore, int portions) {
        return blocker(b, slot, ignore, DeckLayout.tierFor(portions));
    }

    private String blocker(Body b, int slot, Set<UUID> ignore, int larderTier) {
        for (var p : placements.values()) {
            if (p.id.equals(b.id()) || ignore.contains(p.id)) continue;
            if (p.holds(slot)) return layout.slot(slot).id();
            var found = new String[1];
            p.physical(s -> { if (found[0] == null && layout.conflict(slot, b.cls(), s, p.cls)) found[0] = layout.slot(s).id(); });
            if (found[0] != null) return found[0];
        }
        if (larderTier >= 0 && layout.larderExcludes(larderTier, slot, b.cls())) return "larder";
        if (host.paddleStowed() && layout.stowExcludes(slot, b.cls())) return "paddle_stow";
        return null;
    }

    // ---------------------------------------------------------------- admission
    public Admission canAdmit(Body body, Purpose purpose) { return canAdmit(body, purpose, null, DeckLayout.NONE); }

    /** Read only. {@code near} (hull-local, may be null) orders the seats by distance; {@code prefer} names a seat to try first. */
    public Admission canAdmit(Body b, Purpose purpose, Vec3 near, int prefer) {
        ensureLayout();
        var self = placements.get(b.id());
        double before = load(), after = before - (self == null ? 0 : self.load(layout)) + loadOf(b);
        if (!host.stable()) return Admission.no(Admission.Reason.HULL_UNSTABLE, "", before, after);
        if (!b.admitted() && !b.captain()) return Admission.no(Admission.Reason.NOT_ADMITTED, "", before, after);
        if (self != null && !self.aboard && self.target >= 0 && self.lease != null && self.lease.purpose() == purpose)
            return Admission.ok(self.target, layout.slot(self.target).id(), before, after);
        if (!b.captain() && after > layout.maxLoad + 1e-9) return Admission.no(Admission.Reason.UNITS_FULL, "", before, after);
        var list = candidates(b, purpose, near, prefer);
        if (list.isEmpty()) return Admission.no(Admission.Reason.NO_SLOT_FOR_CLASS, "", before, after);
        String first = null;
        for (int i : list) {
            String why = blocker(b, i, Set.of());
            if (why == null) return Admission.ok(i, layout.slot(i).id(), before, after);
            if (first == null) first = why;
        }
        return Admission.no(Admission.Reason.CONFLICT_WITH, first, before, after);
    }

    /** Read-only admission to one named seat; an available alternative never hides this seat's blocker. */
    public Admission canAdmitAt(Body b, Purpose purpose, int seat) {
        var admission = canAdmit(b, purpose, null, seat);
        if (!admission.ok() && admission.reason() != Admission.Reason.CONFLICT_WITH) return admission;
        if (seat < 0 || seat >= layout.slots().size() || !legal(b, purpose, layout.slot(seat)))
            return Admission.no(Admission.Reason.NO_SLOT_FOR_CLASS, "", admission.loadBefore(), admission.loadAfter());
        String why = blocker(b, seat, Set.of());
        return why == null ? Admission.ok(seat, layout.slot(seat).id(), admission.loadBefore(), admission.loadAfter())
                : Admission.no(Admission.Reason.CONFLICT_WITH, why, admission.loadBefore(), admission.loadAfter());
    }

    /** Seats that already-started real moves will clear. This promises no arrival and never frees an occupied seat. */
    public List<Integer> seatsAfterMoves(Body body, Purpose purpose, Vec3 near) {
        var admission = canAdmit(body, purpose, near, DeckLayout.NONE);
        if (!admission.ok() && admission.reason() != Admission.Reason.CONFLICT_WITH) return List.of();
        var out = new ArrayList<Integer>();
        for (int seat : candidates(body, purpose, near, DeckLayout.NONE)) {
            var movingAway = new HashSet<UUID>();
            for (var placed : placements.values())
                if (placed.move != null && placed.target >= 0 && placed.target != seat && placed.home != seat
                        && !layout.conflict(seat, body.cls(), placed.target, placed.cls)) movingAway.add(placed.id);
            if (!movingAway.isEmpty() && blocker(body, seat, Set.of()) != null && blocker(body, seat, movingAway) == null) out.add(seat);
        }
        return out;
    }

    /** Whether the larder could hold this many portions without pressing on a body, and the load allows it. */
    public boolean heapFits(int portions) { return heapRefusal(portions) == null; }
    public String heapRefusal(int portions) {
        ensureLayout();
        if (portions < 0 || portions > layout.larderMax) return "LARDER_FULL";
        double cost = (portions - host.portions()) * layout.foodLoad;
        if (cost > 0 && load() + cost > layout.maxLoad + 1e-9) return "UNITS_FULL";
        int tier = DeckLayout.tierFor(portions);
        for (var p : placements.values()) {
            var bad = new String[1];
            p.physical(s -> { if (bad[0] == null && layout.larderExcludes(tier, s, p.cls)) bad[0] = layout.slot(s).id(); });
            if (bad[0] != null) return "CONFLICT_WITH:" + bad[0];
        }
        return null;
    }

    /** Book a seat. A body not aboard becomes RESERVED; one aboard keeps its seat and reserves the other. Null if refused. */
    public Lease claim(Body b, Purpose purpose, Vec3 near, int prefer, long ttl) {
        // Asking again for the seat already booked for this purpose is the same booking, renewed.
        var held = placements.get(b.id());
        if (held != null && !held.aboard && held.lease != null && held.lease.purpose() == purpose && held.target >= 0
                && (prefer < 0 || prefer == held.target) && purpose != Purpose.RETAINED) {
            if (held.lease.expiry() != Long.MAX_VALUE)
                held.lease = new Lease(held.lease.id(), purpose, b.id(), host.now() + ttl, held.lease.returnTo(), held.lease.caregiver());
            return held.lease;
        }
        var a = canAdmit(b, purpose, near, prefer);
        if (!a.ok()) { refuse(b, purpose, a); return null; }
        return takeClaim(b, purpose, a, ttl);
    }

    /** Book exactly this seat, or leave the deck untouched when the seat is no longer the legal choice. */
    public Lease claimAt(Body b, Purpose purpose, Vec3 near, int seat, long ttl) {
        var a = canAdmit(b, purpose, near, seat);
        if (!a.ok()) { refuse(b, purpose, a); return null; }
        if (a.slot() != seat) return null;
        return takeClaim(b, purpose, a, ttl);
    }

    /** Keep this exact in-flight target and lease identity alive; a missing claim is never rebuilt on another seat. */
    public boolean renewTarget(UUID id, int target, long ttl) {
        var p = placements.get(id);
        if (p == null || p.target != target || target < 0 || p.lease == null) return false;
        var lease = p.lease;
        if (lease.expiry() != Long.MAX_VALUE)
            p.lease = new Lease(lease.id(), lease.purpose(), lease.holder(), Math.max(lease.expiry(), host.now() + ttl), lease.returnTo(), lease.caregiver());
        return true;
    }

    private Lease takeClaim(Body b, Purpose purpose, Admission a, long ttl) {
        var p = placements.computeIfAbsent(b.id(), id -> new Placement(b));
        p.update(b);
        if (b.captain() && p.home < 0) p.home = homeFor(b.cls());
        int returnTo = p.aboard && purpose == Purpose.SERVICE ? p.slot : DeckLayout.NONE;
        long expiry = purpose == Purpose.RETAINED ? Long.MAX_VALUE : host.now() + ttl;
        var lease = new Lease(nextLease++, purpose, b.id(), expiry, returnTo, p.caregiver);
        p.lease = lease;
        p.target = p.aboard && p.slot == a.slot() ? DeckLayout.NONE : a.slot();
        if (purpose == Purpose.RETAINED) p.home = a.slot();
        else if (returnTo >= 0 && p.home < 0) p.home = returnTo;
        p.returning = false;
        host.event("deck claim body={} class={} purpose={} slot={} lease={} load={}->{}", b.id(), b.cls().id, purpose, a.slotId(), lease.id(),
                fmt(a.loadBefore()), fmt(a.loadAfter()));
        return lease;
    }

    /** A refuge lease carries the captain who shelters the body. */
    public Lease claimRefuge(Body b, UUID caregiver, Vec3 near) {
        var lease = claim(b, Purpose.REFUGE, near, DeckLayout.NONE, REFUGE_TTL);
        if (lease == null) return null;
        var p = placements.get(b.id());
        p.pendingCaregiver = caregiver;
        p.lease = new Lease(lease.id(), lease.purpose(), lease.holder(), lease.expiry(), lease.returnTo(), caregiver);
        return p.lease;
    }

    /** The mount has reached real brim support, while the transfer still owns the walk to its promised seat. */
    public void stageBoarding(Body b, Vec3 actual, int target) {
        var p = placements.get(b.id());
        if (p == null || p.aboard || p.target != target || target < 0) return;
        p.update(b);
        p.aboard = true;
        p.slot = DeckLayout.NONE;
        p.free = actual;
        p.move = null;
        p.overflow = false;
        host.event("deck brim reached body={} actual={} target={} arrival=pending", b.id(), actual, layout().slot(target).id());
    }

    /** The body is aboard now. A reserved seat becomes its seat; a body with no reservation is seated by {@link #register}. */
    public boolean commit(Body b, Vec3 actual) {
        ensureLayout();
        var p = placements.get(b.id());
        if (p != null && p.aboard) { p.update(b); return true; }
        if (p == null || p.target < 0) return register(b, actual) != null;
        p.update(b);
        p.aboard = true;
        p.slot = p.target;
        p.seatedAt = host.now();
        p.target = DeckLayout.NONE;
        p.move = null;
        p.overflow = false;
        finishAdmission(p);
        return true;
    }

    private void finishAdmission(Placement p) {
        var lease = p.lease;
        if (lease != null) switch (lease.purpose()) {
            case BOARD, RESCUE, REFUGE -> p.lease = null;
            case OWN -> p.returning = p.home >= 0 && p.home != p.slot;
            default -> { }
        }
        // A refuge reservation becomes a ward only by a completed mount, whichever lease finally carried the body aboard.
        if (p.pendingCaregiver != null) { p.caregiver = p.pendingCaregiver; p.pendingCaregiver = null; }
        host.event("deck admit body={} class={} slot={} purpose={} load={}", p.id, p.cls.id, layout.slot(p.slot).id(),
                lease == null ? "BOARD" : lease.purpose(), fmt(load()));
    }

    /** A body that is aboard without having booked: seat it nearest to where it actually is, or record it as overflow. */
    public Placement register(Body b, Vec3 actual) {
        ensureLayout();
        var p = placements.computeIfAbsent(b.id(), id -> new Placement(b));
        p.update(b);
        p.aboard = true;
        p.move = null;
        int keep = p.target >= 0 ? p.target : p.slot;
        p.target = DeckLayout.NONE;
        p.slot = DeckLayout.NONE;
        if (b.captain() && p.home < 0) p.home = homeFor(b.cls());
        Purpose purpose = Purpose.BOARD;
        int prefer = keep >= 0 ? keep : b.captain() ? p.home : DeckLayout.NONE;
        int chosen = DeckLayout.NONE;
        for (int i : candidates(b, purpose, actual, prefer))
            if (blocker(b, i, Set.of()) == null) { chosen = i; break; }
        if (chosen < 0 && !b.captain()) for (int i : candidates(b, Purpose.RESCUE, actual, DeckLayout.NONE))
            if (blocker(b, i, Set.of()) == null) { chosen = i; break; }
        if (chosen >= 0) {
            p.slot = chosen;
            p.seatedAt = host.now();
            p.overflow = false;
            host.event("deck admit body={} class={} slot={} purpose=REGISTER load={}", b.id(), b.cls().id, layout.slot(chosen).id(), fmt(load()));
        } else {
            p.overflow = true;
            p.free = actual;
            host.limited("deck.overflow." + b.id(), 200, "deck refuse body={} class={} purpose=REGISTER reason={} load={}/{}",
                    b.id(), b.cls().id, explain(b, purpose), fmt(load()), fmt(layout.maxLoad));
            remember("overflow " + b.cls().id + " " + explain(b, purpose));
        }
        return p;
    }

    /** The captain is no longer here (unloaded, dead, gone): her reserved seat is not kept for her. */
    public void dropAway(UUID id) {
        var p = placements.get(id);
        if (p != null && !p.aboard && p.captain()) placements.remove(id);
    }
    /** A body the deck thinks is aboard but the hull no longer carries (a missed dismount, an unloaded rider): its seat is freed. */
    public void reconcile(Set<UUID> passengers) {
        for (var p : List.copyOf(placements.values())) if (p.aboard && !passengers.contains(p.id)) leave(p.id, "not_aboard");
    }
    /** Only the current captain keeps a reserved seat; a former one's reservation ends. */
    public void pruneCaptains(UUID keep) {
        placements.values().removeIf(p -> p.captain() && !p.aboard && !p.id.equals(keep));
    }

    /** The body left the hull (or was never aboard): drop what it held, except a retained voyage berth and the captain's own seat. */
    public void leave(UUID id, String reason) {
        var p = placements.get(id);
        if (p == null) return;
        boolean wasAboard = p.aboard;
        p.aboard = false;
        p.move = null;
        p.overflow = false;
        p.caregiver = null;
        p.returning = false;
        p.carried = false;
        if (p.captain()) {
            p.slot = DeckLayout.NONE;
            p.lease = null;
            p.target = p.home >= 0 ? p.home : DeckLayout.NONE;
        } else if (p.retained()) {
            p.slot = DeckLayout.NONE;
            p.target = DeckLayout.NONE;
        } else {
            placements.remove(id);
        }
        if (wasAboard) host.event("deck release body={} reason=left_hull {}", id, reason);
    }

    /** Drop a lease (and an unboarded reservation) without moving anyone; an aboard body keeps its seat and, if it was on a service seat, goes home. */
    public void release(UUID id, String reason) {
        var p = placements.get(id);
        if (p == null) return;
        var lease = p.lease;
        boolean aboard = p.aboard;
        if (!aboard) {
            if (p.captain()) { p.lease = null; p.target = p.home; }
            else placements.remove(id);
        } else {
            boolean retained = lease != null && lease.purpose() == Purpose.RETAINED;
            p.lease = null;
            if (p.target >= 0 && p.move == null) p.target = DeckLayout.NONE;
            if (retained) { p.home = DeckLayout.NONE; p.returning = false; }
            else if (p.home >= 0) { if (p.slot == p.home) p.home = DeckLayout.NONE; else p.returning = true; }
        }
        if (lease != null || !aboard) host.event("deck release body={} class={} purpose={} reason={} load={}", id, p.cls.id,
                lease == null ? "-" : lease.purpose(), reason, fmt(load()));
    }

    /** The orphan is promoted from a refuge reservation to a boarded ward only by a completed mount ({@link #commit}); this ends it. */
    public void dropRefuge(UUID id, String reason) {
        var p = placements.get(id);
        if (p == null) return;
        boolean boarded = p.caregiver != null;
        p.caregiver = null;
        if (p.pendingCaregiver != null || p.lease != null && p.lease.purpose() == Purpose.REFUGE) { p.pendingCaregiver = null; release(id, reason); }
        if (boarded) host.event("deck release body={} purpose=REFUGE reason={} boarded=true", id, reason);
    }

    /** A retained berth is taken back by its pupil: bound for the old seat again. */
    public boolean reclaimRetained(Body b) {
        var p = placements.get(b.id());
        if (p == null || p.aboard || !p.retained() || p.home < 0) return false;
        p.update(b);
        p.target = p.home;
        return true;
    }

    // ---------------------------------------------------------------- moving
    /** Why the last {@link #moveWithin} failed: true when only a lift (a riser the body cannot climb) would do. */
    private boolean needsLift;
    public boolean needsLift() { return needsLift; }

    /** The timing and route of one trip, before any state is touched. */
    private record Trip(Placement p, Body b, int to, Vec3 from, boolean arc, List<Vec3> route, int ticks, Purpose purpose) {}

    private Trip plan(Body b, int to, Vec3 fromLocal, boolean arc, Purpose purpose) {
        needsLift = false;
        var p = placements.get(b.id());
        if (p == null || !p.aboard || to < 0 || p.slot == to) return null;
        if (p.move != null) return null;
        var seat = layout.slot(to);
        if (!legal(b, purpose, seat)) {
            remember("move refused " + seat.id() + " not legal for " + b.cls().id + " as " + purpose);
            return null;
        }
        List<Vec3> route = new ArrayList<>();
        route.add(fromLocal);
        int ticks;
        if (arc) {
            if (!b.jumps() && !b.captain()) return null;
            var hop = host.hop(b.id(), fromLocal, seat.pos());
            if (hop == null || hop.ticks() <= 0 || hop.route().isEmpty()) {
                remember("move refused " + seat.id() + " hop_exceeds_body");
                return null;
            }
            route = new ArrayList<>(hop.route());
            ticks = hop.ticks();
        } else {
            int[] path = null;
            if (p.slot >= 0) {
                path = layout.path(p.slot, to, b.cls(), false, i -> occupant(i) != null);
                if (path == null) path = layout.path(p.slot, to, b.cls(), false, null);
                if (path == null) {
                    needsLift = layout.path(p.slot, to, b.cls(), true, null) != null;
                    remember("move refused " + seat.id() + (needsLift ? " step path needs a lift" : " no walking path for " + b.cls().id));
                    return null;
                }
            }
            if (path == null) route.add(seat.pos());
            else {
                if (fromLocal.distanceTo(layout.slot(path[0]).pos()) > .05) route.add(layout.slot(path[0]).pos());
                for (int i = 1; i < path.length; i++) route.add(layout.slot(path[i]).pos());
            }
            double length = 0;
            for (int i = 1; i < route.size(); i++) length += route.get(i - 1).distanceTo(route.get(i));
            ticks = Math.max(WALK_MIN_TICKS, (int) Math.ceil(length / WALK_SPEED));
        }
        return new Trip(p, b, to, fromLocal, arc, route, ticks, purpose);
    }

    private void begin(Trip t, long start) {
        var p = t.p();
        p.move = new Move(t.from(), start, t.ticks(), t.arc(), t.route());
        p.target = t.to();
        p.free = null;
        if (t.purpose() == Purpose.SERVICE || t.purpose() == Purpose.RESCUE) {
            if (p.lease == null || p.lease.purpose() != t.purpose())
                p.lease = new Lease(nextLease++, t.purpose(), p.id, host.now() + SERVICE_TTL, p.slot, p.caregiver);
            if (p.home < 0) p.home = p.slot;
            p.returning = false;
        } else if (t.purpose() == Purpose.BOARD && p.lease == null && !p.returning) p.home = DeckLayout.NONE;
        host.limited("deck.move." + p.id, 40, "deck move body={} class={} {}->{} arc={} ticks={} purpose={}", p.id, p.cls.id,
                p.slot < 0 ? "free" : layout.slot(p.slot).id(), layout.slot(t.to()).id(), t.arc(), t.ticks(), t.purpose());
    }

    /**
     * Set an aboard body walking or hopping to another seat. A walk needs a step-free path of seats its class fits through;
     * a hop needs the body to jump. {@code purpose} SERVICE keeps the old seat as the way home; BOARD re-seats for good.
     */
    public boolean moveWithin(Body b, int to, Vec3 fromLocal, boolean arc, Purpose purpose) {
        ensureLayout();
        var trip = plan(b, to, fromLocal, arc, purpose);
        if (trip == null) return false;
        String why = blocker(b, to, Set.of());
        if (why != null) { remember("move refused " + layout.slot(to).id() + " CONFLICT_WITH:" + why); return false; }
        begin(trip, queueStart(b.id(), null));
        return true;
    }

    /**
     * Two bodies change seats together, each checked against everyone but the other (a care station: the captain and the
     * child it feeds). Both trips are planned before either starts, so one cannot strand the other.
     * {@code pairConflict} also applies the conflict table between the two new seats (rescue pairs; authored care pairs are exempt).
     */
    public boolean movePair(Body a, int toA, Vec3 fromA, boolean arcA, Body b, int toB, Vec3 fromB, boolean arcB, Purpose purpose, boolean pairConflict) {
        ensureLayout();
        if (toA == toB) return false;
        boolean sitsA = aboard(a.id()) && placements.get(a.id()).slot == toA;
        boolean sitsB = aboard(b.id()) && placements.get(b.id()).slot == toB;
        Trip first = sitsA ? null : plan(a, toA, fromA, arcA, purpose);
        boolean liftA = needsLift;
        Trip second = sitsB ? null : plan(b, toB, fromB, arcB, purpose);
        needsLift |= liftA;
        if (first == null && !sitsA || second == null && !sitsB) return false;
        var ignore = Set.of(a.id(), b.id());
        if (blocker(a, toA, ignore) != null || blocker(b, toB, ignore) != null) { remember("pair refused CONFLICT_WITH others"); return false; }
        if (pairConflict && layout.conflict(toA, a.cls(), toB, b.cls())) {
            remember("pair refused " + layout.slot(toA).id() + " vs " + layout.slot(toB).id());
            return false;
        }
        long start = queueStart(a.id(), b.id());
        if (first != null) begin(first, start);
        if (second != null) begin(second, start);
        return true;
    }


    /** Seats this body could take now, best first (read only): free, lawful for the purpose, and conflict-free. */
    public List<Integer> freeSeats(Body b, Purpose purpose, Vec3 near) {
        ensureLayout();
        var out = new ArrayList<Integer>();
        if (!host.stable()) return out;
        for (int i : candidates(b, purpose, near, DeckLayout.NONE)) if (blocker(b, i, Set.of()) == null) out.add(i);
        return out;
    }

    /**
     * The first seat the real one-portion-at-a-time relief loop will make admissible, without changing provisions or placements.
     * This keeps a preflight's geometry tied to the exact seat it will later claim, rather than to a seat that would need more
     * relief than the live loop actually performs.
     */
    public int firstSeatAfterRelievingLarder(Body b, Purpose purpose, Vec3 near, boolean allowRescue) {
        ensureLayout();
        if (!host.stable()) return DeckLayout.NONE;
        var self = placements.get(b.id());
        int current = (int) Math.ceil(host.portions());
        double after = load() - (self == null ? 0 : self.load(layout)) + loadOf(b);
        var purposes = purpose == Purpose.BOARD && allowRescue ? new Purpose[]{Purpose.BOARD, Purpose.RESCUE} : new Purpose[]{purpose};
        for (int portions = current - 1; portions >= 0; portions--) {
            double relieved = after - (current - portions) * layout.foodLoad;
            if (!b.captain() && relieved > layout.maxLoad + 1e-9) continue;
            for (var candidatePurpose : purposes)
                for (int i : candidates(b, candidatePurpose, near, DeckLayout.NONE))
                    if (blockerAtPortions(b, i, Set.of(), portions) == null) return i;
        }
        return DeckLayout.NONE;
    }

    /** Bodies queue so none walks through another: a new move starts when the last one ends (the named bodies excepted). */
    private long queueStart(UUID self, UUID also) {
        long start = host.now();
        for (var p : placements.values())
            if (!p.id.equals(self) && !p.id.equals(also) && p.move != null) start = Math.max(start, p.move.start() + p.move.ticks());
        return start;
    }

    /** Mark a lift: the body is carried by someone else to {@code to}; the seat is reserved until {@link #arrived} or {@link #abandon}. */
    public boolean reserveLift(Body b, int to, Purpose purpose) {
        ensureLayout();
        var p = placements.get(b.id());
        if (p == null || !p.aboard) return false;
        var seat = layout.slot(to);
        if (!legal(b, purpose, seat)) return false;
        if (blocker(b, to, Set.of()) != null) return false;
        p.target = to;
        if (purpose == Purpose.SERVICE) {
            if (p.lease == null || p.lease.purpose() != Purpose.SERVICE) p.lease = new Lease(nextLease++, Purpose.SERVICE, b.id(), host.now() + SERVICE_TTL, p.slot, p.caregiver);
            else renewTarget(b.id(), to, SERVICE_TTL);
            if (p.home < 0) p.home = p.slot;
            p.returning = false;
        }
        return true;
    }
    /** While a parent holds a body in the air its old seat is empty space for conflicts (but stays its own to return to). */
    public void lift(UUID id, boolean carried) {
        var p = placements.get(id);
        if (p != null) p.carried = carried;
    }
    public boolean carried(UUID id) { var p = placements.get(id); return p != null && p.carried; }
    /** A lifted body has been put down on its reserved seat. */
    public boolean arrived(UUID id, int target) {
        var p = placements.get(id);
        if (p == null || !p.aboard || p.target != target || target < 0 || !host.arrived(id, layout.slot(target).pos())) return false;
        boolean boarding = p.slot < 0;
        p.carried = false;
        p.slot = p.target;
        p.seatedAt = host.now();
        p.target = DeckLayout.NONE;
        p.move = null;
        p.overflow = false;
        p.free = null;
        if (boarding) finishAdmission(p);
        afterArrival(p);
        return true;
    }
    /** A cancelled lift or move gives the reserved seat back; the body stays where it is. */
    public void abandon(UUID id, String reason) {
        var p = placements.get(id);
        if (p == null) return;
        if (p.aboard && p.target >= 0) {
            host.limited("deck.abandon." + id, 40, "deck release body={} reason={} kept={}", id, reason, p.slot < 0 ? "free" : layout().slot(p.slot).id());
            p.target = DeckLayout.NONE;
            p.move = null;
            if (p.home >= 0 && p.slot == p.home) p.home = DeckLayout.NONE;
        }
    }
    /** A blocked trip can end on the real supported deck point without inventing an arrived seat. */
    public void holdActual(UUID id, Vec3 local, String reason) {
        var p = placements.get(id);
        if (p == null || !p.aboard) return;
        p.slot = DeckLayout.NONE;
        p.target = DeckLayout.NONE;
        p.move = null;
        p.free = local;
        p.carried = false;
        p.returning = false;
        p.overflow = false;
        if (p.lease != null && p.lease.purpose() == Purpose.SERVICE) p.lease = null;
        host.event("deck stopped body={} reason={} actual={} seat=unassigned", id, reason, local);
    }

    private void afterArrival(Placement p) {
        if (p.home >= 0 && p.slot == p.home) {
            // A voyage keeps this actual brim berth until the pupil returns or the voyage releases it.
            // Reaching it is the departure prerequisite, not completion of a temporary service return.
            if (!p.retained()) p.home = DeckLayout.NONE;
            p.returning = false;
            if (p.lease != null && (p.lease.purpose() == Purpose.SERVICE || p.lease.purpose() == Purpose.OWN || p.lease.purpose() == Purpose.RESCUE)) p.lease = null;
        }
    }

    /** Commit actual arrivals, retire lapsed leases, and physically release movers that overrun their slack. Once per tick. */
    public void tick() {
        ensureLayout();
        long now = host.now();
        for (var p : List.copyOf(placements.values())) {
            if (p.move != null) {
                long end = p.move.start() + p.move.ticks();
                if (now >= end && p.target >= 0 && host.arrived(p.id, layout.slot(p.target).pos())) {
                    arrived(p.id, p.target);
                } else if (now > end + MOVE_SLACK_TICKS) {
                    host.event("deck release body={} reason=move_timeout target={} actual=preserved", p.id, p.target);
                    p.move = null;
                    p.target = DeckLayout.NONE;
                    host.moveFailed(p.id, "move_timeout");
                }
            }
            if (p.lease != null && p.lease.lapsed(now) && p.move == null) {
                host.event("deck release body={} class={} purpose={} reason=lease_expired", p.id, p.cls.id, p.lease.purpose());
                release(p.id, "expired");
            }
        }
    }

    // ---------------------------------------------------------------- reflow
    /** Is this body's own seat still lawful (class, night, jump, larder, stick, and every other body)? */
    private boolean seatLawful(Placement p) {
        if (!p.aboard || p.slot < 0) return false;
        var s = layout.slot(p.slot);
        var b = body(p);
        // A toilet rim is a station, not a seat: a body left on one (a lift's first stage, an ended errand) is reseated after a while.
        if (!p.captain() && s.purpose().equals("toilet") && p.lease == null && host.now() - p.seatedAt > REST_ON_STATION_TICKS) return false;
        return legal(b, seatPurpose(p, s), s) && blocker(b, p.slot, Set.of()) == null;
    }
    private Purpose seatPurpose(Placement p, DeckLayout.Slot s) {
        if (p.lease != null && p.lease.purpose() != Purpose.REFUGE) return p.lease.purpose();
        if (s.purpose().equals("rescue")) return Purpose.RESCUE;
        if (s.purpose().equals("toilet") || s.kind() == Kind.SERVICE) return Purpose.SERVICE;
        return Purpose.BOARD;
    }
    private Body body(Placement p) { return new Body(p.id, p.cls, p.jumps, true, p.extra); }

    /**
     * Keep every aboard body on a lawful seat. Only a body whose seat became unlawful (it grew, night fell, a larder tier
     * rose, the captain grew heavy) moves; the others stay. Bodies on service leases are left alone.
     */
    public Tend tend(Map<UUID, Body> aboard, Map<UUID, Vec3> locals) {
        ensureLayout();
        var moved = new ArrayList<UUID>();
        var lifts = new ArrayList<Lift>();
        var stuck = new ArrayList<UUID>();
        for (var entry : aboard.entrySet()) {
            var p = placements.get(entry.getKey());
            if (p == null || !p.aboard) register(entry.getValue(), locals.getOrDefault(entry.getKey(), Vec3.ZERO));
            else {
                boolean grew = p.cls != entry.getValue().cls();
                p.update(entry.getValue());
                if (grew) host.limited("deck.class." + p.id, 200, "deck class changed body={} class={}", p.id, p.cls.id);
            }
        }
        long now = host.now();
        var illegal = new ArrayList<Placement>();
        for (var p : placements.values()) {
            if (!p.aboard || p.move != null || p.target >= 0 || p.returning) continue;
            if (p.lease != null && p.lease.purpose() == Purpose.SERVICE) continue;
            if (p.overflow || p.slot < 0 || !seatLawful(p)) illegal.add(p);
        }
        if (illegal.isEmpty()) return new Tend(moved, lifts, stuck);
        // Smallest first is the cheapest to move; the captain only moves when it is her own seat that is wrong.
        var needs = new ArrayList<Need>();
        for (var p : illegal) needs.add(new Need(body(p), Purpose.BOARD, locals.get(p.id), p.slot, true, DeckLayout.NONE));
        var solution = solve(needs, MAX_DISPLACED + illegal.size());
        if (solution == null) {
            // Everything that may move, once: sleeping or leased bodies, the captain and moving bodies stay put.
            var all = new ArrayList<>(needs);
            Set<UUID> have = new HashSet<>();
            for (var n : needs) have.add(n.body.id());
            for (var p : placements.values())
                if (p.aboard && !p.captain() && !have.contains(p.id) && p.slot >= 0 && p.lease == null && p.target < 0 && p.move == null)
                    all.add(new Need(body(p), Purpose.BOARD, locals.get(p.id), p.slot, true, DeckLayout.NONE));
            solution = solve(all, MAX_DISPLACED + illegal.size());
            if (solution != null) needs = all;
        }
        if (solution == null) {
            for (var p : illegal) {
                p.overflow = true;
                if (p.free == null) p.free = locals.getOrDefault(p.id, p.slot >= 0 ? layout.slot(p.slot).pos() : Vec3.ZERO);
                stuck.add(p.id);
                host.limited("deck.stuck." + p.id, 400, "deck refuse body={} class={} purpose=REFLOW reason={} load={}/{}", p.id, p.cls.id,
                        explain(body(p), Purpose.BOARD), fmt(load()), fmt(layout.maxLoad));
            }
            remember("reflow stuck " + stuck.size());
            return new Tend(moved, lifts, stuck);
        }
        for (int i = 0; i < needs.size(); i++) {
            var n = needs.get(i);
            var p = placements.get(n.body.id());
            int to = solution[i];
            if (p == null || to == p.slot) { if (p != null) p.overflow = false; continue; }
            Vec3 from = locals.getOrDefault(p.id, p.slot >= 0 ? layout.slot(p.slot).pos() : Vec3.ZERO);
            p.overflow = false;
            boolean walk = p.slot >= 0 && layout.path(p.slot, to, p.cls, false, null) != null;
            boolean hop = !walk && (p.jumps || p.captain());
            if (p.slot < 0) {
                // An unassigned real position still needs a physical trip, never an immediate seat transform.
                if (moveWithin(body(p), to, from, true, Purpose.BOARD)) moved.add(p.id);
                else lifts.add(new Lift(p.id, DeckLayout.NONE, to));
            } else if (moveWithin(body(p), to, from, hop, Purpose.BOARD)) moved.add(p.id);
            else if (needsLift) lifts.add(new Lift(p.id, p.slot, to));
        }
        return new Tend(moved, lifts, stuck);
    }

    // ---------------------------------------------------------------- the exhaustive search
    private record Need(Body body, Purpose purpose, Vec3 near, int original, boolean counts, int only) {}

    /** One legal, conflict-free seat per need given everyone else stays, or null. Bodies prefer the seat they already hold. */
    private int[] solve(List<Need> needs, int maxDisplaced) {
        var ordered = new ArrayList<Integer>();
        for (int i = 0; i < needs.size(); i++) ordered.add(i);
        // Largest class first: scarce seats are claimed before the small ones fit around them.
        ordered.sort(Comparator.comparingInt((Integer i) -> -needs.get(i).body.cls().ordinal()));
        Set<UUID> ignore = new HashSet<>();
        for (var n : needs) ignore.add(n.body.id());
        var options = new ArrayList<List<Integer>>();
        for (var n : needs) {
            var list = candidates(n.body, n.purpose, n.near, n.original);
            if (n.only >= 0) list.removeIf(i -> i != n.only);
            else if (list.size() > SEARCH_CANDIDATES) list = new ArrayList<>(list.subList(0, SEARCH_CANDIDATES));
            options.add(list);
        }
        int[] result = new int[needs.size()];
        Arrays.fill(result, DeckLayout.NONE);
        int[] budget = {SEARCH_BUDGET};
        long started = System.nanoTime();
        int[] found = null;
        for (int limit = 0; limit <= maxDisplaced; limit++) {
            Arrays.fill(result, DeckLayout.NONE);
            if (assign(needs, ordered, 0, options, result, ignore, budget, 0, limit)) { found = result; break; }
            if (budget[0] <= 0) break;
        }
        boolean budgetHit = found == null && budget[0] <= 0;
        long micros = (System.nanoTime() - started) / 1000;
        if (budgetHit) {
            // Deterministic fallback: no assignment is claimed; callers see "no seat for the class" and the decision layer takes over.
            remember("search budget " + Admission.Reason.NO_SLOT_FOR_CLASS);
            host.limited("deck.budget", 200, "deck search budget hit needs={} nodes={} micros={} reason={}", needs.size(), SEARCH_BUDGET, micros,
                    Admission.Reason.NO_SLOT_FOR_CLASS);
        } else if (micros > 5000) host.limited("deck.slow", 400, "deck search slow needs={} nodes={} micros={}", needs.size(), SEARCH_BUDGET - budget[0], micros);
        return found;
    }

    private boolean assign(List<Need> needs, List<Integer> order, int depth, List<List<Integer>> options, int[] result, Set<UUID> ignore,
            int[] budget, int displaced, int limit) {
        if (depth == order.size()) return true;
        int index = order.get(depth);
        var need = needs.get(index);
        for (int slot : options.get(index)) {
            if (--budget[0] <= 0) return false;
            boolean moves = need.counts && slot != need.original;
            if (moves && displaced >= limit) continue;
            boolean clash = false;
            for (int other = 0; other < needs.size() && !clash; other++) {
                if (other == index || result[other] < 0) continue;
                clash = result[other] == slot || layout.conflict(slot, need.body.cls(), result[other], needs.get(other).body.cls());
            }
            if (clash || blocker(need.body, slot, ignore) != null) continue;
            result[index] = slot;
            if (assign(needs, order, depth + 1, options, result, ignore, budget, displaced + (moves ? 1 : 0), limit)) return true;
            result[index] = DeckLayout.NONE;
        }
        return false;
    }

    /**
     * The explicit "make room" planning step: seat {@code newcomer} by moving at most a few idle bodies elsewhere. Read only.
     * A refusal for load (UNITS_FULL) cannot be cured by moving anyone; only the decision layer's own choices (a meal, a
     * jettisoned portion) change the load.
     */
    public RoomPlan planRoom(Body newcomer, Purpose purpose, Vec3 near) {
        ensureLayout();
        var direct = canAdmit(newcomer, purpose, near, DeckLayout.NONE);
        if (direct.ok()) return new RoomPlan(direct.slot(), Map.of());
        if (direct.reason() != Admission.Reason.CONFLICT_WITH) return null;
        long now = host.now();
        var needs = new ArrayList<Need>();
        needs.add(new Need(newcomer, purpose, near, DeckLayout.NONE, false, DeckLayout.NONE));
        for (var p : placements.values())
            if (p.aboard && !p.captain() && p.slot >= 0 && p.lease == null && p.target < 0 && p.move == null && !p.id.equals(newcomer.id())
                    && (p.jumps || layout.path(p.slot, p.slot, p.cls, false, null) != null))
                needs.add(new Need(body(p), Purpose.BOARD, null, p.slot, true, DeckLayout.NONE));
        var solution = solve(needs, MAX_DISPLACED);
        if (solution == null) return null;
        var moves = new LinkedHashMap<UUID, Integer>();
        for (int i = 1; i < needs.size(); i++) if (solution[i] != needs.get(i).original) moves.put(needs.get(i).body.id(), solution[i]);
        return new RoomPlan(solution[0], moves);
    }

    /** As {@link #planRoom} but for one named seat: which idle bodies would have to move for this body to take exactly that seat. */
    public RoomPlan planSeat(Body newcomer, Purpose purpose, int wanted) {
        ensureLayout();
        if (wanted < 0 || !legal(newcomer, purpose, layout.slot(wanted))) return null;
        long now = host.now();
        var needs = new ArrayList<Need>();
        needs.add(new Need(newcomer, purpose, null, DeckLayout.NONE, false, wanted));
        for (var p : placements.values())
            if (p.aboard && !p.captain() && p.slot >= 0 && p.lease == null && p.target < 0 && p.move == null && !p.id.equals(newcomer.id()))
                needs.add(new Need(body(p), Purpose.BOARD, null, p.slot, true, DeckLayout.NONE));
        var solution = solve(needs, MAX_DISPLACED);
        if (solution == null) return null;
        var moves = new LinkedHashMap<UUID, Integer>();
        for (int i = 1; i < needs.size(); i++) if (solution[i] != needs.get(i).original) moves.put(needs.get(i).body.id(), solution[i]);
        return new RoomPlan(solution[0], moves);
    }

    /** Track actual feet without assigning a seat, including the supported walk after boarding the brim. */
    public void moveFree(UUID id, Vec3 local) {
        var p = placements.get(id);
        if (p != null && (p.overflow || p.aboard && p.slot < 0)) p.free = local;
    }
    /** Stop every move and lift where the bodies are, as a capsize does: they hold their actual place until they are released. */
    public void freeze(Map<UUID, Vec3> actual) {
        long now = host.now();
        for (var p : placements.values()) {
            if (!p.aboard) continue;
            boolean moving = p.move != null;
            p.move = null;
            p.target = DeckLayout.NONE;
            p.lease = null;
            p.returning = false;
            p.carried = false;
            if (moving || p.slot < 0) {
                p.slot = DeckLayout.NONE;
                p.overflow = true;
                p.free = actual.getOrDefault(p.id, p.free == null ? Vec3.ZERO : p.free);
            }
        }
    }
    /** A body gave up its way home (it grew, the seat turned unlawful): reflow will seat it where it stands. */
    public void forgetHome(UUID id) {
        var p = placements.get(id);
        if (p == null || p.captain()) return;
        p.home = DeckLayout.NONE;
        p.returning = false;
    }
    public boolean returning(UUID id) { var p = placements.get(id); return p != null && p.returning; }

    // ---------------------------------------------------------------- care stations
    /**
     * A care station for a child: one of the authored captain/young pairs whose seats are both lawful and free (the pair's
     * own two bodies excepted). The child's own seat is preferred, then the cheapest walk.
     */
    public CarePlan planCare(Body captain, Body child, boolean rear, Vec3 captainAt, Vec3 childAt) {
        ensureLayout();
        var ignore = Set.of(captain.id(), child.id());
        CarePlan best = null;
        double cost = Double.MAX_VALUE;
        for (var care : layout.care()) {
            if (!(rear ? care.rearAllows(child.cls()) : care.allows(child.cls()))) continue;
            var ys = layout.slot(care.young());
            var cs = layout.slot(care.captain());
            if (!legal(child, Purpose.SERVICE, ys) || !legal(captain, Purpose.SERVICE, cs)) continue;
            if (blocker(child, care.young(), ignore) != null || blocker(captain, care.captain(), ignore) != null) continue;
            double c = ys.pos().distanceTo(childAt == null ? ys.pos() : childAt) + cs.pos().distanceTo(captainAt == null ? cs.pos() : captainAt) * .5;
            var cp = placements.get(child.id());
            if (cp != null && cp.slot == care.young()) c -= 1000;
            if (c < cost) { cost = c; best = new CarePlan(care.captain(), care.young(), care); }
        }
        return best;
    }

    // ---------------------------------------------------------------- sync and save
    /** The client's deck: every body aboard with its seat id, kind and position, plus any move in progress and the load. */
    public CompoundTag snapshot(UUID captainId) {
        ensureLayout();
        var tag = new CompoundTag();
        var list = new ListTag();
        for (var p : placements.values()) {
            if (!p.aboard) continue;
            var seat = new CompoundTag();
            seat.putUUID("Id", p.id);
            var at = positionOf(p.id);
            if (at == null) at = Vec3.ZERO;
            int s = p.aboard && !(p.move != null && p.target >= 0) ? p.slot : p.seat();
            seat.putString("Slot", s < 0 ? "" : layout.slot(s).id());
            seat.putString("Kind", s < 0 ? "FREE" : layout.slot(s).kind().name());
            seat.putDouble("X", at.x);
            seat.putDouble("Y", at.y);
            seat.putDouble("Z", at.z);
            if (p.id.equals(captainId)) seat.putBoolean("OwnerService", true);
            var move = p.move;
            if (move != null) {
                seat.putDouble("FromX", move.from().x);
                seat.putDouble("FromY", move.from().y);
                seat.putDouble("FromZ", move.from().z);
                seat.putLong("At", move.start());
                seat.putInt("Ticks", move.ticks());
                seat.putBoolean("Arc", move.arc());
                var route = new ListTag();
                for (var point : move.route()) {
                    var pt = new CompoundTag();
                    pt.putDouble("X", point.x);
                    pt.putDouble("Y", point.y);
                    pt.putDouble("Z", point.z);
                    route.add(pt);
                }
                seat.put("Route", route);
            }
            list.add(seat);
        }
        tag.put("S", list);
        tag.putDouble("Load", load());
        return tag;
    }

    public CompoundTag save() {
        var tag = new CompoundTag();
        var list = new ListTag();
        long now = host.now();
        for (var p : placements.values()) {
            var saved = new CompoundTag();
            saved.putUUID("Id", p.id);
            saved.putString("Class", p.cls.id);
            saved.putBoolean("Jumps", p.jumps);
            saved.putBoolean("Aboard", p.aboard);
            saved.putDouble("Extra", p.extra);
            saved.putString("Slot", name(p.slot));
            saved.putString("Target", p.aboard ? "" : name(p.target));
            saved.putString("Home", name(p.home));
            saved.putBoolean("Overflow", p.overflow);
            saved.putBoolean("Returning", p.returning);
            if (p.free != null) {
                saved.putDouble("FreeX", p.free.x);
                saved.putDouble("FreeY", p.free.y);
                saved.putDouble("FreeZ", p.free.z);
            }
            if (p.caregiver != null) saved.putUUID("Caregiver", p.caregiver);
            if (p.pendingCaregiver != null) saved.putUUID("Pending", p.pendingCaregiver);
            if (p.lease != null) {
                var lease = new CompoundTag();
                lease.putString("Purpose", p.lease.purpose().name());
                lease.putLong("Left", p.lease.expiry() == Long.MAX_VALUE ? -1 : Math.max(0, p.lease.expiry() - now));
                lease.putString("ReturnTo", name(p.lease.returnTo()));
                if (p.lease.caregiver() != null) lease.putUUID("Caregiver", p.lease.caregiver());
                saved.put("Lease", lease);
            }
            list.add(saved);
        }
        tag.put("P", list);
        return tag;
    }

    public void load(CompoundTag tag) {
        placements.clear();
        ensureLayout();
        long now = host.now();
        for (var raw : tag.getList("P", Tag.TAG_COMPOUND)) {
            var saved = (CompoundTag) raw;
            if (!saved.hasUUID("Id")) continue;
            BodyClass cls;
            try { cls = BodyClass.of(saved.getString("Class")); } catch (IllegalArgumentException invalid) { continue; }
            var p = new Placement(new Body(saved.getUUID("Id"), cls, saved.getBoolean("Jumps"), true, saved.getDouble("Extra")));
            p.aboard = saved.getBoolean("Aboard");
            p.slot = layout.index(saved.getString("Slot"));
            p.target = layout.index(saved.getString("Target"));
            p.home = layout.index(saved.getString("Home"));
            p.overflow = saved.getBoolean("Overflow");
            p.returning = saved.getBoolean("Returning");
            if (saved.contains("FreeX")) p.free = new Vec3(saved.getDouble("FreeX"), saved.getDouble("FreeY"), saved.getDouble("FreeZ"));
            if (saved.hasUUID("Caregiver")) p.caregiver = saved.getUUID("Caregiver");
            if (saved.hasUUID("Pending")) p.pendingCaregiver = saved.getUUID("Pending");
            if (saved.contains("Lease", Tag.TAG_COMPOUND)) {
                var lease = saved.getCompound("Lease");
                try {
                    long left = lease.getLong("Left");
                    p.lease = new Lease(nextLease++, Purpose.valueOf(lease.getString("Purpose")), p.id, left < 0 ? Long.MAX_VALUE : now + left,
                            layout.index(lease.getString("ReturnTo")), lease.hasUUID("Caregiver") ? lease.getUUID("Caregiver") : null);
                } catch (IllegalArgumentException invalid) { p.lease = null; }
            }
            // Transfer tickets are not rehydrated with the hull, so a noncaptain's unboarded BOARD/RESCUE claim cannot survive a reload.
            // A captain remains known to her own hull, even if an unusual external boarding request held this kind of lease.
            if (!p.aboard && p.lease != null && (p.lease.purpose() == Purpose.BOARD || p.lease.purpose() == Purpose.RESCUE)) {
                if (!p.captain()) continue;
                p.lease = null;
                p.target = p.home;
            }
            // The captain keeps her home placement, but an OWN transfer ticket is not resumed after a reload.
            if (!p.aboard && p.lease != null && p.lease.purpose() == Purpose.OWN) {
                p.lease = null;
                p.target = p.home;
            }
            // A move interrupted by a save is not resumed: the body stays on the seat it left.
            if (p.aboard && p.slot < 0 && !p.overflow) p.overflow = true;
            placements.put(p.id, p);
        }
    }

    // ---------------------------------------------------------------- explanations
    private String explain(Body b, Purpose purpose) {
        var a = canAdmit(b, purpose, null, DeckLayout.NONE);
        return a.ok() ? "OK(" + a.slotId() + ")" : a.text();
    }
    private void refuse(Body b, Purpose purpose, Admission a) {
        remember(purpose + " " + b.cls().id + " " + a.text());
        host.limited("deck.refuse." + b.id() + "." + a.reason(), 100, "deck refuse body={} class={} purpose={} reason={} load={}->{} max={}",
                b.id(), b.cls().id, purpose, a.text(), fmt(a.loadBefore()), fmt(a.loadAfter()), fmt(layout.maxLoad));
    }
    private void remember(String text) {
        lastRefusal = text;
        lastRefusalAt = host.now();
    }
    private String name(int slot) { return slot < 0 ? "" : layout.slot(slot).id(); }
    private static String fmt(double value) { return String.format(Locale.ROOT, "%.1f", value); }
}
