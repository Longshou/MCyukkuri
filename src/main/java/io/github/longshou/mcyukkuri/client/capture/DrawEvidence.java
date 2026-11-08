package io.github.longshou.mcyukkuri.client.capture;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.longshou.mcyukkuri.pose.check.PoseGeometry;
import net.minecraft.client.renderer.MultiBufferSource;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Observes emitted quads without changing their attributes, buffers or ordering. */
public final class DrawEvidence {
    public final JsonObject meshes = new JsonObject();
    private final Matrix4f local;

    public DrawEvidence(Matrix4f drawn) {
        local = new Matrix4f().scaling(16).mul(new Matrix4f(drawn).invert());
    }

    public MultiBufferSource buffers(String group, MultiBufferSource source) {
        return type -> buffer(group, source.getBuffer(type));
    }

    public VertexConsumer buffer(String group, VertexConsumer source, String... aliases) {
        if (source instanceof Tap tap) source = tap.source;
        return new Tap(group, source, aliases);
    }

    private final class Tap implements VertexConsumer {
        private final String group;
        private final VertexConsumer source;
        private final String[] aliases;
        private final Vector3f[] quad = new Vector3f[4];
        private int corner;
        Tap(String group, VertexConsumer source, String[] aliases) { this.group=group; this.source=source; this.aliases=aliases; }
        @Override public VertexConsumer addVertex(float x,float y,float z) {
            source.addVertex(x,y,z);
            quad[corner++] = local.transformPosition(new Vector3f(x,y,z));
            if (corner == 4) {
                corner=0;
                if (!group.equals("other")) {
                    var triangles=new JsonArray();
                    for(int[] indices:new int[][]{{0,1,2},{0,2,3}}) {
                        var a=quad[indices[0]]; var b=quad[indices[1]]; var c=quad[indices[2]];
                        if(new Vector3f(b).sub(a).cross(new Vector3f(c).sub(a)).lengthSquared()==0)continue;
                        var tri=new JsonArray();
                        for(int i:indices)tri.add(PoseGeometry.point(quad[i]));
                        triangles.add(tri);
                    }
                    PoseGeometry.append(meshes,group,triangles,false);
                    for(String alias:aliases)PoseGeometry.append(meshes,alias,triangles,false);
                }
            }
            return this;
        }
        @Override public VertexConsumer setColor(int r,int g,int b,int a) { source.setColor(r,g,b,a); return this; }
        @Override public VertexConsumer setUv(float u,float v) { source.setUv(u,v); return this; }
        @Override public VertexConsumer setUv1(int u,int v) { source.setUv1(u,v); return this; }
        @Override public VertexConsumer setUv2(int u,int v) { source.setUv2(u,v); return this; }
        @Override public VertexConsumer setNormal(float x,float y,float z) { source.setNormal(x,y,z); return this; }
    }
}
