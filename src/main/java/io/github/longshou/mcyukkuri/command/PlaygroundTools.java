package io.github.longshou.mcyukkuri.command;

import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.settlement.SettlementData;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import io.github.longshou.mcyukkuri.status.YukkuriStatus;
import net.minecraft.server.MinecraftServer;

/** Playground helpers: forced field clearing (never refuses) and the live status labels of {@code /yukkuri observe}. */
public final class PlaygroundTools {
    private record Label(Component name, boolean visible) {}
    private static final Map<Entity, Label> LABELLED = new WeakHashMap<>();
    private static boolean observing;

    /** What {@link #clear} removes: everything this mod owns (living, remains, boats, drops, humans) and every dropped item. */
    private static boolean removable(Entity entity) {
        if (entity instanceof Player) return false;
        if (entity instanceof ItemEntity || entity instanceof ExperienceOrb) return true;
        return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getNamespace().equals(MCYukkuri.MOD_ID);
    }

    /**
     * Removes every mod entity and dropped item inside the block box, settlement groups and planned sites centred in it,
     * and cancels the broods of the removed mothers so that the population ledger does not stay full. Blocks are not
     * touched: the data pack rebuilds the field's terrain. Returns the number of removed entities.
     */
    static int clear(ServerLevel level, BlockPos first, BlockPos second) {
        var box = new AABB(Math.min(first.getX(), second.getX()), Math.min(first.getY(), second.getY()), Math.min(first.getZ(), second.getZ()),
                Math.max(first.getX(), second.getX()) + 1, Math.max(first.getY(), second.getY()) + 1, Math.max(first.getZ(), second.getZ()) + 1);
        var found = new ArrayList<Entity>(level.getEntities((Entity)null, box, PlaygroundTools::removable));
        int removed = 0;
        for (var entity : found) {
            LABELLED.remove(entity);
            if (entity.isRemoved()) continue;
            entity.ejectPassengers();
            entity.discard();
            removed++;
        }
        var settlements = SettlementData.get(level);
        for (var group : settlements.groups()) if (box.contains(group.center.getX() + .5, group.center.getY() + .5, group.center.getZ() + .5)) settlements.remove(group.id);
        for (var site : settlements.sites()) if (box.contains(site.origin.getX() + .5, site.origin.getY() + .5, site.origin.getZ() + .5)) settlements.removeSite(site.id);
        return removed;
    }

    static void observe(boolean on, MinecraftServer server) {
        observing = on;
        if (!on) restore();
    }

    private static void restore() {
        for (var entry : new ArrayList<>(LABELLED.entrySet())) {
            entry.getKey().setCustomName(entry.getValue().name());
            entry.getKey().setCustomNameVisible(entry.getValue().visible());
        }
        LABELLED.clear();
    }

    public static void tick(ServerTickEvent.Post event) {
        if (!observing || event.getServer().getTickCount() % 10 != 0) return;
        for (var level : event.getServer().getAllLevels()) for (var player : level.players()) {
            for (var y : level.getEntitiesOfClass(YukkuriEntity.class, player.getBoundingBox().inflate(48), YukkuriEntity::isAlive)) {
                var saved = LABELLED.computeIfAbsent(y, e -> new Label(e.getCustomName(), e.isCustomNameVisible()));
                String base = saved.name() != null ? saved.name().getString() : y.getType().getDescription().getString();
                y.setCustomName(Component.literal(base + "  " + status(y)).withStyle(ChatFormatting.YELLOW));
                y.setCustomNameVisible(true);
            }
        }
    }

    /** One line over the head, built only from the status hub's register. */
    static String status(YukkuriEntity y) {
        return YukkuriStatus.label(y);
    }

    private PlaygroundTools() {}
}
