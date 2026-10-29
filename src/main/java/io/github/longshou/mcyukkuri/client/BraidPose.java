package io.github.longshou.mcyukkuri.client;

import static io.github.longshou.mcyukkuri.client.YukkuriModel.DEGREES;
import io.github.longshou.mcyukkuri.client.YukkuriModel.Bone;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** A Marisa's braid bent to one point per render (a Grab's reach and hold, ParentCarryPresentation), over the clip's own braid. */
final class BraidPose {
    /** The authored braid tip in model units (braid_rescue.json), the rest shape both braid segments are posed against. */
    private static final Vector3f MODEL_TIP=readTip();
    private static Vector3f readTip(){
        try(var stream=BraidPose.class.getResourceAsStream("/assets/mcyukkuri/yukkuri_presentation/braid_rescue.json")){
            if(stream==null)return new Vector3f();
            var point=JsonParser.parseReader(new InputStreamReader(stream,StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonArray("braid_tip");
            return new Vector3f(point.get(0).getAsFloat(),point.get(1).getAsFloat(),point.get(2).getAsFloat());
        }catch(IOException|RuntimeException unreadable){return new Vector3f();}
    }
    private final YukkuriModel o;
    private final Vector3f tip=new Vector3f(),joint=new Vector3f();
    /** How much of the reach replaces the clip's braid (0 none, 1 all of it). */
    private float weight;
    BraidPose(YukkuriModel owner){o=owner;}
    boolean active(){return weight>0;}
    /** Bend the braid so its tip lies at {@code point} (model units) with this share of the clip's braid; null lets it go. */
    void reach(Vector3f point,float share){weight=point==null?0:Math.clamp(share,0,1);if(point!=null)tip.set(point);}

    private int bone(String name){var bones=o.bones();for(int i=0;i<bones.length;i++)if(bones[i].name().equals(name))return i;return -1;}

    /** Carry the hat (bone {@code hat} and every bone under it) so its braid grip lies on the braid tip, by this share. */
    void holdHat(int hat,float share,Vector3f grip){
        var bones=o.bones();var world=o.world();int lower=bone("hand_right_tip");
        if(hat<0||lower<0||share<=0)return;
        var move=new Matrix4f().translation(world[lower].transformPosition(new Vector3f(MODEL_TIP))
                .sub(world[hat].transformPosition(new Vector3f(grip))).mul(share));
        for(int i=0;i<bones.length;i++){
            boolean under=i==hat;for(int at=bones[i].parent();!under&&at>=0;at=bones[at].parent())under=at==hat;
            if(under)world[i].set(new Matrix4f(move).mul(world[i]));
        }
    }

    /** Bend the two existing braid sections about their real pivots; no stretching or detached tip. */
    void pose(){
        var bones=o.bones();var world=o.world();var pose=o.pose();
        int upper=bone("hand_right"),lower=bone("hand_right_tip");
        if(upper<0||lower<0||bones[upper].parent()<0)return;
        var base=bones[upper];var end=bones[lower];
        Matrix4f parent=world[base.parent()];
        if(!solveJoint(world[upper],world[lower],base,end))return;
        var desired=new Matrix4f(parent).invert().transformPosition(new Vector3f(joint)).sub(base.x(),base.y(),base.z());
        var rest=new Vector3f(end.x()-base.x(),end.y()-base.y(),end.z()-base.z());
        if(desired.lengthSquared()<1e-10f||rest.lengthSquared()<1e-10f)return;
        var held=new Quaternionf().rotateZYX(pose[upper][5]*DEGREES,pose[upper][4]*DEGREES,pose[upper][3]*DEGREES);
        var rotation=held.slerp(new Quaternionf().rotationTo(rest.normalize(),desired.normalize()),weight);
        world[upper].set(parent).translate(base.x(),base.y(),base.z()).rotate(rotation).translate(-base.x(),-base.y(),-base.z());
        for(int i=upper+1;i<bones.length;i++){
            boolean follows=false;for(int at=bones[i].parent();at>=0;at=bones[at].parent())if(at==upper){follows=true;break;}
            if(!follows)continue;
            Bone bone=bones[i];float[] v=pose[i];
            if(i==lower){
                Matrix4f above=world[bone.parent()];
                var distal=new Vector3f(MODEL_TIP.x-end.x(),MODEL_TIP.y-end.y(),MODEL_TIP.z-end.z());
                var toward=new Matrix4f(above).invert().transformPosition(new Vector3f(tip)).sub(end.x(),end.y(),end.z());
                world[i].set(above);
                if(distal.lengthSquared()>1e-10f&&toward.lengthSquared()>1e-10f)world[i].translate(end.x(),end.y(),end.z())
                        .rotate(new Quaternionf().slerp(new Quaternionf().rotationTo(distal.normalize(),toward.normalize()),weight))
                        .translate(-end.x(),-end.y(),-end.z());
                continue;
            }
            if(bone.name().equals("hand_right_ribbon")){world[i].set(world[lower]);continue;}
            world[i].set(world[bone.parent()]).translate(bone.x()+v[0],bone.y()+v[1],bone.z()+v[2])
                    .rotateZYX(v[5]*DEGREES,v[4]*DEGREES,v[3]*DEGREES)
                    .scale(v[6],v[7],v[8]).translate(-bone.x(),-bone.y(),-bone.z());
        }
        // The ribbon can be a sibling before the tip in the authored bone list.
        for(int i=upper+1;i<bones.length;i++)if(bones[i].name().equals("hand_right_ribbon"))world[i].set(world[lower]);
    }

    /** Two-segment reach in model space: segment lengths as posed, the bend kept on the clip's own side, never stretched. */
    private boolean solveJoint(Matrix4f upper,Matrix4f lower,Bone base,Bone end){
        var root=upper.transformPosition(new Vector3f(base.x(),base.y(),base.z()));
        var elbow=upper.transformPosition(new Vector3f(end.x(),end.y(),end.z()));
        var tipNow=lower.transformPosition(new Vector3f(MODEL_TIP));
        float first=elbow.distance(root),second=tipNow.distance(elbow);
        var toward=new Vector3f(tip).sub(root);float reach=toward.length();
        if(first<1e-4f||second<1e-4f||reach<1e-4f)return false;
        toward.div(reach);
        float d=Math.clamp(reach,Math.abs(first-second)+1e-3f,first+second-1e-3f);
        var pole=new Vector3f(elbow).sub(root);pole.sub(new Vector3f(toward).mul(pole.dot(toward)));
        if(pole.lengthSquared()<1e-8f)pole.set(0,1,0).sub(new Vector3f(toward).mul(toward.y));
        if(pole.lengthSquared()<1e-8f)pole.set(1,0,0);
        pole.normalize();
        float along=(first*first-second*second+d*d)/(2*d),side=(float)Math.sqrt(Math.max(0,first*first-along*along));
        joint.set(root).add(new Vector3f(toward).mul(along)).add(pole.mul(side));
        return true;
    }
}
