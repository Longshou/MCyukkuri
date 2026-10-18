package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.longshou.mcyukkuri.client.YukkuriModel.Part;
import io.github.longshou.mcyukkuri.headwear.Headwear;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.renderer.MultiBufferSource;
import org.joml.Vector3f;

/** Per-model headwear masks, face cells and drawing buffers; triangle scratch belongs to the model. */
final class HatDamageDraw {
    private final YukkuriModel o;
    HatDamageDraw(YukkuriModel owner){o=owner;}
    int level(){return hatDamage;}
    float fit(){return hatFit;}
    Vector3f seat(){return hatSeat;}
    void visual(int headwear){hatDamage=Headwear.visualLevel(headwear);hatFit=Headwear.visualFit(headwear);}
    void sampler(HatDamageFaces.Sampler sampler){hatSampler=sampler;}
    void deferCaps(){hatCaps.clear();deferHatCaps=true;}
    void drawCaps(MultiBufferSource buffers){hatCaps.draw(buffers);}
    void reset(){hatDamage=0;hatFit=1;deferHatCaps=false;hatCaps.clear();}

    /** Accessory damage drawn this frame (0 whole, 1 frayed, 2 torn, 3 pieced back together) and its size relative to
     *  the body (headwear.Headwear.visual).  Damage keeps the whole mesh: square face cells are omitted and filled
     *  with a local-colour inner cap (client/HatDamageFaces). */
    private int hatDamage;
    private float hatFit=1;
    private int hatMaskGeneration=-1;
    private float[] hatBounds;
    private int[] hatSeatVertices;
    private final Map<Integer,HatDamageFaces.Face[]> hatFaces=new HashMap<>();
    private int hatFacesLevel=-1;
    private HatDamageFaces.Sampler hatSampler;
    /** Living caps wait until the body and wounds finish; other render paths retain the body-buffer fallback. */
    private final HatCapQueue hatCaps=new HatCapQueue();
    private boolean deferHatCaps;
    private final Vector3f hatSeat=new Vector3f();
    private final Vector3f[] faceCorners={new Vector3f(),new Vector3f(),new Vector3f(),new Vector3f()};
    private final Vector3f[] cellCorners={new Vector3f(),new Vector3f(),new Vector3f(),new Vector3f()};
    private final Vector3f faceNormal=new Vector3f(),cellTemp=new Vector3f();
    private final float[] faceUv=new float[8];
    private final float[] cellUv=new float[8];
    private void refreshHatMasks(){
        var parts=o.parts();var restVertices=o.restVertices();
        if(hatMaskGeneration==HatDamageMask.generation()&&hatBounds!=null)return;
        hatMaskGeneration=HatDamageMask.generation();hatFaces.clear();hatFacesLevel=-1;
        var ranges=new ArrayList<Integer>();
        for(var p:parts)if(o.accessoryPart(p)){ranges.add(p.start());ranges.add(p.count());}
        int[] r=ranges.stream().mapToInt(Integer::intValue).toArray();
        hatBounds=HatDamageMask.bounds(restVertices,r);
        // The seat: the lowest accessory vertices, about which a small (not yet regrown) hat is drawn.
        float low=hatBounds[1]+(hatBounds[4]-hatBounds[1])*.15f;
        var seat=new ArrayList<Integer>();
        for(int i=0;i<r.length;i+=2)for(int v=r[i];v<r[i]+r[i+1];v++)if(restVertices[v*8+1]<=low)seat.add(v);
        hatSeatVertices=seat.stream().mapToInt(Integer::intValue).toArray();
    }
    HatDamageFaces.Face[] hatFaces(int partIndex){
        var parts=o.parts();var restVertices=o.restVertices();
        refreshHatMasks();
        if(hatFacesLevel!=hatDamage){hatFaces.clear();hatFacesLevel=hatDamage;}
        return hatFaces.computeIfAbsent(partIndex,i->{var p=parts.get(i);return HatDamageFaces.build(restVertices,p.start(),p.count(),p.name(),hatBounds,hatDamage,hatSampler);});
    }
    void poseHatSeat(){
        refreshHatMasks();hatSeat.zero();if(hatSeatVertices.length==0)return;
        var point=new Vector3f();
        for(int v:hatSeatVertices){o.skin(v,point);hatSeat.add(point);}
        hatSeat.div(hatSeatVertices.length);
    }

    /** Draw the square face cells and close-colour inner caps of one accessory part. */
    void renderHatFaces(VertexConsumer buffer,PoseStack.Pose transform,Part part,HatDamageFaces.Face[] faces,int light,int overlay){
        // These aliases are the owner's exact renderPosed scratch buffers, never copies.
        var triangle=o.triangle();var normal=o.normal();var edge=o.edge();var vertices=o.vertices();
        for(var face:faces){
            if(face.original()){
                for(int first=face.originalStart();first<face.originalStart()+face.originalCount();first+=3){
                    for(int corner=0;corner<3;corner++)o.skin(first+corner,triangle[corner]);
                    if(hatFit<.999f)for(int corner=0;corner<3;corner++)triangle[corner].sub(hatSeat).mul(hatFit).add(hatSeat);
                    normal.set(triangle[1]).sub(triangle[0]);edge.set(triangle[2]).sub(triangle[0]);normal.cross(edge);
                    if(normal.lengthSquared()<1.0e-16f)continue;
                    normal.normalize();
                    for(int corner=0;corner<4;corner++){
                        int index=Math.min(corner,2),uv=(first+index)*8+3;
                        buffer.addVertex(transform.pose(),triangle[index].x/16,triangle[index].y/16,triangle[index].z/16)
                                .setColor(255,255,255,255).setUv(vertices[uv],vertices[uv+1])
                                .setOverlay(overlay).setLight(light).setNormal(transform,normal.x,normal.y,normal.z);
                    }
                }
                continue;
            }
            int[] corners=face.corners();
            for(int k=0;k<4;k++){
                o.skin(corners[k],faceCorners[k]);
                if(hatFit<.999f)faceCorners[k].sub(hatSeat).mul(hatFit).add(hatSeat);
                faceUv[k*2]=vertices[corners[k]*8+3];faceUv[k*2+1]=vertices[corners[k]*8+4];
            }
            int n=face.n(),m=face.m();
            for(int i=0;i<n;i++)for(int j=0;j<m;j++){
                int cell=i*m+j;byte code=face.codes()[cell];
                float u0=i/(float)n,u1=(i+1f)/n,v0=j/(float)m,v1=(j+1f)/m;
                bilerp(cellCorners[0],faceCorners[0],faceCorners[1],faceCorners[2],faceCorners[3],u0,v0);
                bilerp(cellCorners[1],faceCorners[0],faceCorners[1],faceCorners[2],faceCorners[3],u1,v0);
                bilerp(cellCorners[2],faceCorners[0],faceCorners[1],faceCorners[2],faceCorners[3],u1,v1);
                bilerp(cellCorners[3],faceCorners[0],faceCorners[1],faceCorners[2],faceCorners[3],u0,v1);
                cellUv[0]=bilerp(faceUv[0],faceUv[2],faceUv[4],faceUv[6],u0,v0);
                cellUv[1]=bilerp(faceUv[1],faceUv[3],faceUv[5],faceUv[7],u0,v0);
                cellUv[2]=bilerp(faceUv[0],faceUv[2],faceUv[4],faceUv[6],u1,v0);
                cellUv[3]=bilerp(faceUv[1],faceUv[3],faceUv[5],faceUv[7],u1,v0);
                cellUv[4]=bilerp(faceUv[0],faceUv[2],faceUv[4],faceUv[6],u1,v1);
                cellUv[5]=bilerp(faceUv[1],faceUv[3],faceUv[5],faceUv[7],u1,v1);
                cellUv[6]=bilerp(faceUv[0],faceUv[2],faceUv[4],faceUv[6],u0,v1);
                cellUv[7]=bilerp(faceUv[1],faceUv[3],faceUv[5],faceUv[7],u0,v1);
                faceNormal.set(cellCorners[1]).sub(cellCorners[0]);
                cellTemp.set(cellCorners[3]).sub(cellCorners[0]);
                faceNormal.cross(cellTemp);
                if(faceNormal.lengthSquared()<1.0e-16f)continue;
                faceNormal.normalize();
                if(face.flip())faceNormal.negate();
                if(code==HatDamageMask.HIDDEN){
                    for(int k=0;k<4;k++)cellCorners[k].sub(faceNormal.x*.16f,faceNormal.y*.16f,faceNormal.z*.16f);
                    emitCap(buffer,transform,cellCorners,face.cap()[cell],light,overlay,faceNormal);
                }else if(code==HatDamageMask.EDGE){
                    emitCap(buffer,transform,cellCorners,face.rim()[cell],light,overlay,faceNormal);
                }else{
                    emitCell(buffer,transform,cellCorners,cellUv,0xFFFFFF,light,overlay,faceNormal);
                }
            }
        }
    }
    private static final float[] WHITE_UV={.5f,.5f,.5f,.5f,.5f,.5f,.5f,.5f};

    private static float bilerp(float a,float b,float c,float d,float u,float v){
        return a+(b-a)*u+(d-a)*v+(a-b+c-d)*u*v;
    }

    private static void bilerp(Vector3f out,Vector3f a,Vector3f b,Vector3f c,Vector3f d,float u,float v){
        out.set(bilerp(a.x,b.x,c.x,d.x,u,v),bilerp(a.y,b.y,c.y,d.y,u,v),bilerp(a.z,b.z,c.z,d.z,u,v));
    }

    private void emitCap(VertexConsumer buffer,PoseStack.Pose transform,Vector3f[] points,int color,int light,int overlay,Vector3f normal){
        if(deferHatCaps)hatCaps.add(transform,points,color,light,overlay,normal);
        else emitCell(buffer,transform,points,WHITE_UV,color,light,overlay,normal);
    }

    private void emitCell(VertexConsumer buffer,PoseStack.Pose transform,Vector3f[] points,float[] uv,int color,int light,int overlay,Vector3f normal){
        emitTriangle(buffer,transform,points[0],points[1],points[2],uv,0,1,2,color,light,overlay,normal);
        emitTriangle(buffer,transform,points[0],points[2],points[3],uv,0,2,3,color,light,overlay,normal);
    }

    private void emitTriangle(VertexConsumer buffer,PoseStack.Pose transform,Vector3f a,Vector3f b,Vector3f c,float[] uv,
                              int ia,int ib,int ic,int color,int light,int overlay,Vector3f normal){
        int r=color>>16&255,g=color>>8&255,bl=color&255;
        Vector3f[] points={a,b,c};
        int[] indices={ia,ib,ic};
        for(int corner=0;corner<4;corner++){
            int k=Math.min(corner,2);Vector3f point=points[k];int u=indices[k]*2;
            buffer.addVertex(transform.pose(),point.x/16,point.y/16,point.z/16).setColor(r,g,bl,255)
                    .setUv(uv[u],uv[u+1]).setOverlay(overlay).setLight(light)
                    .setNormal(transform,normal.x,normal.y,normal.z);
        }
    }
}
