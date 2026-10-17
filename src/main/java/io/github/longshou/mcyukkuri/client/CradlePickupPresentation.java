package io.github.longshou.mcyukkuri.client;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/** Poses the existing right braid against server contact points; the real child renders normally. */
final class CradlePickupPresentation {
    static boolean active(YukkuriEntity actor){
        var state=actor.braidRescueState();return state.getString("Phase").startsWith("CRADLE_")
                &&(state.getInt("ParentId")==actor.getId()||state.getInt("ChildId")==actor.getId());
    }
    static CabinDistressPresentation.Motion motion(YukkuriEntity actor){
        // Keep the authored body at its rest root; only the braid articulates. Physics moves the infant.
        return new CabinDistressPresentation.Motion("idle",0);
    }
    static void configure(YukkuriEntity actor,YukkuriModel model,float partial,float renderedYaw){
        var state=actor.braidRescueState();
        boolean contact=state.getInt("ParentId")==actor.getId()
                &&state.contains("TipX")&&state.contains("JointX");
        model.setBraidCarryContact(contact);
        if(!contact){model.setBraidRescueContact(null);model.setBraidRescueJoint(null);return;}
        model.setBraidRescueContact(point(actor,"Tip",partial,renderedYaw));
        model.setBraidRescueJoint(point(actor,"Joint",partial,renderedYaw));
    }
    private static Vector3f point(YukkuriEntity actor,String key,float partial,float renderedYaw){
        var state=actor.braidRescueState();String previous=state.contains("Prev"+key+"X")?"Prev"+key:key;
        var world=new Vec3(Mth.lerp(partial,state.getDouble(previous+"X"),state.getDouble(key+"X")),
                Mth.lerp(partial,state.getDouble(previous+"Y"),state.getDouble(key+"Y")),
                Mth.lerp(partial,state.getDouble(previous+"Z"),state.getDouble(key+"Z")));
        var relative=world.subtract(actor.getPosition(partial));
        return new Vector3f((float)relative.x,(float)relative.y,(float)relative.z)
                .rotateY((renderedYaw-180)*Mth.DEG_TO_RAD).mul(16/Math.max(.001f,actor.getScale()*actor.growthScale()));
    }
    private CradlePickupPresentation(){}
}
