package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.social.Contest;
import io.github.longshou.mcyukkuri.social.SocialDisposition;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;

/** What a bed and a loose food portion are to a {@link Contest}: how badly a body wants them and what the verdict does to it. */
public final class ContestPrizes {
    private ContestPrizes() {}

    /** A free bed at its lip: wanted as much as its claimant is tired, more at night, in rain and in the cold. */
    public static Contest.Prize bed(BlockPos bed) {
        return new Contest.Prize() {
            @Override public String kind() { return "bed"; }
            @Override public String where() { return bed.toString(); }
            @Override public boolean present(YukkuriEntity probe) { return NestDoorway.bed(probe, bed) != null; }
            @Override public int need(YukkuriEntity body) { return 100 - Math.round(body.life().energy()); }
            @Override public int importance(YukkuriEntity body) {
                var level = body.level();
                double pull = 28 + 32 * SocialDisposition.ramp(100 - body.life().energy(), 15, 85);
                int weather = (level.isNight() ? 8 : 0) + (level.isRainingAt(bed) ? 7 : 0)
                        + (level.getBiome(body.blockPosition()).value().getBaseTemperature() < .3 ? 7 : 0);
                var nest = NestDoorway.bed(body, bed);
                int grade = nest == null ? 1 : nest.grade();
                return (int) Math.round(Math.clamp(pull + weather + (grade - 1) * 6, 0, 100));
            }
            @Override public String winLine() { return "contest_won_bed"; }
            @Override public void settle(YukkuriEntity body, boolean won, UUID rival) { body.life().bedVerdict(bed, won, rival); }
        };
    }

    /** One portion of loose food too small for both: wanted as much as its claimant is hungry, more when it is tasty and the only food near. */
    public static Contest.Prize food(ItemEntity food) {
        return new Contest.Prize() {
            @Override public String kind() { return "food"; }
            @Override public String where() { return food.blockPosition().toString(); }
            @Override public boolean present(YukkuriEntity probe) { return food.isAlive(); }
            @Override public int need(YukkuriEntity body) { return 100 - Math.round(body.life().nutrition()); }
            @Override public int importance(YukkuriEntity body) {
                double pull = 22 + 58 * SocialDisposition.ramp(100 - body.life().nutrition(), 25, 85);
                int taste = switch (FoodTaste.grade(food.getItem())) {
                    case COARSE -> 0; case ORDINARY -> 4; case FINE -> 8; case TREAT -> 12;
                };
                boolean only = body.level().getEntitiesOfClass(ItemEntity.class, food.getBoundingBox().inflate(8),
                        other -> other != food && other.isAlive() && !other.getItem().isEmpty()).isEmpty();
                return (int) Math.round(Math.clamp(pull + taste + (only ? 3 : 0), 0, 100));
            }
            @Override public String winLine() { return "contest_won_food"; }
            @Override public void settle(YukkuriEntity body, boolean won, UUID rival) { body.life().foodVerdict(food, won, rival); }
        };
    }
}
