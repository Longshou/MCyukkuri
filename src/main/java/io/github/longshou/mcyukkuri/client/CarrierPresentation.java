package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import io.github.longshou.mcyukkuri.item.CarrierContents;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import org.joml.Vector3f;

/** An occupant's stopped pose comes from the living model. Cache its drawn vertices and the basket layout on change. */
final class CarrierPresentation {
    static final ResourceManagerReloadListener RELOAD = resources -> clear();
    private static final Map<CustomData, Seat[][]> LAYOUTS = new IdentityHashMap<>();
    private static final Map<Look, Mesh> MESHES = new HashMap<>();
    private static final Vector3f POSITION = new Vector3f();
    private static final Vector3f NORMAL = new Vector3f();

    private record Look(String model, int form, boolean accessory, int headwear, int hair, int medal, boolean asleep) {}
    private record Seat(Mesh mesh, float x, float y, float z, float scale) {}
    private static final Seat[] EMPTY = new Seat[0];

    private static void clear() {
        LAYOUTS.clear();
        MESHES.clear();
    }

    static void render(ItemStack stack, String basket, PoseStack.Pose pose, MultiBufferSource buffers, int light, int overlay) {
        var data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return;
        boolean large = basket.equals("yukkuri_carrier_large");
        int size = large ? 1 : 0;
        var layouts = LAYOUTS.get(data);
        if (layouts == null) {
            // Bound both item revisions and baked appearances. Stack copies share their immutable CustomData.
            if (LAYOUTS.size() >= 128 || MESHES.size() >= 32) clear();
            layouts = new Seat[2][];
            LAYOUTS.put(data, layouts);
        }
        var seats = layouts[size];
        if (seats == null) layouts[size] = seats = layout(data, large);
        for (var seat : seats) {
            for (var batch : seat.mesh.batches) {
                var out = buffers.getBuffer(batch.type);
                var v = batch.vertices;
                for (int i = 0; i < batch.length; i += Batch.STRIDE) {
                    pose.pose().transformPosition(seat.x + v[i] * seat.scale, seat.y + v[i + 1] * seat.scale,
                            seat.z + v[i + 2] * seat.scale, POSITION);
                    pose.transformNormal(v[i + 5], v[i + 6], v[i + 7], NORMAL);
                    out.addVertex(POSITION.x, POSITION.y, POSITION.z).setColor(Float.floatToRawIntBits(v[i + 8]))
                            .setUv(v[i + 3], v[i + 4]).setOverlay(overlay).setLight(light).setNormal(NORMAL.x, NORMAL.y, NORMAL.z);
                }
            }
        }
    }

    private static Seat[] layout(CustomData data, boolean large) {
        var entries = CarrierContents.appearances(data);
        var meshes = new Mesh[entries.size()];
        var scales = new float[entries.size()];
        int count = 0;
        for (int i = 0; i < entries.size(); i++) {
            var entry = entries.getCompound(i);
            if (!entry.contains("Model")) continue; // An older stack can arrive before the ledger has upgraded it.
            var look = new Look(entry.getString("Model"), entry.getInt("Form"), entry.getBoolean("Accessory"),
                    entry.getInt("Headwear"), entry.getInt("HairLoss"),
                    entry.getBoolean("BadgeWorn") ? entry.getInt("Badge") : 0, entry.getBoolean("Asleep"));
            meshes[count] = MESHES.computeIfAbsent(look, Mesh::new);
            scales[count++] = entry.getFloat("RenderScale");
        }
        if (count == 0) return EMPTY;
        int columns = (int) Math.ceil(Math.sqrt(count));
        int rows = (count + columns - 1) / columns;
        // The runtime meshes have a rectangular lined floor at y=1. The small baskets have an 8x6 floor,
        // the large has 10x8. The handle underside is 9 or 10 units above that floor.
        float width = (large ? 10 : 8) / (16f * columns);
        float depth = (large ? 8 : 6) / (16f * rows);
        float height = (large ? 10 : 9) / 16f;
        float gap = .125f / 16;
        float fit = 1;
        for (int i = 0; i < count; i++) {
            var mesh = meshes[i];
            float scale = scales[i];
            fit = Math.min(fit, (width - gap) / (scale * (mesh.maxX - mesh.minX)));
            fit = Math.min(fit, (depth - gap) / (scale * (mesh.maxZ - mesh.minZ)));
            fit = Math.min(fit, (height - gap) / (scale * (mesh.maxY - mesh.minY)));
        }
        var seats = new Seat[count];
        for (int i = 0; i < count; i++) {
            var mesh = meshes[i];
            float scale = fit * scales[i];
            int row = i / columns;
            int across = Math.min(columns, count - row * columns);
            float x = (i % columns - (across - 1) * .5f) * width - (mesh.minX + mesh.maxX) * .5f * scale;
            float z = ((rows - 1) * .5f - row) * depth - (mesh.minZ + mesh.maxZ) * .5f * scale;
            seats[i] = new Seat(mesh, x, 1 / 16f - mesh.minY * scale, z, scale);
        }
        return seats;
    }

    private static final class Mesh {
        private final Batch[] batches;
        private float minX = Float.POSITIVE_INFINITY, minY = minX, minZ = minX;
        private float maxX = Float.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;

        Mesh(Look look) {
            var parts = new LinkedHashMap<RenderType, Batch>();
            var pose = new PoseStack();
            // The runtime body faces -Z. The displayed item's front (including a frame's visible side) faces +Z.
            pose.mulPose(Axis.YP.rotationDegrees(180));
            YukkuriModel.get(look.model).renderBasketOccupant(look.form, look.accessory, look.headwear, look.hair,
                    look.medal, look.asleep, pose, type -> parts.computeIfAbsent(type, Batch::new));
            batches = parts.values().toArray(Batch[]::new);
            for (var batch : batches) {
                batch.vertices = Arrays.copyOf(batch.vertices, batch.length);
                var v = batch.vertices;
                for (int i = 0; i < v.length; i += Batch.STRIDE) {
                    minX = Math.min(minX, v[i]);     maxX = Math.max(maxX, v[i]);
                    minY = Math.min(minY, v[i + 1]); maxY = Math.max(maxY, v[i + 1]);
                    minZ = Math.min(minZ, v[i + 2]); maxZ = Math.max(maxZ, v[i + 2]);
                }
            }
        }
    }

    /** The runtime renderer supplies the quad order, material and normals. Light and overlay come from the current item. */
    private static final class Batch implements VertexConsumer {
        static final int STRIDE = 9;
        private final RenderType type;
        private float[] vertices = new float[STRIDE * 256];
        private int length;

        Batch(RenderType type) { this.type = type; }
        @Override public VertexConsumer addVertex(float x, float y, float z) {
            if (length == vertices.length) vertices = Arrays.copyOf(vertices, length * 2);
            length += STRIDE;
            vertices[length - 9] = x;
            vertices[length - 8] = y;
            vertices[length - 7] = z;
            return this;
        }
        @Override public VertexConsumer setUv(float u, float v) {
            vertices[length - 6] = u;
            vertices[length - 5] = v;
            return this;
        }
        @Override public VertexConsumer setNormal(float x, float y, float z) {
            vertices[length - 4] = x;
            vertices[length - 3] = y;
            vertices[length - 2] = z;
            return this;
        }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) {
            vertices[length - 1] = Float.intBitsToFloat((a << 24) | (r << 16) | (g << 8) | b);
            return this;
        }
        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { return this; }
    }

    private CarrierPresentation() {}
}
