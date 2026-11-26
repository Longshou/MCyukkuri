package io.github.longshou.mcyukkuri.social;

import io.github.longshou.mcyukkuri.entity.presentation.FaceTier;
import io.github.longshou.mcyukkuri.social.Temperament.Gate;
import io.github.longshou.mcyukkuri.social.YukkuriPersonality.Trait;
import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.entity.YukkuriComponents;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.pet.social.BadgeArcs;
import io.github.longshou.mcyukkuri.event.WildEvents;
import io.github.longshou.mcyukkuri.family.FamilyFeedingContact;
import io.github.longshou.mcyukkuri.life.CareBlockEntity;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.life.TravelRange;
import io.github.longshou.mcyukkuri.life.YukkuriLife;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.motor.ClipMarkers;
import io.github.longshou.mcyukkuri.motor.MouthOccupancy.Purpose;
import io.github.longshou.mcyukkuri.pet.exam.ExamSession;
import io.github.longshou.mcyukkuri.pet.rules.VigilOffers;
import io.github.longshou.mcyukkuri.settlement.CommonStore;
import io.github.longshou.mcyukkuri.settlement.ForagePileBlockEntity;
import io.github.longshou.mcyukkuri.settlement.FoundShelter;
import io.github.longshou.mcyukkuri.settlement.PileContact;
import io.github.longshou.mcyukkuri.settlement.ShelterBlockEntity;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.status.Identity;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/** One response owner for voluntary, non-kin help. Food transfers through mouth occupancy. */
public final class Kindness extends Goal {
    private static final int CUE = 20, RECEIVER_REST = 2400, GIVER_REST = 1200, ATTEMPT = 1200;
    private enum Act { BITE, COMPANY, ADOPT }
    private enum Decision { GIVE, IGNORE, REFUSE }
    public record ShelterOffer(ShelterBlockEntity carton, YukkuriEntity local, boolean ready) {}
    public enum ShelterReply { FOLLOW, STAY, INTERRUPTED }
    private final YukkuriEntity giver;
    private final PileContact pileContact;
    private YukkuriEntity guest;
    private Act act;
    private ForagePileBlockEntity pile;
    private Vec3 stand, guestAt, companyAt;
    private ShelterBlockEntity carton;
    private Vec3 gestureStand;
    private int lookBackAt, gestureDeadline;
    private int ticks, contact, cooldown, received, nextScan, visited;
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
        if (visited > 0) visited--;
        if (received > 0 && --received == 0) mealDonor = null;
        if (act != null && !running && (++ticks >= CUE || !valid())) stop();
        if (act == null && giver.component(YukkuriComponents.MOUTH).purpose() == Purpose.KINDNESS
                && !BadgeArcs.holdsBeggingFood(giver)
                && !giver.status().reactions().threat().responding()
                && !giver.isPassenger() && !giver.isRemoved() && nextScan == 0) {
            nextScan = 40;
            // This serving already reached a social contact. Resume chewing without opening it to another request.
            if (!giver.life().finishCarriedMeal(null))
                giver.component(YukkuriComponents.MOUTH).releaseItems(Purpose.KINDNESS, 20);
        }
    }

    private static boolean available(YukkuriEntity body) {
        var identity = body.status().identity();
        return body.isAlive() && !body.isRemoved() && !body.life().isSleeping() && !body.isPassenger()
                && !WildEvents.reserved(body) && !body.isInWaterOrBubble() && !body.isOnFire() && !body.isPanicking() && !body.isCommandPlaying()
                && !body.operations().active() && body.hurtTime == 0 && !body.profile().houseBody()
                && !body.profile().dangerToYukkuri() && !body.settlement().society().slave()
                && (identity.free() || identity == Identity.PET) && !ExamSession.running(body);
    }

    private boolean ready() {
        var body = giver.body().condition();
        return available(giver) && !giver.inert() && (giver.family().adult() || giver.family().subadult())
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

    /** Lonely, or a faltering (tired or overheated) one this giver knows and does not dislike, not visited within the receiver rest. */
    private boolean needsCompany(YukkuriEntity target) {
        return lonely(target) || target.life().faltering() && of(target).visited == 0 && giver.life().canRecognize(target)
                && relations().affinity(giver.getUUID(), target.getUUID()) >= BondLevels.AFFINITY_NEUTRAL;
    }

    private boolean hungry(YukkuriEntity target) {
        return target.life().nutrition() < 30 && of(target).received == 0 && target.life().mealTicks() == 0
                && target.life().canReceiveCareFood();
    }

    private Decision decide(YukkuriEntity target, Act request) {
        if (relations().fear(target.getUUID(), giver.getUUID()) >= (request == Act.ADOPT ? 30 : 25)) return Decision.REFUSE;
        double willingness = giver.life().personality().temperament().willingToGive();
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
        offerMeal(target -> true, () -> true);
    }

    /** A local can present its bite before the hungry waiter asks. A close hungry relative keeps priority. */
    public boolean offerMealToWaiter(BooleanSupplier kinClear) {
        return giver.status().identity() == Identity.STRAY
                && offerMeal(target -> target.status().identity() == Identity.STRAY && target.pet().waitingAfterAbandonment(), kinClear);
    }

    private boolean offerMeal(Predicate<YukkuriEntity> audience, BooleanSupplier kinClear) {
        if (act != null || cooldown > 0 || !ready() || giver.status().identity() == Identity.WILD_SETTLEMENT) return false;
        var recipient = level().getEntitiesOfClass(YukkuriEntity.class, giver.getBoundingBox().inflate(1.2),
                target -> audience.test(target) && peer(target) && hungry(target)
                        && giver.distanceToSqr(target) <= 1.5 * 1.5)
                .stream().min(Comparator.comparingDouble(giver::distanceToSqr)).orElse(null);
        if (recipient == null || !kinClear.getAsBoolean() || !reserve(recipient, Act.BITE)) return false;
        if (giver.life().prepareKindnessFood()) return true;
        stop();
        return false;
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
                || giver.life().personality().temperament().willingToGive() == 0) return false;
        nextScan = 40;
        var peers = level().getEntitiesOfClass(YukkuriEntity.class, giver.getBoundingBox().inflate(8), this::peer);
        for (var target : peers.stream().sorted(Comparator.comparingDouble(giver::distanceToSqr)).toList()) {
            if (giver.family().canAdopt(target) && of(target).prefersFoster(giver)) {
                if (reserve(target, Act.ADOPT)) return true;
                return false;
            }
            if (hungry(target) && giver.component(YukkuriComponents.MOUTH).freeForExternalWork()) {
                var available = source(target);
                if (available != null) {
                    if (!reserve(target, Act.BITE)) return false;
                    pile = available;
                    return true;
                }
            }
            if (needsCompany(target)) return reserve(target, Act.COMPANY);
        }
        return false;
    }

    private boolean prefersFoster(YukkuriEntity candidate) {
        if (!mealSwallowed || mealDonor == null || giver.life().mealTicks() != 0
                || relations().affinity(giver.getUUID(), candidate.getUUID()) <= 0)
            return false;
        var best = level().getEntitiesOfClass(YukkuriEntity.class, giver.getBoundingBox().inflate(8), other ->
                of(other).ready() && of(other).peer(giver) && other.family().canAdopt(giver)
                        && other.life().personality().temperament().is(Gate.WARM)
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
        if (act == Act.BITE && giver.family().canAdopt(guest))
            giver.social().startGreeting(guest, "foster");
        else giver.showReaction("closed_smile", "", FaceTier.Tier.ofTicks(CUE + 10));
        GameplayLog.event(giver, "kindness", "offer act={} peer={}", act, guest.getUUID());
    }

    private boolean valid() {
        if (act == null || !ready() || !peer(guest) || giver.distanceToSqr(guest) > 12 * 12) return false;
        if (relations().fear(guest.getUUID(), giver.getUUID()) >= (act == Act.ADOPT ? 30 : 25)) return false;
        if (giver.life().mealTicks() > 0) return false;
        return switch (act) {
            case BITE -> hungry(guest) && (pile != null || giver.component(YukkuriComponents.MOUTH).purpose() == Purpose.KINDNESS);
            case COMPANY -> needsCompany(guest) && guest.distanceToSqr(companyAt == null ? guestAt : companyAt) <= 2.25;
            case ADOPT -> giver.family().canAdopt(guest) && of(guest).prefersFoster(giver);
        };
    }

    @Override public boolean canContinueToUse() { return running && !finished && ticks < ATTEMPT && valid(); }
    /** A response is under way from the accepted request until it stops; ambient wild cards must not reserve her then. */
    public boolean active() { return act != null; }
    public boolean helping(YukkuriEntity peer) { return act != null && guest == peer; }
    @Override public boolean requiresUpdateEveryTick() { return true; }
    @Override public void tick() {
        if (!finished && valid()) step();
    }

    private void step() {
        ticks++;
        if (carton != null) { gesture(); return; }
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
            if (contact > 0 && act == Act.BITE) giver.showReaction("closed_smile", "", FaceTier.Tier.ofTicks(CUE));
            contact = 0;
            if (stand == null) stand = FamilyFeedingContact.approachSpot(giver, guest);
            if (stand == null || !withinPetRange(stand)
                    || SurfaceApproach.walkResult(giver, stand, null, .85) == SurfaceApproach.Result.BLOCKED) finished = true;
            return;
        }
        SurfaceApproach.halt(giver);
        var clip = ClipMarkers.of(YukkuriAction.OFFER_BITE.id());
        if (contact++ == 0 && act == Act.BITE)
            giver.showReaction(YukkuriAction.OFFER_BITE, "happy", "", clip.length(), FaceTier.Tier.ofTicks(clip.length()));
        if (act == Act.BITE && giver.reactionAction() == YukkuriAction.OFFER_BITE)
            guest.forage().pauseForBite(giver.component(YukkuriComponents.MOUTH).stack(Purpose.KINDNESS));
        int cue = act == Act.BITE ? clip.ticks("contact", clip.length() / 2) : CUE;
        if (contact < cue) return;
        switch (act) {
            case BITE -> give();
            case COMPANY -> {
                if (contact == CUE) {
                    companyAt = guest.position();
                    relations().interact(giver.getUUID(), guest.getUUID(), 1, 0);
                    guest.mind().add(-3, "kindness_company");
                    GameplayLog.event(giver, "kindness", "kept company peer={}", guest.getUUID());
                    startShelterGesture();
                }
                finished = contact >= CUE + 600;
            }
            case ADOPT -> {
                giver.social().noteContact(guest);
                if (giver.family().adopt(guest)) giver.social().startGreeting(guest, "foster");
                finished = true;
            }
        }
    }

    private boolean shelterVisit() {
        return guest.pet().waitingAfterAbandonment() && FoundShelter.invitationTraveler(guest)
                && FoundShelter.invitationTraveler(giver) && FoundShelter.canTakeFoundShelter(guest);
    }

    private void startShelterGesture() {
        if (!shelterVisit()) return;
        var receiver = of(guest);
        var home = FoundShelter.visibleVacantCarton(giver, guest, candidate -> receiver.fresh(candidate, giver));
        if (home == null) return;
        var direction = FoundShelter.invitationOpening(home).subtract(giver.position()).multiply(1, 0, 1);
        // One or two body lengths is enough to show the direction while staying outside the opening.
        var destination = giver.position().add(direction.scale(Math.min(.5, 2 * giver.getBbWidth() / direction.length())));
        gestureStand = SurfaceApproach.stand(giver, destination);
        if (gestureStand != null) {
            carton = home;
            gestureDeadline = ticks + ClipMarkers.of(YukkuriAction.LOOK_AROUND.id()).length();
        }
    }

    private void gesture() {
        if (ticks >= gestureDeadline || !FoundShelter.invitationTraveler(giver) || !FoundShelter.invitationTraveler(guest)
                || !FoundShelter.visibleInvitation(giver, guest, carton)) { finished = true; return; }
        if (gestureStand != null) {
            // The glance only needs a visible step in the right direction, without lining up a contact pose.
            double closeEnough = giver.getBbWidth() / 4;
            if (giver.distanceToSqr(gestureStand) > closeEnough * closeEnough) {
                var step = SurfaceApproach.walkResult(giver, gestureStand, guest, .85);
                if (step == SurfaceApproach.Result.BLOCKED) finished = true;
                if (step != SurfaceApproach.Result.ARRIVED) return;
            }
            SurfaceApproach.halt(giver);
            gestureStand = null;
        }
        float yaw = SurfaceApproach.yaw(giver.position(), guest.position());
        SurfaceApproach.face(giver, yaw);
        if (lookBackAt == 0 && Math.abs(Mth.wrapDegrees(giver.getYRot() - yaw)) < 2) {
            if (!giver.showReaction(YukkuriAction.LOOK_AROUND, "closed_smile", "")) { finished = true; return; }
            lookBackAt = ticks;
            gestureDeadline = ticks + ClipMarkers.of(YukkuriAction.LOOK_AROUND.id()).length();
            GameplayLog.event(giver, "kindness", "carton offered peer={} shelter={} clip={}",
                    guest.getUUID(), carton.getBlockPos(), YukkuriAction.LOOK_AROUND.id());
        }
    }

    /** The receiver samples a live company gesture while choosing its own next life task. */
    public ShelterOffer liveShelterOffer() {
        if (!giver.pet().waitingAfterAbandonment() || giver.life().nutrition() < 30 || giver.life().mealTicks() != 0
                || !giver.component(YukkuriComponents.MOUTH).freeForExternalWork()) return null;
        var locals = level().getEntitiesOfClass(YukkuriEntity.class, giver.getBoundingBox().inflate(8), local -> {
            var visit = of(local);
            return visit.guest == giver && !visit.finished && visit.carton != null && visit.ticks < visit.gestureDeadline
                    && giver.hasLineOfSight(local) && FoundShelter.visibleInvitation(local, giver, visit.carton)
                    && (visit.lookBackAt == 0 || local.reactionAction() == YukkuriAction.LOOK_AROUND)
                    && visit.valid() && visit.shelterVisit() && fresh(visit.carton, local);
        });
        var local = locals.stream().min(Comparator.comparingDouble(giver::distanceToSqr)).orElse(null);
        return local == null ? null : new ShelterOffer(of(local).carton, local, of(local).lookBackAt > 0);
    }

    public ShelterReply answerShelter(ShelterOffer offer) {
        of(offer.local()).finished = true;
        var home = offer.carton();
        var evidence = evidence(offer.local());
        // Attachment shifts the age at which cover wins from the first to the last third of the vigil.
        double age = giver.pet().vigilProgress();
        double wait = (1 + giver.life().personality().temperament().attached()) / 3;
        boolean willing = age + evidence.need() + evidence.bond() >= wait;
        var look = Vec3.atCenterOf(giver.pet().abandonmentSite());
        giver.getLookControl().setLookAt(look.x, look.y, look.z);
        var action = willing ? YukkuriAction.LOOK_AROUND : YukkuriAction.DROOP;
        if (!giver.showReaction(action, willing ? "neutral" : "teary", "")) return ShelterReply.INTERRUPTED;
        giver.pet().vigilOffers().answer(site(home), evidence);
        GameplayLog.event(giver, "kindness", "carton decision={} local={} shelter={} clip={}",
                willing ? "follow" : "stay", offer.local().getUUID(), home.getBlockPos(), action.id());
        return willing ? ShelterReply.FOLLOW : ShelterReply.STAY;
    }

    private VigilOffers.Evidence evidence(YukkuriEntity local) {
        var life = giver.life();
        // Fatigue rises from the leisure band to urgent rest. Wetness rises through the ordinary cover band.
        double fatigue = Math.clamp((60 - life.energy()) / 35, 0, 1);
        double need = .45 * Math.max(Math.max(fatigue, life.wetness() / 50), life.temperature().cold() ? 1 : 0);
        double bond = Math.clamp(relations().affinity(giver.getUUID(), local.getUUID()) / 40.0, 0, .25);
        return new VigilOffers.Evidence(need, bond);
    }

    private VigilOffers.Site site(ShelterBlockEntity carton) {
        return new VigilOffers.Site(level().dimension().location().toString(), carton.getBlockPos().asLong());
    }

    private boolean fresh(ShelterBlockEntity carton, YukkuriEntity local) {
        return giver.pet().vigilOffers().fresh(site(carton), evidence(local));
    }

    private void give() {
        boolean handed = giver.component(YukkuriComponents.MOUTH)
                .offerKindnessFood(item -> guest.life().beginKindnessMeal(giver, item));
        if (handed) {
            giver.life().personality().experienced(Trait.FRIENDLINESS, 1, "shared_food");
            var receiver = of(guest);
            receiver.received = RECEIVER_REST;
            receiver.mealDonor = giver.getUUID();
            receiver.mealSwallowed = false;
            if (giver.family().canAdopt(guest)) giver.social().noteContact(guest);
            GameplayLog.event(giver, "kindness", "bite handed peer={} clip={}", guest.getUUID(), giver.getAnimationId());
            if (shelterVisit()) {
                act = Act.COMPANY;
                contact = 0;
                return;
            }
        } else {
            cooldown = GIVER_REST;
        }
        finished = true;
    }

    public void swallowed(UUID donor) {
        if (donor != null && donor.equals(mealDonor)) mealSwallowed = true;
    }

    @Override public void stop() {
        if (act == null) return;
        if (act == Act.BITE && !finished && giver.reactionAction() == YukkuriAction.OFFER_BITE) giver.cancelReaction();
        cooldown = Math.max(cooldown, act == Act.COMPANY ? RECEIVER_REST : act == Act.BITE && finished ? CUE : GIVER_REST);
        // A guest who was really kept company rests from every visitor, so a body that stays weak is not visited in a loop.
        if (act == Act.COMPANY && companyAt != null) of(guest).visited = RECEIVER_REST;
        if (running) SurfaceApproach.halt(giver);
        running = finished = false;
        guest = null; pile = null; act = null; stand = null; guestAt = null; companyAt = null;
        carton = null; gestureStand = null; lookBackAt = gestureDeadline = 0;
        ticks = contact = 0;
        pileContact.reset();
    }
}
