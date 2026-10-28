package io.github.longshou.mcyukkuri.status;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.SpeciesProfile.Ecology;
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
    /** A regular jump reaches about 1.25 blocks and the trampoline bounce about 1.60. */
    private static final double AIRBORNE_HEIGHT = 1.75;
    private static final int AIRBORNE_TICKS = 4;
    private static final int AIRBORNE_COOLDOWN = 600;
    private final YukkuriEntity entity;
    private final Marks marks;
    private final Reactions reactions;
    private final Map<StatusKey, String> sampled = new EnumMap<>(StatusKey.class);
    private int glow;
    private double groundY, lastY;
    private int airborneTicks;
    private long nextAirborneSpeech;
    private boolean airborneKnown, airborneRose, airborneSpoken;

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
        airborneTick();
        if (t % 20 == 7) {
            if (marks.tick(identity())) entity.syncMarks(Marks.write(marks.visible()));
            if (StatusEvents.trace()) watch();
            else sampled.clear();
        }
    }

    /**
     * A clearly airborne non-flying species may use the L36 "it feels like flying in the sky" phrase. This only observes resolved
     * movement: it never starts a goal, alters velocity, or treats the family's controlled trajectories as flight.
     */
    private void airborneTick() {
        double y = entity.getY();
        var family = entity.family();
        if (entity.profile().ecology() == Ecology.PREDATOR || entity.isPassenger() || entity.isInWaterOrBubble() || entity.waterDistress().active()
                || family.laboring() || family.birthDuty() || family.awaitingLanding()) {
            // A carried or rescued body starts a new observation after release; do not inherit the lift's height.
            airborneKnown = airborneRose = airborneSpoken = false;
            airborneTicks = 0;
            return;
        }
        if (entity.onGround()) {
            airborneKnown = true; groundY = lastY = y;
            airborneTicks = 0; airborneRose = airborneSpoken = false;
            return;
        }
        if (!airborneKnown) { groundY = lastY = y; airborneKnown = true; return; }
        airborneTicks++;
        if (y > lastY + .025 || entity.getDeltaMovement().y > .10) airborneRose = true;
        lastY = y;
        if (airborneSpoken || airborneTicks < AIRBORNE_TICKS || !airborneRose || y - groundY < AIRBORNE_HEIGHT
                || airborneSpeechBlocked()) return;
        long now = entity.level().getGameTime();
        if (now < nextAirborneSpeech) { airborneSpoken = true; return; }
        if (entity.speak("airborne")) {
            airborneSpoken = true;
            nextAirborneSpeech = now + AIRBORNE_COOLDOWN;
            GameplayLog.event(entity, "status", "airborne speech height={} airborne_ticks={} dialogue={}",
                    y - groundY, airborneTicks, entity.getDialogueKey());
        }
    }

    /** A short hurt window may still yield the line later in the same free-flight episode. */
    private boolean airborneSpeechBlocked() {
        return entity.isOnFire() || entity.hurtTicks() > 0
                || reactions.threat().stage() != Reactions.Threat.Stage.NONE;
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

    /** A physical hit was accepted: kin and visible witnesses react, including when the victim dies. */
    public void hurt(DamageSource source) { reactions.hurtBy(source); }

    /** Orange juice restored the body: a short rising orange shimmer, sent to every nearby client. */
    public void orangeRestored() {
        glow = 50;
        if (StatusEvents.trace()) StatusEvents.note(entity, "orange juice glow started");
        if (entity.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, entity.getX(), entity.getY() + entity.getBbHeight() * .5, entity.getZ(),
                    10, entity.getBbWidth() * .4, entity.getBbHeight() * .3, entity.getBbWidth() * .4, 0);
        }
    }

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
        airborneKnown = airborneRose = airborneSpoken = false; airborneTicks = 0; nextAirborneSpeech = 0;
    }
}
