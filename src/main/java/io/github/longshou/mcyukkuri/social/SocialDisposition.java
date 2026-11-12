package io.github.longshou.mcyukkuri.social;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData.Relation;
import net.minecraft.world.entity.Entity;

/** Interaction-time reads. The inputs remain with their existing owners. */
public final class SocialDisposition {
    private static final double DAY = 24000;

    private SocialDisposition() {}

    public static double selfEsteem(YukkuriEntity entity) {
        var personality = entity.life().personality();
        double base = Math.clamp(48 + .28 * (personality.innate(YukkuriPersonality.Trait.BOLDNESS) - 50)
                - .18 * (personality.innate(YukkuriPersonality.Trait.FRIENDLINESS) - 50), 32, 72);
        var hat = entity.life().custody();
        long now = entity.level().getGameTime();
        // Older hatless saves can lack the start of the loss.
        double loss = !hat.lost() ? 0 : hat.since() == 0 && hat.cause().isEmpty() ? 1 : ramp(now - hat.since(), 0, DAY);
        var body = entity.body().condition();
        double illness = Math.max(ramp(body.painSeverity(), 0, 3),
                Math.max(ramp(Math.max(body.infection(), body.mold()), 0, 3), body.sick() ? 1 : 0));
        boolean slave = entity.settlement().society().slave();
        long freed = entity.settlement().lastFreedAt();
        double recovery = slave || freed < 0 ? 0 : 1 - ramp(now - freed, 0, DAY);
        return Math.clamp(base + 2 * entity.pet().effectiveGrade().id() + (entity.treasure().count() > 0 ? 2 : 0)
                - (slave ? 14 : 0) - 12 * loss - 6 * illness - 10 * recovery, 0, 100);
    }

    static double humanWariness(YukkuriEntity entity, Entity person, Relation relation) {
        int boldness = entity.life().personality().boldness();
        double stranger = entity.status().identity().wild()
                ? .50 + .35 * (1 - ramp(boldness, 40, 90))
                : .12 + .34 * (1 - ramp(boldness, 35, 65));
        stranger *= 1 - ramp(relation.closeness(), 0, 8);
        var toward = entity.position().subtract(person.position()).multiply(1, 0, 1).normalize();
        // ServerPlayer supplies its accepted packet movement here. Our own approach does not count as a rush.
        double closing = 20 * person.getKnownMovement().dot(toward);
        double rush = .85 * ramp(closing, .75, 3.5) * (1 - ramp(entity.distanceTo(person), 1.5, 6));
        return Math.max(Math.max(stranger, rush), wariness(entity, relation));
    }

    public static double wariness(YukkuriEntity entity, Relation relation) {
        return Math.max(ramp(relation.fear(), 15, 60), Math.max(.70 * ramp(entity.mind().unease(), 35, 80),
                .45 * (1 - ramp(entity.body().healthRatio(), .30, .65))));
    }

    public static double confidence(double esteem) { return ramp(esteem, 44, 56); }

    public static double insistence(YukkuriPersonality personality, double esteem, double wariness, Relation relation) {
        double restraint = Math.min(1 - ramp(personality.friendliness(), 35, 62), 1 - ramp(personality.selfControl(), 35, 62));
        return Math.min(confidence(esteem), Math.min(restraint, ramp(personality.boldness(), 35, 72)))
                * (1 - .50 * wariness) * (1 - ramp(relation.closeness(), 0, 20));
    }

    static double demandProbability(YukkuriPersonality personality, double esteem, double wariness, Relation relation) {
        return ramp(insistence(personality, esteem, wariness, relation), .20, .52);
    }

    public static double ramp(double value, double low, double high) {
        return Math.clamp((value - low) / (high - low), 0, 1);
    }
}
