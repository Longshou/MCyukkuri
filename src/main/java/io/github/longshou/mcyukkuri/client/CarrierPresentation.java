package io.github.longshou.mcyukkuri.client;

import com.google.common.collect.MapMaker;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import io.github.longshou.mcyukkuri.item.CarrierContents;
import java.util.Arrays;
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
    // Stack copies share an immutable component. Its layout keeps the shared meshes alive while the stack exists.
    private static final Map<CustomData, Seat[]> LAYOUTS = new MapMaker().weakKeys().makeMap();
    private static final Map<Look, Mesh> MESHES = new MapMaker().weakValues().makeMap();
    private static final Vector3f POSITION = new Vector3f();
    private static final Vector3f NORMAL = new Vector3f();
    /** Basket meshes are authored in 1/16-block units; the occupant mesh is drawn in blocks. */
    private static final float UNITS = 16;

    private record Look(String model, int form, boolean accessory, int headwear, int hair, int medal, boolean asleep) {}
    private record Seat(Mesh mesh, float x, float y, float z, float scale) {}
    private static final Seat[] EMPTY = new Seat[0];

    private static void clear() {
        LAYOUTS.clear();
        MESHES.clear();
    }

    static void render(ItemStack stack, PoseStack.Pose pose, MultiBufferSource buffers, int light, int overlay) {
        var data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return;
        var seats = LAYOUTS.get(data);
        if (seats == null) LAYOUTS.put(data, seats = layout(data));
        for (var seat : seats) {
            for (var batch : seat.mesh.batches) {
                var out = buffers.getBuffer(batch.type);
                var v = batch.vertices;
                for (int i = 0; i < batch.length; i += Batch.STRIDE) {
                    pose.pose().transformPosition(seat.x + v[i] * seat.scale, seat.y + v[i + 1] * seat.scale,
                            seat.z + v[i + 2] * seat.scale, POSITION);
                    pose.transformNormal(v[i + 5], v[i + 6], v[i + 7], NORMAL);
                    out.addVertex(POSITION.x, POSITION.y, POSITION.z).setColor(batch.colors[i / Batch.STRIDE])
                            .setUv(v[i + 3], v[i + 4]).setOverlay(overlay).setLight(light).setNormal(NORMAL.x, NORMAL.y, NORMAL.z);
                }
            }
        }
    }

    private static Seat[] layout(CustomData data) {
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
        var boxes = new BasketSeating.Box[count];
        for (int i = 0; i < count; i++) {
            var mesh = meshes[i];
            boxes[i] = new BasketSeating.Box(mesh.minX * UNITS, mesh.minY * UNITS, mesh.minZ * UNITS,
                    mesh.maxX * UNITS, mesh.maxY * UNITS, mesh.maxZ * UNITS);
        }
        var placed = BasketSeating.arrange(boxes, scales);
        var seats = new Seat[count];
        for (int i = 0; i < count; i++) {
            var seat = placed[i];
            seats[i] = new Seat(meshes[i], seat.x() / UNITS, seat.y() / UNITS, seat.z() / UNITS, seat.scale());
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
                batch.colors = Arrays.copyOf(batch.colors, batch.length / Batch.STRIDE);
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
        static final int STRIDE = 8;
        private final RenderType type;
        private float[] vertices = new float[STRIDE * 256];
        private int[] colors = new int[256];
        private int length;

        Batch(RenderType type) { this.type = type; }
        @Override public VertexConsumer addVertex(float x, float y, float z) {
            if (length == vertices.length) {
                vertices = Arrays.copyOf(vertices, length * 2);
                colors = Arrays.copyOf(colors, colors.length * 2);
            }
            length += STRIDE;
            vertices[length - 8] = x;
            vertices[length - 7] = y;
            vertices[length - 6] = z;
            return this;
        }
        @Override public VertexConsumer setUv(float u, float v) {
            vertices[length - 5] = u;
            vertices[length - 4] = v;
            return this;
        }
        @Override public VertexConsumer setNormal(float x, float y, float z) {
            vertices[length - 3] = x;
            vertices[length - 2] = y;
            vertices[length - 1] = z;
            return this;
        }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) {
            colors[length / STRIDE - 1] = (a << 24) | (r << 16) | (g << 8) | b;
            return this;
        }
        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { return this; }
    }

    private CarrierPresentation() {}
}
