package io.github.longshou.mcyukkuri.event;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.headwear.Recognition;
import io.github.longshou.mcyukkuri.human.HumanNpc;
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
    private final YukkuriEntity body;
    private record Home(BlockPos site, BlockPos mouth, BlockPos refuge, boolean sealed) {}
    private Home home;
    private LivingEntity intruder;
    private YukkuriEntity child;
    private RamPerson ram;
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
            return new Home(claim.site.pos(), claim.site.pos(), claim.site.pos(), false);
        }
        var bed = body.life().homeStand();
        if (bed == null) return null;
        var den = body.habitat().shelter();
        return den == null ? new Home(bed, bed, bed, false)
                : new Home(den.getBlockPos(), den.entrance(), bed, DenBarrier.sealed(den));
    }

    private boolean available() {
        var identity=body.status().identity();
        return body.isAlive() && !body.isNoAi() && !body.isPassenger() && !body.isPanicking() && !body.hurtCrying()
                && !body.profile().houseBody() && !body.profile().aquatic() && !body.profile().dangerToYukkuri()
                && !body.settlement().society().slave() && (identity.free() || identity.companion())
                && !body.isCommandPlaying() && !body.operations().active() && body.life().mealTicks() == 0
                && !body.isInWaterOrBubble() && !body.isOnFire() && !body.life().hasUrgentNeed();
    }

    private boolean nearHome(LivingEntity visitor) {
        return home.mouth.distToCenterSqr(visitor.getX(), visitor.getY(), visitor.getZ()) <= RADIUS * RADIUS
                || home.refuge.distToCenterSqr(visitor.getX(), visitor.getY(), visitor.getZ()) <= RADIUS * RADIUS;
    }

    private boolean visit(LivingEntity visitor) {
        return visitor.isAlive() && !visitor.isRemoved() && visitor.level() == body.level()
                && !visitor.isSpectator() && nearHome(visitor);
    }

    private boolean stranger(LivingEntity visitor) {
        if (visitor == body || !visit(visitor) || !body.hasLineOfSight(visitor)) return false;
        if (visitor instanceof YukkuriEntity peer) {
            if (peer.profile().dangerToYukkuri() || peer.isPassenger() || !Recognition.recognizes(body, peer)
                    || Bonds.careOrMate(body, peer) || Bonds.sameGroupId(body, peer)) return false;
            var den = body.habitat().shelter();
            var claim = HomeClaim.of(body);
            if (den != null && den.owns(peer.getUUID()) || claim != null && claim.members.contains(peer.getUUID())) return false;
        } else if (!(visitor instanceof Player || visitor instanceof HumanNpc)
                || visitor.getUUID().equals(body.pet().owner())
                || visitor instanceof Player player && body.life().seeksOfferedFood(player)) return false;
        var contact = SocialDisposition.contact(body, visitor);
        return contact.closeness() < BondLevels.TRUST_FAMILIAR || contact.fear() >= BondLevels.AFFECTION_FEAR_LIMIT;
    }

    @Override public boolean canUse() {
        long now = body.level().getGameTime();
        if (now < scanAt || !available() || !body.onGround()) return false;
        scanAt = now + 10;
        var current = home();
        if (current == null || home == null || !current.site.equals(home.site)) intruder = null;
        home = current;
        if (home == null || !nearHome(body)) return false;
        if (intruder != null && visit(intruder)) return false;
        intruder = body.level().getEntitiesOfClass(LivingEntity.class, new AABB(home.mouth).minmax(new AABB(home.refuge)).inflate(RADIUS),
                this::stranger).stream().min(Comparator.comparingDouble(body::distanceToSqr)).orElse(null);
        if (intruder == null) return false;
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
                && visit(intruder) && elapsed < LOOK + 2 * PUFF + ClipMarkers.of(RamContact.CLIP).length();
    }

    @Override public boolean requiresUpdateEveryTick() { return true; }

    @Override public void start() {
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
        if (ram != null && !stranger(intruder)) {
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
            body.showReaction("angry", "wild_role_provoke", PUFF);
            GameplayLog.event(body, "home-claim", "territory warn target={} site={} esteem={} wariness={} resolve={}",
                    intruder.getUUID(), home.site, contact.esteem(), contact.wariness(), courage);
        }
        if (elapsed == LOOK + PUFF / 2)
            body.showReaction(YukkuriAction.PUFF, "cheek_puff", "");
        if (elapsed < LOOK + PUFF || home().sealed || !stranger(intruder)) return;
        if (person) {
            if (ram != null || courage < .45 || body.body().healthRatio() < .65) return;
            var trial = new RamPerson(intruder);
            if (trial.check(body).ok()) {
                ram = trial;
                body.motor().run(this, ram);
            } else if (elapsed % 5 == 0) {
                if (SurfaceApproach.walkResult(body, RamPerson.approach(body, intruder), intruder, 1) == SurfaceApproach.Result.BLOCKED)
                    elapsed = LOOK + 2 * PUFF + ClipMarkers.of(RamContact.CLIP).length();
            }
        } else {
            var peer = (YukkuriEntity) intruder;
            if (elapsed == LOOK + PUFF) {
                boolean route = body.life().navigate(peer.blockPosition(), 1.2, false);
                GameplayLog.event(body, "home-claim", "territory chase target={} route={}", peer.getUUID(), route);
            }
            if (body.distanceToSqr(peer) < 2.25 && courage > resolve(peer, body)) {
                peer.fleeFrom(body);
                elapsed = LOOK + 2 * PUFF + ClipMarkers.of(RamContact.CLIP).length();
            }
        }
    }

    private static double resolve(YukkuriEntity actor, YukkuriEntity other) {
        return resolve(actor, SocialDisposition.contact(actor, other), actor.getBbWidth() / other.getBbWidth());
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
        if (!body.life().navigate(home.refuge, 1, false) && intruder instanceof YukkuriEntity peer) body.fleeFrom(peer);
    }

    @Override public void stop() {
        if (!yielded && elapsed >= LOOK && intruder.isAlive() && !nearHome(intruder)
                && home.equals(home()) && nearHome(body) && body.family().adult())
            HomeClaim.declareHome(body, home.site);
        body.motor().cancel(this, "territory_ended");
        SurfaceApproach.stopWalking(body);
        active = false; ram = null; child = null;
    }

}
