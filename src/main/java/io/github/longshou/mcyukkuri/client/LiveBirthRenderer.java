package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.LiveBirthGeometry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.joml.Vector3f;

/** Client-only view of the saved litter. Clipping and emergence never create an entity. */
final class LiveBirthRenderer {
    private static final Map<YukkuriModel,float[]> SKINS=new WeakHashMap<>(),CHILDREN=new WeakHashMap<>(),MAMU=new WeakHashMap<>(),MARKS=new WeakHashMap<>();
    private record Vertex(Vector3f point,float u,float v){
        Vertex mix(Vertex other,float q){return new Vertex(new Vector3f(point).lerp(other.point,q),u+(other.u-u)*q,v+(other.v-v)*q);}
    }
    static float contraction(YukkuriEntity mother,float partial){
        if(mother.liveDeliveryTicks()<0||Integer.bitCount(mother.liveBornMask())>=mother.liveChildCount()||!mother.visibleWounds().isEmpty())return 0;
        // A paused server phase also pauses the contraction; interpolation cannot advance a birth.
        return LiveBirthGeometry.contraction(mother.liveDeliveryTicks());
    }
    static Delivery prepare(YukkuriEntity mother,YukkuriModel model,float partial){
        boolean recovering=mother.getAnimationId().equals("postpartum")&&mother.livePregnancyProgress()>0;
        boolean unborn=mother.liveDeliveryTicks()>=0&&Integer.bitCount(mother.liveBornMask())<mother.liveChildCount();
        if(!unborn&&!recovering)return null;
        if(!mother.visibleWounds().isEmpty()||!mother.contactPose().equals("NONE"))return null;
        if(MAMU.computeIfAbsent(model,key->key.deliveryMamuVertices(false)).length==0)return null;
        return new Delivery(mother,model,contraction(mother,partial));
    }
    static void renderDose(YukkuriEntity entity,PoseStack pose,MultiBufferSource buffers,int light){
        // Single-use applicator at the rear; the delivery outlet is a different front surface.
        pose.pushPose();pose.translate(0,.10,.64);pose.mulPose(com.mojang.math.Axis.XP.rotationDegrees(-90));
        pose.scale(.75f,.75f,.75f);PropModels.render("reproductive_filling",1,pose,buffers,light,OverlayTexture.NO_OVERLAY);pose.popPose();
    }
    static final class Delivery {
        private final YukkuriEntity mother;
        private final List<Vertex[]> skin=new ArrayList<>(),child=new ArrayList<>(),mamu=new ArrayList<>();
        private final List<Vector3f> hole;
        private final Vector3f center;
        private final float plane;
        private final String childId;
        private final boolean visible;
        private final float markU,markV;
        /** Root pose of the mother's current motion; the patch is built in rest space and placed with it. */
        private final org.joml.Matrix4f root=new org.joml.Matrix4f(),rest=new org.joml.Matrix4f();
        void follow(org.joml.Matrix4f pose){root.set(pose);rest.set(pose).invert();}
        private Vector3f at(Vector3f p){return root.transformPosition(new Vector3f(p));}
        private List<Vertex> placed(List<Vertex> polygon){
            var out=new ArrayList<Vertex>(polygon.size());for(var v:polygon)out.add(new Vertex(at(v.point),v.u,v.v));return out;
        }
        Delivery(YukkuriEntity mother,YukkuriModel model,float contraction){
            this.mother=mother;
            float belly=mother.livePregnancyProgress();
            var outlet=LiveBirthGeometry.modelOutlet(mother,belly,contraction);
            float[] maternal=SKINS.computeIfAbsent(model,key->key.deliveryVertices(true));
            for(int i=0;i<maternal.length;i+=24){
                var tri=triangle(maternal,i);for(var v:tri)LiveBirthGeometry.deform(v.point,belly,contraction);skin.add(tri);
            }
            // This is the selected mamu_base + mamu_mark, in its authored extended pose.
            // The model's ordinary anatomy pass remains hidden to avoid drawing it twice.
            float[] selected=MAMU.computeIfAbsent(model,key->key.deliveryMamuVertices(false));
            float[] mark=MARKS.computeIfAbsent(model,key->key.deliveryMamuVertices(true));
            float inkU=0,inkV=0;for(int i=0;i<mark.length;i+=8){inkU+=mark[i+3];inkV+=mark[i+4];}
            markU=mark.length>0?inkU/(mark.length/8):selected[3];markV=mark.length>0?inkV/(mark.length/8):selected[4];
            for(int i=0;i<selected.length;i+=24){
                var tri=triangle(selected,i);for(var vertex:tri)LiveBirthGeometry.deform(vertex.point,belly,contraction);mamu.add(tri);
            }
            // Use the actual curved component's front at its own site, not a second outlet.
            plane=frontSurface(mamu,outlet.x,outlet.y,outlet.z)-.015f;
            int next=0;while(next<mother.liveChildCount()&&(mother.liveBornMask()&(1<<next))!=0)next++;
            boolean unborn=next<mother.liveChildCount()&&mother.liveDeliveryTicks()>=0;
            childId=unborn?mother.liveChildAssetId(next):mother.subspecies().assetId();
            float elapsed=unborn?mother.liveDeliveryTicks()-LiveBirthGeometry.emergenceStart(next):-1;
            // The saved server clock owns both pushes and the breath between them. Never advance
            // this with render time: a blocked outlet must hold the child and the mother's effort.
            float progress=unborn?LiveBirthGeometry.emergenceProgress(mother.liveDeliveryTicks(),next):1;visible=unborn&&elapsed>0;
            float scale=LiveBirthGeometry.BABY_SCALE/mother.growthScale();
            float[] newborn=unborn?CHILDREN.computeIfAbsent(YukkuriModel.get(childId),key->key.deliveryVertices(false)):new float[0];
            float minZ=0,maxZ=0;for(int i=2;i<newborn.length;i+=8){minZ=Math.min(minZ,newborn[i]*scale);maxZ=Math.max(maxZ,newborn[i]*scale);}
            float endZ=unborn?-(float)LiveBirthGeometry.exitFront(mother,next)*16/mother.growthScale():plane;
            // Keep the child in the actual mamu through the two pushes. Only the last fifth
            // slides the fully emerged child to the collision-safe server spawn position.
            float startZ=plane-minZ,clearZ=plane-maxZ-.03f;
            float z=progress<=.8f?startZ+(clearZ-startZ)*(progress/.8f)
                    :clearZ+(endZ-clearZ)*LiveBirthGeometry.smooth((progress-.8f)/.2f);
            float bodyHeight=LiveBirthGeometry.modelHeight(childId),halfHeight=bodyHeight*.5f;
            float baseY=Math.max(.015f*16/mother.growthScale(),outlet.y-bodyHeight*scale*.5f);
            for(int i=0;i<newborn.length;i+=24){
                var tri=triangle(newborn,i);
                for(var v:tri){
                    float px=v.point.x,py=v.point.y,pz=v.point.z*scale+z;
                    float pressure=visible?LiveBirthGeometry.smooth(progress/.12f)*(1-LiveBirthGeometry.smooth((progress-.78f)/.22f))
                            *(float)Math.exp(-Math.pow((pz-plane)/.60,2)):0;
                    float nx=Math.min(1,Math.abs(px/6)),ny=Math.min(1,Math.abs((py-halfHeight)/halfHeight));
                    float sx=(1-.24f*pressure)*(float)Math.sqrt(1-.32f*ny*ny*pressure);
                    float sy=(1-.18f*pressure)*(float)Math.sqrt(1-.32f*nx*nx*pressure);
                    v.point.set(outlet.x+px*scale*sx,((py-halfHeight)*sy+halfHeight)*scale+baseY,pz);
                }
                child.add(tri);
            }
            var section=new ArrayList<Vector3f>();
            if(visible)for(var tri:child)for(int k=0;k<3;k++){
                Vector3f a=tri[k].point,b=tri[(k+1)%3].point;
                if((a.z<=plane&&b.z>plane)||(b.z<=plane&&a.z>plane))section.add(new Vector3f(a).lerp(b,(plane-a.z)/(b.z-a.z)));
            }
            var sectionHull=hull(section);hole=sectionHull.size()>2?sectionHull:List.of();center=new Vector3f(outlet.x,outlet.y,plane);
            if(hole.size()>2){
                center.x=0;center.y=0;for(var p:hole){center.x+=p.x/hole.size();center.y+=p.y/hole.size();}
                for(var p:hole){var radial=new Vector3f(p).sub(center);radial.z=0;if(radial.lengthSquared()>1e-10)p.fma(.018f,radial.normalize());p.z=plane;}
            }
            float halfWidth=0,halfHeightMamu=0,holeWidth=0,holeHeight=0;
            for(var tri:mamu)for(var vertex:tri){halfWidth=Math.max(halfWidth,Math.abs(vertex.point.x-outlet.x));halfHeightMamu=Math.max(halfHeightMamu,Math.abs(vertex.point.y-outlet.y));}
            for(var p:hole){holeWidth=Math.max(holeWidth,Math.abs(p.x-outlet.x));holeHeight=Math.max(holeHeight,Math.abs(p.y-outlet.y));}
            float swell=visible?LiveBirthGeometry.smooth(progress/.2f)*(1-LiveBirthGeometry.smooth((progress-.8f)/.2f)):0;
            float width=Math.max(1+.35f*swell,(holeWidth+.10f)/Math.max(.01f,halfWidth));
            float height=Math.max(1+.65f*swell,(holeHeight+.10f)/Math.max(.01f,halfHeightMamu));
            float extension=unborn?LiveBirthGeometry.smooth(mother.liveDeliveryTicks()/5f)
                    :LiveBirthGeometry.smooth(belly/.22f*LiveBirthGeometry.RECOVERY/13f);
            for(var tri:mamu)for(var vertex:tri){
                var p=vertex.point;
                // Keep the authored skin/ink relief on the body's curved surface when stretched.
                if(width!=1||height!=1){
                    float depth=p.z-surfaceZ(p.x,p.y);
                    p.x=outlet.x+(p.x-outlet.x)*width;p.y=outlet.y+(p.y-outlet.y)*height;
                    p.z=surfaceZ(p.x,p.y)+depth;
                }
                p.z+=.6f*(1-extension);
            }
        }
        boolean drawMotherTriangle(PoseStack pose,VertexConsumer buffer,int light,int overlay,Vector3f[] posed,float[] uv){
            if(hole.size()<3)return false;
            // The hole is cut in rest space; the posed skin is brought back there and the pieces placed again.
            Vector3f[] points={rest.transformPosition(new Vector3f(posed[0])),rest.transformPosition(new Vector3f(posed[1])),rest.transformPosition(new Vector3f(posed[2]))};
            if((points[0].z+points[1].z+points[2].z)/3>plane+1.6f)return false;
            float left=Float.MAX_VALUE,right=-Float.MAX_VALUE,bottom=Float.MAX_VALUE,top=-Float.MAX_VALUE;
            for(var p:hole){left=Math.min(left,p.x);right=Math.max(right,p.x);bottom=Math.min(bottom,p.y);top=Math.max(top,p.y);}
            if(Math.min(points[0].x,Math.min(points[1].x,points[2].x))>right||Math.max(points[0].x,Math.max(points[1].x,points[2].x))<left
                    ||Math.min(points[0].y,Math.min(points[1].y,points[2].y))>top||Math.max(points[0].y,Math.max(points[1].y,points[2].y))<bottom)return false;
            List<Vertex> remaining=new ArrayList<>();for(int i=0;i<3;i++)remaining.add(new Vertex(new Vector3f(points[i]),uv[i*2],uv[i*2+1]));
            // Subtract a convex hole, keeping every exterior strip of the original textured triangle.
            for(int edge=0;edge<hole.size()&&!remaining.isEmpty();edge++){
                Vector3f a=hole.get(edge),b=hole.get((edge+1)%hole.size());
                polygon(pose,buffer,light,overlay,placed(clip(remaining,a,b,false)),255,255,255);
                remaining=clip(remaining,a,b,true);
            }
            return true;
        }
        void render(PoseStack pose,MultiBufferSource buffers,int light){
            if(visible){
                var buffer=buffers.getBuffer(RenderType.entityCutoutNoCull(MCYukkuri.id("textures/entity/"+childId+"/sleep.png")));
                for(var tri:child)polygon(pose,buffer,light,OverlayTexture.NO_OVERLAY,placed(front(List.of(tri),plane)),255,255,255);
            }
            var ink=buffers.getBuffer(RenderType.entityCutoutNoCull(MCYukkuri.id("textures/entity/"+mother.subspecies().assetId()+"/neutral.png")));
            // The closed state is the actual authored component. Opening only cuts and stretches
            // that same component; its existing UVs retain the approved skin and central mark.
            for(var tri:mamu){
                List<Vertex> remaining=new ArrayList<>(List.of(tri));
                if(hole.size()>2)for(int edge=0;edge<hole.size()&&!remaining.isEmpty();edge++){
                    Vector3f a=hole.get(edge),b=hole.get((edge+1)%hole.size());
                    polygon(pose,ink,light,OverlayTexture.NO_OVERLAY,placed(clip(remaining,a,b,false)),255,255,255);
                    remaining=clip(remaining,a,b,true);
                }
                else polygon(pose,ink,light,OverlayTexture.NO_OVERLAY,placed(remaining),255,255,255);
            }
            for(int i=0;i<hole.size();i++){
                Vector3f a=hole.get(i),b=hole.get((i+1)%hole.size());
                var lipA=new Vector3f(a);var lipB=new Vector3f(b);
                lipA.z=frontSurface(mamu,a.x,a.y,plane);lipB.z=frontSurface(mamu,b.x,b.y,plane);
                // The inner wall joins the curved authored mamu to the clipping plane, so there
                // is no independent oval painted over it and no gap around the emerging child.
                var backA=new Vector3f(a).add(0,0,.16f);var backB=new Vector3f(b).add(0,0,.16f);
                polygon(pose,ink,light,OverlayTexture.NO_OVERLAY,placed(List.of(ink(lipA),ink(lipB),ink(backB),ink(backA))),255,255,255);
                var cap=new Vector3f(center).add(0,0,.16f);
                polygon(pose,ink,light,OverlayTexture.NO_OVERLAY,placed(List.of(ink(cap),ink(backB),ink(backA))),255,255,255);
            }
        }
        private Vertex ink(Vector3f point){return new Vertex(point,markU,markV);}
        private float surfaceZ(float x,float y){
            return frontSurface(skin,x,y,plane+.025f)-.005f;
        }
        private static float frontSurface(List<Vertex[]> mesh,float x,float y,float fallback){
            float best=Float.MAX_VALUE;
            for(var tri:mesh){var a=tri[0].point;var b=tri[1].point;var c=tri[2].point;
                float det=(b.x-a.x)*(c.y-a.y)-(b.y-a.y)*(c.x-a.x);if(Math.abs(det)<1e-8)continue;
                float u=((x-a.x)*(c.y-a.y)-(y-a.y)*(c.x-a.x))/det,v=((b.x-a.x)*(y-a.y)-(b.y-a.y)*(x-a.x))/det;
                if(u>=-.001&&v>=-.001&&u+v<=1.001)best=Math.min(best,a.z+(b.z-a.z)*u+(c.z-a.z)*v);
            }
            return best==Float.MAX_VALUE?fallback:best;
        }
    }
    private static Vertex[] triangle(float[] data,int start){
        Vertex[] result=new Vertex[3];for(int i=0;i<3;i++){int p=start+i*8;result[i]=new Vertex(new Vector3f(data[p],data[p+1],data[p+2]),data[p+3],data[p+4]);}return result;
    }
    private static float side(Vector3f a,Vector3f b,Vector3f p){return (b.x-a.x)*(p.y-a.y)-(b.y-a.y)*(p.x-a.x);}
    private static List<Vertex> clip(List<Vertex> input,Vector3f a,Vector3f b,boolean inside){
        var output=new ArrayList<Vertex>();if(input.isEmpty())return output;
        var previous=input.getLast();float pd=side(a,b,previous.point)*(inside?1:-1);
        for(var current:input){float cd=side(a,b,current.point)*(inside?1:-1);if((pd>=0)!=(cd>=0))output.add(previous.mix(current,pd/(pd-cd)));if(cd>=0)output.add(current);previous=current;pd=cd;}return output;
    }
    private static List<Vertex> front(List<Vertex> input,float plane){
        var output=new ArrayList<Vertex>();var previous=input.getLast();float pd=plane-previous.point.z;
        for(var current:input){float cd=plane-current.point.z;if((pd>=0)!=(cd>=0))output.add(previous.mix(current,pd/(pd-cd)));if(cd>=0)output.add(current);previous=current;pd=cd;}return output;
    }
    private static List<Vector3f> hull(List<Vector3f> points){
        if(points.size()<3)return List.of();points.sort(Comparator.<Vector3f>comparingDouble(p->p.x).thenComparingDouble(p->p.y));
        var result=new ArrayList<Vector3f>();for(var p:points){while(result.size()>=2&&side(result.get(result.size()-2),result.getLast(),p)<=0)result.removeLast();result.add(new Vector3f(p));}
        int lower=result.size();for(int i=points.size()-2;i>=0;i--){var p=points.get(i);while(result.size()>lower&&side(result.get(result.size()-2),result.getLast(),p)<=0)result.removeLast();result.add(new Vector3f(p));}
        if(!result.isEmpty())result.removeLast();return result;
    }
    private static void polygon(PoseStack stack,VertexConsumer buffer,int light,int overlay,List<Vertex> polygon,int r,int g,int b){
        for(int i=1;i+1<polygon.size();i++){
            Vertex[] tri={polygon.getFirst(),polygon.get(i),polygon.get(i+1)};
            var normal=new Vector3f(tri[1].point).sub(tri[0].point).cross(new Vector3f(tri[2].point).sub(tri[0].point));if(normal.lengthSquared()<1e-12)continue;normal.normalize();
            for(int k=0;k<4;k++){var v=tri[Math.min(k,2)];buffer.addVertex(stack.last().pose(),v.point.x/16,v.point.y/16,v.point.z/16).setColor(r,g,b,255).setUv(v.u,v.v).setOverlay(overlay).setLight(light).setNormal(stack.last(),normal.x,normal.y,normal.z);}
        }
    }
    private LiveBirthRenderer(){}
}
