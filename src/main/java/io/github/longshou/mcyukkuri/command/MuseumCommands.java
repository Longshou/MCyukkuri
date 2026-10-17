package io.github.longshou.mcyukkuri.command;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import net.minecraft.world.entity.Entity;

/**
 * Only the fixed clip actors of the playground's motion-clip corner (NoAI and tagged pm_actor, played with
 * /yukkuri action) are recognised here; every scenario individual runs ordinary AI and is never an actor.
 */
public final class MuseumCommands {
    public static boolean actor(Entity entity) {
        return entity instanceof YukkuriEntity y && y.isAlive() && y.isNoAi() && y.getTags().contains("pm_actor");
    }

    private MuseumCommands() {}
}
