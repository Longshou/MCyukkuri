package io.github.longshou.mcyukkuri.subspecies;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.phys.Vec3;

/** A walk inside the hat as a polyline of deck seat positions (hat units): its length, a point along it, and its synced form. */
final class HatSurfaceRoute {
    static double length(List<Vec3> route){double sum=0;for(int i=1;i<route.size();i++)sum+=route.get(i-1).distanceTo(route.get(i));return sum;}
    static Vec3 sample(List<Vec3> route,double fraction){
        double remaining=length(route)*Math.clamp(fraction,0,1);
        for(int i=1;i<route.size();i++){
            var a=route.get(i-1);var b=route.get(i);double size=a.distanceTo(b);
            if(remaining<=size)return a.lerp(b,size<1e-8?1:remaining/size);remaining-=size;
        }
        return route.getLast();
    }
    static void save(CompoundTag tag,List<Vec3> route){
        var list=new ListTag();for(var p:route){var point=new CompoundTag();point.putDouble("X",p.x);point.putDouble("Y",p.y);point.putDouble("Z",p.z);list.add(point);}tag.put("Route",list);
    }
    static List<Vec3> load(CompoundTag tag){
        var points=new ArrayList<Vec3>();for(var raw:tag.getList("Route",Tag.TAG_COMPOUND)){var p=(CompoundTag)raw;points.add(new Vec3(p.getDouble("X"),
                p.getDouble("Y"),p.getDouble("Z")));}return List.copyOf(points);
    }
    private HatSurfaceRoute() {}
}
