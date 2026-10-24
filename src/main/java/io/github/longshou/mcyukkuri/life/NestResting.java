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
    private NestResting(){}
    private static boolean fits(double x,double z,float width,double edge,UUID id,Map<UUID,Place> others){
        if(Math.abs(x)+width*.5>edge||Math.abs(z)+width*.5>edge)return false;
        for(var entry:others.entrySet())if(!entry.getKey().equals(id)){
            var p=entry.getValue();double gap=(width+p.width())*.5+.06;
            if(Math.abs(x-p.x())<gap&&Math.abs(z-p.z())<gap)return false;
        }
        return true;
    }
    public static Place choose(YukkuriEntity e,boolean large,Map<UUID,Place> others,BiPredicate<UUID,Place> supported){
        double edge=large?14.25/16:.61;float width=e.getBbWidth();
        var previous=others.get(e.getUUID());
        if(previous!=null&&supported.test(e.getUUID(),previous)&&fits(previous.x(),previous.z(),width,edge,e.getUUID(),others))return new Place(previous.x(),previous.z(),width);
        var direct=sample(e,e.getUUID(),width,edge,others,supported,!large);
        if(direct!=null)return direct;
        // Admission/growth may move rest targets, never bodies. Random layouts avoid fixed rows
        // and keep the first sleeper from permanently reserving the centre of a shared bed.
        var sizes=new LinkedHashMap<UUID,Float>();others.forEach((id,p)->sizes.put(id,p.width()));sizes.put(e.getUUID(),width);
        var order=new ArrayList<>(sizes.keySet());order.sort((a,b)->Float.compare(sizes.get(b),sizes.get(a)));
        for(int attempt=0;attempt<32;attempt++){
            var layout=new LinkedHashMap<UUID,Place>();
            for(UUID id:order){var p=sample(e,id,sizes.get(id),edge,layout,supported,!large);if(p==null)break;layout.put(id,p);}
            if(layout.size()==sizes.size()){others.clear();others.putAll(layout);return layout.get(e.getUUID());}
        }
        return null;
    }
    private static Place sample(YukkuriEntity e,UUID id,float width,double edge,Map<UUID,Place> others,BiPredicate<UUID,Place> supported,boolean centreFirst){
        double limit=edge-width*.5;if(limit<0)return null;
        // The small nest is a bowl: every body size settles at its centre (on the pad or on the slope), so try it first.
        if(centreFirst){var centre=new Place(0,0,width);if(fits(0,0,width,edge,id,others)&&supported.test(id,centre))return centre;}
        for(int attempt=0;attempt<96;attempt++){
            double x=(e.getRandom().nextDouble()*2-1)*limit,z=(e.getRandom().nextDouble()*2-1)*limit;
            var candidate=new Place(x,z,width);
            if(fits(x,z,width,edge,id,others)&&supported.test(id,candidate))return candidate;
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
