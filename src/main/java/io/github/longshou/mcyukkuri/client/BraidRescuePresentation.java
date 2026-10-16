package io.github.longshou.mcyukkuri.client;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/** The server owns custody and the single real tip; this class only poses that existing braid. */
final class BraidRescuePresentation {
    static boolean active(YukkuriEntity entity){
        String phase=entity.braidRescuePhase(),behavior=entity.getBehaviorId();
        return (behavior.startsWith("braid_rescue_")||behavior.equals("braid_be_rescued"))&&!phase.isEmpty()&&!phase.equals("IDLE");
    }
    static boolean parent(YukkuriEntity entity){return active(entity)&&entity.braidRescueState().getInt("ParentId")==entity.getId();}
    static boolean heldChild(YukkuriEntity entity){
        if(!active(entity)||entity.braidRescueState().getInt("ChildId")!=entity.getId())return false;
        return switch(entity.braidRescuePhase()){case "GRIP","LIFT","PLACE","SETTLE","RELEASE"->true;default->false;};
    }
    static CabinDistressPresentation.Motion motion(YukkuriEntity entity,float partial){
        if(heldChild(entity))return new CabinDistressPresentation.Motion("water_held_breath",((entity.tickCount+partial)/20f)%1.6f);
        if(!parent(entity))return null;
        String phase=entity.braidRescuePhase();
        float seconds=Math.max(0,(entity.level().getGameTime()-entity.braidRescueStartTick()+partial)/20f);
        int phaseTicks=entity.braidRescueState().contains("Duration")?entity.braidRescueState().getInt("Duration")
                :io.github.longshou.mcyukkuri.family.BraidRescueGeometry.ticks(phase);
        float duration=Math.max(1,phaseTicks)/20f;
        float q=Mth.clamp(seconds/duration,0,1);
        return switch(phase){
            case "REACH"->new CabinDistressPresentation.Motion("braid_rescue_reach",.8f*q);
            case "CONTACT"->new CabinDistressPresentation.Motion("braid_rescue_reach",.8f+.3f*q);
            case "GRIP"->new CabinDistressPresentation.Motion("braid_rescue_hold",.3f*q);
            case "LIFT"->new CabinDistressPresentation.Motion("braid_rescue_hold",.3f+.9f*q);
            case "PLACE"->new CabinDistressPresentation.Motion("braid_rescue_hold",entity.braidRescueState().getBoolean("Travelling")?1.2f:.8f+.4f*q);
            case "SETTLE"->new CabinDistressPresentation.Motion("braid_rescue_settle",.6f*q);
            case "RELEASE"->new CabinDistressPresentation.Motion("braid_rescue_settle",.6f+.3f*q);
            default->null;
        };
    }
    static void configure(YukkuriEntity parent,YukkuriModel model,float partial,float renderedYaw){
        if(!parent(parent)){model.setBraidRescueContact(null);model.setBraidRescueJoint(null);return;}
        CompoundTag state=parent.braidRescueState();
        Vec3 at=new Vec3(Mth.lerp(partial,state.getDouble("PrevTipX"),state.getDouble("TipX")),
                Mth.lerp(partial,state.getDouble("PrevTipY"),state.getDouble("TipY")),
                Mth.lerp(partial,state.getDouble("PrevTipZ"),state.getDouble("TipZ")));
        Vec3 relative=at.subtract(parent.getPosition(partial));
        var local=new Vector3f((float)relative.x,(float)relative.y,(float)relative.z)
                .rotateY((renderedYaw-180)*Mth.DEG_TO_RAD)
                .mul(16/Math.max(.001f,parent.getScale()*parent.growthScale()));
        model.setBraidRescueContact(local);
        Vec3 joint=new Vec3(Mth.lerp(partial,state.getDouble("PrevJointX"),state.getDouble("JointX")),
                Mth.lerp(partial,state.getDouble("PrevJointY"),state.getDouble("JointY")),
                Mth.lerp(partial,state.getDouble("PrevJointZ"),state.getDouble("JointZ")));
        var jointRelative=joint.subtract(parent.getPosition(partial));
        var jointLocal=new Vector3f((float)jointRelative.x,(float)jointRelative.y,(float)jointRelative.z)
                .rotateY((renderedYaw-180)*Mth.DEG_TO_RAD)
                .mul(16/Math.max(.001f,parent.getScale()*parent.growthScale()));
        model.setBraidRescueJoint(jointLocal);
    }
    static float rootBlend(YukkuriEntity parent,float partial){
        if(!parent(parent))return 0;
        var state=parent.braidRescueState();return Mth.lerp(partial,(float)state.getDouble("PrevRootBlend"),(float)state.getDouble("RootBlend"));
    }
    private BraidRescuePresentation(){}
}
