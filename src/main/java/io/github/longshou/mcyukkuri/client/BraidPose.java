package io.github.longshou.mcyukkuri.client;

import static io.github.longshou.mcyukkuri.client.YukkuriModel.DEGREES;
import io.github.longshou.mcyukkuri.motor.geom.BraidChain;
import java.util.ArrayList;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * A Marisa's braid bent along its whole chain (hand_right, hand_right_braid_1.., the tail hand_right_tip) to one point
 * per render (a Grab's reach and hold, ParentCarryPresentation), over the clip's own braid, and the hat it carries.
 * The solve is {@link BraidChain}: every segment keeps its posed length, so the braid never stretches or loses its tip.
 */
final class BraidPose {
    private final YukkuriModel o;
    /** Chain bones root to tail (empty without a braid) and their rest joints, then the rest tuft end, in model units. */
    private int[] chain;
    private float[] rest;
    private final Vector3f tip=new Vector3f();
    /** How much of the reach replaces the clip's braid (0 none, 1 all of it). */
    private float weight;
    BraidPose(YukkuriModel owner){o=owner;}
    boolean active(){return weight>0;}
    /** Bend the braid so its tip lies at {@code point} (model units) with this share of the clip's braid; null lets it go. */
    void reach(Vector3f point,float share){weight=point==null?0:Math.clamp(share,0,1);if(point!=null)tip.set(point);}

    private int bone(String name){var bones=o.bones();for(int i=0;i<bones.length;i++)if(bones[i].name().equals(name))return i;return -1;}

    /** Finds the chain once: its joints are the bone pivots, its end the tuft vertex farthest from the root (the contact point). */
    private boolean resolve(){
        if(chain!=null)return chain.length>0;
        var found=new ArrayList<Integer>();found.add(bone("hand_right"));
        for(int k=1,b;(b=bone("hand_right_braid_"+k))>=0;k++)found.add(b);
        found.add(bone("hand_right_tip"));
        var bones=o.bones();var vertices=o.restVertices();float best=-1,ex=0,ey=0,ez=0;
        var root=bones[Math.max(0,found.get(0))];
        for(var part:o.parts())if(part.name().startsWith("braid_lock_tuft"))for(int v=part.start();v<part.start()+part.count();v++){
            float x=vertices[v*8]-root.x(),y=vertices[v*8+1]-root.y(),z=vertices[v*8+2]-root.z(),d=x*x+y*y+z*z;
            if(d>best){best=d;ex=vertices[v*8];ey=vertices[v*8+1];ez=vertices[v*8+2];}
        }
        if(found.size()<3||found.contains(-1)||best<0){chain=new int[0];return false;}
        chain=found.stream().mapToInt(Integer::intValue).toArray();rest=new float[3*chain.length+3];
        for(int i=0;i<chain.length;i++){var b=bones[chain[i]];rest[3*i]=b.x();rest[3*i+1]=b.y();rest[3*i+2]=b.z();}
        rest[3*chain.length]=ex;rest[3*chain.length+1]=ey;rest[3*chain.length+2]=ez;
        return true;
    }

    /** Carry the hat (bone {@code hat} and every bone under it) so its braid grip lies on the braid tip, by this share. */
    void holdHat(int hat,float share,Vector3f grip){
        if(hat<0||share<=0||!resolve())return;
        var bones=o.bones();var world=o.world();int n=chain.length;
        var move=new Matrix4f().translation(world[chain[n-1]].transformPosition(new Vector3f(rest[3*n],rest[3*n+1],rest[3*n+2]))
                .sub(world[hat].transformPosition(new Vector3f(grip))).mul(share));
        for(int i=0;i<bones.length;i++)if(o.descends(i,hat))world[i].set(new Matrix4f(move).mul(world[i]));
    }

    /** Bend the posed chain toward the tip point: each segment turns about its posed joint, shared out by the weight. */
    void pose(){
        if(!resolve())return;
        var bones=o.bones();var world=o.world();var pose=o.pose();int n=chain.length;
        double[] joints=new double[3*n+3];var p=new Vector3f();
        for(int i=0;i<=n;i++){
            world[chain[Math.min(i,n-1)]].transformPosition(p.set(rest[3*i],rest[3*i+1],rest[3*i+2]));
            joints[3*i]=p.x;joints[3*i+1]=p.y;joints[3*i+2]=p.z;
        }
        double[] solved=BraidChain.reach(joints,tip.x,tip.y,tip.z);
        var at=new Vector3f((float)joints[0],(float)joints[1],(float)joints[2]);Vector3f from=new Vector3f(),to=new Vector3f();
        // Each segment swings from where the one above carried it, so the lobes never twist against each other.
        Quaternionf turn=new Quaternionf(),full=new Quaternionf(),above=new Quaternionf();var dir=new Vector3f();
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
            var bone=bones[i];if(bone.parent()<0||!o.descends(i,chain[0]))continue;
            boolean inChain=false;for(int c:chain)inChain|=c==i;if(inChain)continue;
            float[] v=pose[i];
            world[i].set(world[bone.parent()]).translate(bone.x()+v[0],bone.y()+v[1],bone.z()+v[2]).rotateZYX(v[5]*DEGREES,v[4]*DEGREES,v[3]*DEGREES)
                    .scale(v[6],v[7],v[8]).translate(-bone.x(),-bone.y(),-bone.z());
        }
    }
}
