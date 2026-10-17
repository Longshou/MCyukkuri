package io.github.longshou.mcyukkuri.pet.social;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.event.WildEvents;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.life.YukkuriConfig;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.pet.social.BadgeArcPolicy.Arc;
import io.github.longshou.mcyukkuri.settlement.Rumours;
import io.github.longshou.mcyukkuri.settlement.SettlementData;
import io.github.longshou.mcyukkuri.social.YukkuriPersonality.Trait;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/** Sightings of badges (and of lost ones), turned into silent ledger records, rare one-liners or multi-beat arcs. Server only, driven by PetLife's 2-second
 *  tick; nothing here depends on rendering. Redesign of the old poll-and-perform scan: docs/PET_SHOP.md section 7. */
public final class BadgeSocial {
    /** Affinity moved by badge reactions stays inside these guard rails: far from the bully (-20) and nuzzle (+10) thresholds. */
    public static final int FLOOR = -10, CEILING = 8;
    private static final int SCAN_RADIUS = 8, DWELL_TICKS = 40, FORGET_VIEW_TICKS = 100, ROLL_EVERY = 200, AUDIENCE_RADIUS = 32;
    private static final class View { final long since; long last, nextRoll; boolean reported; View(long t) { since = t; last = t; } }
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
    /** Checks swap in a budget with their own clock and limits; reset() restores the configured one. */
    public static void installBudget(BadgeBudget replacement) { budget = replacement; }
    public static void tickClock(long now) { clock = now; }
    public static void reset() {
        VIEWS.clear(); budget = null; BadgeArcs.reset(); BadgePraise.reset();
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
        if (to.lengthSqr() < 1.0) return true; // very close: always in view
        Vec3 look = o.getLookAngle().multiply(1, 0, 1);
        if (look.lengthSqr() < 1.0e-4) return true;
        return look.normalize().dot(to.normalize()) > .5; // about 120 degrees in front
    }

    /** Called every 2 seconds for each individual (PetLife.tickPerformance): only newly visible badge-holders produce a sighting. */
    public static void scan(YukkuriEntity o) {
        if (!enabled() || !(o.level() instanceof ServerLevel level) || o.getVariant().isPredator() || o.getVariant().isHouse()) return;
        long now = level.getGameTime(); clock = now;
        if (!o.pet().calm() || WildEvents.reserved(o)) return;
        var views = VIEWS.computeIfAbsent(o.getUUID(), id -> new HashMap<>());
        views.values().removeIf(v -> now - v.last > FORGET_VIEW_TICKS);
        if (VIEWS.size() > 256) VIEWS.entrySet().removeIf(e -> e.getValue().isEmpty() && level.getEntity(e.getKey()) == null);
        int mine = o.pet().visibleEffective().id();
        var ledger = o.pet().badgeLedger();
        List<YukkuriEntity> seen = new ArrayList<>();
        for (var y : level.getEntitiesOfClass(YukkuriEntity.class, o.getBoundingBox().inflate(SCAN_RADIUS, 3, SCAN_RADIUS), peer -> peer != o && peer.isAlive() && !peer.isRemoved()
                && !peer.isPassenger() && !peer.getVariant().isHouse() && !peer.life().isSleeping())) {
            if (!o.hasLineOfSight(y) || !inFront(o, y)) continue;
            int theirs = y.pet().visibleEffective().id();
            var known = ledger.get(y.getUUID(), now);
            // Relevant: a badge to notice, a badge of my own to show off, or a badge I remember that is gone.
            if (theirs > 0 || o.pet().isPet() && mine > 0 || known != null && known.lastGrade() > theirs) seen.add(y);
        }
        var viewer = level.getNearestPlayer(o, AUDIENCE_RADIUS);
        for (var y : seen) {
            var v = views.get(y.getUUID());
            if (v == null) { views.put(y.getUUID(), new View(now)); continue; }
            v.last = now;
            if (!v.reported && now - v.since >= DWELL_TICKS) { v.reported = true; v.nextRoll = now + ROLL_EVERY; sighted(level, o, y, viewer, seen, now, true); }
            else if (v.reported && now >= v.nextRoll) { v.nextRoll = now + ROLL_EVERY + o.getRandom().nextInt(100); sighted(level, o, y, viewer, seen, now, false); }
        }
    }

    /** A badge-holder has just taken a bite: hungry wild onlookers decide there and then whether to beg for it. The periodic sighting rolls (every few seconds)
     *  can almost never coincide with the ~1.8 s window in which a bite is held in the mouth, so this is the natural way for B4 to happen. */
    public static void mealSeen(YukkuriEntity subject) {
        if (!enabled() || !(subject.level() instanceof ServerLevel level) || subject.getVariant().isPredator() || subject.getVariant().isHouse()
                || subject.pet().visibleEffective().id() <= 0 || subject.life().mouth().isEmpty()) return;
        long now = level.getGameTime(); clock = now;
        var viewer = level.getNearestPlayer(subject, AUDIENCE_RADIUS);
        for (var o : level.getEntitiesOfClass(YukkuriEntity.class, subject.getBoundingBox().inflate(SCAN_RADIUS, 3, SCAN_RADIUS), peer -> peer != subject && peer.isAlive()
                && !peer.isRemoved() && !peer.isPassenger() && !peer.getVariant().isHouse() && !peer.getVariant().isPredator() && !peer.life().isSleeping()
                && peer.pet().owner() == null && !peer.pet().isPet() && peer.life().nutrition() >= 30 && peer.life().nutrition() < 50)) {
            if (!o.pet().calm() || WildEvents.reserved(o) || !o.hasLineOfSight(subject) || !inFront(o, subject)) continue;
            sighted(level, o, subject, viewer, List.of(subject), now, false, Arc.BEG_BITE);
            if (subject.life().mealHeld()) return; // one beggar at a time
        }
    }

    private static void sighted(ServerLevel level, YukkuriEntity o, YukkuriEntity s, Player viewer, List<YukkuriEntity> seen, long now, boolean edge) {
        sighted(level, o, s, viewer, seen, now, edge, null);
    }

    /** {@code only}: roll just that arc's own probability instead of the shared scale (an event-driven chance, not a periodic sighting). */
    private static void sighted(ServerLevel level, YukkuriEntity o, YukkuriEntity s, Player viewer, List<YukkuriEntity> seen, long now, boolean edge, Arc only) {
        var ledger = o.pet().badgeLedger();
        int grade = s.pet().visibleEffective().id();
        BadgeLedger.Sighting sight;
        if (edge) sight = ledger.note(s.getUUID(), grade, now);
        else { var e = ledger.ensure(s.getUUID(), grade, now); sight = new BadgeLedger.Sighting(e, false, false, false, e.lastGrade()); }
        var entry = sight.entry();
        var observer = observer(o, seen);
        var subject = subject(level, o, s, viewer);
        var relation = new BadgeArcPolicy.Relation(YukkuriWorldData.get(level).affinity(o.getUUID(), s.getUUID()), FamilyData.get(level).related(o.getUUID(), s.getUUID()),
                sight.first(), sight.changed(), sight.lapsed(), entry.sinceArc(now) < 24000L, entry.impression(), entry.flags(), entry.seenCount());
        var group = o.settlement().group();
        var context = new BadgeArcPolicy.Context(viewer != null, Rumours.strength(group, Rumours.WARY_HUMAN, now) > 0, Rumours.strength(group, Rumours.BADGE_FED, now) > 0,
                snatchEnabled(), homeClaimEnabled());
        BadgeArcPolicy.Choice choice;
        if (only == null) choice = BadgeArcPolicy.choose(observer, subject, relation, context, o.getRandom().nextDouble());
        else {
            choice = BadgeArcPolicy.Choice.SILENT;
            for (var cand : BadgeArcPolicy.candidates(observer, subject, relation, context))
                if (cand.arc() == only && o.getRandom().nextDouble() < cand.probability()) choice = new BadgeArcPolicy.Choice(cand.arc(), cand.variant());
            if (choice.silent()) return;
        }
        if (choice.silent()) { glance(o, s, edge && (sight.first() || sight.changed())); return; }
        var arc = choice.arc();
        UUID viewerId = viewer == null ? null : viewer.getUUID();
        if (budget().check(viewerId, arc.cost, o.getUUID(), s.getUUID()) != BadgeBudget.Verdict.OK) { glance(o, s, edge); return; }
        YukkuriEntity extra = extraFor(arc, o, s, seen);
        var scene = BadgeArcs.start(level, arc, choice.variant(), o, s, viewer, extra);
        if (scene == null) { glance(o, s, edge); return; }
        budget().commit(viewerId, arc.cost, o.getUUID(), s.getUUID());
        ledger.arc(s.getUUID(), arc.id, now);
    }

    private static void glance(YukkuriEntity o, YukkuriEntity s, boolean worthIt) {
        if (worthIt && budget().microAllowed(o.getUUID())) o.pet().glanceAt(s, 30);
    }

    private static YukkuriEntity extraFor(Arc arc, YukkuriEntity o, YukkuriEntity s, List<YukkuriEntity> seen) {
        if (arc == Arc.GLANCE && !o.family().adult()) return seen.stream().filter(p -> p != s && WildEvents.childOf(p, o) && p.pet().owner() == null).findFirst().orElse(null);
        if (arc == Arc.ELDER_WARN) return seen.stream().filter(p -> p != s && WildEvents.childOf(o, p)).findFirst().orElse(null);
        return null;
    }

    static BadgeArcPolicy.Observer observer(YukkuriEntity o, List<YukkuriEntity> around) {
        var p = o.life().personality();
        var group = o.settlement().group();
        boolean childNear = around.stream().anyMatch(y -> WildEvents.childOf(o, y));
        boolean elder = o.family().adult() && (group != null && o.getUUID().equals(group.leader) || childNear);
        return new BadgeArcPolicy.Observer(o.pet().isPet(), !o.family().adult(), elder, childNear, p.trait(Trait.CURIOSITY), p.trait(Trait.BOLDNESS), p.selfishness(),
                p.aggression(), p.attachment(), p.selfControl(), Math.round(o.life().nutrition()), o.pet().stress(), o.pet().isPet() ? o.pet().visibleEffective().id() : 0,
                Math.round(o.getBbWidth() * 100));
    }

    static BadgeArcPolicy.Subject subject(ServerLevel level, YukkuriEntity o, YukkuriEntity s, Player viewer) {
        boolean pet = s.pet().owner() != null;
        boolean eating = s.life().mealTicks() > 0 && s.life().mealTicks() < 36 && !s.life().mouth().isEmpty();
        boolean ownerNear = ownerWithin(level, s, 12) != null;
        boolean ownerOnScene = ownerWithin(level, s, 24) != null;
        boolean trusted = false;
        if (o.pet().owner() == null && viewer != null && WildEvents.playerNear(o, viewer) && viewer.distanceToSqr(o) <= 100) trusted = WildEvents.canAdopt(o, viewer);
        var home = s.life().home();
        boolean homeNear = pet && home != null && !s.life().naturalHome() && home.dimension().equals(level.dimension()) && home.pos().distSqr(o.blockPosition()) <= 36;
        return new BadgeArcPolicy.Subject(s.pet().visibleEffective().id(), pet, eating, ownerNear, trusted, homeNear, pet && !ownerNear && ownerOnScene,
                s.life().hasAccessory(), Math.round(s.getBbWidth() * 100));
    }

    /** A deliberate greeting between two individuals (YukkuriSocial): always a silent record; only a budgeted one-liner is performed.
     *  True when a badge reaction replaced the ordinary greeting. */
    public static boolean greeting(YukkuriEntity o, YukkuriEntity peer) {
        if (!(o.level() instanceof ServerLevel level)) return false;
        long now = level.getGameTime(); clock = now;
        var ledger = o.pet().badgeLedger();
        ledger.note(peer.getUUID(), peer.pet().visibleEffective().id(), now);
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
        o.pet().badgeLedger().ensure(s.getUUID(), s.pet().visibleEffective().id(), clock);
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
