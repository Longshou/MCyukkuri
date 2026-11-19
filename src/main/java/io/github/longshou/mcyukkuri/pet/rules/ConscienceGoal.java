package io.github.longshou.mcyukkuri.pet.rules;

import io.github.longshou.mcyukkuri.badge.BadgeConduct;
import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.family.ParentCarry;
import io.github.longshou.mcyukkuri.life.BehaviorContinuity;
import io.github.longshou.mcyukkuri.life.Duty;
import io.github.longshou.mcyukkuri.life.GlassSight;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.pet.PetScenes;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.status.Reactions;
import java.util.EnumSet;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

public final class ConscienceGoal extends Goal {
    private final YukkuriEntity pet;
    private LivingEntity owner;
    private BadgeConduct.Case brood;
    private ArcStyle style;
    private YukkuriEntity child;
    private Vec3 stand;

    public ConscienceGoal(YukkuriEntity pet) {
        this.pet = pet;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override public boolean canUse() {
        var conscience = pet.pet().conscience();
        if (!pet.duty().mayTake(Duty.Kind.RULES)) return false;
        if (conscience.responseDue() && !pet.pet().discovery().open().isEmpty() && pet.pet().isPet() && freeForBrood()) {
            owner = pet.pet().presentOwner();
            if (owner != null && HumanSight.sees(owner, pet)) {
                for (var entry : pet.pet().discovery().open())
                    if (entry.verdict() == null && wantsResponse(entry)) { brood = entry; return true; }
            }
        }
        if (!conscience.undiscovered() || !conscience.confesses()) return false;
        owner = conscience.encounter();
        return owner != null;
    }

    private boolean freeForBrood() {
        return !BehaviorContinuity.emergency(pet) && pet.hurtTime == 0 && !pet.isPassenger() && !pet.isLeashed()
                && !pet.pet().busy() && !pet.life().protectsStep() && !pet.life().hasUrgentNeed() && !pet.life().isSleeping()
                && !pet.house().active() && !pet.family().carry().carrying() && pet.family().yieldsToComfort();
    }

    private boolean wantsResponse(BadgeConduct.Case entry) {
        var level = (ServerLevel) pet.level();
        var family = FamilyData.get(level).brood(entry.brood);
        boolean mother = pet.getUUID().equals(family.mother);
        style = entry.parents.get(mother ? 0 : 1).style();
        if (style == null) return false;
        child = null;
        boolean born = false;
        for (var member : family.children) {
            if (!member.born) continue;
            born = true;
            if (!(level.getEntity(member.id) instanceof YukkuriEntity young) || !young.isAlive()) {
                if (style == ArcStyle.HIDE) return false;
                continue;
            }
            if (!young.childhood().shouldStayWithParents()) continue;
            if (style == ArcStyle.HIDE && (ParentCarry.needsCarry(young) || young.isPassenger()
                    || !young.childhood().firstMealDone() || young.life().nutrition() < 30
                    || young.isPanicking() || young.life().isSleeping() || pet.distanceToSqr(young) > 36)) return false;
            if (child == null || pet.distanceToSqr(young) < pet.distanceToSqr(child)) child = young;
        }
        if (!born) return mother && pet.pet().discovery().leavesNest(pet, family);
        return switch (style) {
            case CONFESS -> true;
            case DEFIANT -> pet.distanceToSqr(owner) <= 16;
            case LIE -> child != null && pet.distanceToSqr(child) <= 16;
            case NAIVE -> child != null && pet.distanceToSqr(owner) <= 144;
            case HIDE -> child != null;
        };
    }

    @Override public void start() {
        pet.pet().conscience().reacted();
        pet.duty().take(Duty.Kind.RULES, 400, 600, brood == null ? "confess" : style.name());
        if (brood == null) return;
        switch (style) {
            case HIDE -> {
                stand = Reactions.escapeStand(pet, owner, 6, at -> !GlassSight.clearTo(owner, at.add(0, pet.getEyeHeight(), 0))
                        && (child == null || child.distanceToSqr(at) <= 36));
                PetScenes.act(pet, YukkuriAction.SHY, "teary", "exam_hide_fail", 30, owner);
                if (stand == null) finish(Duty.End.FAILED, "no_cover");
            }
            case LIE -> {
                var away = pet.position().scale(2).subtract(child.position());
                pet.getLookControl().setLookAt(away.x, pet.getEyeY(), away.z);
                if (say(YukkuriAction.TURN, "pursed", "sukkiri_lie", null)) {
                    stand = Reactions.escapeStand(pet, child, 3, Set.of());
                }
                if (stand == null) finish(Duty.End.DONE, "denial_ended");
            }
            case DEFIANT -> {
                say(YukkuriAction.PUFF, "cheek_puff", "sukkiri_defiant", owner);
                finish(Duty.End.DONE, "confronted");
            }
            default -> {}
        }
    }

    @Override public boolean canContinueToUse() {
        if (brood != null) return pet.duty().holds(Duty.Kind.RULES) && brood.verdict() == null && freeForBrood()
                && pet.pet().ownedBy(owner.getUUID()) && owner.isAlive() && owner.level() == pet.level() && pet.distanceToSqr(owner) <= 256
                && (style == ArcStyle.HIDE || HumanSight.sees(owner, pet)) && (child == null || child.isAlive() && child.level() == pet.level())
                && (style != ArcStyle.HIDE || child == null || child.life().nutrition() >= 30 && !child.isPanicking());
        return pet.duty().holds(Duty.Kind.RULES) && pet.pet().conscience().undiscovered() && pet.pet().canWelcomeOwner()
                && pet.distanceToSqr(owner) <= 36 && HumanSight.sees(owner, pet) && pet.hasLineOfSight(owner);
    }

    @Override public void tick() {
        if (!pet.duty().holds(Duty.Kind.RULES)) return;
        if (brood != null && (style == ArcStyle.HIDE || style == ArcStyle.LIE)) {
            var result = SurfaceApproach.retreatResult(pet, stand, style == ArcStyle.HIDE ? owner : child, .9);
            if (result == SurfaceApproach.Result.ARRIVED) {
                GameplayLog.event(pet, "rules", "arc arrived style={} brood={} screened={}", style, brood.brood,
                        !GlassSight.clearTo(owner, pet.getEyePosition()));
                finish(Duty.End.DONE, "arrived");
            } else if (result == SurfaceApproach.Result.BLOCKED) finish(Duty.End.FAILED, "unreachable");
            return;
        }
        if (pet.distanceToSqr(owner) <= 2.25) {
            if (brood == null) pet.pet().conscience().caught("confession");
            else if (style == ArcStyle.CONFESS) say(YukkuriAction.BOW, "teary", "confess", owner);
            else say(YukkuriAction.JOY_JUMP, "happy", "sukkiri_naive", child);
            finish(Duty.End.DONE, "met_owner");
        } else {
            var stand = SurfaceApproach.beside(pet, owner, 1, .5, 1.5);
            if (stand == null || SurfaceApproach.walkResult(pet, stand, owner, .8) == SurfaceApproach.Result.BLOCKED)
                pet.duty().release(Duty.Kind.RULES, Duty.End.FAILED, "unreachable");
        }
    }

    private boolean say(YukkuriAction action, String face, String event, LivingEntity look) {
        if (!PetScenes.say(pet, action, face, event, look)) return false;
        GameplayLog.event(pet, "rules", "arc spoke={} brood={} owner={}", event, brood.brood, owner.getUUID());
        return true;
    }

    private void finish(Duty.End end, String reason) { pet.duty().release(Duty.Kind.RULES, end, reason); }

    @Override public boolean requiresUpdateEveryTick() { return true; }

    @Override public void stop() {
        brood = null;
        stand = null;
        child = null;
        SurfaceApproach.stopWalking(pet);
        pet.duty().release(Duty.Kind.RULES, Duty.End.YIELDED, "interrupted");
    }
}
