package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import java.util.Map;
import io.github.longshou.mcyukkuri.headwear.HeadTop;
import io.github.longshou.mcyukkuri.life.NestLayout.Place;
import io.github.longshou.mcyukkuri.motor.geom.FloorShape;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;


/** Unmarked resting positions chosen on admission, stable through sleep and reload. */
public final class NestResting {
    /**
     * Where a body may rest, in blocks from the bed centre: its collision box within {@code box} of the centre (the level
     * floor), and its rounded body within the visible hollow, a rounded square of half size {@code half} with corners of
     * radius {@code corner} (a circle when corner == half). Sizes follow the meshes in drafts/care-props (placed.py, wild.py).
     * {@code softBox}: the bed has no collision rim, so its level floor also takes the soft-rim allowance.
     */
    public record Bed(double box,double half,double corner,boolean centreFirst,boolean softBox){
        double levelHalf(){return box+(softBox?RIM_PRESS:0);}
        public FloorShape floor(){
            double reach=Math.min(half+RIM_PRESS,levelHalf());
            return new FloorShape.RoundedRect(reach,Math.min(reach,corner+RIM_PRESS));
        }
    }
    /** Pet cushion nest: a level pad 23 across (the block and the bolster's inner slope) inside a round bolster whose
     *  inner side lies 11.1 out at body height; a 1.2x adult at full term (box 22.45) rests at the centre. */
    public static final Bed CUSHION=new Bed(11.5/16,11.1/16,11.1/16,true,false);
    /** Wild leaf bed cell and personal leaf nest: no collision rim, so a broad (deibu) body may still spread onto the soft
     *  rim feet as before; smaller bodies stay off the corners, where the rim curves in. */
    public static final Bed LEAF_BED=new Bed(.61,.61,5/16.,true,true);
    /** Pet woven and wild 2x2 nests: a level floor 29.2 across inside the wall or pressed rim. */
    public static final Bed LARGE=new Bed(14.6/16,14.6/16,5.5/16,false,false);
    /** The predator species' ledge nest: one level 2.4 platform, unchanged limits. */
    public static final Bed LEDGE=new Bed(.61,.61,0,true,false);
    /** Loose bedding without a rigid rim can compress by 2.5 model pixels. */
    public static final double RIM_PRESS=2.5/16;
    /** Bedding laid on a burrow floor has no collision; its sole-level top above the block bottom, in blocks, from the meshes at
     *  the cell centre: the leaf bed (forest_burrow pad+lining, personal_leaf_nest leaves) 0.4 px, the wild large nest
     *  (large_wild_nest leaf_floor+lining, art/props/large-nests) 1.0 px. Pet nests have real collision and use none. */
    private static final double LEAF_BEDDING=.4/16,LARGE_BEDDING=1.0/16;
    public static double beddingTop(boolean large){return large?LARGE_BEDDING:LEAF_BEDDING;}
    private NestResting(){}
    public static Place choose(YukkuriEntity e,Bed bed,Map<UUID,Place> others,Predicate<Place> supported,boolean centre){
        var body = new Place(0,0,e.getBbWidth(),HeadTop.reach(e));
        return NestLayout.choose(e.getUUID(),body,bed.levelHalf(),others,supported,
                e.life().isSleeping(), centre || bed.centreFirst(), SurfaceApproach.ARRIVAL);
    }
    public static ListTag save(Map<UUID,Place> places){
        var list=new ListTag();places.forEach((id,p)->{var tag=new CompoundTag();tag.putUUID("Id",id);tag.putDouble("X",p.x());tag.putDouble("Z",p.z());tag.putFloat("Width",p.width());tag.putDouble("Reach",p.reach());list.add(tag);});return list;
    }
    public static void load(ListTag list,Map<UUID,Place> places){
        places.clear();for(Tag raw:list){var tag=(CompoundTag)raw;if(tag.hasUUID("Id")&&places.size()<128)
            places.put(tag.getUUID("Id"),new Place(Math.clamp(tag.getDouble("X"),-.98,.98),Math.clamp(tag.getDouble("Z"),-.98,.98),Math.clamp(tag.getFloat("Width"),.05f,2f),tag.contains("Reach")?tag.getDouble("Reach"):tag.getFloat("Width")*.5));}
    }
}
