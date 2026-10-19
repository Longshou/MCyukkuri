package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.registry.ModItems;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** One real empty stem per naturally completed brood; failed item insertion remains world-owned. */
public final class ShedStemDrops extends SavedData {
    private record Drop(ResourceKey<Level> dimension, Vec3 root, boolean ready, boolean delivered) {}
    private final Map<UUID, Drop> drops = new HashMap<>();

    public static ShedStemDrops get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(
                new Factory<>(ShedStemDrops::new, ShedStemDrops::load), "mcyukkuri_shed_stems");
    }

    public boolean queue(YukkuriEntity mother, FamilyData.Brood brood) {
        if (brood.kind != FamilyData.BirthKind.STEM || brood.cancelled || brood.detachedHolder != null
                || brood.children.isEmpty() || !brood.complete() || !mother.getUUID().equals(brood.mother)
                || drops.containsKey(brood.id)) return false;
        var root = FamilyGeometry.toWorld(mother, FamilyGeometry.root(mother.getVariant()).scale(mother.growthScale()));
        drops.put(brood.id, new Drop(mother.level().dimension(), root, false, false));
        setDirty(); return true;
    }

    public boolean delivered(UUID brood) { var drop = drops.get(brood); return drop != null && drop.delivered; }
    public boolean pending(UUID brood) { var drop = drops.get(brood); return drop != null && !drop.delivered; }
    public boolean ready(UUID brood) { var drop = drops.get(brood); return drop != null && drop.ready; }
    public boolean resolve(ServerLevel level,UUID id) {
        var drop=drops.get(id);if(drop==null)return false;
        if(drop.ready)return true;
        var data=FamilyData.get(level);var brood=data.brood(id);
        if(brood==null||brood.cancelled||!data.birthsResolved(brood,level))return false;
        drops.put(id,resolvedAtMother(drop,brood,level));setDirty();return true;
    }
    private static Drop resolvedAtMother(Drop drop,FamilyData.Brood brood,ServerLevel level) {
        for(var dimension:level.getServer().getAllLevels())
            if(dimension.getEntity(brood.mother) instanceof YukkuriEntity mother&&mother.isAlive()&&mother.family().brood()==brood){
                var root=FamilyGeometry.toWorld(mother,FamilyGeometry.root(mother.getVariant()).scale(mother.growthScale()));
                return new Drop(mother.level().dimension(),root,true,drop.delivered);
            }
        return new Drop(drop.dimension,drop.root,true,drop.delivered);
    }
    public static UUID itemId(UUID brood) {
        return UUID.nameUUIDFromBytes(("mcyukkuri:shed_stem/"+brood).getBytes(StandardCharsets.UTF_8));
    }

    /** No chunk loading, teleportation or mother reference is needed to complete a pending transfer. */
    public void deliver(MinecraftServer server) {
        for (var entry : drops.entrySet()) {
            var drop = entry.getValue();
            if (drop.delivered) continue;
            var level = server.getLevel(drop.dimension);
            if (level == null) continue;
            if(!drop.ready) {
                var data=FamilyData.get(level);var brood=data.brood(entry.getKey());
                if(brood==null||brood.cancelled||!data.birthsResolved(brood,level))continue;
                drop=resolvedAtMother(drop,brood,level);entry.setValue(drop);setDirty();
                level=server.getLevel(drop.dimension);if(level==null)continue;
            }
            if (!level.hasChunkAt(BlockPos.containing(drop.root))) continue;
            UUID id = itemId(entry.getKey());
            var existing = level.getEntity(id);
            if (existing != null) {
                if (!(existing instanceof ItemEntity item) || !item.getItem().is(ModItems.YUKKURI_STEM.get())) continue;
            } else {
                var item = new ItemEntity(level, drop.root.x, drop.root.y, drop.root.z, new ItemStack(ModItems.YUKKURI_STEM.get()));
                item.setUUID(id); item.setDeltaMovement(Vec3.ZERO); item.setPickUpDelay(20);
                if (!level.addFreshEntity(item)) continue;
            }
            entry.setValue(new Drop(drop.dimension, drop.root, true, true)); setDirty();
        }
    }

    /**
     * Playground field clear only (command/PlaygroundTools): pending stems of the forgotten {@code broods} or rooted inside {@code box}
     * are dropped, so no stem item appears in the rebuilt field after the clear.
     */
    public void forget(ServerLevel level, AABB box, Set<UUID> broods) {
        var dimension = level.dimension();
        if (drops.entrySet().removeIf(entry -> broods.contains(entry.getKey())
                || entry.getValue().dimension.equals(dimension) && box.contains(entry.getValue().root))) setDirty();
    }

    public static void tick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount()%20 == 0) get(event.getServer().overworld()).deliver(event.getServer());
    }

    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        var entries = new ListTag();
        drops.forEach((id, drop) -> {
            var saved = new CompoundTag(); saved.putUUID("Brood", id); saved.putBoolean("Ready", drop.ready);saved.putBoolean("Delivered", drop.delivered);
            saved.putString("Dimension", drop.dimension.location().toString());
            saved.putDouble("X", drop.root.x); saved.putDouble("Y", drop.root.y); saved.putDouble("Z", drop.root.z);
            entries.add(saved);
        });
        tag.put("Drops", entries); return tag;
    }
    public static ShedStemDrops load(CompoundTag tag, HolderLookup.Provider registries) {
        var data = new ShedStemDrops();
        for (Tag raw : tag.getList("Drops", Tag.TAG_COMPOUND)) {
            var saved = (CompoundTag)raw;
            if (!saved.hasUUID("Brood")) continue;
            var dimension = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(saved.getString("Dimension")));
            var root = new Vec3(saved.getDouble("X"), saved.getDouble("Y"), saved.getDouble("Z"));
            // Old outbox entries were already detached; retain their transfer rather than reattach a mother.
            boolean ready=!saved.contains("Ready")||saved.getBoolean("Ready")||saved.getBoolean("Delivered");
            data.drops.put(saved.getUUID("Brood"), new Drop(dimension, root, ready, saved.getBoolean("Delivered")));
        }
        return data;
    }
}
