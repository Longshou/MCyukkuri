package io.github.longshou.mcyukkuri.client;

import io.github.longshou.mcyukkuri.pose.Overlays;
import io.github.longshou.mcyukkuri.motor.geom.BraidChain;
import java.util.ArrayList;
import org.joml.Vector3f;

/**
 * A Marisa's braid bent along its whole chain (hand_right, hand_right_braid_1.., the tail hand_right_tip) to one point
 * per render (a Grab's reach and hold, ParentCarryPresentation), over the clip's own braid, and the hat it carries.
 * The solve is {@link BraidChain}: every segment keeps its posed length, so the braid never stretches or loses its tip.
 */
final class BraidPose {
    private final YukkuriModel o;
    private final Overlays.BraidScratch scratch=new Overlays.BraidScratch();
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
        Overlays.holdHat(o.bones(),o.world(),chain,rest,hat,share,grip);
    }

    /** BraidChain constrains the bend toward model forward/down; each posed segment turns about its joint, weighted over the clip. */
    void pose(){
        if(!resolve())return;
        Overlays.braid(o.bones(),o.world(),o.pose(),chain,rest,tip,weight,scratch);
    }
}
