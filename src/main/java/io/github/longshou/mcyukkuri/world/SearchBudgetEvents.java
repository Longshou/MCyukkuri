package io.github.longshou.mcyukkuri.world;

import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Single annotation registration; R9 may move these four subscriptions into its explicit wiring. */
public final class SearchBudgetEvents {
    public static void tick(ServerTickEvent.Pre event) { SearchBudget.beginTick(event.getServer()); }
    public static void leaving(EntityLeaveLevelEvent event) {
        if (event.getLevel() instanceof ServerLevel level) SearchBudget.leaving(level, event.getEntity());
    }
    public static void unloaded(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) SearchBudget.clear(level);
    }
    public static void stopped(ServerStoppedEvent event) { SearchBudget.clear(event.getServer()); }
    private SearchBudgetEvents() {}
}
