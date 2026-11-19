package io.github.longshou.mcyukkuri.mind;

import io.github.longshou.mcyukkuri.body.BodyEffects;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.random.ModRandom;
import io.github.longshou.mcyukkuri.life.ToiletContact;
import net.minecraft.nbt.CompoundTag;
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
    public enum Stimulus {
        SCARE(false), SCOLD(false), LOUD_NOISE(false), JOY(false),
        SUDDEN_LIFT(true), DROP(true), PREDATOR(true), DYING(true);

        private final boolean strong;
        Stimulus(boolean strong) { this.strong = strong; }
    }

    private final YukkuriEntity entity;
    private LeakProfile.Beat beat;
    private boolean puddleEmitted;

    ExcitedLeak(YukkuriEntity entity) { this.entity = entity; }

    void spike(Stimulus stimulus, long now) {
        if (!entity.isAlive()) return;
        var stage = entity.family().stage();
        var profile = LeakProfile.valueOf(stage.name());
        int control = entity.life().personality().selfControl();
        float pressure = entity.life().urine();
        String stageId = stage.name().toLowerCase(Locale.ROOT);
        if (beat != null && beat.cooling(now)) {
            if (stimulus == Stimulus.DROP)
                GameplayLog.event(entity, "leak", "stage={} stimulus=drop result=cooldown pressure={} eligible_at={}",
                        stageId, pressure, beat.start() + 1200);
            return;
        }
        float chance = profile.chance(stimulus.strong, control, pressure);
        if (chance == 0 && stimulus != Stimulus.DROP) return;
        // Keep this presentation draw off the entity's gameplay stream, including during seeded smoke runs.
        var id = entity.getUUID();
        var runSeed = ModRandom.runSeed();
        var random = new SplittableRandom(id.getMostSignificantBits() ^ id.getLeastSignificantBits()
                ^ Long.rotateLeft(now, 32) ^ (runSeed == null ? 0 : runSeed));
        float roll = random.nextFloat();
        if (!new LeakProfile.Draw(roll, chance).wins()) {
            if (stimulus == Stimulus.DROP)
                GameplayLog.event(entity, "leak", "stage={} self_control={} stimulus=drop result=dry roll={} chance={} pressure={}",
                        stageId, control, roll, chance, pressure);
            return;
        }

        // The startle atlas already shows the first spurt. Charge the whole bounded serving here.
        float amount = entity.life().dischargeUrine(profile.portion(control, pressure));
        long flowAt = now + random.nextInt(6, 11), puddleAt = flowAt + profile.flow(control);
        float width = profile.width(control, entity.getBbWidth(), amount);
        beat = new LeakProfile.Beat(now, flowAt, puddleAt, puddleAt + random.nextInt(60, 101), width, amount, stimulus == Stimulus.JOY);
        puddleEmitted = false;
        GameplayLog.event(entity, "leak", "stage={} self_control={} stimulus={} result=flow roll={} chance={} flow_at={} puddle_at={} width={} pressure={} -> {} released={}",
                stageId, control, stimulus.name().toLowerCase(Locale.ROOT), roll, chance, flowAt, puddleAt, width,
                pressure, entity.life().urine(), amount);
    }

    void tick(long now) {
        if (beat == null) return;
        if (!beat.cooling(now)) {
            beat = null;
        } else if (now >= beat.puddleAt()) {
            if (entity.leakTraceAt() != beat.puddleAt()) entity.syncLeakTrace(beat.puddleAt());
            if (!puddleEmitted && now == beat.puddleAt()) {
                puddleEmitted = true;
                Vec3 forward = Vec3.directionFromRotation(0, entity.bodyYaw());
                Vec3 outlet = ToiletContact.urineOutlet(entity);
                Vec3 end = outlet.add(forward.scale(.2 * beat.width()));
                ToiletContact.stream(entity, outlet, end.add(0, -beat.width() * .25, 0), beat.amount());
                BodyEffects.emit(entity, PUDDLE, beat.width(), end, forward);
            }
        }
    }

    String face(long now) { return beat == null ? "" : beat.face(now); }

    void save(CompoundTag mind) {
        if (beat == null) return;
        var tag = new CompoundTag();
        tag.putLong("Start", beat.start());
        tag.putLong("FlowAt", beat.flowAt());
        tag.putLong("PuddleAt", beat.puddleAt());
        tag.putLong("End", beat.endAt());
        tag.putFloat("Width", beat.width());
        tag.putFloat("Amount", beat.amount());
        tag.putBoolean("Joy", beat.joy());
        tag.putBoolean("PuddleEmitted", puddleEmitted);
        mind.put("StartleLeak", tag);
    }

    void load(CompoundTag mind) {
        var tag = mind.getCompound("StartleLeak");
        beat = tag.isEmpty() ? null : new LeakProfile.Beat(tag.getLong("Start"), tag.getLong("FlowAt"),
                tag.getLong("PuddleAt"), tag.getLong("End"), tag.getFloat("Width"), tag.getFloat("Amount"), tag.getBoolean("Joy"));
        puddleEmitted = tag.getBoolean("PuddleEmitted");
    }
}
