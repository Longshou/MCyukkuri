package io.github.longshou.mcyukkuri.command;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.function.Consumer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;

/** Read-only generated-world geometry for the museum layout runner. */
final class SceneGeometry {
    static int read(CommandSourceStack source) {
        return read(source, message -> source.sendSuccess(() -> Component.literal(message), false));
    }

    static int record(CommandSourceStack source) {
        return read(source, message -> GameplayLog.world(source.getLevel(), "layout", "{}", message));
    }

    private static int read(CommandSourceStack source, Consumer<String> output) {
        int count = 0;
        for (var entity : source.getLevel().getAllEntities()) {
            boolean player = entity.getTags().contains("mcyv");
            // Block-breaking loot is a falling effect of terrain construction, not a scenario prop placement.
            if (entity instanceof ItemEntity && !entity.getTags().contains("scn")) continue;
            if (!player && !(entity instanceof ItemEntity) && !(entity instanceof LivingEntity)
                    && !entity.getTags().contains("scn") && !entity.getTags().contains("scn_prop")
                    && !BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getNamespace().equals("mcyukkuri")) continue;
            var box = player ? new AABB(entity.getX() - .3, entity.getY(), entity.getZ() - .3,
                    entity.getX() + .3, entity.getY() + 1.8, entity.getZ() + .3) : entity.getBoundingBox();
            var row = new JsonObject();
            row.addProperty("uuid", entity.getUUID().toString());
            row.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
            row.addProperty("player", player);
            var tags = new JsonArray();
            entity.getTags().forEach(tags::add);
            row.add("tags", tags);
            var bounds = new JsonArray();
            for (double v : new double[]{box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ}) bounds.add(v);
            row.add("box", bounds);
            row.addProperty("clear", !source.getLevel().getBlockCollisions(entity, box.deflate(1e-5)).iterator().hasNext());
            row.addProperty("supported", supported(entity, box));
            row.addProperty("vehicle", entity.isPassenger() ? entity.getRootVehicle().getUUID().toString() : "");
            output.accept("MCYGEOMETRY " + row);
            count++;
        }
        output.accept("MCYGEOMETRY_END");
        return count;
    }

    private static boolean supported(Entity entity, AABB box) {
        var level = entity.level();
        // A short downward sweep uses the same block collision shapes as movement; fluids support water starts.
        var foot = new AABB(box.minX + 1e-5, box.minY - .06251, box.minZ + 1e-5,
                box.maxX - 1e-5, box.minY + 1e-5, box.maxZ - 1e-5);
        if (level.getBlockCollisions(entity, foot).iterator().hasNext() || entity.isPassenger()) return true;
        // BlockPos's stream reuses a mutable cursor; consume each cell before advancing it.
        for (var cells = BlockPos.betweenClosedStream(box.expandTowards(0, -.0625, 0)).iterator(); cells.hasNext();) {
            var pos = cells.next();
            var fluid = level.getFluidState(pos);
            if (fluid.is(FluidTags.WATER) && pos.getY() + fluid.getHeight(level, pos) >= box.minY - .0625) return true;
        }
        return false;
    }

    private SceneGeometry() {}
}
