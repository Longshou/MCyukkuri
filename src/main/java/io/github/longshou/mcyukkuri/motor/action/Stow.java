package io.github.longshou.mcyukkuri.motor.action;

import io.github.longshou.mcyukkuri.random.ModRandom;

import io.github.longshou.mcyukkuri.body.MouthPlacement;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.headwear.PlacedHatEntity;
import io.github.longshou.mcyukkuri.motor.Action;
import io.github.longshou.mcyukkuri.motor.Attach;
import io.github.longshou.mcyukkuri.motor.AttachState;
import io.github.longshou.mcyukkuri.motor.BodyAbility;
import io.github.longshou.mcyukkuri.motor.Braid;
import io.github.longshou.mcyukkuri.motor.ContactGeometry;
import io.github.longshou.mcyukkuri.motor.Feasibility;
import io.github.longshou.mcyukkuri.motor.Held;
import io.github.longshou.mcyukkuri.motor.Held.Place;
import io.github.longshou.mcyukkuri.motor.HeldMove;
import io.github.longshou.mcyukkuri.motor.Motor;
import io.github.longshou.mcyukkuri.motor.Outcome;
import io.github.longshou.mcyukkuri.motor.Progress;
import io.github.longshou.mcyukkuri.motor.Reason;
import io.github.longshou.mcyukkuri.motor.Running;
import io.github.longshou.mcyukkuri.motor.TravelMode;
import io.github.longshou.mcyukkuri.motor.geom.CarryGeometry;
import io.github.longshou.mcyukkuri.motor.surface.Occupancy;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.Locale;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.Vec3;

/**
 * One visible beat that moves the stack she holds (her hat's kit) out of a part an action needs, or back into her grip, so it is
 * always in exactly one real place (project design, user 2026-11-06: an item never vanishes or appears). A braid that has to grip
 * a young first hands the stick to her free mouth, else lays it in her own hat when she sits in it, else on the ground as a real
 * item; a mouth that is needed gives it back to a free braid, else lays it down the same way; a free braid takes it back from her
 * mouth or her hat. The braid tuft (or the mouth) goes there and back over its real way at her own pace, as a mouth lift is timed
 * ({@link CarryGeometry#motionTicks}); the one move happens when it arrives, and the client draws the stack from the synced record
 * ({@link Held}).
 */
public final class Stow implements Action, HeldMove {
    public static final String ID = "stow";
    /** The held stick past its grip (model units at her size, recipe braid_item_hold held_item): its short top and long blade. */
    private static final double TOP = 1.6, BLADE = 10.4;

    private final Place from, to;
    private final boolean mouth;
    private final String cause;
    private final Vec3 spot, dir, ground;
    private final int handover;
    private long startTick;
    private boolean moved;

    private Stow(YukkuriEntity body, Place from, Place to, boolean mouth, String cause, Vec3 spot, Vec3 dir, Vec3 ground) {
        this.from = from; this.to = to; this.mouth = mouth; this.cause = cause;
        this.spot = spot; this.dir = dir; this.ground = ground;
        // The tuft from its rest to the place (or the lips down to it), at her own pace.
        var at = to == Place.GROUND ? ground : to == Place.HAT || from == Place.HAT ? world(body, spot) : MouthPlacement.position(body);
        var start = mouth ? MouthPlacement.position(body) : ContactGeometry.braidTip(body);
        handover = CarryGeometry.motionTicks(at == null || start == null ? 0 : at.distanceTo(start), BodyAbility.of(body).walkSpeed());
    }

    /** The beat that frees her braid for one that grips a young, or null when her stick is not in it. */
    public static Stow freeBraid(YukkuriEntity body, String cause) {
        if (!ContactGeometry.braided(body) || body.hatKit().isEmpty() || place(body) != Place.GRIP) return null;
        if (Braid.mouthFree(body)) return new Stow(body, Place.GRIP, Place.MOUTH, false, cause, null, null, null);
        return layDown(body, Place.GRIP, false, cause);
    }

    /** The beat that frees her mouth, or null when her stack is not in it. */
    public static Stow freeMouth(YukkuriEntity body, String cause) {
        if (body.hatKit().isEmpty()) return null;
        boolean braided = ContactGeometry.braided(body);
        Place in = braided ? Place.MOUTH : Place.GRIP;
        if (place(body) != in) return null;
        if (braided && !Braid.gripping(body)) return new Stow(body, Place.MOUTH, Place.GRIP, false, cause, null, null, null);
        return layDown(body, in, true, cause);
    }

    /** The beat that brings her stack back into her braid from her mouth, or from her own hat while its spot is in the braid's reach;
     *  null when it is already there, the braid grips a young, or it cannot be reached. */
    public static Stow regrip(YukkuriEntity body, String cause) {
        if (!ContactGeometry.braided(body) || body.hatKit().isEmpty() || Braid.gripping(body)) return null;
        var custody = body.life().custody();
        if (custody.kitPlace() == Place.MOUTH) return new Stow(body, Place.MOUTH, Place.GRIP, false, cause, null, null, null);
        if (custody.kitPlace() != Place.HAT || !reaches(body, custody.kitSpot())) return null;
        return new Stow(body, Place.HAT, Place.GRIP, false, cause, custody.kitSpot(), custody.kitDir(), null);
    }

    /** Her hat is lifted with her stick still lying in it: it tips out where it lay, a real floating item (never carried on unseen). */
    public static void tipOut(YukkuriEntity body, String cause) {
        var custody = body.life().custody();
        var hat = PlacedHatEntity.find(body);
        if (hat == null || custody.kitPlace() != Place.HAT || hat.kit().isEmpty()) return;
        hat.dropKit(hat.frame().toWorld(custody.kitSpot()), Vec3.ZERO, cause);
    }

    private static Place place(YukkuriEntity body) { return body.life().custody().kitPlace(); }

    private static Vec3 world(YukkuriEntity body, Vec3 local) {
        var hat = PlacedHatEntity.find(body);
        return hat == null || local == null ? null : hat.frame().toWorld(local);
    }

    /** Into her own hat while she sits in it (beside her braid side, or before her lips for the mouth), else on the ground. */
    private static Stow layDown(YukkuriEntity body, Place from, boolean mouth, String cause) {
        double s = body.growthScale() * body.getScale();
        float yaw = (float) Math.toRadians(body.yBodyRot);
        Vec3 forward = new Vec3(-Math.sin(yaw), 0, Math.cos(yaw)), right = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw));
        var hat = PlacedHatEntity.find(body);
        if (hat != null && Attach.of(body) instanceof AttachState.OnHost on && on.host().entity() == hat) {
            // The braid lowers it along her braid side, its long blade toward her front; the mouth lays it crosswise before her, centred.
            var edge = body.getBbWidth() / 2 + .08;
            var shortEnd = mouth ? right : forward.scale(-1);
            var grip = mouth ? body.position().add(forward.scale(edge)).add(right.scale((BLADE - TOP) / 32 * s))
                    : body.position().add(right.scale(edge));
            double rest = Double.NEGATIVE_INFINITY;
            for (double along : new double[]{TOP, 0, -BLADE / 2, -BLADE}) {
                double y = Occupancy.surfaceUnder(hat, .05, grip.add(shortEnd.scale(along / 16 * s)).add(0, 1, 0));
                if (Double.isFinite(y)) rest = Math.max(rest, y);
            }
            if (Double.isFinite(rest)) {
                var at = new Vec3(grip.x, rest + .3 / 16 * s, grip.z);
                var frame = hat.frame();
                var local = frame.toLocal(at);
                var tip = frame.toLocal(at.add(shortEnd)).subtract(local);
                return new Stow(body, from, Place.HAT, mouth, cause, local, new Vec3(tip.x, 0, tip.z).normalize(), null);
            }
        }
        var drop = mouth ? MouthPlacement.position(body) : ContactGeometry.braidTip(body);
        return new Stow(body, from, Place.GROUND, mouth, cause, null, null, drop);
    }

    private static boolean reaches(YukkuriEntity body, Vec3 local) {
        var at = world(body, local);
        return at != null && (ContactGeometry.braidGap(body, at, false) <= 0 || ContactGeometry.braidGap(body, at, true) <= 0);
    }

    @Override public String id() { return ID; }

    @Override public Feasibility check(YukkuriEntity body) {
        if (body.hatKit().isEmpty()) return Feasibility.of(Reason.TARGET_GONE, 0, "nothing_held");
        if (place(body) != from) return Feasibility.of(Reason.NO_CONTACT, 0, "held_elsewhere place=" + place(body));
        if ((from == Place.HAT || to == Place.HAT) && PlacedHatEntity.find(body) == null) return Feasibility.of(Reason.TARGET_GONE, 0, "hat_gone");
        return Feasibility.pass();
    }

    @Override public void start(YukkuriEntity body) {
        startTick = body.level().getGameTime();
        moved = false;
        GameplayLog.event(body, "body", "held beat from={} to={} item={} by={} handover={} cause={}", from, to, body.hatKit().getItem(),
                mouth ? "mouth" : "braid", handover, cause);
    }

    @Override public Progress tick(YukkuriEntity body) {
        long elapsed = body.level().getGameTime() - startTick;
        if (!moved && elapsed >= handover) {
            var still = check(body);
            if (!still.ok()) return Outcome.of(still.reason(), still.shortfall(), still.detail());
            move(body);
            moved = true;
        }
        int left = length() - (int) elapsed;
        return left > 0 ? new Running(left, TravelMode.CONSUMED) : new Outcome.Done("held from=" + from + " to=" + to);
    }

    /** The one transfer: the custody's place (or, onto the ground, the real dropped stack). */
    private void move(YukkuriEntity body) {
        var item = body.hatKit().getItem();
        var custody = body.life().custody();
        if (to == Place.GROUND) {
            var hat = PlacedHatEntity.find(body);
            if (custody.worn() && !custody.kit().isEmpty()) {
                var drop = ModRandom.created(new ItemEntity(body.level(), ground.x, ground.y, ground.z, custody.takeKit()), body.getRandom());
                Motor.velocity(drop, Vec3.ZERO, "laid_down");
                drop.setDefaultPickUpDelay();
                body.level().addFreshEntity(drop);
            } else if (hat != null) hat.dropKit(ground, Vec3.ZERO, "laid_down");
        } else custody.placeKit(to, spot, dir, cause);
        GameplayLog.event(body, "body", "held moved from={} to={} item={} at={} cause={}", from, to, item,
                to == Place.GROUND ? GameplayLog.at(ground) : to == Place.HAT ? GameplayLog.at(spot) : "-", cause);
    }

    /** The beat's whole length in ticks: there, and back to rest. */
    public int length() { return 2 * handover; }

    @Override public Held.Beat beat() { return new Held.Beat(from, to, startTick, handover, length(), mouth, ground); }
    @Override public Vec3 spot() { return to == Place.HAT || from == Place.HAT ? spot : null; }
    @Override public Vec3 dir() { return to == Place.HAT || from == Place.HAT ? dir : null; }
    @Override public HeldMove heldMove() { return this; }

    @Override public String startDetail() { return String.format(Locale.ROOT, "from=%s to=%s by=%s cause=%s", from, to, mouth ? "mouth" : "braid", cause); }
}
