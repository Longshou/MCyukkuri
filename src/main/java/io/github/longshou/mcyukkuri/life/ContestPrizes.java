package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.social.Contest;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;

/** What a bed and a loose food portion are to a {@link Contest}: how badly a body wants them and what the verdict does to it. */
public final class ContestPrizes {
    private ContestPrizes() {}

    /** A free bed at its lip: wanted as much as its claimant is tired. */
    public static Contest.Prize bed(BlockPos bed) {
        return new Contest.Prize() {
            @Override public String kind() { return "bed"; }
            @Override public String where() { return bed.toString(); }
            @Override public boolean present(YukkuriEntity probe) { return NestDoorway.bed(probe, bed) != null; }
            @Override public int need(YukkuriEntity body) { return 100 - Math.round(body.life().energy()); }
            @Override public String winLine() { return "home_declare"; }
            @Override public void settle(YukkuriEntity body, boolean won, UUID rival) { body.life().bedVerdict(bed, won, rival); }
        };
    }

    /** One portion of loose food too small for both: wanted as much as its claimant is hungry. */
    public static Contest.Prize food(ItemEntity food) {
        return new Contest.Prize() {
            @Override public String kind() { return "food"; }
            @Override public String where() { return food.blockPosition().toString(); }
            @Override public boolean present(YukkuriEntity probe) { return food.isAlive(); }
            @Override public int need(YukkuriEntity body) { return 100 - Math.round(body.life().nutrition()); }
            @Override public String winLine() { return "mine"; }
            @Override public void settle(YukkuriEntity body, boolean won, UUID rival) { body.life().foodVerdict(food, won, rival); }
        };
    }
}
