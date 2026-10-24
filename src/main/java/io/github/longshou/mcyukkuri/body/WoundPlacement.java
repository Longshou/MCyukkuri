package io.github.longshou.mcyukkuri.body;

import com.google.gson.JsonParser;
import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.subspecies.HatVessel;
import io.github.longshou.mcyukkuri.subspecies.SelfRescueHatGeometry;
import io.github.longshou.mcyukkuri.family.LiveBirthGeometry;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Server-authoritative inverse posed-skin picking and physical release coordinates. */
public final class WoundPlacement {
    public static float angle(YukkuriEntity e){return switch(e.contactPose()){case "SUPINE","FIXED","HOOK"->90;case "ROLL"->e.contactTurn();case "RUMP","ASSIST"->-12;default->0;};}
    private static final Map<String,Float> LIFTS=new ConcurrentHashMap<>();
    public static float lift(YukkuriEntity e){
        if(e.contactPose().equals("FIXED")){String name="restraint_frame_"+e.getVariant().assetId()+(e.hasVisibleAccessory()?"":"_bare");return LIFTS.computeIfAbsent(name,WoundPlacement::fixedLift);}
        return angle(e)==0?0:WoundSurface.get(e.subspecies().assetId()).contactLift(angle(e),e.hasVisibleAccessory(),e.visibleHairLoss());
    }
    private static float fixedLift(String name){
        String path="/assets/mcyukkuri/yukkuri_props/"+name+".json";
        var stream=WoundPlacement.class.getResourceAsStream(path);
        if(stream==null)throw new IllegalStateException("Missing fitted operation resource: "+path);
        try(var reader=new InputStreamReader(stream,StandardCharsets.UTF_8)){
            var value=JsonParser.parseReader(reader).getAsJsonObject().get("body_lift");
            if(value==null)throw new IllegalStateException("Missing body_lift in "+path);
            float lift=value.getAsFloat();
            if(!Float.isFinite(lift)||lift<=0)throw new IllegalStateException("Invalid body_lift in "+path);
            return lift;
        }catch(IOException ex){throw new UncheckedIOException(ex);}
    }
    /** Model units to an entity-relative world vector; keep world translation in double precision. */
    private static float bodyYaw(YukkuriEntity e) {
        var state=e.aquaticSelfRescueState();
        return e.getBehaviorId().startsWith(Behaviors.WATER_HAT_PREFIX)&&state.contains("BodyYaw")?state.getFloat("BodyYaw"):e.yBodyRot;
    }
    public static Matrix4f transform(YukkuriEntity e){float scale=e.getScale()*e.growthScale();var m=new Matrix4f().rotateY((float)Math.toRadians(180-bodyYaw(e))).scale(scale);
        if(angle(e)!=0)m.translate(0,lift(e),0).rotateX((float)Math.toRadians(angle(e)));
        if(e.contactPose().equals("GRIP"))m.scale(1.1f,.85f,1.1f);
        boolean open=!e.profile().houseBody()&&e.body()!=null&&e.body().wounds().stream().anyMatch(BodySigns::open);
        float health=e.getMaxHealth()<=0?1:e.getHealth()/e.getMaxHealth();
        m.scale(BodySigns.sagWidth(health,open),BodySigns.sagHeight(health,open),BodySigns.sagWidth(health,open));
        String behavior=e.getBehaviorId();float t=Math.max(0,e.level().getGameTime()-e.getBehaviorStartTick());
        float y=WoundSurface.get(e.subspecies().assetId()).hasClip("sleep")?1:switch(behavior){
                case Behaviors.SLEEP_ENTER->1-Math.min(1,t/20)*.04f;case Behaviors.SLEEP->.96f;case Behaviors.WAKE->.96f+Math.min(1,t/20)*.04f;default->1;};
        return m.scale(1,y,1).scale(1f/16);
    }
    private record PoseFrame(String action,float seconds) {}
    private static PoseFrame poseFrame(YukkuriEntity e) {
        boolean operated=!e.contactPose().equals("NONE")&&!e.contactPose().equals("SLIDE");
        if(operated)return new PoseFrame("idle",0);
        var rescue=SelfRescueHatGeometry.motion(e,0);
        if(rescue!=null&&WoundSurface.get(e.subspecies().assetId()).hasClip(rescue.clip()))return new PoseFrame(rescue.clip(),rescue.seconds());
        return new PoseFrame(e.getAnimationId(),Math.max(0,(e.level().getGameTime()-e.getAnimationStartTick())/20f));
    }
    public static Matrix4f[] pose(YukkuriEntity e){var frame=poseFrame(e);
            return WoundSurface.get(e.subspecies().assetId()).pose(frame.action(),frame.seconds(),
                    HatVessel.hatMultiplier(e.subspeciesFlags()),e.contactPose(),e.contactTurn(),SelfRescueHatGeometry.wornMatrix(e,1,bodyYaw(e)));}
    public static WoundSurface.AttachmentLifts pregnancyLifts(YukkuriEntity e) {
        var frame=poseFrame(e);
        return WoundSurface.get(e.subspecies().assetId()).pregnancyLifts(frame.action(),frame.seconds(),e.contactPose(),e.contactTurn(),e.livePregnancyProgress());
    }
    public static WoundSurface.Hit hit(YukkuriEntity e,DamageSource source){return hit(e,source,true);}
    /** Lowest posed tissue touching the supporting floor, including the body's actual rolling angle. */
    public static WoundSurface.Hit groundContact(YukkuriEntity e) {
        if(!e.onGround())return null;
        var mesh=WoundSurface.get(e.subspecies().assetId());var world=pose(e);var transform=transform(e);var wounds=e.body().wounds();
        int lowest=-1;float height=Float.POSITIVE_INFINITY,pregnancy=e.livePregnancyProgress();
        for(int i=0;i<mesh.skinCount;i+=3){
            var a=mesh.posed(i,world,wounds);var b=mesh.posed(i+1,world,wounds);var c=mesh.posed(i+2,world,wounds);
            if(pregnancy>.001f){LiveBirthGeometry.deform(a,pregnancy,0);LiveBirthGeometry.deform(b,pregnancy,0);LiveBirthGeometry.deform(c,pregnancy,0);}
            var center=a.add(b).add(c).div(3);
            transform.transformPosition(center);
            if(center.y<height){height=center.y;lowest=i;}
        }
        return lowest<0?null:new WoundSurface.Hit(lowest,1f/3,1f/3,0);
    }
    /** A collision callback without a source entity can still supply the actual contacting block's direction. */
    public static WoundSurface.Hit contact(YukkuriEntity e,Vec3 contact) {
        var center=e.getBoundingBox().getCenter();var outward=contact.subtract(center);
        if(outward.lengthSqr()<1e-8)outward=e.getDeltaMovement().scale(-1);
        if(outward.lengthSqr()<1e-8)return null;
        outward=outward.normalize();
        var inverse=transform(e).invert();var origin=inverse.transformPosition(center.add(outward.scale(3)).subtract(e.position()).toVector3f());
        var direction=inverse.transformDirection(outward.scale(-1).toVector3f()).normalize();
        return WoundSurface.get(e.subspecies().assetId()).ray(origin,direction,pose(e),e.body().wounds(),e.livePregnancyProgress());
    }
    /** The actual source ray in posed model space, without centre-aim correction. */
    public record SourceRay(Vector3f origin,Vector3f direction,Matrix4f[] pose){}
    public static SourceRay sourceRay(YukkuriEntity e,DamageSource source){var inverse=transform(e).invert();Vec3 origin,direction;
        var direct=source.getDirectEntity();
        if(direct instanceof Projectile){direction=direct.getDeltaMovement().normalize();origin=direct.position().subtract(direction.scale(2));}
        else if(source.getEntity() instanceof LivingEntity attacker){origin=attacker.getEyePosition();direction=attacker.getLookAngle();}
        else {origin=source.getSourcePosition();if(origin==null)origin=e.position().add(0,source.is(DamageTypeTags.IS_FALL)?-2:2,0);direction=
                e.getBoundingBox().getCenter().subtract(origin).normalize();}
        var o=inverse.transformPosition(origin.subtract(e.position()).toVector3f());var d=inverse.transformDirection(direction.toVector3f()).normalize();
        return new SourceRay(o,d,pose(e));
    }
    /** Centre-aim fallback may place a broad-box wound, but must never manufacture a precise core hit. */
    public static WoundSurface.Hit hit(YukkuriEntity e,DamageSource source,boolean fallback){var mesh=WoundSurface.get(e.subspecies().assetId());
        var ray=sourceRay(e,source);var o=ray.origin();var d=ray.direction();var world=ray.pose();var hit=mesh.ray(o,d,world,e.body().wounds(),e.livePregnancyProgress());
        // Vanilla can accept broad-box hits outside the visible skin (e.g. a mob's melee aim).
        if(hit==null&&fallback){var center=transform(e).invert().transformPosition(e.getBoundingBox().getCenter().subtract(e.position()).toVector3f());hit=mesh.ray(o,center.sub(o).normalize(),world,e.body().wounds(),e.livePregnancyProgress());}
        return hit;
    }
    public static Vec3 outlet(YukkuriEntity e,BodyWound wound){var mesh=WoundSurface.get(e.subspecies().assetId());var p=mesh.pointDisplay(wound,
            pose(e),e.body().wounds());transform(e).transformPosition(p);return e.position().add(new Vec3(p));}
    public static Vec3 outward(YukkuriEntity e,BodyWound w){var mesh=WoundSurface.get(e.subspecies().assetId());var world=pose(e);var site=
            mesh.displaySite(w);var a=mesh.posedDisplay(site.triangle(),world,e.body().wounds());var b=mesh.posedDisplay(site.triangle()+1,world,
            e.body().wounds());var c=mesh.posedDisplay(site.triangle()+2,world,e.body().wounds());var n=b.sub(a).cross(c.sub(a));transform(e).transformDirection(n).normalize();return new Vec3(n);}
    private WoundPlacement(){}
}
