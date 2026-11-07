package io.github.longshou.mcyukkuri.pose;

import io.github.longshou.mcyukkuri.motor.geom.BraidChain;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Presentation geometry only. Callers choose when each overlay applies. */
public final class Overlays {
    private Overlays() {}
    public static Matrix4f presentation(float[] value){
        return new Matrix4f().rotateZYX(value[2]*Compose.DEGREES,value[1]*Compose.DEGREES,value[0]*Compose.DEGREES)
                .scale(value[3],value[4],value[5]);
    }
    public interface Contact {
        void compose();
        float minimumY();
        Vector3f mouth();
    }
    public static void care(Contact contact,float[][] pose,Matrix4f[] world,int root,boolean feeding,
                            float careTargetMouthX,float careTargetMouthY,float careTargetMouthZ,float careBlend){
            float floor=contact.minimumY();float[] value=pose[root];
            float maximumPitch=feeding?10:12;
            value[3]=Math.max(-maximumPitch,value[3]);contact.compose();
            float basePitch=value[3],baseMouth=contact.mouth().y;
            float target=baseMouth+(careTargetMouthY+(feeding?0:.035f*16)-baseMouth)*careBlend;
            float low=0,high=Math.max(0,maximumPitch+basePitch)*careBlend;
            if(baseMouth>target)for(int i=0;i<7;i++){
                float pitch=(low+high)*.5f;value[3]=basePitch-pitch;contact.compose();
                float lifted=Math.max(0,floor-contact.minimumY());
                if(contact.mouth().y+lifted>target)low=pitch;else high=pitch;
            }
            value[3]=basePitch-(baseMouth>target?high:0);contact.compose();
            // Pitch changes the mouth's depth. Align the composed anchor, rather than estimating
            // reach from the upright face mesh; keep the extra lean small and tied to real contact.
            var mouth=contact.mouth();
            float lateral=feeding?.02f:.04f,forward=feeding?.03f:.18f;
            value[0]+=Math.clamp((careTargetMouthX-mouth.x)*careBlend,-lateral*16,lateral*16);
            value[2]+=Math.clamp((careTargetMouthZ+(feeding?0:.07f*16)-mouth.z)*careBlend,-forward*16,forward*16);
            contact.compose();float lowered=contact.minimumY();
            if(lowered<floor)Compose.raise(world,floor-lowered);
    }
    /** Existing full pickup / put-down bow, degrees of forward pitch about the root (within the 12 degree body budget). */
    public static final float CARRY_LEAN_DEGREES=9;
    /** Walking under a load keeps this share less of the authored sway (project design). */
    public static final float CARRY_GAIT_DAMP=.3f;
    public static void carryLean(Contact contact,float[][] pose,Matrix4f[] world,int root,float lean){
        float floor=contact.minimumY();pose[root][3]-=lean*CARRY_LEAN_DEGREES;contact.compose();
        float lowered=contact.minimumY();if(lowered<floor)Compose.raise(world,floor-lowered);
    }
    public static void gait(float[][] pose,float[][] defaults,float load){
        float keep=1-CARRY_GAIT_DAMP*load;
        for(int i=0;i<pose.length;i++)for(int k=0;k<9;k++)pose[i][k]=defaults[i][k]+(pose[i][k]-defaults[i][k])*keep;
    }
    public static void cheeks(float[] root,float amount){float wide=1+.2f*amount,tall=1+.14f*amount;root[6]*=wide;root[7]*=tall;root[8]*=wide;}
    /** Boarding pitch and roll share one body tilt budget (degrees, the neckless-ball rule): the lean keeps its direction, not its size. */
    public static final float BOARDING_TILT=12;
    public static void boarding(float[] root,float pitch,float roll,float squash,float yaw){
        root[4]=Math.clamp(root[4],-8,8)*yaw;
        float lean=(float)Math.hypot(pitch,roll),keep=lean>BOARDING_TILT?BOARDING_TILT/lean:1;
        root[0]=root[1]=root[2]=0;root[3]=pitch*keep;root[5]=roll*keep;
        float wide=1+(1-squash)*.5f;root[6]=wide;root[7]=squash;root[8]=wide;
    }
    public static void hat(float[] root,float phase,boolean returning,float lift,float depth){
        float q=Math.clamp(phase,0,1);if(returning)q=1-q;
        float rise=Math.min(1,q/.55f);rise=rise*rise*(3-2*rise);
        float forward=Math.max(0,(q-.4f)/.6f);forward=forward*forward*(3-2*forward);
        root[1]+=lift*rise;root[2]+=depth*forward;
    }
    /** Per-rig scratch: the extracted call boundary must not add escaping per-frame quaternions. */
    public static final class BraidScratch {
        private final Quaternionf turn=new Quaternionf(),full=new Quaternionf(),above=new Quaternionf();
    }
    public static void braid(Rig.Bone[] bones,Matrix4f[] world,float[][] pose,int[] chain,float[] rest,Vector3f tip,float weight,BraidScratch scratch){
        int n=chain.length;
        double[] joints=new double[3*n+3];var p=new Vector3f();
        for(int i=0;i<=n;i++){
            world[chain[Math.min(i,n-1)]].transformPosition(p.set(rest[3*i],rest[3*i+1],rest[3*i+2]));
            joints[3*i]=p.x;joints[3*i+1]=p.y;joints[3*i+2]=p.z;
        }
        double[] solved=BraidChain.reach(joints,tip.x,tip.y,tip.z);
        var at=new Vector3f((float)joints[0],(float)joints[1],(float)joints[2]);Vector3f from=new Vector3f(),to=new Vector3f();
        // Each segment swings from where the one above carried it, so the lobes never twist against each other.
        Quaternionf turn=scratch.turn.identity(),full=scratch.full.identity(),above=scratch.above.identity();var dir=new Vector3f();
        for(int i=0;i<n;i++){
            from.set((float)(joints[3*i+3]-joints[3*i]),(float)(joints[3*i+4]-joints[3*i+1]),(float)(joints[3*i+5]-joints[3*i+2]));
            to.set((float)(solved[3*i+3]-solved[3*i]),(float)(solved[3*i+4]-solved[3*i+1]),(float)(solved[3*i+5]-solved[3*i+2]));
            above.transform(dir.set(from));
            if(dir.lengthSquared()>1e-10f&&to.lengthSquared()>1e-10f)above.set(full.rotationTo(dir.x,dir.y,dir.z,to.x,to.y,to.z).mul(above));
            turn.identity().slerp(above,weight);
            world[chain[i]].set(new Matrix4f().translation(at).rotate(turn).translate((float)-joints[3*i],(float)-joints[3*i+1],(float)-joints[3*i+2])
                    .mul(world[chain[i]]));
            at.add(turn.transform(from));
        }
        // Any other bone under the chain (none on the authored rigs) follows its re-posed parent.
        for(int i=0;i<bones.length;i++){
            var bone=bones[i];if(bone.parent()<0||!Rig.descends(bones,i,chain[0]))continue;
            boolean inChain=false;for(int c:chain)inChain|=c==i;if(inChain)continue;
            float[] v=pose[i];
            Compose.local(world[i].set(world[bone.parent()]),bone,v);
        }
    }
    public static void holdHat(Rig.Bone[] bones,Matrix4f[] world,int[] chain,float[] rest,int hat,float share,Vector3f grip){
        int n=chain.length;
        var move=new Matrix4f().translation(world[chain[n-1]].transformPosition(new Vector3f(rest[3*n],rest[3*n+1],rest[3*n+2]))
                .sub(world[hat].transformPosition(new Vector3f(grip))).mul(share));
        for(int i=0;i<bones.length;i++)if(Rig.descends(bones,i,hat))world[i].set(new Matrix4f(move).mul(world[i]));
    }
    public static Matrix4f item(Rig.Bone[] bones,Matrix4f[] world,Clips.Track prop,Clips.Track grip,float time,float hold){
        var bone=bones[prop.bone];float[] v=new float[6];prop.sample(time,v);
        var turn=new Quaternionf().rotationZYX(v[5]*Compose.DEGREES,v[4]*Compose.DEGREES,v[3]*Compose.DEGREES);
        if(grip!=null&&grip.bone==prop.bone){
            float[] h=new float[6];grip.sample(0,h);
            for(int k=0;k<3;k++)v[k]+=(h[k]-v[k])*hold;
            turn.slerp(new Quaternionf().rotationZYX(h[5]*Compose.DEGREES,h[4]*Compose.DEGREES,h[3]*Compose.DEGREES),hold);
        }
        return new Matrix4f().scaling(1/16f).mul(world[prop.bone])
                .translate(bone.x+v[0],bone.y+v[1],bone.z+v[2]).rotate(turn).scale(16);
    }
}
