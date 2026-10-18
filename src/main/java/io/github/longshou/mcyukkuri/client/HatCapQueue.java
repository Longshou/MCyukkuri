package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Inner caps and rims of damaged headwear, held back until the cloth around them is drawn. They use their own texture,
 * and an immediate buffer source ends the buffer in use the moment another texture's buffer is requested: cloth and caps
 * written interleaved crash the client ("Not building!"). Cells are queued while the cloth is drawn and written afterwards.
 */
final class HatCapQueue {
    private record Cell(Matrix4f pose, Matrix3f normalMatrix, float[] corners, int color, float nx, float ny, float nz, int light, int overlay) {}

    private static final int[][] TRIANGLES = {{0, 1, 2}, {0, 2, 3}};
    private final ArrayList<Cell> cells = new ArrayList<>();
    private Matrix4f lastPose;
    private Matrix3f lastNormal;
    private final Vector3f normal = new Vector3f();

    /** Queue one quad cell: four corners in model units (sixteenths of a block), a tint and the face normal. */
    void add(PoseStack.Pose transform, Vector3f[] points, int color, int light, int overlay, Vector3f n) {
        if (lastPose == null || !lastPose.equals(transform.pose()) || !lastNormal.equals(transform.normal())) {
            lastPose = new Matrix4f(transform.pose());
            lastNormal = new Matrix3f(transform.normal());
        }
        float[] corners = new float[12];
        for (int k = 0; k < 4; k++) {
            corners[k * 3] = points[k].x;
            corners[k * 3 + 1] = points[k].y;
            corners[k * 3 + 2] = points[k].z;
        }
        cells.add(new Cell(lastPose, lastNormal, corners, color, n.x, n.y, n.z, light, overlay));
    }

    void clear() {
        cells.clear();
        lastPose = null;
        lastNormal = null;
    }

    /** Write every queued cell into the cap texture's buffer. Call only once nothing more is written to the cloth's buffer. */
    void draw(MultiBufferSource buffers) {
        if (cells.isEmpty()) return;
        try {
            VertexConsumer buffer = buffers.getBuffer(RenderType.entityCutoutNoCull(HatDamageFaces.INNER_TEXTURE));
            for (var cell : cells) {
                cell.normalMatrix().transform(normal.set(cell.nx(), cell.ny(), cell.nz())).normalize();
                int r = cell.color() >> 16 & 255, g = cell.color() >> 8 & 255, b = cell.color() & 255;
                float[] c = cell.corners();
                // Two triangles, each written as a quad with its third corner repeated, like the cloth around them.
                for (int[] triangle : TRIANGLES) {
                    for (int corner = 0; corner < 4; corner++) {
                        int k = triangle[Math.min(corner, 2)] * 3;
                        buffer.addVertex(cell.pose(), c[k] / 16, c[k + 1] / 16, c[k + 2] / 16).setColor(r, g, b, 255)
                                .setUv(.5f, .5f).setOverlay(cell.overlay()).setLight(cell.light()).setNormal(normal.x, normal.y, normal.z);
                    }
                }
            }
        } finally {
            clear();
        }
    }
}
