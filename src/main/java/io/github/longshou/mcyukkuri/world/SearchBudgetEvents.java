package io.github.longshou.mcyukkuri.world;

import io.github.longshou.mcyukkuri.MCYukkuri;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Single annotation registration; R9 may move these four subscriptions into its explicit wiring. */
@EventBusSubscriber(modid = MCYukkuri.MOD_ID)
public final class SearchBudgetEvents {
    @SubscribeEvent public static void tick(ServerTickEvent.Pre event) { SearchBudget.beginTick(event.getServer()); }
    @SubscribeEvent public static void leaving(EntityLeaveLevelEvent event) {
        if (event.getLevel() instanceof ServerLevel level) SearchBudget.leaving(level, event.getEntity());
    }
    @SubscribeEvent public static void unloaded(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) SearchBudget.clear(level);
    }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event) { SearchBudget.clear(event.getServer()); }
    private SearchBudgetEvents() {}
}
