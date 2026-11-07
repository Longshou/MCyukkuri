package io.github.longshou.mcyukkuri.command;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.status.TraceRecorder;
import java.util.function.Consumer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Read-only generated-world geometry for the museum layout runner. */
final class SceneGeometry {
    static int read(CommandSourceStack source) {
        return read(source, message -> source.sendSuccess(() -> Component.literal(message), false));
    }

    static int record(CommandSourceStack source) {
        return read(source, message -> {
            GameplayLog.world(source.getLevel(), "layout", "{}", message);
            if (message.startsWith("MCYGEOMETRY "))
                TraceRecorder.geometry(source.getLevel(), JsonParser.parseString(message.substring("MCYGEOMETRY ".length())).getAsJsonObject());
        });
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
            row.addProperty("drop", drop(entity, box));
            if (player) vantage(source, entity, row);
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

    /** Downward distance to the first real block/fluid support; 16 means no support within the scan. */
    private static double drop(Entity entity, AABB box) {
        if (entity.isPassenger()) return 0;
        var level = entity.level();
        var foot = new AABB(box.minX + 1e-5, box.minY - 1e-5, box.minZ + 1e-5,
                box.maxX - 1e-5, box.minY + 1e-5, box.maxZ - 1e-5);
        double previous = 0;
        for (int step = 0; step <= 256; step++) {
            double distance = step / 16.0;
            if (level.getBlockCollisions(entity, foot.move(0, -distance, 0)).iterator().hasNext()) {
                if (step == 0) return 0;
                double low = previous, high = distance;
                for (int i = 0; i < 10; i++) {
                    double middle = (low + high) * .5;
                    if (level.getBlockCollisions(entity, foot.move(0, -middle, 0)).iterator().hasNext()) high = middle;
                    else low = middle;
                }
                return high;
            }
            previous = distance;
        }
        for (var cells = BlockPos.betweenClosedStream(box.expandTowards(0, -.0625, 0)).iterator(); cells.hasNext();) {
            var pos = cells.next();
            var fluid = level.getFluidState(pos);
            if (fluid.is(FluidTags.WATER)) {
                double surface = pos.getY() + fluid.getHeight(level, pos);
                if (surface >= box.minY - .0625 && surface <= box.minY + .0625) return Math.max(0, box.minY - surface);
            }
        }
        return 16;
    }

    /** Nine collider rays and an approximate vertical 70-degree, 16:9 screen share for the nearest scene actor. */
    private static void vantage(CommandSourceStack source, Entity viewer, JsonObject row) {
        LivingEntity target = null;
        double nearest = Double.MAX_VALUE;
        for (var entity : source.getLevel().getAllEntities()) {
            if (!(entity instanceof LivingEntity living) || entity == viewer) continue;
            boolean scene = entity.getTags().contains("scn") || entity.getTags().stream().anyMatch(tag -> tag.startsWith("scn_"));
            if (scene && viewer.distanceToSqr(entity) < nearest) { target = living; nearest = viewer.distanceToSqr(entity); }
        }
        if (target == null) {
            row.add("vis", null);
            row.add("scr", null);
            return;
        }
        Vec3 eye = viewer.getEyePosition();
        var box = target.getBoundingBox();
        int visible = 0;
        for (int xi = 0; xi < 3; xi++) for (int yi = 0; yi < 3; yi++) {
            Vec3 at = new Vec3(box.minX + box.getXsize() * (.2 + .3 * xi),
                    box.minY + box.getYsize() * (.2 + .3 * yi), (box.minZ + box.maxZ) * .5);
            HitResult hit = source.getLevel().clip(new ClipContext(eye, at, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, viewer));
            if (hit.getType() == HitResult.Type.MISS || hit.getLocation().distanceTo(at) < .05) visible++;
        }
        row.addProperty("vis", visible);
        Vec3 centre = box.getCenter(), to = centre.subtract(eye);
        double distance = to.length();
        if (distance < 1e-6) { row.addProperty("scr", 1); return; }
        double yaw = Math.toRadians(viewer.getYRot()), pitch = Math.toRadians(viewer.getXRot());
        Vec3 forward = new Vec3(-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch));
        Vec3 right = new Vec3(Math.cos(yaw), 0, Math.sin(yaw));
        Vec3 up = forward.cross(right);
        double depth = to.dot(forward);
        double vertical = Math.atan2(to.dot(up), depth), horizontal = Math.atan2(to.dot(right), depth);
        double verticalHalf = Math.toRadians(35), horizontalHalf = Math.atan(Math.tan(verticalHalf) * 16.0 / 9.0);
        if (depth <= 0 || Math.abs(vertical) > verticalHalf || Math.abs(horizontal) > horizontalHalf) row.addProperty("scr", 0);
        else row.addProperty("scr", Math.min(1, box.getYsize() / (2 * distance * Math.tan(verticalHalf))));
    }

    private SceneGeometry() {}
}
