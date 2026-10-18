package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import io.github.longshou.mcyukkuri.entity.YukkuriView;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import io.github.longshou.mcyukkuri.subspecies.SubspeciesLife;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.Direction;
import org.joml.Quaternionf;

/** Shared pregnancy deformation and front delivery route; adapted from the approved viviparous prototype. */
public final class LiveBirthGeometry {
    private static final Map<String,Vector3f> OUTLETS=new ConcurrentHashMap<>();
    private static final JsonObject BODY=readBodies();
    private static final JsonObject POSES=readPoses();
    private static JsonObject readPoses(){
        try(var stream=LiveBirthGeometry.class.getResourceAsStream("/assets/mcyukkuri/yukkuri_presentation/live_birth_pose.json")){
            if(stream==null)throw new IllegalStateException("Missing selected live birth poses");
            return JsonParser.parseReader(new InputStreamReader(stream,StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("variants");
        }catch(IOException ex){throw new UncheckedIOException(ex);}
    }
    private static Vector3f sample(JsonArray keys,float seconds,Vector3f fallback){
        if(keys==null||keys.isEmpty())return fallback;
        var left=keys.get(0).getAsJsonArray();var right=left;
        for(var entry:keys){right=entry.getAsJsonArray();if(right.get(0).getAsFloat()>=seconds)break;left=right;}
        float a=left.get(0).getAsFloat(),b=right.get(0).getAsFloat(),q=b>a?Math.clamp((seconds-a)/(b-a),0,1):0;
        return new Vector3f(left.get(1).getAsFloat(),left.get(2).getAsFloat(),left.get(3).getAsFloat())
                .lerp(new Vector3f(right.get(1).getAsFloat(),right.get(2).getAsFloat(),right.get(3).getAsFloat()),q);
    }
    /** Same exported root tracks and pivot as the visible mother at the release frame. */
    private static Vec3 releasePose(YukkuriEntity mother,Vec3 local){
        String id=mother.subspecies().assetId();
        if(!POSES.has(id))return local;
        var data=POSES.getAsJsonObject(id);var pivot=data.getAsJsonArray("pivot");
        var clips=data.getAsJsonObject("clips");
        var clip=clips.getAsJsonObject(mother.family().boatHome()!=null&&clips.has("boat_labor")?"boat_labor":"labor");
        float seconds=(STRAIN+EMERGENCE)/20f;
        var position=sample(clip.getAsJsonArray("position"),seconds,new Vector3f());
        var rotation=sample(clip.getAsJsonArray("rotation"),seconds,new Vector3f()).mul((float)Math.PI/180);
        var scale=sample(clip.getAsJsonArray("scale"),seconds,new Vector3f(1));
        var origin=new Vector3f(pivot.get(0).getAsFloat(),pivot.get(1).getAsFloat(),pivot.get(2).getAsFloat());
        float units=mother.growthScale()/16;
        var point=new Vector3f((float)local.x/units,(float)local.y/units,(float)local.z/units).sub(origin).mul(scale);
        new Quaternionf().rotationZYX(rotation.z,rotation.y,rotation.x).transform(point);
        point.add(origin).add(position).mul(units);return new Vec3(point);
    }
    private static JsonObject readBodies(){
        try(var stream=LiveBirthGeometry.class.getResourceAsStream("/assets/mcyukkuri/yukkuri_presentation/body_geometry.json")){
            if(stream==null)throw new IllegalStateException("Missing selected body geometry");
            return JsonParser.parseReader(new InputStreamReader(stream,StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("variants");
        }catch(IOException ex){throw new UncheckedIOException(ex);}
    }
    public static float modelHeight(String id){
        return BODY.has(id)?BODY.getAsJsonObject(id).get("height").getAsFloat():id.startsWith("house_")?3.82f:id.equals("remilia")||id.equals("flandre")?9.6f:8.48f*(id.endsWith("_bean")?1.55f:1);
    }
    /** A live newborn's size (GrowthSize, the life-stage size standard). */
    public static final float BABY_SCALE=GrowthSize.LIVE;
    /** Loaded server ticks: a lead-in of visible labour signs, then for each child a strain, two pushes and a rest.
     *  The sequence follows the reference panels; these durations are this project's presentation choices (original):
     *  the later children strain for less (STRAIN_LATER), as a practised, more tired mother does. */
    public static final int PREPARATION=100, STRAIN=28, STRAIN_LATER=20, EMERGENCE=64, REST=48,
            INTERVAL=STRAIN+EMERGENCE+REST, RECOVERY=160;
    /** Newborns catch in a hat over a readable arc of at least this many ticks (originally a 5 tick flash). */
    public static final int CATCH_ARC_TICKS=9;
    public static int strain(int index){return index<=0?STRAIN:STRAIN_LATER;}
    public static int interval(int index){return strain(index)+EMERGENCE+REST;}
    public static int cycleStart(int index){int start=PREPARATION;for(int i=0;i<Math.max(0,index);i++)start+=interval(i);return start;}
    public static int emergenceStart(int index){return cycleStart(index)+strain(index);}
    public static int birthTick(int index){return emergenceStart(index)+EMERGENCE;}
    /** Which child's cycle these delivery ticks are in; -1 during the lead-in. */
    public static int cycleIndex(float ticks){
        float rest=ticks-PREPARATION;if(rest<0)return -1;
        int index=0;while(rest>=interval(index)){rest-=interval(index);index++;}
        return index;
    }
    public static float cycleTick(float ticks){
        float rest=Math.max(0,ticks-PREPARATION);
        int index=0;while(rest>=interval(index)){rest-=interval(index);index++;}
        return rest;
    }
    /** The twelve-second body clips share this clock: frames 0..139 are one child's cycle (a later child's shorter strain
     *  is stretched onto the same frames), frames 140..239 the lead-in of labour signs before the first push. */
    public static int animationTick(float ticks){
        if(ticks<PREPARATION)return INTERVAL+(int)Math.max(0,ticks);
        int index=cycleIndex(ticks);float phase=cycleTick(ticks);
        if(index<=0)return (int)phase;
        return (int)(phase<STRAIN_LATER?phase*STRAIN/STRAIN_LATER:phase-STRAIN_LATER+STRAIN);
    }
    /** The seven-second helper clips (midwife, hat hold) have no lead-in segment: they wait in the rest frames. */
    public static int partnerTick(float ticks){
        if(ticks<PREPARATION)return STRAIN+EMERGENCE+(int)(Math.max(0,ticks)*REST/PREPARATION);
        return animationTick(ticks);
    }
    /** The authored 2.4-second hat hold reaches its catching pose at frame 36, exactly at release. */
    public static float hatAnimationTick(float ticks){
        float phase=partnerTick(ticks),release=STRAIN+EMERGENCE;
        return phase<=release?phase*36/release:36+(phase-release)*12/REST;
    }
    /** Advance, hold the already visible head during a breath, then finish the second push. */
    public static float emergenceProgress(float ticks,int index){
        float elapsed=ticks-emergenceStart(index);
        if(elapsed<=24)return .42f*smooth(elapsed/24);
        if(elapsed<=36)return .42f;
        return .42f+.58f*smooth((elapsed-36)/28);
    }
    /** The same two contractions deform the belly, outlet and accessories on both sides. */
    public static float contraction(float ticks){
        if(ticks<0)return 0;
        if(ticks<PREPARATION){
            // Three growing pains in the lead-in.
            float span=PREPARATION/3f;int pain=Math.min(2,(int)(ticks/span));
            float phase=(ticks-pain*span)/span;
            return (pain==0?.12f:pain==1?.22f:.32f)*(float)Math.pow(Math.sin(phase*Math.PI),2);
        }
        float phase=cycleTick(ticks);int strain=strain(cycleIndex(ticks));
        if(phase<strain)return .7f*smooth(phase/strain);
        float emergence=phase-strain;
        if(emergence<24)return .7f+.3f*(float)Math.sin(emergence/24*Math.PI);
        if(emergence<36)return .7f-.5f*smooth((emergence-24)/12);
        if(emergence<56)return .2f+.8f*smooth((emergence-36)/20);
        if(emergence<EMERGENCE)return 1-smooth((emergence-56)/8);
        return .08f*(float)Math.pow(Math.sin((emergence-EMERGENCE)/REST*Math.PI*2),2);
    }
    public static float smooth(float value){float q=Math.clamp(value,0,1);return q*q*(3-2*q);}
    /** Fast at first so a pregnancy shows early (a quarter of the term is already half a belly), then settles to the full term belly. */
    public static float belly(float progress,int born,int count){return smooth((float)Math.sqrt(Math.clamp(progress,0f,1f)))*(1-.78f*born/Math.max(1,count));}
    /** Whole-body growth of a full belly; the lower body also fills out, most at the front under the mouth. */
    public static final float SWELL=.26f, LOW_WIDE=.16f, LOW_DEEP=.06f, LOW_FRONT=.28f;
    /** Model units, before the entity's scale and yaw. The body grows from the floor and its lower front rounds out
     *  below the mouth (CADあき: the lower body swells); the face and the upper body are moved, not stretched.
     *  Accessories and Marisa's braid do not take this: they ride the swollen head rigidly (YukkuriModel). */
    public static void deform(Vector3f point,float belly,float contraction){
        if(belly<=0)return;
        float s=1+SWELL*belly,low=smooth(point.y/1.5f)*(1-smooth((point.y-1.5f)/4.5f)),front=point.z<0?smooth(-point.z/4):0;
        float squeeze=1+.035f*contraction;
        point.set(point.x*s*(1+LOW_WIDE*belly*low)*squeeze,point.y*s*(1-.04f*contraction),point.z*s*(1+(LOW_DEEP+LOW_FRONT*front)*belly*low)*squeeze);
    }
    public static boolean bean(int flags){return ((flags>>4)&15)==3;}
    private static EntityDimensions selectedDimensions(YukkuriVariant variant,int flags){
        return selectedDimensions(variant,SubspeciesLife.assetId(variant,flags),bean(flags));
    }
    private static EntityDimensions selectedDimensions(YukkuriVariant variant,String id,boolean bean){
        var original=ModEntities.forVariant(variant).getDimensions();
        if(BODY.has(id)){
            var data=BODY.getAsJsonObject(id);var bounds=data.getAsJsonArray("bounds");
            float width=(bounds.get(0).getAsJsonArray().get(1).getAsFloat()-bounds.get(0).getAsJsonArray().get(0).getAsFloat())/16;
            return original.scale(width/original.width(),data.get("height").getAsFloat()/16/original.height());
        }
        return original.scale(bean?.82f:1,bean?1.55f:1);
    }
    public static EntityDimensions bodyDimensions(YukkuriEntity entity,float growth,float belly){
        var size=selectedDimensions(entity.getVariant(),entity.subspeciesFlags());
        return size.scale(growth*(1+SWELL*belly)*(1+LOW_WIDE*belly),growth*(1+SWELL*belly));
    }
    public static EntityDimensions childDimensions(FamilyData.Child child,float globalScale){
        return selectedDimensions(child.variant,child.subspeciesFlags).scale(BABY_SCALE*globalScale);
    }
    public static Vector3f modelOutlet(YukkuriView mother,float belly,float contraction){
        var anchor=OUTLETS.computeIfAbsent(mother.assetId(),id->{
            // Rounded assets export the exact anatomy_mamu surface site. Unsupported live-birth
            // forms use that same source as a defensive fallback, never a second invented outlet.
            var point=BODY.getAsJsonObject(BODY.has(id)?id:"reimu").getAsJsonArray("outlet");
            return new Vector3f(point.get(0).getAsFloat(),point.get(1).getAsFloat(),point.get(2).getAsFloat());
        });
        var point=new Vector3f(anchor);deform(point,belly,contraction);return point;
    }
    public static Vec3 outlet(YukkuriView mother){
        // Client family state has no brood ledger; museum actors intentionally have no real brood either.
        return new Vec3(modelOutlet(mother,mother.livePregnancyProgress(),0)).scale(1/16d);
    }
    /** How far ahead of her origin the newborn comes out (blocks, before scale). On the ground the full newborn must
     *  clear the maternal collision volume before becoming an entity; afloat in her hat boat it comes just out of her
     *  swollen front, into the hat before her. Both sides (LiveBirthRenderer draws the emergence up to it). */
    public static double exitFront(YukkuriEntity mother,int index){
        if(mother.getVehicle() instanceof HatBoatEntity){
            float width=.8f*BABY_SCALE;
            if(mother.level().isClientSide){
                if(index>=0&&index<mother.liveChildCount()){
                    String id=mother.liveChildAssetId(index);
                    width=selectedDimensions(mother.liveChildVariant(index),id,id.endsWith("_bean")).width()*BABY_SCALE;
                }
            }else{
                var brood=mother.family().brood();
                if(brood!=null&&index>=0&&index<brood.children.size())width=childDimensions(brood.children.get(index),1).width();
            }
            return -modelOutlet(mother,mother.livePregnancyProgress(),0).z/16*mother.growthScale()+width*.5+.02;
        }
        return Math.max(-outlet(mother).z*mother.growthScale()+.15,mother.getBbWidth()/mother.getScale()*.5+.15);
    }
    /** Client emergence reads the synchronized child preview, never the server brood ledger. */
    public static double visibleExitFront(YukkuriView mother,int index){
        if(mother.vehicleHat()!=null){
            float width=.8f*BABY_SCALE;
            if(index>=0&&index<mother.liveChildCount()){
                String id=mother.liveChildAssetId(index);
                width=selectedDimensions(mother.liveChildVariant(index),id,id.endsWith("_bean")).width()*BABY_SCALE;
            }
            return -modelOutlet(mother,mother.livePregnancyProgress(),0).z/16*mother.growthScale()+width*.5+.02;
        }
        return Math.max(-outlet(mother).z*mother.growthScale()+.15,mother.getBbWidth()/mother.getScale()*.5+.15);
    }
    /** How far her body reaches behind its origin (blocks, before scale): her back against the hat boat's stern. */
    public static double back(YukkuriEntity y){
        var id=y.subspecies().assetId();float b=y.livePregnancyProgress();
        double z=BODY.has(id)?BODY.getAsJsonObject(id).getAsJsonArray("bounds").get(2).getAsJsonArray().get(1).getAsDouble():5.9;
        return z/16*y.growthScale()*(1+SWELL*b)*(1+LOW_DEEP*b);
    }
    public static Vec3 localExit(YukkuriEntity mother,int index){
        float size=mother.growthScale();
        var outlet=outlet(mother).scale(size);
        double front=exitFront(mother,index);
        var brood=mother.family().brood();
        float height=brood!=null&&index>=0&&index<brood.children.size()?childDimensions(brood.children.get(index),1).height():.6f*BABY_SCALE;
        return new Vec3(0,Math.max(.015,outlet.y-height*.5),-front);
    }
    public static Vec3 position(YukkuriEntity mother,int index){return FamilyGeometry.toWorld(mother,releasePose(mother,localExit(mother,index)));}
    public static boolean clear(YukkuriEntity mother,FamilyData.Child child,int index){
        if(!(mother.level() instanceof ServerLevel level))return false;
        // Afloat in her own hat boat the young are born into the hat: only a free seat is needed.
        var boat=mother.family().boatHome();
        // The exact seat is planned for the real newborn (FamilyData.birth: her own hat, or the partner's, with the emergency refuge as the last place);
        // hasRoomForYoung() asks for a worn-hat seat among the owner's whole body projection and is never true for a newborn in a full-size hat.
        if(boat!=null)return boat.isAfloat()&&boat.youngAboard()<HatBoatEntity.MAX_YOUNG;
        var position=position(mother,index);
        var dimensions=childDimensions(child,mother.getScale());
        var box=dimensions.makeBoundingBox(position);
        // At a bank the newborn comes out over the hat floated at the water's edge: a free seat in it is the room needed.
        var brood=mother.family().brood();
        var floated=level.getEntitiesOfClass(HatBoatEntity.class,box.inflate(.9),hat->hat.isMoored()&&brood!=null
                &&(mother.getUUID().equals(hat.ownerYukkuri())||hat.ownerYukkuri()!=null&&hat.ownerYukkuri().equals(brood.father))).stream().findFirst().orElse(null);
        if(floated!=null)return floated.hasRoomForYoung()&&level.noCollision(mother,box);
        if(!level.hasChunkAt(BlockPos.containing(position))||!level.getWorldBorder().isWithinBounds(box)
                ||!level.noCollision(mother,box)||level.containsAnyLiquid(box))return false;
        // Non-collidable mobs still occupy delivery space; never layer siblings at the outlet.
        // Siblings already caught in a hat cradle at the outlet do not block the next one.
        if(!level.getEntitiesOfClass(YukkuriEntity.class,box.inflate(.015),other->other!=mother&&other.isAlive()
                &&!(other.getVehicle() instanceof HatBoatEntity)).isEmpty())return false;
        var ground=level.clip(new ClipContext(position.add(0,.01,0),position.add(0,-.55,0),ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,mother));
        if(ground.getType()!=HitResult.Type.BLOCK||ground.getDirection()!=Direction.UP)return false;
        double y=ground.getLocation().y;
        for(double dx:new double[]{-dimensions.width()*.4,dimensions.width()*.4})for(double dz:new double[]{-dimensions.width()*.4,dimensions.width()*.4}){
            Vec3 foot=new Vec3(position.x+dx,y+.03,position.z+dz);
            var support=level.clip(new ClipContext(foot,foot.add(0,-.08,0),ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,mother));
            if(support.getType()!=HitResult.Type.BLOCK||Math.abs(support.getLocation().y-y)>.03)return false;
        }
        // Prevent a wall between the front outlet and the checked destination.
        var origin=FamilyGeometry.toWorld(mother,releasePose(mother,outlet(mother).scale(mother.growthScale())));
        return level.clip(new ClipContext(origin,position.add(0,dimensions.height()*.5,0),ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,mother)).getType()==HitResult.Type.MISS;
    }
    private LiveBirthGeometry(){}
}
