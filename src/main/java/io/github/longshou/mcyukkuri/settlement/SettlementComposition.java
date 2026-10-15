package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.life.YukkuriConfig;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.util.RandomSource;

/** Initial encounter composition; living households may grow, separate or lose members later. */
public record SettlementComposition(List<Household> households, boolean leader) {
    public record Household(int parents, int children) {
        public int size() { return parents + children; }
    }
    public int size() { return households.stream().mapToInt(Household::size).sum() + (leader ? 1 : 0); }
    public static SettlementComposition sample(SettlementSpawner.Preset preset, RandomSource random) {
        if (preset == SettlementSpawner.Preset.WANDERER || preset == SettlementSpawner.Preset.BANDITS) return new SettlementComposition(List.of(), false);
        int minimum = Math.min(YukkuriConfig.FOREST_MIN_HOUSEHOLDS.get(), YukkuriConfig.FOREST_MAX_HOUSEHOLDS.get());
        int maximum = Math.max(YukkuriConfig.FOREST_MIN_HOUSEHOLDS.get(), YukkuriConfig.FOREST_MAX_HOUSEHOLDS.get());
        int count = preset == SettlementSpawner.Preset.FAMILY ? 1 : minimum + random.nextInt(maximum - minimum + 1);
        var households = new ArrayList<Household>();
        for (int i = 0; i < count; i++) {
            int parents = random.nextInt(100) < 25 ? 1 : 2;
            int children = parents == 2 && random.nextInt(100) < 20 ? 0 : 1 + random.nextInt(YukkuriConfig.HOUSEHOLD_MAX_CHILDREN.get());
            households.add(new Household(parents, children));
        }
        return new SettlementComposition(List.copyOf(households), preset == SettlementSpawner.Preset.FOREST_LED);
    }
}
