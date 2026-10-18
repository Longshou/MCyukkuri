package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.world.SearchBudget.Cost;
import io.github.longshou.mcyukkuri.world.SearchBudget.Lease;
import io.github.longshou.mcyukkuri.world.SearchBudget.Purpose;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

/** Compatibility door and conservative bounds for the settlement call graph; the only ledger lives in world. */
public final class SearchBudget {
    // enclosed includes one stand entry (2 probes); survey <= 3 initial enclosed + 2 stands + 121 enclosed.
    static final Cost SURVEY = new Cost(250, 1, 0);
    static final Cost CAN_PLACE = SURVEY.times(2).plus(new Cost(20, 0, 0));
    static final Cost PLACE = SURVEY.times(3).plus(new Cost(40, 0, 0));
    // At most five sound() calls through claim, assignNaturalHome, independence and establishHousehold;
    // each has <= 20 beds + centre. Reserve slack for candidate/stand probes as well.
    static final Cost CHOOSE = new Cost(512, 0, 2);
    static final Cost NAVIGATE = new Cost(126, 0, 13);
    static final Cost PICK_SITE = new Cost(24L * 23 * (1 + 1445L * 2) + 3L * 3087 + 24, 0, 0);
    static Cost settle(long slots) {
        return CHOOSE.plus(new Cost(2, 0, 0)).times(slots).plus(PLACE.plus(CHOOSE).times(3));
    }
    static Cost findHome(long cores, long slots) {
        return settle(slots).plus(CHOOSE.plus(new Cost(1, 0, 0)).times(cores))
                .plus(new Cost(1445L * 3, 0, 0))
                .plus(CAN_PLACE.plus(SURVEY).times(1445L * 4))
                .plus(PLACE.plus(CHOOSE).plus(new Cost(0, 0, 1)).times(4));
    }
    static Cost relocate(long cores, long movers) {
        return new Cost(43, 0, 0).times(cores)
                .plus(new Cost(18605L * 3, 0, 0))
                .plus(CAN_PLACE.plus(SURVEY).plus(new Cost(0, 0, 1)).times(18605L * 4))
                .plus(PLACE).plus(CHOOSE.times(movers));
    }
    public static boolean take(ServerLevel level) { return io.github.longshou.mcyukkuri.world.SearchBudget.take(level); }
    static boolean take(ServerLevel level, Entity owner, Purpose purpose) { return io.github.longshou.mcyukkuri.world.SearchBudget.take(level, owner, purpose); }
    static Lease request(ServerLevel level, Entity owner, Purpose purpose, long generation, Cost upper) {
        return io.github.longshou.mcyukkuri.world.SearchBudget.request(level, owner, purpose, generation, upper);
    }
    static void cancel(ServerLevel level, Entity owner, Purpose purpose, long generation) {
        io.github.longshou.mcyukkuri.world.SearchBudget.cancel(level, owner, purpose, generation);
    }
    static void probe(Level level) { io.github.longshou.mcyukkuri.world.SearchBudget.probe(level); }
    static void survey(Level level) { io.github.longshou.mcyukkuri.world.SearchBudget.survey(level); }
    static void path(Entity owner) { io.github.longshou.mcyukkuri.world.SearchBudget.path(owner); }
    private SearchBudget() {}
}
