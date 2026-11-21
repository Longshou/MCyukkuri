package io.github.longshou.mcyukkuri.play;

import com.google.gson.JsonParser;
import io.github.longshou.mcyukkuri.pet.rules.HouseRule;
import io.github.longshou.mcyukkuri.pet.rules.RulesMath;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The orange-juice tub's numbers and rules, free of Minecraft so they can be tested: the juice level, how a soak pays out, which
 * seat a body of a given width takes and the route over the stool and rim. Lengths are 1/16 block from the tub's root floor, as
 * authored in {@code data/mcyukkuri/playgrounds/yukkuri_pool.json} ({@code tub}).
 */
public final class JuiceTub {
    /** The fill mesh draws levels 1-4; one orange juice item is one level. */
    public static final int CAPACITY = 4;
    /** One soak step is two seconds in the juice; a whole soak is six steps and uses up one level. */
    public static final int STEP_TICKS = 40, STEPS = 6;
    public static final int SOAK_TICKS = STEP_TICKS * STEPS;
    /** Cleanliness gained each step; marks (grime) are washed every third step, so a full soak lifts about two grades. */
    public static final float HYGIENE_STEP = 8;
    /** Unease eased each step before the individual's shift: a whole soak lifts about one stage band (18-24 of 0-100). */
    public static final float UNEASE_STEP = 3, UNEASE_SHIFT = 1;
    /** A bench seat's centres are 7 units apart (.4375 block): the widest body that sits there. */
    public static final double SMALL_MAX_WIDTH = .40;
    /** Adult side seats are 9 units apart (.5625 block); the lone middle seat has the floor's 15-unit depth less the leaning walls. */
    public static final double SIDE_MAX_WIDTH = .55, ALONE_MAX_WIDTH = .84;
    private static final double RIM_Z = -15, BENCH_ENTRY_Z = -11.5, APPROACH_GAP = 4;

    public record Seat(String id, boolean small, double x, double z, double y, double[] soak) {
        /** The widest body that fits this seat (blocks). */
        public double maxWidth() { return small ? SMALL_MAX_WIDTH : x == 0 ? ALONE_MAX_WIDTH : SIDE_MAX_WIDTH; }
        /** How deep the body sits in the juice at {@code level} (1/16 block); 0 when the juice does not reach it. */
        public double depth(int level) { return level <= 0 ? 0 : soak[Math.min(level, CAPACITY) - 1]; }
    }

    public record Layout(List<Seat> seats, double stoolTop, double benchTop, double rimTop, double stoolFrontZ) {}

    /** Points of a soak in local blocks {x, y, z}; {@code seat} is the index of the seat point. */
    public record Route(List<double[]> points, int seat) {}

    private static Layout layout;

    public static synchronized Layout layout() {
        if (layout == null) {
            try (var reader = new InputStreamReader(JuiceTub.class.getResourceAsStream("/data/mcyukkuri/playgrounds/yukkuri_pool.json"), StandardCharsets.UTF_8)) {
                var tub = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("tub");
                var seats = new ArrayList<Seat>();
                for (var raw : tub.getAsJsonArray("seats")) {
                    var s = raw.getAsJsonObject();
                    var soak = s.getAsJsonArray("soak");
                    var depths = new double[soak.size()];
                    for (int i = 0; i < depths.length; i++) depths[i] = soak.get(i).getAsDouble();
                    seats.add(new Seat(s.get("id").getAsString(), s.get("size").getAsString().equals("small"), s.get("x").getAsDouble(),
                            s.get("z").getAsDouble(), s.get("y").getAsDouble(), depths));
                }
                layout = new Layout(List.copyOf(seats), tub.get("stool_top").getAsDouble(), tub.get("bench_top").getAsDouble(),
                        tub.get("rim_top").getAsDouble(), tub.getAsJsonArray("stool").get(1).getAsDouble());
            } catch (IOException error) { throw new UncheckedIOException(error); }
        }
        return layout;
    }

    public static boolean full(int level) { return level >= CAPACITY; }
    /** One item is one level; a full tub takes nothing. */
    public static int filled(int level) { return Math.min(CAPACITY, level + 1); }
    /** A completed soak uses up one level. */
    public static int drained(int level) { return Math.max(0, level - 1); }

    public static float healthStep(float maxHealth) { return maxHealth * .04f; }
    /** A wound closes one grade every second step, so a soak eases three grades and a deep wound needs several soaks. */
    public static boolean mendsWound(int step) { return step % 2 == 0; }
    public static boolean washesMarks(int step) { return step % 3 == 0; }

    /** What one soak step pays a body: only a hurt one regains health and, every second step, closes one wound grade. */
    public record Payout(boolean heal, boolean mend) {}
    public static Payout payout(boolean hurt, int step) { return new Payout(hurt, hurt && mendsWound(step)); }

    /** Unease eased per step; a timid body settles a little deeper in the warm juice. */
    public static float uneaseStep(double timid) { return UNEASE_STEP + UNEASE_SHIFT * (float) timid; }

    /** The house rule that speaks to this body: a hurt one answers the hurt ban, a healthy one the healthy ban. */
    public static HouseRule tubRule(boolean hurt) {
        return hurt ? HouseRule.NO_TUB_WHEN_HURT : HouseRule.NO_TUB_WHEN_HEALTHY;
    }

    /** A body may soak unless it held or waited on the ban; an unbound rule ({@code ALLOW}) or a break both let it in. */
    public static boolean permitsSoak(RulesMath.Choice choice) {
        return choice != RulesMath.Choice.HOLD && choice != RulesMath.Choice.WAIT;
    }

    /** The seats a body of {@code width} fits at {@code level}: its size class, wide enough, and reached by the juice. */
    public static List<Seat> seatsFor(Layout layout, double width, int level) {
        boolean small = width <= SMALL_MAX_WIDTH;
        return layout.seats().stream().filter(s -> s.small() == small && width <= s.maxWidth() && s.depth(level) > 0).toList();
    }

    /** The widest body the tub takes at all. */
    public static boolean tooBig(double width) { return width > ALONE_MAX_WIDTH; }

    /** Over the stool and rim, across the bench to the seat, and the same way back out. */
    public static Route route(Layout layout, Seat seat) {
        double u = 1 / 16.0;
        var approach = new double[]{0, 0, (layout.stoolFrontZ() - APPROACH_GAP) * u};
        var stool = new double[]{0, layout.stoolTop() * u, (layout.stoolFrontZ() + 2.4) * u};
        var rim = new double[]{0, layout.rimTop() * u, RIM_Z * u};
        var bench = new double[]{0, layout.benchTop() * u, BENCH_ENTRY_Z * u};
        var at = new double[]{seat.x() * u, seat.y() * u, seat.z() * u};
        return new Route(List.of(approach, stool, rim, bench, at, bench, rim, stool, approach), 4);
    }

    private JuiceTub() {}
}
