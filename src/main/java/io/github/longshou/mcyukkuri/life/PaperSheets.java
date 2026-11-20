package io.github.longshou.mcyukkuri.life;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/** The portions of the real stacks used for a cover, and its release order. */
public final class PaperSheets {
    public record Portion<T>(T source, int count) {}

    public static <T> List<Portion<T>> select(List<T> sources, int needed, ToIntFunction<T> count) {
        var portions = new ArrayList<Portion<T>>();
        for (T source : sources) {
            int take = Math.min(needed, count.applyAsInt(source));
            portions.add(new Portion<>(source, take));
            if ((needed -= take) == 0) break;
        }
        return portions;
    }

    /** The first refused release leaves that stack and the subsequent ones in the cover. */
    static <T> void release(List<T> cover, Predicate<T> drop) {
        while (!cover.isEmpty() && drop.test(cover.getFirst())) cover.removeFirst();
    }

    private PaperSheets() {}
}
