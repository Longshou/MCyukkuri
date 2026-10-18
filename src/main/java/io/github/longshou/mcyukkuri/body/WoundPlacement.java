package io.github.longshou.mcyukkuri.body;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Server-authoritative inverse posed-skin picking and physical release coordinates. */
public final class WoundPlacement {
    public static float angle(YukkuriEntity e){return switch(e.contactPose()){case "SUPINE","FIXED","HOOK"->90;case "ROLL"->e.contactTurn();case "RUMP"->-12;default->0;};}
    private static final java.util.Map<String,Float> LIFTS=new java.util.concurrent.ConcurrentHashMap<>();
    public static float lift(YukkuriEntity e){
        if(e.contactPose().equals("FIXED")){String name="restraint_frame_"+e.getVariant().assetId()+(e.hasVisibleAccessory()?"":"_bare");return LIFTS.computeIfAbsent(name,WoundPlacement::fixedLift);}
        return angle(e)==0?0:WoundSurface.get(e.subspecies().assetId()).contactLift(angle(e),e.hasVisibleAccessory(),e.visibleHairLoss());
    }
    private static float fixedLift(String name){
        String path="/assets/mcyukkuri/yukkuri_props/"+name+".json";
        var stream=WoundPlacement.class.getResourceAsStream(path);
        if(stream==null)throw new IllegalStateException("Missing fitted operation resource: "+path);
        try(var reader=new java.io.InputStreamReader(stream,java.nio.charset.StandardCharsets.UTF_8)){
            var value=com.google.gson.JsonParser.parseReader(reader).getAsJsonObject().get("body_lift");
            if(value==null)throw new IllegalStateException("Missing body_lift in "+path);
            float lift=value.getAsFloat();
            if(!Float.isFinite(lift)||lift<=0)throw new IllegalStateException("Invalid body_lift in "+path);
            return lift;
        }catch(java.io.IOException ex){throw new java.io.UncheckedIOException(ex);}
    }
    /** Model units to an entity-relative world vector; keep world translation in double precision. */
    public static Matrix4f transform(YukkuriEntity e){float scale=e.getScale()*e.growthScale();var m=new Matrix4f().rotateY((float)Math.toRadians(180-e.yBodyRot)).scale(scale);
        if(angle(e)!=0)m.translate(0,lift(e),0).rotateX((float)Math.toRadians(angle(e)));
        if(e.contactPose().equals("GRIP"))m.scale(1.1f,.85f,1.1f);
        int filling=e.body()!=null&&!e.level().isClientSide?e.body().filling():e.visibleFilling();
        m.scale(BodySigns.sagWidth(filling),BodySigns.sagHeight(filling),BodySigns.sagWidth(filling));
        String behavior=e.getBehaviorId();float t=Math.max(0,e.level().getGameTime()-e.getBehaviorStartTick());float y=switch(behavior){case "sleep_enter"->1-Math.min(1,t/20)*.04f;case "sleep"->.96f;case "wake"->.96f+Math.min(1,t/20)*.04f;default->1;};
        return m.scale(1,y,1).scale(1f/16);
    }
    public static Matrix4f[] pose(YukkuriEntity e){boolean operated=!e.contactPose().equals("NONE")&&!e.contactPose().equals("SLIDE");return WoundSurface.get(e.subspecies().assetId()).pose(operated?"idle":e.getAnimationId(),operated?0:Math.max(0,(e.level().getGameTime()-e.getAnimationStartTick())/20f));}
    public static WoundSurface.Hit hit(YukkuriEntity e,DamageSource source){var mesh=WoundSurface.get(e.subspecies().assetId());var inverse=transform(e).invert();var world=pose(e);Vec3 origin,direction;
        var direct=source.getDirectEntity();
        if(direct instanceof net.minecraft.world.entity.projectile.Projectile){direction=direct.getDeltaMovement().normalize();origin=direct.position().subtract(direction.scale(2));}
        else if(source.getEntity() instanceof net.minecraft.world.entity.LivingEntity attacker){origin=attacker.getEyePosition();direction=attacker.getLookAngle();}
        else {origin=source.getSourcePosition();if(origin==null)origin=e.position().add(0,source.is(net.minecraft.tags.DamageTypeTags.IS_FALL)?-2:2,0);direction=e.getBoundingBox().getCenter().subtract(origin).normalize();}
        var o=inverse.transformPosition(origin.subtract(e.position()).toVector3f());var d=inverse.transformDirection(direction.toVector3f()).normalize();var hit=mesh.ray(o,d,world,e.body().wounds());
        // Vanilla can accept broad-box hits outside the visible skin (e.g. a mob's melee aim).
        if(hit==null){var center=inverse.transformPosition(e.getBoundingBox().getCenter().subtract(e.position()).toVector3f());hit=mesh.ray(o,center.sub(o).normalize(),world,e.body().wounds());}
        return hit;
    }
    public static Vec3 outlet(YukkuriEntity e,BodyWound wound){var mesh=WoundSurface.get(e.subspecies().assetId());var p=mesh.point(wound,pose(e),e.body().wounds());transform(e).transformPosition(p);return e.position().add(new Vec3(p));}
    public static Vec3 outward(YukkuriEntity e,BodyWound w){var mesh=WoundSurface.get(e.subspecies().assetId());var world=pose(e);var a=mesh.posed(w.triangle(),world,e.body().wounds());var b=mesh.posed(w.triangle()+1,world,e.body().wounds());var c=mesh.posed(w.triangle()+2,world,e.body().wounds());var n=b.sub(a).cross(c.sub(a));transform(e).transformDirection(n).normalize();return new Vec3(n);}
    private WoundPlacement(){}
}
