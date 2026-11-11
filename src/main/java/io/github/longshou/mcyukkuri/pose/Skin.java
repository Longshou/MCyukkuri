package io.github.longshou.mcyukkuri.pose;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Skin math with explicit deformation inputs. Caller owns caches and scratch vectors. */
public final class Skin {
    private Skin() {}
    public record Binding(int first,int second,float weight) {}
    public record Patch(int start,float[] values) {}
    @FunctionalInterface public interface Deform { void deform(int index,Vector3f point); }
    public static void expression(float[] rest,float[] vertices,List<Patch> previous,List<Patch> next){
        for(var patch:previous)System.arraycopy(rest,patch.start,vertices,patch.start,patch.values.length);
        for(var patch:next)System.arraycopy(patch.values,0,vertices,patch.start,patch.values.length);
    }
    public static void base(int index,Vector3f target,Vector3f second,float[] vertices,Binding[] bindings,
                            Matrix4f[] world,Deform wound,float puffAmount,float[] puffOffsets,
                            int[] featureBones,Map<Integer,Matrix4f> featureTransforms){
        int offset = index * 8;
        Binding binding = bindings[index];
        target.set(vertices[offset], vertices[offset + 1], vertices[offset + 2]);
        if(wound!=null)wound.deform(index,target);
        if(puffAmount>0)target.add(puffAmount*puffOffsets[index*3],puffAmount*puffOffsets[index*3+1],puffAmount*puffOffsets[index*3+2]);
        if (binding.weight > 0) {
            second.set(target);
            world[binding.second].transformPosition(second);
        }
        world[binding.first].transformPosition(target);
        if (binding.weight > 0) target.lerp(second, binding.weight);
        int feature=featureBones[index];if(feature>=0)featureTransforms.get(feature).transformPosition(target);
    }
    public static void blend(Vector3f point,Vector3f second,Binding binding,Matrix4f[] world){
        second.set(point);world[binding.first].transformPosition(point);
        if(binding.weight>0)point.lerp(world[binding.second].transformPosition(second),binding.weight);
    }
    public static Vector3f weighted(float[] weights,Vector3f point,Matrix4f[] world,Vector3f out,Vector3f temp){
        out.zero();for(int k=0;k<weights.length;k+=2){world[(int)weights[k]].transformPosition(point,temp);out.fma(weights[k+1],temp);}return out;
    }
    public static void fragment(int triangle,float a,float b,float c,Vector3f rest,Vector3f target,Vector3f second,
                                Binding[] bindings,Matrix4f[] world,int[] featureBones,Map<Integer,Matrix4f> featureTransforms){
        target.zero();
        for(int corner=0;corner<3;corner++){
            int index=triangle+corner;Binding binding=bindings[index];float bary=corner==0?a:corner==1?b:c;
            second.set(rest);world[binding.first].transformPosition(second);target.fma(bary*(1-binding.weight),second);
            if(binding.weight>0){second.set(rest);world[binding.second].transformPosition(second);target.fma(bary*binding.weight,second);}
        }
        int feature=featureBones[triangle];if(feature>=0)featureTransforms.get(feature).transformPosition(target);
    }
    public static void pregnancy(Vector3f target,float belly,float contraction,int group,Vector3f crownLift,Vector3f braidLift){
        if(belly>0){if(group==1)target.add(crownLift);else if(group==2)target.add(braidLift);else deformPregnancy(target,belly,contraction);}
    }
    // Existing live-birth body shape; shared with the game-side geometric queries.
    public static final float SWELL=.26f, LOW_WIDE=.16f, LOW_DEEP=.06f, LOW_FRONT=.28f;
    public static void deformPregnancy(Vector3f point,float belly,float contraction){
        if(belly<=0)return;
        float s=1+SWELL*belly,low=Clips.smooth(point.y/1.5f)*(1-Clips.smooth((point.y-1.5f)/4.5f)),front=point.z<0?Clips.smooth(-point.z/4):0;
        float squeeze=1+.035f*contraction;
        point.set(point.x*s*(1+LOW_WIDE*belly*low)*squeeze,point.y*s*(1-.04f*contraction),point.z*s*(1+(LOW_DEEP+LOW_FRONT*front)*belly*low)*squeeze);
    }
    /**
     * Swaddle retention swelling (project design, art decision swaddle-neglect): the bottom and the rear of the body round out under the
     * hard leaf. RETAIN_OUT is the outward growth at full swelling, most at the back and none in front of RETAIN_FRONT (the share of the
     * body depth from the face), so the face plane stays put; it reaches up to RETAIN_TOP_SIDE of the body height at the sides and
     * RETAIN_TOP_BACK at the back (under the hanging back hair, which moves with it), fullest at RETAIN_PEAK and rounding in to RETAIN_FOOT
     * of that at the ground contact, so the bottom bulges like a full sack rather than flaring. The sack grows backwards; sideways only
     * RETAIN_SIDE of that, so seen from behind it fills out under the leaf instead of spreading. Heights never change.
     */
    public static final float RETAIN_OUT=.5f, RETAIN_SIDE=.5f, RETAIN_FRONT=.3f, RETAIN_PEAK=.22f, RETAIN_FOOT=.6f, RETAIN_TOP_SIDE=.32f,
            RETAIN_TOP_BACK=.58f;
    public record RetentionFrame(float front,float back,float height,float halfWidth,float[] rings) {}

    /** Hair over the body follows the straight skin triangles between the authored body rings. */
    public static void deformRetention(Vector3f point,float swell,RetentionFrame body,boolean overSkin){
        if(swell<=0)return;
        float front=body.front,back=body.back,mid=(front+back)/2,rear=Clips.smooth(((point.z-front)/(back-front)-RETAIN_FRONT)/(1-RETAIN_FRONT));
        float u=point.y/body.height,top=RETAIN_TOP_SIDE+(RETAIN_TOP_BACK-RETAIN_TOP_SIDE)*rear;
        float shape=retentionShape(u,top);
        if(overSkin&&u>RETAIN_PEAK&&u<1){
            int ring=Arrays.binarySearch(body.rings,point.y);
            if(ring<-1){
                int upper=-ring-1;
                float low=body.rings[upper-1],high=body.rings[upper];
                float between=(point.y-low)/(high-low);
                float lowerShape=retentionShape(low/body.height,top),upperShape=retentionShape(high/body.height,top);
                shape=Math.max(shape,lowerShape+(upperShape-lowerShape)*between);
            }
        }
        float dx=point.x,dz=point.z-mid,within=Math.min(1,Math.min(body.halfWidth/Math.max(1e-4f,Math.abs(dx)),(back-mid)/Math.max(1e-4f,Math.abs(dz))));
        float s=RETAIN_OUT*swell*rear*shape*within;
        point.x+=dx*s*RETAIN_SIDE;point.z+=dz*s;
    }

    private static float retentionShape(float height,float top){
        return height<RETAIN_PEAK?RETAIN_FOOT+(1-RETAIN_FOOT)*Clips.smooth(height/RETAIN_PEAK)
                :1-Clips.smooth((height-RETAIN_PEAK)/(top-RETAIN_PEAK));
    }

    /** Rest bounds and ring heights from the body surface vertices [start, start + count) of each part. */
    public static RetentionFrame retentionFrame(float[] vertices,int[] ranges){
        float front=Float.MAX_VALUE,back=-Float.MAX_VALUE,height=0,halfWidth=0;
        var heights=new TreeSet<Float>();
        for(int r=0;r<ranges.length;r+=2)for(int i=ranges[r];i<ranges[r]+ranges[r+1];i++){
            front=Math.min(front,vertices[i*8+2]);back=Math.max(back,vertices[i*8+2]);height=Math.max(height,vertices[i*8+1]);
            halfWidth=Math.max(halfWidth,Math.abs(vertices[i*8]));heights.add(vertices[i*8+1]);
        }
        float[] rings=new float[heights.size()];int i=0;
        for(float y:heights)rings[i++]=y;
        return new RetentionFrame(front,back,height,halfWidth,rings);
    }
}
