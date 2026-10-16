package io.github.longshou.mcyukkuri.client;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * Maps the server's brim-dip rescue to clips (project original, not canon): the parent settles beside the child and
 * dips the near rim, the child bites the rim and surges up it, the parent gives one chest-first nudge and the child
 * slides in. The braid only holds the paddle or lightly touches the rim; it never lifts, carries or turns anything.
 * Unknown or retired phase names (including saves from the old braid-carry flow) pose nothing.
 */
final class BraidRescuePresentation {
    private static boolean known(String phase){
        return switch(phase){case "APPROACH","OFFER","BITE","PUSH","SLIDE","SETTLE","RELEASE"->true;default->false;};
    }
    static boolean active(YukkuriEntity entity){
        if(!known(entity.braidRescuePhase()))return false;
        var state=entity.braidRescueState();int id=entity.getId();
        return state.getInt("ParentId")==id||state.getInt("ChildId")==id;
    }
    static boolean parent(YukkuriEntity entity){return active(entity)&&entity.braidRescueState().getInt("ParentId")==entity.getId();}
    static boolean child(YukkuriEntity entity){return active(entity)&&entity.braidRescueState().getInt("ChildId")==entity.getId();}
    static CabinDistressPresentation.Motion motion(YukkuriEntity entity,float partial){
        if(!active(entity))return null;
        String phase=entity.braidRescuePhase();
        float seconds=Math.max(0,(entity.level().getGameTime()-entity.braidRescueStartTick()+partial)/20f);
        var state=entity.braidRescueState();
        int phaseTicks=state.contains("Duration")?state.getInt("Duration")
                :io.github.longshou.mcyukkuri.family.BraidRescueGeometry.ticks(phase);
        float q=Mth.clamp(seconds/(Math.max(1,phaseTicks)/20f),0,1);
        boolean parent=parent(entity);
        // Bite 1: the child's mouth is on the parent's rim; Bite 2: the parent's mouth is on the child's hat.
        boolean parentBites=state.getInt("Bite")==2;
        float slide=state.contains("Progress")?Mth.clamp((float)state.getDouble("Progress"),0,1):q;
        return switch(phase){
            // The boat is held and the parent takes the last steps: calm and attentive, the child keeps struggling.
            case "APPROACH"->parent?new CabinDistressPresentation.Motion("boat_calm",.7f+seconds%1.1f)
                    :new CabinDistressPresentation.Motion("water_struggle",seconds%1.6f);
            case "OFFER"->parent?new CabinDistressPresentation.Motion("hat_brim_offer",seconds%1.2f)
                    :new CabinDistressPresentation.Motion("water_struggle",seconds%1.6f);
            case "BITE"->parent?parentBites?new CabinDistressPresentation.Motion("water_mouth_grip",seconds%.8f)
                            :new CabinDistressPresentation.Motion("hat_brim_offer",seconds%1.2f)
                    :parentBites?new CabinDistressPresentation.Motion("water_held_breath",seconds%1.6f)
                            :new CabinDistressPresentation.Motion("water_mouth_grip",seconds%.8f);
            case "PUSH"->parent?new CabinDistressPresentation.Motion("boat_nudge_young",.8f*q)
                    :new CabinDistressPresentation.Motion("water_held_breath",seconds%1.6f);
            case "SLIDE"->parent?new CabinDistressPresentation.Motion("hat_brim_offer",seconds%1.2f)
                    :new CabinDistressPresentation.Motion("hat_slide_over",1.4f*slide);
            case "SETTLE"->parent?new CabinDistressPresentation.Motion("boat_calm",Math.min(4f,seconds))
                    :new CabinDistressPresentation.Motion("water_recover",Math.min(2.8f,seconds));
            case "RELEASE"->parent?new CabinDistressPresentation.Motion("boat_calm",Math.min(4f,.8f+seconds))
                    :new CabinDistressPresentation.Motion("water_recover",Math.min(2.8f,.8f+seconds));
            default->null;
        };
    }
    /** Light visual touch only, and only when the server supplies the rim point during the bite, slide or settle. */
    static void configure(YukkuriEntity actor,YukkuriModel model,float partial,float renderedYaw){
        CompoundTag state=actor.braidRescueState();
        String phase=actor.braidRescuePhase();
        if(!parent(actor)||!(phase.equals("BITE")||phase.equals("SLIDE")||phase.equals("SETTLE"))
                ||!state.contains("TipX")||!state.contains("JointX")||!state.contains("PrevTipX")||!state.contains("PrevJointX")){
            model.setBraidRescueContact(null);model.setBraidRescueJoint(null);return;
        }
        float unit=16/Math.max(.001f,actor.getScale()*actor.growthScale());
        Vec3 at=new Vec3(Mth.lerp(partial,state.getDouble("PrevTipX"),state.getDouble("TipX")),
                Mth.lerp(partial,state.getDouble("PrevTipY"),state.getDouble("TipY")),
                Mth.lerp(partial,state.getDouble("PrevTipZ"),state.getDouble("TipZ")));
        Vec3 relative=at.subtract(actor.getPosition(partial));
        model.setBraidRescueContact(new Vector3f((float)relative.x,(float)relative.y,(float)relative.z)
                .rotateY((renderedYaw-180)*Mth.DEG_TO_RAD).mul(unit));
        Vec3 joint=new Vec3(Mth.lerp(partial,state.getDouble("PrevJointX"),state.getDouble("JointX")),
                Mth.lerp(partial,state.getDouble("PrevJointY"),state.getDouble("JointY")),
                Mth.lerp(partial,state.getDouble("PrevJointZ"),state.getDouble("JointZ")));
        var jointRelative=joint.subtract(actor.getPosition(partial));
        model.setBraidRescueJoint(new Vector3f((float)jointRelative.x,(float)jointRelative.y,(float)jointRelative.z)
                .rotateY((renderedYaw-180)*Mth.DEG_TO_RAD).mul(unit));
    }
    private BraidRescuePresentation(){}
}
