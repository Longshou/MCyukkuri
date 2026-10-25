package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.mind.EmotionEvent;
import io.github.longshou.mcyukkuri.status.GameplayLog;

import com.google.gson.*;
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
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import org.joml.Vector3f;
import io.github.longshou.mcyukkuri.body.WoundSurface;
import io.github.longshou.mcyukkuri.human.HumanCommerce;
import io.github.longshou.mcyukkuri.interaction.CareMenu;
import java.io.IOException;
import java.io.UncheckedIOException;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;

/** Real passenger custody on the authored hat brim. Geometry and authority are checked before pickup. */
public final class HatCarry {
    public static final JsonObject GEOMETRY=load();
    private static JsonObject load(){
        try(var stream=HatCarry.class.getResourceAsStream("/assets/mcyukkuri/yukkuri_presentation/hat_interactions.json")){
            if(stream==null)throw new IllegalStateException("Missing hat interaction geometry");
            return JsonParser.parseReader(new InputStreamReader(stream,StandardCharsets.UTF_8)).getAsJsonObject();
        }catch(IOException e){throw new UncheckedIOException(e);}
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
    private static boolean obstacleHit(JsonObject hat,JsonObject child,double[] seat,double z,double ratio){
        for(var raw:child.getAsJsonArray("parts")){
            double[] part=values(raw.getAsJsonArray());
            for(var obstacle:hat.getAsJsonArray("obstacles")){
                double[] box=values(obstacle.getAsJsonArray());boolean overlap=true;
                double[] at={seat[0],seat[1],z};
                for(int k=0;k<3;k++)if(at[k]+part[k+3]*ratio<=box[k]+.005||at[k]+part[k]*ratio>=box[k+3]-.005){overlap=false;break;}
                if(overlap)return true;
            }
        }
        return false;
    }
    private static final java.util.Map<String,double[]> LAYOUTS=new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * Seats of several children on one brim, in boarding order: each child takes the first spot along the brim lip where its
     * real foot box lies inside the support area, clears the crown and does not overlap the children before it; the group
     * is then centred on the lip. Entry i is the child's seat offset along z (model units, from the authored seat), or NaN
     * when it does not fit. Pure geometry, so server and client agree (project design for the packing order and gap).
     */
    public static double[] layout(String hatId,java.util.List<String> childIds,java.util.List<Double> ratios){
        var key=new StringBuilder(hatId);
        for(int i=0;i<childIds.size();i++)key.append('|').append(childIds.get(i)).append(':').append(Math.round(ratios.get(i)*1000));
        var cached=LAYOUTS.get(key.toString());if(cached!=null)return cached;
        int n=childIds.size();double[] out=new double[n];java.util.Arrays.fill(out,Double.NaN);
        var hat=shape(hatId);
        if(hat!=null&&n>0){
            double[] seat=values(hat.getAsJsonArray("seat")),support=values(hat.getAsJsonArray("support"));
            double margin=hat.get("margin").getAsDouble(),zLow=support[2]+margin,zHigh=support[5]-margin,gap=.05;
            var placed=new java.util.ArrayList<double[]>();double[] absolute=new double[n];
            for(int i=0;i<n;i++){
                var shape=GEOMETRY.getAsJsonObject("children").getAsJsonObject(childIds.get(i));
                double ratio=Math.round(ratios.get(i)*1000)/1000.0;
                if(shape==null||!Double.isFinite(ratio)||ratio<=0)continue;
                double[] foot=values(shape.getAsJsonArray("foot"));
                double x0=seat[0]+foot[0]*ratio,x1=seat[0]+foot[3]*ratio;
                if(x0<support[0]+margin||x1>support[3]-margin)continue;
                double low=zLow-foot[2]*ratio,high=zHigh-foot[5]*ratio;
                if(high<low-1e-9)continue;
                for(int step=0;;step++){
                    double z=Math.min(high,low+step*.1);
                    double z0=z+foot[2]*ratio,z1=z+foot[5]*ratio;boolean free=true;
                    for(var other:placed)if(x0<other[1]-.02&&x1>other[0]+.02&&z0<other[3]+gap&&z1>other[2]-gap){free=false;break;}
                    if(free&&!obstacleHit(hat,shape,seat,z,ratio)){
                        placed.add(new double[]{x0,x1,z0,z1,i});absolute[i]=z;out[i]=z-seat[2];break;
                    }
                    if(z>=high)break;
                }
            }
            if(!placed.isEmpty()){
                double lowest=Double.MAX_VALUE,highest=-Double.MAX_VALUE;
                for(var p:placed){lowest=Math.min(lowest,p[2]);highest=Math.max(highest,p[3]);}
                double shift=((zHigh-highest)-(lowest-zLow))/2;boolean ok=Math.abs(shift)>1e-6;
                for(var p:placed){
                    int i=(int)p[4];
                    if(ok&&obstacleHit(hat,GEOMETRY.getAsJsonObject("children").getAsJsonObject(childIds.get(i)),seat,absolute[i]+shift,Math.round(ratios.get(i)*1000)/1000.0))ok=false;
                }
                if(ok)for(var p:placed)out[(int)p[4]]+=shift;
            }
        }
        if(LAYOUTS.size()>512)LAYOUTS.clear();
        LAYOUTS.put(key.toString(),out);
        return out;
    }
    public static boolean canSeat(YukkuriEntity carrier,Entity passenger){
        if(!(passenger instanceof YukkuriEntity child)||carrier==child||!carrier.isAlive()||!child.isAlive()
                ||carrier.level()!=child.level()||!carrier.family().adult()||child.family().adult()
                ||!hasHat(carrier)||!carrier.life().hasAccessory()||carrier.isPassenger()||carrier.hasPassenger(child)
                ||child.isPassenger()||child.isVehicle()||carrier.isInWaterOrBubble()||child.isInWaterOrBubble()
                ||carrier.operations().active()||child.operations().active())return false;
        return ParentCarry.resolve(carrier,child).mode()==ParentCarry.Mode.HAT&&seatClear(carrier,child);
    }
    public static boolean pickUp(YukkuriEntity carrier,Player player){
        if(!(carrier.level() instanceof ServerLevel)||!CareMenu.valid(player,carrier)
                ||!HumanCommerce.mayCapture(player,carrier))return false;
        var child=carrier.level().getEntitiesOfClass(YukkuriEntity.class,carrier.getBoundingBox().inflate(1.5),
                y->canSeat(carrier,y)&&player.hasLineOfSight(y)&&HumanCommerce.mayCapture(player,y))
                .stream().min(Comparator.comparingDouble(carrier::distanceToSqr)).orElse(null);
        if(child==null||!carrier.hasLineOfSight(child))return false;
        child.life().interrupt();child.family().stop();child.social().stop();child.dailyLife().stop();child.getNavigation().stop();
        carrier.family().stop();carrier.social().stop();carrier.dailyLife().stop();
        if(!child.startRiding(carrier,true))return false;
        carrier.setPersistenceRequired();child.setPersistenceRequired();child.fallDistance=0;
        carrier.family().carry().manual(child);
        GameplayLog.event(carrier,"care","hat passenger picked up child={} player={}",child.getUUID(),player.getUUID());
        carrier.mind().felt(EmotionEvent.COMFORTED); carrier.showReaction("happy","gentle",40);return true;
    }
    public static String carryAction(YukkuriView carrier){return carrier.getAnimationId().equals("waddle")?"waddle":"idle";}
    public static Matrix4f hatPose(YukkuriView carrier,float partial){
        float elapsed=Math.max(0,(carrier.level().getGameTime()-carrier.getAnimationStartTick()+partial)/20f);
        return WoundSurface.get(carrier.assetId()).pose(carryAction(carrier),elapsed)[shape(carrier.assetId()).get("bone").getAsInt()];
    }
    public static Matrix4f riderRotation(YukkuriView carrier,float partial){
        float yaw=carrier.level().isClientSide?Mth.rotLerp(partial,carrier.previousBodyYaw(),carrier.bodyYaw()):carrier.bodyYaw();
        return new Matrix4f().rotationY((180-yaw)*(float)Math.PI/180).mul(hatPose(carrier,partial)).setTranslation(0,0,0);
    }
    public static Vec3 seatWorld(YukkuriView carrier,float partial){return seatWorld(carrier,partial,0);}
    /** The brim seat moved {@code dz} along the lip (model units); several carried children each take their own. */
    public static Vec3 seatWorld(YukkuriView carrier,float partial,double dz){
        var hat=shape(carrier.assetId());if(hat==null)return carrier.position();
        var seat=values(hat.getAsJsonArray("seat"));var point=new Vector3f((float)seat[0],(float)seat[1],(float)(seat[2]+dz));
        hatPose(carrier,partial).transformPosition(point);
        float yaw=carrier.level().isClientSide?Mth.rotLerp(partial,carrier.previousBodyYaw(),carrier.bodyYaw()):carrier.bodyYaw();
        point.mul(carrier.growthScale()*carrier.getScale()/16f).rotateY((180-yaw)*(float)Math.PI/180);
        var base=carrier.level().isClientSide?carrier.getPosition(partial):carrier.position();
        return base.add(point.x,point.y,point.z);
    }
    private static boolean seatClear(YukkuriEntity carrier,YukkuriEntity child){
        var geometry=GEOMETRY.getAsJsonObject("children").getAsJsonObject(child.subspecies().assetId());
        if(geometry==null)return false;
        double[] bounds={Double.MAX_VALUE,Double.MAX_VALUE,Double.MAX_VALUE,-Double.MAX_VALUE,-Double.MAX_VALUE,-Double.MAX_VALUE};
        for(var part:geometry.getAsJsonArray("parts")){var b=values(part.getAsJsonArray());for(int k=0;k<3;k++){bounds[k]=Math.min(bounds[k],b[k]);bounds[k+3]=Math.max(bounds[k+3],b[k+3]);}}
        var rotation=riderRotation(carrier,0);var seat=seatWorld(carrier,0,ParentCarry.resolve(carrier,child).dz());
        double[] world={Double.MAX_VALUE,Double.MAX_VALUE,Double.MAX_VALUE,-Double.MAX_VALUE,-Double.MAX_VALUE,-Double.MAX_VALUE};
        for(int corner=0;corner<8;corner++){
            var point=new Vector3f((float)bounds[(corner&1)==0?0:3],(float)bounds[(corner&2)==0?1:4],(float)bounds[(corner&4)==0?2:5]).mul(child.growthScale()*child.getScale()/16);
            rotation.transformPosition(point);double[] p={seat.x+point.x,seat.y+point.y,seat.z+point.z};
            for(int k=0;k<3;k++){world[k]=Math.min(world[k],p[k]);world[k+3]=Math.max(world[k+3],p[k]);}
        }
        var box=new AABB(world[0],world[1],world[2],world[3],world[4],world[5]);
        return !carrier.level().getBlockCollisions(child,box).iterator().hasNext()&&!carrier.level().containsAnyLiquid(box);
    }
    static Vec3 landing(YukkuriEntity carrier,YukkuriEntity child){
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
        // Every rider is set down where it sits (in place, nudged clear of blocks only if needed), then logged by ParentCarry.
        if(!(carrier.level() instanceof ServerLevel))return false;
        return carrier.family().carry().putDown("operation",0);
    }
    public static void tick(EntityTickEvent.Post event){
        if(!(event.getEntity() instanceof YukkuriEntity carrier)||carrier.level().isClientSide||!hasHat(carrier)||!carrier.isVehicle())return;
        for(var child:carrier.family().carry().riders()){
            if(ParentCarry.resolve(carrier,child).mode()!=ParentCarry.Mode.HAT)continue;
            boolean unsafe=!carrier.isAlive()||!carrier.life().hasAccessory()||carrier.isInWaterOrBubble()||carrier.isOnFire()
                    ||carrier.operations().active()||child.family().adult()||!seatClear(carrier,child);
            if(unsafe){
                // Set the child down where it sits (only nudged clear of blocks); the carrier then holds it off briefly before any re-lift.
                carrier.family().carry().putDown(child,"hat_unsafe",ParentCarry.HOLD_TICKS);
                continue;
            }
            child.fallDistance=0;child.getNavigation().stop();
        }
    }
    private HatCarry(){}
}
