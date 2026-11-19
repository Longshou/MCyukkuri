package io.github.longshou.mcyukkuri.pet.rules;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** One loaded parent. Entity IDs distinguish two separate loads of the same witness or child. */
final class Observation {
    record Sign(UUID brood, int judge, int subject, boolean inspection) {}
    private final Map<Sign, ArrayDeque<Long>> ticks = new HashMap<>();

    boolean seen(Sign sign, long now) {
        var history = ticks.computeIfAbsent(sign, key -> new ArrayDeque<>());
        while (!history.isEmpty() && now - history.getFirst() >= 200) history.removeFirst();
        if (history.isEmpty() || history.getLast() != now) history.addLast(now);
        return history.size() >= 40;
    }
    void expire(long now) { ticks.values().removeIf(history -> now - history.getLast() >= 200); }
    static boolean inView(boolean awake, double distanceSquared, double facingDot) {
        return awake && distanceSquared <= 256 && facingDot > .5;
    }
    static boolean pregnancy(UUID brood, UUID attached, boolean ongoing, boolean showing) {
        return brood.equals(attached) && ongoing && showing;
    }
    static boolean young(UUID brood, UUID parent, UUID birth, UUID mother, UUID father, boolean adult, boolean seenTogether) {
        return brood.equals(birth) && (parent.equals(mother) || parent.equals(father)) && !adult && seenTogether;
    }

    static boolean identifyingCall(UUID brood, UUID denyingParent, UUID birth, UUID mother, boolean dependent, boolean spoken) {
        return spoken && dependent && brood.equals(birth) && denyingParent.equals(mother);
    }

    static String words(boolean witness, boolean delivered, boolean confession, boolean sawCall) {
        if (!witness || !delivered) return null;
        if (confession) return "confession";
        return sawCall ? "lie_exposed" : null;
    }
}
