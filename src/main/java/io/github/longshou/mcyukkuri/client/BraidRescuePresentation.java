package io.github.longshou.mcyukkuri.client;

import io.github.longshou.mcyukkuri.entity.YukkuriView;
import io.github.longshou.mcyukkuri.family.BraidRescueGeometry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/** Displays the server's real rim bite or two-segment braid contact; it never moves either entity. */
final class BraidRescuePresentation {
    private static boolean known(String phase){
        return switch(phase){case "APPROACH","OFFER","REACH","GRIP","BITE","PUSH","SLIDE","SETTLE","RELEASE"->true;default->false;};
    }
    static boolean active(YukkuriView entity){
        if(CradlePickupPresentation.active(entity))return true;
        if(!known(entity.braidRescuePhase()))return false;
        var state=entity.braidRescueState();int id=entity.getId();
        return state.getInt("ParentId")==id||state.getInt("ChildId")==id;
    }
    static boolean parent(YukkuriView entity){return active(entity)&&entity.braidRescueState().getInt("ParentId")==entity.getId();}
    static boolean child(YukkuriView entity){return active(entity)&&entity.braidRescueState().getInt("ChildId")==entity.getId();}
    static CabinDistressPresentation.Motion motion(YukkuriView entity,float partial){
        if(CradlePickupPresentation.active(entity))return CradlePickupPresentation.motion(entity);
        if(!active(entity))return null;
        String phase=entity.braidRescuePhase();
        float seconds=Math.max(0,(entity.level().getGameTime()-entity.braidRescueStartTick()+partial)/20f);
        var state=entity.braidRescueState();
        int phaseTicks=state.contains("Duration")?state.getInt("Duration")
                :BraidRescueGeometry.ticks(phase);
        float q=Mth.clamp(seconds/(Math.max(1,phaseTicks)/20f),0,1);
        boolean parent=parent(entity);
        // Bite 1: the child's mouth is on the parent's rim; Bite 2: the parent's mouth is on the child's hat.
        boolean parentBites=state.getInt("Bite")==2;
        float slide=state.contains("Progress")?Mth.clamp((float)state.getDouble("Progress"),0,1):q;
        if(state.getBoolean("WaterParent")){
            // This controller is swimming alongside the child, so deck/rim poses would show a false support.
            if(parent)return new CabinDistressPresentation.Motion("water_held_breath",seconds%1.6f);
            return new CabinDistressPresentation.Motion(phase.equals("REACH")?"water_struggle":"water_held_breath",seconds%1.6f);
        }
        if(parent&&state.getBoolean("Braid")&&state.contains("LeanStart")){
            // The braid holds the child: she sinks her braid side toward it (the server measures the reach from this pose)
            // and rises out of it again as the child settles.
            float leaning=(entity.level().getGameTime()-state.getLong("LeanStart")+partial)/20f;
            float ease=BraidRescueGeometry.leanTicks()/20f;
            switch(phase){
                case "REACH","GRIP","PUSH","SLIDE"->{return new CabinDistressPresentation.Motion("braid_reach_low",leaning);}
                case "SETTLE"->{if(seconds<ease)return new CabinDistressPresentation.Motion("braid_reach_low",ease-seconds);}
                default->{}
            }
        }
        return switch(phase){
            // The boat is held and the parent takes the last steps: calm and attentive, the child keeps struggling.
            case "APPROACH"->parent?new CabinDistressPresentation.Motion("boat_calm",.7f+seconds%1.1f)
                    :new CabinDistressPresentation.Motion("water_struggle",seconds%1.6f);
            case "OFFER"->parent?new CabinDistressPresentation.Motion("hat_brim_offer",seconds%1.2f)
                    :new CabinDistressPresentation.Motion("water_struggle",seconds%1.6f);
            case "REACH","GRIP"->parent?new CabinDistressPresentation.Motion("hat_brim_offer",seconds%1.2f)
                    :new CabinDistressPresentation.Motion("water_struggle",seconds%1.6f);
            case "BITE"->parent?parentBites?new CabinDistressPresentation.Motion("water_mouth_grip",seconds%.8f)
                            :new CabinDistressPresentation.Motion("hat_brim_offer",seconds%1.2f)
                    :parentBites?new CabinDistressPresentation.Motion("water_held_breath",seconds%1.6f)
                            :new CabinDistressPresentation.Motion("water_mouth_grip",seconds%.8f);
            case "PUSH"->parent?new CabinDistressPresentation.Motion(state.getBoolean("Braid")?"hat_brim_offer":"boat_nudge_young",.8f*q)
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
    /** Reach and grip target the actual synchronized tip and joint, with the authored segment lengths intact. */
    static void configure(YukkuriView actor,YukkuriModel model,float partial,float renderedYaw){
        model.setBraidCarryContact(false);
        if(CradlePickupPresentation.active(actor)){CradlePickupPresentation.configure(actor,model,partial,renderedYaw);return;}
        CompoundTag state=actor.braidRescueState();
        String phase=actor.braidRescuePhase();
        boolean braid=state.getBoolean("Braid");
        model.setBraidRescueGrip(braid);
        if(!parent(actor)||!braid||!(phase.equals("REACH")||phase.equals("GRIP")||phase.equals("PUSH")||phase.equals("SLIDE")||phase.equals("SETTLE"))
                ||!state.contains("TipX")||!state.contains("JointX")||!state.contains("PrevTipX")||!state.contains("PrevJointX")){
            model.setBraidRescueContact(null);model.setBraidRescueJoint(null);model.setBraidRescueGrip(false);return;
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
