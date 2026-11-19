package io.github.longshou.mcyukkuri.pet.rules;

import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.family.PentUpMath;
import io.github.longshou.mcyukkuri.house.HouseholdProtection;
import io.github.longshou.mcyukkuri.human.Custody;
import io.github.longshou.mcyukkuri.life.BehaviorContinuity;
import io.github.longshou.mcyukkuri.life.Duty;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.pet.PetPrivileges;
import io.github.longshou.mcyukkuri.pet.PetScenes;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.status.Identity;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/** The visitor calls. The pet's own goal decides when to cross the yard. Each moves only its own body. */
final class Elopement {
    private static final int TRAVEL_TICKS = 2400, RETRY_TICKS = 24000;
    private enum Phase { EDGE, CALL, MEET, TRYST, COURT, RETURN, LEAVE }
    private final YukkuriEntity body;
    private final Map<UUID, Integer> visits = new HashMap<>();
    private Visit visit;
    private long nextCheck, nextPoll;
    private Failure lastFailure;

    Elopement(YukkuriEntity body) { this.body = body; }
    private static Elopement of(YukkuriEntity y) { return y.pet().conscience().departure; }
    private static ServerLevel level(YukkuriEntity y) { return (ServerLevel) y.level(); }
    private static double flat(Vec3 a, Vec3 b) { return a.subtract(b).horizontalDistanceSqr(); }
    private static int affinity(YukkuriEntity from, YukkuriEntity to) {
        return YukkuriWorldData.get(level(from)).affinity(from.getUUID(), to.getUUID());
    }
    private static boolean free(YukkuriEntity y) {
        return !BehaviorContinuity.emergency(y) && y.hurtTime == 0 && !y.isNoAi() && !y.isPassenger()
                && !y.isVehicle() && !y.isLeashed() && !y.pet().busy() && !y.operations().active()
                && !y.life().sedated() && !y.life().isSleeping() && !y.life().protectsStep()
                && !y.life().hasUrgentNeed() && !y.house().active();
    }
    private static boolean needsCare(YukkuriEntity y) {
        return y.family().hasDependentYoung() || FamilyData.get(level(y)).busyParent(y.getUUID());
    }
    private static boolean watched(YukkuriEntity pet, YukkuriEntity subject) {
        var owner = pet.pet().presentOwner();
        return owner != null && HumanSight.sees(owner, subject);
    }
    private int count(UUID wild) { return visits.getOrDefault(wild, 0); }
    private void recordVisits(YukkuriEntity wild, int after) {
        int change = RulesMath.bondChange(count(wild.getUUID()), after);
        if (after == 0) visits.remove(wild.getUUID()); else visits.put(wild.getUUID(), after);
        var relations = YukkuriWorldData.get(level(body));
        relations.relate(body.getUUID(), wild.getUUID(), change);
        relations.relate(wild.getUUID(), body.getUUID(), change);
    }
    private boolean available() {
        return free(body) && body.family().yieldsToComfort() && !needsCare(body) && body.duty().mayTake(Duty.Kind.RULES);
    }
    private Visit caller() {
        for (var y : body.level().getEntitiesOfClass(YukkuriEntity.class, body.getBoundingBox().inflate(32))) {
            var v = of(y).visit;
            if (v != null && v.pet == body && v.wild == y && v.reason == null) return v;
        }
        return null;
    }
    boolean choose() {
        long now = body.level().getGameTime();
        if (now < nextCheck || now < nextPoll || !body.family().adult()) return false;
        nextPoll = now + 20;
        if (body.status().identity() == Identity.PET) {
            if (body.life().homeStand() == null || !available()) return false;
            var v = caller();
            if (v == null || v.phase != Phase.CALL || !body.hasLineOfSight(v.wild)) return false;
            var traits = body.life().personality();
            // An observant pet checks the owner's gaze before committing its one house-rule choice.
            if (now - v.since < traits.temperament().clever() * YukkuriAction.LOOK_AROUND.durationTicks()
                    || traits.temperament().clever() >= .5 && HumanSight.watching(body)) return false;
            if (flat(body.position(), v.yard) > 64 || !RulesMath.receptive(affinity(body, v.wild), body.family().pentUp(), traits.attachment())) {
                v.end("declined"); return false;
            }
            var conscience = body.pet().conscience();
            var rule = conscience.tempted(HouseRule.NO_LEAVING_YARD, body.family().pentUp() / 100,
                    1 - .65 * Math.clamp(affinity(body, v.wild) / 30.0, 0, 1));
            if (rule == RulesMath.Choice.HOLD || rule == RulesMath.Choice.WAIT) {
                if (rule == RulesMath.Choice.HOLD) body.family().heldCourtship(v.wild, rule);
                v.end("stayed_home"); return false;
            }
            visit = v;
            v.phase = Phase.MEET;
            return true;
        }
        if (!body.status().identity().wild() || body.family().pentUp() <= 40 || !available()) return false;
        nextPoll = now + 100;
        var pets = body.level().getEntitiesOfClass(YukkuriEntity.class, body.getBoundingBox().inflate(32),
                p -> p.status().identity() == Identity.PET && p.life().homeStand() != null
                        && Custody.view(p).ownerKind() == Custody.OwnerKind.PLAYER
                        && flat(p.position(), Vec3.atBottomCenterOf(p.life().homeStand())) <= 64
                        && of(p).visit == null && now >= of(p).nextCheck && of(p).available() && p.canJoinDeparture() && of(p).caller() == null
                        && body.hasLineOfSight(p) && !watched(p, body) && body.family().compatibleWith(p)
                        && FamilyData.get(level(body)).canPair(body, p, true)
                        && RulesMath.lures(body.family().pentUp(), 1 - body.life().personality().temperament().timid(),
                                body.life().nutrition(), body.life().energy(), p.life().nutrition(), p.life().hygiene(), affinity(body, p)));
        pets.sort(Comparator.comparingInt((YukkuriEntity p) -> affinity(body, p)).reversed());
        for (var p : pets) {
            var yard = Vec3.atBottomCenterOf(p.life().homeStand());
            var edge = SurfaceApproach.beside(body, yard, 10, at -> flat(at, yard) > 81
                    && !HouseholdProtection.protectedAt(level(body), BlockPos.containing(at)));
            if (edge == null || HouseholdProtection.protectedAt(level(body), p.life().homeStand())) continue;
            visit = new Visit(body, p, yard, edge);
            return true;
        }
        return false;
    }

    boolean trystWith(YukkuriEntity peer) {
        return visit != null && visit.reason == null && (visit.phase == Phase.TRYST || visit.phase == Phase.COURT)
                && visit.peer(body) == peer;
    }
    void start() {
        body.duty().take(Duty.Kind.RULES, TRAVEL_TICKS, 0, "tryst");
        SurfaceApproach.halt(body);
        if (body == visit.wild)
            GameplayLog.event(body, "rules", "tryst approach pet={} from={} edge={} pressure={} boldness={} petNutrition={} petHygiene={}",
                    visit.pet.getUUID(), body.position(), visit.edge, body.family().pentUp(), body.life().personality().boldness(),
                    visit.pet.life().nutrition(), visit.pet.life().hygiene());
    }
    boolean continues() { return visit.reason == null && body.duty().holds(Duty.Kind.RULES); }
    void tick() { if (continues()) visit.tick(body); }
    void stop() {
        if (visit.reason == null) visit.end("interrupted");
        body.duty().release(Duty.Kind.RULES, visit.completed() ? Duty.End.DONE : Duty.End.FAILED, visit.reason);
        SurfaceApproach.stopWalking(body);
        visit = null;
    }

    void counter(UUID actor) {
        Visit v = visit;
        if (v == null && body.status().identity() == Identity.PET && body.pet().ownedBy(actor)) v = caller();
        if (v != null && v.reason == null && RulesMath.witnessedCounter(v.owner.equals(actor), watched(v.pet, v.pet), watched(v.pet, v.wild)))
            v.chase("owner_intervened", v.pet.pet().presentOwner());
    }
    void sprayedBy(LivingEntity sprayer) {
        if (visit != null && visit.reason == null && visit.wild == body) visit.chase("repellent", sprayer);
    }

    private record Failure(UUID wild, BlockPos petStand, BlockPos wildStand, int route, int conditions) {}
    private static int route(YukkuriEntity y, BlockPos end) {
        var path = y.getNavigation().createPath(end, 1);
        if (path == null || !path.canReach()) return 0;
        int hash = 1;
        for (int i = 0; i < path.getNodeCount(); i++) hash = 31 * hash + path.getNodePos(i).hashCode();
        return hash;
    }
    void forgetOwner() { lastFailure = null; nextCheck = 0; }
    void save(CompoundTag tag) {
        var history = new ListTag();
        visits.forEach((id, n) -> {
            var entry = new CompoundTag(); entry.putUUID("Partner", id); entry.putInt("Returns", n); history.add(entry);
        });
        if (!history.isEmpty()) tag.put("Trysts", history);
        var f = lastFailure;
        long after = nextCheck;
        if (visit != null && visit.reason == null) {
            after = body.level().getGameTime() + (visit.phase == Phase.LEAVE ? RETRY_TICKS : RulesMath.nextVisitTicks(visit.wild.life().personality().temperament().timid()));
            if (body == visit.pet && visit.phase == Phase.LEAVE) f = visit.failure();
        }
        tag.putLong("NextDepartureCheck", after);
        if (f == null) return;
        var failed = new CompoundTag();
        failed.putUUID("Partner", f.wild); failed.putLong("PetStand", f.petStand.asLong()); failed.putLong("WildStand", f.wildStand.asLong());
        failed.putInt("Route", f.route); failed.putInt("Conditions", f.conditions);
        tag.put("FailedDeparture", failed);
    }
    void load(CompoundTag tag) {
        visits.clear();
        for (var element : tag.getList("Trysts", Tag.TAG_COMPOUND)) {
            var entry = (CompoundTag) element; visits.put(entry.getUUID("Partner"), entry.getInt("Returns"));
        }
        nextCheck = tag.getLong("NextDepartureCheck");
        var f = tag.getCompound("FailedDeparture");
        if (f.hasUUID("Partner")) lastFailure = new Failure(f.getUUID("Partner"), BlockPos.of(f.getLong("PetStand")),
                BlockPos.of(f.getLong("WildStand")), f.getInt("Route"), f.getInt("Conditions"));
    }

    private static final class Visit {
        final YukkuriEntity wild, pet;
        final UUID owner;
        final Vec3 yard, edge;
        Vec3 petStand, wildStand;
        Phase phase = Phase.EDGE;
        long since;
        long crossedAt = -1;
        String reason;

        Visit(YukkuriEntity wild, YukkuriEntity pet, Vec3 yard, Vec3 edge) {
            this.wild = wild; this.pet = pet; this.yard = yard; this.edge = edge;
            owner = pet.pet().owner();
        }
        YukkuriEntity peer(YukkuriEntity y) { return y == pet ? wild : pet; }
        boolean completed() { return "returned".equals(reason) || "arrived".equals(reason); }
        Failure failure() {
            var p = BlockPos.containing(petStand); var w = BlockPos.containing(wildStand);
            int conditions = (HumanSight.watching(pet) ? 2 : 0) | (pet.pet().conscience().binds(HouseRule.NO_LEAVING_YARD) ? 4 : 0)
                    | (free(pet) && free(wild) ? 8 : 0) | (wild.hasLineOfSight(pet) ? 16 : 0)
                    | (!needsCare(pet) && !needsCare(wild) ? 32 : 0);
            return new Failure(wild.getUUID(), p, w, 31 * route(pet, p) + route(wild, w), conditions);
        }
        void end(String why) {
            reason = why;
            if (phase == Phase.COURT) { wild.family().interruptCourtship(); pet.family().interruptCourtship(); }
            var memory = of(pet);
            boolean failedDeparture = phase == Phase.LEAVE && !completed();
            if (failedDeparture) memory.lastFailure = failure();
            long delay = failedDeparture ? RETRY_TICKS : RulesMath.nextVisitTicks(wild.life().personality().temperament().timid());
            memory.nextCheck = of(wild).nextCheck = pet.level().getGameTime() + delay;
            GameplayLog.event(pet, "rules", "departure ended reason={} partner={} petAt={} wildAt={}", why, wild.getUUID(), pet.position(), wild.position());
        }
        void chase(String why, LivingEntity person) {
            var memory = of(pet);
            int before = memory.count(wild.getUUID()), after = RulesMath.visitsAfterCounter(before);
            int bond = affinity(pet, wild), reciprocal = affinity(wild, pet);
            if (after != before) memory.recordVisits(wild, after);
            end(why);
            GameplayLog.event(wild, "rules", "tryst chased pet={} reason={} returns={} -> {} bond={} -> {} reciprocal={} -> {}",
                    pet.getUUID(), why, before, after, bond, affinity(pet, wild), reciprocal, affinity(wild, pet));
            if (person != null) wild.fleeFrom(person);
        }
        void move(YukkuriEntity actor, Vec3 at, YukkuriEntity target) {
            if (at == null || SurfaceApproach.walkResult(actor, at, target, .9) == SurfaceApproach.Result.BLOCKED) end("blocked");
        }
        boolean familyBlocks() {
            return needsCare(pet) || needsCare(wild) || !pet.family().compatibleWith(wild)
                    || !FamilyData.get(level(pet)).canPair(pet, wild, true);
        }
        void tick(YukkuriEntity actor) {
            long now = actor.level().getGameTime();
            boolean returning = phase == Phase.RETURN;
            boolean familyCheck = now % 20 == 0;
            var person = pet.pet().presentOwner();
            boolean intervened = phase == Phase.LEAVE && person != null && pet.distanceToSqr(person) <= 16 && HumanSight.sees(person, pet);
            String stopped = RulesMath.interrupted(intervened,
                    pet.isAlive() && wild.isAlive() && pet.pet().ownedBy(owner) && wild.status().identity().wild()
                            && pet.level() == wild.level() && (returning || pet.distanceToSqr(wild) <= (phase == Phase.EDGE ? 32*32 : 20*20)),
                    free(actor) && (phase == Phase.COURT || actor.family().yieldsToComfort()),
                    familyCheck && !returning && phase != Phase.COURT && familyBlocks());
            if (stopped != null) {
                if (intervened) chase(stopped, person); else end(stopped);
                return;
            }
            if (familyCheck && (HouseholdProtection.protectedAt(level(pet), BlockPos.containing(edge))
                    || HouseholdProtection.protectedAt(level(pet), BlockPos.containing(yard))
                    || HouseholdProtection.protectedAt(level(pet), wild.blockPosition()))) { chase("repellent", null); return; }
            if (actor == pet && crossedAt >= 0 && flat(pet.position(), yard) > 64 && HumanSight.watching(pet))
                pet.pet().conscience().witnessedYard(crossedAt);
            if (phase == Phase.LEAVE && (!pet.hasLineOfSight(wild) || pet.distanceToSqr(wild) > 256)) { end("partner_lost"); return; }
            switch (phase) {
                case EDGE -> {
                    if (!SurfaceApproach.at(wild, edge)) { move(wild, edge, null); return; }
                    if (!wild.hasLineOfSight(pet) || watched(pet, wild)) { end("no_private_call"); return; }
                    SurfaceApproach.halt(wild);
                    boolean spoke = PetScenes.say(wild, YukkuriAction.BANK_BECKON, "happy", "elope_invite", pet);
                    GameplayLog.event(wild, "rules", "tryst call pet={} at={} yard={} spoke={}", pet.getUUID(), wild.position(), yard, spoke);
                    since = now; phase = Phase.CALL;
                }
                case CALL -> {
                    wild.getLookControl().setLookAt(pet, 30, 30);
                    if (now - since == YukkuriAction.BANK_BECKON.durationTicks())
                        PetScenes.act(wild, YukkuriAction.PUFF, "happy", "", YukkuriAction.PUFF.durationTicks(), pet);
                    if (now - since >= 3 * YukkuriAction.LOOK_AROUND.durationTicks()) end("unanswered");
                }
                case MEET -> {
                    if (actor != pet) return;
                    if (flat(wild.position(), yard) <= 64) { end("caller_left_edge"); return; }
                    if (crossedAt < 0 && flat(pet.position(), yard) <= 64 && pet.life().personality().temperament().clever() >= .5
                            && HumanSight.watching(pet)) { SurfaceApproach.halt(pet); return; }
                    if (crossedAt < 0 && flat(pet.position(), yard) > 64) {
                        crossedAt = now;
                        pet.pet().conscience().breach(HouseRule.NO_LEAVING_YARD);
                    }
                    if (!pet.hasLineOfSight(wild)) { end("partner_lost"); return; }
                    if (flat(pet.position(), yard) <= 64 || pet.distanceToSqr(wild) > 2.25) {
                        move(pet, SurfaceApproach.beside(pet, wild, 1.35, 1, 1.5, at -> flat(at, yard) > 64), wild);
                        return;
                    }
                    SurfaceApproach.halt(pet);
                    PetScenes.act(pet, YukkuriAction.NUZZLE, "happy", "", YukkuriAction.LOOK_AROUND.durationTicks(), wild);
                    PetScenes.act(wild, YukkuriAction.NUZZLE, "happy", "", YukkuriAction.LOOK_AROUND.durationTicks(), pet);
                    GameplayLog.event(pet, "rules", "tryst met partner={} petAt={} wildAt={}", wild.getUUID(), pet.position(), wild.position());
                    since = now; phase = Phase.TRYST;
                }
                case TRYST -> {
                    if (actor != pet) return;
                    if (flat(pet.position(), yard) <= 64 || flat(wild.position(), yard) <= 64
                            || pet.distanceToSqr(wild) > 2.5*2.5) { end("partner_lost"); return; }
                    if (now - since < YukkuriAction.LOOK_AROUND.durationTicks()) return;
                    if (depart()) return;
                    if ((pet.family().pentUp() >= PentUpMath.band(pet.life().personality().temperament().patient())
                            || wild.family().inBreedingPeriod()) && wild.family().beginTrystCourt(pet)) { phase = Phase.COURT; return; }
                    returnHome();
                }
                case COURT -> {
                    if (actor.family().courting(peer(actor))) actor.family().tickGoal();
                    if (!wild.family().courting(pet) || !pet.family().courting(wild)) {
                        pet.family().interruptCourtship(); wild.family().interruptCourtship();
                        returnHome();
                    }
                }
                case RETURN -> {
                    if (actor == wild) {
                        if (wildStand != null && SurfaceApproach.walkResult(wild, wildStand, null, .9) == SurfaceApproach.Result.BLOCKED)
                            wildStand = null;
                        return;
                    }
                    if (!SurfaceApproach.at(pet, petStand)) { move(pet, petStand, null); return; }
                    var memory = of(pet);
                    int n = RulesMath.visitsAfterReturn(memory.count(wild.getUUID()));
                    memory.recordVisits(wild, n);
                    GameplayLog.event(pet, "rules", "tryst returned partner={} visits={} bond={} petAt={} yard={}",
                            wild.getUUID(), n, affinity(pet, wild), pet.position(), yard);
                    end("returned");
                }
                case LEAVE -> travel(actor);
            }
        }
        void returnHome() {
            petStand = SurfaceApproach.beside(pet, yard, 2, at -> flat(at, yard) < 64);
            var home = wild.life().homeStand();
            Vec3 away = home == null || flat(Vec3.atBottomCenterOf(home), yard) <= 100
                    ? edge.add(edge.subtract(yard).normalize().scale(6)) : Vec3.atBottomCenterOf(home);
            wildStand = SurfaceApproach.beside(wild, away, 2, at -> flat(at, yard) > 64);
            phase = Phase.RETURN;
            if (petStand == null) end("blocked");
        }
        boolean depart() {
            var memory = of(pet);
            var traits = pet.life().personality();
            int visits = memory.count(wild.getUUID()), bond = affinity(pet, wild), trust = traits.trust(owner);
            double chance = RulesMath.departureChance(pet.family().pentUp(), pet.pet().neglect(), traits.attachment(),
                    pet.pet().obedience(), traits.selfControl(), bond, trust, visits);
            double roll = pet.getRandom().nextDouble();
            GameplayLog.event(pet, "rules", "departure opportunity partner={} visits={} chance={} roll={} pressure={} neglect={} attachment={} obedience={} selfControl={} bond={} ownerTrust={}",
                    wild.getUUID(), visits, chance, roll, pet.family().pentUp(), pet.pet().neglect(), traits.attachment(), pet.pet().obedience(), traits.selfControl(), bond, trust);
            if (roll >= chance) return false;
            var home = wild.life().homeStand();
            var toward = home == null ? edge : Vec3.atBottomCenterOf(home);
            if (flat(toward, yard) < 27*27) toward = yard.add(edge.subtract(yard).multiply(1,0,1).normalize().scale(28));
            double limit = Math.pow(24 + SurfaceApproach.ARRIVAL, 2);
            wildStand = SurfaceApproach.beside(wild, toward, 2, at -> flat(at, yard) > limit);
            petStand = wildStand == null ? null : SurfaceApproach.beside(pet, wildStand, 2, at -> flat(at, yard) > limit);
            if (petStand == null || memory.lastFailure != null && memory.lastFailure.equals(failure())) return false;
            phase = Phase.LEAVE; since = pet.level().getGameTime();
            PetScenes.say(pet, YukkuriAction.LOOK_AROUND, "teary", "elope_leave", null);
            GameplayLog.event(pet, "rules", "departure started partner={} from={} to={} yard={}", wild.getUUID(), pet.position(), petStand, yard);
            return true;
        }
        void travel(YukkuriEntity actor) {
            int clip = YukkuriAction.LOOK_AROUND.durationTicks();
            long age = pet.level().getGameTime() - since;
            if (actor == pet && age < clip * (1 + Math.round(pet.life().personality().temperament().attached()))) {
                pet.getLookControl().setLookAt(yard.x, pet.getEyeY(), yard.z);
                if (age == clip) PetScenes.act(pet, YukkuriAction.LOOK_AROUND, "teary", "", clip);
                return;
            }
            if (actor == pet && RulesMath.departed(SurfaceApproach.at(pet, petStand), SurfaceApproach.at(wild, wildStand),
                    flat(pet.position(), yard), flat(wild.position(), yard), pet.distanceToSqr(wild))) {
                if (!free(wild) || !wild.family().yieldsToComfort()) { end("unable_to_travel"); return; }
                if (familyBlocks()) { end("family_care"); return; }
                end("arrived");
                PetPrivileges.eloped(pet, owner);
                GameplayLog.event(pet, "rules", "eloped partner={} wildAt={} identity={} token={} serial={}", wild.getUUID(),
                        wild.position(), pet.status().identity().id(), pet.pet().visibleGrade(), pet.pet().serial());
                return;
            }
            if (pet.distanceToSqr(wild) > 36 && actor.distanceToSqr(wildStand) < peer(actor).distanceToSqr(wildStand)) {
                SurfaceApproach.halt(actor); return;
            }
            move(actor, actor == pet ? petStand : wildStand, null);
        }
    }
}
