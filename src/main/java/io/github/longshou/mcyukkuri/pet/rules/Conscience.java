package io.github.longshou.mcyukkuri.pet.rules;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.pet.PetScenes;
import io.github.longshou.mcyukkuri.pet.rules.RulesMath.Choice;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.OptionalLong;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;

public final class Conscience {
    private final YukkuriEntity pet;
    private HouseRule.Instruction instruction = new HouseRule.Instruction(null, 0);
    private HouseRule guilt;
    private UUID guiltOwner;
    private long at;
    private boolean caught;

    public Conscience(YukkuriEntity pet) { this.pet = pet; }
    public HouseRules household() { return HouseRules.get((ServerLevel) pet.level()); }
    public boolean told() { return instruction.known(pet.pet().owner(), household().version(pet.pet().owner())); }
    public boolean binds(HouseRule rule) {
        return pet.pet().isPet() && household().enabled(pet.pet().owner(), rule) && told();
    }
    public void tell(Player owner) {
        if (!pet.pet().owned(owner) || told() || pet.life().isSleeping() || pet.isPassenger() || !HumanSight.sees(owner, pet)) return;
        instruction = new HouseRule.Instruction(owner.getUUID(), household().version(owner.getUUID()));
        PetScenes.act(pet, "bow", "happy", null, 40, owner);
        GameplayLog.event(pet, "rules", "told version={} owner={}", instruction.version(), owner.getUUID());
    }

    public Choice tempted(HouseRule rule, double pressure, double restraint) {
        if (!binds(rule)) return Choice.ALLOW;
        var temper = pet.life().personality().temperament();
        double hold = RulesMath.hold(temper.patient(), pet.pet().obedience(), pressure);
        boolean watched = HumanSight.watching(pet);
        var choice = RulesMath.choose(pet.getRandom().nextDouble(), hold, temper.clever(), watched, restraint);
        GameplayLog.limited(pet, "rules", rule.key(), 100, "tempted rule={} pressure={} hold={} choice={} watched={}",
                rule.key(), pressure, hold * restraint, choice, watched);
        if (choice == Choice.HOLD) GameplayLog.event(pet, "rules", "held rule={}", rule.key());
        return choice;
    }

    public void breach(HouseRule rule) {
        if (!binds(rule)) return;
        guilt = rule;
        at = pet.level().getGameTime();
        guiltOwner = pet.pet().owner();
        caught = false;
        boolean watched = HumanSight.watching(pet);
        GameplayLog.event(pet, "rules", "breach rule={} watched={} owner={}", rule.key(), watched, guiltOwner);
        if (watched) caught("sight");
    }

    public OptionalLong breachAge(UUID owner) {
        return guilt != null && owner.equals(guiltOwner) ? OptionalLong.of(pet.level().getGameTime() - at) : OptionalLong.empty();
    }

    void caught(String evidence) {
        caught = true;
        pet.pet().caught(guiltOwner);
        PetScenes.act(pet, "brace", "horrified", pet.life().personality().temperament().clever() >= .5 ? "exam_unsure" : "plea_sad", 40, pet.pet().presentOwner());
        GameplayLog.event(pet, "rules", "caught rule={} evidence={} owner={}", guilt.key(), evidence, guiltOwner);
    }

    public void forgetOwner() {
        instruction = new HouseRule.Instruction(null, 0);
        guilt = null;
        guiltOwner = null;
    }

    public CompoundTag save() {
        var tag = new CompoundTag();
        if (instruction.owner() != null) {
            tag.putUUID("ToldOwner", instruction.owner());
            tag.putInt("Version", instruction.version());
        }
        if (guilt != null) {
            tag.putString("Rule", guilt.key());
            tag.putUUID("Owner", guiltOwner);
            tag.putLong("At", at);
            tag.putBoolean("Caught", caught);
        }
        return tag;
    }

    public void load(CompoundTag tag) {
        forgetOwner();
        if (tag.hasUUID("ToldOwner")) instruction = new HouseRule.Instruction(tag.getUUID("ToldOwner"), tag.getInt("Version"));
        if (tag.hasUUID("Owner")) {
            for (var rule : HouseRule.values()) if (rule.key().equals(tag.getString("Rule"))) guilt = rule;
            guiltOwner = tag.getUUID("Owner");
            at = tag.getLong("At");
            caught = tag.getBoolean("Caught");
        }
    }
}
