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
import net.minecraft.world.phys.AABB;

/** Routes over the hat's measured well, ledge and brim; occupied patches are impassable. */
final class HatSurfaceRoute {
    private static final double STEP=.4;
    private record Node(int index,double cost) implements Comparable<Node> {
        @Override public int compareTo(Node other){return Double.compare(cost,other.cost);}
    }
    static List<Vec3> find(HatVessel.Mode mode,Vec3 from,Vec3 to,double radius,List<double[]> occupied){
        return find(mode,from,to,radius,occupied,radius*2);
    }
    static List<Vec3> find(HatVessel.Mode mode,Vec3 from,Vec3 to,double radius,List<double[]> occupied,double height){
        return find(mode,from,to,radius,occupied,Vec3.ZERO,0,height,0);
    }
    static List<Vec3> find(HatVessel.Mode mode,Vec3 from,Vec3 to,double radius,List<double[]> occupied,Vec3 carriedOffset,double carriedRadius,double height,double carriedHeight){
        return find(mode,from,to,radius,occupied,carriedOffset,carriedRadius,
                new HatVessel.BodySpace(height,0,0,0,0),new HatVessel.BodySpace(carriedHeight,0,0,0,0));
    }
    static List<Vec3> find(HatVessel.Mode mode,Vec3 from,Vec3 to,double radius,List<double[]> occupied,HatVessel.BodySpace body){
        return find(mode,from,to,radius,occupied,Vec3.ZERO,0,body,null);
    }
    static List<Vec3> find(HatVessel.Mode mode,Vec3 from,Vec3 to,double radius,List<double[]> occupied,Vec3 carriedOffset,double carriedRadius,HatVessel.BodySpace body,HatVessel.BodySpace carriedBody){
        return find(mode,from,to,radius,occupied,carriedOffset,carriedRadius,body,carriedBody,at->true);
    }
    /** A specific real contact pair may add posed geometry; every ordinary obstacle check remains active. */
    static List<Vec3> find(HatVessel.Mode mode,Vec3 from,Vec3 to,double radius,List<double[]> occupied,Vec3 carriedOffset,double carriedRadius,HatVessel.BodySpace body,HatVessel.BodySpace carriedBody,java.util.function.Predicate<Vec3> contact){
        if(!clearPair(mode,to,radius,occupied,from,carriedOffset,carriedRadius,body,carriedBody)||!contact.test(to))return List.of();
        if(segment(mode,from,to,radius,occupied,from,carriedOffset,carriedRadius,body,carriedBody,contact))return List.of(from,to);
        int nx=(int)Math.ceil(HatVessel.BRIM[0]*2/STEP)+1,nz=(int)Math.ceil(HatVessel.BRIM[1]*2/STEP)+1;
        Vec3[] points=new Vec3[nx*nz+2]; int start=points.length-2,end=start+1;
        points[start]=from;points[end]=to;

        for(int x=0;x<nx;x++)for(int z=0;z<nz;z++){
            double px=-HatVessel.BRIM[0]+x*STEP,pz=-HatVessel.BRIM[1]+z*STEP;
            double h=HatVessel.travelSupport(mode,px,pz,radius);
            if(!Double.isFinite(h)||!Double.isFinite(HatVessel.surface(mode,px,pz)))continue;
            Vec3 at=new Vec3(px,h,pz);
            if(clearPair(mode,at,radius,occupied,from,carriedOffset,carriedRadius,body,carriedBody)&&contact.test(at))points[x*nz+z]=at;
        }
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
                if(points[next]==null||!segment(mode,points[at],points[next],radius,occupied,from,carriedOffset,carriedRadius,body,carriedBody,contact))continue;
                double cost=distance[at]+points[at].distanceTo(points[next]);
                if(cost+1e-8<distance[next]){distance[next]=cost;previous[next]=at;queue.add(new Node(next,cost));}
            }
        }
        if(previous[end]<0)return List.of();
        var path=new ArrayList<Vec3>();for(int at=end;at>=0;at=previous[at]){path.add(points[at]);if(at==start)break;}
        Collections.reverse(path);return List.copyOf(path);
    }
    private static double horizontal(Vec3 a,Vec3 b){return a.subtract(b).horizontalDistance();}
    private static List<AABB> volumes(Vec3 at,double radius,HatVessel.BodySpace body){
        double half=radius/HatVessel.radius(1)*.5;
        var volumes=new ArrayList<AABB>(2);
        volumes.add(new AABB(at.x-half,at.y,at.z-half,at.x+half,at.y+body.height(),at.z+half));
        if(body.hatX()>0)volumes.add(new AABB(at.x-body.hatX(),at.y+body.hatBase(),at.z-body.hatZ(),
                at.x+body.hatX(),at.y+body.hatBase()+body.hatHeight(),at.z+body.hatZ()));
        return volumes;
    }
    private static double overlap(AABB a,AABB b){
        // Retain a small soft-shell allowance, including when a parent covers a lower berth.
        return Math.min(Math.min(a.maxX-b.minX,b.maxX-a.minX),Math.min(Math.min(a.maxZ-b.minZ,b.maxZ-a.minZ),
                Math.min(a.maxY-b.minY,b.maxY-a.minY)-.05));
    }
    private static boolean clear(Vec3 at,double radius,List<double[]> occupied,Vec3 from,HatVessel.BodySpace body){
        var current=volumes(at,radius,body);var initial=volumes(from,radius,body);
        for(var other:occupied){
            var otherBody=new HatVessel.BodySpace(other.length>4?other[4]:other[3]*2,
                    other.length>=10?other[6]:0,other.length>=10?other[7]:0,
                    other.length>=10?other[8]:0,other.length>=10?other[9]:0);
            // Cargo has an authored containing cylinder, not the yukkuri's soft contact radius.
            double otherRadius=other.length>5&&other[5]==2?other[3]*HatVessel.radius(1)*2:other[3];
            for(var obstacle:volumes(new Vec3(other[0],other[1],other[2]),otherRadius,otherBody)){
                for(int i=0;i<current.size();i++){
                    double depth=overlap(current.get(i),obstacle);
                    if(depth<=0)continue;
                    double before=overlap(initial.get(i),obstacle);
                    // A crowded old save may move out of an overlap, but must never deepen it.
                    if(before<=0||depth>before+1e-4
                            ||Math.hypot(at.x-other[0],at.z-other[2])<Math.hypot(from.x-other[0],from.z-other[2])-.01)return false;
                }
            }
        }
        return true;
    }
    private static boolean clearPair(HatVessel.Mode mode,Vec3 at,double radius,List<double[]> occupied,Vec3 from,Vec3 offset,double carriedRadius,HatVessel.BodySpace body,HatVessel.BodySpace carriedBody){
        return HatVessel.cabinVolumeClear(mode,at.x,at.y,at.z,radius,body)&&clear(at,radius,occupied,from,body)
                &&(carriedRadius<=0||HatVessel.cabinVolumeClear(mode,at.x+offset.x,at.y+offset.y,at.z+offset.z,carriedRadius,carriedBody)
                    &&clear(at.add(offset),carriedRadius,occupied,from.add(offset),carriedBody));
    }
    /** Lift/lower portions of a care transfer have no floor-support requirement, but retain the whole envelope. */
    static boolean transferClear(HatVessel.Mode mode,Vec3 from,Vec3 to,double radius,List<double[]> occupied,HatVessel.BodySpace body){
        int steps=Math.max(1,(int)Math.ceil(from.distanceTo(to)/.05));
        for(int i=0;i<=steps;i++){
            var at=from.lerp(to,(double)i/steps);
            if(!HatVessel.cabinVolumeClear(mode,at.x,at.y,at.z,radius,body)||!clear(at,radius,occupied,from,body))return false;
        }
        return true;
    }
    private static boolean segment(HatVessel.Mode mode,Vec3 a,Vec3 b,double radius,List<double[]> occupied,Vec3 from,Vec3 offset,double carriedRadius,HatVessel.BodySpace body,HatVessel.BodySpace carriedBody,java.util.function.Predicate<Vec3> contact){
        int steps=Math.max(1,(int)Math.ceil(a.distanceTo(b)/.05));
        for(int i=0;i<=steps;i++){
            var p=a.lerp(b,(double)i/steps);
            double support=HatVessel.travelSupport(mode,p.x,p.z,radius);
            if(!Double.isFinite(HatVessel.surface(mode,p.x,p.z))||!Double.isFinite(support))return false;
            if(support>Math.max(a.y,b.y)+.001)return false;
            // Match walkingLocal exactly: lift the whole body before moving into a riser.
            p=new Vec3(p.x,Math.max(p.y,support),p.z);
            if(!clearPair(mode,p,radius,occupied,from,offset,carriedRadius,body,carriedBody)||!contact.test(p))return false;
        }
        double step=HatVessel.maxSurfaceStep(mode);
        return Math.abs(a.y-b.y)<=step+.001; // one measured layer at a time, never floor-to-brim
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
