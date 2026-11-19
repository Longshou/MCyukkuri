package io.github.longshou.mcyukkuri.family;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/** A contact or departure leg can fail at three distinct stands before giving way. */
final class FarewellStands<T> {
    private static final int MAX_REFUSALS = 3;
    private final List<T> refused = new ArrayList<>(MAX_REFUSALS);
    private final BiPredicate<T, T> sameStand;

    FarewellStands(BiPredicate<T, T> sameStand) { this.sameStand = sameStand; }

    T next(List<T> candidates, Predicate<T> reachable) {
        if (refused.size() == MAX_REFUSALS) return null;
        for (T candidate : candidates)
            if (refused.stream().noneMatch(at -> sameStand.test(at, candidate)) && reachable.test(candidate)) return candidate;
        return null;
    }

    void refuse(T stand) { refused.add(stand); }
    void clear() { refused.clear(); }

    enum Exchange { CLOSE, WALK, CALL, UNREACHABLE }

    static Exchange exchange(boolean close, boolean canWalk, boolean visible) {
        if (close) return Exchange.CLOSE;
        if (canWalk) return Exchange.WALK;
        return visible ? Exchange.CALL : Exchange.UNREACHABLE;
    }
}
