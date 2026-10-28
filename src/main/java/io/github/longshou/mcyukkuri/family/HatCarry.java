package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.mind.EmotionEvent;
import io.github.longshou.mcyukkuri.status.GameplayLog;

import com.google.gson.*;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriView;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
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
    /** One child of this form and size has a place somewhere on the brim ring. */
    public static boolean fits(String parentId,String childId,double ratio){
        return !Double.isNaN(layout(parentId,java.util.List.of(childId),java.util.List.of(ratio))[0]);
    }
    private static final java.util.Map<String,Double> HALF_WIDTHS=new java.util.concurrent.ConcurrentHashMap<>();
    /** Half width of a child's authored boxes, hair and ribbons included (model units); Reimu's when the form has none. */
    public static double childHalfWidth(String childId){
        return HALF_WIDTHS.computeIfAbsent(childId,id->{
            var children=GEOMETRY.getAsJsonObject("children");
            var child=children.has(id)?children.getAsJsonObject(id):children.getAsJsonObject("reimu");
            double half=0;
            for(var part:child.getAsJsonArray("parts")){var b=values(part.getAsJsonArray());half=Math.max(half,Math.max(Math.abs(b[0]),Math.abs(b[3])));}
            return half;
        });
    }
    private static final java.util.Map<String,double[]> LAYOUTS=new java.util.concurrent.ConcurrentHashMap<>();
    /** Where a child sits on the brim ring: centre of its feet on the brim top (hat-bone model units) and its facing (degrees). */
    public record RingSeat(double x,double y,double z,float yaw){}
    /**
     * The seat at ring position {@code s}: model units along the ring line from the middle of the front brim, positive
     * towards +x, round the side and on along the back. The child faces outward: forward on the front, sideways on the
     * sides, backward at the back; it stands on the highest brim face under the centre of its feet.
     */
    public static RingSeat ringSeat(JsonObject hat,double s){
        var ring=hat.getAsJsonObject("ring");
        double front=ring.get("front").getAsDouble(),back=ring.get("back").getAsDouble(),side=ring.get("side").getAsDouble();
        double along=Math.abs(s),sign=s<0?-1:1,x,z;float yaw;
        if(along<=side){x=s;z=front;yaw=0;}
        else if(along<=side+back-front){x=sign*side;z=front+along-side;yaw=sign>0?-90:90;}
        else{x=sign*Math.max(0,side-(along-side-(back-front)));z=back;yaw=180;}
        double top=-Double.MAX_VALUE;
        for(var raw:ring.getAsJsonArray("supports")){var r=values(raw.getAsJsonArray());if(x>=r[0]&&x<=r[2]&&z>=r[1]&&z<=r[3])top=Math.max(top,r[4]);}
        if(top==-Double.MAX_VALUE)top=values(hat.getAsJsonArray("seat"))[1]-.02;
        return new RingSeat(x,top+.02,z,yaw);
    }
    /** One ring half: half the front, one side and half the back (model units). */
    private static double ringHalf(JsonObject hat){
        var ring=hat.getAsJsonObject("ring");
        return 2*ring.get("side").getAsDouble()+ring.get("back").getAsDouble()-ring.get("front").getAsDouble();
    }
    /** A box (x0,y0,z0,x1,y1,z1) turned by a multiple of 90 degrees about y, as the child model is turned to face outward. */
    private static double[] turned(double[] b,float yaw){
        return switch(Math.round(yaw)){
            case 180 -> new double[]{-b[3],b[1],-b[5],-b[0],b[4],-b[2]};
            case -90 -> new double[]{-b[5],b[1],b[0],-b[2],b[4],b[3]};
            case 90 -> new double[]{b[2],b[1],-b[3],b[5],b[4],-b[0]};
            default -> b;
        };
    }
    private static boolean supported(JsonArray supports,double x,double z,double margin){
        for(var raw:supports){var r=values(raw.getAsJsonArray());if(x>=r[0]+margin&&x<=r[2]-margin&&z>=r[1]+margin&&z<=r[3]-margin)return true;}
        return false;
    }
    /**
     * Seats of several children on the brim ring around the crown, in boarding order (project design): each child takes the
     * first free place going out from the middle of the front brim, then along the sides from front to back, then across the
     * back, where its feet lie on the real brim top and its whole body (hair and ribbons
     * included) keeps 0.05 model units from the hat, the bow and every child before it. Children placed earlier keep their places. Entry i is
     * the child's ring position for {@link #ringSeat}, or NaN when it does not fit anywhere. Pure geometry, so server and
     * client agree.
     */
    public static double[] layout(String hatId,java.util.List<String> childIds,java.util.List<Double> ratios){
        var key=new StringBuilder(hatId);
        for(int i=0;i<childIds.size();i++)key.append('|').append(childIds.get(i)).append(':').append(Math.round(ratios.get(i)*1000));
        var cached=LAYOUTS.get(key.toString());if(cached!=null)return cached;
        int n=childIds.size();double[] out=new double[n];java.util.Arrays.fill(out,Double.NaN);
        var hat=shape(hatId);
        if(hat!=null&&hat.has("ring")&&n>0){
            double margin=hat.get("margin").getAsDouble(),gap=.05,half=ringHalf(hat);
            var supports=hat.getAsJsonObject("ring").getAsJsonArray("supports");
            var obstacles=new java.util.ArrayList<double[]>();for(var raw:hat.getAsJsonArray("obstacles"))obstacles.add(values(raw.getAsJsonArray()));
            var placed=new java.util.ArrayList<double[]>();
            for(int i=0;i<n;i++){
                var shape=GEOMETRY.getAsJsonObject("children").getAsJsonObject(childIds.get(i));
                double ratio=Math.round(ratios.get(i)*1000)/1000.0;
                if(shape==null||!Double.isFinite(ratio)||ratio<=0)continue;
                double[] foot=values(shape.getAsJsonArray("foot"));
                var parts=new java.util.ArrayList<double[]>();
                double[] whole={Double.MAX_VALUE,0,Double.MAX_VALUE,-Double.MAX_VALUE,0,-Double.MAX_VALUE};
                for(var raw:shape.getAsJsonArray("parts")){var b=values(raw.getAsJsonArray());parts.add(b);
                    whole[0]=Math.min(whole[0],b[0]);whole[2]=Math.min(whole[2],b[2]);whole[3]=Math.max(whole[3],b[3]);whole[5]=Math.max(whole[5],b[5]);}
                for(int step=0;step*.05<=half&&Double.isNaN(out[i]);step++)for(int sign=1;sign>=-1&&Double.isNaN(out[i]);sign-=2){
                    if(step==0&&sign<0)continue;
                    double s=sign*step*.05;var seat=ringSeat(hat,s);
                    // Feet on the brim top.
                    var f=turned(foot,seat.yaw());boolean ok=true;
                    for(int u=0;u<3&&ok;u++)for(int v=0;v<3&&ok;v++)
                        ok=supported(supports,seat.x()+(f[0]+(f[3]-f[0])*u/2)*ratio,seat.z()+(f[2]+(f[5]-f[2])*v/2)*ratio,margin);
                    if(!ok)continue;
                    // Whole bodies apart.
                    var w=turned(whole,seat.yaw());
                    double x0=seat.x()+w[0]*ratio,x1=seat.x()+w[3]*ratio,z0=seat.z()+w[2]*ratio,z1=seat.z()+w[5]*ratio;
                    for(var other:placed)if(x0<other[1]+gap&&x1>other[0]-gap&&z0<other[3]+gap&&z1>other[2]-gap){ok=false;break;}
                    if(!ok)continue;
                    // The same 0.05 clear of the crown, band and bow.
                    double[] at={seat.x(),seat.y(),seat.z()};
                    for(int p=0;p<parts.size()&&ok;p++){var t=turned(parts.get(p),seat.yaw());
                        for(var box:obstacles){boolean overlap=true;
                            for(int k=0;k<3;k++)if(at[k]+t[k+3]*ratio<=box[k]-gap||at[k]+t[k]*ratio>=box[k+3]+gap){overlap=false;break;}
                            if(overlap){ok=false;break;}}}
                    if(!ok)continue;
                    placed.add(new double[]{x0,x1,z0,z1});out[i]=s;
                }
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
    public static Matrix4f riderRotation(YukkuriView carrier,float partial){return riderRotation(carrier,partial,0);}
    /** Orientation of a child at ring position {@code s}: the carrier, its hat bone, then the seat's outward facing. */
    public static Matrix4f riderRotation(YukkuriView carrier,float partial,double s){
        float yaw=carrier.level().isClientSide?Mth.rotLerp(partial,carrier.previousBodyYaw(),carrier.bodyYaw()):carrier.bodyYaw();
        var hat=shape(carrier.assetId());float facing=hat!=null&&hat.has("ring")?ringSeat(hat,s).yaw():0;
        return new Matrix4f().rotationY((180-yaw)*(float)Math.PI/180).mul(hatPose(carrier,partial)).setTranslation(0,0,0).rotateY(facing*(float)Math.PI/180);
    }
    public static Vec3 seatWorld(YukkuriView carrier,float partial){return seatWorld(carrier,partial,0);}
    /** The brim seat at ring position {@code s} (see {@link #layout}); several carried children each take their own. */
    public static Vec3 seatWorld(YukkuriView carrier,float partial,double s){
        var hat=shape(carrier.assetId());if(hat==null)return carrier.position();
        Vector3f point;
        if(hat.has("ring")){var seat=ringSeat(hat,s);point=new Vector3f((float)seat.x(),(float)seat.y(),(float)seat.z());}
        else{var seat=values(hat.getAsJsonArray("seat"));point=new Vector3f((float)seat[0],(float)seat[1],(float)(seat[2]+s));}
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
        double ring=ParentCarry.resolve(carrier,child).dz();var rotation=riderRotation(carrier,0,ring);var seat=seatWorld(carrier,0,ring);
        double[] world={Double.MAX_VALUE,Double.MAX_VALUE,Double.MAX_VALUE,-Double.MAX_VALUE,-Double.MAX_VALUE,-Double.MAX_VALUE};
        for(int corner=0;corner<8;corner++){
            var point=new Vector3f((float)bounds[(corner&1)==0?0:3],(float)bounds[(corner&2)==0?1:4],(float)bounds[(corner&4)==0?2:5]).mul(child.growthScale()*child.getScale()/16);
            rotation.transformPosition(point);double[] p={seat.x+point.x,seat.y+point.y,seat.z+point.z};
            for(int k=0;k<3;k++){world[k]=Math.min(world[k],p[k]);world[k+3]=Math.max(world[k+3],p[k]);}
        }
        var box=new AABB(world[0],world[1],world[2],world[3],world[4],world[5]);
        return !carrier.level().getBlockCollisions(child,box).iterator().hasNext()&&!carrier.level().containsAnyLiquid(box);
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
