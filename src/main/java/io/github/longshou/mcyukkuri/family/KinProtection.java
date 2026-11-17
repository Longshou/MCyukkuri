package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.human.Custody;
import io.github.longshou.mcyukkuri.life.GlassSight;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.motor.Action;
import io.github.longshou.mcyukkuri.motor.ContactGeometry;
import io.github.longshou.mcyukkuri.motor.MotorOwner;
import io.github.longshou.mcyukkuri.motor.Outcome;
import io.github.longshou.mcyukkuri.motor.action.Face;
import io.github.longshou.mcyukkuri.motor.action.GlassPress;
import io.github.longshou.mcyukkuri.motor.action.Push;
import io.github.longshou.mcyukkuri.motor.action.Walk;
import io.github.longshou.mcyukkuri.social.ProtectivePuff;
import io.github.longshou.mcyukkuri.social.SocialDisposition;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.status.Reactions;
import java.util.EnumSet;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

import static io.github.longshou.mcyukkuri.social.SocialDisposition.ramp;

/** One parent's response to a perceived threat. Walking, glass contact and pushing use the ordinary motor. */
public final class KinProtection extends Goal implements MotorOwner {
    private enum Choice { SHIELD, PRESS, PUSH, TURN_AWAY, FREEZE }

    private final YukkuriEntity parent;
    private record Response(Choice choice, YukkuriEntity child, LivingEntity actor, long until) {}
    private Response response;
    private Action action;
    private boolean started;

    public KinProtection(YukkuriEntity parent) {
        this.parent = parent;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    public String witnessed(YukkuriEntity child, LivingEntity actor, boolean injured) {
        return choose(child, actor, false, injured).name();
    }

    public String approaching(YukkuriEntity child, LivingEntity actor) {
        return choose(child, actor, true, false).name();
    }

    private Choice choose(YukkuriEntity child, LivingEntity actor, boolean approach, boolean injured) {
        var mind = parent.life().personality();
        double attachment = ramp(mind.attachment(), 25, 80);
        double bold = ramp(mind.boldness(), 25, 70);
        double fear = ramp(mind.fear(actor.getUUID()), 35, 65);
        double closeness = ramp(mind.trust(child.getUUID()), 10, 65);
        double esteem = ramp(SocialDisposition.selfEsteem(parent), 45, 63);
        double guard = attachment * (.7 + .2 * closeness + .1 * bold) * (1 - .5 * fear);
        double freeze = .2 + .5 * (1 - bold) + .3 * fear;
        double selfish = (1 - attachment) * (approach ? esteem * bold * (1 - .5 * fear) : .35 + .55 * bold);
        var glass = GlassSight.glassBetween(parent, parent.getEyePosition(), child);
        Choice choice = guard > Math.max(freeze, selfish) ? (glass == null ? Choice.SHIELD : Choice.PRESS)
                : selfish > freeze ? (approach ? Choice.PUSH : Choice.TURN_AWAY) : Choice.FREEZE;
        boolean guarding = choice == Choice.SHIELD || choice == Choice.PRESS;
        boolean puff = guarding && injured && ProtectivePuff.humanWitness(parent, child, actor);
        YukkuriAction pose = guarding || choice == Choice.PUSH ? YukkuriAction.PUFF : YukkuriAction.BRACE;
        String face = guarding ? (puff ? "cheek_puff" : "puff_teary")
                : choice == Choice.FREEZE ? "horrified" : "angry";
        parent.forceReaction(pose, face, guarding ? "protect_taunt" : "");
        if (choice != Choice.TURN_AWAY) parent.getLookControl().setLookAt(choice == Choice.PRESS ? child : actor, 30, 30);
        if (!approach || response == null || response.actor() != actor || response.child() != child) {
            stop();
            if (mobile()) response = new Response(choice, child, actor, parent.level().getGameTime() + pose.durationTicks());
        }
        GameplayLog.event(parent, "family", "kin threat trigger={} child={} actor={} response={} attachment={} boldness={} esteem={} fear={}",
                approach ? "approach" : "punishment", child.getUUID(), actor.getUUID(), choice, mind.attachment(), mind.boldness(),
                SocialDisposition.selfEsteem(parent), mind.fear(actor.getUUID()));
        return choice;
    }

    private boolean mobile() {
        return Reactions.free(parent) && parent.onGround() && !parent.isLeashed() && !parent.isPanicking()
                && !parent.body().weak() && !parent.life().isSleeping() && !parent.life().hasUrgentNeed();
    }

    private boolean perceived() {
        var child = response.child();
        var actor = response.actor();
        return actor.isAlive() && child.isAlive() && child.level() == parent.level() && actor.level() == parent.level()
                && actor.distanceToSqr(child) <= 64
                && Custody.samePlantStock(parent, child) && ProtectivePuff.parentOf(child, parent) && GlassSight.sees(parent, child);
    }

    @Override public String ownerId() { return Behaviors.PROTECT_APPROACH; }
    @Override public boolean active() {
        return response != null && (action != null || parent.level().getGameTime() < response.until());
    }
    @Override public boolean requiresUpdateEveryTick() { return true; }
    @Override public boolean canUse() {
        if (response == null) return false;
        if (parent.level().getGameTime() >= response.until() || !mobile() || !perceived()) {
            response = null;
            return false;
        }
        return true;
    }
    @Override public boolean canContinueToUse() { return active() && mobile() && perceived(); }

    private void startResponse() {
        var child = response.child();
        switch (response.choice()) {
            case FREEZE -> SurfaceApproach.halt(parent);
            case TURN_AWAY -> run(new Face(SurfaceApproach.yaw(child.position(), parent.position())));
            case PRESS -> {
                var sight = GlassSight.glassBetween(parent, parent.getEyePosition(), child);
                if (sight == null) { coverThroughOpening(); return; }
                var stand = GlassSight.stand(parent, child, sight.getBlockPos());
                if (stand == null) stop();
                else run(new Walk(stand));
            }
            case SHIELD, PUSH -> approach(child);
        }
    }

    private boolean onSide(Vec3 stand) {
        Vec3 child = response.child().position();
        Vec3 threat = response.actor().position().subtract(child).multiply(1, 0, 1).normalize();
        Vec3 side = stand.subtract(child).multiply(1, 0, 1).normalize();
        return side.dot(threat) * (response.choice() == Choice.PUSH ? -1 : 1) > .8;
    }

    private void approach(YukkuriEntity child) {
        if (onSide(parent.position()) && ContactGeometry.inMouthReach(parent, child)) {
            contact(child);
            return;
        }
        var distance = (parent.getBbWidth() + child.getBbWidth()) / 2 + Walk.STAND_ARRIVED;
        var stand = SurfaceApproach.beside(parent, child.position(), distance, child,
                at -> onSide(at) && ContactGeometry.inMouthReach(parent, at, child));
        if (stand == null) stop();
        else run(Walk.around(stand, child));
    }

    private void contact(YukkuriEntity child) {
        if (!onSide(parent.position()) || !ContactGeometry.inMouthReach(parent, child)) { stop(); return; }
        if (response.choice() == Choice.PUSH) {
            run(new Face(SurfaceApproach.yaw(parent.position(), child.position())));
        } else {
            GameplayLog.event(parent, "family", "kin shield reached child={} actor={}", child.getUUID(), response.actor().getUUID());
            run(new Face(SurfaceApproach.yaw(parent.position(), response.actor().position())));
        }
    }

    private void coverThroughOpening() {
        var child = response.child();
        if (!parent.hasLineOfSight(child)) { stop(); return; }
        response = new Response(Choice.SHIELD, child, response.actor(), response.until());
        approach(child);
    }

    @Override public void tick() {
        if (response == null) return;
        if (!started) {
            started = true;
            startResponse();
            return;
        }
        if (action == null || parent.motor().current() == action) return;
        if (action instanceof Walk && parent.motor().last() instanceof Outcome.Done) {
            var child = response.child();
            if (response.choice() != Choice.PRESS) contact(child);
            else {
                var press = GlassPress.toward(parent, child);
                if (press == null) coverThroughOpening();
                else run(press);
            }
        } else if (action instanceof GlassPress && parent.motor().last() instanceof Outcome.Blocked
                && parent.hasLineOfSight(response.child())) {
            coverThroughOpening();
        } else if (action instanceof Face && response.choice() == Choice.PUSH && parent.motor().last() instanceof Outcome.Done
                && onSide(parent.position())) run(new Push(response.child()));
        else if (action instanceof Face && response.choice() == Choice.SHIELD) action = null;
        else stop();
    }

    private void run(Action move) {
        if (parent.motor().run(this, move)) action = move;
        else stop();
    }

    @Override public void stop() {
        parent.motor().cancel(this, "kin_threat_ended");
        response = null;
        action = null;
        started = false;
    }
}
