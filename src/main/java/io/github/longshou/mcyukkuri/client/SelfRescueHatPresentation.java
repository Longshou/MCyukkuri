package io.github.longshou.mcyukkuri.client;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.subspecies.HatVessel;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Moves only the one still-worn hat to its already reserved physical deployment endpoint. */
final class SelfRescueHatPresentation {
    static void configure(YukkuriEntity entity,YukkuriModel model,float partial,float bodyYaw){
        model.setSelfHatTransfer(null,0);
        var spec=model.selfHatTransfer();var state=entity.aquaticSelfRescueState();
        if(spec==null||!entity.hasVisibleAccessory()||!entity.getBehaviorId().startsWith("water_hat_")||!state.getBoolean("Planned"))return;
        String phase=state.getString("Phase");
        float elapsed=Math.max(0,entity.level().getGameTime()-state.getLong("Start")+partial);
        float amount=phase.equals("REMOVE_HAT")?Mth.clamp(elapsed/spec.removeTicks(),0,1)
                :phase.equals("SLIPPED")?state.getFloat("RemovalProgress")*(1-Mth.clamp(elapsed/spec.returnTicks(),0,1)):0;
        if(amount<=0)return;
        float scale=Math.max(.001f,entity.getScale()*entity.growthScale());
        var feet=entity.getPosition(partial);
        var offset=new Vector3f((float)(state.getDouble("DeployX")-feet.x),(float)(state.getDouble("DeployY")-feet.y),(float)(state.getDouble("DeployZ")-feet.z))
                .rotateY((bodyYaw-180)*Mth.DEG_TO_RAD).mul(16/scale);
        float hat=HatVessel.hatMultiplier(entity.subspeciesFlags());
        var destination=new Matrix4f().translation(offset)
                .rotateY((bodyYaw-state.getFloat("DeployYaw"))*Mth.DEG_TO_RAD).rotateZ(spec.rollDegrees()*Mth.DEG_TO_RAD)
                .translate(0,HatVessel.FREEBOARD*hat,0).rotateY(Mth.HALF_PI).rotateX(Mth.PI)
                .scale(hat,hat*HatVessel.SQUASH_FLOAT,hat).translate(HatVessel.anchor().negate());
        model.setSelfHatTransfer(destination,amount);
    }
    private SelfRescueHatPresentation(){}
}
