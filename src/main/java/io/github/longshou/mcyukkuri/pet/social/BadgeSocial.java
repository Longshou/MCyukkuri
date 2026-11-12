package io.github.longshou.mcyukkuri.pet.social;

import io.github.longshou.mcyukkuri.social.Bonds;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.pet.BadgeGrade;
import io.github.longshou.mcyukkuri.event.WildEventData;
import io.github.longshou.mcyukkuri.event.WildEvents;
import io.github.longshou.mcyukkuri.life.YukkuriConfig;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.pet.social.BadgeArcPolicy.Arc;
import io.github.longshou.mcyukkuri.settlement.Rumours;
import io.github.longshou.mcyukkuri.settlement.SettlementData;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/** Sightings of badges (and of lost ones), turned into silent ledger records, rare one-liners or multi-beat arcs. Server only, driven by PetLife's 2-second
 *  tick; nothing here depends on rendering. Redesign of the old poll-and-perform scan: docs/FEATURES.md */
public final class BadgeSocial {
    public static BadgeGrade visibleRank(YukkuriEntity subject) { return subject.pet().visibleEffective(); }
    /** Affinity moved by badge reactions stays inside these guard rails: far from the bully (-20) and nuzzle (+10) thresholds. */
    public static final int FLOOR = -10, CEILING = 8;
    private static final int SCAN_RADIUS = 8, DWELL_TICKS = 40, FORGET_VIEW_TICKS = 100, ROLL_EVERY = 200, AUDIENCE_RADIUS = 32;
    private static final double NEAR_SIGHT_SQR = 2.5 * 2.5;
    private static final class View {
        final long since;
        final int grade;
        long last, nextRoll;
        boolean reported;
        View(long t, int grade) { since = t; last = t; this.grade = grade; }
    }
    private static final Map<UUID, Map<UUID, View>> VIEWS = new HashMap<>();
    private static long clock;
    private static BadgeBudget budget;
    private BadgeSocial() {}

    // configuration (read defensively: checks that run before any config is loaded fall back to the defaults)
    public static boolean enabled() { try { return YukkuriConfig.BADGE_SOCIAL.get(); } catch (RuntimeException e) { return true; } }
    static boolean snatchEnabled() { try { return YukkuriConfig.BADGE_SNATCH.get(); } catch (RuntimeException e) { return false; } }
    static boolean homeClaimEnabled() { try { return YukkuriConfig.BADGE_HOME_CLAIM.get(); } catch (RuntimeException e) { return true; } }
    public static BadgeBudget.Settings settings() {
        try {
            return new BadgeBudget.Settings(YukkuriConfig.BADGE_REFILL_TICKS.get(), YukkuriConfig.BADGE_CAPACITY.get(), YukkuriConfig.BADGE_OBSERVER_COOLDOWN.get(),
                    YukkuriConfig.BADGE_PAIR_COOLDOWN.get(), YukkuriConfig.BADGE_TARGET_COOLDOWN.get(), YukkuriConfig.BADGE_TARGET_PER_DAY.get(),
                    YukkuriConfig.BADGE_WORLD_REFILL_TICKS.get(), YukkuriConfig.BADGE_WORLD_CAPACITY.get());
        } catch (RuntimeException e) { return BadgeBudget.Settings.defaults(); }
    }
    public static BadgeBudget budget() {
        if (budget == null) budget = new BadgeBudget(() -> clock, settings());
        return budget;
    }
    public static void reset() {
        VIEWS.clear(); budget = null; BadgeArcs.reset(); BadgePraise.reset();
    }
    public static void forget(Set<UUID> ids) {
        VIEWS.keySet().removeAll(ids);
        VIEWS.values().forEach(views -> views.keySet().removeAll(ids));
        if (budget != null) budget.forget(ids);
        BadgeArcs.forget(ids); BadgePraise.forget(ids);
    }

    /** Move a directed affinity by a badge reaction without ever passing the guard rails (a change that points back toward 0 is always allowed). */
    public static void nudge(YukkuriWorldData world, UUID from, UUID to, int delta) {
        int now = world.affinity(from, to);
        if (delta < 0 && now + delta < FLOOR) delta = Math.min(0, FLOOR - now);
        if (delta > 0 && now + delta > CEILING) delta = Math.max(0, CEILING - now);
        if (delta != 0) world.relate(from, to, delta);
    }

    public static Player ownerWithin(ServerLevel level, YukkuriEntity pet, double radius) {
        var id = pet.pet().owner();
        if (id == null) return null;
        var p = level.getPlayerByUUID(id);
        return p != null && p.isAlive() && !p.isSpectator() && p.distanceToSqr(pet) <= radius * radius ? p : null;
    }

    private static boolean inFront(YukkuriEntity o, YukkuriEntity y) {
        Vec3 to = y.position().subtract(o.position()).multiply(1, 0, 1);
        if (o.distanceToSqr(y) <= NEAR_SIGHT_SQR) return true;
        Vec3 look = o.getLookAngle().multiply(1, 0, 1);
        if (look.lengthSqr() < 1.0e-4) return true;
        return look.normalize().dot(to.normalize()) > .5; // about 120 degrees in front
    }

    /** Called every 2 seconds for each individual (PetLife.tickPerformance): only newly visible badge-holders produce a sighting. */
    public static void scan(YukkuriEntity o) {
        if (!enabled() || !(o.level() instanceof ServerLevel level) || o.profile().dangerToYukkuri() || o.profile().houseBody()) return;
        long now = level.getGameTime(); clock = now;
        if (!o.pet().calm() || !WildEventData.get(level).opportunitySlot(o.getUUID())) return;
        var views = VIEWS.computeIfAbsent(o.getUUID(), id -> new HashMap<>());
        views.values().removeIf(v -> now - v.last > FORGET_VIEW_TICKS);
        if (VIEWS.size() > 256) VIEWS.entrySet().removeIf(e -> e.getValue().isEmpty() && level.getEntity(e.getKey()) == null);
        int mine = visibleRank(o).id();
        var ledger = o.pet().badgeLedger();
        List<YukkuriEntity> seen = new ArrayList<>();
        for (var y : level.getEntitiesOfClass(YukkuriEntity.class, o.getBoundingBox().inflate(SCAN_RADIUS, 3, SCAN_RADIUS), peer -> peer != o && peer.isAlive() && !peer.isRemoved()
                && !peer.isPassenger() && !peer.profile().houseBody() && !peer.life().isSleeping())) {
            if (!o.hasLineOfSight(y) || !inFront(o, y)) continue;
            int theirs = visibleRank(y).id();
            var known = ledger.get(y.getUUID(), now);
            // Relevant: a badge to notice, a badge of my own to show off, or a badge I remember that is gone.
            if (theirs > 0 || o.pet().isPet() && mine > 0 || known != null && known.lastGrade() > theirs) seen.add(y);
        }
        var viewer = level.getNearestPlayer(o, AUDIENCE_RADIUS);
        for (var y : seen) {
            var v = views.get(y.getUUID());
            int grade = visibleRank(y).id();
            if (v == null || v.grade != grade) {
                v = new View(now, grade);
                views.put(y.getUUID(), v);
            }
            v.last = now;
            if (!v.reported && (o.distanceToSqr(y) <= NEAR_SIGHT_SQR || now - v.since >= DWELL_TICKS)) {
                v.reported = true;
                v.nextRoll = now + ROLL_EVERY;
                sighted(level, o, y, viewer, seen, now, true);
            }
            else if (v.reported && now >= v.nextRoll) { v.nextRoll = now + ROLL_EVERY + o.getRandom().nextInt(100); sighted(level, o, y, viewer, seen, now, false); }
        }
    }

    /** A badge-holder has just taken a bite: hungry wild onlookers decide there and then whether to beg for it. The periodic sighting rolls (every few seconds)
     *  can almost never coincide with the ~1.8 s window in which a bite is held in the mouth, so this is the natural way for B4 to happen. */
    public static void mealSeen(YukkuriEntity subject) {
        if (!enabled() || !(subject.level() instanceof ServerLevel level) || subject.profile().dangerToYukkuri() || subject.profile().houseBody()
                || visibleRank(subject).id() <= 0 || subject.life().mouth().isEmpty()) return;
        long now = level.getGameTime(); clock = now;
        var viewer = level.getNearestPlayer(subject, AUDIENCE_RADIUS);
        var data = WildEventData.get(level);
        for (var o : level.getEntitiesOfClass(YukkuriEntity.class, subject.getBoundingBox().inflate(SCAN_RADIUS, 3, SCAN_RADIUS), peer -> peer != subject && peer.isAlive()
                && !peer.isRemoved() && !peer.isPassenger() && !peer.profile().houseBody() && !peer.profile().dangerToYukkuri() && !peer.life().isSleeping()
                && peer.status().identity().free() && peer.life().nutrition() >= 30 && peer.life().nutrition() < 50)) {
            if (!o.pet().calm() || !data.opportunitySlot(o.getUUID()) || !o.hasLineOfSight(subject) || !inFront(o, subject)) continue;
            sighted(level, o, subject, viewer, List.of(subject), now, false, true);
            if (subject.life().mealHeld()) return; // one beggar at a time
        }
    }

    private static void sighted(ServerLevel level, YukkuriEntity o, YukkuriEntity s, Player viewer, List<YukkuriEntity> seen, long now, boolean edge) {
        sighted(level, o, s, viewer, seen, now, edge, false);
    }

    private static void sighted(ServerLevel level, YukkuriEntity o, YukkuriEntity s, Player viewer, List<YukkuriEntity> seen, long now, boolean edge, boolean meal) {
        var ledger = o.pet().badgeLedger();
        int grade = visibleRank(s).id();
        BadgeLedger.Sighting sight;
        if (edge) sight = ledger.note(s.getUUID(), grade, now);
        else { var e = ledger.ensure(s.getUUID(), grade, now); sight = new BadgeLedger.Sighting(e, false, false, false, e.lastGrade()); }
        var entry = sight.entry();
        var observer = observer(o, seen);
        var subject = subject(level, o, s, viewer);
        var relation = new BadgeArcPolicy.Relation(YukkuriWorldData.get(level).affinity(o.getUUID(), s.getUUID()), Bonds.careKin(o, s),
                sight.first(), sight.changed(), sight.lapsed(), entry.sinceArc(now) < 24000L, entry.impression(), entry.flags(), entry.seenCount());
        var group = o.settlement().group();
        var context = new BadgeArcPolicy.Context(viewer != null, Rumours.strength(group, Rumours.WARY_HUMAN, now) > 0, Rumours.strength(group, Rumours.BADGE_FED, now) > 0,
                snatchEnabled(), homeClaimEnabled());
        BadgeArcPolicy.Choice choice;
        if (!meal) {
            double roll = o.getRandom().nextDouble();
            choice = BadgeArcPolicy.choose(observer, subject, relation, context, roll);
            GameplayLog.limited(o, "badge", "sight." + s.getUUID(), 100,
                    "badge sight subject={} grade={} first={} roll={} choice={}", s.getUUID(), grade, sight.first(), roll,
                    choice.silent() ? "silent" : choice.arc().id);
        }
        else {
            double chance = BadgeArcPolicy.begBiteChance(observer, subject, relation);
            if (chance == 0 || o.getRandom().nextDouble() >= chance) return;
            choice = new BadgeArcPolicy.Choice(Arc.BEG_BITE, "");
        }
        if (choice.silent()) { glance(o, s, edge && (sight.first() || sight.changed())); return; }
        var arc = choice.arc();
        String variant = arc == Arc.BOAST && o.pet().recentBadgeSuccess() && o.life().personality().boldness() >= 55
                && o.life().personality().friendliness() <= 65 ? "boast" : choice.variant();
        UUID viewerId = viewer == null ? null : viewer.getUUID();
        // Begging acts on a real bite. Its existing pair memory and the held meal govern repeats.
        if (arc != Arc.BEG_BITE) {
            var verdict = budget().check(viewerId, arc.cost, o.getUUID(), s.getUUID());
            if (verdict != BadgeBudget.Verdict.OK) {
                GameplayLog.limited(o, "badge", "budget." + arc.id, 100, "badge arc deferred arc={} reason={}", arc.id, verdict);
                glance(o, s, edge);
                return;
            }
        }
        YukkuriEntity extra = extraFor(arc, o, s, seen);
        var scene = BadgeArcs.start(level, arc, variant, o, s, viewer, extra);
        if (scene == null) { glance(o, s, edge); return; }
        if (arc != Arc.BEG_BITE) budget().commit(viewerId, arc.cost, o.getUUID(), s.getUUID());
        ledger.arc(s.getUUID(), arc.id, now);
    }

    private static void glance(YukkuriEntity o, YukkuriEntity s, boolean worthIt) {
        if (worthIt && budget().microAllowed(o.getUUID())) o.pet().glanceAt(s, 30);
    }

    private static YukkuriEntity extraFor(Arc arc, YukkuriEntity o, YukkuriEntity s, List<YukkuriEntity> seen) {
        if (arc == Arc.GLANCE && !o.family().adult()) return seen.stream().filter(p -> p != s && WildEvents.childOf(p, o) && p.status().identity().free()).findFirst().orElse(null);
        if (arc == Arc.ELDER_WARN) return seen.stream().filter(p -> p != s && WildEvents.childOf(o, p)).findFirst().orElse(null);
        return null;
    }

    static BadgeArcPolicy.Observer observer(YukkuriEntity o, List<YukkuriEntity> around) {
        var p = o.life().personality();
        var group = o.settlement().group();
        boolean childNear = around.stream().anyMatch(y -> WildEvents.childOf(o, y));
        boolean elder = o.family().adult() && (group != null && o.getUUID().equals(group.leader) || childNear);
        return new BadgeArcPolicy.Observer(o.pet().isPet(), !o.family().adult(), elder, childNear, p.intelligence(), p.friendliness(), p.boldness(),
                p.attachment(), p.selfControl(), Math.round(o.life().nutrition()), o.pet().stress(), o.pet().isPet() ? visibleRank(o).id() : 0,
                Math.round(o.getBbWidth() * 100));
    }

    static BadgeArcPolicy.Subject subject(ServerLevel level, YukkuriEntity o, YukkuriEntity s, Player viewer) {
        boolean pet = s.pet().isPet();
        boolean eating = s.life().mealTicks() > 0 && s.life().mealTicks() < 36 && !s.life().mouth().isEmpty();
        boolean ownerNear = ownerWithin(level, s, 12) != null;
        boolean ownerOnScene = ownerWithin(level, s, 24) != null;
        boolean trusted = false;
        if (viewer != null && WildEvents.playerNear(o, viewer) && viewer.distanceToSqr(o) <= 100) trusted = WildEvents.canAdopt(o, viewer);
        var home = s.life().home();
        boolean homeNear = pet && home != null && !s.life().naturalHome() && home.dimension().equals(level.dimension()) && home.pos().distSqr(o.blockPosition()) <= 36;
        return new BadgeArcPolicy.Subject(visibleRank(s).id(), pet, eating, ownerNear, trusted, homeNear, pet && !ownerNear && ownerOnScene,
                s.life().hasAccessory(), Math.round(s.getBbWidth() * 100));
    }

    /** A deliberate greeting between two individuals (YukkuriSocial): always a silent record; only a budgeted one-liner is performed.
     *  True when a badge reaction replaced the ordinary greeting. */
    public static boolean greeting(YukkuriEntity o, YukkuriEntity peer) {
        if (!(o.level() instanceof ServerLevel level)) return false;
        long now = level.getGameTime(); clock = now;
        var ledger = o.pet().badgeLedger();
        ledger.note(peer.getUUID(), visibleRank(peer).id(), now);
        var viewer = level.getNearestPlayer(o, AUDIENCE_RADIUS);
        UUID viewerId = viewer == null ? null : viewer.getUUID();
        if (budget().check(viewerId, 1, o.getUUID(), peer.getUUID()) != BadgeBudget.Verdict.OK) return false;
        int flag = o.pet().reactNow(peer);
        budget().commit(viewerId, 1, o.getUUID(), peer.getUUID());
        ledger.arc(peer.getUUID(), "greeting", now);
        if (flag != 0) { ledger.flag(peer.getUUID(), flag, now); ledger.impress(peer.getUUID(), flag == BadgeLedger.ENVIED ? -1 : 1, now); }
        return true;
    }

    /** Command / test entry: why an arc could not start (null = it was started). Bypasses the roll and the budget, never the safety checks. */
    public static String force(ServerLevel level, Arc arc, YukkuriEntity o, YukkuriEntity s, Player viewer) {
        clock = level.getGameTime();
        String why = BadgeArcs.check(level, arc, o, s, viewer, null);
        if (why != null) return why;
        var scene = BadgeArcs.start(level, arc, arc == Arc.BOAST ? "boast" : arc == Arc.PITY ? "pity" : arc == Arc.ELDER_WARN ? "warn" : "", o, s, viewer, null);
        if (scene == null) return "start_refused";
        o.pet().badgeLedger().ensure(s.getUUID(), visibleRank(s).id(), clock);
        o.pet().badgeLedger().arc(s.getUUID(), arc.id, clock);
        return null;
    }

    /** Budget lines for the admin command. */
    public static List<String> status(ServerLevel level, Player viewer) {
        clock = level.getGameTime();
        var b = budget(); var st = b.settings();
        List<String> lines = new ArrayList<>();
        lines.add(String.format("tokens near you %.2f/%d (+1 per %d ticks), far from players %.2f/%d (+1 per %d)", b.tokens(viewer == null ? null : viewer.getUUID()), st.capacity(),
                st.refillTicks(), b.tokens(null), st.worldCapacity(), st.worldRefillTicks()));
        lines.add("observer " + st.observerCooldown() + ", pair " + st.pairCooldown() + ", target " + st.targetCooldown() + " (max " + st.targetPerDay() + "/day) ticks");
        return lines;
    }

    /** The witness's community (if any) hears about a settled arc. */
    static void spread(ServerLevel level, YukkuriEntity witness, int kind, UUID subject, int strength) {
        var group = witness.settlement().group();
        if (group == null) return;
        Rumours.add(SettlementData.get(level), group, kind, subject, strength, level.getGameTime());
    }
}
