package io.github.longshou.mcyukkuri.command;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/** Museum-only history, created by a tagged clear. Epochs reject old unloaded entities without rejecting fresh fixed UUIDs. */
public final class PlaygroundFields extends SavedData {
    private static final String NAME = "mcyukkuri_playground_fields";
    private static final Factory<PlaygroundFields> FACTORY = new Factory<>(PlaygroundFields::new, PlaygroundFields::load);
    private static final String MARK = "MuseumFields";
    private static final class Field {
        final AABB box;
        final String dimension;
        long epoch;
        final Set<UUID> ids = new HashSet<>();
        Field(AABB box, String dimension) { this.box = box; this.dimension = dimension; }
    }
    private final Map<String, Field> fields = new HashMap<>();

    static PlaygroundFields get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    static int register(ServerLevel level, String name, AABB box) {
        var data = get(level);
        if (data.fields.putIfAbsent(name, new Field(box, level.dimension().location().toString())) == null) data.setDirty();
        for (Entity entity : level.getAllEntities()) data.remember(entity);
        return 1;
    }

    Set<UUID> clear(ServerLevel level, String name, AABB box) {
        Field field = fields.computeIfAbsent(name, ignored -> new Field(box, level.dimension().location().toString()));
        for (Entity entity : level.getAllEntities()) remember(entity);
        var ids = Set.copyOf(field.ids);
        field.ids.clear();
        field.epoch++;
        setDirty();
        return ids;
    }

    private void remember(Entity entity) {
        if (entity instanceof Player) return;
        for (var entry : fields.entrySet()) {
            Field field = entry.getValue();
            if (!field.dimension.equals(entity.level().dimension().location().toString()) || !field.box.intersects(entity.getBoundingBox())) continue;
            CompoundTag data = entity.getPersistentData();
            CompoundTag marks = data.getCompound(MARK);
            marks.putLong(entry.getKey(), field.epoch);
            data.put(MARK, marks);
            if (field.ids.add(entity.getUUID())) setDirty();
        }
    }

    public static void joining(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        PlaygroundFields data = level.getServer().overworld().getDataStorage().get(FACTORY, NAME);
        if (data == null) return; // Ordinary worlds have no museum registry.
        Entity entity = event.getEntity();
        if (event.loadedFromDisk()) {
            CompoundTag marks = entity.getPersistentData().getCompound(MARK);
            for (String name : marks.getAllKeys()) {
                Field field = data.fields.get(name);
                if (field != null && marks.getLong(name) != field.epoch) {
                    event.setCanceled(true);
                    return;
                }
            }
        }
        data.remember(entity);
    }

    public static void tick(EntityTickEvent.Post event) {
        if (!(event.getEntity().level() instanceof ServerLevel level)) return;
        PlaygroundFields data = level.getServer().overworld().getDataStorage().get(FACTORY, NAME);
        if (data != null) data.remember(event.getEntity());
    }

    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        var list = new ListTag();
        fields.forEach((name, field) -> {
            var row = new CompoundTag();
            row.putString("Name", name); row.putLong("Epoch", field.epoch);
            row.putString("Dimension", field.dimension);
            row.putDouble("X1", field.box.minX); row.putDouble("Y1", field.box.minY); row.putDouble("Z1", field.box.minZ);
            row.putDouble("X2", field.box.maxX); row.putDouble("Y2", field.box.maxY); row.putDouble("Z2", field.box.maxZ);
            var ids = new ListTag();
            for (UUID id : field.ids) { var value = new CompoundTag(); value.putUUID("Id", id); ids.add(value); }
            row.put("Ids", ids); list.add(row);
        });
        tag.put("Fields", list); return tag;
    }

    private static PlaygroundFields load(CompoundTag tag, HolderLookup.Provider registries) {
        var data = new PlaygroundFields();
        for (var value : tag.getList("Fields", 10)) {
            var row = (CompoundTag) value;
            var field = new Field(new AABB(row.getDouble("X1"), row.getDouble("Y1"), row.getDouble("Z1"),
                    row.getDouble("X2"), row.getDouble("Y2"), row.getDouble("Z2")), row.getString("Dimension"));
            field.epoch = row.getLong("Epoch");
            for (var id : row.getList("Ids", 10)) field.ids.add(((CompoundTag) id).getUUID("Id"));
            data.fields.put(row.getString("Name"), field);
        }
        return data;
    }
}
