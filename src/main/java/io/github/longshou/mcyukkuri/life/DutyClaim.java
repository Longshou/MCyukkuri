package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.Duty.End;
import io.github.longshou.mcyukkuri.life.Duty.Kind;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.EnumMap;

/** The body's current duty and the earliest time it can reclaim each kind. */
public final class DutyClaim {
    private final YukkuriEntity body;
    private final EnumMap<Kind, Long> coolUntil = new EnumMap<>(Kind.class);
    private Kind kind;
    private long since;
    private long deadline;
    private int failCooldown;

    public DutyClaim(YukkuriEntity body) { this.body = body; }

    public boolean claimed() { return kind != null; }
    public boolean holds(Kind wanted) { return kind == wanted; }
    public Kind kind() { return kind; }

    public boolean mayTake(Kind wanted) {
        return Duty.mayReplace(kind, wanted) && now() >= coolUntil.getOrDefault(wanted, 0L);
    }

    public boolean take(Kind wanted, int ticks, int failureCooldown, String why) {
        if (!mayTake(wanted)) {
            String reason = Duty.mayReplace(kind, wanted) ? "cooldown" : "outranked";
            GameplayLog.limited(body, "duty", "refused." + wanted + "." + reason, 600,
                    "refused kind={} reason={} holder={}", wanted, reason, kind);
            return false;
        }
        if (holds(wanted)) return true;
        if (claimed()) release(kind, End.REPLACED, why);
        kind = wanted;
        since = now();
        deadline = since + ticks;
        failCooldown = failureCooldown;
        GameplayLog.event(body, "duty", "claimed kind={} ticks={} why={}", kind, ticks, why);
        return true;
    }

    public void release(Kind held, End end, String why) {
        if (!holds(held)) return;
        long now = now();
        coolUntil.put(held, Duty.coolUntil(end, now, failCooldown));
        kind = null;
        GameplayLog.event(body, "duty", "released kind={} end={} why={} held={}", held, end, why, now - since);
    }

    public void tick() {
        if (body.level().isClientSide || !claimed()) return;
        End end = Duty.lapse(now(), deadline, BehaviorContinuity.emergency(body) || body.hurtTime > 0);
        if (end != null) release(kind, end, end == End.EMERGENCY ? "emergency" : "deadline");
    }

    public String describe() {
        long now = now();
        var cooling = new EnumMap<Kind, Long>(Kind.class);
        coolUntil.forEach((key, time) -> { if (time > now) cooling.put(key, time - now); });
        return (claimed() ? kind + " left=" + (deadline - now) : "none")
                + (cooling.isEmpty() ? "" : " cool=" + cooling);
    }

    private long now() { return body.level().getGameTime(); }
}
