package io.github.longshou.mcyukkuri.human;

import com.mojang.datafixers.util.Pair;
import com.mojang.logging.LogUtils;
import io.github.longshou.mcyukkuri.human.mixin.SinglePoolElementAccessor;
import io.github.longshou.mcyukkuri.human.mixin.TemplatePoolAccessor;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import org.slf4j.Logger;

public final class TownPools {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final List<String> STYLES = List.of("plains", "desert", "savanna", "snowy", "taiga");

    public static void append(ServerAboutToStartEvent event) {
        var pools = event.getServer().registryAccess().registryOrThrow(Registries.TEMPLATE_POOL);
        for (String style : STYLES) {
            String village = "village/" + style + "/";
            var housesId = ResourceLocation.withDefaultNamespace(village + "houses");
            var lotsId = ResourceLocation.fromNamespaceAndPath("mcyukkuri", village + "town_lots");
            int added = pools.getOptional(lotsId).map(lots -> append(
                    pools.getOrThrow(ResourceKey.create(Registries.TEMPLATE_POOL, housesId)), lots)).orElse(0);
            LOGGER.info("[mcyukkuri] town lots appended to {}: {} elements", housesId, added);
        }
    }

    private static int append(StructureTemplatePool houses, StructureTemplatePool lots) {
        var target = (TemplatePoolAccessor) houses;
        var entries = target.mcyukkuri$rawTemplates();
        var additions = ((TemplatePoolAccessor) lots).mcyukkuri$rawTemplates();
        Set<StructurePoolElement> seen = new HashSet<>();
        Set<ResourceLocation> locations = new HashSet<>();
        for (var entry : entries) remember(entry.getFirst(), seen, locations);

        List<Pair<StructurePoolElement, Integer>> merged = new ArrayList<>(entries);
        for (var entry : additions) {
            StructurePoolElement element = entry.getFirst();
            if (!remember(element, seen, locations)) continue;
            merged.add(entry);
            for (int i = 0; i < entry.getSecond(); i++) target.mcyukkuri$templates().add(element);
        }
        int added = merged.size() - entries.size();
        if (added > 0) {
            target.mcyukkuri$setRawTemplates(merged);
            target.mcyukkuri$setMaxSize(Integer.MIN_VALUE);
        }
        return added;
    }

    private static boolean remember(StructurePoolElement element, Set<StructurePoolElement> seen, Set<ResourceLocation> locations) {
        return seen.add(element) && (!(element instanceof SinglePoolElement single)
                || ((SinglePoolElementAccessor) single).mcyukkuri$template().left().map(locations::add).orElse(true));
    }

    private TownPools() {}
}
