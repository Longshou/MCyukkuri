package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Bookkeeping only, registered once; persistent membership/death cleanup remains with R1c. */
@EventBusSubscriber(modid = MCYukkuri.MOD_ID)
public final class GroupMemberEvents {
    @SubscribeEvent public static void joining(EntityJoinLevelEvent event) {
        if (!event.isCanceled() && event.getLevel() instanceof ServerLevel level && event.getEntity() instanceof YukkuriEntity entity)
            LoadedGroupMembers.joining(level, entity);
    }
    @SubscribeEvent public static void leaving(EntityLeaveLevelEvent event) {
        if (event.getLevel() instanceof ServerLevel level && event.getEntity() instanceof YukkuriEntity entity)
            LoadedGroupMembers.leaving(level, entity);
    }
    @SubscribeEvent public static void tick(ServerTickEvent.Post event) { LoadedGroupMembers.tick(event.getServer()); }
    @SubscribeEvent public static void unloaded(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) LoadedGroupMembers.clear(level);
    }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event) { LoadedGroupMembers.clear(event.getServer()); }
    private GroupMemberEvents() {}
}
