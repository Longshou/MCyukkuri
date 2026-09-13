package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.longshou.mcyukkuri.family.LiveBirthGeometry;
import java.util.ArrayList;
import java.util.List;
import org.joml.Vector3f;

/** Keep large expression panels on the authored 0.265-unit body slices while the belly bends. */
final class PregnancyFaces {
    private record Vertex(Vector3f point,float u,float v){
        Vertex mix(Vertex other,float q){return new Vertex(new Vector3f(point).lerp(other.point,q),u+(other.u-u)*q,v+(other.v-v)*q);}
    }
    static void render(Vector3f[] points,float[] uv,float belly,float contraction,LiveBirthRenderer.Delivery delivery,
                       PoseStack stack,VertexConsumer buffer,int light,int overlay){
        var source=new ArrayList<Vertex>();float low=Float.MAX_VALUE,high=-Float.MAX_VALUE;
        for(int i=0;i<3;i++){source.add(new Vertex(new Vector3f(points[i]),uv[i*2],uv[i*2+1]));low=Math.min(low,points[i].y);high=Math.max(high,points[i].y);}
        int begin=(int)Math.floor(low/.265f),end=(int)Math.floor(high/.265f);
        for(int band=begin;band<=end;band++){
            var strip=clip(clip(source,band*.265f,true),(band+1)*.265f,false);
            for(var vertex:strip)LiveBirthGeometry.deform(vertex.point,belly,contraction);
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
