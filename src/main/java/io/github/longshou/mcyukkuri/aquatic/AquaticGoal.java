package io.github.longshou.mcyukkuri.aquatic;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.presentation.PresentationGoals;
import io.github.longshou.mcyukkuri.headwear.HatProp;
import io.github.longshou.mcyukkuri.headwear.HullBounds;
import io.github.longshou.mcyukkuri.headwear.PlacedHatEntity;
import io.github.longshou.mcyukkuri.family.CareDecisionLedger;
import io.github.longshou.mcyukkuri.family.CareApproach;
import io.github.longshou.mcyukkuri.family.ParentCarry;
import io.github.longshou.mcyukkuri.family.FamilyLife;
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
            Behaviors.BOAT_EAT, Behaviors.BOAT_FEED_YOUNG, Behaviors.BOAT_NUZZLE, Behaviors.FAMILY_CHEW,
            Behaviors.KID_BRIM_SPLASH, Behaviors.KID_MIMIC_PADDLE, Behaviors.BOAT_RIM_PEEK, Behaviors.KID_HIDE_BEHIND, Behaviors.BOAT_TOILET);
    /** How long (ticks) a young she knowingly left ashore when setting out does not pull her back; how many she remembers. */
    private static final int LEFT_TICKS = 1200, LEFT_MAX = 16;

    private final YukkuriEntity body;
    private Drive drive;
    /** Young this owner set out without (asleep, in the water, not fetched, did not board), until when: no pull ashore for them. */
    private Map<UUID, Long> left;
    /** Young she comforted, until when (ticks): a young still cold or gasping is comforted again only after a while. */
    private Map<UUID, Long> comforted;
    /** A first voyage that ended unfinished: not tried again before this game tick (the next morning). Never saved. */
    private long voyageAfter;
    private long fearTick = Long.MIN_VALUE;
    private String fear;
    /** The brood whose late interval was already reported: the shore return is announced once per brood. */
    private UUID shoreBrood;

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

    /** An aquatic body, or the young of a parent whose hat is out; awake, not carried. Water belongs to the escape goal except
     *  while this drive's own reclaim owns the water way. */
    private boolean eligible() {
        return body.isAlive() && !body.level().isClientSide && (!body.isInWaterOrBubble() || landing() || ownsWaterWay())
                && !body.life().isSleeping() && !heldInBraidOrMouth() && !body.family().carry().setDownDue()
                && (body.profile().aquatic() || AquaticLife.parent(body) != null);
    }

    /** A body a carrier holds is driven by it; a young sitting on her head keeps its own day (it can look, hide and be fed there). */
    private boolean heldInBraidOrMouth() {
        return body.getVehicle() instanceof YukkuriEntity carrier && ParentCarry.hold(carrier, body) != ParentCarry.Hold.HEAD;
    }

    private boolean ownsWaterWay() {
        var intent = Drive.running(body, DOMAIN);
        return intent != null && intent.ownsBodyInWater();
    }

    private boolean landing() { return body.motor().current() instanceof Hop hop && !hop.interruptible(); }

    @Override public boolean canUse() { return eligible() && drive().wants(); }
    @Override public boolean canContinueToUse() { return eligible() && (drive().running() || drive().wants()); }
    @Override public boolean requiresUpdateEveryTick() { return true; }
    @Override public void start() { drive().urgent(); }
    @Override public void tick() {
        if (Drive.running(body, DOMAIN) instanceof Care care && care.tick() == CareApproach.Result.FAILED) {
            drive().stop("care_approach_failed");
            return;
        }
        if (Drive.running(body, DOMAIN) instanceof SetOut && body.family().shoreBirthDue()
                && (body.motor().current() == null || body.motor().current().interruptible())) drive().stop("birth_lead");
        if (!body.social().greeting()) drive().tick();
    }
    @Override public void stop() { drive().stop("aquatic_goal_stop"); }

    @Override public boolean isInterruptable() {
        if (landing()) return false;
        var runner = drive == null ? null : drive.runner();
        if (runner == null || BehaviorContinuity.emergency(body) || body.family().birthRole() != null || body.careApproach().waiting()
                || body.family().carry().setDownDue()) return true;
        return !(body.getVehicle() instanceof PlacedHatEntity) && runner.intent().commitment() == Commitment.NONE;
    }

    /** What the presentation shows: the paddle stroke, or the boat clip a wait plays (calling, drifting, eating, caring). */
    private String activity() {
        var action = body.motor().current();
        if (action instanceof Paddle) return Behaviors.BOAT_PADDLE;
        if (action instanceof Beckon) return Behaviors.BANK_BECKON;
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
        if (body.family().adult()) {
            help(out);
            if (HatProp.usable(body) && !body.life().custody().lost()) owner(out);
        }
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
        // The birth's own lead also forbids departures after landing, until delivery is complete.
        var brood = body.family().brood();
        boolean birth = body.family().shoreBirthDue();
        double birthWant = birth ? num("birth_ashore_want", 200) : 0;
        if (birth && !brood.id.equals(shoreBrood)) {
            shoreBrood = brood.id;
            GameplayLog.event(body, "aquatic", "birth shore return brood={} remaining={} aboard={} reason=birth_lead lead={}",
                    brood.id, brood.duration - brood.elapsed, aboard, FamilyLife.shoreBirthLead());
        }
        var young = AquaticLife.young(body);
        // Finish the first meal/toilet care ashore; a crawling child can gather for a braid lift without learning to jump.
        var unloadedNewborns = young.stream().filter(y -> !y.isPassenger() && y.family().stage() == FamilyLife.Stage.BABY && y.childhood().isNewborn()
                && y.childhood().shouldStayWithParents()).toList();
        var nursing = AquaticLife.nursing(body);
        boolean nursery = !nursing.isEmpty();
        if (!aboard) {
            if (birth && hat != null) out.add(Desire.of("come_ashore", floor(birthWant), List.of(cell(hat), birth),
                    () -> "birth_lead", () -> Ashore.come(body)));
            if (nursery && AquaticLife.ashore(body)) GameplayLog.limited(body, "aquatic", "nursery.ashore", 1200,
                    "set_out held reason=newborn_care children={}", nursing.stream().map(YukkuriEntity::getUUID).toList());
            // A missing stick never keeps her ashore: SetOut takes one lying (or afloat beside the bank) within reach first, and afloat
            // she goes for sticks on the water or comes ashore for one (fetch_stick, come_ashore).
            boolean nearStick = !stick && Waters.stick(body, 10, true) != null;
            double pull = Math.max(Math.max(hunger, weather), breed);
            boolean asleep = young.stream().anyMatch(y -> y.life().isSleeping() && !AquaticLife.aboard(y, hat));
            boolean beyondGather = SetOut.dependentBeyondGatherRange(body);
            // a search for a child ends at the child (greeting, halt) before she turns to the water
            boolean searching = body.family().searchingChild();
            double setOut = asleep || beyondGather || searching || birth || nursery ? 0 : num("water_life", 60) - 2 * pull - (dusk ? num("night_pull", 60) : 0) - (rain ? num("rain_pull", 30) : 0);
            var world = List.of(body.blockPosition(), stick, nearStick, young.size(), beyondGather, searching, birth, nursery);
            boolean usable = hat == null ? life.custody().worn() : AquaticLife.afloat(hat);
            if (AquaticLife.ashore(body) && (!usable || floor(setOut) == 0 || drive().failed("set_out"))) {
                for (var rider : body.family().carry().riders()) if (ParentCarry.hold(body, rider) == ParentCarry.Hold.HEAD) {
                    out.add(Desire.of("set_down_young", PlayAboard.number("hide_want"), List.of(body.blockPosition(), rider.getUUID()),
                            () -> "head_rider_ashore child=" + rider.getUUID(), () -> new Care.Transfer(body, rider, null)));
                    break;
                }
                // The young still riding the afloat hull come ashore too, through the family landing: a capable one hops off by
                // itself, a dependent one she takes over the rim from the bank, or boards for when the bank does not reach it.
                var rider = youngAboard(body, hat);
                if (rider != null) out.add(Desire.of("unload_young", PlayAboard.number("hide_want"),
                        List.of(cell(hat), rider.getUUID()), () -> "aboard child=" + rider.getUUID(), () -> Ashore.come(body)));
            }
            if (usable) out.add(Desire.of("set_out", floor(setOut), world, () -> String.format(Locale.ROOT,
                    "pull=%.0f hunger=%.0f weather=%.0f breed=%.0f stick=%b stick_near=%b dusk=%b rain=%b young=%d asleep=%b beyond_gather=%b searching=%b nursery=%b",
                    pull, hunger, weather, breed, stick, nearStick, dusk, rain, young.size(), asleep, beyondGather, searching, nursery), () -> new SetOut(body, this::leftAshore)));
            // Newborns ashore go through family loading, never the solo reclaim that may finish aboard without them.
            if (hat != null && !birth && !nursery && unloadedNewborns.isEmpty() && !beyondGather
                    && (floor(setOut) == 0 || !AquaticLife.afloat(hat) || drive().failed("set_out")))
                out.add(Desire.of("resolve_own_hat", num("reclaim", 85), List.of(cell(hat), form(hat), young.size()),
                        () -> "hat=" + form(hat), () -> Ashore.reclaim(body)));
            return;
        }
        if (!AquaticLife.afloat(hat)) return;
        // A hull rocking hard, going over or being righted is no footing to set off from: she holds on until it lies still (an intent
        // already under way keeps the body; nothing new starts to fail at once on the unsteady seat).
        if (!hat.steady()) return;
        // A failed ordinary Release leaves the child held; the family landing owns the remaining route to dry support.
        var child = body.family().carry().childForShore();
        if (child != null) {
            out.add(Desire.of("carry_ashore", num("birth_ashore_want", 200), List.of(cell(hat), child.getUUID()),
                    () -> "held_child=" + child.getUUID(), () -> Ashore.come(body)));
            return;
        }
        var at = cell(hat);
        // Her mate waits beside her through her own-hat water labour, so a newborn that lands in the water is within a stroke of him.
        var labouring = GuardLabour.labouringMate(body);
        if (labouring != null) out.add(Desire.of("guard_labour", num("guard_want", 90), List.of(at, hat.blockPosition(), labouring.blockPosition()),
                () -> "mother=" + labouring.getUUID() + " hold=" + labouring.family().birthBlock(), () -> new GuardLabour(body, labouring)));
        var larder = HullLarder.of(hat);
        boolean food = larder != null && larder.has(life::acceptsFood);
        double eat = eat(body);
        if (food && eat > 0) out.add(Desire.of("eat_aboard", floor(eat + num("eat_bonus", 15)), List.of(at, larder.count()),
                () -> "nutrition=" + Math.round(life.nutrition()) + " stores=" + larder.count(), () -> Provision.eat(body)));
        // food to fish up: when hungry, or the hold runs low and has floor for it
        var catchable = (eat > 0 || larder != null && larder.count() < num("stock_portions", 2) && larder.room(hat))
                ? Waters.food(hat, body, 12) : null;
        if (catchable != null) out.add(Desire.of("forage", floor(Math.max(eat, num("forage_want", 35))), List.of(at, catchable.getUUID()),
                () -> "item=" + catchable.getItem().getItem() + " at=" + GameplayLog.at(catchable.position()), () -> Provision.forage(body, catchable)));
        // no stick aboard (never had one, or lost it: one state) and one afloat in sight she can paddle to: fetch it.
        // A capsize or a righting changes the hull itself, so what failed before it is not a fact of this hull any more.
        var floating = stick ? null : Waters.floatingStick(hat, (int) num("stick_sight", 16));
        if (floating != null) out.add(Desire.of("fetch_stick", floor(num("stick_want", 55)),
                List.of(at, floating.getUUID(), HullAfloat.capsizeCount(hat)),
                () -> "item at=" + GameplayLog.at(floating.position()), () -> Provision.stick(body, floating)));
        care(out, hat, young, at);
        BoatToilet.offer(out, body, hat);
        if (nursery) {
            GameplayLog.limited(body, "aquatic", "nursery.aboard", 1200,
                    "departure held reason=newborn_care children={}", nursing.stream().map(YukkuriEntity::getUUID).toList());
            return;
        }
        // a dependent young on dry ground anywhere in her search range (one that climbed out by itself and strayed on is a separated young
        // too; the family search finds it once she is ashore) that is not off on its own first voyage (one on its father's boat, in the water or in a mouth is not ashore)
        long ashore = SetOut.strays(body).stream().filter(y -> AquaticLife.ashore(y) && !leftBehind(y) && !(Drive.running(y, DOMAIN) instanceof FirstVoyage)).count();
        // no stick aboard pulls her ashore only when one lies there to be had; hunger only with nothing aboard or afloat she can eat
        boolean toHave = !stick && Waters.stick(body, 12, false) != null;
        boolean fed = food && !drive().failed("eat_aboard") || catchable != null && !drive().failed("forage");
        double pull = Math.max(Math.max(fed ? 0 : hunger, toHave ? num("stick_need", 50) : 0),
                Math.max(birthWant, Math.max(Math.max(weather, breed), Math.max(dusk ? num("night_pull", 60) : 0, ashore > 0 ? num("young_ashore_need", 60) : 0))));
        var world = List.of(at, stick, young.size(), ashore, birth);
        Supplier<String> why = () -> String.format(Locale.ROOT, "hunger=%.0f food_aboard=%b stick=%b stick_ashore=%b weather=%.0f breed=%.0f birth=%.0f young_ashore=%d dusk=%b rain=%b",
                hunger, food, stick, toHave, weather, breed, birthWant, ashore, dusk, rain);
        var ashoreDesire = Desire.of("come_ashore", floor(pull), world, why, () -> Ashore.come(body));
        // At the desire ceiling, labour's shore requirement wins ties with hunger; other bodily emergencies still own their goals.
        if (birth) out.addFirst(ashoreDesire); else out.add(ashoreDesire);
        out.add(Desire.of("cruise", dusk ? 0 : floor(num("cruise_want", 30)), world, () -> "energy=" + Math.round(life.energy()), () -> new Cruise(body)));
    }

    /** How much a body that can eat wants to now (0 when it cannot or is not hungry). */
    private static double eat(YukkuriEntity y) {
        return y.life().canEat() ? Math.max(0, num("eat_from", 70) - y.life().nutrition()) * num("eat_slope", 3) : 0;
    }

    /** Her decided help: a young of hers in the water whose incident she accepted (one decision each; a rescue under way
     *  keeps its desire). A young that cannot sail is always accepted for rescue; a protecting parent watches an
     *  independent young with tears. Rescue is offered before retaining a running watch. She decides only from steady footing:
     *  while her own hull rocks hard, goes over or is being righted, or she is in the water herself, she sees to herself first,
     *  and no answer is drawn that she could not act on (it would spend her one answer to that incident). */
    private void help(List<Desire> out) {
        var ledger = AskLedger.server();
        if (drive().runner() != null && drive().runner().intent() instanceof Rescue r) out.add(rescue(r.faller(), r.incident(), null));
        else {
            YukkuriEntity watching = null;
            String watched = null;
            boolean steady = steadyFooting();
            for (var y : AquaticLife.young(body)) {
                String id = y.isInWaterOrBubble() ? ledger.openOf(y.getUUID(), "water") : null;
                if (id == null) continue;
                if (!steady) {
                    GameplayLog.limited(body, "aquatic", "help.unsteady", 200, "rescue decision deferred faller={} incident={} reason=unsteady_footing",
                            y.getUUID(), id);
                    continue;
                }
                var ask = new Ask(y.getUUID(), body.getUUID(), h -> new Rescue(h, y, id), id);
                String refusal = Rescue.refusal(body, y);
                boolean[] fresh = {false};
                var decision = ledger.decide(ask, refusal == null ? 1 : 0,
                        () -> { fresh[0] = true; return body.getRandom().nextDouble(); }, body);
                if (fresh[0] && decision != null && !decision.accepted()) {
                    GameplayLog.event(body, "aquatic", "rescue refused faller={} incident={} reason={} protected={}",
                            y.getUUID(), id, refusal, body.life().personality().protects());
                }
                if (ledger.helping(id, body.getUUID())) { out.add(rescue(y, id, ask)); return; }
                if ("can_sail".equals(refusal) && watching == null && body.life().personality().protects()) { watching = y; watched = id; }
            }
            if (drive().runner() != null && drive().runner().intent() instanceof Rescue.Watch w) out.add(watch(w.faller(), w.incident()));
            else if (watching != null) out.add(watch(watching, watched));
        }
    }

    /** Footing she can act from: dry ground, or a seat on a hull lying upright and still (not rocking hard, going over or being
     *  righted); never while she is in the water. */
    private boolean steadyFooting() {
        if (body.isInWaterOrBubble()) return false;
        return body.getVehicle() instanceof PlacedHatEntity hat ? hat.steady() : AquaticLife.ashore(body);
    }

    /** One desire for the accepted incident, kept until its performance ends. */
    private Desire rescue(YukkuriEntity faller, String incident, Ask ask) {
        return new Desire("rescue", num("rescue_want", 95), 0, true, List.of(incident, AquaticLife.riding(body) != null, faller.blockPosition()),
                () -> "faller=" + faller.getUUID() + " incident=" + incident, () -> ask == null ? null : ask.intent(body));
    }

    private Desire watch(YukkuriEntity faller, String incident) {
        return new Desire("watch_water", num("rescue_want", 95), 0, true, List.of(incident, faller.blockPosition()),
                () -> "faller=" + faller.getUUID() + " incident=" + incident, () -> new Rescue.Watch(body, faller, incident));
    }

    /** Care for her young aboard: the hungriest that cannot eat by itself and has food aboard for it, her decision accepted (the
     *  care ledger's one draw, kept 1200 ticks); one gasping after the water or cold. */
    private void care(List<Desire> out, PlacedHatEntity hat, List<YukkuriEntity> young, Object at) {
        if (!ParentCarry.braidBusy(body)) for (var child : young) if (Care.needsShelter(child, hat)) {
            out.add(new Desire("shelter_young", PlayAboard.number("hide_want"), 0, true, List.of(at, child.getUUID()),
                    () -> "frightened_brim child=" + child.getUUID(), () -> new Care.Transfer(body, child, hat)));
            break;
        }
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

    /** The nearest of her own young still riding {@code hat} afloat, or null. */
    static YukkuriEntity youngAboard(YukkuriEntity parent, PlacedHatEntity hat) {
        if (!AquaticLife.afloat(hat)) return null;
        for (var y : AquaticLife.young(parent)) if (y.getVehicle() == hat) return y;
        return null;
    }

    private void young(List<Desire> out) {
        // A graduated young lives the same owner life on its own hat, not on its parent's boat.
        if (body.childhood().canSail()) { owner(out); return; }
        // back from its own hat: ashore with it, or the hat taken back
        var own = AquaticLife.hat(body);
        if (own != null && AquaticLife.aboard(body, own) && AquaticLife.afloat(own))
            out.add(Desire.of("come_ashore", floor(num("young_return", 60)), List.of(cell(own)), () -> "own_hat=afloat", () -> Ashore.come(body)));
        else if (own != null && AquaticLife.ashore(body) && Ashore.canReclaim(body, own))
            out.add(Desire.of("resolve_own_hat", num("reclaim", 85), List.of(cell(own), form(own), body.blockPosition()), () -> "hat=" + form(own),
                    () -> Ashore.reclaim(body)));
        var ride = AquaticLife.riding(body);
        var larder = HullLarder.of(ride);
        double eat = eat(body);
        if (eat > 0 && larder != null && larder.has(body.life()::acceptsFood))
            out.add(Desire.of("eat_aboard", floor(eat + num("eat_bonus", 15)), List.of(cell(ride), larder.count()),
                    () -> "nutrition=" + Math.round(body.life().nutrition()), () -> Provision.eat(body)));
        var parent = AquaticLife.parent(body);
        var hat = parent == null ? null : AquaticLife.hat(parent);
        if (!AquaticLife.afloat(hat)) return;
        boolean on = AquaticLife.aboard(body, hat), parentOn = AquaticLife.aboard(parent, hat);
        var running = IntentRunner.running(parent);
        Supplier<String> why = () -> "parent=" + parent.getUUID() + " parent_aboard=" + parentOn
                + " doing=" + (running == null ? "none" : running.intent().id());
        var world = List.of(cell(hat), body.blockPosition(), parentOn);
        if (on && parentOn && body.family().stage() == FamilyLife.Stage.BABY) play(out, hat, world);
        if (on) BoatToilet.offer(out, body, hat);
        var rest = TakeSeat.offer(body, hat);
        if (rest != null)
            out.add(Desire.of("take_seat", num("take_seat_want", 70), rest.world(), rest::why, () -> rest));
        voyage(out, parent, hat);
        if (!on && AquaticLife.ashore(body) && AquaticLife.jumper(body) && body.distanceTo(hat) < AquaticLife.FAMILY_RANGE
                && !body.childhood().needsNewbornCare() && AquaticLife.nursing(parent).isEmpty()
                && !(running != null && running.intent() instanceof Ashore)
                && parentOn) {
            var full = hat.boardingRefusal(body);
            // Only a boarding this young has a means for now: the hop admitted where it stands, or a dry stand near the hull to hop
            // from. Without one it waits on the bank and her own fetch/ferry applies, instead of failing the same boarding over and over.
            if (full == null && Ferry.canBoard(body, hat)) out.add(Desire.of("board_parent", num("follow", 65), world, why, () -> Follow.board(body, hat)));
            else GameplayLog.limited(body, "aquatic", "board refused hat=" + hat.getUUID(), 1200,
                    "board refused hat={} reason={} size={}", hat.getUUID(), full == null ? "no_boarding_stand" : "hull_full " + full,
                    body.growthScale() * body.getScale());
        }
        // A young standing on the hull leaves it by itself; one her braid carries on her head goes with her and is set down there.
        // A hull with no dry stand in this young's hop reach has no way off for it, so nothing is offered: the young waits where it
        // stands (the hop's own search reports why) instead of failing the same jump over and over.
        if (body.getVehicle() == hat && AquaticLife.jumper(body)
                && (AquaticLife.ashore(parent) && !(running != null && running.intent() instanceof SetOut)
                || running != null && running.intent() instanceof Ashore)
                && Waters.moored(hat) && Waters.brimAshore(hat, body) != null)
            out.add(Desire.of("follow_ashore", num("follow", 65), world, why, () -> Follow.ashore(body, hat)));
    }

    private void play(List<Desire> out, PlacedHatEntity hat, Object world) {
        String fear = fear(hat);
        if (!fear.equals("none")) {
            out.add(new Desire("hide_aboard", PlayAboard.number("hide_want"), 0, true, List.of(world, fear),
                    () -> "fear=" + fear, () -> PlayAboard.shrink(body, hat, () -> fear(hat))));
            return;
        }
        if (PlayAboard.canPlay(body, hat)) out.add(Desire.of("play_aboard", PlayAboard.number("play_want"), world,
                () -> "spot=" + PlayAboard.place(body, hat), () -> PlayAboard.create(body, hat, () -> fear(hat))));
    }

    private String fear(PlacedHatEntity hat) {
        long now = body.level().getGameTime();
        if (fearTick != now) { fear = PlayAboard.fear(body, hat); fearTick = now; }
        return fear;
    }

    /**
     * A young able to handle its own hat that has not yet (solid food, hopping, learning), wearing it with a stick for it (tucked in
     * it, or loose near), not tired, its parent aboard her own hat afloat on a calm day, and it on the bank or standing on her hat by one
     * or with calm water beside her hull to set its hat down on from her brim (a young her braid carries on her head has no way off by
     * itself, so it does not try): its readiness grows with size relative to the
     * shared young-seat limit, shifted earlier by boldness. Kept while it runs.
     */
    private void voyage(List<Desire> out, YukkuriEntity parent, PlacedHatEntity hat) {
        var life = body.life();
        var child = body.childhood();
        boolean running = drive().runner() != null && drive().runner().intent() instanceof FirstVoyage;
        if (!running && (child.canSail() || body.level().getGameTime() < voyageAfter || !child.canEatSolid() || !child.canLearn() || !AquaticLife.jumper(body)
                || !HatProp.usable(body) || !life.custody().worn()
                || life.energy() < num("voyage_energy", 30) || AquaticLife.dusk(body.level()) || AquaticLife.rain(body) || !AquaticLife.aboard(parent, hat)
                || !(AquaticLife.ashore(body) || body.getVehicle() == hat
                        && (!Waters.edges(body, body.position(), 2, false).isEmpty() || Waters.brimLaunch(body, hat) != null))
                || !AquaticLife.stick(body) && Waters.stick(body, 10, true) == null)) return;
        double size = body.growthScale() * body.getScale();
        double ratio = size / HullBounds.youngLimit(hat.hullScale() / HullBounds.SCALE);
        double want = VoyageReadiness.desire(ratio, life.personality().boldness(), DriveWeights.of(DOMAIN, "first_voyage_from"),
                DriveWeights.of(DOMAIN, "first_voyage_to"), DriveWeights.of(DOMAIN, "first_voyage_bold_shift"),
                DriveWeights.of(DOMAIN, "first_voyage_want"));
        if (want <= 0) return;
        out.add(Desire.of("first_voyage", want, List.of(body.blockPosition(), cell(hat), AquaticLife.stick(body)),
                () -> "parent=" + parent.getUUID() + " boldness=" + life.personality().boldness() + " size=" + size + " ratio=" + ratio,
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
