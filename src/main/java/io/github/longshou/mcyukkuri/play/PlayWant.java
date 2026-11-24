package io.github.longshou.mcyukkuri.play;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;

/** Shared play drive (boredom above floor, enough energy, calm mind). One authority for playgrounds and keepsake play. */
public final class PlayWant {
    private PlayWant() {}

    public static int boredomFloor(YukkuriEntity entity) {
        double timid = entity.life().personality().temperament().timid();
        return Math.max(10, (int) Math.round(15 + 20 * timid) - (entity.family().adult() ? 0 : 5));
    }

    public static double stopEnergy(YukkuriEntity entity) {
        return entity.outing().onOuting() ? Math.max(35, entity.outing().restBand()) : 35;
    }

    public static double scareCeiling(YukkuriEntity entity) {
        return 75 - 45 * entity.life().personality().temperament().timid();
    }

    public static boolean wants(YukkuriEntity entity) {
        return entity.dailyLife().boredom() > boredomFloor(entity)
                && entity.life().energy() >= stopEnergy(entity)
                && entity.mind().unease() < scareCeiling(entity);
    }
}
