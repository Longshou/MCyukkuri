package io.github.longshou.mcyukkuri.pet.rules;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.entity.presentation.FaceTier;
import io.github.longshou.mcyukkuri.social.ProtectivePuff;
import io.github.longshou.mcyukkuri.social.Temperament.Gate;
import io.github.longshou.mcyukkuri.pet.PetScenes;
import io.github.longshou.mcyukkuri.pet.social.BadgeSocial;
import io.github.longshou.mcyukkuri.pet.rules.RulesMath.Choice;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.status.PunishmentReaction;
import io.github.longshou.mcyukkuri.badge.BadgeConduct;
import io.github.longshou.mcyukkuri.life.BehaviorContinuity;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

public final class Conscience {
    private final YukkuriEntity pet;
    private HouseRule.Instruction instruction = new HouseRule.Instruction(null, 0);
    private HouseRule guilt;
    private UUID guiltOwner;
    private long at;
    private boolean caught;
    private long reactAfter;
    /** Ten seconds: a catch the pet cannot freeze for within this (a long meal, a flight) is lost, not replayed late. */
    private static final int CATCH_PATIENCE = 200;
    /** How far from its young a parent that is paying penance can see the owner treat them. */
    private static final int PARENT_RANGE = 16;
    private long catchAt = -1;
    private Penance penance;
    private Penance.Verdict early;
    private boolean earlyBlow;
    private record Encounter(int owner, BlockPos ownerAt, BlockPos parentAt, BlockPos childAt) {}
    private final Map<BadgeConduct.Case, Encounter> encounters = new HashMap<>();

    final Elopement departure;
    public Conscience(YukkuriEntity pet) { this.pet = pet; departure = new Elopement(pet); }

    public void ownerIntervened(UUID actor) { departure.counter(actor); }
    public void sprayedBy(Player sprayer) { departure.sprayedBy(sprayer); }
    public boolean trystWith(YukkuriEntity peer) { return departure.trystWith(peer); }
    public HouseRules household() { return HouseRules.get((ServerLevel) pet.level()); }
    public boolean told() { return instruction.known(pet.pet().owner(), household().version(pet.pet().owner())); }
    public boolean binds(HouseRule rule) { return binds(rule, false); }
    private boolean binds(HouseRule rule, boolean permitted) {
        if (!pet.pet().isPet()) return false;
        UUID owner = pet.pet().owner();
        var rules = household();
        return instruction.forbids(owner, rules.version(owner), rules.enabled(owner, rule), permitted);
    }
    public void tell(Player owner) {
        if (HumanSight.sees(owner, pet)) toldBy(owner);
    }

    /** The pet saw or heard the owner state or change a rule (the shared perception layer decided who): it bows once per revision. */
    void toldBy(Player owner) {
        if (!pet.pet().isPet() || !pet.pet().owned(owner) || told() || pet.life().isSleeping() || pet.isPassenger()) return;
        instruction = new HouseRule.Instruction(owner.getUUID(), household().version(owner.getUUID()));
        PetScenes.act(pet, YukkuriAction.BOW, "happy", "", 40, owner);
        GameplayLog.event(pet, "rules", "told version={} owner={}", instruction.version(), owner.getUUID());
    }

    /** How far from the owner's rule change a partner still counts as one this pet could turn to. */
    private static final int PARTNER_RANGE = 12;
    /** The gap between the two beats of one answer: the first one's hold, plus the pause the settling needs (see {@link #showRuleBeat}). */
    private static final int BEAT_GAP = 6;

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

    /**
     * The owner changed a house rule and this pet saw or heard it ({@code Senses}) and has been told: after the bow it answers once,
     * by its own numbers ({@link RuleReaction}), the same for every rule. Tightening and loosening differ; a breeding rule change
     * ends in a glance at the nearest partner. Calm, unoccupied pets the rule concerns answer; the others only bow.
     */
    void ruleChanged(Player owner, HouseRule rule, boolean loosened) {
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
        GameplayLog.event(pet, "rules", "rule reaction rule={} change={} beats={} urge={} obedience={} fear={} boldness={} partner={}",
                rule.key(), loosened ? "loosened" : "tightened", beats.size(), urge, obedience, fear, pet.life().personality().boldness(),
                partner == null ? "-" : partner.getUUID());
        showRuleBeat(owner, rule, loosened, beats, 0, partner);
    }

    /** One beat of the answer; the next one follows when this one's hold ends, if the pet is still alive and calm. */
    private void showRuleBeat(Player owner, HouseRule rule, boolean loosened, List<RuleReaction.Beat> beats, int index, YukkuriEntity partner) {
        var beat = beats.get(index);
        // The pet may still be inside the speech gap of its own heat line or of the previous beat, so the line waits for the shared
        // speech clock's next gap while the face's hold still runs, instead of being lost to a refused cue.
        String line = BadgeSocial.reactionLine(pet, beat.event(), owner);
        pet.getLookControl().setLookAt(owner, 30, 30);
        boolean shown = pet.showWithDeferredLine(beat.action(), beat.ticks(), beat.face(), line,
                FaceTier.Tier.ofTicks(beat.ticks()), 2, said -> GameplayLog.event(pet, "rules",
                        "rule reaction speech beat={} event={} tick={} holdEnd={} spoken={}",
                        beat.name(), line, said.tick() < 0 ? "none" : said.tick(), said.holdEnd(), said.spoken()));
        if (shown) pet.pet().expectBeat("rule_" + beat.name(), beat.face(), beat.event(), beat.name());
        GameplayLog.event(pet, "rules", "rule reaction rule={} change={} beat={} face={} line={} shown={}",
                rule.key(), loosened ? "loosened" : "tightened", beat.name(), beat.face(), beat.event(), shown);
        if (!shown) return;
        var glance = partner;
        if (beat.toward() && glance != null)
            PetScenes.later(pet, beat.ticks(), () -> { if (pet.isAlive() && glance.isAlive()) pet.pet().glanceAt(glance, 40); });
        if (index + 1 < beats.size())
            PetScenes.later(pet, beat.ticks() + BEAT_GAP, () -> {
                if (pet.isAlive() && pet.pet().calm() && pet.pet().ownedBy(owner.getUUID())) showRuleBeat(owner, rule, loosened, beats, index + 1, partner);
            });
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
            PetScenes.act(pet, YukkuriAction.TURN, "pursed", "caught_guilty", 30);
        } else PetScenes.act(pet, YukkuriAction.TREMBLE, "afraid", "", 30);
    }

    public Component memory(Player reader) {
        if (!pet.pet().owned(reader) || !pet.pet().isPet()) return Component.empty();
        var line = Component.literal("\n").append(Component.translatable(told() ? "rules.mcyukkuri.told" : "rules.mcyukkuri.untold"));
        if (told()) for (var rule : HouseRule.values()) {
            if (household().enabled(reader.getUUID(), rule)) line.append(" · ").append(Component.translatable("rules.mcyukkuri." + rule.key()));
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
        instruction = new HouseRule.Instruction(null, 0);
        guilt = null;
        guiltOwner = null;
        catchAt = -1;
        early = null;
    }

    public CompoundTag save() {
        var tag = new CompoundTag();
        departure.save(tag);
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
        if (tag.hasUUID("ToldOwner")) instruction = new HouseRule.Instruction(tag.getUUID("ToldOwner"), tag.getInt("Version"));
        if (tag.hasUUID("Owner")) {
            for (var rule : HouseRule.values()) if (rule.key().equals(tag.getString("Rule"))) guilt = rule;
            guiltOwner = tag.getUUID("Owner");
            at = tag.getLong("At");
            caught = tag.getBoolean("Caught");
            reactAfter = tag.getLong("ReactAfter");
        }
    }
}
