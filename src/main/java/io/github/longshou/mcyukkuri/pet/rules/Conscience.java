package io.github.longshou.mcyukkuri.pet.rules;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.social.Temperament.Gate;
import io.github.longshou.mcyukkuri.pet.PetScenes;
import io.github.longshou.mcyukkuri.pet.rules.RulesMath.Choice;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.badge.BadgeConduct;
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
        if (!pet.pet().isPet() || !pet.pet().owned(owner) || told() || pet.life().isSleeping() || pet.isPassenger() || !HumanSight.sees(owner, pet)) return;
        instruction = new HouseRule.Instruction(owner.getUUID(), household().version(owner.getUUID()));
        PetScenes.act(pet, YukkuriAction.BOW, "happy", "", 40, owner);
        GameplayLog.event(pet, "rules", "told version={} owner={}", instruction.version(), owner.getUUID());
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

    void witnessedYard() {
        if (guilt == HouseRule.NO_LEAVING_YARD && !caught && pet.pet().ownedBy(guiltOwner)) caught("sight");
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
            PetScenes.act(pet, YukkuriAction.TURN, "pursed", "exam_unsure", 30);
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

    void caught(String evidence) {
        caught = true;
        pet.pet().caught(guiltOwner);
        if (evidence.equals("confession")) PetScenes.act(pet, YukkuriAction.BOW, "teary", "exam_sorry", 40, pet.pet().presentOwner());
        else PetScenes.act(pet, YukkuriAction.BRACE, "horrified", pet.life().personality().temperament().clever() >= .5 ? "exam_unsure" : "plea_sad", 40);
        GameplayLog.event(pet, "rules", "caught rule={} evidence={} owner={}", guilt.key(), evidence, guiltOwner);
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
