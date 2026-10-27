package io.github.longshou.mcyukkuri.subspecies;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/** Shared hit geometry only: an empty patch of brim never selects a nearby passenger. */
public final class HatHitResolver {
    public record Hit(YukkuriEntity passenger,Vec3 point){}
    public static Hit resolve(HatBoatEntity hat,Player player,Vec3 hit){
        var eye=player.getEyePosition();
        var direction=hit.subtract(eye);
        if(direction.lengthSqr()<1e-8)return null;
        double reach=player.entityInteractionRange();
        var end=eye.add(direction.normalize().scale(reach));
        Hit chosen=null;double nearest=Double.MAX_VALUE;
        for(var passenger:hat.getPassengers()){
            if(!(passenger instanceof YukkuriEntity child)||!child.isAlive())continue;
            var box=child.getBoundingBox().inflate(Math.min(.035,child.getBbWidth()*.15));
            var contact=box.contains(eye)?eye:box.clip(eye,end).orElse(null);
            if(contact==null)continue;
            double distance=eye.distanceToSqr(contact);
            // Permit the thin hull slab to precede the child's feet, not a body hidden beyond the hull.
            if(eye.distanceTo(contact)>eye.distanceTo(hit)+Math.min(.3,hat.getBbWidth()*.5))continue;
            if(distance<nearest){nearest=distance;chosen=new Hit(child,contact);}
        }
        return chosen;
    }
    private HatHitResolver(){}
}
