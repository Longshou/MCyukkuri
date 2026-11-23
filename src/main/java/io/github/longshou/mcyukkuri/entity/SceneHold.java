package io.github.longshou.mcyukkuri.entity;

import net.minecraft.world.entity.Entity;

/** The museum's start hold (`yukkuri scenario hold`): a mob frozen only while the player gets into position. One query for every
 *  system that must neither consume a one-shot request nor run its clocks against a body that cannot act; a mob made NoAI on purpose is never tagged. */
public final class SceneHold {
    public static final String TAG = "scn_hold";

    private SceneHold() {}

    public static boolean held(Entity entity) { return entity.getTags().contains(TAG); }
}
