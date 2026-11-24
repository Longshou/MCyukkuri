package io.github.longshou.mcyukkuri.play;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.mind.MindLife;
import io.github.longshou.mcyukkuri.motor.BodyAbility;
import io.github.longshou.mcyukkuri.perception.Cue;
import io.github.longshou.mcyukkuri.perception.Senses;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Shared play want and facility preference, used by outing pets, lingering owners, strays, keepsake play and {@link PlayLife}.
 * No reservation tables: occupancy is always read from the world.
 */
public final class PlayWant {
    /** Minimum slide stair rise (blocks) that a climbing body must be able to scramble over. */
    private static final double SLIDE_STEP_RISE = 0.25;
    /** Novelty multiplier applied to the facility just used so a pet explores other toys when free. */
    private static final double REPEAT_NOVELTY = 0.55;
    /** Energy headroom above {@link #stopEnergy} required to start a trampoline session (avoids 1-bounce false starts). */
    private static final double TRAMPOLINE_START_MARGIN = 8.0;

    private PlayWant() {}

    /** Boredom floor below which this individual no longer wants ordinary play (~10-15 bold/young, 35 timid/old). */
    public static int boredomFloor(YukkuriEntity entity) {
        double timid = entity.life().personality().temperament().timid();
        int ageShift = entity.family().elder() ? 5 : entity.family().adult() ? 0 : -5;
        return Math.clamp((int) Math.round(15 + 20 * timid) + ageShift, 10, 35);
    }

    /** Energy floor below which this individual stops playing to rest. */
    public static double stopEnergy(YukkuriEntity entity) {
        return entity.outing().onOuting() ? Math.max(35, entity.outing().restBand()) : 35;
    }

    public static boolean tired(YukkuriEntity entity) {
        return entity.life().energy() < stopEnergy(entity);
    }

    public static double scareCeiling(YukkuriEntity entity) {
        return 75 - 45 * entity.life().personality().temperament().timid();
    }

    /** Whether the body is physically and socially free to play ({@code tub} permits juice-treatable wounds). */
    public static boolean bodyReady(YukkuriEntity entity, boolean tub, boolean active) {
        return entity.outing().permitsPerformance(true)
                && (active || entity.childhood().canExercise())
                && entity.isAlive() && !entity.isRemoved() && !entity.inert()
                && !entity.isPanicking() && !entity.isCommandPlaying() && !entity.isPassenger()
                && !entity.operations().active() && !entity.groupInteraction().active()
                && !(active
                        ? entity.life().nutrition() < 15 || entity.life().energy() < 15
                                || entity.isOnFire() || entity.isInWaterOrBubble()
                        : entity.life().hasUrgentNeed())
                && !entity.life().isSleeping() && entity.life().mealTicks() == 0
                && (active || !entity.species().catchingBreath())
                && entity.life().energy() >= (active ? 15 : 35)
                && (tub || entity.body().condition().readyForPlay())
                && !entity.family().active() && !entity.house().active();
    }

    /** The one play want: boredom above the individual's floor, calm mind, not tired, body ready. */
    public static boolean wants(YukkuriEntity entity) {
        return bodyReady(entity, false, false) && !tired(entity)
                && entity.mind().unease() < scareCeiling(entity)
                && entity.dailyLife().boredom() > boredomFloor(entity);
    }

    /** Juice tub want: only when dirty, hurt (juice-treatable) or uneasy, not tired, body otherwise free. */
    public static boolean wantsTub(YukkuriEntity entity) {
        return bodyReady(entity, true, false) && !tired(entity)
                && (entity.body().hasJuiceTreatableWound()
                        || entity.life().hygiene() < 55
                        || entity.mind().unease() >= MindLife.UNEASY);
    }

    /** Whether this individual wants to use {@code facility} right now. */
    public static boolean wants(YukkuriEntity entity, PlayBlockEntity facility) {
        if (facility == null || facility.isRemoved()) return false;
        return switch (facility.kind()) {
            case POOL -> facility.juice() > 0 && wantsTub(entity);
            case TRAMPOLINE -> wants(entity) && entity.childhood().canJump()
                    && !BodyAbility.cannotSpring(entity)
                    && entity.life().energy() >= stopEnergy(entity) + TRAMPOLINE_START_MARGIN;
            case SLIDE -> wants(entity) && BodyAbility.of(entity).scrambleRise() >= SLIDE_STEP_RISE;
            case CERAMIC -> wants(entity);
        };
    }

    /**
     * Preference score for {@code facility} derived from stats and novelty ({@code <= 0} when unwanted).
     * Trampoline: bold, light, energetic. Slide: bold, curious, can scramble. Tube: timid. Tub: dirty, hurt or uneasy.
     */
    public static double preference(YukkuriEntity entity, PlayBlockEntity facility, BlockPos lastUsed) {
        if (!wants(entity, facility)) return -1;
        var personality = entity.life().personality();
        var temp = personality.temperament();
        double bold = personality.boldness() / 100.0;
        double timid = temp.timid();
        double curious = temp.curious();
        double energy = Math.clamp((entity.life().energy() - 35) / 65.0, 0, 1);
        double weight = entity.getBbWidth() * (double) entity.getBbWidth() * entity.getBbHeight();
        double light = Math.clamp(1.3 - weight / 0.5, 0, 1);
        double score = switch (facility.kind()) {
            case TRAMPOLINE -> 0.25 + 0.45 * bold + 0.30 * light + 0.25 * energy;
            case SLIDE -> 0.25 + 0.35 * bold + 0.30 * curious + 0.15 * energy;
            case CERAMIC -> 0.25 + 0.65 * timid;
            case POOL -> entity.body().hasJuiceTreatableWound()
                    ? 1.5
                    : 0.4 + Math.max(0, 100 - entity.life().hygiene()) / 150.0 + entity.mind().unease() / 150.0;
        };
        if (lastUsed != null && lastUsed.equals(facility.getBlockPos())) score *= REPEAT_NOVELTY;
        return score;
    }

    /** True when this body wants to play and currently sees a wanted playground facility within {@code radius} via {@link Senses}. */
    public static boolean seesPlayable(YukkuriEntity entity, int radius) {
        if (!(entity.level() instanceof ServerLevel level) || !level.isDay() || level.isRaining() || !wants(entity)) return false;
        var origin = entity.blockPosition();
        for (var pos : BlockPos.betweenClosed(origin.offset(-radius, -2, -radius), origin.offset(radius, 2, radius))) {
            if (!level.hasChunkAt(pos)) continue;
            if (level.getBlockEntity(pos) instanceof PlayBlockEntity found && wants(entity, found)
                    && Senses.sees(entity, found.getBlockPos().getCenter(), p -> PlayBlock.facility(level, p) == found, Cue.Need.PLAY)) {
                return true;
            }
        }
        return false;
    }
}
