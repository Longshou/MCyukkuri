package io.github.longshou.mcyukkuri.subspecies;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.level.biome.Biome;

/** Server-owned shell pose clock. Rain avoidance and the damage multiplier are project design. */
public final class ShellRetraction {
    private static final int INSIDE = 36, OUT_START = 64, OUT_END = 110;
    private final YukkuriEntity entity;
    private int frame = -1;
    private long lastTick = Long.MIN_VALUE;

    public ShellRetraction(YukkuriEntity entity) { this.entity = entity; }
    public boolean hasShell() {
        return entity.subspecies().kind() == SubspeciesLife.Kind.SHELL_MARISA && entity.life().hasAccessory();
    }
    public boolean available() {
        return hasShell() && entity.isAlive() && !entity.isNoAi() && !entity.isInWaterOrBubble() && !entity.isOnFire()
                && !entity.isPassenger() && !entity.operations().active() && !entity.isCommandPlaying();
    }
    public boolean exposedRain() {
        var pos = entity.blockPosition();
        return entity.level().isRainingAt(pos)
                && entity.level().getBiome(pos).value().getPrecipitationAt(pos) == Biome.Precipitation.RAIN;
    }
    private boolean rainWanted() {
        var life = entity.life();
        return exposedRain() && !life.hasUrgentNeed() && life.nutrition() >= 45 && life.energy() >= 35
                && life.bladder() < 70 && life.dryness() < 40 && !life.isSleeping() && life.mealTicks() == 0
                && !life.temperature().cold() && !life.temperature().hot()
                && life.activity().equals(Behaviors.IDLE) && !entity.family().active();
    }
    public boolean active() { return frame >= 0; }
    public int frame() { return frame; }
    public boolean controlsMovement() {
        return available() && (active() || entity.isPanicking() || rainWanted());
    }
    public void reset() { frame = -1; lastTick = Long.MIN_VALUE; }

    /** Remaining fright includes the outward phase; rain itself never extends the fright timer. */
    public void tick(int frightTicks) {
        if (entity.level().isClientSide) return;
        if (!available()) { reset(); return; }
        long now = entity.level().getGameTime();
        int step = now == lastTick ? 0 : 1; lastTick = now;
        boolean hold = frightTicks > OUT_END-OUT_START || rainWanted();
        if (hold) {
            if (frame < 0) frame = 0;
            else if (frame > INSIDE) frame = enteringFrame(frame);
            else frame = Math.min(INSIDE, frame+step);
        } else if (frame >= 0) {
            if (frame <= INSIDE) frame = leavingFrame(frame);
            else frame += step;
            if (frame >= OUT_END) frame = -1;
        }
        if (active()) { entity.getNavigation().stop(); entity.poseShellRetraction(frame); }
    }

    // Match the existing body tracks: 0-.5-1.8 inward, 3.2-4.8-5.5 outward.
    private static int leavingFrame(int inward) {
        return inward <= 10 ? Math.round(OUT_END-inward*14f/10) : Math.round(96-(inward-10)*32f/26);
    }
    private static int enteringFrame(int outward) {
        return outward >= 96 ? Math.round((OUT_END-outward)*10f/14) : Math.round(10+(96-outward)*26f/32);
    }
    public boolean protects(DamageSource source) {
        if (!hasShell() || entity.isInWaterOrBubble() || entity.isOnFire()
                || source.is(DamageTypeTags.IS_FIRE) || source.is(DamageTypeTags.IS_DROWNING)
                || !externalBlow(source)
                || !(active() || entity.isCommandPlaying())
                || !entity.getAnimationId().equals(YukkuriAction.SHELL_RETRACT.id())) return false;
        long elapsed = entity.level().getGameTime()-entity.getAnimationStartTick();
        if (elapsed < 0) return false;
        if (!active()) elapsed %= YukkuriAction.SHELL_RETRACT.durationTicks();
        return elapsed >= 24 && elapsed <= 76;
    }

    /** A shell absorbs external blows, never internal physiology or unknown generic damage. */
    private static boolean externalBlow(DamageSource source) {
        return source.is(DamageTypeTags.IS_PROJECTILE) || source.is(DamageTypeTags.IS_EXPLOSION)
                || source.is(DamageTypeTags.IS_FALL) || source.is(DamageTypes.PLAYER_ATTACK)
                || source.is(DamageTypes.MOB_ATTACK) || source.is(DamageTypes.MOB_ATTACK_NO_AGGRO)
                || source.is(DamageTypes.FLY_INTO_WALL) || source.is(DamageTypes.FALLING_BLOCK)
                || source.is(DamageTypes.FALLING_ANVIL) || source.is(DamageTypes.FALLING_STALACTITE)
                || source.is(DamageTypes.STALAGMITE) || source.is(DamageTypes.CACTUS)
                || source.is(DamageTypes.SWEET_BERRY_BUSH)
                || source.is(io.github.longshou.mcyukkuri.life.Facilities.ABRASION);
    }
}
