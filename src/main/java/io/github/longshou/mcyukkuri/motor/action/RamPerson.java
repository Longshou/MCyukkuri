package io.github.longshou.mcyukkuri.motor.action;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.motor.Action;
import io.github.longshou.mcyukkuri.motor.BodyAbility;
import io.github.longshou.mcyukkuri.motor.ClipMarkers;
import io.github.longshou.mcyukkuri.motor.Feasibility;
import io.github.longshou.mcyukkuri.motor.MotionPose;
import io.github.longshou.mcyukkuri.motor.Motor;
import io.github.longshou.mcyukkuri.motor.Outcome;
import io.github.longshou.mcyukkuri.motor.Progress;
import io.github.longshou.mcyukkuri.motor.Reason;
import io.github.longshou.mcyukkuri.motor.Running;
import io.github.longshou.mcyukkuri.motor.TravelMode;
import io.github.longshou.mcyukkuri.motor.geom.RamContact;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/** One low contact stroke. The skinned reach, impact and recovery use the same clip as pane ramming. */
public final class RamPerson implements Action {
    private static final String CLIP = RamContact.CLIP;
    private final LivingEntity target;
    private Vec3 stand, direction;
    private double stroke;
    private int tick;
    private long started;

    public RamPerson(LivingEntity target) { this.target = target; }
    @Override public String id() { return CLIP; }

    public static Vec3 approach(YukkuriEntity body, LivingEntity target) {
        var back = body.position().subtract(target.position()).multiply(1, 0, 1).normalize();
        return target.position().add(back.scale(target.getBbWidth() * .5 + Bash.standOff(body)));
    }

    @Override public Feasibility check(YukkuriEntity body) {
        if (!body.onGround() || BodyAbility.bashPower(body) <= 0)
            return Feasibility.of(Reason.NO_SUPPORT, 0, "ram_requires_ground_and_strength");
        if (Math.abs(body.getY() - target.getY()) > body.getBbHeight() || !body.hasLineOfSight(target)
                || body.distanceTo(target) > target.getBbWidth() * .5 + Bash.standOff(body) + Walk.STAND_ARRIVED)
            return Feasibility.of(Reason.NO_CONTACT, body.distanceTo(target), "ram_out_of_reach");
        return Feasibility.pass();
    }

    @Override public void start(YukkuriEntity body) {
        SurfaceApproach.halt(body);
        started = body.level().getGameTime();
        stand = body.position();
        direction = target.position().subtract(stand).multiply(1, 0, 1).normalize();
        var reach = RamContact.of(body.assetId());
        double scale = body.growthScale() * body.getScale() / 16;
        stroke = Math.max(0, Math.min(Bash.standOff(body), body.distanceTo(target) - target.getBbWidth() * .5) - reach.impact() * scale);
    }

    @Override public Progress tick(YukkuriEntity body) {
        var clip = ClipMarkers.of(CLIP);
        int launch = clip.ticks("launch", 0), impact = clip.ticks("impact", 0);
        Motor.face(body, SurfaceApproach.yaw(Vec3.ZERO, direction));
        double travel = tick < launch ? 0 : tick <= impact ? stroke : stroke * (clip.length() - 1 - tick) / (clip.length() - 1 - impact);
        Motor.move(body, stand.add(direction.scale(travel)).subtract(body.position()).multiply(1, 0, 1), "territory_ram");
        if (tick == impact) {
            var reach = RamContact.of(body.assetId());
            double distance = target.getBoundingBox().distanceToSqr(body.position().add(0, body.getBbHeight() * .5, 0));
            if (distance > Mth.square(reach.impact() * body.growthScale() * body.getScale() / 16 + Walk.STAND_ARRIVED)
                    || !body.hasLineOfSight(target) || target.position().subtract(body.position()).dot(direction) <= 0)
                return Outcome.of(Reason.NO_CONTACT, distance, "person_moved_or_stroke_blocked");
            // A territorial bump is a warning. Leave at least half a heart even on a wounded person.
            float damage = Math.clamp(target.getHealth() - 1, 0, 1);
            boolean hurt = damage > 0 && target.hurt(body.damageSources().mobAttack(body), damage);
            target.knockback(.25, -direction.x, -direction.z);
            target.hurtMarked = true;
            GameplayLog.event(body, "home-claim", "territory ram target={} damage={}", target.getUUID(), hurt ? damage : 0);
        }
        return ++tick >= clip.length() ? new Outcome.Done("territory_ram_finished")
                : new Running(clip.length() - tick, TravelMode.CONSUMED);
    }

    @Override public MotionPose pose() { return new MotionPose(CLIP, started, false); }
}
