package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyLife.Leash;
import io.github.longshou.mcyukkuri.motor.Action;
import io.github.longshou.mcyukkuri.motor.MotorOwner;
import io.github.longshou.mcyukkuri.motor.Outcome;
import io.github.longshou.mcyukkuri.motor.action.Gesture;
import io.github.longshou.mcyukkuri.perception.Senses;
import io.github.longshou.mcyukkuri.social.Temperament;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.Comparator;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Family closeness as one drive shared by every family body. A dependent young that is not kept to a home wanders around its
 * family anchor (the caring parent while it is out and awake, else the nest or the caregiver at home) instead of around itself,
 * in a comfort band widened by age and shifted by curiosity, timidity and attachment: at the edge it looks back, beyond the band
 * it hops back and now and then calls. A caring parent glances at its young and calls one that drifts to the band edge, so a warm
 * family stays gathered while a cold or selfish parent's young loosen. A young left at home looks after a caregiver that leaves
 * alone; its own greeting on the caregiver's return is {@link io.github.longshou.mcyukkuri.social.DailyLife#welcomeHome}.
 *
 * <p>The band stops short of the lost gate ({@link FamilySeparation#near}): a child held here is never a search target. The
 * young's own wander is the existing {@link io.github.longshou.mcyukkuri.entity.RoamingGoal} aimed at this anchor; a parent's call
 * is the existing {@link ChildCall} beckon and the child's existing answer. Transient: a reload starts with no anchor rememberd.
 */
final class FamilyTether {
    /** A caregiver within this reach anchors the young; one beyond the keep range is away and the home holds the young (blocks). */
    private static final double TAKE = 12, KEEP = 24;
    /** The young looks back at the edge, hops back beyond the band and says a line now and then, each on its own pace (ticks). */
    private static final int GLANCE = 60, HOP = 200, LINE = 1200;
    /** A young at home that sees a caregiver leave alone looks after it once per outing (ticks; blocks). */
    private static final int DEPARTURE = 1200;
    private static final double DEPARTURE_RANGE = 16;
    /** A caring parent looks over its young about every half minute; a cold or selfish one never does (ticks). */
    private static final int WATCH = 400, WATCH_ROUND = 120;
    /** How far into the band the young notices its edge. */
    private static final double EDGE = .65;

    /** The comfort interval of one young, in blocks from its anchor. */
    private record Band(int near, int far) {}

    private final FamilyLife o;
    /** The caregiver the young currently orbits (transient); null: the home holds it. */
    private UUID leashParent;
    /** The body resolved as the current anchor during {@link #anchor()}, null for the nest. */
    private YukkuriEntity anchorActor;
    private long nextGlance, nextHop, nextLine, nextDeparture, nextWatch;
    private boolean hopping;
    private final MotorOwner body = new MotorOwner() {
        @Override public String ownerId() { return "family_tether"; }
        @Override public boolean active() { return hopping; }
        @Override public void finished(Action action, Outcome outcome) { hopping = false; }
    };

    FamilyTether(FamilyLife owner) { o = owner; }

    private YukkuriEntity entity() { return o.entity(); }

    /** Where this young's idle wandering is held and how wide its comfort band is, or null when it may roam as it likes. */
    Leash leash() {
        if (!dependent()) return null;
        var anchor = anchor();
        if (anchor == null) return null;
        var band = band(entity());
        return new Leash(anchor, band.near(), band.far());
    }

    /** A dependent young that is not kept to a home: the only young whose wander the family anchor holds. */
    private boolean dependent() {
        var e = entity();
        var family = e.family();
        return !family.adult() && family.stage() != FamilyLife.Stage.BABY && e.childhood().shouldStayWithParents()
                && !family.keptHome() && !e.isPassenger() && !e.isInWaterOrBubble()
                && !family.familyTravelling() && !family.emergencyForaging() && !e.settlement().underway()
                && !(e.roaming() != null && e.roaming().exploring());
    }

    /** Wider with age; curiosity and boldness widen it, timidity and attachment narrow it. Never past the lost gate. */
    private static Band band(YukkuriEntity young) {
        var family = young.family();
        int base = family.stage() == FamilyLife.Stage.BABY ? 3 : family.subadult() ? 8 : 5;
        var traits = young.life().personality().temperament();
        double shift = 1 + .25 * traits.curious() - .20 * traits.timid() - .15 * traits.attached();
        int far = Math.clamp((int) Math.round(base * shift), 3, 8);
        return new Band(Math.max(2, (int) Math.round(far * .45)), far);
    }

    private static double edge(Band band) { return band.near() + EDGE * (band.far() - band.near()); }

    /** The family anchor: the caregiver that is out and awake, else the nest, else the caregiver at home. */
    private BlockPos anchor() {
        anchorActor = presentCaregiver();
        if (anchorActor != null) {
            leashParent = anchorActor.getUUID();
            return anchorActor.blockPosition();
        }
        leashParent = null;
        var nest = entity().family().nestStand();
        if (nest != null) return nest;
        anchorActor = homeCaregiver();
        return anchorActor == null ? null : anchorActor.blockPosition();
    }

    /** The caregiver beside the young now. It keeps the same caregiver while that one is near, so a walk at the range's rim does not toss the young between two anchors. */
    private YukkuriEntity presentCaregiver() {
        var e = entity();
        if (leashParent != null) {
            if (e.family().expectsCare(leashParent) && o.level().getEntity(leashParent) instanceof YukkuriEntity kept
                    && e.life().canRecognize(kept) && present(kept) && e.distanceToSqr(kept) <= KEEP * KEEP) return kept;
            leashParent = null;
        }
        YukkuriEntity best = null;
        double nearest = TAKE * TAKE;
        for (var id : e.family().caregiverIds()) {
            if (id == null || !(o.level().getEntity(id) instanceof YukkuriEntity parent)) continue;
            if (!e.life().canRecognize(parent) || !present(parent)) continue;
            double at = e.distanceToSqr(parent);
            if (at < nearest) { best = parent; nearest = at; }
        }
        return best;
    }

    /** Out and awake beside the young: not asleep, carried, swimming, or away alone on an errand. */
    private static boolean present(YukkuriEntity parent) {
        return !parent.life().isSleeping() && !parent.isPassenger() && !parent.isInWaterOrBubble() && !awayAlone(parent);
    }

    private static boolean awayAlone(YukkuriEntity parent) {
        return parent.forageTrip() != null && parent.forageTrip().outing()
                || parent.habitat().movingHome() || parent.outing().onOuting();
    }

    /** The last anchor for a family without a nest: a caregiver at home, even asleep. */
    private YukkuriEntity homeCaregiver() {
        for (var id : entity().family().caregiverIds())
            if (id != null && o.level().getEntity(id) instanceof YukkuriEntity parent && parent.isAlive()
                    && parent.family().atHome()) return parent;
        return null;
    }

    /** Whether this parent is the young's current anchor (read by the parent-side watch). */
    boolean anchoredOn(YukkuriEntity parent) { return leash() != null && anchorActor == parent; }

    void tick() {
        var e = entity();
        if (e.family().adult()) return;
        if (e.tickCount % 20 != 0 || e.isPassenger() || e.life().isSleeping() || e.isPanicking()) return;
        var leash = leash();
        if (leash != null) hold(leash);
        else departure();
    }

    /** The young's own side of the hold: sample it, look back at the edge, hop back beyond it and call now and then. */
    private void hold(Leash leash) {
        var e = entity();
        long now = o.level().getGameTime();
        var center = Vec3.atBottomCenterOf(leash.anchor());
        double away = Math.sqrt(e.distanceToSqr(center));
        var band = band(e);
        GameplayLog.limited(e, "family", "tether.young", 200,
                "tether young anchor={} parent={} near={} far={} distance={}",
                leash.anchor(), anchorActor == null ? "nest" : anchorActor.getUUID(), leash.near(), leash.far(), round(away));
        if (away < edge(band)) return;
        if (now >= nextGlance) {
            nextGlance = now + GLANCE;
            e.getLookControl().setLookAt(center);
            GameplayLog.limited(e, "family", "tether.edge", 200, "tether edge looks back at={} parent={} distance={} near={} far={}",
                    leash.anchor(), anchorActor == null ? "nest" : anchorActor.getUUID(), round(away), leash.near(), leash.far());
        }
        if (away <= leash.far()) return;
        if (now >= nextHop && e.onGround() && e.motor().current() == null) {
            nextHop = now + HOP + e.getRandom().nextInt(HOP);
            hopping = true;
            if (!e.motor().run(body, new Gesture(Gesture.Kind.HOP_SMALL))) hopping = false;
            else GameplayLog.limited(e, "family", "tether.hop", 200, "tether young hops back distance={} far={}", round(away), leash.far());
        }
        if (now >= nextLine && e.quiet()) {
            nextLine = now + LINE + e.getRandom().nextInt(LINE);
            if (e.speak("caregiver")) GameplayLog.limited(e, "family", "tether.line", 600, "tether young calls at the edge distance={}", round(away));
        }
    }

    /** A young at home watches a caregiver go out alone; its return greeting is the ordinary welcome home. */
    private void departure() {
        var e = entity();
        long now = o.level().getGameTime();
        if (now < nextDeparture || !e.family().atHome()) return;
        for (var id : e.family().caregiverIds()) {
            if (id == null || !(o.level().getEntity(id) instanceof YukkuriEntity parent) || !parent.isAlive()) continue;
            if (!awayAlone(parent) || e.distanceToSqr(parent) > DEPARTURE_RANGE * DEPARTURE_RANGE || !Senses.sees(e, parent)) continue;
            nextDeparture = now + DEPARTURE;
            e.getLookControl().setLookAt(parent, 20, 20);
            boolean said = e.quiet() && e.speak("wait_family");
            GameplayLog.limited(e, "family", "tether.departure", 600,
                    "tether young watches a caregiver leave parent={} distance={} spoken={}",
                    id, round(Math.sqrt(e.distanceToSqr(parent))), said);
            return;
        }
    }

    /** The child this parent calls before it is lost, or null: a caring parent's glance at its young beyond its band edge. */
    YukkuriEntity watchTarget() {
        var e = entity();
        if (!e.family().adult() || e.isPassenger() || e.life().isSleeping() || e.isPanicking()
                || e.life().hasUrgentNeed() || o.carryingOrLifting()) return null;
        long now = o.level().getGameTime();
        if (now < nextWatch) return null;
        nextWatch = now + WATCH + e.getRandom().nextInt(WATCH);
        // An unloving parent does not look: cold (low caring) or harsh (selfish) leaves its young to its own wander.
        var traits = e.life().personality().temperament();
        if (!traits.is(Temperament.Gate.KINDLY) || traits.is(Temperament.Gate.HARSH)) return null;
        var child = driftingChild();
        if (child == null) return null;
        double watchful = .6 * traits.caring() + .4 * traits.patient();
        if (e.getRandom().nextDouble() >= watchful) return null;
        var band = band(child);
        GameplayLog.event(e, "family", "tether watch child={} distance={} edge={} far={} watchfulness={}",
                child.getUUID(), round(Math.sqrt(e.distanceToSqr(child))), round(edge(band)), band.far(),
                String.format(Locale.ROOT, "%.2f", watchful));
        return child;
    }

    /** The nearest own young that orbits this parent and stands beyond its band edge in plain sight. */
    private YukkuriEntity driftingChild() {
        var e = entity();
        var family = e.family();
        return o.level().getEntitiesOfClass(YukkuriEntity.class, e.getBoundingBox().inflate(KEEP),
                        child -> child != e && family.caresFor(child) && !child.family().adult()
                                && child.family().stage() != FamilyLife.Stage.BABY
                                && child.childhood().shouldStayWithParents() && !child.life().isSleeping()
                                && !child.isPanicking() && e.hasLineOfSight(child)
                                && child.family().tether().anchoredOn(e)
                                && e.distanceToSqr(child) > square(edge(band(child))))
                .stream().min(Comparator.comparingDouble(e::distanceToSqr)).orElse(null);
    }

    /** The watch still has its child: alive, cared for and still orbiting this parent. */
    boolean watchContinues(YukkuriEntity child) {
        var e = entity();
        return child.isAlive() && child.level() == e.level() && e.family().caresFor(child)
                && !child.isPanicking() && child.family().tether().anchoredOn(e);
    }

    static int watchRound() { return WATCH_ROUND; }

    private static double square(double value) { return value * value; }

    private static double round(double value) { return Math.round(value * 10) / 10.0; }
}
