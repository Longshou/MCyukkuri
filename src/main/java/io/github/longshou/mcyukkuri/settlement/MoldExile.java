package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.body.BodySigns;
import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.event.WildEvents;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.mind.EmotionCourse;
import io.github.longshou.mcyukkuri.mind.EmotionEvent;
import io.github.longshou.mcyukkuri.motor.ContactGeometry;
import io.github.longshou.mcyukkuri.motor.MotorOwner;
import io.github.longshou.mcyukkuri.motor.action.Push;
import io.github.longshou.mcyukkuri.social.Bonds;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

/**
 * Residents answer visible mould. The affected individual owns its walk, and leaves only on reaching safe ground outside.
 * <p>The confrontation in beats (docs/FEATURES.md 油霉驱逐), each chosen by the values on both sides: a resident notices the mould
 * with a start ("Mr. Mold...!"); a fond kin sobs an apology, a driver puffs up and warns it off, and a harsh one (unfriendly or
 * bold, and not attached to it) shouts it out and pushes; the sick one first stares, then pleads (meek, attached) or protests (bold, cold), and
 * walks off with looks back the more attached it is, saying goodbye at the last; a young it leaves behind cries after it. Once
 * outside, the exile grieves its lost home (mind/EmotionCourse) before it comes back to ask again.
 */
public final class MoldExile extends Goal implements MotorOwner {
    private static final int SIGHT = 6;
    private static final int REACTION = 60;
    /** Witness beats: the start at the sight, then the warning, then (harsh ones) the scorn and the push. */
    private static final int NOTICE = 30, SCORN_AFTER = 50, WARN_HOLD = 80, SCORN_HOLD = 150;
    /** The sick one's stare before it answers. */
    private static final int LISTEN = 30;
    // The walk limit leaves time to answer, look back, turn, try another dry stand and cover the colony return radius at a crawl.
    private static final int WALK_LIMIT = 900;
    private static final int[] EXIT_ANGLES = {0, 45, -45, 90, -90, 135, -135, 180};

    private record Home(GlobalPos range, UUID group, BlockPos den, boolean colony) {
        Vec3 centre() { return Vec3.atBottomCenterOf(range.pos()); }
        int radius() { return colony ? SettlementLife.COLONY_HOME_RANGE : SettlementLife.FAMILY_HOME_RANGE; }
        boolean includes(Vec3 at) { return at.subtract(centre()).horizontalDistanceSqr() <= radius() * radius(); }
    }

    /** The former address is a memory, and confers neither an occupied bed nor membership. */
    private record Exile(Home home, boolean appealed) {}
    private enum Task { WITNESS, LEAVE, VISIT }

    private final YukkuriEntity entity;
    private Exile exile;
    private Task task;
    private YukkuriEntity peer;
    private Home home;
    private Vec3 destination, outward;
    private int ticks, nextExit, arrivedAt;
    private boolean driving, pushed, visitingSick;
    private long nextScan, nextWitness;
    private UUID lastWitnessed;
    private BlockPos failedFrom;
    private UUID failedWitness;
    private int failedStage;
    // The beats of this confrontation, decided at its start. Transient.
    private int noticeEnd, answerTicks, lookBacks, lookBackTicks;
    private boolean fond, harsh, resists;

    public MoldExile(YukkuriEntity entity) {
        this.entity = entity;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    public String activity() {
        if (task == null) return null;
        return switch (task) {
            case WITNESS -> ticks < noticeEnd ? Behaviors.MOLD_NOTICE : fond ? Behaviors.MOLD_SORROW
                    : !driving ? null : harsh && ticks >= noticeEnd + SCORN_AFTER ? Behaviors.MOLD_SCORN : Behaviors.MOLD_WARN;
            case LEAVE -> ticks < LISTEN ? Behaviors.MOLD_NOTICE : ticks < LISTEN + answerTicks ? resists ? Behaviors.MOLD_RESIST : Behaviors.MOLD_PLEAD
                    : lookBackTicks > 0 ? Behaviors.MOLD_LOOK_BACK : Behaviors.MOLD_LEAVE;
            case VISIT -> visitingSick && arrivedAt > 0 ? Behaviors.MOLD_PLEAD : null;
        };
    }
    @Override public String ownerId() { return "mold_exile"; }
    @Override public boolean active() { return task != null; }
    private ServerLevel level() { return (ServerLevel)entity.level(); }

    private Home currentHome() {
        var group = entity.settlement().group();
        var den = entity.habitat().shelter();
        if (group != null && group.kind == SettlementData.Kind.FOREST)
            return new Home(GlobalPos.of(level().dimension(), group.center), group.id, den == null ? null : den.getBlockPos(), true);
        if (den != null && den.owns(entity.getUUID()) && (group == null || group.kind == SettlementData.Kind.FAMILY))
            return new Home(GlobalPos.of(level().dimension(), den.entrance()), group == null ? null : group.id, den.getBlockPos(), false);
        return null;
    }

    private boolean local(Home address) { return address.range().dimension().equals(entity.level().dimension()); }

    private static boolean resident(YukkuriEntity observer, Home address) {
        if (address.colony()) return address.group().equals(observer.settlement().groupId()) && SettlementSociety.accepted(observer);
        var den = observer.habitat().shelter();
        return den != null && address.den().equals(den.getBlockPos()) && den.owns(observer.getUUID());
    }

    private static Home encounterHome(YukkuriEntity subject) {
        var goal = subject.moldExile();
        if (goal.task == Task.VISIT && goal.visitingSick) return goal.home;
        return goal.currentHome();
    }

    private static boolean sees(YukkuriEntity observer, YukkuriEntity subject) {
        return observer.distanceToSqr(subject) <= SIGHT * SIGHT && observer.hasLineOfSight(subject);
    }

    /** Stage and proximity increase revulsion. Familiarity and the individual's attachment to kin oppose it. */
    private static double aversion(YukkuriEntity observer, YukkuriEntity subject) {
        var personality = observer.life().personality();
        int bond = YukkuriWorldData.get((ServerLevel)observer.level()).affinity(observer.getUUID(), subject.getUUID());
        double closeness = 1 - observer.distanceTo(subject) / SIGHT;
        int visibleSeverity = switch (BodySigns.moldTier(subject.body().mold())) {
            case 1 -> 2;
            case 2 -> 5;
            case 3 -> 7;
            case 4 -> 8;
            default -> 0;
        };
        double ties = Bonds.careOrMate(observer, subject) ? 12 + .55 * personality.attachment() : 0;
        return 9 * visibleSeverity + 12 * closeness + .18 * (100 - personality.friendliness())
                + .12 * (100 - personality.boldness()) - 38 - .30 * bond - ties;
    }

    public static boolean avoids(YukkuriEntity observer, YukkuriEntity subject) {
        if (observer == subject || observer.level().isClientSide || subject.body().mold() == 0 || !sees(observer, subject)) return false;
        var address = encounterHome(subject);
        return address != null && resident(observer, address) && aversion(observer, subject) > 0;
    }

    public boolean allowsAddress(BlockPos pos) {
        return exile == null || entity.body().mold() == 0 || !local(exile.home())
                || !pos.equals(exile.home().den()) && !exile.home().includes(Vec3.atBottomCenterOf(pos));
    }

    public boolean eligibleForWelcome(YukkuriEntity resident) {
        return exile != null && exile.home().colony() && entity.body().mold() == 0 && local(exile.home())
                && !entity.settlement().settlementMember() && resident(resident, exile.home());
    }

    public void welcomed(YukkuriEntity resident) {
        if (exile != null && exile.home().colony()) readmitted(resident.getUUID());
    }

    public YukkuriEntity reunionPeer() {
        if (exile == null || !exile.home().colony() || entity.body().mold() > 0 || !local(exile.home())) return null;
        return nearby().stream().filter(y -> sees(entity, y) && y.settlement().society().canWelcome(entity))
                .min(Comparator.comparingDouble(entity::distanceToSqr)).orElse(null);
    }

    void homeAccepted(ShelterBlockEntity den) {
        if (exile == null || exile.home().colony() || !local(exile.home()) || !den.getBlockPos().equals(exile.home().den())) return;
        var group = SettlementData.get(level()).group(exile.home().group());
        if (group != null && den.residentIds().stream().anyMatch(id -> !id.equals(entity.getUUID()) && group.role(id) != SettlementData.Role.FREE))
            entity.settlement().join(group);
        readmitted(den.getBlockPos());
    }

    private void readmitted(Object by) {
        GameplayLog.event(entity, "settlement", "mold exile readmitted group={} den={} by={}", exile.home().group(), exile.home().den(), by);
        exile = null;
    }

    private boolean available() {
        return entity.isAlive() && entity.habitat().wildLiver() && !entity.isNoAi() && !entity.body().dying() && !entity.isPassenger()
                && !entity.isPanicking() && !entity.isInWaterOrBubble() && !entity.isOnFire() && !entity.life().isSleeping()
                && !entity.life().hasUrgentNeed() && entity.life().mealTicks() == 0 && !entity.isCommandPlaying()
                && !entity.operations().active() && !entity.family().prioritizesBirth() && !entity.settlement().underway()
                && !WildEvents.reserved(entity);
    }

    private YukkuriEntity healthyParent(YukkuriEntity child, Home address) {
        var id = entity.getUUID().equals(child.family().mother()) ? child.family().father() : child.family().mother();
        if (id != null && level().getEntity(id) instanceof YukkuriEntity other && other.isAlive() && !other.body().dying()
                && other.body().mold() == 0 && resident(other, address) && Bonds.parentOf(other, child)
                && child.distanceToSqr(other) <= 12 * 12 && (child.getVehicle() == other || !child.isPassenger())) return other;
        return null;
    }

    private List<YukkuriEntity> dependants() {
        return level().getEntitiesOfClass(YukkuriEntity.class, entity.getBoundingBox().inflate(24),
                child -> child.isAlive() && child.childhood().shouldStayWithParents() && Bonds.parentOf(entity, child));
    }

    private boolean canLeave(Home address) {
        if (entity.isVehicle() || !entity.family().adult() && entity.childhood().shouldStayWithParents()) return false;
        return dependants().stream().allMatch(child -> healthyParent(child, address) != null);
    }

    /** Both ordinary follow and land care prefer a healthy resident parent while one parent is being driven off. */
    public boolean leavesCareToResident(YukkuriEntity child) {
        if (entity.body().mold() == 0) return false;
        var address = exile != null ? exile.home() : task == Task.LEAVE ? home : null;
        return address != null && local(address) && child.childhood().shouldStayWithParents() && healthyParent(child, address) != null;
    }

    @Override public boolean canUse() {
        long now = level().getGameTime();
        if (now < nextScan || !available() || !entity.onGround()) return false;
        nextScan = now + 12;
        if (entity.body().mold() != 0) {
            Home address = exile == null ? currentHome() : null;
            if (address != null && beginLeave(address)) return true;
        } else if (entity.family().adult() && currentHome() != null) {
            var observed = nearby().stream().filter(this::canNotice).max(Comparator.comparingDouble(y -> aversion(entity, y))).orElse(null);
            if (observed != null) {
                task = Task.WITNESS;
                peer = observed;
                home = encounterHome(observed);
                driving = aversion(entity, observed) > 0;
                return true;
            }
        }
        return beginVisit();
    }

    private List<YukkuriEntity> nearby() {
        return level().getEntitiesOfClass(YukkuriEntity.class, entity.getBoundingBox().inflate(SIGHT),
                y -> y != entity && y.isAlive() && !y.body().dying());
    }

    private boolean canNotice(YukkuriEntity subject) {
        int mold = subject.body().mold();
        if (mold == 0 || !sees(entity, subject)) return false;
        var address = encounterHome(subject);
        if (address == null || !resident(entity, address) || !address.includes(subject.position())) return false;
        if (level().getGameTime() < nextWitness && subject.getUUID().equals(lastWitnessed)
                && subject.moldExile().task != Task.VISIT) return false;
        return aversion(entity, subject) > 0 || BodySigns.moldTier(mold) >= 2 && Bonds.careOrMate(entity, subject);
    }

    private YukkuriEntity driver() {
        return nearby().stream().filter(y -> y.moldExile().task == Task.WITNESS && y.moldExile().driving
                && y.moldExile().peer == entity && sees(y, entity))
                .max(Comparator.comparingDouble(y -> aversion(y, entity))).orElse(null);
    }

    private boolean beginLeave(Home address) {
        var witness = driver();
        if (witness == null || failed(witness.getUUID())) return false;
        if (!canLeave(address)) {
            GameplayLog.limited(entity, "settlement", "mold_exile.care", 200,
                    "mold exile deferred reason=dependent_or_carry witness={} den={}", witness.getUUID(), address.den());
            return false;
        }
        task = Task.LEAVE;
        peer = witness;
        home = address;
        return true;
    }

    private boolean beginVisit() {
        if (exile == null || !local(exile.home()) || failed(null)) return false;
        var address = exile.home();
        boolean sick = entity.body().mold() > 0;
        // Fresh from the exile it first sits out the worst of its grief; only then does it come back to ask.
        if (sick && (exile.appealed() || entity.mind().feeling().stricken()) || !level().hasChunkAt(address.range().pos())) return false;
        if (!sick && (address.colony() ? reunionPeer() != null : !denStillPresent(address))) return false;
        if (entity.distanceToSqr(address.centre()) > 32 * 32) return false;
        task = Task.VISIT;
        home = address;
        visitingSick = sick;
        return true;
    }

    private boolean denStillPresent(Home address) {
        return ShelterBlock.shelter(level(), address.den()) != null;
    }

    private boolean failed(UUID witness) {
        return failedFrom != null && failedFrom.distSqr(entity.blockPosition()) < 4 && Objects.equals(failedWitness, witness)
                && failedStage == entity.body().mold();
    }

    @Override public void start() {
        ticks = nextExit = arrivedAt = 0;
        pushed = false;
        lookBackTicks = 0;
        var personality = entity.life().personality();
        if (task == Task.WITNESS) {
            // A sight already known to it (the same sick one coming back) skips the start and goes straight to the warning.
            boolean known = peer.getUUID().equals(lastWitnessed) && level().getGameTime() < nextWitness + 1200;
            lastWitnessed = peer.getUUID();
            nextWitness = level().getGameTime() + 200;
            noticeEnd = known ? 0 : NOTICE;
            fond = Bonds.careOrMate(entity, peer) && personality.attachment() >= 55;
            // Harsh: unfriendly or bold, and not held back by real attachment to this one (a cold mate can be harsh too).
            harsh = driving && (personality.friendliness() < 40 || personality.boldness() >= 65)
                    && !(Bonds.careOrMate(entity, peer) && personality.attachment() >= 40);
            if (!known) entity.showReaction("surprise", "mold_notice", NOTICE);
            else answer();
            GameplayLog.event(entity, "settlement", "mold witnessed subject={} stage={} distance={} aversion={} response={} fond={} harsh={}",
                    peer.getUUID(), peer.body().mold(), entity.distanceTo(peer), Math.round(aversion(entity, peer)), driving ? "drive" : "stay", fond, harsh);
            // A fond kin feels the loss while it still watches: a light grief of its own.
            if (fond) entity.mind().feeling().begin(EmotionCourse.Kind.GRIEF, .3f, peer.getUUID(), peer.blockPosition());
        } else if (task == Task.LEAVE) {
            // Pleads when meek or attached, protests when bold and cold; looks back on the way out the more attached it is.
            resists = personality.boldness() >= 60 && personality.friendliness() < 60;
            answerTicks = resists ? 50 + personality.boldness() / 3 : 50 + personality.attachment() / 2;
            lookBacks = personality.attachment() >= 70 ? 2 : personality.attachment() >= 35 ? 1 : 0;
            entity.showReaction("surprise", "", LISTEN);
            outward = entity.position().subtract(home.centre()).multiply(1, 0, 1).normalize();
            if (outward.lengthSqr() == 0 && peer != null) outward = entity.position().subtract(peer.position()).multiply(1, 0, 1).normalize();
            if (outward.lengthSqr() == 0) outward = new Vec3(0, 0, 1);
            if (peer != null) GameplayLog.event(entity, "settlement", "mold exile {} witness={} stage={} group={} den={} answer={} lookBacks={}",
                    exile == null ? "protest" : "refused return", peer.getUUID(), entity.body().mold(), home.group(), home.den(),
                    resists ? "resist" : "plead", lookBacks);
        } else {
            destination = SurfaceApproach.floor(entity, home.centre());
            if (visitingSick) {
                entity.showReaction("teary", "", REACTION);
                GameplayLog.event(entity, "settlement", "mold exile appeals group={} den={} stage={}", home.group(), home.den(), entity.body().mold());
            }
        }
    }

    @Override public boolean canContinueToUse() {
        if (task == null || !available()) return false;
        return switch (task) {
            case WITNESS -> ticks < noticeEnd + (harsh ? SCORN_HOLD : WARN_HOLD) && peer.isAlive() && peer.body().mold() > 0 && sees(entity, peer);
            case LEAVE -> entity.body().mold() > 0 && (exile != null || home.equals(currentHome()));
            case VISIT -> exile != null && visitingSick == (entity.body().mold() > 0);
        };
    }

    @Override public boolean requiresUpdateEveryTick() { return true; }

    @Override public void tick() {
        // A terminal tick can end this task before the selector clears its running wrapper.
        if (!canContinueToUse()) return;
        ticks++;
        switch (task) {
            case WITNESS -> watch();
            case LEAVE -> leave();
            case VISIT -> visit();
        }
    }

    private void watch() {
        entity.getLookControl().setLookAt(peer, 30, 30);
        if (ticks == noticeEnd && noticeEnd > 0) answer();
        if (harsh && ticks == noticeEnd + SCORN_AFTER) entity.showReaction("angry", "mold_scorn", 50);
        if (!driving || pushed && !(entity.motor().current() instanceof Push)) return;
        if (!canPush()) {
            if (pushed) { entity.motor().cancel(this, "contact_ended"); return; }
            // A harsh one, having shouted, goes right up to shove it out; the others push only one already within reach.
            // It comes up on the home side of the sick one, so its shove points out of the home.
            if (harsh && ticks > noticeEnd + SCORN_AFTER) {
                var inward = home.centre().subtract(peer.position()).multiply(1, 0, 1);
                if (inward.lengthSqr() < 1e-4) inward = entity.position().subtract(peer.position()).multiply(1, 0, 1);
                var aim = peer.position().add(inward.normalize().scale((entity.getBbWidth() + peer.getBbWidth()) * .5 + .1));
                var stand = SurfaceApproach.floor(entity, aim);
                if (stand != null) SurfaceApproach.walkAround(entity, stand, peer, 1);
            }
            return;
        }
        if (!pushed) {
            pushed = true;
            SurfaceApproach.stopWalking(entity);
            entity.motor().run(this, new Push(peer));
            GameplayLog.event(entity, "settlement", "mold exile push subject={} harsh={}", peer.getUUID(), harsh);
        }
    }

    /** The witness's answer to what it saw: a fond kin's sobbed apology, a driver's warning, or a wary watch. */
    private void answer() {
        if (fond) entity.showReaction("puff_teary", "mold_sorrow", WARN_HOLD);
        else if (driving) entity.showReaction("puff", "mold_warn", 50);
        else entity.showReaction("teary", "", 40);
    }

    private boolean canPush() {
        if (!peer.onGround() || peer.isPassenger() || peer.isVehicle()
                || ContactGeometry.mouthGap(entity, peer) > ContactGeometry.GRAB_MARGIN
                || !pushed && !peer.moldExile().canLeave(home)) return false;
        Vec3 away = peer.position().subtract(entity.position()).multiply(1, 0, 1).normalize();
        Vec3 at = peer.position().add(away.scale(peer.getBbWidth()));
        return away.dot(peer.position().subtract(home.centre())) > 0 && SurfaceApproach.stands(peer, at)
                && SurfaceApproach.dryStep(peer, peer.position(), at);
    }

    private void leave() {
        // It stares at the one driving it, then pleads or protests to its face; only then does it turn to go.
        if (ticks < LISTEN + answerTicks) {
            if (peer != null) entity.getLookControl().setLookAt(peer, 30, 30);
            if (ticks == LISTEN) entity.showReaction(resists ? "angry" : "teary", resists ? "mold_resist" : "mold_plea", answerTicks);
            return;
        }
        if (ticks == LISTEN + answerTicks) turnAway();
        if (lookBackTicks > 0) {
            SurfaceApproach.stopWalking(entity);
            SurfaceApproach.face(entity, SurfaceApproach.yaw(entity.position(), home.centre()));
            lookBackTicks--;
            return;
        }
        // Looks back at about half way and near the edge of the old home's range (attached ones), the goodbye at the last.
        double out = Math.sqrt(entity.position().subtract(home.centre()).horizontalDistanceSqr()) / home.radius();
        if (lookBacks > 0 && out >= (lookBacks == 2 ? .45 : .8)) {
            lookBacks--;
            lookBackTicks = 40 + entity.life().personality().attachment() / 4;
            SurfaceApproach.stopWalking(entity);
            if (lookBacks == 0) entity.showReaction("teary", "mold_leave", lookBackTicks);
            else entity.showReaction("teary", "", lookBackTicks);
            GameplayLog.event(entity, "settlement", "mold exile looked back left={} out={}", lookBacks, Math.round(out * 100) / 100.0);
            return;
        }
        if (!home.includes(entity.position()) && entity.onGround() && SurfaceApproach.stands(entity, entity.position())) {
            if (exile == null && !canLeave(home)) { fail("dependent_needs_care"); return; }
            completeLeave();
            return;
        }
        if (ticks >= WALK_LIMIT) { fail("walk_limit"); return; }
        if (destination == null && !nextRetreat()) { fail("no_dry_exit"); return; }
        var outcome = SurfaceApproach.walkResult(entity, destination, null, 1);
        if (outcome == SurfaceApproach.Result.BLOCKED || outcome == SurfaceApproach.Result.ARRIVED) destination = null;
    }

    /** It gives in and turns to go: hurt, and a young it must leave behind cries after it and is left grieving. */
    private void turnAway() {
        entity.mind().felt(EmotionEvent.SOCIAL_HURT);
        entity.showReaction("cry", exile == null ? "settlement_hurt" : "", 40);
        if (exile != null) return;
        for (var child : dependants()) {
            if (!child.hasLineOfSight(entity) || child.distanceToSqr(entity) > 12 * 12) continue;
            child.showReactionDelayed("cry", "mold_child_cry", 80, 20);
            child.mind().feeling().begin(EmotionCourse.Kind.GRIEF, .5f, entity.getUUID(), entity.blockPosition());
            GameplayLog.event(child, "family", "mold exile child cries parent={}", entity.getUUID());
        }
    }

    private boolean nextRetreat() {
        while (nextExit < EXIT_ANGLES.length) {
            float turn = (float)Math.toRadians(EXIT_ANGLES[nextExit++]);
            var aim = home.centre().add(outward.yRot(turn).scale(home.radius() + 2));
            if (!level().hasChunkAt(BlockPos.containing(aim))) continue;
            var stand = SurfaceApproach.floor(entity, aim);
            if (stand == null || home.includes(stand) || !SurfaceApproach.hasDryBankBuffer(entity, stand, entity.getMaxFallDistance())
                    || !SurfaceApproach.canApproach(entity, stand)) continue;
            destination = stand;
            return true;
        }
        return false;
    }

    private void completeLeave() {
        if (exile == null) {
            for (var child : dependants()) {
                var parent = healthyParent(child, home);
                GameplayLog.event(entity, "family", "mold exile child stays child={} healthy_parent={} child_home={}",
                        child.getUUID(), parent.getUUID(), child.life().homeStand());
            }
            exile = new Exile(home, false);
            entity.habitat().release();
            entity.life().releaseHome();
            entity.settlement().exile();
            GameplayLog.event(entity, "settlement", "mold exile departed scope={} group={} den={} at={} witness={} stage={}",
                    home.colony() ? "colony" : "family", home.group(), home.den(), entity.blockPosition(), peer.getUUID(), entity.body().mold());
        } else {
            exile = new Exile(home, true);
            GameplayLog.event(entity, "settlement", "mold exile withdrew {} witness={} stage={}",
                    peer == null ? "without answer" : "after refusal", peer == null ? null : peer.getUUID(), entity.body().mold());
        }
        // Outside, it grieves the home and family it lost (less after a second refusal) before it looks for another.
        entity.mind().feeling().begin(EmotionCourse.Kind.GRIEF, exile.appealed() ? .3f : .5f, null, BlockPos.containing(home.centre()));
        entity.habitat().seekHomeNow();
        end();
    }

    private void visit() {
        if (visitingSick && beginLeave(home)) { SurfaceApproach.stopWalking(entity); destination = null; start(); return; }
        if (!visitingSick && home.colony() && reunionPeer() != null) {
            end();
            return;
        }
        if (ticks >= WALK_LIMIT || destination == null) { fail("visit_unreachable"); return; }
        var outcome = SurfaceApproach.walkResult(entity, destination, null, 1);
        if (outcome == SurfaceApproach.Result.BLOCKED) { fail("visit_blocked"); return; }
        if (outcome != SurfaceApproach.Result.ARRIVED) return;
        if (!visitingSick) {
            var den = home.colony() ? null : ShelterBlock.shelter(level(), home.den());
            if (den != null && entity.habitat().choose(den)) end();
            else fail("no_admission");
            return;
        }
        // Give a resident a chance to see the appeal. An empty address still has a way back out.
        if (arrivedAt == 0) { arrivedAt = ticks; entity.showReaction("teary", "mold_plea", REACTION); }
        if (ticks - arrivedAt >= REACTION) {
            task = Task.LEAVE;
            destination = null;
            start();
        }
    }

    private void fail(String reason) {
        failedFrom = entity.blockPosition();
        failedWitness = task == Task.LEAVE && peer != null ? peer.getUUID() : null;
        failedStage = entity.body().mold();
        GameplayLog.limited(entity, "settlement", "mold_exile." + reason, 200,
                "mold exile interrupted task={} reason={} group={} den={}", task, reason, home.group(), home.den());
        end();
    }

    @Override public void stop() {
        if (task == Task.LEAVE || task == Task.VISIT)
            GameplayLog.limited(entity, "settlement", "mold_exile.preempted", 200,
                    "mold exile interrupted task={} reason=preempted group={} den={}", task, home.group(), home.den());
        end();
    }

    private void end() {
        if (task == null) return;
        entity.motor().cancel(this, "mold_exile_end");
        SurfaceApproach.stopWalking(entity);
        task = null;
        peer = null;
        home = null;
        destination = null;
    }

    void save(CompoundTag tag) {
        if (exile == null) return;
        var memory = new CompoundTag();
        var address = exile.home();
        FamilyData.putPosition(memory, "Range", address.range());
        if (address.group() != null) memory.putUUID("Group", address.group());
        if (address.den() != null) memory.putLong("Den", address.den().asLong());
        memory.putBoolean("Colony", address.colony());
        memory.putBoolean("Appealed", exile.appealed());
        tag.put("MoldExile", memory);
    }

    void load(CompoundTag tag) {
        var memory = tag.getCompound("MoldExile");
        var range = FamilyData.position(memory, "Range");
        exile = range == null ? null : new Exile(new Home(range, memory.hasUUID("Group") ? memory.getUUID("Group") : null,
                memory.contains("Den") ? BlockPos.of(memory.getLong("Den")) : null, memory.getBoolean("Colony")), memory.getBoolean("Appealed"));
    }
}
