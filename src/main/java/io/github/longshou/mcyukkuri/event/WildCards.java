package io.github.longshou.mcyukkuri.event;

import io.github.longshou.mcyukkuri.event.WildEventData.Kind;
import io.github.longshou.mcyukkuri.event.WildEventData.Phase;
import io.github.longshou.mcyukkuri.event.WildEventData.Scene;
import java.util.Set;

/** Small conditional scenes around existing life, relationships, food and weather. */
public final class WildCards {
    /** No surviving card is an optional play or social scene; care, conflict, food and weather keep their own priority. */
    private static final Set<String> LEISURE=Set.of();
    static boolean leisure(String card) { return LEISURE.contains(card); }
    static boolean ambient(Scene scene) { return scene.kind == Kind.CARD && leisure(scene.card); }
    static boolean canYield(Scene scene) {
        return scene.active() && ambient(scene) && scene.phase == Phase.APPROACH && scene.responses == 0;
    }
    private WildCards() {}
}
