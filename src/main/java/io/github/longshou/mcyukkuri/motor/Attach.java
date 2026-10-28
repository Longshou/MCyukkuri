package io.github.longshou.mcyukkuri.motor;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.motor.surface.Riders;
import io.github.longshou.mcyukkuri.motor.surface.SurfaceHost;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.Locale;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Where a body is, read from facts and re-derived every time (spec 5). The only storage is the vanilla vehicle
 * relation plus the host's {@link Riders}; nothing caches "in the hat" or "being carried".
 */
public final class Attach {
    /** The clips that put a body in the air; used only to name the {@link AttachState.Airborne} state. */
    private static final String HOP = "hop";
    private static final String LAUNCH = "launch";

    private Attach() {}

    /** Pure read: the attachment of {@code body} right now. */
    public static AttachState of(YukkuriEntity body) {
        if (body == null) return new AttachState.Free(false);
        Entity vehicle = body.getVehicle();
        if (vehicle instanceof SurfaceHost host) {
            Riders riders = host.riders();
            return new AttachState.OnHost(host, riders.local(body.getUUID()), riders.yaw(body.getUUID()));
        }
        if (vehicle instanceof YukkuriEntity carrier) return new AttachState.Held(carrier);
        BodyDriver driver = body.motor();
        Action action = driver == null ? null : driver.current();
        if (action != null && (HOP.equals(action.id()) || LAUNCH.equals(action.id()))
                && !body.onGround() && !body.isInWater()) {
            return new AttachState.Airborne(action.id());
        }
        return new AttachState.Free(body.isInWater());
    }

    /**
     * Re-derives the state after a transition, records a change, keeps the sync slot current, and pushes a body that
     * ended up inside a block collision out by at most {@link Motor#NUDGE_MAX}. Never decides where to go.
     */
    public static AttachState settle(YukkuriEntity body, String cause) {
        AttachState state = of(body);
        if (body == null || body.level().isClientSide) return state;
        BodyDriver driver = body.motor();
        String text = describe(state);
        if (!text.equals(driver.settled())) {
            String before = driver.settled();
            if (!before.isEmpty()) GameplayLog.event(body, "body", "attach {} -> {} cause={}", before, text, cause);
            driver.settled(text);
        }
        driver.syncAttach(state.kind());
        if (!body.level().noCollision(body)) nudgeOut(body);
        return state;
    }

    /** A compact one-line form for {@code [body] attach} logs and the {@code body.attach} status. */
    public static String describe(AttachState state) {
        if (state instanceof AttachState.Free free) return free.inWater() ? "free(water)" : "free";
        if (state instanceof AttachState.Airborne air) return "airborne:" + air.action();
        if (state instanceof AttachState.OnHost host) {
            return String.format(Locale.ROOT, "on_host:%s@%.2f,%.2f,%.2f/yaw=%.1f",
                    host.host().entity().getUUID(), host.local().x, host.local().y, host.local().z, host.localYaw());
        }
        AttachState.Held held = (AttachState.Held) state;
        return "held:" + held.carrier().getUUID();
    }

    /** Small pushes out of a block collision; every attempt is undone before the next, and a body that still
     *  collides afterwards is reported instead of being moved further. */
    private static void nudgeOut(YukkuriEntity body) {
        Vec3[] tries = {
                new Vec3(0, Motor.NUDGE_MAX, 0),
                new Vec3(Motor.NUDGE_MAX, 0, 0),
                new Vec3(-Motor.NUDGE_MAX, 0, 0),
                new Vec3(0, 0, Motor.NUDGE_MAX),
                new Vec3(0, 0, -Motor.NUDGE_MAX)
        };
        for (Vec3 delta : tries) {
            if (!Motor.nudge(body, delta, "settle")) return;
            if (body.level().noCollision(body)) return;
            Motor.nudge(body, delta.scale(-1), "settle_revert");
        }
        Motor.warn(body, "settle nudge refused still_colliding={}", GameplayLog.at(body.position()));
    }
}
