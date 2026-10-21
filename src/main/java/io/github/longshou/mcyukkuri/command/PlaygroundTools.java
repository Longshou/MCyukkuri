package io.github.longshou.mcyukkuri.command;

import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.entity.RemainsEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.event.HomeClaim;
import io.github.longshou.mcyukkuri.event.WildEvents;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.family.ShedStemDrops;
import io.github.longshou.mcyukkuri.house.HouseholdEcologyData;
import io.github.longshou.mcyukkuri.house.HouseholdProtection;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.settlement.Grazing;
import io.github.longshou.mcyukkuri.settlement.SettlementData;
import io.github.longshou.mcyukkuri.shop.ShopDeliveries;
import io.github.longshou.mcyukkuri.shop.ShopRegistry;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
     * Removes every mod entity and dropped item inside the block box and the settlement groups and sites centred in it.
     * Discarding is not death: the ordinary removal interrupts release activities and cancel the unborn broods.
     * With a field {@code tag} (the mark the data pack gives everything that stood in the field, see util/tag_fields) this is
     * "clear this field": the loaded entities carrying the mark go too, wherever they wandered, and the world ledgers forget
     * what the field left behind ({@link #forgetLedgers}), so a scene started after the clear begins from the same records
     * every time. Without a tag only entities and settlements are removed, as an ordinary non-death removal (the
     * playground's REMOVED control). Blocks are not touched: the data pack rebuilds the field's terrain.
     * Returns the number of removed entities.
     */
    static int clear(ServerLevel level, BlockPos first, BlockPos second, String tag) {
        var box = new AABB(Math.min(first.getX(), second.getX()), Math.min(first.getY(), second.getY()), Math.min(first.getZ(), second.getZ()),
                Math.max(first.getX(), second.getX()) + 1, Math.max(first.getY(), second.getY()) + 1, Math.max(first.getZ(), second.getZ()) + 1);
        var found = new LinkedHashSet<Entity>(level.getEntities((Entity)null, box, PlaygroundTools::removable));
        if (tag != null) for (var entity : level.getAllEntities()) if (entity != null && entity.getTags().contains(tag) && removable(entity)) found.add(entity);
        var ids = new HashSet<UUID>();
        int removed = 0;
        for (var entity : found) {
            LABELLED.remove(entity);
            if (entity.isRemoved()) continue;
            ids.add(entity.getUUID());
            if (entity instanceof RemainsEntity remains && remains.data().hasUUID("Identity")) ids.add(remains.data().getUUID("Identity"));
            entity.ejectPassengers();
            entity.discard();
            removed++;
        }
        var settlements = SettlementData.get(level);
        for (var group : settlements.groups()) if (box.contains(group.center.getX() + .5, group.center.getY() + .5, group.center.getZ() + .5)) {
            // Unlike an ordinary terrain block, this pile is the group's persisted public-food address.  Leave neither
            // its food nor the address behind when a repeatable playground field is cleared.
            if (group.waterCommons != null && box.contains(group.waterCommons.getX() + .5, group.waterCommons.getY() + .5, group.waterCommons.getZ() + .5))
                level.removeBlock(group.waterCommons, false);
            settlements.remove(group.id);
        }
        for (var site : settlements.sites()) if (box.contains(site.origin.getX() + .5, site.origin.getY() + .5, site.origin.getZ() + .5)) settlements.removeSite(site.id);
        if (tag != null) forgetLedgers(level, box, ids);
        return removed;
    }

    /**
     * Field clear only. Each ledger owner drops its own records: family records of the removed individuals and of everybody last
     * registered in the box (fixed scenario UUIDs, deaths of the previous run) with their broods; their object relationships,
     * acquaintances, wild events and home claims; regrowth, shed stems, household activity and spray anchors inside the box;
     * shops and factories that stood there. Kept on purpose: custody records (a carrier or a freezer may still hold the token),
     * confirmed NPC deaths ({@code CustodyData}) and player-wide records.
     */
    private static void forgetLedgers(ServerLevel level, AABB box, Set<UUID> removed) {
        var custody = YukkuriWorldData.get(level);
        var forgotten = FamilyData.get(level).forgetPlayground(level, box, removed, custody::isCaptured);
        var individuals = new HashSet<UUID>(removed);
        individuals.addAll(forgotten.people());
        HomeClaim.forget(level, box, individuals);
        WildEvents.forget(level, box, individuals);
        custody.forgetIndividuals(individuals);
        ShedStemDrops.get(level).forget(level, box, forgotten.broods());
        Grazing.forget(level, box);
        HouseholdEcologyData.get(level).forget(box);
        HouseholdProtection.get(level).removeWithin(box);
        ShopRegistry.get(level).forget(level, box, removed);
        for (var token : ShopDeliveries.get(level).forget(level, box, removed)) custody.forgetCaptive(token);
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
