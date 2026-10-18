package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.mind.EmotionEvent;

import com.google.gson.*;
import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriView;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import org.joml.Vector3f;

/** Real passenger custody on the authored hat brim. Geometry and authority are checked before pickup. */
@EventBusSubscriber(modid=MCYukkuri.MOD_ID)
public final class HatCarry {
    public static final JsonObject GEOMETRY=load();
    private static JsonObject load(){
        try(var stream=HatCarry.class.getResourceAsStream("/assets/mcyukkuri/yukkuri_presentation/hat_interactions.json")){
            if(stream==null)throw new IllegalStateException("Missing hat interaction geometry");
            return JsonParser.parseReader(new InputStreamReader(stream,StandardCharsets.UTF_8)).getAsJsonObject();
        }catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}
    }
    public static JsonObject shape(String id){return GEOMETRY.getAsJsonObject("variants").getAsJsonObject(id);}
    public static boolean hasHat(YukkuriView carrier){return shape(carrier.assetId())!=null;}
    public static boolean riding(YukkuriEntity carrier,Entity child){return hasHat(carrier)&&child instanceof YukkuriEntity&&carrier.hasPassenger(child);}
    public static boolean visibleRiding(YukkuriView carrier,YukkuriView child){return hasHat(carrier)&&carrier.hasPassengerId(child.getUUID());}
    private static double[] values(JsonArray a){double[] out=new double[a.size()];for(int i=0;i<out.length;i++)out[i]=a.get(i).getAsDouble();return out;}
    public static boolean fits(String parentId,String childId,double ratio){
        var hat=shape(parentId);var child=GEOMETRY.getAsJsonObject("children").getAsJsonObject(childId);
        if(hat==null||child==null||!Double.isFinite(ratio)||ratio<=0)return false;
        double[] seat=values(hat.getAsJsonArray("seat")),support=values(hat.getAsJsonArray("support")),foot=values(child.getAsJsonArray("foot"));
        double margin=hat.get("margin").getAsDouble();
        for(int axis:new int[]{0,2})if(seat[axis]+foot[axis]*ratio<support[axis]+margin||seat[axis]+foot[axis+3]*ratio>support[axis+3]-margin)return false;
        for(var raw:child.getAsJsonArray("parts")){
            double[] part=values(raw.getAsJsonArray());
            for(var obstacle:hat.getAsJsonArray("obstacles")){
                double[] box=values(obstacle.getAsJsonArray());boolean overlap=true;
                for(int k=0;k<3;k++)if(seat[k]+part[k+3]*ratio<=box[k]+.005||seat[k]+part[k]*ratio>=box[k+3]-.005){overlap=false;break;}
                if(overlap)return false;
            }
        }
        return true;
    }
    public static boolean canSeat(YukkuriEntity carrier,Entity passenger){
        if(!(passenger instanceof YukkuriEntity child)||carrier==child||!carrier.isAlive()||!child.isAlive()
                ||carrier.level()!=child.level()||!carrier.family().adult()||child.family().adult()
                ||!hasHat(carrier)||!carrier.life().hasAccessory()||carrier.isPassenger()||!carrier.getPassengers().isEmpty()
                ||child.isPassenger()||child.isVehicle()||carrier.isInWaterOrBubble()||child.isInWaterOrBubble()
                ||carrier.operations().active()||child.operations().active())return false;
        double ratio=child.growthScale()*child.getScale()/(carrier.growthScale()*carrier.getScale());
        return fits(carrier.subspecies().assetId(),child.subspecies().assetId(),ratio)&&seatClear(carrier,child);
    }
    public static boolean pickUp(YukkuriEntity carrier,Player player){
        if(!(carrier.level() instanceof ServerLevel)||!io.github.longshou.mcyukkuri.interaction.CareMenu.valid(player,carrier)
                ||!io.github.longshou.mcyukkuri.human.HumanCommerce.mayCapture(player,carrier))return false;
        var child=carrier.level().getEntitiesOfClass(YukkuriEntity.class,carrier.getBoundingBox().inflate(1.5),
                y->canSeat(carrier,y)&&player.hasLineOfSight(y)&&io.github.longshou.mcyukkuri.human.HumanCommerce.mayCapture(player,y))
                .stream().min(Comparator.comparingDouble(carrier::distanceToSqr)).orElse(null);
        if(child==null||!carrier.hasLineOfSight(child))return false;
        child.life().interrupt();child.family().stop();child.social().stop();child.dailyLife().stop();child.getNavigation().stop();
        carrier.family().stop();carrier.social().stop();carrier.dailyLife().stop();
        if(!child.startRiding(carrier,true))return false;
        carrier.setPersistenceRequired();child.setPersistenceRequired();child.fallDistance=0;
        carrier.mind().felt(EmotionEvent.COMFORTED); carrier.showReaction("happy","gentle",40);return true;
    }
    public static String carryAction(YukkuriView carrier){return carrier.getAnimationId().equals("waddle")?"waddle":"idle";}
    public static org.joml.Matrix4f hatPose(YukkuriView carrier,float partial){
        float elapsed=Math.max(0,(carrier.level().getGameTime()-carrier.getAnimationStartTick()+partial)/20f);
        return io.github.longshou.mcyukkuri.body.WoundSurface.get(carrier.assetId()).pose(carryAction(carrier),elapsed)[shape(carrier.assetId()).get("bone").getAsInt()];
    }
    public static org.joml.Matrix4f riderRotation(YukkuriView carrier,float partial){
        float yaw=carrier.level().isClientSide?net.minecraft.util.Mth.rotLerp(partial,carrier.previousBodyYaw(),carrier.bodyYaw()):carrier.bodyYaw();
        return new org.joml.Matrix4f().rotationY((180-yaw)*(float)Math.PI/180).mul(hatPose(carrier,partial)).setTranslation(0,0,0);
    }
    public static Vec3 seatWorld(YukkuriView carrier,float partial){
        var hat=shape(carrier.assetId());if(hat==null)return carrier.position();
        var seat=values(hat.getAsJsonArray("seat"));var point=new Vector3f((float)seat[0],(float)seat[1],(float)seat[2]);
        hatPose(carrier,partial).transformPosition(point);
        float yaw=carrier.level().isClientSide?net.minecraft.util.Mth.rotLerp(partial,carrier.previousBodyYaw(),carrier.bodyYaw()):carrier.bodyYaw();
        point.mul(carrier.growthScale()*carrier.getScale()/16f).rotateY((180-yaw)*(float)Math.PI/180);
        var base=carrier.level().isClientSide?carrier.getPosition(partial):carrier.position();
        return base.add(point.x,point.y,point.z);
    }
    private static boolean seatClear(YukkuriEntity carrier,YukkuriEntity child){
        var geometry=GEOMETRY.getAsJsonObject("children").getAsJsonObject(child.subspecies().assetId());
        if(geometry==null)return false;
        double[] bounds={Double.MAX_VALUE,Double.MAX_VALUE,Double.MAX_VALUE,-Double.MAX_VALUE,-Double.MAX_VALUE,-Double.MAX_VALUE};
        for(var part:geometry.getAsJsonArray("parts")){var b=values(part.getAsJsonArray());for(int k=0;k<3;k++){bounds[k]=Math.min(bounds[k],b[k]);bounds[k+3]=Math.max(bounds[k+3],b[k+3]);}}
        var rotation=riderRotation(carrier,0);var seat=seatWorld(carrier,0);
        double[] world={Double.MAX_VALUE,Double.MAX_VALUE,Double.MAX_VALUE,-Double.MAX_VALUE,-Double.MAX_VALUE,-Double.MAX_VALUE};
        for(int corner=0;corner<8;corner++){
            var point=new Vector3f((float)bounds[(corner&1)==0?0:3],(float)bounds[(corner&2)==0?1:4],(float)bounds[(corner&4)==0?2:5]).mul(child.growthScale()*child.getScale()/16);
            rotation.transformPosition(point);double[] p={seat.x+point.x,seat.y+point.y,seat.z+point.z};
            for(int k=0;k<3;k++){world[k]=Math.min(world[k],p[k]);world[k+3]=Math.max(world[k+3],p[k]);}
        }
        var box=new net.minecraft.world.phys.AABB(world[0],world[1],world[2],world[3],world[4],world[5]);
        return !carrier.level().getBlockCollisions(child,box).iterator().hasNext()&&!carrier.level().containsAnyLiquid(box);
    }
    private static Vec3 landing(YukkuriEntity carrier,YukkuriEntity child){
        for(int i=0;i<12;i++)for(int dy=1;dy>=-2;dy--){
            double angle=i*Math.PI/6,radius=(carrier.getBbWidth()+child.getBbWidth())/2+.3;
            var pos=BlockPos.containing(carrier.getX()+Math.cos(angle)*radius,carrier.getY()+dy,carrier.getZ()+Math.sin(angle)*radius);
            var support=pos.below();var state=carrier.level().getBlockState(support);
            if(!state.isFaceSturdy(carrier.level(),support,Direction.UP)||!carrier.level().getFluidState(pos).isEmpty())continue;
            Vec3 target=Vec3.atBottomCenterOf(pos);var box=child.getBoundingBox().move(target.subtract(child.position()));
            if(carrier.level().noCollision(child,box)&&!carrier.level().containsAnyLiquid(box))return target;
        }
        return null;
    }
    public static boolean putDown(YukkuriEntity carrier){
        if(!(carrier.level() instanceof ServerLevel))return false;
        var child=carrier.getFirstPassenger() instanceof YukkuriEntity y?y:null;if(child==null)return false;
        var target=landing(carrier,child);if(target==null)return false;
        child.stopRiding();child.moveTo(target.x,target.y,target.z,carrier.getYRot(),0);child.setDeltaMovement(Vec3.ZERO);child.fallDistance=0;return true;
    }
    @SubscribeEvent public static void tick(EntityTickEvent.Post event){
        if(!(event.getEntity() instanceof YukkuriEntity carrier)||carrier.level().isClientSide||!hasHat(carrier)||!carrier.isVehicle())return;
        var child=carrier.getFirstPassenger() instanceof YukkuriEntity y?y:null;if(child==null)return;
        double ratio=child.growthScale()*child.getScale()/(carrier.growthScale()*carrier.getScale());
        boolean unsafe=!carrier.isAlive()||!carrier.life().hasAccessory()||carrier.isInWaterOrBubble()||carrier.isOnFire()
                ||carrier.operations().active()||child.family().adult()||!fits(carrier.subspecies().assetId(),child.subspecies().assetId(),ratio);
        unsafe|=!seatClear(carrier,child);
        if(unsafe){
            if(!putDown(carrier)){
                // If solid ground is unavailable, release to free space and let normal gravity resume.
                Vec3 free=carrier.position();boolean found=false;
                for(int dy=0;dy<=2&&!found;dy++)for(int i=0;i<12&&!found;i++){
                    var candidate=carrier.position().add(Math.cos(i*Math.PI/6)*.8,dy*.5,Math.sin(i*Math.PI/6)*.8);
                    var box=child.getBoundingBox().move(candidate.subtract(child.position()));
                    if(!carrier.level().getBlockCollisions(child,box).iterator().hasNext()){free=candidate;found=true;}
                }
                child.stopRiding();child.setPos(free);child.setDeltaMovement(Vec3.ZERO);child.fallDistance=0;
            }
            return;
        }
        child.fallDistance=0;child.getNavigation().stop();
    }
    private HatCarry(){}
}
