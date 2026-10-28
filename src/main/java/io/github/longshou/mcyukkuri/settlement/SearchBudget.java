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
    // At most five sound() calls through claim, assignNaturalHome, independence and establishHousehold;
    // each has <= 20 beds + centre. Reserve slack for candidate/stand probes as well.
    static final Cost NAVIGATE = new Cost(126, 0, 13);
    /** One cavity cell, including its four facings and the enclosing check (4535 logical units). */
    static final Cost HOME_SEARCH_SLICE = CAN_PLACE.plus(SURVEY).times(4).plus(new Cost(3, 0, 0));
    /** Every facing can verify reachability; reserve extra probes for current target validation. */
    static final Cost RELOCATION_SEARCH_SLICE = HOME_SEARCH_SLICE.plus(new Cost(128, 0, 4));
    /** One site slot or one placement/claim is comfortably below this fixed admission. */
    static final Cost SITE_SETTLE_SLICE = new Cost(4096, 0, 0);
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
