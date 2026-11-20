package io.github.longshou.mcyukkuri.shop;

import java.util.ArrayList;
import java.util.List;

/** Plan both sides of a bedding exchange before changing the barrel or the room. */
final class DockBedding {
    enum Kind { EMPTY, CARRIER, OTHER, CUSHION, WOVEN }
    record Slot(Kind kind, int count) {}

    // Positive deltas go into the barrel. Withdraw first so the returned bed can use the emptied slot.
    static List<Slot> exchange(List<Slot> dock, int cushions, int woven, int maxCushions, int maxWoven, int basketPlaces) {
        var slots = new ArrayList<>(dock);
        if (!withdraw(slots, Kind.CUSHION, -cushions) || !withdraw(slots, Kind.WOVEN, -woven)
                || !deposit(slots, Kind.CUSHION, cushions, maxCushions) || !deposit(slots, Kind.WOVEN, woven, maxWoven))
            return List.of();
        return slots.stream().filter(s -> s.kind == Kind.EMPTY || s.kind == Kind.CARRIER).count() >= basketPlaces ? slots : List.of();
    }

    private static boolean withdraw(List<Slot> slots, Kind kind, int need) {
        while (need > 0) {
            // Empty the smallest stacks first so a full barrel can accept the returned bed or incoming basket.
            int i = -1;
            for (int j = 0; j < slots.size(); j++)
                if (slots.get(j).kind == kind && (i < 0 || slots.get(j).count < slots.get(i).count)) i = j;
            if (i < 0) return false;
            var slot = slots.get(i);
            int take = Math.min(need, slot.count);
            slots.set(i, slot.count == take ? new Slot(Kind.EMPTY, 0) : new Slot(kind, slot.count - take));
            need -= take;
        }
        return true;
    }

    private static boolean deposit(List<Slot> slots, Kind kind, int count, int max) {
        for (var destination : List.of(kind, Kind.EMPTY)) {
            for (int i = 0; count > 0 && i < slots.size(); i++) {
                var slot = slots.get(i);
                if (slot.kind != destination) continue;
                int add = Math.min(count, max - slot.count);
                slots.set(i, new Slot(kind, slot.count + add));
                count -= add;
            }
        }
        return count <= 0;
    }
}
