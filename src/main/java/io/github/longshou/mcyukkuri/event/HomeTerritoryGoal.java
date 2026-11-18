package io.github.longshou.mcyukkuri.event;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyNest;
import io.github.longshou.mcyukkuri.headwear.Recognition;
import io.github.longshou.mcyukkuri.human.HumanNpc;
import io.github.longshou.mcyukkuri.life.CareBlock;
import io.github.longshou.mcyukkuri.life.CareBlockEntity;
import io.github.longshou.mcyukkuri.life.EcologyMovement;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.motor.ClipMarkers;
import io.github.longshou.mcyukkuri.motor.MotorOwner;
import io.github.longshou.mcyukkuri.motor.action.RamPerson;
import io.github.longshou.mcyukkuri.motor.geom.RamContact;
import io.github.longshou.mcyukkuri.settlement.DenBarrier;
import io.github.longshou.mcyukkuri.social.BondLevels;
import io.github.longshou.mcyukkuri.social.Bonds;
import io.github.longshou.mcyukkuri.social.SocialDisposition;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.status.Identity;
import java.util.Comparator;
import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** One encounter at a real home. The same visitor gets one warning and one escalation per visit. */
public final class HomeTerritoryGoal extends Goal implements MotorOwner {
    private static final double RADIUS = 3;
    // Allow a visible turn before warning; allow a full puff before escalating.
    private static final int LOOK = 12;
    private static final int PUFF = ClipMarkers.of("puff").length();
    // A yield walk round the nest rim happens at waddle pace, so it needs more than one puff.
    private static final int NEST_VISIT = LOOK + 4 * PUFF;
    private static final int VISIT = LOOK + 2 * PUFF + ClipMarkers.of(RamContact.CLIP).length();
    private final YukkuriEntity body;
    private record Home(BlockPos site, BlockPos mouth, BlockPos refuge, boolean sealed, boolean softNest) {}
    private Home home;
    private LivingEntity intruder;
    private record Offence(BlockPos site, Player player) {}
    private Offence offence;
    private YukkuriEntity child;
    private RamPerson ram;
    private boolean nestVisit;
    private Vec3 aside;
    private int elapsed;
    private boolean active, yielded;
    private long scanAt;

    public HomeTerritoryGoal(YukkuriEntity body) {
        this.body = body;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override public String ownerId() { return Behaviors.HOME_TERRITORY; }
    @Override public boolean active() { return active; }
    public String activity() { return Behaviors.HOME_TERRITORY; }

    private Home home() {
        var claim = HomeClaim.of(body);
        if (claim != null) {
            if (!claim.declared || claim.stage == HomeClaimData.Stage.RETREAT) return null;
            return new Home(claim.site.pos(), claim.site.pos(), claim.site.pos(), false, false);
        }
        var bed = body.life().homeStand();
        if (bed == null) return null;
        var den = body.habitat().shelter();
        boolean soft = body.level().getBlockEntity(bed) instanceof CareBlockEntity care && care.kind() == CareBlock.Kind.NEST;
        return den == null ? new Home(bed, bed, bed, false, soft)
                : new Home(den.getBlockPos(), den.entrance(), bed, DenBarrier.sealed(den), false);
    }

    private boolean available() {
        var identity=body.status().identity();
        return body.isAlive() && !body.isNoAi() && !body.isPassenger() && !body.isPanicking() && !body.hurtCrying()
                && !body.profile().houseBody() && !body.profile().aquatic() && !body.profile().dangerToYukkuri()
                && !body.settlement().society().slave() && identity != Identity.STOCK
                && !body.isCommandPlaying() && !body.operations().active() && body.life().mealTicks() == 0
                && !body.isInWaterOrBubble() && !body.isOnFire() && !body.life().hasUrgentNeed();
    }

    private static boolean nearHome(Home at, LivingEntity visitor) {
        return at.mouth.distToCenterSqr(visitor.getX(), visitor.getY(), visitor.getZ()) <= RADIUS * RADIUS
                || at.refuge.distToCenterSqr(visitor.getX(), visitor.getY(), visitor.getZ()) <= RADIUS * RADIUS;
    }

    private boolean visit(Home at, LivingEntity visitor) {
        return visitor.isAlive() && !visitor.isRemoved() && visitor.level() == body.level()
                && !visitor.isSpectator() && nearHome(at, visitor);
    }

    private boolean nestVisitor(Home at, LivingEntity visitor) {
        return at.softNest && body.family().adult() && visitor instanceof YukkuriEntity peer && peer != body
                && visit(at, peer) && !peer.isPassenger() && !peer.profile().dangerToYukkuri() && Recognition.recognizes(body, peer)
                && !FamilyNest.sameNest(body, peer) && body.hasLineOfSight(peer)
                && at.site.distToCenterSqr(peer.getX(), peer.getY(), peer.getZ()) <= 4;
    }

    private boolean stranger(Home at, LivingEntity visitor) {
        if (visitor == body || !visit(at, visitor) || !body.hasLineOfSight(visitor)) return false;
        if (visitor instanceof YukkuriEntity peer) {
            if (peer.profile().dangerToYukkuri() || peer.isPassenger() || !Recognition.recognizes(body, peer)
                    || Bonds.careOrMate(body, peer) || Bonds.sameGroupId(body, peer)
                    || FamilyNest.sameNest(body, peer) || SocialDisposition.caringContact(body, peer)) return false;
            var den = body.habitat().shelter();
            var claim = HomeClaim.of(body);
            if (den != null && den.owns(peer.getUUID()) || claim != null && claim.members.contains(peer.getUUID())) return false;
            var contact = SocialDisposition.contact(body, peer);
            if (contact.fear() < BondLevels.AFFECTION_FEAR_LIMIT && SocialDisposition.toleratesStrangeYoung(body, peer, contact))
                return false;
            return contact.closeness() < BondLevels.TRUST_FAMILIAR || contact.fear() >= BondLevels.AFFECTION_FEAR_LIMIT;
        }
        if (!(visitor instanceof Player || visitor instanceof HumanNpc)
                || visitor.getUUID().equals(body.pet().owner())
                || visitor instanceof Player player && body.life().seeksOfferedFood(player)) return false;
        var contact = SocialDisposition.contact(body, visitor);
        return contact.closeness() < BondLevels.TRUST_FAMILIAR || contact.fear() >= BondLevels.AFFECTION_FEAR_LIMIT;
    }

    /** Actual harm or a lift attempt counts even when the resident knows this player. */
    public void offended(Player player) {
        var at = home();
        if (at == null || !nearHome(at, body) || !visit(at, player)) return;
        offence = new Offence(at.site, player);
        scanAt = body.level().getGameTime();
        GameplayLog.event(body, "home-claim", "territory offended target={} site={}", player.getUUID(), at.site);
    }

    private boolean offending(Home at, LivingEntity visitor) {
        return offence != null && offence.player == visitor && offence.site.equals(at.site) && visit(at, visitor);
    }

    private LivingEntity visitor(Home at) {
        return body.level().getEntitiesOfClass(LivingEntity.class, new AABB(at.mouth).minmax(new AABB(at.refuge)).inflate(RADIUS),
                visitor -> visitor != intruder
                        && (at.softNest && visitor instanceof YukkuriEntity ? nestVisitor(at, visitor) : stranger(at, visitor)))
                .stream().min(Comparator.comparingDouble(body::distanceToSqr)).orElse(null);
    }

    /** The stationary declaration yields to a new encounter, which the normal territory goal owns. */
    public boolean declarationChallenged() {
        if (!available() || !body.onGround() || body.level().getGameTime() < scanAt) return false;
        var at = home();
        return at != null && nearHome(at, body) && (offence != null && offending(at, offence.player) || visitor(at) != null);
    }

    @Override public boolean canUse() {
        long now = body.level().getGameTime();
        if (now < scanAt || !available() || !body.onGround()) return false;
        scanAt = now + 10;
        var current = home();
        if (current == null || home == null || !current.site.equals(home.site)) intruder = null;
        home = current;
        if (home == null || !nearHome(home, body)) { offence = null; return false; }
        if (offence != null && !offending(home, offence.player)) offence = null;
        if (offence != null) intruder = offence.player;
        else {
            if (intruder != null && visit(home, intruder)) return false;
            intruder = visitor(home);
        }
        if (intruder == null) return false;
        nestVisit = nestVisitor(home, intruder);
        if (body.life().isSleeping()) {
            body.life().wakeForDanger("territory");
            scanAt = now + LOOK;
            var visitor = intruder;
            intruder = null;
            GameplayLog.event(body, "home-claim", "territory woke target={} site={}", visitor.getUUID(), home.site);
            return false;
        }
        return true;
    }

    @Override public boolean canContinueToUse() {
        var current = home();
        return active && available() && current != null && current.site.equals(home.site)
                && visit(home, intruder) && elapsed < (nestVisit ? (aside != null ? NEST_VISIT : LOOK + PUFF + 1) : VISIT)
                && !(intruder instanceof YukkuriEntity peer && (SocialDisposition.caringContact(body, peer) || FamilyNest.sameNest(body, peer)));
    }

    @Override public boolean requiresUpdateEveryTick() { return true; }

    @Override public void start() {
        if (HomeClaim.declaring(body)) body.cancelReaction();
        active = true; elapsed = 0; yielded = false;
        child = body.level().getEntitiesOfClass(YukkuriEntity.class, body.getBoundingBox().inflate(RADIUS),
                y -> y.isAlive() && body.family().caresFor(y) && Recognition.recognizes(body, y))
                .stream().min(Comparator.comparingDouble(body::distanceToSqr)).orElse(null);
        EcologyMovement.halt(body);
    }

    @Override public void tick() {
        elapsed++;
        body.getLookControl().setLookAt(intruder, 30, 30);
        if (elapsed < LOOK) {
            SurfaceApproach.face(body,SurfaceApproach.yaw(body.position(),intruder.position()));
            return;
        }
        if (nestVisit) {
            if (elapsed == LOOK) resolveNestVisit();
            else if (HomeClaim.declaring(body)) {
                elapsed = LOOK;
                SurfaceApproach.face(body, SurfaceApproach.yaw(body.position(), intruder.position()));
            } else if (elapsed == LOOK + 1 && !yielded && nestVisitor(home, intruder) && intruder.blockPosition().equals(home.site)) {
                puff();
            }
            if (aside != null && body.onGround()) {
                var movement = SurfaceApproach.walkResult(body, aside, intruder, 1);
                if (movement == SurfaceApproach.Result.ARRIVED || movement == SurfaceApproach.Result.BLOCKED) {
                    if (movement == SurfaceApproach.Result.ARRIVED)
                        GameplayLog.event(body, "home-claim", "nest gave space peer={} stand={}", intruder.getUUID(), aside);
                    elapsed = NEST_VISIT;
                }
            }
            return;
        }
        if (ram != null && !stranger(home, intruder) && !offending(home, intruder)) {
            body.motor().cancel(this, "territory_visitor_withdrew");
            return;
        }
        boolean person = !(intruder instanceof YukkuriEntity);
        double ratio = person ? 1 : body.getBbWidth() / intruder.getBbWidth();
        var contact = SocialDisposition.contact(body, intruder);
        double courage = resolve(body, contact, ratio);
        if (intruder instanceof Player player && player.isSprinting() && contact.wariness() >= .8
                && body.distanceToSqr(player) < 4 && courage < .30 && HomeClaim.displaced(body, player)) return;
        if (!body.family().adult() || body.body().weak() || body.body().mold()>0 || body.family().carry().carrying() || courage < (person ? .30 : .25)
                || intruder instanceof YukkuriEntity peer && courage + .10 < resolve(peer, body)) {
            if (!yielded) {
                if (ram != null) body.motor().cancel(this, "territory_yielded");
                yielded = true;
                body.showReaction(YukkuriAction.TREMBLE, "teary", child == null ? "afraid" : "protect_child_flee");
                GameplayLog.event(body, "home-claim", "territory yield target={} child={} resolve={} ratio={}",
                        intruder.getUUID(), child == null ? null : child.getUUID(), courage, ratio);
            }
            shelter();
            return;
        }
        if (yielded) { shelter(); return; }
        if (elapsed == LOOK) {
            body.showReaction("angry", "wild_role_provoke", PUFF / 2);
            GameplayLog.event(body, "home-claim", "territory warn target={} site={} esteem={} wariness={} resolve={}",
                    intruder.getUUID(), home.site, contact.esteem(), contact.wariness(), courage);
        }
        if (elapsed >= LOOK && elapsed < LOOK + PUFF + PUFF / 2)
            SurfaceApproach.face(body, SurfaceApproach.yaw(body.position(), intruder.position()));
        if (elapsed == LOOK + PUFF / 2 && (stranger(home, intruder) || offending(home, intruder))) puff();
        if (elapsed < LOOK + PUFF + PUFF / 2 || home().sealed || !stranger(home, intruder) && !offending(home, intruder)) return;
        if (person) {
            if (ram != null || courage < .45 || body.body().healthRatio() < .65) return;
            var trial = new RamPerson(intruder);
            if (trial.check(body).ok()) {
                // One stroke per visit: a motor refusal ends the escalation instead of retrying the same action every tick.
                if (body.motor().run(this, trial)) ram = trial;
                else elapsed = VISIT;
            } else if (SurfaceApproach.walkResult(body, RamPerson.approach(body, intruder), intruder, 1) == SurfaceApproach.Result.BLOCKED) {
                elapsed = VISIT;
            }
        } else {
            var peer = (YukkuriEntity) intruder;
            if (elapsed == LOOK + PUFF + PUFF / 2) {
                boolean route = body.life().navigate(peer.blockPosition(), 1.2, false);
                GameplayLog.event(body, "home-claim", "territory chase target={} route={}", peer.getUUID(), route);
            }
            if (body.distanceToSqr(peer) < 2.25 && courage > resolve(peer, body)) {
                peer.fleeFrom(body);
                elapsed = VISIT;
            }
        }
    }

    private static double resolve(YukkuriEntity actor, YukkuriEntity other) {
        return resolve(actor, SocialDisposition.contact(actor, other), actor.getBbWidth() / other.getBbWidth());
    }

    private void resolveNestVisit() {
        if (!nestVisitor(home, intruder)) { yielded = true; return; }
        var p = body.life().personality();
        var contact = SocialDisposition.contact(body, intruder);
        // Choose a reachable place along the rim and clear of both the visitor and the resident's current stand.
        var alternative = SurfaceApproach.beside(body, Vec3.atBottomCenterOf(home.site), 1.6, intruder,
                at -> at.distanceToSqr(body.position()) >= body.getBbWidth() * body.getBbWidth());
        double guard = .40 * SocialDisposition.confidence(contact.esteem()) + .30 * SocialDisposition.ramp(p.boldness(), 35, 72)
                + .30 * (1 - SocialDisposition.ramp(body.life().energy(), 25, 80)) - .65 * contact.wariness();
        double yield = .25 + .45 * SocialDisposition.ramp(p.friendliness(), 35, 75)
                + .20 * SocialDisposition.ramp(contact.closeness(), 0, 20) + (alternative == null ? 0 : .10);
        boolean declare = guard > yield && !body.body().weak() && body.body().mold() == 0 && !body.family().carry().carrying();
        yielded = !declare;
        if (declare) HomeClaim.declareHome(body, home.site);
        else {
            aside = alternative;
            body.showReaction(YukkuriAction.SHY, "side_teary", "");
        }
        GameplayLog.event(body, "home-claim", "nest dispute peer={} site={} response={} guard={} yield={}",
                intruder.getUUID(), home.site, declare ? "declare" : "yield", guard, yield);
    }

    private void puff() {
        if (body.showReaction(YukkuriAction.PUFF, "cheek_puff", "", PUFF))
            GameplayLog.event(body, "home-claim", "territory puff target={} site={}", intruder.getUUID(), home.site);
    }

    private static double resolve(YukkuriEntity actor, SocialDisposition.Contact contact, double ratio) {
        var personality = actor.life().personality();
        return .55 * personality.boldness() / 100 + .20 * SocialDisposition.confidence(contact.esteem())
                + .25 * contact.insistence() - .5 * contact.wariness()
                + .2 * Math.clamp(ratio * actor.body().healthRatio() - 1, -1, 1);
    }

    private void shelter() {
        if (home().sealed || elapsed % 10 != 0) return;
        if (child != null && child.isAlive() && !child.isPassenger() && body.distanceToSqr(child) < RADIUS * RADIUS) {
            var toward = intruder.position().subtract(child.position()).multiply(1, 0, 1).normalize();
            var between = child.position().add(toward.scale((body.getBbWidth() + child.getBbWidth()) * .5));
            if (SurfaceApproach.walkResult(body, between, child, 1) != SurfaceApproach.Result.BLOCKED) return;
        }
        if (!body.life().navigate(home.refuge, 1, false)
                && body.blockPosition().distSqr(home.refuge) > 2
                && intruder instanceof YukkuriEntity peer) body.fleeFrom(peer);
    }

    @Override public void stop() {
        if (!nestVisit && !yielded && elapsed >= LOOK && intruder.isAlive() && !nearHome(home, intruder)
                && home.equals(home()) && nearHome(home, body) && body.family().adult())
            HomeClaim.declareHome(body, home.site);
        if (offence != null && offence.player == intruder) offence = null;
        body.motor().cancel(this, "territory_ended");
        SurfaceApproach.stopWalking(body);
        active = false; ram = null; child = null; aside = null;
    }

}
