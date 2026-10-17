package io.github.longshou.mcyukkuri.pet.social;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.event.WildEventData;
import io.github.longshou.mcyukkuri.event.WildEventData.Kind;
import io.github.longshou.mcyukkuri.event.WildEventData.Phase;
import io.github.longshou.mcyukkuri.event.WildEventData.Scene;
import io.github.longshou.mcyukkuri.event.WildEvents;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.pet.PetScenes;
import io.github.longshou.mcyukkuri.pet.social.BadgeArcPolicy.Arc;
import io.github.longshou.mcyukkuri.pet.stage.Staging;
import io.github.longshou.mcyukkuri.registry.ModItems;
import io.github.longshou.mcyukkuri.settlement.Rumours;
import io.github.longshou.mcyukkuri.social.YukkuriPersonality.Disposition;
import io.github.longshou.mcyukkuri.social.YukkuriPersonality.Trait;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.phys.Vec3;

/** The multi-beat badge scenes. Every arc is a WildEventData scene of kind BADGE_ARC (so claims, exclusion, persistence and yielding of the other
 *  systems come for free): members are [observer, subject, optional third], {@code Scene.responses} is the beat, {@code sourceCount} the tick the beat
 *  began and {@code result} the stance chosen so far. Each arc ends with an outcome that {@link #settle} writes into the ledgers, affinity, stress,
 *  Poise and the community's rumours; an interrupted arc writes nothing. Movement is done by {@link #move} (called from WildEventGoal). */
public final class BadgeArcs {
    private static final Map<UUID, Long> TOLD = new HashMap<>();
    private static final int TIMEOUT = 1400;
    private BadgeArcs() {}

    public static void reset() { TOLD.clear(); }
    public static boolean handles(Scene s) { return s != null && s.kind == Kind.BADGE_ARC; }

    /** Ticks the participants stay out of new scenes after the arc closes (the observer's own pair cooldown lives in the budget). */
    public static int cooldown(Scene s) {
        Arc arc = Arc.byId(s.card);
        if (arc == null) return 1200;
        return switch (arc) {
            case GLANCE, ENVY, BOAST, PITY, ELDER_WARN -> 6000;
            case PLEA -> 24000;
            default -> 12000;
        };
    }

    // ------------------------------------------------------------------ starting

    /** Whether the participant may be pulled into an arc right now; no tasks or sessions that this would break. */
    static boolean usable(YukkuriEntity e) {
        return e.isAlive() && !e.isRemoved() && !e.isNoAi() && !e.isPanicking() && !e.pet().busy() && !e.operations().active() && !e.isInWaterOrBubble()
                && !e.isOnFire() && !e.isPassenger() && !e.life().isSleeping() && e.hurtTime == 0 && e.family().brood() == null;
    }
    private static boolean subjectReady(YukkuriEntity e, boolean allowMeal) {
        return usable(e) && WildEvents.safe(e) && e.onGround() && !e.dailyLife().active() && !e.groupInteraction().active() && !e.playLife().active()
                && !e.family().active() && (allowMeal || e.life().mealTicks() == 0);
    }
    private static boolean eating(YukkuriEntity e) {
        return e.life().mealTicks() > 0 && e.life().mealTicks() <= 36 && !e.life().mouth().isEmpty();
    }

    /** Null when the arc could start; otherwise a short machine reason (shown by the admin command). */
    public static String check(ServerLevel level, Arc arc, YukkuriEntity o, YukkuriEntity s, Player player, YukkuriEntity extra) {
        if (o == s || !o.isAlive() || !s.isAlive() || o.level() != level || s.level() != level) return "invalid_pair";
        if (!WildEvents.available(o)) return "observer_unavailable";
        if (!subjectReady(s, arc == Arc.BEG_BITE)) return "subject_unavailable";
        if (WildEventData.get(level).forMember(s.getUUID()) != null) return "subject_in_scene";
        if (o.distanceToSqr(s) > 144 || !o.hasLineOfSight(s)) return "not_in_sight";
        switch (arc) {
            case BEG_BITE -> {
                if (o.pet().owner() != null) return "observer_owned";
                if (!eating(s)) return "subject_not_eating";
                if (!o.life().canEat() || o.life().nutrition() >= 75) return "observer_not_hungry";
            }
            case PLEA -> {
                if (o.pet().owner() != null) return "observer_owned";
                if (player == null || !WildEvents.playerNear(o, player)) return "no_player_near";
            }
            case BOAST -> { if (!o.pet().isPet()) return "observer_not_pet"; }
            case HOME_CLAIM -> {
                if (s.life().home() == null || s.life().naturalHome()) return "subject_has_no_home";
                if (o.pet().owner() != null) return "observer_owned";
            }
            case SNATCH -> {
                if (!BadgeSocial.snatchEnabled()) return "snatch_disabled";
                if (!s.life().hasAccessory() || s.isVehicle()) return "subject_has_no_accessory";
                if (o.pet().owner() != null) return "observer_owned";
            }
            default -> { }
        }
        return null;
    }

    public static Scene start(ServerLevel level, Arc arc, String variant, YukkuriEntity o, YukkuriEntity s, Player player, YukkuriEntity extra) {
        if (check(level, arc, o, s, player, extra) != null) return null;
        var data = WildEventData.get(level);
        long now = level.getGameTime();
        var origin = GlobalPos.of(level.dimension(), o.blockPosition());
        List<YukkuriEntity> all = new ArrayList<>(List.of(o, s));
        boolean third = extra != null && extra != o && extra != s && WildEvents.available(extra) && extra.distanceToSqr(o) <= 100;
        if (third) all.add(extra);
        List<UUID> ids = all.stream().map(Entity::getUUID).toList();
        if (!data.canStart(origin, ids, now)) {
            if (!third) return null;
            all.remove(extra); ids = all.stream().map(Entity::getUUID).toList();
            if (!data.canStart(origin, ids, now)) return null;
        }
        if (arc == Arc.BEG_BITE && !s.life().holdMeal(260)) return null;
        Scene scene = data.start(Kind.BADGE_ARC, arc.id, origin, ids, player == null ? null : player.getUUID(), now);
        if (scene == null) { if (arc == Arc.BEG_BITE) s.life().releaseMeal(); return null; }
        scene.result = variant == null ? "" : variant;
        scene.responses = 0; scene.sourceCount = 0;
        for (var e : all) { e.setPersistenceRequired(); e.social().stop(); }
        if (arc == Arc.PLEA && player != null) tell(level, player, "pet.mcyukkuri.badge.plea", o, true);
        return scene;
    }

    // ------------------------------------------------------------------ helpers

    private static YukkuriEntity member(ServerLevel level, Scene s, int index) {
        return index < s.members.size() && level.getEntity(s.members.get(index)) instanceof YukkuriEntity y && y.isAlive() ? y : null;
    }
    private static int since(Scene s) { return s.ticks - s.sourceCount; }
    private static void next(Scene s) { s.responses++; s.sourceCount = s.ticks; }
    private static void end(ServerLevel level, Scene s, Phase phase, String result) { WildEvents.finish(level, s, phase, result); }
    private static void quiet(YukkuriEntity e) { if (e.isCommandPlaying() && !e.isPanicking()) e.playAction("idle"); }
    private static void look(YukkuriEntity e, Entity at) { if (at != null) e.getLookControl().setLookAt(at, 30, 30); }
    private static int grade(YukkuriEntity e) { return e.pet().visibleEffective().id(); }
    private static void like(ServerLevel level, YukkuriEntity a, YukkuriEntity b, int delta) { BadgeSocial.nudge(YukkuriWorldData.get(level), a.getUUID(), b.getUUID(), delta); }
    /** Holder remembers the subject: flags and impression, creating the entry when the holder never sighted it. */
    private static void note(YukkuriEntity holder, YukkuriEntity subject, int flags, int impression, long now) {
        var ledger = holder.pet().badgeLedger();
        ledger.ensure(subject.getUUID(), grade(subject), now);
        if (flags != 0) ledger.flag(subject.getUUID(), flags, now);
        if (impression != 0) ledger.impress(subject.getUUID(), impression, now);
    }

    /** How the badge-holder answers a stranger's interest, by its own nature. */
    private static String attitude(YukkuriEntity sub) {
        var p = sub.life().personality();
        if (p.selfishness() < 45) return "kind";
        if (p.trait(Trait.BOLDNESS) >= 55 && p.selfishness() >= 50) return "proud";
        return "cold";
    }
    private static void answer(String attitude, YukkuriEntity sub, YukkuriEntity to) {
        switch (attitude) {
            case "kind" -> PetScenes.act(sub, "greet", "happy", "badge_show_kind", 60, to);
            case "proud" -> PetScenes.act(sub, "proud", "smug", "badge_proud", 70, to);
            default -> PetScenes.act(sub, "ignore", "closed_smile", null, 50, to);
        }
    }

    /** At most one actionbar line per owner per five minutes, and only for arcs that really involve their pet. */
    static void tell(ServerLevel level, Player owner, String key, YukkuriEntity pet, boolean force) {
        if (owner == null) return;
        long now = level.getGameTime();
        if (!force && TOLD.getOrDefault(owner.getUUID(), 0L) > now) return;
        if (TOLD.size() > 64) TOLD.values().removeIf(t -> t <= now);
        TOLD.put(owner.getUUID(), now + 6000);
        owner.displayClientMessage(Component.translatable(key, pet.getDisplayName()), true);
    }

    // ------------------------------------------------------------------ advancing

    public static void advance(ServerLevel level, Scene s, List<YukkuriEntity> loaded) {
        Arc arc = Arc.byId(s.card);
        if (arc == null || loaded.size() < 2) { end(level, s, Phase.INTERRUPTED, "unknown_arc"); return; }
        for (var e : loaded) {
            if (!usable(e)) { end(level, s, Phase.INTERRUPTED, "danger_or_unavailable"); return; }
            if (e.blockPosition().distSqr(s.origin.pos()) > 40 * 40) { end(level, s, Phase.INTERRUPTED, "left_region"); return; }
        }
        s.ticks++; s.phaseTicks++; WildEventData.get(level).setDirty();
        if (s.ticks > TIMEOUT) { end(level, s, Phase.INTERRUPTED, "arc_timed_out"); return; }
        var o = loaded.get(0); var sub = loaded.get(1); var extra = loaded.size() > 2 ? loaded.get(2) : null;
        switch (arc) {
            case GLANCE -> glance(level, s, o, sub, extra);
            case ENVY -> envy(level, s, o, sub);
            case BOAST -> boast(level, s, o, sub);
            case BEG_BITE -> begBite(level, s, o, sub);
            case PROVOKE -> provoke(level, s, o, sub);
            case PLEA -> plea(level, s, o, sub);
            case PITY -> pity(level, s, o, sub);
            case ELDER_WARN -> elder(level, s, o, sub, extra);
            case HOME_CLAIM -> homeClaim(level, s, o, sub);
            case SNATCH -> snatch(level, s, o, sub);
        }
    }

    /** B1: a shiny thing. The child asks, the badge-holder answers by nature, and a parent who is there pulls the child away. */
    private static void glance(ServerLevel level, Scene s, YukkuriEntity o, YukkuriEntity sub, YukkuriEntity parent) {
        int t = since(s);
        switch (s.responses) {
            case 0 -> { if (t == 1) PetScenes.act(o, "nervous", "surprise", "badge_glance", 50, sub); if (t >= 45) { quiet(o); next(s); } }
            case 1 -> { if (o.distanceToSqr(sub) <= 9) next(s); else if (t >= 160) end(level, s, Phase.INTERRUPTED, "glance_unreached"); }
            case 2 -> {
                if (t == 1) { if (o.family().adult()) PetScenes.act(o, "look_around", "neutral", null, 40, sub); else PetScenes.act(o, "beg", "open_smile", "badge_ask", 50, sub); }
                if (t >= 55) next(s);
            }
            case 3 -> {
                if (t == 1) { s.result = attitude(sub); answer(s.result, sub, o); }
                if (t >= 70) { quiet(sub); quiet(o); next(s); }
            }
            case 4 -> {
                if (parent == null || o.family().adult()) { if (t >= 20) end(level, s, Phase.COMPLETE, "glance_" + s.result); return; }
                if (t == 1) parent.showReaction("surprise", "badge_pull_back", 70);
                if (parent.distanceToSqr(o) <= 4 || t >= 120) { s.result = s.result + "|pulled"; next(s); }
            }
            default -> { if (t >= 70) end(level, s, Phase.COMPLETE, "glance_" + s.result.replace("|", "_")); }
        }
    }

    /** B2: envy that turns into sulking, a snarl, or (if the holder is kind) a comforting nuzzle. */
    private static void envy(ServerLevel level, Scene s, YukkuriEntity o, YukkuriEntity sub) {
        int t = since(s);
        switch (s.responses) {
            case 0 -> {
                if (t == 1) {
                    var p = o.life().personality();
                    boolean snark = p.aggression() >= 55 || p.selfishness() >= 65;
                    s.result = snark ? "snark" : "sulk";
                    if (snark) { PetScenes.act(o, "puff", "angry", "badge_envy_snark", 70, sub); Staging.anger(level, o.position().add(0, o.getBbHeight(), 0)); }
                    else PetScenes.act(o, "sulk", "teary", "badge_envy", 80, sub);
                }
                if (t >= 70) { quiet(o); next(s); }
            }
            case 1 -> {
                if (t == 1) {
                    String a = attitude(sub);
                    boolean timid = sub.life().personality().trait(Trait.BOLDNESS) < 40;
                    String stance;
                    if (a.equals("kind")) { PetScenes.act(sub, "nuzzle", "happy", "badge_show_kind", 60, o); stance = "comforted"; }
                    else if (a.equals("proud") && !timid) { PetScenes.act(sub, "proud", "smug", "badge_proud", 70, o); stance = "proud"; }
                    else if (timid) { PetScenes.act(sub, "tremble", "cry", "afraid", 60, o); Staging.sweat(sub); stance = "scared"; }
                    else { PetScenes.act(sub, "ignore", "closed_smile", null, 50, o); stance = "cold"; }
                    s.result = s.result + "|" + stance; // variant|stance
                }
                if (t >= 70) { quiet(sub); next(s); }
            }
            default -> { if (t >= (s.result.endsWith("|comforted") ? 20 : 90)) end(level, s, Phase.COMPLETE, "envy_" + s.result.substring(s.result.indexOf('|') + 1)); }
        }
    }

    /** B3: a badged pet shows off (or, if kind, humbly shares) its badge with someone of lower standing. */
    private static void boast(ServerLevel level, Scene s, YukkuriEntity o, YukkuriEntity sub) {
        int t = since(s);
        boolean boastful = s.result.startsWith("boast");
        switch (s.responses) {
            case 0 -> { if (o.distanceToSqr(sub) <= 16) next(s); else if (t >= 120) end(level, s, Phase.INTERRUPTED, "boast_unreached"); }
            case 1 -> {
                if (t == 1) { quiet(o); if (boastful) PetScenes.act(o, "proud", "smug", "badge_proud", 70, sub); else PetScenes.act(o, "greet", "closed_smile", "badge_humble", 70, sub); }
                if (t >= 60) { quiet(o); next(s); }
            }
            default -> {
                if (t == 1) {
                    var p = sub.life().personality();
                    String reply;
                    if (sub.pet().isPet() || p.selfishness() < 55 && p.aggression() < 55) reply = "admired";
                    else if (boastful && p.aggression() >= 55 && p.trait(Trait.BOLDNESS) >= 55) reply = "retorted";
                    else reply = "ignored";
                    s.result = s.result + "|" + reply;
                    switch (reply) {
                        case "admired" -> PetScenes.act(sub, sub.pet().isPet() ? "bow" : "beg", "open_smile", "badge_admire", 60, o);
                        case "retorted" -> PetScenes.act(sub, "puff", "angry", "badge_retort", 60, o);
                        default -> PetScenes.act(sub, "ignore", "closed_smile", null, 50, o);
                    }
                }
                if (t >= 70) {
                    String reply = s.result.substring(s.result.indexOf('|') + 1);
                    end(level, s, Phase.COMPLETE, (boastful ? "boast_" : "humble_") + (reply.equals("admired") && !boastful ? "accepted" : reply));
                }
            }
        }
    }

    /** B4: a hungry wild one begs at a bite the pet is really chewing; the pet decides by its share score and hands it over or keeps it. */
    private static void begBite(ServerLevel level, Scene s, YukkuriEntity o, YukkuriEntity sub) {
        int t = since(s);
        switch (s.responses) {
            case 0 -> {
                if (!sub.life().mealHeld()) { end(level, s, Phase.INTERRUPTED, "meal_over"); return; }
                if (t == 1) o.showReaction("teary", "badge_beg", 70);
                if (o.distanceToSqr(sub) <= 4) { quiet(o); next(s); }
                else if (t >= 140) { sub.life().releaseMeal(); end(level, s, Phase.INTERRUPTED, "begger_unreached"); }
            }
            case 1 -> {
                if (!sub.life().mealHeld()) { end(level, s, Phase.INTERRUPTED, "meal_over"); return; }
                if (t == 1) {
                    PetScenes.act(o, "beg", "drool", null, 40, sub);
                    var world = YukkuriWorldData.get(level);
                    int score = sub.life().personality().shareScore(sub.life().nutrition(), o.life().nutrition(), sub.family().caresFor(o), world.affinity(sub.getUUID(), o.getUUID()));
                    s.result = score >= 50 && o.life().canEat() ? "offer" : "refuse";
                    look(sub, o);
                }
                if (t >= 36) next(s);
            }
            case 2 -> {
                if (t == 1) {
                    if (s.result.equals("offer") && sub.life().handOverMouth(o, "badge_share_offer")) s.result = "shared";
                    else { s.result = "refuse"; sub.life().releaseMeal(); sub.showReaction("cheek_puff", "badge_share_refuse", 70); look(sub, o); }
                }
                if (s.result.equals("shared")) {
                    if (t >= 12 && o.life().mealTicks() == 0) next(s);
                    else if (t >= 160) end(level, s, Phase.COMPLETE, "shared");
                } else if (t >= 30) { PetScenes.act(o, "droop", "teary", null, 50, sub); next(s); }
            }
            default -> {
                if (s.result.equals("shared")) {
                    if (t == 1) PetScenes.act(o, "bow", "happy", "badge_thanks", 60, sub);
                    if (t >= 60) end(level, s, Phase.COMPLETE, "shared");
                } else if (t >= 60) end(level, s, Phase.REFUSED, "refused");
            }
        }
    }

    /** B5: a provocation. The pet ignores it, answers it, or runs; a pet whose owner is around then reports to the owner (B7 praise window). */
    private static void provoke(ServerLevel level, Scene s, YukkuriEntity o, YukkuriEntity sub) {
        int t = since(s);
        switch (s.responses) {
            case 0 -> {
                if (t == 1) o.showReaction("angry", "badge_mock", 70);
                if (o.distanceToSqr(sub) <= 6.25) { quiet(o); next(s); }
                else if (t >= 140) end(level, s, Phase.INTERRUPTED, "provoker_unreached");
            }
            case 1 -> {
                if (t == 1) { PetScenes.act(o, "puff", "angry", null, 50, sub); Staging.anger(level, o.position().add(0, o.getBbHeight(), 0)); }
                if (t >= 40) { quiet(o); next(s); }
            }
            case 2 -> {
                if (t == 1) {
                    var p = sub.life().personality();
                    if (p.trait(Trait.BOLDNESS) >= 55 && p.aggression() >= 45) { s.result = "retort"; PetScenes.act(sub, "puff", "angry", "badge_retort", 60, o); }
                    else if (p.selfControl() >= 50) { s.result = "ignore"; PetScenes.act(sub, "ignore", "closed_smile", null, 50, o); }
                    else { s.result = "flee"; PetScenes.act(sub, "tremble", "cry", "afraid", 60, o); Staging.sweat(sub); }
                }
                if (t >= 70) { quiet(sub); next(s); }
            }
            case 3 -> {
                if (t == 1) {
                    quiet(sub);
                    switch (s.result) {
                        case "retort" -> {
                            boolean backs = o.life().personality().trait(Trait.BOLDNESS) < 65 || sub.getBbWidth() > o.getBbWidth();
                            s.result = backs ? "retort_backed" : "retort_standoff";
                            if (backs) PetScenes.act(o, "droop", "teary", null, 50, sub); else PetScenes.act(o, "push", "angry", null, 40, sub);
                        }
                        case "ignore" -> PetScenes.act(o, "sulk", "angry", null, 50, sub);
                        default -> PetScenes.act(o, "proud", "smug", null, 50, sub);
                    }
                }
                if (t >= 60) {
                    quiet(o);
                    boolean report = !s.result.startsWith("retort") && BadgeSocial.ownerWithin(level, sub, 24) != null;
                    if (report) next(s); else end(level, s, Phase.COMPLETE, "provoke_" + s.result);
                }
            }
            case 4 -> {
                var owner = BadgeSocial.ownerWithin(level, sub, 24);
                if (owner == null) { end(level, s, Phase.COMPLETE, "provoke_" + s.result); return; }
                if (sub.distanceToSqr(owner) <= 6.25) next(s);
                else if (t >= 240) end(level, s, Phase.COMPLETE, "provoke_" + s.result);
            }
            default -> {
                var owner = BadgeSocial.ownerWithin(level, sub, 24);
                if (t == 1 && owner != null) {
                    boolean calm = s.result.equals("ignore");
                    PetScenes.act(sub, "bow", calm ? "happy" : "teary", calm ? "pet_report_owner" : "pet_report_hurt", 70, owner);
                    BadgePraise.start(sub, owner);
                }
                if (t >= 70) end(level, s, Phase.COMPLETE, "provoke_" + s.result + "_reported");
            }
        }
    }

    /** B6: a wild one walks up to a player and asks to be kept. The player closes it for real: adopt, feed, decline, or let the window lapse. */
    private static void plea(ServerLevel level, Scene s, YukkuriEntity o, YukkuriEntity sub) {
        int t = since(s);
        var player = s.player == null ? null : level.getPlayerByUUID(s.player);
        if (player == null || !player.isAlive() || player.isSpectator() || player.distanceToSqr(o) > 400) { end(level, s, Phase.REFUSED, "player_left"); return; }
        switch (s.responses) {
            case 0 -> {
                if (o.distanceToSqr(player) <= 9) {
                    quiet(o);
                    PetScenes.act(o, o.getRandom().nextBoolean() ? "beg" : "shy", "teary", "wild_plea_keep", 40, player);
                    boolean possessive = sub.life().personality().selfishness() >= 60;
                    if (possessive) PetScenes.act(sub, "puff", "angry", null, 40, o); else PetScenes.act(sub, "greet", "happy", null, 40, o);
                    next(s);
                } else if (t >= 220) end(level, s, Phase.REFUSED, "plea_unreached");
            }
            default -> {
                if (t > 0 && t % 130 == 0) o.showReaction("teary", "wild_plea_keep", 60);
                if (t >= 400) end(level, s, Phase.REFUSED, "plea_ignored");
            }
        }
    }

    /** B11: the badge this one remembers is gone. */
    private static void pity(ServerLevel level, Scene s, YukkuriEntity o, YukkuriEntity sub) {
        int t = since(s);
        boolean scorn = s.result.equals("scorn");
        switch (s.responses) {
            case 0 -> { if (o.distanceToSqr(sub) <= 4) next(s); else if (t >= 120) end(level, s, Phase.INTERRUPTED, "pity_unreached"); }
            case 1 -> {
                if (t == 1) { quiet(o); if (scorn) PetScenes.act(o, "puff", "angry", "badge_envy_snark", 60, sub); else PetScenes.act(o, "nuzzle", "teary", "badge_pity", 70, sub); }
                if (t >= 55) { quiet(o); next(s); }
            }
            default -> {
                if (t == 1) { if (scorn) PetScenes.act(sub, "tremble", "cry", null, 60, o); else PetScenes.act(sub, "droop", "teary", null, 60, o); }
                if (t >= 60) end(level, s, Phase.COMPLETE, scorn ? "scorn_hurt" : "pity_comforted");
            }
        }
    }

    /** B12: an elder with a child at its side has seen a human nearby with a kept yukkuri: warns the child away, or lets it stay if the community believes humans feed. */
    private static void elder(ServerLevel level, Scene s, YukkuriEntity o, YukkuriEntity sub, YukkuriEntity child) {
        int t = since(s);
        boolean warn = !s.result.equals("kind");
        switch (s.responses) {
            case 0 -> {
                if (t == 1) PetScenes.act(o, warn ? "nervous" : "bow", warn ? "surprise" : "open_smile", warn ? "elder_warn" : "elder_kind", 80, sub);
                if (t >= 90) { quiet(o); next(s); }
            }
            case 1 -> {
                if (child != null && t == 1) child.showReaction("surprise", warn ? "badge_pull_back" : "badge_ask", 60);
                if (t >= 50) next(s);
            }
            default -> { if (t >= (warn ? 80 : 20)) end(level, s, Phase.COMPLETE, warn ? "elder_warned" : "elder_allowed"); }
        }
    }

    /** B9: a hungry wild one declares the pet's home its own. A pet that protects defends it; the rest give way and tell their owner. */
    private static void homeClaim(ServerLevel level, Scene s, YukkuriEntity o, YukkuriEntity sub) {
        int t = since(s);
        var home = sub.life().home();
        if (home == null) { end(level, s, Phase.INTERRUPTED, "home_gone"); return; }
        switch (s.responses) {
            case 0 -> {
                if (t == 1) o.showReaction("angry", "mine", 70);
                if (o.blockPosition().distSqr(home.pos()) <= 4) { quiet(o); next(s); }
                else if (t >= 220) end(level, s, Phase.INTERRUPTED, "home_unreached");
            }
            case 1 -> {
                if (t == 1) {
                    PetScenes.act(o, "puff", "smug", "mine", 70, sub);
                    s.result = sub.life().personality().protects() ? "guard" : "yield";
                    var owner = BadgeSocial.ownerWithin(level, sub, 24);
                    if (owner != null) tell(level, owner, "pet.mcyukkuri.badge.home", sub, false);
                }
                if (t >= 50) { quiet(o); next(s); }
            }
            case 2 -> {
                if (s.result.equals("guard")) {
                    if (t == 1) sub.showReaction("angry", "badge_retort", 70);
                    if (sub.distanceToSqr(o) <= 6.25) { PetScenes.act(sub, "puff", "angry", null, 50, o); Staging.anger(level, sub.position().add(0, sub.getBbHeight(), 0)); next(s); }
                    else if (t >= 160) next(s);
                } else {
                    if (t == 1) { PetScenes.act(sub, "tremble", "cry", "afraid", 70, o); Staging.sweat(sub); }
                    if (t >= 60) { quiet(sub); next(s); }
                }
            }
            case 3 -> {
                boolean guard = s.result.equals("guard");
                if (t == 1) { if (guard) PetScenes.act(o, "droop", "teary", null, 60, sub); else PetScenes.act(o, "proud", "smug", null, 60, sub); }
                if (t >= 60) {
                    quiet(o); quiet(sub);
                    if (guard) end(level, s, Phase.COMPLETE, "home_defended");
                    else if (BadgeSocial.ownerWithin(level, sub, 24) != null) next(s);
                    else end(level, s, Phase.COMPLETE, "home_yielded");
                }
            }
            case 4 -> {
                var owner = BadgeSocial.ownerWithin(level, sub, 24);
                if (owner == null) { end(level, s, Phase.COMPLETE, "home_yielded"); return; }
                if (sub.distanceToSqr(owner) <= 6.25) next(s); else if (t >= 240) end(level, s, Phase.COMPLETE, "home_yielded");
            }
            default -> {
                var owner = BadgeSocial.ownerWithin(level, sub, 24);
                if (t == 1 && owner != null) { PetScenes.act(sub, "bow", "teary", "pet_report_hurt", 70, owner); BadgePraise.start(sub, owner); }
                if (t >= 70) end(level, s, Phase.COMPLETE, "home_yielded_reported");
            }
        }
    }

    /** B8 (config switch, default off): a grudge-bearing wild one pulls the lone pet's accessory off and runs. The item is real, never expires and is recoverable. */
    private static void snatch(ServerLevel level, Scene s, YukkuriEntity o, YukkuriEntity sub) {
        int t = since(s);
        switch (s.responses) {
            case 0 -> {
                if (t == 1) o.showReaction("angry", "badge_mock", 60);
                if (o.distanceToSqr(sub) <= 2.25) { quiet(o); next(s); }
                else if (t >= 140) end(level, s, Phase.INTERRUPTED, "snatcher_unreached");
            }
            case 1 -> {
                if (t == 1) {
                    if (!BadgeSocial.snatchEnabled() || !knockOff(level, sub, o)) { end(level, s, Phase.INTERRUPTED, "nothing_to_take"); return; }
                    PetScenes.act(o, "push", "angry", null, 30, sub);
                    sub.showReaction("cry", "lost_accessory", 140);
                }
                if (t >= 30) { quiet(o); next(s); }
            }
            default -> { if (t >= 70) end(level, s, Phase.COMPLETE, "snatched"); }
        }
    }

    /** The accessory (and with it the visible badge) drops as a real, permanent item that its owner fetches or the player returns; a fallback brings it back after 5 minutes. */
    private static boolean knockOff(ServerLevel level, YukkuriEntity victim, YukkuriEntity thief) {
        if (!victim.life().hasAccessory() || victim.isVehicle()) return false;
        final UUID token = UUID.randomUUID();
        var stack = new ItemStack(ModItems.accessoryFor(victim));
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            tag.putUUID("Owner", victim.getUUID()); tag.putUUID("Token", token); tag.putString("Variant", victim.getVariant().assetId());
            tag.putString("SourceModel", victim.subspecies().assetId());
            tag.putFloat("SourceScale", victim.growthScale() * victim.getScale() * (victim.getVariant().isHouse() ? .32f : 1));
        });
        victim.pet().markAccessory(stack);
        var item = new ItemEntity(level, victim.getX(), victim.getY(), victim.getZ(), stack);
        item.setDefaultPickUpDelay(); item.setUnlimitedLifetime();
        if (!level.addFreshEntity(item)) return false;
        if (victim.life().takeAccessory(thief.getUUID(), token) == null) { item.discard(); return false; }
        PetScenes.later(level, 6000, () -> {
            if (item.isAlive() && victim.isAlive() && victim.life().matchesAccessory(victim.getUUID(), token)) {
                item.setPos(victim.getX(), victim.getY(), victim.getZ()); item.setDefaultPickUpDelay();
            }
        });
        return true;
    }

    // ------------------------------------------------------------------ movement (WildEventGoal)

    private static void walk(YukkuriEntity e, Entity target, double stopSqr, Scene s) {
        look(e, target);
        if (e.distanceToSqr(target) <= stopSqr) { e.getNavigation().stop(); return; }
        if ((s.ticks + e.getId()) % 10 == 0) e.life().navigate(target.blockPosition(), .85, false);
    }
    private static void walkTo(YukkuriEntity e, BlockPos pos, double stopSqr, Scene s) {
        if (e.blockPosition().distSqr(pos) <= stopSqr) { e.getNavigation().stop(); return; }
        if ((s.ticks + e.getId()) % 10 == 0) e.life().navigate(pos, .85, false);
    }
    private static void retreat(YukkuriEntity e, Entity from, double distance, Scene s) {
        if ((s.ticks + e.getId()) % 20 != 0) return;
        Vec3 away = e.position().subtract(from.position()).multiply(1, 0, 1);
        away = away.lengthSqr() < .01 ? new Vec3(1, 0, 0) : away.normalize();
        for (double f : new double[] {1, .7, .5}) {
            BlockPos goal = BlockPos.containing(e.position().add(away.scale(distance * f)));
            if (e.life().dryStand(goal) && e.life().navigate(goal, .8, false)) return;
        }
    }
    private static void hold(YukkuriEntity e, Entity at) { e.getNavigation().stop(); look(e, at); }

    public static void move(YukkuriEntity e, Scene s) {
        Arc arc = Arc.byId(s.card);
        if (arc == null || !(e.level() instanceof ServerLevel level)) return;
        YukkuriEntity o = member(level, s, 0), sub = member(level, s, 1), extra = member(level, s, 2);
        if (o == null || sub == null) return;
        boolean isO = e == o, isSub = e == sub, isExtra = e == extra;
        Entity other = isO ? sub : o;
        int b = s.responses;
        switch (arc) {
            case GLANCE -> {
                if (isO && b == 1) walk(e, sub, 9, s);
                else if (isExtra && b == 4) walk(e, o, 4, s);
                else if (b >= 5 && (isExtra || isO)) { if (isExtra) retreat(e, sub, 10, s); else walk(e, extra == null ? sub : extra, 4, s); }
                else hold(e, isExtra ? o : isSub ? o : sub);
            }
            case ENVY -> {
                if (isO && b >= 2 && !s.result.endsWith("|comforted")) retreat(e, sub, 8, s); else hold(e, other);
            }
            case BOAST -> { if (isO && b == 0) walk(e, sub, 16, s); else hold(e, other); }
            case BEG_BITE -> { if (isO && b == 0) walk(e, sub, 4, s); else hold(e, other); }
            case PROVOKE -> {
                var owner = BadgeSocial.ownerWithin(level, sub, 24);
                if (isO && b == 0) walk(e, sub, 6.25, s);
                else if (isSub && b == 2 && s.result.equals("flee") && owner != null) walk(e, owner, 6.25, s);
                else if (isSub && b >= 4 && owner != null) walk(e, owner, 6.25, s);
                else hold(e, other);
            }
            case PLEA -> {
                var player = s.player == null ? null : level.getPlayerByUUID(s.player);
                if (player == null) { hold(e, other); return; }
                if (isO && b == 0) walk(e, player, 9, s);
                else if (isO && b >= 1) { hold(e, player); }
                else hold(e, isSub ? o : sub);
            }
            case PITY -> { if (isO && b == 0) walk(e, sub, 4, s); else hold(e, other); }
            case ELDER_WARN -> {
                if (isO && b >= 2 && !s.result.equals("kind")) retreat(e, sub, 10, s);
                else if (isExtra && b >= 1) walk(e, o, 4, s);
                else hold(e, isExtra ? o : sub);
            }
            case HOME_CLAIM -> {
                var home = sub.life().home();
                var owner = BadgeSocial.ownerWithin(level, sub, 24);
                if (home == null) { hold(e, other); return; }
                if (isO && b == 0) walkTo(e, home.pos(), 4, s);
                else if (isSub && b == 2 && s.result.equals("guard")) walk(e, o, 6.25, s);
                else if (isSub && b >= 4 && owner != null) walk(e, owner, 6.25, s);
                else hold(e, other);
            }
            case SNATCH -> {
                if (isO && b == 0) walk(e, sub, 2.25, s);
                else if (isO && b >= 2) retreat(e, sub, 12, s);
                else hold(e, other);
            }
        }
    }

    // ------------------------------------------------------------------ hooks from WildEvents

    /** The player took the pleading wild one in (WildEvents.adopt already made it a companion). */
    public static void adopted(ServerLevel level, Scene s, YukkuriEntity e, Player player) {
        if (Arc.byId(s.card) == Arc.PLEA && s.active() && e.getUUID().equals(s.members.getFirst())) end(level, s, Phase.COMPLETE, "plea_adopted");
    }
    /** A real meal from the player's hand reached the pleading one. */
    public static void fed(ServerLevel level, Scene s, YukkuriEntity e, UUID player) {
        if (Arc.byId(s.card) == Arc.PLEA && s.active() && e.getUUID().equals(s.members.getFirst()) && player != null && player.equals(s.player)) end(level, s, Phase.COMPLETE, "plea_fed");
    }

    // ------------------------------------------------------------------ outcome

    /** Writes the lasting consequences of a closed arc; called by WildEvents.finish before the scene is retired (so Scene.result still holds the stance). */
    public static void settle(ServerLevel level, Scene s, Phase phase, String result) {
        Arc arc = Arc.byId(s.card);
        if (arc == null) return;
        var o = member(level, s, 0); var sub = member(level, s, 1); var extra = member(level, s, 2);
        if (arc == Arc.BEG_BITE && sub != null) sub.life().releaseMeal(); // a held bite is always let go
        if (phase == Phase.INTERRUPTED || o == null || sub == null) return;
        if (result.equals("player_declined") && arc != Arc.PLEA) return;
        long now = level.getGameTime();
        note(o, sub, 0, 0, now); note(sub, o, 0, 0, now);
        o.pet().badgeLedger().arc(sub.getUUID(), arc.id, now); sub.pet().badgeLedger().arc(o.getUUID(), arc.id, now);
        var owner = BadgeSocial.ownerWithin(level, sub, 24);
        UUID human = sub.pet().owner() != null ? sub.pet().owner() : sub.getUUID();
        switch (arc) {
            case GLANCE -> {
                if (result.contains("kind")) { note(o, sub, BadgeLedger.ADMIRED, 1, now); note(sub, o, 0, 1, now); like(level, o, sub, 1); }
                else if (result.contains("proud")) { note(o, sub, BadgeLedger.ADMIRED, 0, now); like(level, o, sub, 1); }
                else note(o, sub, BadgeLedger.SNUBBED, 0, now);
                if (result.contains("pulled") && extra != null) { note(extra, sub, BadgeLedger.WARY, -1, now); BadgeSocial.spread(level, extra, Rumours.WARY_HUMAN, human, 1); }
            }
            case ENVY -> {
                if (result.endsWith("comforted")) { note(o, sub, BadgeLedger.ADMIRED, 1, now); note(sub, o, BadgeLedger.HELPED, 1, now); like(level, o, sub, 1); like(level, sub, o, 1); }
                else {
                    note(o, sub, BadgeLedger.ENVIED, -1, now); like(level, o, sub, -1);
                    if (s.result.startsWith("snark") || result.endsWith("scared")) sub.pet().addStress(1);
                }
            }
            case BOAST -> {
                boolean boastful = result.startsWith("boast");
                if (result.endsWith("admired") || result.endsWith("accepted")) { note(sub, o, BadgeLedger.ADMIRED, 1, now); like(level, sub, o, 1); if (!boastful) like(level, o, sub, 1); }
                else {
                    if (boastful) o.pet().addStress(1);
                    if (result.endsWith("retorted")) { like(level, sub, o, -1); note(sub, o, BadgeLedger.MOCKED, -1, now); }
                }
            }
            case BEG_BITE -> {
                if (result.equals("shared")) {
                    note(o, sub, BadgeLedger.FED_BY, 2, now); note(sub, o, BadgeLedger.HELPED, 1, now);
                    BadgeSocial.spread(level, o, Rumours.BADGE_FED, human, 1);
                    tell(level, owner, "pet.mcyukkuri.badge.shared", sub, false);
                } else {
                    note(o, sub, BadgeLedger.SNUBBED, -1, now); like(level, o, sub, -1);
                    sub.life().personality().experienced(Disposition.SELFISHNESS, 1);
                }
            }
            case PROVOKE -> {
                note(sub, o, BadgeLedger.MOCKED, -1, now);
                if (result.contains("ignore")) {
                    note(o, sub, BadgeLedger.SNUBBED, -1, now); like(level, sub, o, -1);
                    if (owner != null) sub.pet().wildPoiseGain();
                } else if (result.contains("retort")) {
                    note(o, sub, BadgeLedger.SNUBBED, -1, now); like(level, o, sub, -1); like(level, sub, o, -1);
                } else {
                    note(o, sub, BadgeLedger.ENVIED, 0, now); sub.pet().addStress(2);
                    sub.life().personality().environment().record(o.getUUID(), "mockery", -3);
                    BadgeSocial.spread(level, o, Rumours.BULLY, o.getUUID(), 1);
                }
                tell(level, owner, "pet.mcyukkuri.badge.provoked", sub, false);
            }
            case PLEA -> {
                var human0 = s.player == null ? null : level.getPlayerByUUID(s.player);
                UUID asked = s.player == null ? human : s.player;
                if (result.equals("plea_adopted") || result.equals("plea_fed")) {
                    note(o, sub, BadgeLedger.PLEADED_OWNER, 1, now);
                    BadgeSocial.spread(level, o, Rumours.HUMAN_KIND, asked, 1);
                    if (result.equals("plea_adopted")) {
                        if (sub.life().personality().selfishness() >= 60) sub.pet().addStress(2); else sub.life().personality().experienced(Disposition.ATTACHMENT, 1);
                        o.showReaction("happy", "badge_thanks", 80);
                    } else o.showReaction("happy", "badge_thanks", 80);
                } else if (result.equals("player_declined")) {
                    BadgeSocial.spread(level, o, Rumours.WARY_HUMAN, asked, 1);
                    o.showReaction("teary", "plea_sad", 80); PetScenes.act(o, "droop", "teary", null, 60, human0);
                } else if (result.equals("plea_ignored")) {
                    o.showReaction("teary", "plea_sad", 80); PetScenes.act(o, "sulk", "teary", null, 60, null);
                    if (sub.life().personality().selfishness() >= 60) like(level, o, sub, -1);
                }
            }
            case PITY -> {
                if (result.equals("pity_comforted")) { like(level, o, sub, 1); sub.pet().addStress(-1); note(o, sub, BadgeLedger.HELPED, 1, now); }
                else { like(level, o, sub, -1); sub.pet().addStress(2); note(sub, o, BadgeLedger.MOCKED, -1, now); }
            }
            case ELDER_WARN -> {
                if (result.equals("elder_warned")) { BadgeSocial.spread(level, o, Rumours.WARY_HUMAN, human, 1); note(o, sub, BadgeLedger.WARY, -1, now); }
                else { BadgeSocial.spread(level, o, Rumours.HUMAN_KIND, human, 1); note(o, sub, BadgeLedger.ADMIRED, 1, now); }
            }
            case HOME_CLAIM -> {
                if (result.equals("home_defended")) {
                    note(o, sub, BadgeLedger.SNUBBED, -1, now); like(level, o, sub, -1);
                    if (owner != null) sub.pet().wildPoiseGain();
                    BadgeSocial.spread(level, o, Rumours.BULLY, o.getUUID(), 1);
                } else { sub.pet().addStress(3); like(level, sub, o, -1); note(sub, o, BadgeLedger.MOCKED, -1, now); }
            }
            case SNATCH -> {
                note(o, sub, BadgeLedger.ROBBED, 0, now); note(sub, o, BadgeLedger.ROBBED_BY, -2, now);
                sub.pet().addStress(3); like(level, sub, o, -2);
                BadgeSocial.spread(level, o, Rumours.THIEF, o.getUUID(), 2);
                if (owner != null) tell(level, owner, "pet.mcyukkuri.badge.snatched", sub, true);
            }
            default -> { }
        }
    }
}
