package io.github.longshou.mcyukkuri.pet;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.human.HumanNpc;
import io.github.longshou.mcyukkuri.life.EcologyMovement;
import io.github.longshou.mcyukkuri.life.PetNestBlock;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.status.LearnedFear;
import io.github.longshou.mcyukkuri.status.Reactions;
import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.Vec3;

/** An unled companion follows outside its comfort distance. If separated, it remembers the way into its yard. */
public final class PetOuting extends Goal {
    private final YukkuriEntity pet;
    private BlockPos home, blockedAt;
    private HumanNpc owner;
    private Vec3 stand;
    private boolean shelter;

    public PetOuting(YukkuriEntity pet) {
        this.pet = pet;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    private double independence() {
        var traits = pet.life().personality();
        return (100 + traits.boldness() - traits.attachment()) / 200.0;
    }

    public double outerDistance() { return 2 + 4 * independence(); }
    private double innerDistance() { return 1 + 2 * independence(); }

    public boolean fit() {
        var body = pet.body().condition();
        return pet.isAlive() && !pet.isNoAi() && pet.family().adult() && pet.family().brood() == null
                && !pet.family().tendsUnweaned() && !pet.isPassenger() && !pet.isVehicle() && !pet.isLeashed()
                && !pet.profile().aquatic() && !pet.profile().houseBody() && !pet.profile().dangerToYukkuri()
                && body.readyForPlay() && !body.weak() && !body.dying() && !body.sick() && !body.hasSicknessPressure()
                && !body.footBurned() && pet.body().toxin() == 0 && !pet.life().temperature().cold()
                && !pet.life().hasUrgentNeed() && pet.life().energy() >= 45 - 15 * independence()
                && !pet.life().isSleeping() && !pet.isPanicking() && !pet.pet().busy() && !pet.operations().active();
    }

    public boolean wants(HumanNpc resident) {
        var traits = pet.life().personality();
        return home == null && fit() && pet.pet().calm() && !LearnedFear.waryOf(pet, resident)
                && pet.dailyLife().boredom() >= 70 - (traits.boldness() + traits.attachment()) / 8.0;
    }

    public void leave(BlockPos yard) {
        home = yard;
        blockedAt = null;
        GameplayLog.event(pet, "pet", "outing joined yard={} follow={}..{}", yard.toShortString(), innerDistance(), outerDistance());
    }

    public void forget() { home = null; }
    public boolean away() { return home != null && pet.blockPosition().distSqr(home) > 36; }

    HumanNpc escort() {
        if (home == null || pet.pet().owner() == null) return null;
        return ((ServerLevel) pet.level()).getEntity(pet.pet().owner()) instanceof HumanNpc resident
                && resident.strollingWith(pet) ? resident : null;
    }

    private boolean free() {
        return home != null && !pet.isLeashed() && !pet.isPassenger() && !pet.isPanicking()
                && !pet.operations().active() && !pet.isCommandPlaying() && !pet.pet().busy()
                && !pet.life().isSleeping() && pet.life().mealTicks() == 0;
    }

    @Override public boolean canUse() {
        if (!free()) return false;
        owner = escort();
        if (owner != null) return pet.distanceTo(owner) > outerDistance();
        return !pet.blockPosition().equals(blockedAt);
    }

    @Override public void start() {
        if (owner != null) return;
        if (atHome()) { arrived("home"); return; }
        stand = SurfaceApproach.beside(pet, Vec3.atBottomCenterOf(home), 1.5, at -> true);
        if (stand == null) seekShelter();
    }

    private boolean atHome() {
        return pet.distanceToSqr(Vec3.atBottomCenterOf(home)) <= 6.25 && pet.onGround()
                && pet.level().clip(new ClipContext(pet.getEyePosition(), home.getCenter(),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, pet))
                .getBlockPos().equals(home);
    }

    private void seekShelter() {
        var roof = Reactions.findCover(pet, null, 0);
        stand = roof == null ? null : Vec3.atBottomCenterOf(roof);
        shelter = true;
        if (stand == null) blockedAt = pet.blockPosition();
    }

    private void arrived(String where) {
        GameplayLog.event(pet, "pet", "outing reached {} yard={}", where, home.toShortString());
        home = null;
    }

    @Override public boolean canContinueToUse() {
        return free() && (owner != null ? owner == escort() && pet.distanceTo(owner) > innerDistance()
                : stand != null && escort() == null);
    }

    @Override public boolean requiresUpdateEveryTick() { return true; }
    @Override public void tick() {
        if (!free()) return;
        var bed = pet.life().homeStand();
        if (away() && bed != null && pet.level().getBlockState(bed).getBlock() instanceof PetNestBlock)
            pet.life().releaseHome();
        if (owner != null) {
            if (EcologyMovement.approachResult(pet, owner, 1.15) == SurfaceApproach.Result.BLOCKED)
                owner.turnOutingHome();
        } else if (stand != null) {
            var result = SurfaceApproach.walkResult(pet, stand, null, 1);
            if (shelter ? result == SurfaceApproach.Result.ARRIVED : atHome()) arrived(shelter ? "shelter" : "home");
            else if (result == SurfaceApproach.Result.BLOCKED || result == SurfaceApproach.Result.ARRIVED) {
                if (!shelter) seekShelter();
                else { blockedAt = pet.blockPosition(); stand = null; }
            }
        }
    }

    @Override public void stop() { SurfaceApproach.stopWalking(pet); owner = null; stand = null; shelter = false; }

    public void save(CompoundTag tag) {
        if (home != null) {
            tag.putLong("OutingYard", home.asLong());
            tag.putString("OutingDimension", pet.level().dimension().location().toString());
        }
    }

    public void load(CompoundTag tag) {
        home = tag.contains("OutingYard") && pet.level().dimension().location().toString().equals(tag.getString("OutingDimension"))
                ? BlockPos.of(tag.getLong("OutingYard")) : null;
    }
}
