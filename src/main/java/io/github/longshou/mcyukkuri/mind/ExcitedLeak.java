package io.github.longshou.mcyukkuri.mind;

import io.github.longshou.mcyukkuri.body.BodyEffects;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.random.ModRandom;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.Locale;
import java.util.SplittableRandom;
import net.minecraft.world.phys.Vec3;

/** A transient presentation reaction to a real spike. The balance ranges are provisional project design. */
public final class ExcitedLeak {
    public static final String STARTLE_FACE = "leak_startle", FLOW_FACE = "leak_flow";
    public static final String PUDDLE = "excited_leak";
    public static final float STARTLED_FALL_DISTANCE = 1.5f;
    public static final int TRACE_LIFETIME = 800, TRACE_FADE_TICKS = 200;
    private static final int COOLDOWN = 1200;
    private static final int FLOW_RANGE = 20;
    private static final Band BABY = new Band(.50f, .80f, .20f, .40f, 40);
    private static final Band CHILD = new Band(.25f, .45f, .05f, .15f, 30);
    private static final Band ADULT = new Band(.05f, .15f, 0, 0, 20);

    public enum Stimulus {
        SCARE(false), SCOLD(false), LOUD_NOISE(false), JOY(false),
        SUDDEN_LIFT(true), DROP(true), PREDATOR(true), DYING(true);

        private final boolean strong;
        Stimulus(boolean strong) { this.strong = strong; }
    }

    private record Band(float strongMin, float strongMax, float moderateMin, float moderateMax, int minFlow) {
        float chance(Stimulus stimulus, float impulse) {
            return stimulus.strong ? strongMin + (strongMax - strongMin) * impulse
                    : moderateMin + (moderateMax - moderateMin) * impulse;
        }
    }

    private final YukkuriEntity entity;
    private long nextLeak;
    private Beat beat;
    private boolean puddleEmitted;

    private record Beat(long flowAt, long puddleAt, long endAt, float width, String afterFace) {}

    ExcitedLeak(YukkuriEntity entity) { this.entity = entity; }

    void spike(Stimulus stimulus, long now) {
        if (!entity.isAlive() || now < nextLeak) return;
        var stage = entity.family().stage();
        Band band = switch (stage) {
            case BABY -> BABY;
            case JUVENILE -> CHILD;
            case ADULT -> ADULT;
        };
        int control = entity.life().personality().selfControl();
        float impulse = 1f - control / 100f;
        float chance = band.chance(stimulus, impulse);
        if (chance <= 0) return;
        // Keep this presentation draw off the entity's gameplay stream, including during seeded smoke runs.
        var id = entity.getUUID();
        var runSeed = ModRandom.runSeed();
        var random = new SplittableRandom(id.getMostSignificantBits() ^ id.getLeastSignificantBits()
                ^ Long.rotateLeft(now, 32) ^ (runSeed == null ? 0 : runSeed));
        float roll = random.nextFloat();
        if (roll >= chance) return;

        int start = random.nextInt(6, 11), flow = band.minFlow + Math.round(FLOW_RANGE * impulse);
        float width = Math.max(.12f, entity.getBbWidth() * (.5f + .2f * (flow - band.minFlow) / FLOW_RANGE));
        long puddleAt = now + start + flow;
        beat = new Beat(now + start, puddleAt, puddleAt + random.nextInt(60, 101), width,
                stimulus == Stimulus.JOY ? "satisfied" : "side_teary");
        puddleEmitted = false;
        nextLeak = now + COOLDOWN;
        GameplayLog.event(entity, "leak", "stage={} self_control={} stimulus={} roll={} chance={} flow_at={} puddle_at={} width={}",
                stage.name().toLowerCase(Locale.ROOT), control, stimulus.name().toLowerCase(Locale.ROOT),
                roll, chance, beat.flowAt, puddleAt, width);
    }

    void tick(long now) {
        if (beat == null) return;
        if (now >= beat.endAt) {
            beat = null;
        } else if (now >= beat.puddleAt && !puddleEmitted) {
            puddleEmitted = true;
            entity.syncLeakTrace(now);
            Vec3 forward = Vec3.directionFromRotation(0, entity.bodyYaw());
            Vec3 outlet = entity.position().add(forward.scale(entity.getBbWidth() / 2 + .2 * beat.width));
            BodyEffects.emit(entity, PUDDLE, beat.width, outlet, forward);
        }
    }

    String face(long now) {
        if (beat == null) return "";
        if (now < beat.flowAt) return STARTLE_FACE;
        return now < beat.puddleAt ? FLOW_FACE : beat.afterFace;
    }
}
