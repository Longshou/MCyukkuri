package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/** Shared pregnancy deformation and front delivery route; adapted from the approved viviparous prototype. */
public final class LiveBirthGeometry {
    private static final java.util.Map<String,Vector3f> OUTLETS=new java.util.concurrent.ConcurrentHashMap<>();
    private static final com.google.gson.JsonObject BODY=readBodies();
    private static com.google.gson.JsonObject readBodies(){
        try(var stream=LiveBirthGeometry.class.getResourceAsStream("/assets/mcyukkuri/yukkuri_presentation/body_geometry.json")){
            if(stream==null)throw new IllegalStateException("Missing selected body geometry");
            return com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(stream,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("variants");
        }catch(java.io.IOException ex){throw new java.io.UncheckedIOException(ex);}
    }
    public static float modelHeight(String id){
        return BODY.has(id)?BODY.getAsJsonObject(id).get("height").getAsFloat():id.startsWith("house_")?3.82f:id.equals("remilia")||id.equals("flandre")?9.6f:8.48f*(id.endsWith("_bean")?1.55f:1);
    }
    /** A live newborn's size (GrowthSize, the life-stage size standard). */
    public static final float BABY_SCALE=GrowthSize.LIVE;
    public static final int EMERGENCE=36, INTERVAL=48;
    public static int birthTick(int index){return index*INTERVAL+EMERGENCE;}
    public static float smooth(float value){float q=Math.clamp(value,0,1);return q*q*(3-2*q);}
    public static float belly(float progress,int born,int count){return smooth(progress)*(1-.78f*born/Math.max(1,count));}
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
    private static net.minecraft.world.entity.EntityDimensions selectedDimensions(io.github.longshou.mcyukkuri.entity.YukkuriVariant variant,int flags){
        var original=io.github.longshou.mcyukkuri.registry.ModEntities.forVariant(variant).getDimensions();
        String id=io.github.longshou.mcyukkuri.subspecies.SubspeciesLife.assetId(variant,flags);
        if(BODY.has(id)){
            var data=BODY.getAsJsonObject(id);var bounds=data.getAsJsonArray("bounds");
            float width=(bounds.get(0).getAsJsonArray().get(1).getAsFloat()-bounds.get(0).getAsJsonArray().get(0).getAsFloat())/16;
            return original.scale(width/original.width(),data.get("height").getAsFloat()/16/original.height());
        }
        return original.scale(bean(flags)?.82f:1,bean(flags)?1.55f:1);
    }
    public static net.minecraft.world.entity.EntityDimensions bodyDimensions(YukkuriEntity entity,float growth,float belly){
        var size=selectedDimensions(entity.getVariant(),entity.subspeciesFlags());
        return size.scale(growth*(1+SWELL*belly)*(1+LOW_WIDE*belly),growth*(1+SWELL*belly));
    }
    public static net.minecraft.world.entity.EntityDimensions childDimensions(FamilyData.Child child,float globalScale){
        return selectedDimensions(child.variant,child.subspeciesFlags).scale(BABY_SCALE*globalScale);
    }
    public static Vector3f modelOutlet(YukkuriEntity mother,float belly,float contraction){
        var anchor=OUTLETS.computeIfAbsent(mother.subspecies().assetId(),id->{
            if(BODY.has(id)){var point=BODY.getAsJsonObject(id).getAsJsonArray("outlet");return new Vector3f(point.get(0).getAsFloat(),point.get(1).getAsFloat(),point.get(2).getAsFloat());}
            if(!id.endsWith("_bean"))return new Vector3f(0,1.02f,-4.63f);
            var origin=new Vector3f(0,1.02f*1.55f,-32);
            var hit=io.github.longshou.mcyukkuri.body.WoundSurface.get(id).ray(origin,new Vector3f(0,0,1),null,java.util.List.of());
            return new Vector3f(0,origin.y,hit==null?-4.63f*.82f:origin.z+hit.distance()-.03f);
        });
        var point=new Vector3f(anchor);deform(point,belly,contraction);return point;
    }
    public static Vec3 outlet(YukkuriEntity mother){
        return new Vec3(modelOutlet(mother,mother.family().liveBelly(),0)).scale(1/16d);
    }
    /** How far ahead of her origin the newborn comes out (blocks, before scale). On the ground the full newborn must
     *  clear the maternal collision volume before becoming an entity; afloat in her hat boat it comes just out of her
     *  swollen front, into the hat before her. Both sides (LiveBirthRenderer draws the emergence up to it). */
    public static double exitFront(YukkuriEntity mother,int index){
        if(mother.getVehicle() instanceof io.github.longshou.mcyukkuri.subspecies.HatBoatEntity){
            var brood=mother.family().brood();
            float width=brood!=null&&index>=0&&index<brood.children.size()?childDimensions(brood.children.get(index),1).width():.8f*BABY_SCALE;
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
    public static Vec3 position(YukkuriEntity mother,int index){return FamilyGeometry.toWorld(mother,localExit(mother,index));}
    public static boolean clear(YukkuriEntity mother,FamilyData.Child child,int index){
        if(!(mother.level() instanceof ServerLevel level))return false;
        // Afloat in her own hat boat the young are born into the hat: only a free seat is needed.
        var boat=mother.family().boatHome();
        if(boat!=null)return boat.hasRoomForYoung();
        var position=position(mother,index);
        var dimensions=childDimensions(child,mother.getScale());
        var box=dimensions.makeBoundingBox(position);
        // At a bank the newborn comes out over the hat floated at the water's edge: a free seat in it is the room needed.
        var brood=mother.family().brood();
        var floated=level.getEntitiesOfClass(io.github.longshou.mcyukkuri.subspecies.HatBoatEntity.class,box.inflate(.9),hat->hat.isMoored()&&brood!=null
                &&(mother.getUUID().equals(hat.ownerYukkuri())||hat.ownerYukkuri()!=null&&hat.ownerYukkuri().equals(brood.father))).stream().findFirst().orElse(null);
        if(floated!=null)return floated.hasRoomForYoung()&&level.noCollision(mother,box);
        if(!level.hasChunkAt(BlockPos.containing(position))||!level.getWorldBorder().isWithinBounds(box)
                ||!level.noCollision(mother,box)||level.containsAnyLiquid(box))return false;
        // Non-collidable mobs still occupy delivery space; never layer siblings at the outlet.
        // Siblings already caught in a hat cradle at the outlet do not block the next one.
        if(!level.getEntitiesOfClass(YukkuriEntity.class,box.inflate(.015),other->other!=mother&&other.isAlive()
                &&!(other.getVehicle() instanceof io.github.longshou.mcyukkuri.subspecies.HatBoatEntity)).isEmpty())return false;
        var ground=level.clip(new ClipContext(position.add(0,.01,0),position.add(0,-.55,0),ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,mother));
        if(ground.getType()!=HitResult.Type.BLOCK||ground.getDirection()!=net.minecraft.core.Direction.UP)return false;
        double y=ground.getLocation().y;
        for(double dx:new double[]{-dimensions.width()*.4,dimensions.width()*.4})for(double dz:new double[]{-dimensions.width()*.4,dimensions.width()*.4}){
            Vec3 foot=new Vec3(position.x+dx,y+.03,position.z+dz);
            var support=level.clip(new ClipContext(foot,foot.add(0,-.08,0),ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,mother));
            if(support.getType()!=HitResult.Type.BLOCK||Math.abs(support.getLocation().y-y)>.03)return false;
        }
        // Prevent a wall between the front outlet and the checked destination.
        var origin=FamilyGeometry.toWorld(mother,outlet(mother).scale(mother.growthScale()));
        return level.clip(new ClipContext(origin,position.add(0,dimensions.height()*.5,0),ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,mother)).getType()==HitResult.Type.MISS;
    }
    private LiveBirthGeometry(){}
}
