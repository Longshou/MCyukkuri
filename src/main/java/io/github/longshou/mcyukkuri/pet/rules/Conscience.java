package io.github.longshou.mcyukkuri.pet.rules;

import io.github.longshou.mcyukkuri.badge.BadgeConduct;
import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.presentation.FaceTier;
import io.github.longshou.mcyukkuri.life.BehaviorContinuity;
import io.github.longshou.mcyukkuri.perception.Cue;
import io.github.longshou.mcyukkuri.perception.Senses;
import io.github.longshou.mcyukkuri.pet.PetScenes;
import io.github.longshou.mcyukkuri.pet.rules.RulesMath.Choice;
import io.github.longshou.mcyukkuri.pet.social.BadgeSocial;
import io.github.longshou.mcyukkuri.social.ProtectivePuff;
import io.github.longshou.mcyukkuri.social.Temperament.Gate;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.status.PunishmentReaction;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

public final class Conscience {
    private final YukkuriEntity pet;
    private final HouseRule.Policy policy = new HouseRule.Policy();
    private HouseRule guilt;
    private UUID guiltOwner;
    private long at;
    private boolean caught;
    private long reactAfter;
    /** Ten seconds: a catch the pet cannot freeze for within this (a long meal, a flight) is lost, not replayed late. */
    private static final int CATCH_PATIENCE = 200;
    /** How far from its young a parent that is paying penance can see the owner treat them. */
    private static final int PARENT_RANGE = 16;
    /** Brief debounce before answering a rule toggle: lets rapid double-clicks settle on the final rule state without a bogus bow. */
    private static final int ANSWER_DEBOUNCE = 16;
    private long catchAt = -1;
    private Penance penance;
    private Penance.Verdict early;
    private boolean earlyBlow;
    private long answerHoldUntil;
    private LivingEntity answerFocus;
    private record Encounter(int owner, BlockPos ownerAt, BlockPos parentAt, BlockPos childAt) {}
    private final Map<BadgeConduct.Case, Encounter> encounters = new HashMap<>();

    final Elopement departure;
    public Conscience(YukkuriEntity pet) { this.pet = pet; departure = new Elopement(pet); }

    public void ownerIntervened(UUID actor) { departure.counter(actor); }
    public void sprayedBy(Player sprayer) { departure.sprayedBy(sprayer); }
    public boolean trystWith(YukkuriEntity peer) { return departure.trystWith(peer); }

    /** Per-pet independent house rules. */
    public int ruleMask() { return policy.mask(); }
    public boolean ruleEnabled(HouseRule rule) { return policy.enabled(rule); }
    public int ruleVersion() { return policy.version(); }
    public boolean told() { return policy.told(pet.pet().owner()); }
    public boolean binds(HouseRule rule) { return binds(rule, false); }
    private boolean binds(HouseRule rule, boolean permitted) {
        return pet.pet().isPet() && policy.binds(pet.pet().owner(), rule, permitted);
    }

    /** Reset this pet's rules back to default without any reaction (scenario setup only). */
    public void resetRules() {
        policy.reset();
        clearAnswerHold();
    }

    /**
     * Toggle one house rule for THIS pet alone. An awake pet hears it at once and answers by its own numbers; a sleeping pet
     * keeps "untold" until it wakes and meets the owner. Nearby owned housemates who see or hear the change through the shared
     * perception layer react as onlookers without their own rules changing.
     */
    public void toggle(Player owner, HouseRule rule) {
        if (!pet.pet().isPet() || !pet.pet().owned(owner)) return;
        boolean awake = !pet.life().isSleeping() && !pet.isPassenger();
        boolean on = policy.toggle(owner.getUUID(), rule, awake);
        int version = policy.version();
        boolean loosened = !on;
        GameplayLog.event(pet, "rules", "set owner={} rule={} on={} version={}", owner.getUUID(), rule.key(), on, version);
        if (awake) {
            GameplayLog.event(pet, "rules", "change noticed rule={} via=SAW version={}", rule.key(), version);
            holdRuleAnswer(owner, ANSWER_DEBOUNCE + 40);
            PetScenes.later(pet, ANSWER_DEBOUNCE, () -> {
                if (policy.version() == version) ruleChanged(owner, rule, loosened, version);
            });
        }
        int delay = ANSWER_DEBOUNCE + 10;
        for (var witness : Senses.witnesses((ServerLevel) owner.level(), owner.getEyePosition(), null, Cue.RULE_CHANGE)) {
            var peer = witness.body();
            if (peer == pet || !peer.pet().owned(owner)) continue;
            GameplayLog.event(peer, "rules", "change witnessed target={} rule={} on={} via={}", pet.getUUID(), rule.key(), on, witness.via());
            int wait = delay;
            PetScenes.later(peer, wait, () -> {
                if (pet.isAlive() && policy.version() == version) peer.pet().conscience().ruleWitnessed(owner, pet, rule, loosened, version);
            });
            delay += 10;
        }
    }

    public void tell(Player owner) {
        if (HumanSight.sees(owner, pet)) toldBy(owner);
    }

    /** An untold pet (for example one that was asleep when its rule changed) learns its current rules on meeting the owner. */
    void toldBy(Player owner) {
        if (!pet.pet().isPet() || !pet.pet().owned(owner) || told() || pet.life().isSleeping() || pet.isPassenger()) return;
        if (!policy.markTold(owner.getUUID())) return;
        PetScenes.act(pet, YukkuriAction.BOW, policy.hasRestrictions() ? "ashamed" : "shy_peek", "", 40, owner);
        GameplayLog.event(pet, "rules", "told version={} owner={}", policy.version(), owner.getUUID());
    }

    /** Movement/look hold while this pet is playing its rule-change or witness answer, so roaming does not waddle away mid-bow. */
    boolean answeringRule() {
        if (answerHoldUntil <= 0) return false;
        long now = pet.level().getGameTime();
        if (now >= answerHoldUntil || !pet.isAlive() || !pet.pet().calm() || catchPending() || penance != null
                || answerFocus == null || !answerFocus.isAlive() || answerFocus.level() != pet.level()) {
            clearAnswerHold();
            return false;
        }
        return true;
    }

    LivingEntity answerFocus() { return answerFocus; }

    void holdRuleAnswer(LivingEntity focus, int ticks) {
        if (focus == null || !focus.isAlive()) return;
        answerFocus = focus;
        answerHoldUntil = Math.max(answerHoldUntil, pet.level().getGameTime() + ticks);
    }

    void clearAnswerHold() {
        answerHoldUntil = 0;
        answerFocus = null;
    }

    /** How far from the owner's rule change a partner still counts as one this pet could turn to. */
    private static final int PARTNER_RANGE = 12;
    /** The pause between the two beats of one answer; how long a beat waits for a held face or the previous line; the retry step. */
    private static final int BEAT_GAP = 6, NEXT_PATIENCE = 300, RETRY_TICKS = 20;

    /** How much this pet wants what the rule withholds, 0-1: the same pressure that tempts it to break the rule. */
    private double urge(HouseRule rule) {
        var personality = pet.life().personality();
        return Math.clamp(switch (rule) {
            case NO_SUKKIRI -> pet.family().pentUp() / 100;
            case NO_SINGING -> pet.dailyLife().boredom() / 100.0;
            case NO_LEAVING_YARD -> Math.max(pet.dailyLife().boredom() / 100.0, personality.temperament().curious());
            case NO_EATING_UNASKED -> 1 - pet.life().nutrition() / 100;
            case NO_BULLYING -> personality.escalationScore() / 100.0;
            case NO_TUB_WHEN_HEALTHY -> Math.max(pet.dailyLife().boredom() / 100.0, pet.mind().unease() / 100.0);
            case NO_TUB_WHEN_HURT -> Math.max(pet.body().condition().painSeverity() / 3.0, 1 - pet.body().healthRatio());
            case GIVE_WAY -> 0;
        }, 0, 1);
    }

    /** Whether the rule changes anything for this pet: only a fertile adult at ease has a say about the breeding ban. */
    private boolean affectedBy(HouseRule rule) {
        if (rule != HouseRule.NO_SUKKIRI) return true;
        var family = pet.family();
        return pet.status().identity().canConceive() && family.adult() && !family.sterile() && !family.elder() && family.brood() == null
                && family.recovery() == 0 && !family.carryingOrLifting() && family.carrying().isEmpty();
    }

    /** One rule change as this pet answers it: the beats its numbers chose, whom it glances at, and the revision that makes it stale. */
    private record Answer(Player owner, HouseRule rule, boolean loosened, int version, List<RuleReaction.Beat> beats, YukkuriEntity partner) {
        String change() { return loosened ? "loosened" : "tightened"; }
    }

    /**
     * This pet's own rule was just changed while it was awake: it answers once by its own numbers ({@link RuleReaction}),
     * the same for every rule. A breeding rule change glances at the nearest compatible partner afterwards.
     */
    private void ruleChanged(Player owner, HouseRule rule, boolean loosened, int version) {
        if (!told() || !pet.isAlive() || !pet.pet().calm() || !affectedBy(rule) || !PetScenes.ready(pet, "rule_change", 200)) return;
        var temper = pet.life().personality().temperament();
        int obedience = pet.pet().obedience(), fear = pet.life().personality().fear(owner.getUUID());
        double urge = urge(rule);
        YukkuriEntity partner = null;
        if (rule == HouseRule.NO_SUKKIRI) {
            var family = pet.family();
            partner = pet.level().getEntitiesOfClass(YukkuriEntity.class, pet.getBoundingBox().inflate(PARTNER_RANGE),
                            y -> y.isAlive() && family.compatibleWith(y) && pet.hasLineOfSight(y))
                    .stream().min(Comparator.comparingDouble(pet::distanceToSqr)).orElse(null);
        }
        var beats = RuleReaction.choose(loosened, urge, obedience, fear, temper, rule == HouseRule.NO_SUKKIRI ? "permit_glad" : "rule_loosen");
        var answer = new Answer(owner, rule, loosened, version, beats, partner);
        GameplayLog.event(pet, "rules", "rule reaction rule={} change={} beats={} urge={} obedience={} fear={} boldness={} partner={}",
                rule.key(), answer.change(), beats.size(), urge, obedience, fear, pet.life().personality().boldness(),
                partner == null ? "-" : partner.getUUID());
        showRuleBeat(answer, 0, 0);
    }

    /**
     * One beat of this pet's answer to its own rule change. Waits if another face or speech gap is active (bounded by
     * {@link #NEXT_PATIENCE}), and stops at once if a newer revision supersedes it.
     */
    private void showRuleBeat(Answer answer, int index, int waited) {
        var owner = answer.owner();
        if (!pet.isAlive() || !pet.pet().calm() || !pet.pet().ownedBy(owner.getUUID()) || policy.version() != answer.version()) {
            clearAnswerHold();
            return;
        }
        var beat = answer.beats().get(index);
        if (!pet.quiet() && waited < NEXT_PATIENCE) {
            holdRuleAnswer(owner, RETRY_TICKS + 10);
            PetScenes.later(pet, RETRY_TICKS, () -> showRuleBeat(answer, index, waited + RETRY_TICKS));
            return;
        }
        String line = BadgeSocial.reactionLine(pet, beat.event(), owner);
        holdRuleAnswer(owner, beat.ticks() + 20);
        pet.getLookControl().setLookAt(owner, 30, 30);
        var lineDone = new boolean[1];
        boolean shown = pet.showWithDeferredLine(beat.action(), beat.ticks(), beat.face(), line,
                FaceTier.Tier.ofTicks(beat.ticks()), 2, said -> {
                    lineDone[0] = true;
                    GameplayLog.event(pet, "rules", "rule reaction speech beat={} event={} tick={} holdEnd={} spoken={}",
                            beat.name(), line, said.tick() < 0 ? "none" : said.tick(), said.holdEnd(), said.spoken());
                });
        if (!shown) {
            if (waited < NEXT_PATIENCE) {
                holdRuleAnswer(owner, RETRY_TICKS + 10);
                PetScenes.later(pet, RETRY_TICKS, () -> showRuleBeat(answer, index, waited + RETRY_TICKS));
            } else {
                clearAnswerHold();
                GameplayLog.event(pet, "rules", "rule reaction rule={} change={} beat={} face={} line={} shown=false waited={}",
                        answer.rule().key(), answer.change(), beat.name(), beat.face(), beat.event(), waited);
            }
            return;
        }
        pet.pet().expectBeat("rule_" + beat.name(), beat.face(), beat.event(), beat.name());
        GameplayLog.event(pet, "rules", "rule reaction rule={} change={} beat={} face={} line={} shown=true waited={}",
                answer.rule().key(), answer.change(), beat.name(), beat.face(), beat.event(), waited);
        var glance = answer.partner();
        if (beat.toward() && glance != null) {
            PetScenes.later(pet, beat.ticks(), () -> { if (pet.isAlive() && glance.isAlive()) pet.pet().glanceAt(glance, 40); });
        }
        if (index + 1 < answer.beats().size()) nextRuleBeat(answer, index + 1, lineDone, beat.ticks() + BEAT_GAP, 0);
    }

    /** The next beat starts once the one before it has said its line and the shared speech clock is free, so its own line is heard. */
    private void nextRuleBeat(Answer answer, int index, boolean[] lineDone, int wait, int waited) {
        holdRuleAnswer(answer.owner(), wait + RETRY_TICKS);
        PetScenes.later(pet, wait, () -> {
            if (policy.version() != answer.version()) { clearAnswerHold(); return; }
            if (!(lineDone[0] && pet.quiet()) && waited + wait < NEXT_PATIENCE) nextRuleBeat(answer, index, lineDone, RETRY_TICKS, waited + wait);
            else showRuleBeat(answer, index, 0);
        });
    }

    /**
     * Another owned pet saw or heard the owner change a housemate's rule. This pet's own rules stay unchanged; it reacts as an
     * onlooker (envious if left forbidden, wary/sympathetic on tightening, or a silent glance if indifferent).
     */
    private void ruleWitnessed(Player owner, YukkuriEntity target, HouseRule rule, boolean loosened, int targetVersion) {
        if (!pet.isAlive() || !pet.pet().calm() || !pet.pet().owned(owner) || !target.isAlive()
                || target.pet().conscience().ruleVersion() != targetVersion || !affectedBy(rule)) return;
        var temper = pet.life().personality().temperament();
        int obedience = pet.pet().obedience(), fear = pet.life().personality().fear(owner.getUUID());
        var beats = RuleReaction.witness(loosened, policy.enabled(rule), urge(rule), obedience, fear, temper);
        String change = loosened ? "loosened" : "tightened";
        if (beats.isEmpty()) {
            pet.pet().glanceAt(target, 40);
            GameplayLog.event(pet, "rules", "rule witness rule={} change={} beat=glance face=- line=- shown=true target={}",
                    rule.key(), change, target.getUUID());
            return;
        }
        if (!PetScenes.ready(pet, "rule_witness", 200)) return;
        showWitnessBeat(owner, target, rule, change, targetVersion, beats.get(0), 0);
    }

    private void showWitnessBeat(Player owner, YukkuriEntity target, HouseRule rule, String change, int targetVersion,
                                 RuleReaction.Beat beat, int waited) {
        if (!pet.isAlive() || !pet.pet().calm() || !pet.pet().ownedBy(owner.getUUID()) || !target.isAlive()
                || target.pet().conscience().ruleVersion() != targetVersion) {
            clearAnswerHold();
            return;
        }
        if (!pet.quiet() && waited < NEXT_PATIENCE) {
            holdRuleAnswer(beat.toward() ? target : owner, RETRY_TICKS + 10);
            PetScenes.later(pet, RETRY_TICKS, () -> showWitnessBeat(owner, target, rule, change, targetVersion, beat, waited + RETRY_TICKS));
            return;
        }
        LivingEntity focus = beat.toward() ? target : owner;
        String line = BadgeSocial.reactionLine(pet, beat.event(), owner);
        holdRuleAnswer(focus, beat.ticks() + 15);
        pet.getLookControl().setLookAt(focus, 30, 30);
        boolean shown = pet.showWithDeferredLine(beat.action(), beat.ticks(), beat.face(), line,
                FaceTier.Tier.ofTicks(beat.ticks()), 2, said -> GameplayLog.event(pet, "rules",
                        "rule witness speech beat={} event={} tick={} holdEnd={} spoken={} target={}",
                        beat.name(), line, said.tick() < 0 ? "none" : said.tick(), said.holdEnd(), said.spoken(), target.getUUID()));
        if (!shown) {
            if (waited < NEXT_PATIENCE) {
                holdRuleAnswer(focus, RETRY_TICKS + 10);
                PetScenes.later(pet, RETRY_TICKS, () -> showWitnessBeat(owner, target, rule, change, targetVersion, beat, waited + RETRY_TICKS));
            } else {
                clearAnswerHold();
                GameplayLog.event(pet, "rules", "rule witness rule={} change={} beat={} face={} line={} shown=false target={}",
                        rule.key(), change, beat.name(), beat.face(), beat.event(), target.getUUID());
            }
            return;
        }
        pet.pet().expectBeat("rule_" + beat.name(), beat.face(), beat.event(), beat.name());
        GameplayLog.event(pet, "rules", "rule witness rule={} change={} beat={} face={} line={} shown=true target={}",
                rule.key(), change, beat.name(), beat.face(), beat.event(), target.getUUID());
        if (beat.toward()) {
            PetScenes.later(pet, beat.ticks() / 2, () -> { if (pet.isAlive() && owner.isAlive()) pet.getLookControl().setLookAt(owner, 30, 30); });
        }
    }

    /**
     * Expressive restraint when this pet chooses {@link Choice#HOLD} on a tempted rule (singing, leaving yard, bullying, tub).
     * Cooldown-gated so repeated temptations never spam speech or faces.
     */
    public void showHold(HouseRule rule, double pressure, LivingEntity look) {
        if (!pet.isAlive() || !pet.pet().calm() || !PetScenes.ready(pet, "rule_hold", 160)) return;
        var owner = pet.pet().presentOwner();
        int fear = owner == null ? 0 : pet.life().personality().fear(owner.getUUID());
        var beat = RuleReaction.hold(rule, pressure, pet.pet().obedience(), fear, pet.life().personality().temperament());
        LivingEntity focus = look != null && look.isAlive() ? look : owner;
        if (focus != null) pet.getLookControl().setLookAt(focus, 30, 30);
        String line = BadgeSocial.reactionLine(pet, beat.event(), owner);
        boolean shown = pet.showWithDeferredLine(beat.action(), beat.ticks(), beat.face(), line, FaceTier.Tier.SHORT, 2, null);
        GameplayLog.event(pet, "rules", "hold reaction rule={} beat={} face={} line={} shown={}",
                rule.key(), beat.name(), beat.face(), beat.event(), shown);
    }

    public Choice tempted(HouseRule rule, double pressure, double restraint) {
        return tempted(rule, pressure, restraint, false);
    }

    public Choice tempted(HouseRule rule, double pressure, double restraint, boolean permitted) {
        if (!binds(rule, permitted)) return Choice.ALLOW;
        var temper = pet.life().personality().temperament();
        double hold = RulesMath.hold(temper.patient(), pet.pet().obedience(), pressure);
        boolean watched = HumanSight.watching(pet);
        var choice = RulesMath.choose(pet.getRandom().nextDouble(), hold, temper.clever(), watched, restraint);
        GameplayLog.limited(pet, "rules", rule.key(), 100, "tempted rule={} pressure={} hold={} choice={} watched={}",
                rule.key(), pressure, hold * restraint, choice, watched);
        if (choice == Choice.HOLD) GameplayLog.event(pet, "rules", "held rule={}", rule.key());
        return choice;
    }

    public boolean breach(HouseRule rule) {
        if (!binds(rule)) return false;
        guilt = rule;
        at = pet.level().getGameTime();
        guiltOwner = pet.pet().owner();
        caught = false;
        catchAt = -1;
        early = null;
        reactAfter = at;
        boolean watched = HumanSight.watching(pet);
        GameplayLog.event(pet, "rules", "breach rule={} watched={} owner={}", rule.key(), watched, guiltOwner);
        if (watched) caught("sight");
        return true;
    }

    public OptionalLong breachAge(UUID owner) {
        return guilt != null && owner.equals(guiltOwner) ? OptionalLong.of(pet.level().getGameTime() - at) : OptionalLong.empty();
    }

    public void witnessedSong() {
        if (guilt == HouseRule.NO_SINGING && !caught && pet.pet().ownedBy(guiltOwner) && HumanSight.watching(pet)) caught("sight");
    }

    void witnessedYard(long crossing) {
        if (guilt == HouseRule.NO_LEAVING_YARD && at == crossing && !caught && pet.pet().ownedBy(guiltOwner)) caught("sight");
    }

    boolean undiscovered() {
        return guilt != null && !caught && pet.pet().ownedBy(guiltOwner) && pet.level().getGameTime() - at < 24000;
    }

    LivingEntity encounter() {
        if (!undiscovered() || pet.level().getGameTime() < reactAfter || !pet.pet().canWelcomeOwner()) return null;
        var owner = pet.pet().presentOwner();
        return owner != null && pet.distanceToSqr(owner) <= 36 && HumanSight.sees(owner, pet) && pet.hasLineOfSight(owner) ? owner : null;
    }

    void reacted() { reactAfter = pet.level().getGameTime() + 1200; }

    boolean responseDue() { return pet.level().getGameTime() >= reactAfter; }

    private Encounter encounter(LivingEntity owner, YukkuriEntity child) {
        return new Encounter(owner.getId(), owner.blockPosition(), pet.blockPosition(), child == null ? null : child.blockPosition());
    }
    boolean mayApproach(BadgeConduct.Case brood, LivingEntity owner, YukkuriEntity child) {
        return !encounter(owner, child).equals(encounters.get(brood));
    }
    void approachEnded(BadgeConduct.Case brood, LivingEntity owner, YukkuriEntity child) {
        encounters.put(brood, encounter(owner, child));
    }
    void retainEncounters(List<BadgeConduct.Case> open) { encounters.keySet().retainAll(open); }

    boolean confesses() { return pet.pet().obedience() >= 60 && pet.life().personality().temperament().is(Gate.CLEVER); }

    public void react() {
        var owner = encounter();
        if (owner == null || confesses()) return;
        reacted();
        if (pet.pet().obedience() < 40 && pet.life().personality().temperament().is(Gate.BOLD)) {
            var away = pet.position().scale(2).subtract(owner.position());
            pet.getLookControl().setLookAt(away.x, pet.getEyeY(), away.z);
            PetScenes.act(pet, YukkuriAction.TURN, "down_angry", "rule_guilty_shy", 50);
        } else {
            PetScenes.act(pet, YukkuriAction.TREMBLE, "afraid", "rule_guilty_shy", 50, owner);
        }
    }

    public Component memory(Player reader) {
        if (!pet.pet().owned(reader) || !pet.pet().isPet()) return Component.empty();
        var line = Component.literal("\n").append(Component.translatable(told() ? "rules.mcyukkuri.told" : "rules.mcyukkuri.untold"));
        if (told()) for (var rule : HouseRule.values()) {
            if (policy.enabled(rule)) line.append(" · ").append(Component.translatable("rules.mcyukkuri." + rule.key()));
        }
        if (caught && pet.pet().ownedBy(guiltOwner)) line.append("\n").append(Component.translatable("rules.mcyukkuri.memory",
                at / 24000 + 1, Component.translatable("rules.mcyukkuri." + guilt.key())));
        return line;
    }

    /**
     * The one entry of every house rule: the owner saw or heard the breach, or the pet owned up to it. A catch queues the penance
     * (the goal runs it as soon as the pet is free); a confession is the goal's own walk and starts its penance on arrival.
     */
    void caught(String evidence) {
        caught = true;
        clearAnswerHold();
        pet.pet().caught(guiltOwner);
        pet.pet().waiveWelcome();
        if (!evidence.equals("confession")) {
            catchAt = pet.level().getGameTime();
            early = null;
            // The face is the pet's from this very tick (the song's or the chewing's face is cut); the goal takes over once the pet is free.
            pet.holdReaction("horrified", "", CATCH_PATIENCE);
        }
        GameplayLog.event(pet, "rules", "caught rule={} evidence={} owner={}", guilt.key(), evidence, guiltOwner);
    }

    /** A catch waiting for the pet to be free to freeze (it does not wait out a long meal). */
    public boolean catchPending() {
        return catchAt >= 0 && pet.level().getGameTime() - catchAt < CATCH_PATIENCE && pet.pet().ownedBy(guiltOwner);
    }

    /**
     * A catch that was never answered (the pet stayed busy or the owner changed) is dropped, not replayed late: the pet shows
     * its ordinary life again once the held startle face runs out, and the memory page keeps "caught". Logs why, once.
     */
    void expireCatch() {
        if (catchAt < 0 || catchPending()) return;
        boolean owned = pet.pet().ownedBy(guiltOwner);
        GameplayLog.event(pet, "rules", "catch expired reason={} waited={} rule={}", owned ? "patience" : "owner_changed",
                pet.level().getGameTime() - catchAt, guilt.key());
        catchAt = -1;
        early = null;
        pet.cancelReaction();
    }

    LivingEntity guiltyOwner() { return pet.pet().presentOwner(); }

    String guiltRule() { return guilt.key(); }

    void startedPenance(Penance running) {
        clearAnswerHold();
        catchAt = -1;
        penance = running;
        // An owner who answered before the pet was free to freeze (it was still chewing) is answered once it has.
        if (early != null) running.judged(early, earlyBlow);
        early = null;
    }

    void endedPenance() {
        if (penance != null) pet.pet().waiveWelcome();
        penance = null;
    }

    /** The performance under way (also while the goal waits for the owner's hand to let go), or null. */
    Penance running() { return penance; }

    /**
     * The owner's stroke, scold or blow reaches a pet that is paying penance (or about to): true when the performance took it as its
     * verdict, so the generic reaction (the stranger-wariness retreat, the pleased rub face) stays silent.
     */
    public boolean judged(LivingEntity actor, Penance.Verdict verdict, boolean blow) {
        if (!taking(actor)) return false;
        if (penance != null) return penance.judged(verdict, blow);
        if (early == null) {
            early = verdict;
            earlyBlow = blow;
        }
        return true;
    }

    /**
     * The owner's stroke, scold or blow on this young reaches each parent that is paying penance to that owner and sees both the
     * act and the actor: the child's treatment is the parent's verdict. Once per act, at the one place each act passes (the care
     * actions, the scold, and {@link PunishmentReaction#recordHuman} for every strike); outside a penance the parent is only a witness.
     */
    public void treatedBy(LivingEntity actor, Penance.Verdict verdict, boolean blow) {
        if (!pet.isAlive() || pet.body().dying()) return;
        for (var parent : pet.level().getEntitiesOfClass(YukkuriEntity.class, pet.getBoundingBox().inflate(PARENT_RANGE),
                e -> e != pet && e.pet().conscience().payingTo(actor) && ProtectivePuff.parentOf(pet, e) && PunishmentReaction.sees(e, pet, actor)))
            parent.pet().conscience().judgedViaChild(pet, verdict, blow);
    }

    private void judgedViaChild(YukkuriEntity young, Penance.Verdict verdict, boolean blow) {
        if (penance == null || penance.finished()) return;
        boolean taken = penance.judgedViaChild(young, verdict, blow);
        GameplayLog.event(pet, "rules", "penance child treated child={} verdict={} blow={} taken={}", young.getUUID(), verdict, blow, taken);
    }

    /** A penance to this owner is running (a catch still waiting for the pet to freeze is not one). */
    public boolean payingTo(LivingEntity actor) {
        return penance != null && !penance.finished() && pet.pet().ownedBy(actor.getUUID());
    }

    /**
     * The one gate of the owner's scold and blow: the owner's hand on a pet that is caught or paying penance is its punishment,
     * so the generic answers (the stranger retreat, the flight or angry puff of a struck pet) stay silent.
     */
    public boolean taking(LivingEntity actor) {
        return pet.pet().ownedBy(actor.getUUID()) && (penance != null && !penance.finished() || catchPending());
    }

    /**
     * Danger that ends a short duty. Between the owner's answer and the end of a penance the owner's own hand (the stroke's
     * contact, a scold's flinch, a blow's panic and hurt) is no emergency: only the world's hazards count.
     */
    public boolean endangered() {
        if (penance != null && penance.ownerHand()) return BehaviorContinuity.hazard(pet, pet.pet().owner());
        return BehaviorContinuity.emergency(pet) || pet.hurtTime > 0;
    }

    public void broodDiscovered(UUID owner) {
        if (guilt == HouseRule.NO_SUKKIRI && owner.equals(guiltOwner)) caught = true;
        pet.pet().caught(owner);
    }

    public void forgetOwner() {
        departure.forgetOwner();
        policy.reset();
        clearAnswerHold();
        guilt = null;
        guiltOwner = null;
        catchAt = -1;
        early = null;
    }

    public CompoundTag save() {
        var tag = new CompoundTag();
        departure.save(tag);
        tag.putBoolean("HasRules", true);
        tag.putInt("RulesMask", policy.mask());
        tag.putInt("RuleVersion", policy.version());
        if (policy.owner() != null) tag.putUUID("RulesOwner", policy.owner());
        var instruction = policy.instruction();
        if (instruction.owner() != null) {
            tag.putUUID("ToldOwner", instruction.owner());
            tag.putInt("Version", instruction.version());
        }
        if (guilt != null) {
            tag.putString("Rule", guilt.key());
            tag.putUUID("Owner", guiltOwner);
            tag.putLong("At", at);
            tag.putBoolean("Caught", caught);
            tag.putLong("ReactAfter", reactAfter);
        }
        return tag;
    }

    public void load(CompoundTag tag) {
        forgetOwner();
        departure.load(tag);
        var instruction = tag.hasUUID("ToldOwner")
                ? new HouseRule.Instruction(tag.getUUID("ToldOwner"), tag.getInt("Version"))
                : new HouseRule.Instruction(null, 0);
        policy.restore(tag.hasUUID("RulesOwner") ? tag.getUUID("RulesOwner") : null,
                tag.getInt("RuleVersion"), tag.getInt("RulesMask"), tag.getBoolean("HasRules"), instruction);
        if (tag.hasUUID("Owner")) {
            for (var rule : HouseRule.values()) if (rule.key().equals(tag.getString("Rule"))) guilt = rule;
            guiltOwner = tag.getUUID("Owner");
            at = tag.getLong("At");
            caught = tag.getBoolean("Caught");
            reactAfter = tag.getLong("ReactAfter");
        }
    }
}
