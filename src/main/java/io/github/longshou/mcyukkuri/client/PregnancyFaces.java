package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.longshou.mcyukkuri.family.LiveBirthGeometry;
import java.util.ArrayList;
import java.util.List;
import org.joml.Vector3f;

/** Project expression panels onto the actual posed belly triangles, including their creases. */
final class PregnancyFaces {
    static final class Surface {
        final List<Vector3f[]> triangles=new ArrayList<>();
        final java.util.TreeSet<Float> levels=new java.util.TreeSet<>();
        void add(Vector3f[] points){
            if(new Vector3f(points[1]).sub(points[0]).cross(new Vector3f(points[2]).sub(points[0])).z>=-.00001f)return;
            var copy=new Vector3f[3];for(int i=0;i<3;i++){copy[i]=new Vector3f(points[i]);levels.add(copy[i].y);}triangles.add(copy);
        }
        void fit(Vector3f p){
            float front=Float.POSITIVE_INFINITY;
            for(var t:triangles){
                float ax=t[1].x-t[0].x,ay=t[1].y-t[0].y,bx=t[2].x-t[0].x,by=t[2].y-t[0].y,d=ax*by-ay*bx;
                if(Math.abs(d)<1e-8f)continue;
                float x=p.x-t[0].x,y=p.y-t[0].y,u=(x*by-y*bx)/d,v=(ax*y-ay*x)/d;
                if(u>=-.0001f&&v>=-.0001f&&u+v<=1.0001f)front=Math.min(front,t[0].z+u*(t[1].z-t[0].z)+v*(t[2].z-t[0].z));
            }
            if(Float.isFinite(front))p.z=Math.min(p.z,front-.035f);
        }
    }
    private record Vertex(Vector3f point,float u,float v){
        Vertex mix(Vertex other,float q){return new Vertex(new Vector3f(point).lerp(other.point,q),u+(other.u-u)*q,v+(other.v-v)*q);}
    }
    static void render(Vector3f[] points,float[] uv,float belly,float contraction,LiveBirthRenderer.Delivery delivery,
                       PoseStack stack,VertexConsumer buffer,int light,int overlay,Surface surface){
        var source=new ArrayList<Vertex>();float low=Float.MAX_VALUE,high=-Float.MAX_VALUE;
        for(int i=0;i<3;i++){var p=new Vector3f(points[i]);LiveBirthGeometry.deform(p,belly,contraction);source.add(new Vertex(p,uv[i*2],uv[i*2+1]));low=Math.min(low,p.y);high=Math.max(high,p.y);}
        var levels=new ArrayList<Float>();levels.add(low);levels.addAll(surface.levels.subSet(low,false,high,false));levels.add(high);
        for(int band=0;band+1<levels.size();band++){
            var strip=clip(clip(source,levels.get(band),true),levels.get(band+1),false);
            for(var vertex:strip)surface.fit(vertex.point);
            for(int i=1;i+1<strip.size();i++){
                Vertex[] tri={strip.getFirst(),strip.get(i),strip.get(i+1)};
                Vector3f[] p={tri[0].point,tri[1].point,tri[2].point};
                float[] texture={tri[0].u,tri[0].v,tri[1].u,tri[1].v,tri[2].u,tri[2].v};
                if(delivery!=null&&delivery.drawMotherTriangle(stack,buffer,light,overlay,p,texture))continue;
                var normal=new Vector3f(p[1]).sub(p[0]).cross(new Vector3f(p[2]).sub(p[0]));
                if(normal.lengthSquared()<1e-12)continue;normal.normalize();
                for(int k=0;k<4;k++){
                    var vertex=tri[Math.min(k,2)];var point=vertex.point;
                    buffer.addVertex(stack.last().pose(),point.x/16,point.y/16,point.z/16).setColor(255,255,255,255)
                            .setUv(vertex.u,vertex.v).setOverlay(overlay).setLight(light).setNormal(stack.last(),normal.x,normal.y,normal.z);
                }
            }
        }
    }
    private static List<Vertex> clip(List<Vertex> input,float boundary,boolean above){
        var output=new ArrayList<Vertex>();if(input.isEmpty())return output;
        var previous=input.getLast();float pd=(previous.point.y-boundary)*(above?1:-1);
        for(var current:input){
            float cd=(current.point.y-boundary)*(above?1:-1);
            if((pd>=0)!=(cd>=0))output.add(previous.mix(current,pd/(pd-cd)));
            if(cd>=0)output.add(new Vertex(new Vector3f(current.point),current.u,current.v));
            previous=current;pd=cd;
        }
        return output;
    }
    private PregnancyFaces(){}
}
