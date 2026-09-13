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
    public static final float BABY_SCALE=.18f;
    public static final int EMERGENCE=36, INTERVAL=48;
    public static int birthTick(int index){return index*INTERVAL+EMERGENCE;}
    public static float smooth(float value){float q=Math.clamp(value,0,1);return q*q*(3-2*q);}
    public static float belly(float progress,int born,int count){return smooth(progress)*(1-.78f*born/Math.max(1,count));}
    /** Model units, before the entity's scale and yaw. Upper face and accessories rise together. */
    public static void deform(Vector3f point,float belly,float contraction){
        if(belly<=0)return;
        float blend=smooth(point.y/4.2f),lower=1-blend;
        point.set(point.x*(1+.22f*belly*lower)*(1+.035f*contraction),
                (point.y+2.5f*belly*blend)*(1-.04f*contraction),point.z*(1+.24f*belly*lower));
    }
    public static boolean bean(int flags){return ((flags>>4)&15)==3;}
    public static net.minecraft.world.entity.EntityDimensions bodyDimensions(YukkuriEntity entity,float growth,float belly){
        boolean bean=bean(entity.subspeciesFlags());
        return entity.getType().getDimensions().scale(growth*(1+.22f*belly)*(bean?.82f:1),growth*(1+.295f*belly)*(bean?1.55f:1));
    }
    public static net.minecraft.world.entity.EntityDimensions childDimensions(FamilyData.Child child,float globalScale){
        boolean bean=bean(child.subspeciesFlags);
        return io.github.longshou.mcyukkuri.registry.ModEntities.forVariant(child.variant).getDimensions()
                .scale(BABY_SCALE*globalScale*(bean?.82f:1),BABY_SCALE*globalScale*(bean?1.55f:1));
    }
    public static Vector3f modelOutlet(YukkuriEntity mother,float belly,float contraction){
        var anchor=OUTLETS.computeIfAbsent(mother.subspecies().assetId(),id->{
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
    public static Vec3 localExit(YukkuriEntity mother,int index){
        float size=mother.family().scale();
        var outlet=outlet(mother).scale(size);
        // The full newborn must clear the maternal collision volume before becoming an entity.
        double front=Math.max(-outlet.z+.15,mother.getBbWidth()/mother.getScale()*.5+.15);
        var brood=mother.family().brood();
        float height=brood!=null&&index>=0&&index<brood.children.size()?childDimensions(brood.children.get(index),1).height():.53f*BABY_SCALE;
        return new Vec3(0,Math.max(.015,outlet.y-height*.5),-front);
    }
    public static Vec3 position(YukkuriEntity mother,int index){return FamilyGeometry.toWorld(mother,localExit(mother,index));}
    public static boolean clear(YukkuriEntity mother,FamilyData.Child child,int index){
        if(!(mother.level() instanceof ServerLevel level))return false;
        var position=position(mother,index);
        var dimensions=childDimensions(child,mother.getScale());
        var box=dimensions.makeBoundingBox(position);
        if(!level.hasChunkAt(BlockPos.containing(position))||!level.getWorldBorder().isWithinBounds(box)
                ||!level.noCollision(mother,box)||level.containsAnyLiquid(box))return false;
        // Non-collidable mobs still occupy delivery space; never layer siblings at the outlet.
        if(!level.getEntitiesOfClass(YukkuriEntity.class,box.inflate(.015),other->other!=mother&&other.isAlive()).isEmpty())return false;
        var ground=level.clip(new ClipContext(position.add(0,.01,0),position.add(0,-.55,0),ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,mother));
        if(ground.getType()!=HitResult.Type.BLOCK||ground.getDirection()!=net.minecraft.core.Direction.UP)return false;
        double y=ground.getLocation().y;
        for(double dx:new double[]{-dimensions.width()*.4,dimensions.width()*.4})for(double dz:new double[]{-dimensions.width()*.4,dimensions.width()*.4}){
            Vec3 foot=new Vec3(position.x+dx,y+.03,position.z+dz);
            var support=level.clip(new ClipContext(foot,foot.add(0,-.08,0),ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,mother));
            if(support.getType()!=HitResult.Type.BLOCK||Math.abs(support.getLocation().y-y)>.03)return false;
        }
        // Prevent a wall between the front outlet and the checked destination.
        var origin=FamilyGeometry.toWorld(mother,outlet(mother).scale(mother.family().scale()));
        return level.clip(new ClipContext(origin,position.add(0,dimensions.height()*.5,0),ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,mother)).getType()==HitResult.Type.MISS;
    }
    private LiveBirthGeometry(){}
}
