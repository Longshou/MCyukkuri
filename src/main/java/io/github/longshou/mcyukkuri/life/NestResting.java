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
     * {@code softBox}: the bed has no collision rim, so its level floor also takes the soft-rim allowance.
     */
    public record Bed(double box,double half,double corner,boolean centreFirst,boolean softBox){}
    /** Pet cushion nest: a level pad 23 across (the block and the bolster's inner slope) inside a round bolster whose
     *  inner side lies 11.1 out at body height; a 1.2x adult at full term (box 22.45) rests at the centre. */
    public static final Bed CUSHION=new Bed(11.5/16,11.1/16,11.1/16,true,false);
    /** Wild leaf bed cell and personal leaf nest: no collision rim, so a broad (deibu) body may still spread onto the soft
     *  rim feet as before; smaller bodies stay off the corners, where the rim curves in. */
    public static final Bed LEAF_BED=new Bed(.61,.61,5/16.,true,true);
    /** Pet woven and wild 2x2 nests: a level floor 29.2 across inside the wall or pressed rim. */
    public static final Bed LARGE=new Bed(14.6/16,14.6/16,5.5/16,false,false);
    /** The same 2x2 wild nest made in a burrow (ShelterBlockEntity): drawn only, no collision rim, so its floor takes the
     *  soft-rim allowance too. */
    public static final Bed LARGE_BED=new Bed(14.6/16,14.6/16,5.5/16,false,true);
    /** The predator species' ledge nest: one level 2.4 platform, unchanged limits. */
    public static final Bed LEDGE=new Bed(.61,.61,0,true,false);
    /** A rounded body reaches out about 0.6 of its width at its corners (body_geometry: 7.62 for 12.8), its flat sides 0.5. */
    private static final double BODY_REACH=.6;
    /**
     * Fitting tolerance (project design; user 2026-10-25: some clipping is accepted so that a body really lies in its bed).
     * When no clean layout exists, a rounded body may press RIM_PRESS into the soft visible wall of the hollow (bolster,
     * pressed grass or leaves; never into a collision box), and resting bodies may nestle so that their boxes overlap by a
     * share of the smaller body's width, tried in steps of 0.2, 0.35 and at most NESTLE. A clean layout is always tried first.
     */
    public static final double RIM_PRESS=2.5/16, NESTLE=.5;
    private enum Fit {
        CLEAN(false,0), SNUG(true,.2), CLOSE(true,.35), PRESSED(true,NESTLE);
        final boolean press;final double overlap;
        Fit(boolean press,double overlap){this.press=press;this.overlap=overlap;}
    }
    private NestResting(){}
    private static boolean inside(double x,double z,float width,Bed bed,Fit fit){
        double press=fit.press?RIM_PRESS:0,box=bed.box()+(bed.softBox()?press:0);
        double ax=Math.abs(x),az=Math.abs(z);
        if(ax+width*.5>box||az+width*.5>box)return false;
        double inner=bed.half()-bed.corner(),dx=Math.max(0,ax-inner),dz=Math.max(0,az-inner);
        return inner<=0||dx>0&&dz>0?Math.hypot(dx,dz)+width*BODY_REACH<=bed.corner()+press:Math.max(ax,az)+width*.5<=bed.half()+press;
    }
    private static boolean fits(double x,double z,float width,Bed bed,UUID id,Map<UUID,Place> others,Fit fit){
        if(!inside(x,z,width,bed,fit))return false;
        for(var entry:others.entrySet())if(!entry.getKey().equals(id)){
            var p=entry.getValue();
            double gap=fit==Fit.CLEAN?(width+p.width())*.5+.06:(width+p.width())*.5-fit.overlap*Math.min(width,p.width());
            if(Math.abs(x-p.x())<gap&&Math.abs(z-p.z())<gap)return false;
        }
        return true;
    }
    /** The resting place of one body; {@code centre} asks for the middle of the bed (a mother near term). */
    public static Place choose(YukkuriEntity e,Bed bed,Map<UUID,Place> others,BiPredicate<UUID,Place> supported,boolean centre){
        float width=e.getBbWidth();
        var previous=others.get(e.getUUID());
        // A kept place is stable (also a nestled one: the clean search is not repeated every claim). Asked for the middle,
        // she moves there once it is free, one check per claim.
        if(previous!=null&&supported.test(e.getUUID(),previous)&&fits(previous.x(),previous.z(),width,bed,e.getUUID(),others,Fit.PRESSED)){
            var middle=new Place(0,0,width);
            if(centre&&Math.hypot(previous.x(),previous.z())>=.05&&fits(0,0,width,bed,e.getUUID(),others,Fit.SNUG)&&supported.test(e.getUUID(),middle))
                return middle;
            return new Place(previous.x(),previous.z(),width);
        }
        for(var fit:Fit.values()){var place=search(e,bed,others,supported,centre,fit);if(place!=null)return place;}
        return null;
    }
    public static Place choose(YukkuriEntity e,Bed bed,Map<UUID,Place> others,BiPredicate<UUID,Place> supported){
        return choose(e,bed,others,supported,false);
    }
    private static Place search(YukkuriEntity e,Bed bed,Map<UUID,Place> others,BiPredicate<UUID,Place> supported,boolean centre,Fit fit){
        float width=e.getBbWidth();
        var direct=sample(e,e.getUUID(),width,bed,others,supported,centre,fit);
        if(direct!=null)return direct;
        // Admission/growth may move rest targets, never bodies. Random layouts avoid fixed rows
        // and keep the first sleeper from permanently reserving the centre of a shared bed.
        var sizes=new LinkedHashMap<UUID,Float>();others.forEach((id,p)->sizes.put(id,p.width()));sizes.put(e.getUUID(),width);
        var order=new ArrayList<>(sizes.keySet());order.sort((a,b)->Float.compare(sizes.get(b),sizes.get(a)));
        if(centre){order.remove(e.getUUID());order.addFirst(e.getUUID());}
        // The two widest bodies pressed to opposite sides first: the widest gap a pair can have, which random samples
        // rarely hit when the bed is only just wide enough; the smaller ones then find their places among them.
        if(order.size()>=2)for(int axis=0;axis<2;axis++)for(int sign=-1;sign<=1;sign+=2){
            var layout=new LinkedHashMap<UUID,Place>();
            for(int k=0;k<2;k++){
                UUID id=order.get(k);float w=sizes.get(id);
                double reach=Math.min(bed.box()+(bed.softBox()&&fit.press?RIM_PRESS:0),bed.half()+(fit.press?RIM_PRESS:0))-w*.5;
                double at=(k==0?-sign:sign)*Math.max(0,reach-.01);
                var place=new Place(axis==0?at:0,axis==1?at:0,w);
                if(fits(place.x(),place.z(),w,bed,id,layout,fit)&&supported.test(id,place))layout.put(id,place);else break;
            }
            for(UUID id:order.subList(Math.min(2,order.size()),order.size())){
                if(layout.size()<2)break;
                var p=sample(e,id,sizes.get(id),bed,layout,supported,false,fit);if(p==null)break;layout.put(id,p);
            }
            if(layout.size()==sizes.size()){others.clear();others.putAll(layout);return layout.get(e.getUUID());}
        }
        for(int attempt=0;attempt<32;attempt++){
            var layout=new LinkedHashMap<UUID,Place>();
            for(UUID id:order){var p=sample(e,id,sizes.get(id),bed,layout,supported,centre&&id.equals(e.getUUID()),fit);if(p==null)break;layout.put(id,p);}
            if(layout.size()==sizes.size()){others.clear();others.putAll(layout);return layout.get(e.getUUID());}
        }
        return null;
    }
    private static Place sample(YukkuriEntity e,UUID id,float width,Bed bed,Map<UUID,Place> others,BiPredicate<UUID,Place> supported,boolean centre,Fit fit){
        double limit=bed.box()+(bed.softBox()&&fit.press?RIM_PRESS:0)-width*.5;if(limit<0||!inside(0,0,width,bed,fit))return null;
        // A one-body bed is a bowl whose level floor is centred: the first sleeper of any size settles in the middle.
        if(bed.centreFirst()||centre){var middle=new Place(0,0,width);if(fits(0,0,width,bed,id,others,fit)&&supported.test(id,middle))return middle;}
        // Asked for the middle, the free sample nearest the centre wins; otherwise the first free sample.
        Place best=null;double nearest=Double.MAX_VALUE;
        for(int attempt=0;attempt<96;attempt++){
            double x=(e.getRandom().nextDouble()*2-1)*limit,z=(e.getRandom().nextDouble()*2-1)*limit;
            var candidate=new Place(x,z,width);
            if(!fits(x,z,width,bed,id,others,fit)||!supported.test(id,candidate))continue;
            if(!centre)return candidate;
            double d=Math.hypot(x,z);if(d<nearest){nearest=d;best=candidate;}
        }
        return best;
    }
    public static ListTag save(Map<UUID,Place> places){
        var list=new ListTag();places.forEach((id,p)->{var tag=new CompoundTag();tag.putUUID("Id",id);tag.putDouble("X",p.x());tag.putDouble("Z",p.z());tag.putFloat("Width",p.width());list.add(tag);});return list;
    }
    public static void load(ListTag list,Map<UUID,Place> places){
        places.clear();for(Tag raw:list){var tag=(CompoundTag)raw;if(tag.hasUUID("Id")&&places.size()<128)
            places.put(tag.getUUID("Id"),new Place(Math.clamp(tag.getDouble("X"),-.98,.98),Math.clamp(tag.getDouble("Z"),-.98,.98),Math.clamp(tag.getFloat("Width"),.05f,2f)));}
    }
}
