package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.body.BodyCavity;
import io.github.longshou.mcyukkuri.body.BodyWound;
import io.github.longshou.mcyukkuri.body.WoundSurface;
import io.github.longshou.mcyukkuri.family.LiveBirthGeometry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;
import org.joml.Matrix3f;
import org.joml.Vector3f;

/** Cached skin subtraction and skinned loft from the existing body-marks cavity source. */
final class CavityRenderer {
    static void clear() { BodyCavity.clear(); }

    private record Piece(float x, float y, float a, float b, float c) {
        Piece mix(Piece other, float t) { return new Piece(x+(other.x-x)*t, y+(other.y-y)*t, a+(other.a-a)*t, b+(other.b-b)*t, c+(other.c-c)*t); }
    }
    private record Vertex(Vector3f rest, float[] weights, Vector3f sourceNormal,
                          WoundSurface.Hit rim, Vector3f restRim, float deep) {}
    private record Face(int a, int b, int c, float red, float green, float blue, boolean core) {}
    final BodyCavity.Opening opening;
    private final Map<Integer, List<float[]>> skinCuts = new HashMap<>();
    private final List<Vertex> vertices = new ArrayList<>();
    private final List<Face> faces = new ArrayList<>();
    private final Vector3f[] posed, normals;
    private final Vector3f temp = new Vector3f(), edge = new Vector3f(), normal = new Vector3f();
    private final Vector3f a = new Vector3f(), b = new Vector3f(), c = new Vector3f();
    private final Vector3f na = new Vector3f(), nb = new Vector3f(), nc = new Vector3f();
    private final int tissue;
    private final Matrix3f normalMatrix = new Matrix3f();

    CavityRenderer(WoundSurface mesh, BodyCavity.Opening opening, Vector3f[] original, List<BodyWound> wounds, boolean broken, boolean corePresent) {
        this.opening = opening;
        tissue=mesh.tissueBone();
        for (int i = 0; i < original.length; i += 3) {
            var pieces = outside(original[i], original[i+1], original[i+2]);
            if (pieces != null) skinCuts.put(i, pieces);
        }
        build(mesh, wounds, broken, corePresent);
        posed = new Vector3f[vertices.size()]; normals = new Vector3f[vertices.size()];
        for (int i = 0; i < posed.length; i++) { posed[i] = new Vector3f(); normals[i] = new Vector3f(); }
    }

    /** Barycentric triangles outside the actual, possibly concave source outline. Null means unchanged. */
    List<float[]> outside(Vector3f a, Vector3f b, Vector3f c) {
        if (!opening.front(new Vector3f(a).add(b).add(c).div(3))) return null;
        float ax=opening.x(a), ay=opening.y(a), bx=opening.x(b), by=opening.y(b), cx=opening.x(c), cy=opening.y(c);
        float minX=Math.min(ax,Math.min(bx,cx)),maxX=Math.max(ax,Math.max(bx,cx)),minY=Math.min(ay,Math.min(by,cy)),maxY=Math.max(ay,Math.max(by,cy));
        if (minX > opening.radiusU*1.1f || maxX < -opening.radiusU*1.1f || minY > opening.radiusV*1.1f || maxY < -opening.radiusV*1.1f) return null;
        List<List<Piece>> remaining = new ArrayList<>();
        remaining.add(List.of(new Piece(ax,ay,1,0,0), new Piece(bx,by,0,1,0), new Piece(cx,cy,0,0,1)));
        boolean touched = false;
        var center = new float[]{0,0};
        for (int i=0;i<opening.polygon.length&&!remaining.isEmpty();i++) {
            var p=opening.polygon[i]; var q=opening.polygon[(i+1)%opening.polygon.length];
            if (Math.max(0,Math.max(p[0],q[0]))<minX || Math.min(0,Math.min(p[0],q[0]))>maxX
                    || Math.max(0,Math.max(p[1],q[1]))<minY || Math.min(0,Math.min(p[1],q[1]))>maxY) continue;
            float[][] triangle={center,p,q};
            var next = new ArrayList<List<Piece>>();
            for (var polygon:remaining) {
                var inside=polygon;
                var rejected = new ArrayList<List<Piece>>();
                for(int edge=0;edge<3&&!inside.isEmpty();edge++) {
                    var from=triangle[edge];var to=triangle[(edge+1)%3];
                    var outside=clip(inside,from,to,false);
                    if(outside.size()>=3)rejected.add(outside);
                    inside=clip(inside,from,to,true);
                }
                if(inside.size()<3)next.add(polygon);
                else { touched=true; next.addAll(rejected); }
            }
            remaining=next;
        }
        if(!touched)return null;
        var result=new ArrayList<float[]>();
        for(var polygon:remaining)for(int i=1;i+1<polygon.size();i++) {
            var p=polygon.getFirst();var q=polygon.get(i);var r=polygon.get(i+1);
            if(Math.abs((q.x-p.x)*(r.y-p.y)-(q.y-p.y)*(r.x-p.x))<1e-9)continue;
            result.add(new float[]{p.a,p.b,p.c,q.a,q.b,q.c,r.a,r.b,r.c});
        }
        return result;
    }
    private static List<Piece> clip(List<Piece> polygon,float[] from,float[] to,boolean inside) {
        var result=new ArrayList<Piece>();if(polygon.isEmpty())return result;
        var previous=polygon.getLast();float prev=side(from,to,previous);boolean prevIn=inside?prev>=0:prev<=0;
        for(var current:polygon) {
            float distance=side(from,to,current);boolean currentIn=inside?distance>=0:distance<=0;
            if(prevIn!=currentIn)result.add(previous.mix(current,Math.clamp(prev/(prev-distance),0,1)));
            if(currentIn)result.add(current);
            previous=current;prev=distance;prevIn=currentIn;
        }
        return result;
    }
    private static float side(float[] a,float[] b,Piece p) { return (b[0]-a[0])*(p.y-a[1])-(b[1]-a[1])*(p.x-a[0]); }

    private void build(WoundSurface mesh,List<BodyWound> wounds,boolean broken,boolean corePresent) {
        var levels=BodyCavity.profile().levels();int count=opening.rim.length;
        for(var level:levels)for(int j=0;j<count;j++) {
            var hit=opening.rim[j];var rim=BodyCavity.restRim(mesh,hit,wounds);
            var p=BodyCavity.wallRest(opening,rim,level);
            vertices.add(new Vertex(p,BodyCavity.rimWeights(mesh,hit),null,hit,rim,level[2]));
        }
        for(int r=0;r<levels.length-1;r++)for(int j=0;j<count;j++) {
            int k=(j+1)%count,a=r*count+j,b=r*count+k,c=(r+1)*count+k,d=(r+1)*count+j;
            // The local tangent basis faces outward; the source XY plane faced inward (+Z).
            var color=levels[r];faces.add(new Face(a,b,c,color[3],color[4],color[5],false));faces.add(new Face(a,c,d,color[3],color[4],color[5],false));
        }
        if(!corePresent)return;
        var source=BodyCavity.coreMesh();var data=source.vertices();int start=vertices.size();
        for(int i=0;i<data.length;i+=6) {
            var p=BodyCavity.coreRest(opening,data,i);
            var n=new Vector3f(data[i+3],data[i+4],data[i+5]).div(opening.span).normalize();
            vertices.add(new Vertex(p,new float[]{tissue,1},n,null,null,1));
        }
        var colours=broken?source.brokenColors():source.colors();
        for(int i=0;i<colours.length;i+=3)faces.add(new Face(start+i,start+i+1,start+i+2,colours[i],colours[i+1],colours[i+2],true));
    }

    /** Returns true when this replaces or removes the original skin/face triangle. */
    boolean skin(int triangle,WoundSurface mesh,Vector3f[] original,Vector3f[] sourceNormals,int[] slots,
                 PoseStack stack,VertexConsumer buffer,int light,int overlay) {
        var pieces=skinCuts.get(triangle);if(pieces==null)return false;
        for(var weights:pieces) {
            blend(original[triangle],original[triangle+1],original[triangle+2],weights,0,a);
            blend(original[triangle],original[triangle+1],original[triangle+2],weights,3,b);
            blend(original[triangle],original[triangle+1],original[triangle+2],weights,6,c);
            blend(sourceNormals[slots[triangle]],sourceNormals[slots[triangle+1]],sourceNormals[slots[triangle+2]],weights,0,na).normalize();
            blend(sourceNormals[slots[triangle]],sourceNormals[slots[triangle+1]],sourceNormals[slots[triangle+2]],weights,3,nb).normalize();
            blend(sourceNormals[slots[triangle]],sourceNormals[slots[triangle+1]],sourceNormals[slots[triangle+2]],weights,6,nc).normalize();
            WoundRenderer.emit(stack,buffer,light,overlay,a,b,c,uv(mesh,triangle,weights,0,3),uv(mesh,triangle,weights,0,4),
                    uv(mesh,triangle,weights,3,3),uv(mesh,triangle,weights,3,4),uv(mesh,triangle,weights,6,3),uv(mesh,triangle,weights,6,4),na,nb,nc);
        }
        return true;
    }
    private static float uv(WoundSurface mesh,int triangle,float[] weights,int start,int coordinate) {
        return mesh.points[triangle*5+coordinate]*weights[start]+mesh.points[(triangle+1)*5+coordinate]*weights[start+1]+mesh.points[(triangle+2)*5+coordinate]*weights[start+2];
    }
    private static Vector3f blend(Vector3f a,Vector3f b,Vector3f c,float[] weights,int start,Vector3f out) {
        return out.set(a).mul(weights[start]).fma(weights[start+1],b).fma(weights[start+2],c);
    }
    void render(Matrix4f[] world,Vector3f[] skin,float pregnancy,PoseStack stack,MultiBufferSource buffers,int light,int overlay) {
        for(int i=0;i<vertices.size();i++) {
            var vertex=vertices.get(i);var p=posed[i].zero();var weights=vertex.weights;
            if(vertex.rim==null){
                for(int k=0;k<weights.length;k+=2){world[(int)weights[k]].transformPosition(vertex.rest,temp);p.fma(weights[k+1],temp);}
                if(pregnancy>.001f)LiveBirthGeometry.deform(p,pregnancy,0);
            }else{
                var hit=vertex.rim;p.set(skin[hit.triangle()]).mul(1-hit.u()-hit.v()).fma(hit.u(),skin[hit.triangle()+1]).fma(hit.v(),skin[hit.triangle()+2]);
                BodyCavity.poseWall(vertex.rest,vertex.restRim,weights,vertex.deep,p,world,tissue,pregnancy,p,edge,temp);
            }
            normals[i].zero();
        }
        for(var face:faces){normal.set(posed[face.b]).sub(posed[face.a]).cross(edge.set(posed[face.c]).sub(posed[face.a]));
            normals[face.a].add(normal);normals[face.b].add(normal);normals[face.c].add(normal);}
        for(var n:normals){if(n.lengthSquared()>1e-12)n.normalize();else n.set(opening.normal);}
        // The authored soft core keeps its smooth normals, including non-uniform body scale.
        world[tissue].normal(normalMatrix);
        for(int i=0;i<vertices.size();i++){var vertex=vertices.get(i);if(vertex.sourceNormal==null)continue;
            normalMatrix.transform(vertex.sourceNormal,normals[i]).normalize();}
        var buffer=buffers.getBuffer(RenderType.entityCutoutNoCull(MCYukkuri.id("textures/props/body_cavity_white.png")));
        var transform=stack.last();
        for(var face:faces)for(int j=0;j<4;j++) {
            int i=j==0?face.a:j==1?face.b:face.c;var p=posed[i];var n=normals[i];
            buffer.addVertex(transform.pose(),p.x/16,p.y/16,p.z/16).setColor(Math.round(face.red),Math.round(face.green),Math.round(face.blue),255)
                    .setUv(.5f,.5f).setOverlay(overlay).setLight(light).setNormal(transform,n.x,n.y,n.z);
        }
    }
}
