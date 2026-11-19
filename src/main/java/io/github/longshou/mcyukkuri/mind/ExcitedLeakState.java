package io.github.longshou.mcyukkuri.mind;

import io.github.longshou.mcyukkuri.body.BodyEffects;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.random.ModRandom;
import io.github.longshou.mcyukkuri.life.ToiletContact;
import io.github.longshou.mcyukkuri.mind.ExcitedLeak.Stimulus;
import net.minecraft.nbt.CompoundTag;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.Locale;
import net.minecraft.world.phys.Vec3;

/** World state of a leak, including pressure discharge and its one puddle event. */
final class ExcitedLeakState {
    private final YukkuriEntity entity;
    private ExcitedLeak.Beat beat;
    private boolean puddleEmitted;

    ExcitedLeakState(YukkuriEntity entity) { this.entity = entity; }

    void spike(Stimulus stimulus, long now) {
        if (!entity.isAlive()) return;
        var stage = entity.family().stage();
        int control = entity.life().personality().selfControl();
        float pressure = entity.life().urine();
        String stageId = stage.name().toLowerCase(Locale.ROOT);
        if (beat != null && beat.cooling(now)) {
            if (stimulus == Stimulus.DROP)
                GameplayLog.event(entity, "leak", "stage={} stimulus=drop result=cooldown pressure={} eligible_at={}",
                        stageId, pressure, beat.start() + 1200);
            return;
        }
        // Keep this presentation draw off the entity's gameplay stream, including during seeded smoke runs.
        var id = entity.getUUID();
        var runSeed = ModRandom.runSeed();
        long seed = id.getMostSignificantBits() ^ id.getLeastSignificantBits() ^ Long.rotateLeft(now, 32)
                ^ (runSeed == null ? 0 : runSeed);
        var outcome = ExcitedLeak.roll(stage.name(), control, stimulus, seed, now, pressure, entity.getBbWidth());
        if (outcome.beat() == null) {
            if (stimulus == Stimulus.DROP)
                GameplayLog.event(entity, "leak", "stage={} self_control={} stimulus=drop result=dry roll={} chance={} pressure={}",
                        stageId, control, outcome.roll(), outcome.chance(), pressure);
            return;
        }

        // The startle atlas already shows the first spurt. Charge the whole bounded serving here.
        beat = outcome.beat();
        entity.life().dischargeUrine(beat.amount());
        puddleEmitted = false;
        GameplayLog.event(entity, "leak", "stage={} self_control={} stimulus={} result=flow roll={} chance={} flow_at={} puddle_at={} width={} pressure={} -> {} released={}",
                stageId, control, stimulus.name().toLowerCase(Locale.ROOT), outcome.roll(), outcome.chance(),
                beat.flowAt(), beat.puddleAt(), beat.width(), pressure, entity.life().urine(), beat.amount());
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
                Vec3 end = outlet.add(forward.scale(ExcitedLeak.puddleReach(beat.width())));
                ToiletContact.stream(entity, outlet, end.add(0, -beat.width() * .25, 0), beat.amount());
                BodyEffects.emit(entity, ExcitedLeak.PUDDLE, beat.width(), end, forward);
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
        beat = tag.isEmpty() ? null : new ExcitedLeak.Beat(tag.getLong("Start"), tag.getLong("FlowAt"),
                tag.getLong("PuddleAt"), tag.getLong("End"), tag.getFloat("Width"), tag.getFloat("Amount"), tag.getBoolean("Joy"));
        puddleEmitted = tag.getBoolean("PuddleEmitted");
    }
}
