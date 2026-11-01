package io.github.longshou.mcyukkuri.aquatic;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.presentation.PresentationGoals;
import io.github.longshou.mcyukkuri.headwear.HatProp;
import io.github.longshou.mcyukkuri.headwear.PlacedHatEntity;
import io.github.longshou.mcyukkuri.family.CareDecisionLedger;
import io.github.longshou.mcyukkuri.intent.Ask;
import io.github.longshou.mcyukkuri.intent.AskLedger;
import io.github.longshou.mcyukkuri.intent.Commitment;
import io.github.longshou.mcyukkuri.intent.Desire;
import io.github.longshou.mcyukkuri.intent.Drive;
import io.github.longshou.mcyukkuri.intent.DriveWeights;
import io.github.longshou.mcyukkuri.intent.IntentRunner;
import io.github.longshou.mcyukkuri.life.BehaviorContinuity;
import io.github.longshou.mcyukkuri.motor.action.Await;
import io.github.longshou.mcyukkuri.motor.action.Beckon;
import io.github.longshou.mcyukkuri.motor.action.Hop;
import io.github.longshou.mcyukkuri.motor.action.Paddle;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;

/**
 * The aquatic life's one goal: below the escape from water (0) and the birth roles (2),
 * above the daily life, it runs one {@link Drive} over the desires read from this body's real state. An owner ashore wants the
 * water (a constant preference less what pulls her ashore, dusk and rain: {@code set_out}) or her hat back
 * ({@code resolve_own_hat}); afloat she wants the bank (hunger with nothing aboard or afloat to eat, no stick, wet weather, a mate,
 * young left ashore, dusk: {@code come_ashore}) or cruises in daylight ({@code cruise}). She eats, feeds and comforts her young,
 * and rescues a young in the water once she accepts its incident. A young boards or follows its parent ashore, eats aboard,
 * and once able tries its own hat ({@code first_voyage}), then comes ashore and takes it back. Who goes
 * first between bodies follows only from these desires (P11, Q4). Every number is in
 * {@code data/mcyukkuri/drive/aquatic.json}. While an intent runs afloat, or a committed one ashore, only an emergency takes the
 * body (the land goals cannot steer a body on the water); with no intent running any goal may.
 */
final class AquaticGoal extends Goal {
    static final int PRIORITY = 3;
    static final String DOMAIN = "aquatic";
    /** The clips of the boat life the presentation may show while this goal drives the body. */
    private static final Set<String> SHOWN = Set.of(Behaviors.BOAT_CALL, Behaviors.BOAT_DRIFT,
            Behaviors.BOAT_EAT, Behaviors.BOAT_FEED_YOUNG, Behaviors.BOAT_NUZZLE, Behaviors.FAMILY_CHEW);
    /** How long (ticks) a young she knowingly left ashore when setting out does not pull her back; how many she remembers. */
    private static final int LEFT_TICKS = 1200, LEFT_MAX = 16;

    private final YukkuriEntity body;
    private Drive drive;
    /** Young this owner set out without (asleep, in the water, not fetched, did not board), until when: no pull ashore for them. */
    private Map<UUID, Long> left;
    /** Young she comforted, until when (ticks): a young still cold or gasping is comforted again only after a while. */
    private Map<UUID, Long> comforted;
    /** Rescue runs that tried a way and failed, by incident (three and she is done with it). Bounded, never saved. */
    private Map<String, Integer> rescueFailures;
    /** A first voyage that ended unfinished: not tried again before this game tick (the next morning). Never saved. */
    private long voyageAfter;

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
        return body.isAlive() && !body.level().isClientSide && (!body.isInWaterOrBubble() || landing()) && !body.life().isSleeping()
                && !(body.getVehicle() instanceof YukkuriEntity) && (body.profile().aquatic() || AquaticLife.parent(body) != null);
    }

    private boolean landing() { return body.motor().current() instanceof Hop hop && !hop.interruptible(); }

    @Override public boolean canUse() { return eligible() && drive().wants(); }
    @Override public boolean canContinueToUse() { return eligible() && (drive().running() || drive().wants()); }
    @Override public boolean requiresUpdateEveryTick() { return true; }
    @Override public void start() { drive().urgent(); }
    @Override public void tick() { drive().tick(); }
    @Override public void stop() { drive().stop("aquatic_goal_stop"); }

    @Override public boolean isInterruptable() {
        if (landing()) return false;
        var runner = drive == null ? null : drive.runner();
        if (runner == null || BehaviorContinuity.emergency(body) || body.family().birthRole() != null) return true;
        return !(body.getVehicle() instanceof PlacedHatEntity) && runner.intent().commitment() == Commitment.NONE;
    }

    /** What the presentation shows: the paddle stroke, or the boat clip a wait plays (calling, drifting, eating, caring). */
    private String activity() {
        var action = body.motor().current();
        if (action instanceof Paddle || action instanceof Beckon) return Behaviors.BOAT_PADDLE;
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

    /** The hat's calm cell, or its block: what a route or a landing is chosen from (a stable fact while it drifts). */
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
        help(out);
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
            // Her hat out and no wish (or no way) to sail on it now: she wants it back (HEADWEAR).
            if (hat != null && (floor(setOut) == 0 || !AquaticLife.afloat(hat) || drive().failed("set_out")))
                out.add(Desire.of("resolve_own_hat", num("reclaim", 85), List.of(cell(hat), form(hat), young.size()),
                        () -> "hat=" + form(hat), () -> Ashore.reclaim(body)));
            return;
        }
        if (!AquaticLife.afloat(hat)) return;
        var at = cell(hat);
        var larder = HullLarder.of(hat);
        boolean food = larder != null && larder.has(life::acceptsFood);
        double eat = eat(body);
        if (food && eat > 0) out.add(Desire.of("eat_aboard", floor(eat + num("eat_bonus", 15)), List.of(at, larder.count()),
                () -> "nutrition=" + Math.round(life.nutrition()) + " stores=" + larder.count(), () -> Provision.eat(body)));
        // food to fish up: when hungry, or the hold runs low and has floor for it
        var catchable = stick && (eat > 0 || larder != null && larder.count() < num("stock_portions", 2) && larder.room(hat))
                ? Waters.food(hat, body, 12) : null;
        if (catchable != null) out.add(Desire.of("forage", floor(Math.max(eat, num("forage_want", 35))), List.of(at, catchable.getUUID()),
                () -> "item=" + catchable.getItem().getItem() + " at=" + GameplayLog.at(catchable.position()), () -> Provision.forage(body, catchable)));
        care(out, hat, young, at);
        // a young on dry ground that is not off on its own first voyage (one on its father's boat, in the water or in a mouth is not ashore)
        long ashore = young.stream().filter(y -> AquaticLife.ashore(y) && !leftBehind(y) && !(Drive.running(y, DOMAIN) instanceof FirstVoyage)).count();
        // no stick aboard pulls her ashore only when one lies there to be had; hunger only with nothing aboard or afloat she can eat
        boolean toHave = !stick && Waters.stick(body, 12, false) != null;
        boolean fed = food && !drive().failed("eat_aboard") || catchable != null && !drive().failed("forage");
        double pull = Math.max(Math.max(fed ? 0 : hunger, toHave ? num("stick_need", 50) : 0),
                Math.max(Math.max(weather, breed), Math.max(dusk ? num("night_pull", 60) : 0, ashore > 0 ? num("young_ashore_need", 60) : 0)));
        boolean outgrown = young.stream().anyMatch(y -> AquaticLife.aboard(y, hat) && !hat.seatFits(y));
        if (outgrown) pull = Math.max(pull, num("young_ashore_need", 60));
        var world = List.of(at, stick, young.size(), outgrown);
        Supplier<String> why = () -> String.format(Locale.ROOT, "hunger=%.0f food_aboard=%b stick=%b stick_ashore=%b weather=%.0f breed=%.0f young_ashore=%d dusk=%b rain=%b",
                hunger, food, stick, toHave, weather, breed, ashore, dusk, rain);
        out.add(Desire.of("come_ashore", floor(pull), world, why, () -> Ashore.come(body)));
        out.add(Desire.of("cruise", dusk ? 0 : floor(num("cruise_want", 30)), world, () -> "energy=" + Math.round(life.energy()), () -> new Cruise(body)));
    }

    /** How much a body that can eat wants to now (0 when it cannot or is not hungry). */
    private static double eat(YukkuriEntity y) {
        return y.life().canEat() ? Math.max(0, num("eat_from", 70) - y.life().nutrition()) * num("eat_slope", 3) : 0;
    }

    /** Her decided help: a young of hers in the water whose incident she accepted (one decision each; a rescue under way
     *  keeps its desire). */
    private void help(List<Desire> out) {
        var ledger = AskLedger.server();
        if (drive().runner() != null && drive().runner().intent() instanceof Rescue r) out.add(rescue(r.faller(), r.incident(), null));
        else for (var y : AquaticLife.young(body)) {
            String id = y.isInWaterOrBubble() ? ledger.openOf(y.getUUID(), "water") : null;
            if (id == null) continue;
            var ask = new Ask(y.getUUID(), body.getUUID(), h -> new Rescue(h, y, id, this::rescueFailed), id);
            ledger.decide(ask, body.life().personality().helpProbability(true, true), body.getRandom()::nextDouble, body);
            if (ledger.helping(id, body.getUUID())) { out.add(rescue(y, id, ask)); break; }
        }
    }

    /** The world a failed rescue waits on: the incident, whether she rides her hat, where the faller is. */
    private Desire rescue(YukkuriEntity faller, String incident, Ask ask) {
        return new Desire("rescue", num("rescue_want", 95), 0, true, List.of(incident, AquaticLife.riding(body) != null, faller.blockPosition()),
                () -> "faller=" + faller.getUUID() + " incident=" + incident, () -> ask == null ? null : ask.intent(body));
    }

    /** A rescue run that tried and failed: after {@code rescue_tries} of them she is done with this incident. */
    private void rescueFailed(String incident) {
        if (rescueFailures == null) rescueFailures = new LinkedHashMap<>();
        int failures = rescueFailures.merge(incident, 1, Integer::sum);
        GameplayLog.event(body, "aquatic", "rescue failed incident={} failures={}", incident, failures);
        if (failures >= num("rescue_tries", 3)) {
            rescueFailures.remove(incident);
            AskLedger.server().ended(incident, body.getUUID(), "EXHAUSTED");
        }
        while (rescueFailures.size() > LEFT_MAX) rescueFailures.remove(rescueFailures.keySet().iterator().next());
    }

    /** Care for her young aboard: the hungriest that cannot eat by itself and has food aboard for it, her decision accepted (the
     *  care ledger's one draw, kept 1200 ticks); one gasping after the water or cold. */
    private void care(List<Desire> out, PlacedHatEntity hat, List<YukkuriEntity> young, Object at) {
        var held = body.family().carrying();
        var larder = HullLarder.of(hat);
        YukkuriEntity hungry = null, gasping = null;
        for (var y : young) {
            if (y.getVehicle() != hat) continue;
            if (Care.upset(y) && gasping == null && !recently(y)) gasping = y;
            if (!Care.hungry(body, y) || body.family().careDecisions().refused(y, CareDecisionLedger.Need.FEED)) continue;
            boolean food = !held.isEmpty() ? y.life().canReceiveCareFood(held) : larder != null && larder.has(one -> y.life().canReceiveCareFood(one));
            if (food && (hungry == null || y.life().nutrition() < hungry.life().nutrition())) hungry = y;
        }
        var fed = hungry;
        if (fed != null && body.family().careDecisions().decide(fed, CareDecisionLedger.Need.FEED, fed.life().nutrition() < 30).accepted())
            out.add(Desire.of("feed_young", floor(num("feed_want", 55) + Math.max(0, 50 - fed.life().nutrition())), List.of(at, fed.getUUID()),
                    () -> "child=" + fed.getUUID() + " nutrition=" + Math.round(fed.life().nutrition()), () -> Care.feed(body, fed)));
        var calmed = gasping;
        if (calmed != null) out.add(Desire.of("comfort_young", floor(num("comfort_want", 45)), List.of(at, calmed.getUUID()),
                () -> "child=" + calmed.getUUID(), () -> { comforted(calmed); return Care.comfort(body, calmed); }));
    }

    private boolean recently(YukkuriEntity young) {
        if (comforted == null) return false;
        long now = body.level().getGameTime();
        comforted.values().removeIf(until -> until <= now);
        return comforted.containsKey(young.getUUID());
    }

    private void comforted(YukkuriEntity young) {
        if (comforted == null) comforted = new HashMap<>();
        if (comforted.size() < LEFT_MAX) comforted.put(young.getUUID(), body.level().getGameTime() + (long) num("comfort_again", 600));
    }

    private void young(List<Desire> out) {
        // back from its own hat: ashore with it, or the hat taken back
        var own = AquaticLife.hat(body);
        if (own != null && AquaticLife.aboard(body, own) && AquaticLife.afloat(own))
            out.add(Desire.of("come_ashore", floor(num("young_return", 60)), List.of(cell(own)), () -> "own_hat=afloat", () -> Ashore.come(body)));
        else if (own != null && AquaticLife.ashore(body))
            out.add(Desire.of("resolve_own_hat", num("reclaim", 85), List.of(cell(own), form(own), body.blockPosition()), () -> "hat=" + form(own),
                    () -> Ashore.reclaim(body)));
        var ride = AquaticLife.riding(body);
        var larder = HullLarder.of(ride);
        double eat = eat(body);
        if (eat > 0 && larder != null && larder.has(body.life()::acceptsFood))
            out.add(Desire.of("eat_aboard", floor(eat + num("eat_bonus", 15)), List.of(cell(ride), larder.count()),
                    () -> "nutrition=" + Math.round(body.life().nutrition()), () -> Provision.eat(body)));
        // a parent offering it food aboard: it turns to her and keeps still
        for (var p : AquaticLife.parents(body)) if (ride != null && p.getVehicle() == ride && Care.feeding(p, body)) {
            out.add(Desire.of("be_fed", floor(num("be_fed_want", 75)), List.of(p.getUUID()), () -> "parent=" + p.getUUID(), () -> new Care.Taking(body, p)));
            break;
        }
        var parent = AquaticLife.parent(body);
        var hat = parent == null ? null : AquaticLife.hat(parent);
        if (!AquaticLife.afloat(hat)) return;
        boolean on = AquaticLife.aboard(body, hat), parentOn = AquaticLife.aboard(parent, hat);
        var running = IntentRunner.running(parent);
        Supplier<String> why = () -> "parent=" + parent.getUUID() + " parent_aboard=" + parentOn
                + " doing=" + (running == null ? "none" : running.intent().id());
        var world = List.of(cell(hat), body.blockPosition(), parentOn);
        voyage(out, parent, hat);
        if (!on && AquaticLife.ashore(body) && AquaticLife.jumper(body) && body.distanceTo(hat) < AquaticLife.FAMILY_RANGE
                && (parentOn || parent.distanceTo(hat) < 4 && running != null && running.intent() instanceof SetOut))
            out.add(Desire.of("board_parent", num("follow", 65), world, why, () -> Follow.board(body, hat)));
        if (on && AquaticLife.jumper(body) && (AquaticLife.ashore(parent) && !(running != null && running.intent() instanceof SetOut)
                || running != null && running.intent() instanceof Ashore || !hat.seatFits(body)) && Waters.ashore(body) != null)
            out.add(Desire.of("follow_ashore", num("follow", 65), world, why, () -> Follow.ashore(body, hat)));
    }

    /**
     * A young able to handle its own hat that has not yet (solid food, hopping, learning, a fifth of an adult's size), wearing it
     * with a stick for it (tucked in it, or loose near), not tired, its parent aboard her own hat afloat on a calm day, and it on the
     * bank or aboard her hat by one: it wants to try (first_voyage), the bolder the more. Kept while it runs.
     */
    private void voyage(List<Desire> out, YukkuriEntity parent, PlacedHatEntity hat) {
        var life = body.life();
        var child = body.childhood();
        boolean running = drive().runner() != null && drive().runner().intent() instanceof FirstVoyage;
        if (!running && (child.canSail() || body.level().getGameTime() < voyageAfter || !child.canEatSolid() || !child.canLearn() || !AquaticLife.jumper(body)
                || body.family().scale() < num("voyage_scale", .2) || !HatProp.usable(body) || !life.custody().worn()
                || life.energy() < num("voyage_energy", 30) || AquaticLife.dusk(body.level()) || AquaticLife.rain(body) || !AquaticLife.aboard(parent, hat)
                || !(AquaticLife.ashore(body) || AquaticLife.aboard(body, hat) && !Waters.edges(body, body.position(), 2, false).isEmpty())
                || !AquaticLife.stick(body) && Waters.stick(body, 10, true) == null)) return;
        out.add(Desire.of("first_voyage", floor(num("first_voyage_want", 40) + life.personality().boldness() * num("first_voyage_bold", .4)),
                List.of(body.blockPosition(), cell(hat), AquaticLife.stick(body)),
                () -> "parent=" + parent.getUUID() + " boldness=" + life.personality().boldness() + " scale=" + body.family().scale(),
                () -> new FirstVoyage(body, parent, () -> drive().runner(), this::voyageFailed)));
    }

    /** An unfinished first voyage: the young tries again from the next morning. */
    private void voyageFailed() {
        long day = Math.floorMod(body.level().getDayTime(), 24000L);
        voyageAfter = body.level().getGameTime() + 24000 - day;
        GameplayLog.event(body, "aquatic", "first_voyage cooldown until={} in={}", voyageAfter, 24000 - day);
    }

    /** The hat's form for the reclaim line: upright afloat, turned over, on the ground. */
    static String form(PlacedHatEntity hat) {
        return hat.upsideDown() ? "capsized" : AquaticLife.afloat(hat) ? "afloat" : "ground";
    }
}
