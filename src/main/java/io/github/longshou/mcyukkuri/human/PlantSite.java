package io.github.longshou.mcyukkuri.human;

import io.github.longshou.mcyukkuri.human.mixin.StructureTemplateAccessor;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/** The persistent site marker owns the boundary and its UUID. Staff save that UUID with their posts. */
final class PlantSite {
    static final ResourceLocation TEMPLATE = HumanScenes.Kind.PLANT.template;
    static final BlockPos ENTRANCE = new BlockPos(HumanScenes.Kind.PLANT.entranceX, 0, 0);
    private static final String SITE = "mcy_plant_site";

    private PlantSite() {}

    static UUID id(ServerLevelAccessor level, BlockPos origin, Rotation rotation) {
        String key = TEMPLATE + "/" + level.getLevel().dimension().location() + "/" + origin.asLong() + "/" + rotation;
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }

    static void place(ServerLevelAccessor level, StructureTemplate template, BlockPos origin, Rotation rotation, BoundingBox clip) {
        var settings = new StructurePlaceSettings().setRotation(rotation);
        var id = id(level, origin, rotation);
        var corners = new ArrayList<BlockPos>(2);
        BlockPos counter = null;
        for (var marker : ((StructureTemplateAccessor) template).mcyukkuri$entities()) {
            if (!marker.nbt.getString("id").equals("minecraft:marker")) continue;
            String tag = marker.nbt.getList("Tags", Tag.TAG_STRING).getString(0);
            BlockPos at = origin.offset(StructureTemplate.calculateRelativePosition(settings, marker.blockPos));
            var data = marker.nbt.getCompound("data");
            switch (tag) {
                case "mcy_plant_corner" -> corners.add(at);
                case "mcy_plant_counter" -> {
                    counter = at;
                    staff(level, clip, rotation, id, "plant_counter", at);
                }
                case "mcy_plant_receive" -> staff(level, clip, rotation, id, "plant_receive", at.offset(offset(data, "Counter", rotation)));
                case "mcy_plant_box" -> {
                    if (data.getString("Box").equals("A"))
                        staff(level, clip, rotation, id, "plant_sweetening", at.offset(offset(data, "Door", rotation)));
                }
                case "mcy_plant_zone" -> staff(level, clip, rotation, id, "plant_" + data.getString("Kind"), at);
                default -> {} // Other markers describe equipment and pens.
            }
        }
        if (!clip.isInside(counter)) return;
        var boundary = BoundingBox.fromCorners(corners.get(0), corners.get(1));
        var data = new CompoundTag();
        data.putLong("Min", new BlockPos(boundary.minX(), boundary.minY(), boundary.minZ()).asLong());
        data.putLong("Max", new BlockPos(boundary.maxX(), boundary.maxY(), boundary.maxZ()).asLong());
        var nbt = at(counter, "minecraft:marker", rotation);
        nbt.putUUID("UUID", id);
        nbt.put("data", data);
        var site = EntityType.create(nbt, level.getLevel()).orElseThrow();
        site.addTag(SITE);
        if (level.addFreshEntity(site))
            GameplayLog.event(site, "factory", "action=register_site min={} max={}",
                    BlockPos.of(data.getLong("Min")).toShortString(), BlockPos.of(data.getLong("Max")).toShortString());
    }

    private static BlockPos offset(CompoundTag data, String key, Rotation rotation) {
        var xyz = data.getList(key, Tag.TAG_INT);
        return new BlockPos(xyz.getInt(0), xyz.getInt(1), xyz.getInt(2)).rotate(rotation);
    }

    private static void staff(ServerLevelAccessor level, BoundingBox clip, Rotation rotation, UUID site, String role, BlockPos post) {
        if (!clip.isInside(post)) return;
        var nbt = at(post, "mcyukkuri:caretaker_brother", rotation);
        var npc = (HumanNpc) EntityType.create(nbt, level.getLevel()).orElseThrow();
        npc.postAtPlant(site, role, post);
        if (level.addFreshEntity(npc))
            GameplayLog.event(npc, "factory", "action=post_staff role={} site={} stand={}", role, site, post.toShortString());
    }

    private static CompoundTag at(BlockPos pos, String entityType, Rotation rotation) {
        var tag = new CompoundTag();
        tag.putString("id", entityType);
        var location = new ListTag();
        location.add(DoubleTag.valueOf(pos.getX() + .5));
        location.add(DoubleTag.valueOf(pos.getY()));
        location.add(DoubleTag.valueOf(pos.getZ() + .5));
        tag.put("Pos", location);
        var angles = new ListTag();
        angles.add(FloatTag.valueOf(rotation.rotate(Direction.NORTH).toYRot()));
        angles.add(FloatTag.valueOf(0));
        tag.put("Rotation", angles);
        return tag;
    }
}
