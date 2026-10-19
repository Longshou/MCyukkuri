package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import net.minecraft.core.BlockPos;

/** Stateless home and rest policies. Resolve from the current profile at each entry;
 * addresses, search order and persistence still belong to LifeHome. Aquatic residents
 * use the ordinary land-home policy here; their boat is not a second saved HomePos.
 */
enum HomeProvider {
    ORDINARY,
    HOUSE {
        @Override BlockPos homeStand(YukkuriEntity entity) { return entity.house().homeStand(); }

        @Override FoodPlan beforeFood(YukkuriLife life) {
            if (life.temperature().unsafeSleep() && life.temperature().plan()) return FoodPlan.TEMPERATURE;
            // This can start work or consume the house goal's wait. Call only at the original planning slot.
            return life.entity().house().preferRoutine() ? FoodPlan.ROUTINE : FoodPlan.CONTINUE;
        }

        @Override boolean ordinaryRestAllowed(YukkuriLife life) {
            return homeStand(life.entity()) == null || life.energy() <= 10;
        }

        @Override boolean temporaryCoverAllowed() { return false; }

        @Override boolean finishRestEarly(YukkuriLife life) {
            return !life.restTime() && life.energy() >= 70;
        }
    },
    PREDATOR {
        @Override boolean acceptsPredatorNest() { return true; }

        @Override boolean canFlyTo(YukkuriEntity entity, BlockPos stand) {
            return entity.predator().canFlyTo(stand);
        }

        @Override SearchResult findHome(YukkuriEntity entity) {
            return switch (entity.predator().findNest(false)) {
                case FOUND -> SearchResult.FOUND;
                case NOT_FOUND -> SearchResult.NOT_FOUND;
                case DEFERRED -> SearchResult.DEFERRED;
            };
        }
    };

    enum FoodPlan { CONTINUE, TEMPERATURE, ROUTINE }

    /** A search result, not permission to erase an existing address or run a fallback search. */
    enum SearchResult {
        FOUND, NOT_FOUND, DEFERRED
    }

    static HomeProvider forEntity(YukkuriEntity entity) {
        return switch (entity.profile().ecology()) {
            case HOUSE -> HOUSE;
            case PREDATOR -> PREDATOR;
            case WILD, AQUATIC -> ORDINARY;
        };
    }

    BlockPos homeStand(YukkuriEntity entity) { return null; }
    boolean acceptsPredatorNest() { return false; }
    boolean canFlyTo(YukkuriEntity entity, BlockPos stand) { return false; }

    SearchResult findHome(YukkuriEntity entity) {
        return switch(entity.habitat().findHome()) {
            case FOUND -> SearchResult.FOUND;
            case NOT_FOUND -> SearchResult.NOT_FOUND;
            case DEFERRED -> SearchResult.DEFERRED;
        };
    }

    FoodPlan beforeFood(YukkuriLife life) { return FoodPlan.CONTINUE; }
    boolean ordinaryRestAllowed(YukkuriLife life) { return true; }
    boolean temporaryCoverAllowed() { return true; }
    boolean finishRestEarly(YukkuriLife life) { return false; }
}
