package io.github.longshou.mcyukkuri.status;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import java.util.EnumMap;
import java.util.Map;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import org.joml.Vector3f;

/**
 * The per-individual end of the status hub. It owns what no other component owned (lasting surface marks, the
 * perception/reaction arbiter), samples the register on a slow clock to announce changes, and keeps the synced
 * appearance data current. The numbers themselves stay in the components the register reads from.
 */
public final class StatusLife {
    private static final Vector3f ORANGE = new Vector3f(1f, .55f, .1f);
    private final YukkuriEntity entity;
    private final Marks marks;
    private final Reactions reactions;
    private final Map<StatusKey, String> sampled = new EnumMap<>(StatusKey.class);
    private int glow;

    public StatusLife(YukkuriEntity entity) {
        this.entity = entity; marks = new Marks(entity); reactions = new Reactions(entity);
    }

    public Marks marks() { return marks; }
    public Reactions reactions() { return reactions; }
    public Identity identity() { return YukkuriStatus.identity(entity); }

    /** Server side, once per entity tick (after the components have ticked). */
    public void tick() {
        if (entity.level().isClientSide || !entity.isAlive()) return;
        entity.mind().ensureGivenName();
        if (glow > 0) glowTick();
        if (entity.isNoAi()) return;
        int t = entity.tickCount;
        if (t % 5 == 0) reactions.scan();
        if (t % 20 == 7) {
            if (marks.tick(identity())) entity.syncMarks(Marks.write(marks.visible()));
            watch();
        }
    }

    private void watch() {
        for (var key : StatusKey.values()) {
            String now;
            if (!key.readable()) continue;
            try { now = key.watched(entity); } catch (RuntimeException ex) { continue; }
            String before = sampled.put(key, now);
            if (before != null && !before.equals(now)) StatusEvents.changed(entity, key.id(), before, now);
        }
    }

    /** The individual was hurt: kin who can perceive it react. Called after the body has recorded the wound. */
    public void hurt(DamageSource source) { reactions.hurtBy(source); }

    /** Orange juice restored the body: a short rising orange shimmer, sent to every nearby client. */
    public void orangeRestored() {
        marks.treated();
        glow = 50;
        if (StatusEvents.trace()) StatusEvents.note(entity, "orange juice glow started");
        if (entity.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, entity.getX(), entity.getY() + entity.getBbHeight() * .5, entity.getZ(),
                    10, entity.getBbWidth() * .4, entity.getBbHeight() * .3, entity.getBbWidth() * .4, 0);
        }
    }
    public boolean glowing() { return glow > 0; }

    private void glowTick() {
        glow--;
        if (!(entity.level() instanceof ServerLevel level) || glow % 2 != 0) return;
        int age = 50 - glow;
        double radius = entity.getBbWidth() * .65, height = entity.getBbHeight();
        for (int arm = 0; arm < 2; arm++) {
            double angle = age * .42 + arm * Math.PI;
            double rise = Math.min(1, age / 44.0);
            level.sendParticles(new DustParticleOptions(ORANGE, 1.2f), entity.getX() + Math.cos(angle) * radius,
                    entity.getY() + height * (.05 + .95 * rise), entity.getZ() + Math.sin(angle) * radius, 1, 0, 0, 0, 0);
        }
        if (age % 6 == 0)
            level.sendParticles(ParticleTypes.END_ROD, entity.getX(), entity.getY() + height + .1, entity.getZ(), 2, radius * .5, .05, radius * .5, .01);
    }

    public CompoundTag save() {
        var tag = new CompoundTag();
        tag.put("Marks", marks.save());
        return tag;
    }
    public void load(CompoundTag tag) {
        marks.load(tag.getCompound("Marks"));
        sampled.clear(); glow = 0; reactions.stop();
    }
}
