package io.github.longshou.mcyukkuri.family;

import org.joml.Vector3f;

/** Rigid pregnancy attachment offsets shared by the posed model and physical surface queries. */
public final class PregnancyAttachments {
    private PregnancyAttachments() {}
    public static Vector3f crownLift(String model,float belly,float[] localHatPose) {
        float weight=1;
        if(localHatPose!=null){var v=localHatPose;
            weight=Math.max(0,1-(float)Math.sqrt(v[0]*v[0]+v[1]*v[1]+v[2]*v[2])/3
                    -Math.max(Math.abs(v[3]),Math.max(Math.abs(v[4]),Math.abs(v[5])))/30);}
        float scale=1+LiveBirthGeometry.SWELL*belly;
        return new Vector3f(0,LiveBirthGeometry.modelHeight(model)*(scale-1)*weight,0);
    }
    public static Vector3f braidLift(Vector3f root,float belly,float[] localBraidPose) {
        if(root==null)return new Vector3f();
        float weight=1;
        if(localBraidPose!=null){var v=localBraidPose;
            weight=Math.max(0,1-Math.max(Math.abs(v[3]),Math.max(Math.abs(v[4]),Math.abs(v[5])))/30);}
        var moved=new Vector3f(root);LiveBirthGeometry.deform(moved,belly,0);
        return moved.sub(root).mul(weight);
    }
}
