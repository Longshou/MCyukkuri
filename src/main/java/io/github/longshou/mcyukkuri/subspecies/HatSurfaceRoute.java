package io.github.longshou.mcyukkuri.subspecies;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.phys.Vec3;

/** Routes over the hat's measured well, ledge and brim; occupied patches are impassable. */
final class HatSurfaceRoute {
    private static final double STEP=.4;
    private record Node(int index,double cost) implements Comparable<Node> {
        @Override public int compareTo(Node other){return Double.compare(cost,other.cost);}
    }
    static List<Vec3> find(HatVessel.Mode mode,Vec3 from,Vec3 to,double radius,List<double[]> occupied){
        int nx=(int)Math.ceil(HatVessel.BRIM[0]*2/STEP)+1,nz=(int)Math.ceil(HatVessel.BRIM[1]*2/STEP)+1;
        Vec3[] points=new Vec3[nx*nz+2]; int start=points.length-2,end=start+1;
        points[start]=from;points[end]=to;
        double support=Math.min(1.05,Math.max(.25,radius*.3));
        for(int x=0;x<nx;x++)for(int z=0;z<nz;z++){
            double px=-HatVessel.BRIM[0]+x*STEP,pz=-HatVessel.BRIM[1]+z*STEP;
            double h=HatVessel.footprint(mode,px,pz,support);
            if(!Double.isFinite(h)||!Double.isFinite(HatVessel.surface(mode,px,pz)))continue;
            Vec3 at=new Vec3(px,h,pz);
            if(clear(at,radius,occupied,from))points[x*nz+z]=at;
        }
        if(!clear(to,radius,occupied,from))return List.of();
        double[] distance=new double[points.length];Arrays.fill(distance,Double.POSITIVE_INFINITY);
        int[] previous=new int[points.length];Arrays.fill(previous,-1);
        var queue=new PriorityQueue<Node>();distance[start]=0;queue.add(new Node(start,0));
        while(!queue.isEmpty()){
            var node=queue.poll();int at=node.index();if(node.cost()>distance[at]+1e-8)continue;
            if(at==end)break;
            var candidates=new ArrayList<Integer>();
            if(at==start){for(int i=0;i<start;i++)if(points[i]!=null&&horizontal(points[i],from)<1.0)candidates.add(i);}
            else{
                int x=at/nz,z=at%nz;
                for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)if((dx!=0||dz!=0)&&x+dx>=0&&x+dx<nx&&z+dz>=0&&z+dz<nz)candidates.add((x+dx)*nz+z+dz);
            }
            if(horizontal(points[at],to)<1.0)candidates.add(end);
            for(int next:candidates){
                if(points[next]==null||!segment(mode,points[at],points[next],radius,occupied,from))continue;
                double cost=distance[at]+points[at].distanceTo(points[next]);
                if(cost+1e-8<distance[next]){distance[next]=cost;previous[next]=at;queue.add(new Node(next,cost));}
            }
        }
        if(previous[end]<0)return List.of();
        var path=new ArrayList<Vec3>();for(int at=end;at>=0;at=previous[at]){path.add(points[at]);if(at==start)break;}
        Collections.reverse(path);return List.copyOf(path);
    }
    private static double horizontal(Vec3 a,Vec3 b){return a.subtract(b).horizontalDistance();}
    private static boolean clear(Vec3 at,double radius,List<double[]> occupied,Vec3 from){
        for(var other:occupied){
            double clearance=(radius+other[3])*.70;
            double distance=Math.hypot(at.x-other[0],at.z-other[2]);
            double initial=Math.hypot(from.x-other[0],from.z-other[2]);
            // Existing crowded saves may leave an overlap; only allow movement that leaves it.
            if(distance<clearance&&distance<initial-.01)return false;
            if(initial>=clearance&&distance<clearance)return false;
        }
        return true;
    }
    private static boolean segment(HatVessel.Mode mode,Vec3 a,Vec3 b,double radius,List<double[]> occupied,Vec3 from){
        for(int i=0;i<=5;i++){
            var p=a.lerp(b,i/5.0);
            double support = HatVessel.footprint(mode,p.x,p.z,Math.min(1.05,Math.max(.25,radius*.3)));
            if(!Double.isFinite(HatVessel.surface(mode,p.x,p.z))||!Double.isFinite(support)||!clear(p,radius,occupied,from))return false;
            if(support>Math.max(a.y,b.y)+.001)return false;
        }
        return Math.abs(a.y-b.y)<=2.1; // measured inner steps; never jump across the well or open water
    }
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
        var points=new ArrayList<Vec3>();for(var raw:tag.getList("Route",Tag.TAG_COMPOUND)){var p=(CompoundTag)raw;points.add(new Vec3(p.getDouble("X"),p.getDouble("Y"),p.getDouble("Z")));}return List.copyOf(points);
    }
}
