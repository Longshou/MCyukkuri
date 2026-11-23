package io.github.longshou.mcyukkuri.pet;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.human.HumanNpc;
import io.github.longshou.mcyukkuri.life.BehaviorContinuity;
import io.github.longshou.mcyukkuri.life.PetNestBlock;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.play.PlayBlockEntity;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.status.LearnedFear;
import io.github.longshou.mcyukkuri.status.Reactions;
import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.ClipContext;
import io.github.longshou.mcyukkuri.pet.rules.OutingPerformance;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** A companion follows outside its comfort distance. If separated, it remembers the way into its yard. */
public final class PetOuting extends Goal {
    private final YukkuriEntity pet;
    private BlockPos home, blockedAt, escortAt;
    private HumanNpc escort, owner;
    private Vec3 stand;
    /** A pet with no stand beside its owner looks again this often: the crowd round the owner and its own place change while the owner sits still. */
    private static final int RESAMPLE_TICKS = 20;
    private Vec3 doorwayStand;
    private boolean shelter;
    private boolean closeApproach;
    private boolean playAvailable;

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

    public boolean sound() {
        return pet.family().adult() && leisureHealth(false) && freeOfOtherLead();
    }

    private boolean leisureHealth(boolean socialOk) {
        var body = pet.body().condition();
        return pet.isAlive() && !pet.isNoAi() && pet.family().brood() == null
                && !pet.family().tendsUnweaned() && !pet.isPassenger() && !pet.isVehicle()
                && !pet.profile().aquatic() && !pet.profile().houseBody() && !pet.profile().dangerToYukkuri()
                && body.readyForPlay() && !body.weak() && !body.dying() && !body.sick() && !body.hasSicknessPressure()
                && !body.footBurned() && pet.body().toxin() == 0 && !pet.life().temperature().cold()
                && !pet.life().hasUrgentNeed() && !pet.isPanicking() && (socialOk || !pet.pet().busy()) && !pet.operations().active();
    }

    /** An optional stop can yield earlier than an ordinary escort when the pet would rather eat. */
    public boolean readyForPlaza() {
        return leisureHealth(false) && !tired() && pet.life().nutrition() > picnicBand() && pet.pet().calm()
                && !BehaviorContinuity.emergency(pet);
    }

    /** Resting at the plaza the pet may be mid-way through a badge exchange with a passer-by; only its body and needs end the visit. */
    public boolean restsAtPlaza() {
        return pet.family().adult() && leisureHealth(true) && freeOfOtherLead() && !pet.life().isSleeping() && !tired()
                && pet.life().nutrition() > picnicBand() && !BehaviorContinuity.emergency(pet);
    }

    public double restBand() { return 45 - 15 * independence(); }
    public double picnicBand() { return 50 - pet.life().personality().boldness() / 5.0; }
    public boolean tired() { return pet.life().energy() < restBand(); }
    public boolean fit() { return sound() && !pet.life().isSleeping() && !tired(); }

    public boolean restsHere() {
        var resident = escort();
        return resident != null && resident.parkLingering() && tired() && sound() && pet.level().getDayTime() % 24000 <= 11000;
    }

    public PlayBlockEntity parkFacility() {
        var resident = escort();
        return resident == null ? null : resident.parkFacility();
    }

    private boolean nearFacility(PlayBlockEntity facility) {
        return pet.distanceToSqr(facility.getBlockPos().getCenter()) <= 64;
    }

    public boolean tryParkPlay() {
        var resident = escort();
        var facility = resident == null ? null : resident.parkFacility();
        if (facility == null || onEscortLead() || tired() || !wantsParkPlay() || wantsPicnic(resident)
                || !nearFacility(facility)) return false;
        if (facility.occupiedByOther(pet)) {
            GameplayLog.limited(pet, "pet", "park_occupied", 600, "outing play waiting kind={} at={}",
                    facility.kind(), facility.getBlockPos());
            return false;
        }
        var play = pet.playLife();
        if (play.begin(facility)) return true;
        playAvailable = false;
        GameplayLog.event(pet, "pet", "outing play refused reason={} kind={} at={}",
                play.refused(), facility.kind(), facility.getBlockPos());
        return false;
    }

    private boolean wantsParkPlay() {
        return playAvailable && pet.dailyLife().boredom() >= 60 - pet.life().personality().boldness() / 5.0;
    }

    public void finishedParkPlay() { playAvailable = false; }

    public boolean doneAtPark() {
        return !pet.life().isSleeping() && !pet.life().outingResting() && !pet.playLife().active() && !tired()
                && !wantsParkPlay();
    }

    public boolean wants(HumanNpc resident) {
        var traits = pet.life().personality();
        return home == null && fit() && !pet.playLife().active() && pet.pet().calm() && !LearnedFear.waryOf(pet, resident)
                && pet.dailyLife().boredom() >= 70 - (traits.boldness() + traits.attachment()) / 8.0;
    }

    public void leave(HumanNpc resident, BlockPos yard) {
        escort = resident;
        home = yard;
        blockedAt = null;
        playAvailable = true;
        GameplayLog.event(pet, "pet", "outing joined yard={} follow={}..{}", yard.toShortString(), innerDistance(), outerDistance());
    }

    public void forget() { home = null; escort = null; playAvailable = false; }
    /** Until it is back in its yard it neither searches for a home nor claims a bed, so the way back is not cut short by a bed claim. */
    public boolean onOuting() { return home != null; }
    public boolean escorted() { return escort() != null; }
    public boolean onEscortLead() { return pet.getLeashHolder() instanceof HumanNpc holder && holder == escort(); }
    public boolean permitsPerformance(boolean optional) { return OutingPerformance.allowed(onEscortLead(), optional); }
    private boolean away() { return home != null && pet.blockPosition().distSqr(home) > 36; }

    void releaseVacatedNest() {
        if (!away() || pet.life().home() == null) return;
        var bed = pet.life().homeStand();
        if (bed != null && pet.level().getBlockState(bed).getBlock() instanceof PetNestBlock) pet.life().releaseHome();
    }

    HumanNpc escort() {
        return escort != null && escort.level() == pet.level() && !escort.isRemoved()
                && escort.strollingWith(pet) ? escort : null;
    }

    private boolean freeOfOtherLead() {
        var resident = escort();
        return !pet.isLeashed() || resident != null && pet.getLeashHolder() == resident;
    }

    private boolean free() {
        return home != null && freeOfOtherLead() && !pet.isPassenger() && !pet.isPanicking()
                && !pet.operations().active() && !pet.isCommandPlaying() && !pet.pet().busy()
                && !pet.life().isSleeping() && pet.life().mealTicks() == 0 && !pet.playLife().active();
    }

    @Override public boolean canUse() {
        if (!free()) return false;
        owner = escort();
        if (owner != null) return shouldFollow(outerDistance());
        return !pet.blockPosition().equals(blockedAt);
    }

    private boolean shouldFollow(double comfortDistance) {
        if (owner.doorFollowerStand(pet) != null) return true;
        if (owner.callingPet(pet) || wantsPicnic(owner)) return pet.distanceToSqr(owner) > 4 || !pet.hasLineOfSight(owner);
        var paving = owner.plazaPaving();
        // The owner sits on a bench beside the plaza: the pet stays on the paving, where the strays can see its badge.
        if (paving != null) return !onPaving(paving, pet.position());
        var facility = owner.parkFacility();
        return (pet.distanceTo(owner) > comfortDistance || pet.getLeashHolder() == owner && !pet.hasLineOfSight(owner))
                && (facility == null || !nearFacility(facility));
    }

    private static AABB restPaving(AABB paving) { return paving.inflate(-.6, 0, -.6); }
    private static boolean onPaving(AABB paving, Vec3 at) { return restPaving(paving).contains(at); }

    private boolean wantsPicnic(HumanNpc resident) {
        return resident.parkLingering() && resident.holdsPicnicFood() && pet.life().nutrition() <= picnicBand();
    }

    @Override public void start() {
        if (owner != null) return;
        if (atHome()) { arrived("home"); return; }
        stand = SurfaceApproach.beside(pet, Vec3.atBottomCenterOf(home), 1.5,
                at -> seesYard(at.add(0, pet.getEyeHeight(), 0)));
        if (stand == null) seekShelter();
    }

    private boolean atHome() {
        return pet.distanceToSqr(Vec3.atBottomCenterOf(home)) <= 6.25 && pet.onGround()
                && seesYard(pet.getEyePosition());
    }

    private boolean seesYard(Vec3 eye) {
        return pet.level().clip(new ClipContext(eye, home.getCenter(), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, pet))
                .getBlockPos().equals(home);
    }

    private void seekShelter() {
        var roof = Reactions.findCover(pet, null, 0);
        stand = roof == null ? null : Vec3.atBottomCenterOf(roof);
        shelter = true;
        if (stand == null) blockedReturn();
    }

    private void blockedReturn() {
        blockedAt = pet.blockPosition();
        stand = null;
        GameplayLog.event(pet, "pet", "outing return blocked yard={}", home.toShortString());
    }

    private void arrived(String where) {
        GameplayLog.event(pet, "pet", "outing reached {} yard={}", where, home.toShortString());
        forget();
    }

    @Override public boolean canContinueToUse() {
        return free() && (owner != null ? owner == escort() && shouldFollow(innerDistance())
                : stand != null && escort() == null);
    }

    @Override public boolean requiresUpdateEveryTick() { return true; }
    @Override public void tick() {
        if (!free()) return;
        if (owner != null) {
            var through = owner.doorFollowerStand(pet);
            if (through != null) {
                if (!through.equals(doorwayStand)) {
                    doorwayStand = through;
                    stand = SurfaceApproach.restingFloor(pet, through);
                    escortAt = null;
                }
                if (stand != null) SurfaceApproach.walkResult(pet, stand, null, 1.15);
                return;
            }
            doorwayStand = null;
            var paving = owner.plazaPaving();
            if (paving != null) {
                if (stand == null && pet.tickCount % RESAMPLE_TICKS == 0)
                    stand = SurfaceApproach.beside(pet, new Vec3(paving.getCenter().x, paving.minY, paving.getCenter().z), 1.2,
                            at -> onPaving(paving, at));
                if (stand != null) {
                    var result = SurfaceApproach.walkResult(pet, stand, null, 1.15);
                    if (result == SurfaceApproach.Result.BLOCKED || result == SurfaceApproach.Result.ARRIVED) stand = null;
                }
                return;
            }
            boolean close = owner.callingPet(pet) || wantsPicnic(owner);
            if (!owner.blockPosition().equals(escortAt) || close != closeApproach || stand == null && pet.tickCount % RESAMPLE_TICKS == 0) {
                escortAt = owner.blockPosition();
                closeApproach = close;
                stand = SurfaceApproach.beside(pet, owner, close ? 1.5 : innerDistance(), 1, Double.POSITIVE_INFINITY,
                        at -> !close && pet.getLeashHolder() != owner || pet.level().clip(new ClipContext(at.add(0, pet.getEyeHeight(), 0),
                                owner.getEyePosition(), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, pet)).getType() == HitResult.Type.MISS);
            }
            // This leg ends at a fixed stand sampled behind the resident. Moving the resident's body
            // must not invalidate its path between samples.
            if (stand != null) {
                var result = SurfaceApproach.walkResult(pet, stand, null, 1.15);
                if (result == SurfaceApproach.Result.BLOCKED || result == SurfaceApproach.Result.ARRIVED) stand = null;
            }
        } else if (stand != null) {
            var result = SurfaceApproach.walkResult(pet, stand, null, 1);
            if (shelter ? result == SurfaceApproach.Result.ARRIVED : atHome()) arrived(shelter ? "shelter" : "home");
            else if (result == SurfaceApproach.Result.BLOCKED || result == SurfaceApproach.Result.ARRIVED) {
                if (!shelter) seekShelter();
                else blockedReturn();
            }
        }
    }

    @Override public void stop() { SurfaceApproach.stopWalking(pet); owner = null; escortAt = null; doorwayStand = null; stand = null; shelter = false; }

    public void save(CompoundTag tag) {
        if (home != null) {
            tag.putLong("OutingYard", home.asLong());
            tag.putString("OutingDimension", pet.level().dimension().location().toString());
        }
    }

    public void load(CompoundTag tag) {
        forget();
        home = tag.contains("OutingYard") && pet.level().dimension().location().toString().equals(tag.getString("OutingDimension"))
                ? BlockPos.of(tag.getLong("OutingYard")) : null;
    }
}
