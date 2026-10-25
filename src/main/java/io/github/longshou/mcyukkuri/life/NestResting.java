package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import java.util.Map;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.function.BiPredicate;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.phys.Vec3;

/** Unmarked resting positions chosen on admission, stable through sleep and reload. */
public final class NestResting {
    public record Place(double x,double z,float width) { public Vec3 offset(){return new Vec3(x,.1,z);} }
    /**
     * Where a body may rest, in blocks from the bed centre: its collision box within {@code box} of the centre (the level
     * floor), and its rounded body within the visible hollow, a rounded square of half size {@code half} with corners of
     * radius {@code corner} (a circle when corner == half). Sizes follow the meshes in drafts/care-props (placed.py, wild.py).
     */
    public record Bed(double box,double half,double corner,boolean centreFirst){}
    /** Pet cushion nest: the whole block is level pad inside a round bolster whose inner side lies 9.6 out at body height. */
    public static final Bed CUSHION=new Bed(.5,9.6/16,9.6/16,true);
    /** Wild leaf bed cell and personal leaf nest: no collision rim, so a broad (deibu) body may still spread onto the soft
     *  rim feet as before; smaller bodies stay off the corners, where the rim curves in. */
    public static final Bed LEAF_BED=new Bed(.61,.61,5/16.,true);
    /** Pet woven and wild 2x2 nests: a level floor 29.2 across inside the wall or pressed rim. */
    public static final Bed LARGE=new Bed(14.6/16,14.6/16,5.5/16,false);
    /** The predator species' ledge nest: one level 2.4 platform, unchanged limits. */
    public static final Bed LEDGE=new Bed(.61,.61,0,true);
    /** A rounded body reaches out about 0.6 of its width at its corners (body_geometry: 7.62 for 12.8), its flat sides 0.5. */
    private static final double BODY_REACH=.6;
    private NestResting(){}
    private static boolean inside(double x,double z,float width,Bed bed){
        double ax=Math.abs(x),az=Math.abs(z);
        if(ax+width*.5>bed.box()||az+width*.5>bed.box())return false;
        double inner=bed.half()-bed.corner(),dx=Math.max(0,ax-inner),dz=Math.max(0,az-inner);
        return inner<=0||dx>0&&dz>0?Math.hypot(dx,dz)+width*BODY_REACH<=bed.corner():Math.max(ax,az)+width*.5<=bed.half();
    }
    private static boolean fits(double x,double z,float width,Bed bed,UUID id,Map<UUID,Place> others){
        if(!inside(x,z,width,bed))return false;
        for(var entry:others.entrySet())if(!entry.getKey().equals(id)){
            var p=entry.getValue();double gap=(width+p.width())*.5+.06;
            if(Math.abs(x-p.x())<gap&&Math.abs(z-p.z())<gap)return false;
        }
        return true;
    }
    public static Place choose(YukkuriEntity e,Bed bed,Map<UUID,Place> others,BiPredicate<UUID,Place> supported){
        float width=e.getBbWidth();
        var previous=others.get(e.getUUID());
        if(previous!=null&&supported.test(e.getUUID(),previous)&&fits(previous.x(),previous.z(),width,bed,e.getUUID(),others))return new Place(previous.x(),previous.z(),width);
        var direct=sample(e,e.getUUID(),width,bed,others,supported);
        if(direct!=null)return direct;
        // Admission/growth may move rest targets, never bodies. Random layouts avoid fixed rows
        // and keep the first sleeper from permanently reserving the centre of a shared bed.
        var sizes=new LinkedHashMap<UUID,Float>();others.forEach((id,p)->sizes.put(id,p.width()));sizes.put(e.getUUID(),width);
        var order=new ArrayList<>(sizes.keySet());order.sort((a,b)->Float.compare(sizes.get(b),sizes.get(a)));
        for(int attempt=0;attempt<32;attempt++){
            var layout=new LinkedHashMap<UUID,Place>();
            for(UUID id:order){var p=sample(e,id,sizes.get(id),bed,layout,supported);if(p==null)break;layout.put(id,p);}
            if(layout.size()==sizes.size()){others.clear();others.putAll(layout);return layout.get(e.getUUID());}
        }
        return null;
    }
    private static Place sample(YukkuriEntity e,UUID id,float width,Bed bed,Map<UUID,Place> others,BiPredicate<UUID,Place> supported){
        double limit=bed.box()-width*.5;if(limit<0||!inside(0,0,width,bed))return null;
        // A one-body bed is a bowl whose level floor is centred: the first sleeper of any size settles in the middle.
        if(bed.centreFirst()){var centre=new Place(0,0,width);if(fits(0,0,width,bed,id,others)&&supported.test(id,centre))return centre;}
        for(int attempt=0;attempt<96;attempt++){
            double x=(e.getRandom().nextDouble()*2-1)*limit,z=(e.getRandom().nextDouble()*2-1)*limit;
            var candidate=new Place(x,z,width);
            if(fits(x,z,width,bed,id,others)&&supported.test(id,candidate))return candidate;
        }
        return null;
    }
    public static ListTag save(Map<UUID,Place> places){
        var list=new ListTag();places.forEach((id,p)->{var tag=new CompoundTag();tag.putUUID("Id",id);tag.putDouble("X",p.x());tag.putDouble("Z",p.z());tag.putFloat("Width",p.width());list.add(tag);});return list;
    }
    public static void load(ListTag list,Map<UUID,Place> places){
        places.clear();for(Tag raw:list){var tag=(CompoundTag)raw;if(tag.hasUUID("Id")&&places.size()<128)
            places.put(tag.getUUID("Id"),new Place(Math.clamp(tag.getDouble("X"),-.98,.98),Math.clamp(tag.getDouble("Z"),-.98,.98),Math.clamp(tag.getFloat("Width"),.05f,2f)));}
    }
}
