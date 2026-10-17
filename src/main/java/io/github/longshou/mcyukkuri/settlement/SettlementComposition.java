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
    /** The fixed exhibit group: the leader, a family of three and a family of two. */
    public static SettlementComposition demo() {
        return new SettlementComposition(List.of(new Household(2, 1), new Household(1, 1)), true);
    }
    /** A group sized for a planned site (band 4-8, village 10-20 including the leader); never more households than burrows. */
    public static SettlementComposition forTier(io.github.longshou.mcyukkuri.settlement.site.SiteTier tier, RandomSource random, boolean leader) {
        if (tier == io.github.longshou.mcyukkuri.settlement.site.SiteTier.DEMO) return demo();
        boolean led = leader && tier.hall;
        int children = Math.max(1, Math.min(3, YukkuriConfig.HOUSEHOLD_MAX_CHILDREN.get()));
        for (int attempt = 0; attempt < 12; attempt++) {
            int count = tier.minHouseholds + random.nextInt(tier.maxHouseholds - tier.minHouseholds + 1);
            var households = new ArrayList<Household>();
            for (int i = 0; i < count; i++) {
                int parents = random.nextInt(100) < 25 ? 1 : 2;
                households.add(new Household(parents, parents == 2 && random.nextInt(100) < 15 ? 0 : 1 + random.nextInt(children)));
            }
            var result = new SettlementComposition(List.copyOf(households), led);
            if (result.size() >= tier.minPopulation && result.size() <= tier.maxPopulation) return result;
        }
        var households = new ArrayList<Household>();
        households.add(new Household(2, 2)); households.add(new Household(1, 1));
        if (tier == io.github.longshou.mcyukkuri.settlement.site.SiteTier.VILLAGE) { households.add(new Household(2, 2)); households.add(new Household(2, 1)); }
        return new SettlementComposition(List.copyOf(households), led);
    }
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
