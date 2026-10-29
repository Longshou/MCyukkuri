package io.github.longshou.mcyukkuri.aquatic;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.presentation.PresentationGoals;
import io.github.longshou.mcyukkuri.headwear.HatProp;
import io.github.longshou.mcyukkuri.headwear.PlacedHatEntity;
import io.github.longshou.mcyukkuri.intent.Commitment;
import io.github.longshou.mcyukkuri.intent.Desire;
import io.github.longshou.mcyukkuri.intent.Drive;
import io.github.longshou.mcyukkuri.intent.DriveWeights;
import io.github.longshou.mcyukkuri.intent.IntentRunner;
import io.github.longshou.mcyukkuri.life.BehaviorContinuity;
import io.github.longshou.mcyukkuri.motor.action.Await;
import io.github.longshou.mcyukkuri.motor.action.Paddle;
import io.github.longshou.mcyukkuri.motor.surface.Occupancy;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;

/**
 * The aquatic life's one goal (refactor WP6a, spec 14 WP6a, design C4): below the escape from water (0) and the birth roles (2),
 * above the daily life, it runs one {@link Drive} over the desires read from this body's real state. An owner ashore wants the
 * water (a constant preference less what pulls her ashore, dusk and rain: {@code set_out}) or her hat back
 * ({@code resolve_own_hat}); afloat she wants the bank (hunger, no stick, wet weather, a mate, young left ashore:
 * {@code come_ashore}), a sheltered mooring at dusk or in rain ({@code moor}) or just to be out on the water ({@code cruise}).
 * A young follows its parent's hat on or off ({@code board_parent}, {@code follow_ashore}) and creeps into the hollow at night
 * ({@code nestle}). Who goes first between bodies follows only from these desires (P11, Q4). Every number is in
 * {@code data/mcyukkuri/drive/aquatic.json}. While an intent runs afloat, or a committed one ashore, only an emergency takes the
 * body (the land goals cannot steer a body on the water); with no intent running any goal may.
 */
final class AquaticGoal extends Goal {
    static final int PRIORITY = 3;
    static final String DOMAIN = "aquatic";
    /** The clips of the boat life the presentation may show while this goal drives the body. */
    private static final Set<String> SHOWN = Set.of(Behaviors.BOAT_SLEEP, Behaviors.BOAT_COVER_YOUNG, Behaviors.BOAT_CALL, Behaviors.BOAT_DRIFT);
    /** How long (ticks) a young she knowingly left ashore when setting out does not pull her back; how many she remembers. */
    private static final int LEFT_TICKS = 1200, LEFT_MAX = 16;

    private final YukkuriEntity body;
    private Drive drive;
    /** Young this owner set out without (asleep, in the water, not fetched, did not board), until when: no pull ashore for them. */
    private Map<UUID, Long> left;

    private AquaticGoal(YukkuriEntity body) {
        this.body = body;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP));
    }

    /** The factory the base calls in the entity's constructor: the goal, bound to the presentation under its activity. */
    static Goal bound(YukkuriEntity body, PresentationGoals presentation) {
        var goal = new AquaticGoal(body);
        return presentation.bind(new PresentationGoals.Source<AquaticGoal>(DOMAIN, (e, g) -> g.activity(), PresentationGoals.Actions.ORDINARY), goal);
    }

    static double num(String key, double fallback) { return DriveWeights.of(DOMAIN, key, fallback); }

    private Drive drive() {
        if (drive == null) drive = new Drive(body, DOMAIN, this::desires, () -> {
            var hat = AquaticLife.hat(body);
            return new IntentRunner.Target(body.position(), hat == null ? null : hat.position(), Set.of());
        });
        return drive;
    }

    /** An aquatic body, or the young of a parent whose hat is out; awake, not in the water (the escape owns it), not carried. */
    private boolean eligible() {
        return body.isAlive() && !body.level().isClientSide && !body.isInWaterOrBubble() && !body.life().isSleeping()
                && !(body.getVehicle() instanceof YukkuriEntity) && (body.profile().aquatic() || AquaticLife.parent(body) != null);
    }

    @Override public boolean canUse() { return eligible() && drive().wants(); }
    @Override public boolean canContinueToUse() { return eligible() && (drive().running() || drive().wants()); }
    @Override public boolean requiresUpdateEveryTick() { return true; }
    @Override public void start() { drive().urgent(); }
    @Override public void tick() { drive().tick(); }
    @Override public void stop() { drive().stop("aquatic_goal_stop"); }

    @Override public boolean isInterruptable() {
        var runner = drive == null ? null : drive.runner();
        if (runner == null || BehaviorContinuity.emergency(body) || body.family().birthRole() != null) return true;
        return !(body.getVehicle() instanceof PlacedHatEntity) && runner.intent().commitment() == Commitment.NONE;
    }

    /** What the presentation shows: the paddle stroke, or the boat clip a wait plays (sleep, covering the young, calling, drifting). */
    private String activity() {
        var action = body.motor().current();
        if (action instanceof Paddle) return Behaviors.BOAT_PADDLE;
        return action instanceof Await && SHOWN.contains(action.pose().clip()) ? action.pose().clip() : null;
    }

    /** SetOut's report of the young it left ashore. */
    private void leftAshore(Map<UUID, String> young) {
        if (left == null) left = new HashMap<>();
        long until = body.level().getGameTime() + LEFT_TICKS;
        for (var id : young.keySet()) if (left.size() < LEFT_MAX || left.containsKey(id)) left.put(id, until);
    }

    private boolean leftBehind(YukkuriEntity young) {
        if (left == null) return false;
        long now = body.level().getGameTime();
        left.values().removeIf(until -> until <= now);
        return left.containsKey(young.getUUID());
    }

    // ---------------------------------------------------------------- desires (one evaluation reads the world once)
    private List<Desire> desires() {
        var out = new ArrayList<Desire>();
        if (body.family().adult()) { if (HatProp.usable(body) && !body.life().custody().lost()) owner(out); }
        else young(out);
        return out;
    }

    private static double floor(double desire) { return desire >= num("floor", 20) ? desire : 0; }

    /** The hat's calm cell, or its block: what a route, a landing or a notch is chosen from (a stable fact while it drifts). */
    private static BlockPos cell(PlacedHatEntity hat) {
        var c = Waters.cell(hat);
        return c != null ? c : hat.blockPosition();
    }

    private void owner(List<Desire> out) {
        var hat = AquaticLife.hat(body);
        boolean aboard = AquaticLife.aboard(body, hat);
        if (body.isPassenger() && !aboard) return;
        var life = body.life();
        boolean dusk = AquaticLife.dusk(body.level()), rain = AquaticLife.rain(body), stick = AquaticLife.stick(body);
        double hunger = Math.max(0, num("hunger_from", 55) - life.nutrition()) * num("hunger_slope", 3);
        double weather = body.level().isThundering() ? num("thunder_need", 60) : rain && life.wetness() >= num("wet_from", 60) ? num("wet_need", 70) : 0;
        double breed = body.family().ready(false) && body.family().partner() != null ? num("breed_need", 35) : 0;
        var young = AquaticLife.young(body);
        if (!aboard) {
            boolean nearStick = !stick && Waters.stick(body, 10, true) != null;
            double noStick = stick || nearStick ? 0 : num("stick_need", 50);
            double pull = Math.max(Math.max(hunger, noStick), Math.max(weather, breed));
            boolean asleep = young.stream().anyMatch(y -> y.life().isSleeping() && !AquaticLife.aboard(y, hat));
            double setOut = asleep ? 0 : num("water_life", 60) - 2 * pull - (dusk ? num("night_pull", 60) : 0) - (rain ? num("rain_pull", 30) : 0);
            var world = List.of(body.blockPosition(), stick, nearStick, young.size());
            boolean usable = hat == null ? life.custody().worn() : AquaticLife.afloat(hat);
            if (usable) out.add(Desire.of("set_out", floor(setOut), world, () -> String.format(Locale.ROOT,
                    "pull=%.0f hunger=%.0f no_stick=%.0f weather=%.0f breed=%.0f dusk=%b rain=%b young=%d asleep=%b",
                    pull, hunger, noStick, weather, breed, dusk, rain, young.size(), asleep), () -> new SetOut(body, this::leftAshore)));
            // Her hat out and no wish (or no way) to sail on it now: she wants it back (design 8.1, HEADWEAR).
            if (hat != null && (floor(setOut) == 0 || !AquaticLife.afloat(hat) || drive().failed("set_out")))
                out.add(Desire.of("resolve_own_hat", num("reclaim", 85), List.of(cell(hat), form(hat), young.size()),
                        () -> "hat=" + form(hat), () -> Ashore.reclaim(body)));
            return;
        }
        if (!AquaticLife.afloat(hat)) return;
        // until WP6b lets her eat from the hollow, food aboard does not still her hunger
        boolean food = hat.load() > 0;
        long ashore = young.stream().filter(y -> !AquaticLife.aboard(y, hat) && !leftBehind(y)).count();
        // no stick aboard pulls her ashore only when one lies there to be had
        boolean toHave = !stick && Waters.stick(body, 12, false) != null;
        double pull = Math.max(Math.max(hunger, toHave ? num("stick_need", 50) : 0),
                Math.max(Math.max(weather, breed), ashore > 0 ? num("young_ashore_need", 60) : 0));
        var at = cell(hat);
        var world = List.of(at, stick, young.size());
        Supplier<String> why = () -> String.format(Locale.ROOT, "hunger=%.0f food_aboard=%b stick=%b stick_ashore=%b weather=%.0f breed=%.0f young_ashore=%d dusk=%b rain=%b",
                hunger, food, stick, toHave, weather, breed, ashore, dusk, rain);
        out.add(Desire.of("come_ashore", floor(pull), world, why, () -> Ashore.come(body)));
        out.add(Desire.of("moor", floor((dusk ? num("moor_night", 65) : 0) + (rain ? num("moor_rain", 40) : 0)), world, why, () -> new Moor(body)));
        out.add(Desire.of("cruise", dusk ? 0 : floor(num("cruise_want", 30)), world, () -> "energy=" + Math.round(life.energy()), () -> new Cruise(body)));
    }

    private void young(List<Desire> out) {
        var parent = AquaticLife.parent(body);
        var hat = parent == null ? null : AquaticLife.hat(parent);
        if (!AquaticLife.afloat(hat)) return;
        boolean on = AquaticLife.aboard(body, hat), parentOn = AquaticLife.aboard(parent, hat);
        var running = IntentRunner.running(parent);
        Supplier<String> why = () -> "parent=" + parent.getUUID() + " parent_aboard=" + parentOn
                + " doing=" + (running == null ? "none" : running.intent().id());
        var world = List.of(cell(hat), body.blockPosition(), parentOn);
        if (!on && AquaticLife.ashore(body) && AquaticLife.jumper(body) && body.distanceTo(hat) < AquaticLife.FAMILY_RANGE
                && (parentOn || parent.distanceTo(hat) < 4 && running != null && running.intent() instanceof SetOut))
            out.add(Desire.of("board_parent", num("follow", 65), world, why, () -> Follow.board(body, hat)));
        if (on && AquaticLife.jumper(body) && (AquaticLife.ashore(parent) && !(running != null && running.intent() instanceof SetOut)
                || running != null && running.intent() instanceof Ashore) && Waters.ashore(body) != null)
            out.add(Desire.of("follow_ashore", num("follow", 65), world, why, () -> Follow.ashore(body, hat)));
        if (on && (AquaticLife.dusk(body.level()) || AquaticLife.rain(body))
                && Occupancy.of(body, hat.container()) != Occupancy.INSIDE_SUPPORTED && AquaticLife.freeSpot(hat, body) != null)
            out.add(Desire.of("nestle", num("nestle_want", 40), world, why, () -> Follow.nestle(body, hat)));
    }

    /** The hat's form for the reclaim line: upright afloat, turned over, on the ground. */
    static String form(PlacedHatEntity hat) {
        return hat.upsideDown() ? "capsized" : AquaticLife.afloat(hat) ? "afloat" : "ground";
    }
}
