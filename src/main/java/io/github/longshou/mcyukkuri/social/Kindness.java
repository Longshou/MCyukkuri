package io.github.longshou.mcyukkuri.social;

import io.github.longshou.mcyukkuri.entity.YukkuriComponents;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyFeedingContact;
import io.github.longshou.mcyukkuri.family.FamilyNest;
import io.github.longshou.mcyukkuri.life.CareBlockEntity;
import io.github.longshou.mcyukkuri.life.NestDoorway;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.life.TravelRange;
import io.github.longshou.mcyukkuri.life.YukkuriLife;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.motor.MouthOccupancy.Purpose;
import io.github.longshou.mcyukkuri.motor.surface.Occupancy;
import io.github.longshou.mcyukkuri.pet.exam.ExamSession;
import io.github.longshou.mcyukkuri.settlement.CommonStore;
import io.github.longshou.mcyukkuri.settlement.ForagePileBlockEntity;
import io.github.longshou.mcyukkuri.settlement.PileContact;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.status.Identity;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/** One response owner for voluntary, non-kin help. The mouth and bed retain their own transfer authority. */
public final class Kindness extends Goal {
    private static final int CUE = 20, RECEIVER_REST = 2400, GIVER_REST = 1200, ATTEMPT = 1200;
    private enum Act { BITE, COMPANY, BED, ADOPT }
    private enum Decision { GIVE, IGNORE, REFUSE }
    private final YukkuriEntity giver;
    private final PileContact pileContact;
    private YukkuriEntity guest;
    private Act act;
    private CareBlockEntity bed;
    private ForagePileBlockEntity pile;
    private Vec3 stand, guestAt, companyAt;
    private int ticks, contact, cooldown, received, nextScan;
    private UUID mealDonor;
    private boolean running, finished, mealSwallowed;

    public Kindness(YukkuriEntity giver) {
        this.giver = giver;
        pileContact = new PileContact(giver);
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    private ServerLevel level() { return (ServerLevel) giver.level(); }
    private YukkuriWorldData relations() { return YukkuriWorldData.get(level()); }
    private static Kindness of(YukkuriEntity entity) { return entity.kindness(); }

    void clock() {
        if (giver.life().isSleeping()) {
            if (act != null && !running) stop();
            return;
        }
        if (cooldown > 0) cooldown--;
        if (nextScan > 0) nextScan--;
        if (received > 0 && --received == 0) mealDonor = null;
        if (act != null && !running && (++ticks >= CUE || !valid())) stop();
        if (act == null && giver.component(YukkuriComponents.MOUTH).purpose() == Purpose.KINDNESS
                && !giver.isPassenger() && !giver.isRemoved() && nextScan == 0) {
            nextScan = 40;
            if (!giver.life().beginCarriedMeal(Purpose.KINDNESS))
                giver.component(YukkuriComponents.MOUTH).releaseItems(Purpose.KINDNESS, 20);
        }
    }

    private static boolean available(YukkuriEntity body) {
        var identity = body.status().identity();
        return body.isAlive() && !body.isRemoved() && !body.life().isSleeping() && !body.isPassenger()
                && !body.isInWaterOrBubble() && !body.isOnFire() && !body.isPanicking() && !body.isCommandPlaying()
                && !body.operations().active() && body.hurtTime == 0 && !body.profile().houseBody()
                && !body.profile().dangerToYukkuri() && !body.settlement().society().slave()
                && (identity.free() || identity == Identity.PET) && !ExamSession.running(body);
    }

    private boolean ready() {
        var body = giver.body().condition();
        return available(giver) && !giver.isNoAi() && (giver.family().adult() || giver.family().subadult())
                && giver.life().nutrition() >= 50 && received <= GIVER_REST
                && body.readyForAffection() && body.mold() == 0 && !body.sick() && !body.dying()
                && !CareBlockEntity.expecting(giver) && !giver.family().laboring() && !giver.family().birthDuty()
                && !giver.groupInteraction().defense().active() && !giver.predator().active() && !giver.agitation().hunting();
    }

    private boolean withinPetRange(Vec3 at) {
        if (giver.status().identity() != Identity.PET) return true;
        if (!giver.isWithinRestriction(BlockPos.containing(at))) return false;
        var home = giver.life().homeStand();
        if (home != null && at.distanceToSqr(Vec3.atBottomCenterOf(home)) > TravelRange.LEISURE_RADIUS * TravelRange.LEISURE_RADIUS)
            return false;
        var lead = giver.getLeashHolder();
        return !giver.isLeashed() || lead != null && lead.distanceToSqr(at) <= 6 * 6;
    }

    private boolean peer(YukkuriEntity target) {
        if (target == giver || target.level() != giver.level() || !available(target) || !withinPetRange(target.position())
                || target.profile().aquatic() != giver.profile().aquatic() || target.groupInteraction().defense().active()
                || target.groupInteraction().active() || Bonds.careKin(giver, target)
                || target.family().isActiveFoster(giver) || giver.family().isActiveFoster(target)
                || !giver.hasLineOfSight(target)) return false;
        UUID owner = giver.pet().owner();
        return giver.status().identity() == Identity.PET
                ? owner != null && owner.equals(target.pet().owner()) : target.status().identity().free();
    }

    private boolean lonely(YukkuriEntity target) {
        return target.pet().waitingAfterAbandonment() || target.life().custody().lost()
                || target.family().orphaned() && target.hasReaction() && target.mind().unease() > 20;
    }

    private boolean hungry(YukkuriEntity target) {
        return target.life().nutrition() < 30 && of(target).received == 0 && target.life().mealTicks() == 0
                && target.life().canReceiveCareFood();
    }

    private boolean wetOrCold(YukkuriEntity body) {
        return body.life().wetness() > 0 || body.life().temperature().cold();
    }

    private boolean waitingAtBed(YukkuriEntity target, CareBlockEntity candidate) {
        if (candidate == null || candidate.isRemoved() || candidate.owns(target.getUUID())
                || target.family().adult() || !(wetOrCold(target) || target.body().condition().needsPainExpression())
                || level().isRaining() || wetOrCold(giver)) return false;
        var opening = NestDoorway.stand(target, candidate);
        return opening != null && target.distanceToSqr(opening) <= .64
                && (level().isDay() || target.life().homeStand() != null);
    }

    private Decision decide(YukkuriEntity target, Act request) {
        if (relations().fear(target.getUUID(), giver.getUUID()) >= (request == Act.ADOPT ? 30 : 25)) return Decision.REFUSE;
        double willingness = SocialDisposition.ramp(giver.life().personality().friendliness(), 60, 90);
        return giver.getRandom().nextDouble() < willingness ? Decision.GIVE : Decision.IGNORE;
    }

    private boolean reserve(YukkuriEntity target, Act request) {
        var decision = decide(target, request);
        if (decision != Decision.GIVE) {
            cooldown = GIVER_REST;
            GameplayLog.event(giver, "kindness", "response={} act={} peer={}", decision, request, target.getUUID());
            return false;
        }
        guest = target;
        act = request;
        return true;
    }

    /** An unshared ordinary mouthful can be offered in place before the first chew completes. */
    public void offerMeal() {
        if (act != null || cooldown > 0 || !ready() || giver.status().identity() == Identity.WILD_SETTLEMENT) return;
        var recipient = level().getEntitiesOfClass(YukkuriEntity.class, giver.getBoundingBox().inflate(1.2),
                target -> peer(target) && hungry(target)
                        && giver.distanceToSqr(target) <= 1.5 * 1.5)
                .stream().min(Comparator.comparingDouble(giver::distanceToSqr)).orElse(null);
        if (recipient != null && reserve(recipient, Act.BITE)) {
            if (!giver.life().prepareKindnessFood()) stop();
        }
    }

    private ForagePileBlockEntity source(YukkuriEntity target) {
        if (giver.status().identity() == Identity.WILD_SETTLEMENT)
            return CommonStore.nearest(level(), giver.settlement().group(), giver.blockPosition(),
                    candidate -> hasFood(candidate, target));
        var shelter = giver.habitat().shelter();
        var candidate = shelter == null || giver.status().identity() == Identity.STRAY ? null : shelter.pile();
        return candidate != null && hasFood(candidate, target) ? candidate : null;
    }

    private boolean hasFood(ForagePileBlockEntity candidate, YukkuriEntity target) {
        return !candidate.isRemoved() && level().hasChunkAt(candidate.getBlockPos())
                && level().getBlockEntity(candidate.getBlockPos()) == candidate && withinPetRange(candidate.standPoint(giver))
                && !candidate.peek(item -> suitable(target, item)).isEmpty();
    }

    private static boolean suitable(YukkuriEntity target, ItemStack item) {
        return YukkuriLife.kindnessFood(item) && target.life().acceptsFood(item);
    }

    @Override public boolean canUse() {
        if (act != null) return !running && valid();
        if (cooldown > 0 || nextScan > 0 || giver.life().mealTicks() > 0 || !ready()
                || SocialDisposition.ramp(giver.life().personality().friendliness(), 60, 90) == 0) return false;
        nextScan = 40;
        var nest = FamilyNest.care(giver);
        if (nest != null && FamilyNest.bedOccupancy(giver, nest.getBlockPos()) != Occupancy.INSIDE_SUPPORTED) nest = null;
        var peers = level().getEntitiesOfClass(YukkuriEntity.class, giver.getBoundingBox().inflate(8), this::peer);
        for (var target : peers.stream().sorted(Comparator.comparingDouble(giver::distanceToSqr)).toList()) {
            if (giver.family().canAdopt(target) && of(target).prefersFoster(giver)) {
                if (reserve(target, Act.ADOPT)) return true;
                return false;
            }
            if (waitingAtBed(target, nest)) {
                if (!reserve(target, Act.BED)) return false;
                bed = nest;
                return true;
            }
            if (hungry(target) && giver.component(YukkuriComponents.MOUTH).freeForExternalWork()) {
                var available = source(target);
                if (available != null) {
                    if (!reserve(target, Act.BITE)) return false;
                    pile = available;
                    return true;
                }
            }
            if (lonely(target)) return reserve(target, Act.COMPANY);
        }
        return false;
    }

    private boolean prefersFoster(YukkuriEntity candidate) {
        if (!mealSwallowed || mealDonor == null || giver.life().mealTicks() != 0
                || relations().affinity(giver.getUUID(), candidate.getUUID()) <= 0)
            return false;
        var best = level().getEntitiesOfClass(YukkuriEntity.class, giver.getBoundingBox().inflate(8), other ->
                of(other).ready() && of(other).peer(giver) && other.family().canAdopt(giver)
                        && other.life().personality().friendliness() > 60
                        && (other == candidate || of(other).cooldown == 0)
                        && relations().fear(giver.getUUID(), other.getUUID()) < 30
                        && (mealDonor.equals(other.getUUID()) || relations().affinity(giver.getUUID(), other.getUUID()) >= 3))
                .stream().max(Comparator.<YukkuriEntity>comparingInt(other -> relations().affinity(giver.getUUID(), other.getUUID()))
                        .thenComparingDouble(other -> -giver.distanceToSqr(other))).orElse(null);
        return best == candidate;
    }

    @Override public void start() {
        running = true;
        ticks = 0;
        guestAt = guest.position();
        giver.getLookControl().setLookAt(guest, 30, 30);
        if (act == Act.ADOPT || act == Act.BITE && giver.family().canAdopt(guest))
            giver.social().startGreeting(guest, "foster");
        else giver.showReaction("closed_smile", "", CUE + 10);
        GameplayLog.event(giver, "kindness", "offer act={} peer={}", act, guest.getUUID());
    }

    private boolean valid() {
        if (act == null || !ready() || !peer(guest) || giver.distanceToSqr(guest) > 12 * 12) return false;
        if (relations().fear(guest.getUUID(), giver.getUUID()) >= (act == Act.ADOPT ? 30 : 25)) return false;
        if (giver.life().mealTicks() > 0) return false;
        return switch (act) {
            case BITE -> hungry(guest) && (pile != null || giver.component(YukkuriComponents.MOUTH).purpose() == Purpose.KINDNESS);
            case BED -> waitingAtBed(guest, bed) && bed.owns(giver.getUUID());
            case COMPANY -> lonely(guest) && guest.distanceToSqr(companyAt == null ? guestAt : companyAt) <= 2.25;
            case ADOPT -> giver.family().canAdopt(guest) && of(guest).prefersFoster(giver);
        };
    }

    @Override public boolean canContinueToUse() { return running && !finished && ticks < ATTEMPT && valid(); }
    @Override public boolean requiresUpdateEveryTick() { return true; }
    @Override public void tick() {
        if (!finished && valid()) step();
    }

    private void step() {
        ticks++;
        if (act == Act.BED) { yieldBed(); return; }
        if (pile != null) {
            if (!hasFood(pile, guest) || !giver.component(YukkuriComponents.MOUTH).freeForExternalWork()) { finished = true; return; }
            if (pileContact.tick(pile)) {
                var food = pile.take(item -> suitable(guest, item));
                if (!food.isEmpty()) giver.component(YukkuriComponents.MOUTH).set(Purpose.KINDNESS, food);
                else finished = true;
                pile = null;
            }
            return;
        }
        if (guest.distanceToSqr(guestAt) > .09) { guestAt = guest.position(); stand = null; }
        if (giver.distanceToSqr(guest) <= 2.25) FamilyFeedingContact.faceEachOther(giver, guest);
        if (!FamilyFeedingContact.mouthsMeet(giver, guest)) {
            if (act == Act.COMPANY && contact >= CUE) { finished = true; return; }
            contact = 0;
            if (stand == null) stand = FamilyFeedingContact.approachSpot(giver, guest);
            if (stand == null || !withinPetRange(stand)
                    || SurfaceApproach.walkResult(giver, stand, null, .85) == SurfaceApproach.Result.BLOCKED) finished = true;
            return;
        }
        SurfaceApproach.halt(giver);
        if (++contact < CUE) return;
        switch (act) {
            case BITE -> give();
            case COMPANY -> {
                if (contact == CUE) {
                    companyAt = guest.position();
                    relations().interact(giver.getUUID(), guest.getUUID(), 1, 0);
                    guest.mind().add(-3, "kindness_company");
                    GameplayLog.event(giver, "kindness", "kept company peer={}", guest.getUUID());
                }
                finished = contact >= CUE + 600;
            }
            case ADOPT -> {
                giver.social().noteContact(guest);
                giver.family().adopt(guest);
                finished = true;
            }
            case BED -> throw new IllegalStateException("Bed yield uses its doorway");
        }
    }

    private void give() {
        boolean handed = giver.component(YukkuriComponents.MOUTH)
                .offerKindnessFood(item -> guest.life().beginKindnessMeal(giver, item));
        if (handed) {
            var receiver = of(guest);
            receiver.received = RECEIVER_REST;
            receiver.mealDonor = giver.getUUID();
            receiver.mealSwallowed = false;
            if (giver.family().canAdopt(guest)) giver.social().noteContact(guest);
            GameplayLog.event(giver, "kindness", "bite handed peer={}", guest.getUUID());
        } else {
            cooldown = GIVER_REST;
        }
        finished = true;
    }

    public void swallowed(UUID donor) {
        if (donor != null && donor.equals(mealDonor)) mealSwallowed = true;
    }

    private void yieldBed() {
        if (ticks <= CUE) { giver.getLookControl().setLookAt(guest, 30, 30); return; }
        if (stand == null) stand = NestDoorway.stand(giver, bed, giver.position().add(giver.position().subtract(guest.position())));
        if (stand == null || !withinPetRange(stand)) { finished = true; return; }
        var result = SurfaceApproach.walkResult(giver, stand, null, .85);
        if (result == SurfaceApproach.Result.BLOCKED) { finished = true; return; }
        if (result == SurfaceApproach.Result.ARRIVED && FamilyNest.bedOccupancy(giver, bed.getBlockPos()) != Occupancy.INSIDE_SUPPORTED) {
            if (bed.yieldTo(giver, guest)) GameplayLog.event(giver, "kindness", "nest yielded peer={} pos={}", guest.getUUID(), bed.getBlockPos());
            finished = true;
        }
    }

    @Override public void stop() {
        if (act == null) return;
        cooldown = Math.max(cooldown, act == Act.COMPANY ? RECEIVER_REST : act == Act.BITE && finished ? CUE : GIVER_REST);
        if (running) SurfaceApproach.halt(giver);
        running = finished = false;
        guest = null; bed = null; pile = null; act = null; stand = null; guestAt = null; companyAt = null;
        ticks = contact = 0;
        pileContact.reset();
    }
}
