package io.github.longshou.mcyukkuri.pose;

import java.util.Arrays;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Bone composition in the original operation order, with caller-owned output and cached local matrices. */
public final class Compose {
    public static final float DEGREES=(float)(Math.PI/180);
    private final Rig.Bone[] bones;
    private final Matrix4f[] local;
    private final float[][] last;
    public Compose(Rig.Bone[] bones){
        this.bones=bones;local=Rig.matrices(bones.length);last=new float[bones.length][9];
        for(var v:last)Arrays.fill(v,Float.NaN);
    }
    public static Matrix4f local(Matrix4f matrix,Rig.Bone b,float[] v){
        return transform(matrix,b.x,b.y,b.z,v,v[3]*DEGREES,v[4]*DEGREES,v[5]*DEGREES);
    }
    private static Matrix4f transform(Matrix4f matrix,float x,float y,float z,float[] v,float rx,float ry,float rz){
        return matrix.translate(x+v[0],y+v[1],z+v[2]).rotateZYX(rz,ry,rx).scale(v[6],v[7],v[8]).translate(-x,-y,-z);
    }
    /** Common-side wound poses historically multiply by float PI before dividing; retain that rounding. */
    public static Matrix4f woundLocal(Matrix4f matrix,float[] pivot,float[] v){
        return transform(matrix,pivot[0],pivot[1],pivot[2],v,v[3]*(float)Math.PI/180,v[4]*(float)Math.PI/180,v[5]*(float)Math.PI/180);
    }
    public static Matrix4f unrotated(Matrix4f matrix,Rig.Bone b,float[] v){return transform(matrix,b.x,b.y,b.z,v,0,0,0);}
    public void world(float[][] pose,Matrix4f[] world,int hat,float hatScale,Vector3f anchor,Matrix4f hatPose){
        for(int i=0;i<bones.length;i++){
            var bone=bones[i];var v=pose[i];var matrix=world[i];
            if(!Arrays.equals(v,last[i])){local(local[i].identity(),bone,v);System.arraycopy(v,0,last[i],0,9);}
            if(bone.parent<0)matrix.set(local[i]);else matrix.set(world[bone.parent]).mul(local[i]);
            if(hatScale!=1&&i==hat)matrix.translate(anchor).scale(hatScale).translate(-anchor.x,-anchor.y,-anchor.z);
            if(i==hat&&hatPose!=null)matrix.set(hatPose);
        }
    }
    public static void raise(Matrix4f[] world,float lift){for(var matrix:world)matrix.m31(matrix.m31()+lift);}
    public static void feature(Rig.Bone[] bones,Matrix4f[] world,int bone,Matrix4f matrix,Matrix4f inverse){
        matrix.set(world[bone]);if(bones[bone].parent>=0)matrix.mul(inverse.set(world[bones[bone].parent]).invert());
    }
}
