package io.github.longshou.mcyukkuri.pose.cli;

import com.google.gson.*;
import io.github.longshou.mcyukkuri.pose.*;
import io.github.longshou.mcyukkuri.pose.check.PoseChecks;
import io.github.longshou.mcyukkuri.motor.geom.BraidChain;
import java.util.*;
import org.joml.*;

/** Unsaved authoring inputs use the same composition as packaged models. Matrices here are row-major. */
final class Authoring {
    private Authoring() {}
    static JsonArray rows(Matrix4f m) {
        var out=new JsonArray();
        for(int r=0;r<3;r++){var row=new JsonArray();for(int c=0;c<4;c++)row.add((double)m.get(c,r));out.add(row);}
        return out;
    }
    static JsonObject run(JsonObject request) {
        var out=new JsonObject();String op=request.get("op").getAsString();
        if(op.equals("arc")) {
            var raw=request.getAsJsonArray("joints");double[] joints=new double[raw.size()];
            for(int i=0;i<joints.length;i++)joints[i]=raw.get(i).getAsDouble();
            var target=request.getAsJsonArray("target");
            double[] solved=BraidChain.reach(joints,target.get(0).getAsDouble(),target.get(1).getAsDouble(),target.get(2).getAsDouble());
            var values=new JsonArray();for(double x:solved)values.add(x);out.add("joints",values);return out;
        }
        if(!op.equals("compose"))throw new IllegalArgumentException("Unknown authoring operation: "+op);
        var bones=Rig.bones(PoseChecks.need(request,"bones").getAsJsonArray());var pose=Rig.values(bones.length);
        if(request.has("defaults")) tracks(request.getAsJsonArray("defaults"),0,pose);
        if(request.has("tracks")) tracks(request.getAsJsonArray("tracks"),request.get("time").getAsFloat(),pose);
        if(request.has("values")&&!request.get("values").isJsonNull()) {
            var values=request.getAsJsonArray("values");
            if(values.size()!=pose.length)throw new IllegalArgumentException("Expected one pose per bone");
            for(int i=0;i<pose.length;i++){pose[i]=Rig.floats(values.get(i).getAsJsonArray());if(pose[i].length!=9)throw new IllegalArgumentException("Expected nine pose channels");}
        }
        var world=Rig.matrices(bones.length);
        int hat=request.has("hat")?request.get("hat").getAsInt():-1;
        float scale=request.has("hat_scale")?request.get("hat_scale").getAsFloat():1;
        Vector3f anchor=scale==1?null:new Vector3f(Rig.floats(request.getAsJsonArray("hat_anchor")));
        new Compose(bones).world(pose,world,hat,scale,anchor,null);
        var matrices=new JsonArray();for(var m:world)matrices.add(rows(m));out.add("world",matrices);return out;
    }
    private static void tracks(JsonArray tracks,float time,float[][] pose) {
        for(var raw:tracks){var tr=raw.getAsJsonObject();int offset=switch(tr.get("channel").getAsString()){
            case "position"->0;case "rotation"->3;case "scale"->6;default->throw new IllegalArgumentException("Unknown channel");};
            var keys=tr.getAsJsonArray("keys");float[][] values=new float[keys.size()][];
            for(int i=0;i<values.length;i++)values[i]=Rig.floats(keys.get(i).getAsJsonArray());
            new Clips.Track(tr.get("bone").getAsInt(),offset,values).sample(time,pose[tr.get("bone").getAsInt()]);
        }
    }
}
