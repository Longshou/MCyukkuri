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
import io.github.longshou.mcyukkuri.pet.SceneReset;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.human.CustodyData;
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
     * With a registered field {@code tag}, its historical UUID roster also finds entities that left or entered custody.
     * Unloaded copies are invalidated by the field epoch; fresh summons may reuse the UUID. World ledgers forget
     * what the field left behind ({@link #forgetLedgers}), so a scene started after the clear begins from the same records
     * every time. Without a tag only entities and settlements are removed, as an ordinary non-death removal (the
     * playground's REMOVED control). Blocks are not touched: the data pack rebuilds the field's terrain.
     * Returns the number of removed entities.
     */
    static int clear(ServerLevel level, BlockPos first, BlockPos second, String tag) {
        var box = new AABB(Math.min(first.getX(), second.getX()), Math.min(first.getY(), second.getY()), Math.min(first.getZ(), second.getZ()),
                Math.max(first.getX(), second.getX()) + 1, Math.max(first.getY(), second.getY()) + 1, Math.max(first.getZ(), second.getZ()) + 1);
        var ids = new HashSet<UUID>();
        if (tag != null) {
            ids.addAll(PlaygroundFields.get(level).clear(level, tag, box));
            // The following terrain function must be able to write every block, including chunks outside the player's view.
            for (int x = Math.floorDiv((int)box.minX, 16); x <= Math.floorDiv((int)box.maxX - 1, 16); x++)
                for (int z = Math.floorDiv((int)box.minZ, 16); z <= Math.floorDiv((int)box.maxZ - 1, 16); z++) level.getChunk(x, z);
        }
        var found = new LinkedHashSet<Entity>(level.getEntities((Entity)null, box,
                entity -> tag != null ? !(entity instanceof Player) : removable(entity)));
        if (tag != null) for (var dimension : level.getServer().getAllLevels())
            for (var entity : dimension.getAllEntities()) if (ids.contains(entity.getUUID())) found.add(entity);
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
        int groups = 0, sites = 0;
        for (var dimension : level.getServer().getAllLevels()) {
            if (tag == null && dimension != level) continue;
            var settlements = SettlementData.get(dimension);
            int groupsBefore = settlements.groups().size(), sitesBefore = settlements.sites().size();
            for (var group : settlements.groups()) if (dimension == level && box.contains(group.center.getX() + .5, group.center.getY() + .5, group.center.getZ() + .5)
                    || tag != null && ids.stream().anyMatch(id -> settlements.existingMember(group, id) != null)) {
                if (tag != null && group.siteId != null) settlements.removeSite(group.siteId);
                settlements.remove(group.id);
            }
            if (dimension == level) for (var site : settlements.sites())
                if (box.contains(site.origin.getX() + .5, site.origin.getY() + .5, site.origin.getZ() + .5)) settlements.removeSite(site.id);
            groups += groupsBefore - settlements.groups().size(); sites += sitesBefore - settlements.sites().size();
        }
        if (tag != null) forgetLedgers(level, box, ids, removed, groups, sites);
        return removed;
    }

    /**
     * Field clear only. Each ledger owner drops its own records: family records of the removed individuals and of everybody last
     * registered in the box (fixed scenario UUIDs, deaths of the previous run) with their broods; their object relationships,
     * acquaintances, wild events and home claims; regrowth, shed stems, household activity and spray anchors inside the box;
     * shops and factories that stood there. Tokens and NPC death records are invalidated too; player-wide records stay.
     */
    private static void forgetLedgers(ServerLevel level, AABB box, Set<UUID> removed, int entities, int groups, int sites) {
        var custody = YukkuriWorldData.get(level);
        var forgotten = FamilyData.get(level).forgetPlayground(level, box, removed);
        var individuals = new HashSet<UUID>(removed);
        individuals.addAll(forgotten.people());
        HomeClaim.forget(level, box, individuals);
        WildEvents.forget(level, box, individuals);
        var records = custody.forgetIndividuals(individuals);
        CustodyData.get(level).forget(individuals);
        SettlementData.get(level).forgetDiscovery(box);
        ShedStemDrops.get(level).forget(level, box, forgotten.broods());
        Grazing.forget(level, box);
        HouseholdEcologyData.get(level).forget(box);
        HouseholdProtection.get(level).removeWithin(box);
        ShopRegistry.get(level).forget(level, box, removed);
        for (var token : ShopDeliveries.get(level).forget(level, box, removed)) custody.forgetCaptive(token);
        SceneReset.field(level, box, individuals, records.captiveTokens());
        GameplayLog.world(level, "scene", "clear ids={} entities={} people={} broods={} captives={} freezer_leases={} relations={} groups={} sites={} box={}",
                individuals.size(), entities, forgotten.people().size(), forgotten.broods().size(), records.captiveTokens().size(), records.freezerLeases(),
                records.relations(), groups, sites, box);
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
