package io.github.longshou.mcyukkuri.client;

import static io.github.longshou.mcyukkuri.client.YukkuriModel.DEGREES;
import io.github.longshou.mcyukkuri.client.YukkuriModel.Bone;
import com.google.gson.JsonParser;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Finite braid rescue pose and its per-render contact inputs. */
final class BraidPose {
    /** The authored braid tip in model units (braid_rescue.json), the rest shape both braid segments are posed against. */
    private static final Vector3f MODEL_TIP=readTip();
    private static Vector3f readTip(){
        try(var stream=BraidPose.class.getResourceAsStream("/assets/mcyukkuri/yukkuri_presentation/braid_rescue.json")){
            if(stream==null)return new Vector3f();
            var point=JsonParser.parseReader(new java.io.InputStreamReader(stream,java.nio.charset.StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonArray("braid_tip");
            return new Vector3f(point.get(0).getAsFloat(),point.get(1).getAsFloat(),point.get(2).getAsFloat());
        }catch(java.io.IOException|RuntimeException unreadable){return new Vector3f();}
    }
    private final YukkuriModel o;
    private final Vector3f rescueTip=new Vector3f(),rescueJoint=new Vector3f();
    private boolean rescueContact,rescueJointActive,rescueGrip;
    /** Incidental rim touch blends softly; a server-validated rescue contact uses the whole finite braid. */
    private static final float LIGHT_RIM_TOUCH=.5f;
    private boolean braidCarryContact;
    BraidPose(YukkuriModel owner){o=owner;}
    boolean active(){return rescueContact;}

    void setBraidCarryContact(boolean active){braidCarryContact=active;}
    void setBraidRescueContact(Vector3f point){rescueContact=point!=null;if(point!=null)rescueTip.set(point);}
    void setBraidRescueGrip(boolean active){rescueGrip=active;}
    void setBraidRescueJoint(Vector3f point){rescueJointActive=point!=null;if(point!=null)rescueJoint.set(point);solveJoint=false;}
    /** Only the tip is given: the joint is solved on the two authored segment lengths, bending the way the clip bends them. */
    private boolean solveJoint;
    void setContactTip(Vector3f tip,boolean grip){
        rescueContact=tip!=null;solveJoint=tip!=null;rescueJointActive=false;rescueGrip=grip;braidCarryContact=grip;
        if(tip!=null)rescueTip.set(tip);
    }

    /** Bend the two existing braid sections about their real pivots; no stretching or detached tip. */
    void poseRescueBraid(){
        var bones=o.bones();var world=o.world();var pose=o.pose();
        int joint=-1,tip=-1;
        for(int i=0;i<bones.length;i++){
            if(bones[i].name().equals("hand_right"))joint=i;
            if(bones[i].name().equals("hand_right_tip"))tip=i;
        }
        if(joint<0||tip<0||bones[joint].parent()<0)return;
        var base=bones[joint];var end=bones[tip];
        Matrix4f parent=world[base.parent()];
        if(solveJoint)solveJoint(world[joint],world[tip],base,end);
        var desired=new Matrix4f(parent).invert().transformPosition(new Vector3f(rescueJointActive?rescueJoint:rescueTip)).sub(base.x(),base.y(),base.z());
        var authored=MODEL_TIP;
        var rest=rescueJointActive?new Vector3f(end.x()-base.x(),end.y()-base.y(),end.z()-base.z())
                :new Vector3f((float)authored.x-base.x(),(float)authored.y-base.y(),(float)authored.z-base.z());
        if(desired.lengthSquared()<1e-10f||rest.lengthSquared()<1e-10f)return;
        var rotation=new Quaternionf().rotationTo(rest.normalize(),desired.normalize());
        // A real rescue target must reach its contact; incidental touch still blends with the paddle pose.
        var held=new Quaternionf().rotateZYX(pose[joint][5]*DEGREES,pose[joint][4]*DEGREES,pose[joint][3]*DEGREES);
        rotation=held.slerp(rotation,(rescueGrip||braidCarryContact)?1f:LIGHT_RIM_TOUCH);
        world[joint].set(parent).translate(base.x(),base.y(),base.z()).rotate(rotation).translate(-base.x(),-base.y(),-base.z());
        for(int i=joint+1;i<bones.length;i++){
            boolean follows=false;for(int at=bones[i].parent();at>=0;at=bones[at].parent())if(at==joint){follows=true;break;}
            if(!follows)continue;
            Bone bone=bones[i];float[] v=pose[i];
            if(i==tip){
                Matrix4f upper=world[bone.parent()];
                if(rescueJointActive){
                    var distal=new Vector3f((float)authored.x-end.x(),(float)authored.y-end.y(),(float)authored.z-end.z());
                    var toward=new Matrix4f(upper).invert().transformPosition(new Vector3f(rescueTip)).sub(end.x(),end.y(),end.z());
                    if(distal.lengthSquared()>1e-10f&&toward.lengthSquared()>1e-10f){
                        var bend=new Quaternionf().slerp(new Quaternionf().rotationTo(distal.normalize(),toward.normalize()),(rescueGrip||braidCarryContact)?1f:LIGHT_RIM_TOUCH);
                        world[i].set(upper).translate(end.x(),end.y(),end.z()).rotate(bend).translate(-end.x(),-end.y(),-end.z());
                        continue;
                    }
                }
                world[i].set(upper);continue;
            }
            if(bone.name().equals("hand_right_ribbon")){
                world[i].set(rescueJointActive?world[tip]:world[bone.parent()]);continue;
            }
            world[i].set(world[bone.parent()]).translate(bone.x()+v[0],bone.y()+v[1],bone.z()+v[2])
                    .rotateZYX(v[5]*DEGREES,v[4]*DEGREES,v[3]*DEGREES)
                    .scale(v[6],v[7],v[8]).translate(-bone.x(),-bone.y(),-bone.z());
        }
        // The ribbon can be a sibling before the tip in the authored bone list.
        for(int i=joint+1;i<bones.length;i++)if(bones[i].name().equals("hand_right_ribbon"))world[i].set(world[tip]);
    }

    /** Two-segment reach in model space: segment lengths as posed, the bend kept on the clip's own side, never stretched. */
    private void solveJoint(Matrix4f upper,Matrix4f lower,Bone base,Bone end){
        var authored=MODEL_TIP;
        var root=upper.transformPosition(new Vector3f(base.x(),base.y(),base.z()));
        var elbow=upper.transformPosition(new Vector3f(end.x(),end.y(),end.z()));
        var tipNow=lower.transformPosition(new Vector3f((float)authored.x,(float)authored.y,(float)authored.z));
        float first=elbow.distance(root),second=tipNow.distance(elbow);
        var toward=new Vector3f(rescueTip).sub(root);float reach=toward.length();
        if(first<1e-4f||second<1e-4f||reach<1e-4f){rescueJointActive=false;return;}
        toward.div(reach);
        float d=Math.clamp(reach,Math.abs(first-second)+1e-3f,first+second-1e-3f);
        var pole=new Vector3f(elbow).sub(root);pole.sub(new Vector3f(toward).mul(pole.dot(toward)));
        if(pole.lengthSquared()<1e-8f)pole.set(0,1,0).sub(new Vector3f(toward).mul(toward.y));
        if(pole.lengthSquared()<1e-8f)pole.set(1,0,0);
        pole.normalize();
        float along=(first*first-second*second+d*d)/(2*d),side=(float)Math.sqrt(Math.max(0,first*first-along*along));
        rescueJoint.set(root).add(new Vector3f(toward).mul(along)).add(pole.mul(side));
        rescueJointActive=true;
    }
}
