package io.github.longshou.mcyukkuri.entity;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;

/**
 * The museum's start hold (`yukkuri scenario hold`): a scene's mobs wait while the player gets into position. For the mod's own bodies
 * (yukkuri, human NPCs) the hold is this tag: {@link Mob#isImmobile()} skips the AI step (no goal, navigation or control starts) while
 * travel still runs, so the body falls, lands, floats and is pushed like a free one; {@link #inert} is the one query every life system
 * uses to decide nothing and start nothing (it also covers deliberate NoAI, which keeps its vanilla behaviour). Vanilla mobs cannot take
 * this hold; the command makes them NoAI only once they rest on the ground or in water.
 */
public final class SceneHold {
    public static final String TAG = "scn_hold";

    private SceneHold() {}

    public static boolean held(Entity entity) { return entity.getTags().contains(TAG); }

    public static boolean inert(Mob mob) { return mob.isNoAi() || held(mob); }
}
