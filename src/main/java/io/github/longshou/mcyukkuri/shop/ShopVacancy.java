package io.github.longshou.mcyukkuri.shop;

import java.util.Collection;
import java.util.Set;

/** A litter still in baskets needs one room, including when its first sibling already lives there. */
record ShopVacancy(int singles, int predators, int families) {
    static ShopVacancy plan(int freeSingles, int freePredators, int freeFamilies, int waitingSingles,
                            int waitingPredators, Collection<ShopLitter> waitingLitters, Set<ShopLitter> housedLitters) {
        int waitingFamilies = (int) waitingLitters.stream().distinct().filter(litter -> !housedLitters.contains(litter)).count();
        return new ShopVacancy(Math.max(0, freeSingles - waitingSingles), Math.max(0, freePredators - waitingPredators),
                Math.max(0, freeFamilies - waitingFamilies));
    }
}
