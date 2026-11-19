package io.github.longshou.mcyukkuri.pet.rules;

import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.human.Custody;
import io.github.longshou.mcyukkuri.life.BehaviorContinuity;
import io.github.longshou.mcyukkuri.life.Duty;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.pet.PetScenes;
import io.github.longshou.mcyukkuri.pet.PetPrivileges;
import io.github.longshou.mcyukkuri.social.Temperament.Gate;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.status.Identity;
import java.util.Comparator;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/** The two rules goals share the current walk. Each goal drives and stops only its own body. */
final class Elopement {
    private static final int RETRY_TICKS = 24000;
    private static final double DEPARTURE_STAND_DISTANCE = 24 + SurfaceApproach.ARRIVAL;
    private final YukkuriEntity body;
    private Walk walk;
    private long nextCheck;
    private Failure lastFailure;

    Elopement(YukkuriEntity body) { this.body = body; }
    boolean active() { return walk != null; }
    private static Elopement behavior(YukkuriEntity y) { return y.pet().conscience().departure; }

    private static boolean mobile(YukkuriEntity y) {
        return !BehaviorContinuity.emergency(y) && y.hurtTime == 0 && !y.isNoAi() && !y.isPassenger()
                && !y.isVehicle() && !y.isLeashed() && !y.pet().busy() && !y.operations().active()
                && !y.life().sedated() && !y.life().isSleeping() && !y.life().protectsStep()
                && !y.life().hasUrgentNeed() && !y.house().active() && y.family().yieldsToComfort();
    }

    private static boolean needsCare(YukkuriEntity y) {
        return y.family().hasDependentYoung() || FamilyData.get((ServerLevel) y.level()).busyParent(y.getUUID());
    }

    private static boolean ownerNear(YukkuriEntity pet) {
        var owner = pet.pet().presentOwner();
        return owner != null && pet.distanceToSqr(owner) <= 16 && pet.hasLineOfSight(owner);
    }

    boolean choose() {
        long now = body.level().getGameTime();
        if (now < nextCheck || !body.family().adult()) return false;
        var identity = body.status().identity();
        if (identity.wild()) {
            // Notice a neighbour actually looking back to leave, within one look-around clip.
            nextCheck = now + 40;
            for (var other : body.level().getEntitiesOfClass(YukkuriEntity.class, body.getBoundingBox().inflate(16))) {
                var leaving = behavior(other).walk;
                if (leaving != null && leaving.wild == body && leaving.running() && body.hasLineOfSight(other)) {
                    walk = leaving;
                    return true;
                }
            }
            return false;
        }
        if (identity != Identity.PET || body.family().pentUp() < 70) return false;
        // A passing partner can appear between minute-long decisions. Only an actual opportunity spends that draw.
        nextCheck = now + 40;
        if (Custody.view(body).ownerKind() != Custody.OwnerKind.PLAYER || ownerNear(body) || !mobile(body) || needsCare(body)) return false;
        var home = body.life().homeStand();
        var yard = home == null ? body.position() : Vec3.atBottomCenterOf(home);
        if (horizontalSq(body.position(), yard) > 24 * 24) return false;
        var bonds = YukkuriWorldData.get((ServerLevel) body.level());
        var owner = body.pet().owner();
        var candidates = body.level().getEntitiesOfClass(YukkuriEntity.class, body.getBoundingBox().inflate(16),
                y -> y != body && body.distanceToSqr(y) <= 256 && horizontalSq(y.position(), yard) <= 24 * 24
                        && RulesMath.drawnTo(y.status().identity(),
                        bonds.affinity(body.getUUID(), y.getUUID()), bonds.affinity(y.getUUID(), body.getUUID()),
                        body.life().personality().trust(owner), body.pet().neglect())
                        && body.hasLineOfSight(y) && body.family().compatibleWith(y) && mobile(y) && !needsCare(y)
                        && !y.duty().claimed() && y.canStartConscience()
                        && FamilyData.get((ServerLevel) body.level()).canPair(body, y, true));
        candidates.sort(Comparator.comparingInt((YukkuriEntity y) -> bonds.affinity(body.getUUID(), y.getUUID())).reversed());
        for (var wild : candidates) {
            var wildStand = destination(wild, yard);
            if (wildStand == null) continue;
            var petStand = SurfaceApproach.beside(body, wildStand, 2, at -> beyondYard(at, yard)
                    && horizontalSq(at, body.position()) > 4 * 4);
            if (petStand == null || lastFailure != null && lastFailure.equals(failure(wild, petStand, wildStand))) continue;
            nextCheck = now + 1200;
            double chance = RulesMath.departureChance(body.family().pentUp(), body.pet().obedience());
            boolean leaving = body.getRandom().nextDouble() < chance;
            GameplayLog.event(body, "rules", "departure opportunity partner={} chance={} leaving={}", wild.getUUID(), chance, leaving);
            if (!leaving) return false;
            var rule = home == null || horizontalSq(body.position(), yard) > 64 ? RulesMath.Choice.ALLOW
                    : body.pet().conscience().tempted(HouseRule.NO_LEAVING_YARD, body.family().pentUp() / 100, 1);
            if (rule == RulesMath.Choice.HOLD || rule == RulesMath.Choice.WAIT) {
                PetScenes.act(body, YukkuriAction.LOOK_AROUND, "teary", "", YukkuriAction.LOOK_AROUND.durationTicks());
                lastFailure = failure(wild, petStand, wildStand);
                return false;
            }
            walk = new Walk(body, wild, owner, yard, petStand, wildStand, rule);
            return true;
        }
        if (!candidates.isEmpty()) nextCheck = now + 1200;
        return false;
    }

    private Vec3 destination(YukkuriEntity wild, Vec3 yard) {
        var home = wild.life().homeStand();
        var toward = home == null ? wild.position() : Vec3.atBottomCenterOf(home);
        if (horizontalSq(toward, yard) < 27 * 27) {
            var direction = wild.position().subtract(yard).multiply(1, 0, 1).normalize();
            toward = yard.add(direction.scale(28));
        }
        // Leave room for a second body beside the destination. The wild animal must travel there too.
        return SurfaceApproach.beside(wild, toward, 2, at -> beyondYard(at, yard)
                && horizontalSq(at, wild.position()) > 4 * 4);
    }

    private static double horizontalSq(Vec3 a, Vec3 b) { return a.subtract(b).horizontalDistanceSqr(); }
    private static boolean beyondYard(Vec3 stand, Vec3 yard) {
        return horizontalSq(stand, yard) > DEPARTURE_STAND_DISTANCE * DEPARTURE_STAND_DISTANCE;
    }

    private record Failure(UUID wild, BlockPos petStand, BlockPos wildStand, int route, int conditions) {}
    private Failure failure(YukkuriEntity wild, Vec3 petStand, Vec3 wildStand) {
        var petEnd = BlockPos.containing(petStand);
        var wildEnd = BlockPos.containing(wildStand);
        var rules = body.pet().conscience();
        int conditions = (ownerNear(body) ? 1 : 0) | (HumanSight.watching(body) ? 2 : 0)
                | (rules.binds(HouseRule.NO_LEAVING_YARD) ? 4 : 0) | (mobile(body) && mobile(wild) ? 8 : 0)
                | (body.level() == wild.level() && body.distanceToSqr(wild) <= 256 && body.hasLineOfSight(wild) ? 16 : 0)
                | (!needsCare(body) && !needsCare(wild) ? 32 : 0);
        return new Failure(wild.getUUID(), petEnd, wildEnd,
                31 * route(body, petEnd) + route(wild, wildEnd), conditions);
    }
    private static int route(YukkuriEntity y, BlockPos end) {
        var path = y.getNavigation().createPath(end, 1);
        if (path == null || !path.canReach()) return 0;
        int hash = 1;
        for (int i = 0; i < path.getNodeCount(); i++) hash = 31 * hash + path.getNodePos(i).hashCode();
        return hash;
    }

    void start() {
        body.duty().take(Duty.Kind.RULES, 2400, 0, "elope");
        GameplayLog.event(body, "rules", "departure started partner={} from={} to={} yard={}",
                walk.peer(body).getUUID(), body.position(), walk.stand(body), walk.yard);
        SurfaceApproach.halt(body);
        if (body == walk.pet) PetScenes.say(body, YukkuriAction.LOOK_AROUND, "teary", "elope_leave", null);
    }
    boolean continues() { return walk.running() && body.duty().holds(Duty.Kind.RULES); }
    void tick() { if (continues()) walk.tick(body); }
    void interrupt(String reason) { if (active() && walk.running()) walk.end(reason, false); }
    void stop() {
        interrupt("interrupted");
        body.duty().release(Duty.Kind.RULES, walk.arrived ? Duty.End.DONE : Duty.End.FAILED, walk.reason);
        SurfaceApproach.stopWalking(body);
        walk = null;
    }

    void forgetOwner() { lastFailure = null; nextCheck = 0; }
    void save(CompoundTag tag) {
        long after = nextCheck;
        var f = lastFailure;
        // A loaded body starts its ordinary goals again. The interrupted pair must earn another departure.
        if (walk != null && walk.pet == body && walk.running()) {
            f = failure(walk.wild, walk.petStand, walk.wildStand);
            after = body.level().getGameTime() + RETRY_TICKS;
        }
        tag.putLong("NextDepartureCheck", after);
        if (f == null) return;
        var failed = new CompoundTag();
        failed.putUUID("Partner", f.wild); failed.putLong("PetStand", f.petStand.asLong()); failed.putLong("WildStand", f.wildStand.asLong());
        failed.putInt("Route", f.route); failed.putInt("Conditions", f.conditions);
        tag.put("FailedDeparture", failed);
    }
    void load(CompoundTag tag) {
        nextCheck = tag.getLong("NextDepartureCheck");
        var f = tag.getCompound("FailedDeparture");
        if (!f.hasUUID("Partner")) return;
        lastFailure = new Failure(f.getUUID("Partner"), BlockPos.of(f.getLong("PetStand")), BlockPos.of(f.getLong("WildStand")),
                f.getInt("Route"), f.getInt("Conditions"));
    }

    private static final class Walk {
        final YukkuriEntity pet, wild;
        final UUID owner;
        final Vec3 yard, petStand, wildStand;
        final long start;
        final int looks;
        RulesMath.Choice yardRule;
        String reason;
        boolean arrived;

        Walk(YukkuriEntity pet, YukkuriEntity wild, UUID owner, Vec3 yard, Vec3 petStand, Vec3 wildStand, RulesMath.Choice rule) {
            this.pet = pet; this.wild = wild; this.owner = owner; this.yard = yard;
            this.petStand = petStand; this.wildStand = wildStand; this.yardRule = rule;
            start = pet.level().getGameTime();
            looks = pet.life().personality().temperament().is(Gate.ATTACHED) ? 2 : 1;
        }
        boolean running() { return reason == null; }
        YukkuriEntity peer(YukkuriEntity actor) { return actor == pet ? wild : pet; }
        Vec3 stand(YukkuriEntity actor) { return actor == pet ? petStand : wildStand; }
        void tick(YukkuriEntity actor) {
            long age = pet.level().getGameTime() - start;
            boolean bothAt = SurfaceApproach.at(pet, petStand) && SurfaceApproach.at(wild, wildStand);
            boolean checkFamily = age % 20 == 0 || bothAt;
            String stopped = RulesMath.interrupted(pet.pet().ownedBy(owner) && ownerNear(pet),
                    pet.pet().ownedBy(owner) && wild.status().identity().wild() && pet.level() == wild.level()
                            && pet.distanceToSqr(wild) <= 256 && pet.hasLineOfSight(wild),
                    mobile(pet) && mobile(wild), checkFamily && (needsCare(pet) || needsCare(wild)
                            || !pet.family().compatibleWith(wild) || !FamilyData.get((ServerLevel) pet.level()).canPair(pet, wild, true)));
            if (stopped != null) { end(stopped, false); return; }
            int clip = YukkuriAction.LOOK_AROUND.durationTicks();
            if (actor == pet && age < (long) looks * clip) {
                if (age == clip) PetScenes.act(pet, YukkuriAction.LOOK_AROUND, "teary", "", clip);
                pet.getLookControl().setLookAt(yard.x, pet.getEyeY(), yard.z);
                return;
            }
            if (behavior(wild).walk != this || !wild.duty().holds(Duty.Kind.RULES)) { end("partner_busy", false); return; }
            // Let the slower partner catch up, including when a bend puts the pet in front.
            if (pet.distanceToSqr(wild) > 36 && actor.distanceToSqr(wildStand) < peer(actor).distanceToSqr(wildStand)) {
                SurfaceApproach.halt(actor);
                return;
            }
            if (actor == pet && yardRule == RulesMath.Choice.BREAK && horizontalSq(pet.position(), yard) > 64) {
                pet.pet().conscience().breach(HouseRule.NO_LEAVING_YARD);
                yardRule = RulesMath.Choice.ALLOW;
            }
            if (actor == pet && RulesMath.departed(SurfaceApproach.at(pet, petStand), SurfaceApproach.at(wild, wildStand),
                    horizontalSq(pet.position(), yard), horizontalSq(wild.position(), yard), pet.distanceToSqr(wild))) {
                end("arrived", true);
                PetPrivileges.eloped(pet, owner);
                GameplayLog.event(pet, "rules", "eloped partner={} wildAt={} identity={} token={} serial={}", wild.getUUID(),
                        wild.position(), pet.status().identity().id(), pet.pet().visibleGrade(), pet.pet().serial());
                return;
            }
            if (SurfaceApproach.walkResult(actor, stand(actor), null, .9) == SurfaceApproach.Result.BLOCKED) end("blocked", false);
        }
        void end(String why, boolean success) {
            reason = why; arrived = success;
            if (!success && pet.pet().ownedBy(owner)) {
                var leaving = behavior(pet);
                leaving.lastFailure = leaving.failure(wild, petStand, wildStand);
                leaving.nextCheck = pet.level().getGameTime() + RETRY_TICKS;
            }
            GameplayLog.event(pet, "rules", "departure ended reason={} partner={} petAt={} wildAt={}",
                    why, wild.getUUID(), pet.position(), wild.position());
        }
    }
}
