package io.github.longshou.mcyukkuri.motor.action;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.motor.HeldMove;
import io.github.longshou.mcyukkuri.motor.Outcome;
import io.github.longshou.mcyukkuri.motor.Progress;

/** The {@link Stow} beat an action runs before (or after) its own work: started when there is one, ticked to its end, then the action goes on. */
final class StowStep {
    private Stow stow;

    /** Starts {@code next} when there is one that can run now; false otherwise. */
    boolean start(YukkuriEntity body, Stow next) {
        if (next == null || !next.check(body).ok()) return false;
        stow = next;
        next.start(body);
        return true;
    }

    boolean running() { return stow != null; }

    /** One tick of the running beat: its progress, or its outcome on the tick it ends; null when none runs. */
    Progress tick(YukkuriEntity body) {
        if (stow == null) return null;
        var progress = stow.tick(body);
        if (progress instanceof Outcome) stow = null;
        return progress;
    }

    HeldMove move() { return stow; }
}
