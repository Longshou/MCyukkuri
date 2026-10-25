package io.github.longshou.mcyukkuri.subspecies.crew;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.longshou.mcyukkuri.subspecies.HatVessel;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;
import net.minecraft.world.phys.Vec3;

/**
 * The discrete deck of one hull mode, read from {@code hat_deck_layout.json} (seats, per-class conflicts, walking edges,
 * entries, larder tiers, the stick's stowage and the care pairs) and {@code hat_frames.json} (the per-load waterline and
 * roll factors). Nothing here is computed from geometry at run time and no coordinate or scale is copied into code: the
 * tables are exported by {@code standards/yukkuri-library/interactions/marisa-hat-vessel/deck.py}.
 * Positions are hull-local hat units (the prop's, one unit being {@code hullScale/16} blocks).
 */
public final class DeckLayout {
    public enum Kind { CAPTAIN, CAPTAIN_HIGH, BOW_PERCH, WELL_FRONT, NEST_HOLLOW, BRIM, TOILET_RIM, RESCUE_RIM, SERVICE, LARDER }
    public static final int NONE = -1;
    private static final String LAYOUT = "/assets/mcyukkuri/yukkuri_presentation/hat_deck_layout.json";
    private static final String FRAMES = "/assets/mcyukkuri/yukkuri_presentation/hat_frames.json";

    /** {@code purpose} is empty for an ordinary seat, otherwise care, toilet or rescue (leased seats only). */
    public record Slot(int index, String id, Kind kind, Vec3 pos, float yaw, int classes, boolean night, boolean requiresJump,
            int dayRank, int nightRank, boolean leaseOnly, String purpose, float leanBack) {
        public boolean allows(BodyClass c) { return (classes & c.mask()) != 0; }
        public boolean ordinary() { return purpose.isEmpty() && !leaseOnly && kind != Kind.LARDER; }
    }
    public record Edge(int a, int b, double length, double rise, boolean step, int classes) {}
    public record Anchor(Vec3 outside, Vec3 bank, String fromWater, String fromBank) {}
    public record Entry(String id, Vec3 edge, double nx, double nz, Map<BodyClass, Anchor> anchors, int[] nearest) {}
    /** One larder tier: how many portions it holds, the zone it occupies and the seats it presses on. */
    /** {@code visualOffset} (x, z hat units) moves the existing heap mesh onto the measured zone. */
    public record Tier(int tier, int maxCount, Vec3 zone, double height, double[] visualOffset, byte[] excludes, int[] lip) {}
    public record Care(int captain, int young, int classes, int rear, float youngYaw) {
        public boolean allows(BodyClass c) { return (classes & c.mask()) != 0; }
        public boolean rearAllows(BodyClass c) { return (rear & c.mask()) != 0; }
    }
    /** The stick's stowage on the braid-free left brim: a segment and the seats it blocks. */
    public record Stow(Vec3 a, Vec3 b, double radius, byte[] excludes) {
        public Vec3 middle() { return a.add(b).scale(.5); }
    }

    private static final Map<HatVessel.Mode, DeckLayout> CACHE = new EnumMap<>(HatVessel.Mode.class);

    public final HatVessel.Mode mode;
    public final double hullRatio, top, maxLoad, foodLoad, paddleLoad, larderMax, sink, roll;
    private final double[] classLoad = new double[BodyClass.values().length];
    private final double[] classScale = new double[BodyClass.values().length];
    private final boolean[] jumps = new boolean[BodyClass.values().length];
    private final List<Slot> slots;
    private final Map<String, Integer> byId = new HashMap<>();
    private final byte[][][] conflicts;
    private final List<Edge> edges;
    private final List<List<Edge>> adjacency = new ArrayList<>();
    private final List<Entry> entries;
    private final List<Tier> tiers;
    private final List<Care> care;
    private final Stow stow;
    private final float[][] freeboardByLoad, rollByLoad;

    public static DeckLayout of(HatVessel.Mode mode) {
        synchronized (CACHE) { return CACHE.computeIfAbsent(mode, DeckLayout::new); }
    }

    private static JsonObject read(String resource) {
        try (var stream = DeckLayout.class.getResourceAsStream(resource)) {
            if (stream == null) throw new IllegalStateException("Missing " + resource);
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException error) { throw new UncheckedIOException(error); }
    }

    private static Vec3 vec(JsonArray a) { return new Vec3(a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble()); }
    private static int mask(JsonArray ids) {
        int result = 0;
        for (var id : ids) result |= BodyClass.of(id.getAsString()).mask();
        return result;
    }
    private static float[][] table(JsonObject node, String key) {
        if (node == null || !node.has(key)) return null;
        var rows = node.getAsJsonArray(key);
        var out = new float[rows.size()][];
        for (int i = 0; i < out.length; i++) {
            var row = rows.get(i).getAsJsonArray();
            out[i] = new float[]{row.get(0).getAsFloat(), row.get(1).getAsFloat()};
        }
        return out;
    }

    private DeckLayout(HatVessel.Mode mode) {
        this.mode = mode;
        var root = read(LAYOUT);
        var node = root.getAsJsonObject("modes").getAsJsonObject(mode.name());
        hullRatio = node.get("hull_ratio").getAsDouble();
        top = node.get("top").getAsDouble();
        var classes = root.getAsJsonObject("classes");
        var jump = root.getAsJsonObject("jumps");
        for (var c : BodyClass.values()) {
            classScale[c.ordinal()] = classes.has(c.id) ? classes.get(c.id).getAsDouble() : 1;
            jumps[c.ordinal()] = jump.has(c.id) && jump.get(c.id).getAsBoolean();
        }
        var load = root.getAsJsonObject("load");
        maxLoad = load.get("max").getAsDouble();
        foodLoad = load.get("food").getAsDouble();
        paddleLoad = load.get("paddle").getAsDouble();
        larderMax = load.get("larder_max").getAsDouble();
        sink = load.get("sink").getAsDouble();
        roll = load.get("roll").getAsDouble();
        for (var c : BodyClass.values()) classLoad[c.ordinal()] = load.has(c.id) ? load.get(c.id).getAsDouble() : 0;

        var rawSlots = node.getAsJsonArray("slots");
        var list = new ArrayList<Slot>();
        for (int i = 0; i < rawSlots.size(); i++) {
            var s = rawSlots.get(i).getAsJsonObject();
            var prefer = s.has("prefer") ? s.getAsJsonObject("prefer") : null;
            var slot = new Slot(i, s.get("id").getAsString(), Kind.valueOf(s.get("kind").getAsString()), vec(s.getAsJsonArray("pos")),
                    s.get("yaw").getAsFloat(), mask(s.getAsJsonArray("classes")), s.get("night").getAsBoolean(), s.get("requires_jump").getAsBoolean(),
                    prefer != null && prefer.has("day") ? prefer.get("day").getAsInt() : 1000,
                    prefer != null && prefer.has("night") ? prefer.get("night").getAsInt() : 1000,
                    s.has("lease_only") && s.get("lease_only").getAsBoolean(), s.has("purpose") ? s.get("purpose").getAsString() : "",
                    s.has("lean_back") ? s.get("lean_back").getAsFloat() : 0);
            list.add(slot);
            byId.put(slot.id(), i);
        }
        slots = List.copyOf(list);
        int n = slots.size(), k = BodyClass.values().length;
        conflicts = new byte[n][k][n];
        for (var row : conflicts) for (var inner : row) Arrays.fill(inner, (byte) NONE);
        for (int i = 0; i < n; i++) {
            var s = rawSlots.get(i).getAsJsonObject();
            if (!s.has("conflicts")) continue;
            for (var byClass : s.getAsJsonObject("conflicts").entrySet()) {
                int c = BodyClass.of(byClass.getKey()).ordinal();
                for (var other : byClass.getValue().getAsJsonObject().entrySet()) {
                    Integer j = byId.get(other.getKey());
                    if (j != null) conflicts[i][c][j] = (byte) BodyClass.of(other.getValue().getAsString()).ordinal();
                }
            }
        }
        for (int i = 0; i < n; i++) adjacency.add(new ArrayList<>());
        var edgeList = new ArrayList<Edge>();
        for (var raw : node.getAsJsonArray("edges")) {
            var e = raw.getAsJsonObject();
            var edge = new Edge(byId.get(e.get("a").getAsString()), byId.get(e.get("b").getAsString()), e.get("length").getAsDouble(),
                    e.get("rise").getAsDouble(), e.get("step").getAsBoolean(), mask(e.getAsJsonArray("classes")));
            edgeList.add(edge);
            adjacency.get(edge.a()).add(edge);
            adjacency.get(edge.b()).add(edge);
        }
        edges = List.copyOf(edgeList);
        var entryList = new ArrayList<Entry>();
        for (var raw : node.getAsJsonArray("entries")) {
            var e = raw.getAsJsonObject();
            var anchors = new EnumMap<BodyClass, Anchor>(BodyClass.class);
            for (var a : e.getAsJsonObject("anchors").entrySet()) {
                var v = a.getValue().getAsJsonObject();
                var curves = v.getAsJsonObject("curves");
                anchors.put(BodyClass.of(a.getKey()), new Anchor(vec(v.getAsJsonArray("outside")), vec(v.getAsJsonArray("bank")),
                        curves.has("from_water") ? curves.get("from_water").getAsString() : "",
                        curves.has("from_bank") ? curves.get("from_bank").getAsString()
                                : curves.has("from_ground") ? curves.get("from_ground").getAsString() : ""));
            }
            var near = e.getAsJsonArray("nearest");
            int[] nearest = new int[near.size()];
            for (int i = 0; i < nearest.length; i++) nearest[i] = byId.getOrDefault(near.get(i).getAsString(), NONE);
            var normal = e.getAsJsonArray("normal");
            entryList.add(new Entry(e.get("id").getAsString(), vec(e.getAsJsonArray("edge")), normal.get(0).getAsDouble(),
                    normal.get(1).getAsDouble(), anchors, nearest));
        }
        entries = List.copyOf(entryList);
        var tierList = new ArrayList<Tier>();
        if (node.has("larder")) for (var raw : node.getAsJsonArray("larder")) {
            var t = raw.getAsJsonObject();
            var zone = t.get("zone");
            Vec3 at = null;
            double height = 0;
            double[] offset = {0, 0};
            if (zone != null && !zone.isJsonNull()) {
                var z = zone.getAsJsonObject();
                at = new Vec3(z.get("x").getAsDouble(), z.get("y").getAsDouble(), z.get("z").getAsDouble());
                height = z.get("height").getAsDouble();
                if (z.has("visual_offset")) {
                    var o = z.getAsJsonArray("visual_offset");
                    offset = new double[]{o.get(0).getAsDouble(), o.get(1).getAsDouble()};
                }
            }
            tierList.add(new Tier(t.get("tier").getAsInt(), t.get("max_count").getAsInt(), at, height, offset,
                    excludes(t.getAsJsonObject("excludes")), ids(t.getAsJsonArray("lip"))));
        }
        tiers = List.copyOf(tierList);
        var careList = new ArrayList<Care>();
        if (node.has("care")) for (var raw : node.getAsJsonArray("care")) {
            var c = raw.getAsJsonObject();
            careList.add(new Care(byId.get(c.get("captain").getAsString()), byId.get(c.get("young").getAsString()), mask(c.getAsJsonArray("classes")),
                    mask(c.getAsJsonArray("rear")), c.has("young_yaw") ? c.get("young_yaw").getAsFloat() : 0));
        }
        care = List.copyOf(careList);
        Stow found = null;
        if (node.has("fixtures") && node.getAsJsonObject("fixtures").has("paddle_stow")) {
            var f = node.getAsJsonObject("fixtures").getAsJsonObject("paddle_stow");
            var seg = f.getAsJsonArray("segment");
            found = new Stow(vec(seg.get(0).getAsJsonArray()), vec(seg.get(1).getAsJsonArray()), f.get("radius").getAsDouble(),
                    excludes(f.getAsJsonObject("excludes")));
        }
        stow = found;
        // The per-load waterline and roll factors live in the one transform contract.
        float[][] free = null, rolls = null;
        for (var frame : read(FRAMES).getAsJsonObject("modes").entrySet()) {
            var f = frame.getValue().getAsJsonObject();
            if (f.has("hull_mode") && f.get("hull_mode").getAsString().equals(mode.name())) {
                free = table(f, "freeboard_by_load");
                rolls = table(f, "roll_by_load");
            }
        }
        freeboardByLoad = free;
        rollByLoad = rolls;
    }

    private byte[] excludes(JsonObject object) {
        var out = new byte[slots.size()];
        Arrays.fill(out, (byte) NONE);
        if (object != null) for (var e : object.entrySet()) {
            Integer j = byId.get(e.getKey());
            if (j != null) out[j] = (byte) BodyClass.of(e.getValue().getAsString()).ordinal();
        }
        return out;
    }
    private int[] ids(JsonArray array) {
        var out = new ArrayList<Integer>();
        if (array != null) for (JsonElement id : array) {
            Integer j = byId.get(id.getAsString());
            if (j != null) out.add(j);
        }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    // ---------------------------------------------------------------- lookups
    public List<Slot> slots() { return slots; }
    public Slot slot(int index) { return slots.get(index); }
    public int index(String id) { return byId.getOrDefault(id, NONE); }
    public Slot slot(String id) { int i = index(id); return i < 0 ? null : slots.get(i); }
    public List<Entry> entries() { return entries; }
    public List<Care> care() { return care; }
    public Stow stow() { return stow; }
    public List<Tier> tiers() { return tiers; }
    public Tier tier(int tier) { return tiers.isEmpty() ? null : tiers.get(Math.clamp(tier, 0, tiers.size() - 1)); }
    public List<Edge> edges() { return edges; }
    public int firstOfKind(Kind kind) {
        for (var s : slots) if (s.kind() == kind) return s.index();
        return NONE;
    }

    /** The nearest seat to a hull-local point, or {@link #NONE} when none lies within {@code tolerance}. */
    public int slotAt(Vec3 local, double tolerance) {
        int best = NONE;
        double cost = tolerance * tolerance;
        for (var s : slots) {
            double d = s.pos().distanceToSqr(local);
            if (d <= cost) { cost = d; best = s.index(); }
        }
        return best;
    }

    // ---------------------------------------------------------------- classes and load
    /** The class of a body whose growth is {@code ratio} times the captain's (hat scale divided by the mode's hull ratio). */
    public BodyClass classOf(double ratio, boolean captain, boolean gravid) {
        if (captain) return gravid ? BodyClass.GRAVID : BodyClass.ADULT;
        for (var c : BodyClass.values()) {
            if (c == BodyClass.ADULT || c == BodyClass.GRAVID) continue;
            if (ratio <= classScale[c.ordinal()] + 1e-4) return c;
        }
        return BodyClass.JUVENILE;
    }
    public double classLoad(BodyClass c) { return classLoad[c.ordinal()]; }
    public boolean classJumps(BodyClass c) { return jumps[c.ordinal()]; }

    /** Waterline sink in hat units at this load: how far the hull rides lower than empty (project design, ecology A3). */
    public double sinkUnits(double load) {
        if (freeboardByLoad == null) return 0;
        return freeboardByLoad[0][1] - interpolate(freeboardByLoad, load);
    }
    /** Roll and flooding sensitivity at this load, 1 when empty. */
    public double rollFactor(double load) {
        return rollByLoad == null ? 1 : interpolate(rollByLoad, load);
    }
    private static double interpolate(float[][] rows, double x) {
        if (x <= rows[0][0]) return rows[0][1];
        for (int i = 1; i < rows.length; i++) {
            if (x <= rows[i][0]) {
                double t = (x - rows[i - 1][0]) / Math.max(1e-9, rows[i][0] - rows[i - 1][0]);
                return rows[i - 1][1] + (rows[i][1] - rows[i - 1][1]) * t;
            }
        }
        return rows[rows.length - 1][1];
    }

    // ---------------------------------------------------------------- conflicts and larder
    /** Whether a body of class {@code ca} on seat {@code a} touches a body of class {@code cb} on seat {@code b}. */
    public boolean conflict(int a, BodyClass ca, int b, BodyClass cb) {
        if (a == b) return true;
        int m = conflicts[a][ca.ordinal()][b];
        if (m >= 0 && cb.ordinal() >= m) return true;
        int n = conflicts[b][cb.ordinal()][a];
        return n >= 0 && ca.ordinal() >= n;
    }
    /** The larder tier that a number of portions fills: 0 empty, 1 for 1-3, 2 for 4-7, 3 for 8-12. */
    public static int tierFor(int portions) { return portions <= 0 ? 0 : portions <= 3 ? 1 : portions <= 7 ? 2 : 3; }
    /** True when a body of this class on this seat is pressed by the larder tier. */
    public boolean larderExcludes(int tier, int slot, BodyClass c) {
        var t = tier(tier);
        if (t == null || tier <= 0) return false;
        int m = t.excludes()[slot];
        return m >= 0 && c.ordinal() >= m;
    }
    /** True when a body of this class on this seat is pressed by the stowed stick. */
    public boolean stowExcludes(int slot, BodyClass c) {
        if (stow == null) return false;
        int m = stow.excludes()[slot];
        return m >= 0 && c.ordinal() >= m;
    }

    // ---------------------------------------------------------------- walking
    /** Seat indices from {@code from} to {@code to} along walking edges this class fits through (inclusive), or null. */
    public int[] path(int from, int to, BodyClass c, boolean allowStep, IntPredicate blocked) {
        if (from == to) return new int[]{from};
        int n = slots.size();
        int[] previous = new int[n];
        Arrays.fill(previous, -2);
        previous[from] = -1;
        var queue = new ArrayDeque<Integer>();
        queue.add(from);
        while (!queue.isEmpty()) {
            int at = queue.poll();
            for (var edge : adjacency.get(at)) {
                if ((edge.classes() & c.mask()) == 0 || edge.step() && !allowStep) continue;
                int next = edge.a() == at ? edge.b() : edge.a();
                if (previous[next] != -2 || next != to && blocked != null && blocked.test(next)) continue;
                previous[next] = at;
                if (next == to) {
                    var out = new ArrayList<Integer>();
                    for (int i = to; i != -1; i = previous[i]) out.add(i);
                    Collections.reverse(out);
                    return out.stream().mapToInt(Integer::intValue).toArray();
                }
                queue.add(next);
            }
        }
        return null;
    }
    /** Whether any edge of the path is a step (a riser the body cannot climb unaided). */
    public boolean hasStep(int[] path) {
        for (int i = 1; i < path.length; i++) {
            for (var edge : adjacency.get(path[i - 1]))
                if ((edge.a() == path[i] || edge.b() == path[i]) && edge.step()) return true;
        }
        return false;
    }
    /** The walking length of the path in hat units. */
    public double length(int[] path) {
        double sum = 0;
        for (int i = 1; i < path.length; i++) sum += slots.get(path[i - 1]).pos().distanceTo(slots.get(path[i]).pos());
        return sum;
    }
}
