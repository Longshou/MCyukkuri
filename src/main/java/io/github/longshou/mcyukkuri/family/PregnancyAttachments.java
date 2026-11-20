package io.github.longshou.mcyukkuri.family;

import com.google.gson.JsonObject;
import io.github.longshou.mcyukkuri.motor.geom.GeometryJson;
import io.github.longshou.mcyukkuri.pose.Skin;
import org.joml.Vector3f;

/** Rigid pregnancy attachment offsets shared by the posed model and physical surface queries. */
public final class PregnancyAttachments {
    static final JsonObject BODY=GeometryJson.read("yukkuri_presentation/body_geometry.json").data().getAsJsonObject("variants");

    private PregnancyAttachments() {}
    static float modelHeight(String id){
        return BODY.has(id)?BODY.getAsJsonObject(id).get("height").getAsFloat():id.startsWith("house_")?3.82f:id.equals("remilia")||id.equals("flandre")?9.6f:8.48f*(id.endsWith("_bean")?1.55f:1);
    }
    public static Vector3f crownLift(String model,float belly,float[] localHatPose) {
        float weight=1;
        if(localHatPose!=null){var v=localHatPose;
            weight=Math.max(0,1-(float)Math.sqrt(v[0]*v[0]+v[1]*v[1]+v[2]*v[2])/3
                    -Math.max(Math.abs(v[3]),Math.max(Math.abs(v[4]),Math.abs(v[5])))/30);}
        float scale=1+Skin.SWELL*belly;
        return new Vector3f(0,modelHeight(model)*(scale-1)*weight,0);
    }
    public static Vector3f braidLift(Vector3f root,float belly,float[] localBraidPose) {
        if(root==null)return new Vector3f();
        float weight=1;
        if(localBraidPose!=null){var v=localBraidPose;
            weight=Math.max(0,1-Math.max(Math.abs(v[3]),Math.max(Math.abs(v[4]),Math.abs(v[5])))/30);}
        var moved=new Vector3f(root);Skin.deformPregnancy(moved,belly,0);
        return moved.sub(root).mul(weight);
    }
}
