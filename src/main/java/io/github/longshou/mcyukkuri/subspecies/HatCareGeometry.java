package io.github.longshou.mcyukkuri.subspecies;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.EnumMap;
import java.util.LinkedHashMap;

/** Fitted, posed surface checks for the finite mouth-pickup and carry stages.
 * Model data is immutable and loaded once. A Context belongs to one planning attempt, never to a global
 * entity/position cache. Ordinary walking still uses HatSurfaceRoute's conservative volume checks. */
public final class HatCareGeometry {
    private static final double EPS=1e-6;
    /**
     * Upper bound on triangle-pair and ray tests for one pair check. Two close but not touching fitted bodies overlap in every
     * bounding volume, so the surface search has nothing to prune and costs the product of their triangle counts: one such pose
     * kept the server thread inside a single tick for over nine minutes. A check that runs out of budget is a refusal, exactly as
     * if the pose were blocked (callers already keep their safe refusal for that).
     */
    private static final int BUDGET=2_000_000;
    private static final ThreadLocal<int[]> SPENT=ThreadLocal.withInitial(()->new int[]{-1});   // -1: not counting
    private static final class OverBudget extends RuntimeException{OverBudget(){super(null,null,false,false);}}
    private static final OverBudget OVER=new OverBudget();
    private static void spend(){var spent=SPENT.get();if(spent[0]>=0&&++spent[0]>BUDGET)throw OVER;}
    private record SourcePart(String name,float[] xyz,int[] indices,int[][] components,boolean hat,boolean closed,SourcePart parent){}
    private record VertexKey(int x,int y,int z){}
    private static final Map<String,List<SourcePart>> MODELS=readModels();
    private static final Map<HatVessel.Mode,List<AABB>> WALLS=readWalls();

    private static Map<String,List<SourcePart>> readModels(){
        try(var raw=HatCareGeometry.class.getResourceAsStream("/assets/mcyukkuri/yukkuri_presentation/hat_carry_geometry.json.gz")){
            if(raw==null)throw new IllegalStateException("Missing fitted hat care geometry");
            try(var zip=new GZIPInputStream(raw);var reader=new InputStreamReader(zip,StandardCharsets.UTF_8)){
                var data=JsonParser.parseReader(reader).getAsJsonObject();
                if(data.get("format").getAsInt()!=1)throw new IllegalStateException("Unsupported hat care geometry");
                var models=new HashMap<String,List<SourcePart>>();
                for(var model:data.getAsJsonObject("models").entrySet()){
                    var parts=new ArrayList<SourcePart>();
                    for(var item:model.getValue().getAsJsonObject().getAsJsonArray("parts")){
                        var p=item.getAsJsonObject();var values=p.getAsJsonArray("triangles");
                        if(values.isEmpty()||values.size()%9!=0)throw new IllegalStateException("Invalid fitted triangles");
                        float[] xyz=new float[values.size()];
                        for(int i=0;i<xyz.length;i++){
                            xyz[i]=values.get(i).getAsFloat();
                            if(!Float.isFinite(xyz[i]))throw new IllegalStateException("Non-finite fitted triangle");
                        }
                        var source=indexed(p.get("name").getAsString(),xyz,p.get("hat").getAsBoolean(),p.get("body").getAsBoolean());
                        if(p.has("parent_triangles")){
                            var alternate=p.getAsJsonArray("parent_triangles");
                            if(alternate.size()!=xyz.length)throw new IllegalStateException("Mismatched parent carry triangles");
                            float[] parent=new float[xyz.length];
                            for(int i=0;i<parent.length;i++){
                                parent[i]=alternate.get(i).getAsFloat();
                                if(!Float.isFinite(parent[i]))throw new IllegalStateException("Non-finite parent carry triangle");
                            }
                            if(!Arrays.equals(parent,xyz))source=new SourcePart(source.name,source.xyz,source.indices,source.components,source.hat,source.closed,
                                    indexed(source.name,parent,source.hat,source.closed));
                        }
                        parts.add(source);
                    }
                    models.put(model.getKey(),List.copyOf(parts));
                }
                return Map.copyOf(models);
            }
        }catch(IOException error){throw new UncheckedIOException(error);}
    }

    /** Exact shared edges define components; a part may contain several disconnected shells. The same
     * topology also identifies closed accessory solids and avoids transforming duplicate source vertices. */
    private static SourcePart indexed(String name,float[] raw,boolean hat,boolean body){
        var vertices=new HashMap<VertexKey,Integer>();var unique=new ArrayList<VertexKey>();
        int[] indices=new int[raw.length/3];
        for(int i=0;i<indices.length;i++){
            int k=i*3;var key=new VertexKey(bits(raw[k]),bits(raw[k+1]),bits(raw[k+2]));
            Integer index=vertices.get(key);
            if(index==null){index=unique.size();vertices.put(key,index);unique.add(key);}indices[i]=index;
        }
        float[] xyz=new float[unique.size()*3];
        for(int i=0;i<unique.size();i++){
            var p=unique.get(i);xyz[i*3]=Float.intBitsToFloat(p.x);xyz[i*3+1]=Float.intBitsToFloat(p.y);xyz[i*3+2]=Float.intBitsToFloat(p.z);
        }
        int count=indices.length/3;int[] roots=new int[count],ranks=new int[count];for(int i=0;i<count;i++)roots[i]=i;
        // Each entry records the first incident face and the number of incident faces.
        var edges=new HashMap<Long,int[]>();
        for(int i=0;i<count;i++)for(int e=0;e<3;e++){
            int a=indices[i*3+e],b=indices[i*3+(e+1)%3];if(a==b)continue;
            long key=((long)Math.min(a,b)<<32)|(Math.max(a,b)&0xffffffffL);var edge=edges.get(key);
            if(edge==null)edges.put(key,new int[]{i,1});else{
                edge[1]++;int x=find(roots,i),y=find(roots,edge[0]);
                if(x!=y){if(ranks[x]<ranks[y])roots[x]=y;else{roots[y]=x;if(ranks[x]==ranks[y])ranks[x]++;}}
            }
        }
        var groups=new LinkedHashMap<Integer,List<Integer>>();
        for(int i=0;i<count;i++)groups.computeIfAbsent(find(roots,i),key->new ArrayList<>()).add(i);
        int[][] components=new int[groups.size()][];int c=0;
        for(var faces:groups.values())components[c++]=faces.stream().mapToInt(Integer::intValue).toArray();
        boolean closed=body||!edges.isEmpty()&&edges.values().stream().allMatch(edge->edge[1]==2);
        return new SourcePart(name,xyz,indices,components,hat,closed,null);
    }
    private static int bits(float value){return Float.floatToIntBits(value==0?0:value);}
    private static int find(int[] roots,int i){while(roots[i]!=i){roots[i]=roots[roots[i]];i=roots[i];}return i;}

    /**
     * Solid wall strips from the selected hull's exported cavity sections.  Land data has rectangles;
     * the aquatic data supplies narrow z strips that follow its rounded cavity, so no care pose can
     * enter the empty corners of the old rectangular land hat.
     */
    private static Map<HatVessel.Mode,List<AABB>> readWalls(){
        var result=new EnumMap<HatVessel.Mode,List<AABB>>(HatVessel.Mode.class);
        for(var mode:HatVessel.Mode.values()){
            JsonObject data=HatVessel.presentation(mode.aquatic());
            double top=HatVessel.top(mode),upper=top,squash=HatVessel.squash(mode);
            var brim=HatVessel.brim(mode);double brimX=brim[0],brimZ=brim[1];
            var walls=new ArrayList<AABB>();
            for(var item:data.getAsJsonArray("cavity_sections")){
                var section=item.getAsJsonObject();var rect=section.getAsJsonArray("rect");
                double lower=top-section.get("depth").getAsDouble()*squash;
                var strips=new ArrayList<double[]>();
                if(section.has("strips"))for(var value:section.getAsJsonArray("strips")){
                    var strip=value.getAsJsonArray();strips.add(new double[]{strip.get(0).getAsDouble(),strip.get(1).getAsDouble(),strip.get(2).getAsDouble(),strip.get(3).getAsDouble()});
                }
                if(strips.isEmpty())strips.add(new double[]{rect.get(0).getAsDouble(),rect.get(1).getAsDouble(),rect.get(2).getAsDouble(),rect.get(3).getAsDouble()});
                double zMin=Double.POSITIVE_INFINITY,zMax=Double.NEGATIVE_INFINITY;
                for(var strip:strips){
                    zMin=Math.min(zMin,strip[2]);zMax=Math.max(zMax,strip[3]);
                    // Within each exported z band, the two side slabs exactly bound its rounded width.
                    walls.add(new AABB(-brimX,lower,strip[2],strip[0],upper,strip[3]));
                    walls.add(new AABB(strip[1],lower,strip[2],brimX,upper,strip[3]));
                }
                // The front and rear slabs close the strip union without filling the rounded cavity between bands.
                walls.add(new AABB(-brimX,lower,-brimZ,brimX,upper,zMin));
                walls.add(new AABB(-brimX,lower,zMax,brimX,upper,brimZ));
                upper=lower;
            }
            walls.add(new AABB(-brimX,-1e6,-brimZ,brimX,upper,brimZ));
            result.put(mode,List.copyOf(walls));
        }
        return Map.copyOf(result);
    }

    /** Null means the selected model/pose is unsupported; callers retain their existing safe refusal. */
    public static Context plan(YukkuriEntity parent,YukkuriEntity child,double unit,Vec3 direction){
        if(!Double.isFinite(unit)||unit<=0||!finite(direction)||direction.horizontalDistanceSqr()<1e-12
                // Pregnancy has bone-specific rigid accessory lifts, absent from this fitted-root contract.
                ||parent.livePregnancyProgress()>0||child.livePregnancyProgress()>0
                ||!MODELS.containsKey(parent.subspecies().assetId())||!MODELS.containsKey(child.subspecies().assetId()))return null;
        double yaw=Math.atan2(-direction.x,-direction.z);
        Shape p=shape(parent,"boat_carry_young",0,unit,yaw),c=shape(child,"boat_be_carried",0,unit,yaw+Math.PI);
        return p==null||c==null?null:new Context(child,unit,yaw+Math.PI,p,c);
    }

    public static final class Context {
        private final YukkuriEntity child;
        private final double unit,yaw;
        private final Shape parentShape,childShape;
        // At most 26 animation samples per short-lived attempt; growth/positions never become permanent keys.
        private final Shape[][] transition=new Shape[2][13];
        private Vec3 previousDelta;
        private boolean previousPairClear;
        private String failure="not checked";
        private Context(YukkuriEntity child,double unit,double yaw,Shape p,Shape c){this.child=child;this.unit=unit;this.yaw=yaw;parentShape=p;childShape=c;}

        /** Only the designated parent/child pair. Repeated rigid-carry offsets reuse the result. */
        public boolean pickupClear(Vec3 parentAt,Vec3 childAt){
            if(!finite(parentAt)||!finite(childAt)){failure="non-finite position";return false;}
            Vec3 delta=childAt.subtract(parentAt);
            if(previousDelta==null||delta.distanceToSqr(previousDelta)>=1e-18){
                previousDelta=delta;SPENT.get()[0]=0;
                boolean hit;
                try{hit=collides(parentShape,Vec3.ZERO,childShape,delta);}
                catch(OverBudget over){previousPairClear=false;failure="surface search over budget (treated as blocked)";return false;}
                finally{SPENT.get()[0]=-1;}
                previousPairClear=!hit;
            }
            failure=previousPairClear?"clear":"parent/child surfaces or containment";return previousPairClear;
        }
        public String debug(){return failure;}
        /** Endpoints and the final selected route, not an exhaustive route-node predicate. */
        public boolean clear(Vec3 parentAt,Vec3 childAt,HatVessel.Mode mode,List<double[]> obstacles){
            if(!pickupClear(parentAt,childAt))return false;
            String blocked=environmentFailure(parentShape,parentAt,mode,obstacles);
            if(blocked!=null){failure="parent "+blocked;return false;}
            blocked=environmentFailure(childShape,childAt,mode,obstacles);
            if(blocked!=null){failure="child "+blocked;return false;}
            failure="clear";return true;
        }
        /** Check the full authored preparation at server tick resolution before starting it. */
        public boolean prepareClear(Vec3 childAt,HatVessel.Mode mode,List<double[]> obstacles){
            for(int i=0;i<=12;i++)if(!preparationClear(childAt,mode,obstacles,i*.05,false))return false;
            return true;
        }
        /** The planner/prepare phase can retain thirteen full meshes; custody no longer needs those samples. */
        public void discardPreparationSamples(){Arrays.fill(transition[0],null);}
        /** Tick-time preparation/release; seconds must be an authored 0.05-second server sample. */
        public boolean preparationClear(Vec3 childAt,HatVessel.Mode mode,List<double[]> obstacles,double seconds,boolean release){
            if(!finite(childAt)||!Double.isFinite(seconds)){failure="non-finite preparation";return false;}
            int index=(int)Math.round(Math.clamp(seconds,0,.6)*20),row=release?1:0;
            if(Math.abs(Math.clamp(seconds,0,.6)-index*.05)>1e-6){failure="non-tick preparation sample";return false;}
            Shape s=transition[row][index];
            if(s==null){s=shape(child,release?"boat_release_carry":"boat_prepare_carry",index*.05,unit,yaw);transition[row][index]=s;}
            String blocked=s==null?"unsupported pose":environmentFailure(s,childAt,mode,obstacles);
            failure=blocked==null?"clear":(release?"release ":"prepare ")+index+" "+blocked;return blocked==null;
        }
    }

    private static boolean finite(Vec3 p){return p!=null&&Double.isFinite(p.x)&&Double.isFinite(p.y)&&Double.isFinite(p.z);}
    private record Component(Vec3 sample,Triangle[] faces){}
    private record Part(String name,Tree surface,Component[] components,boolean closed){}
    private record Shape(PartTree parts,AABB bounds){}
    private static Shape shape(YukkuriEntity actor,String action,double seconds,double unit,double yaw){
        var root=HatCareAnchor.root(actor,action,seconds);if(root==null)return null;
        double scale=actor.getScale()*actor.growthScale()/16/unit;
        if(!Double.isFinite(scale)||scale<=0)return null;
        var parts=new ArrayList<Part>();AABB bounds=null;var anchor=HatVessel.anchor();
        float hat=HatVessel.hatMultiplier(actor.subspeciesFlags());double cos=Math.cos(yaw),sin=Math.sin(yaw);
        for(var original:MODELS.get(actor.subspecies().assetId())){
            var source=action.equals("boat_carry_young")&&original.parent!=null?original.parent:original;
            if(source.hat()&&!actor.life().hasAccessory())continue;
            Vec3[] vertices=new Vec3[source.xyz().length/3];
            for(int i=0;i<vertices.length;i++){
                int k=i*3;var p=new Vector3f(source.xyz()[k],source.xyz()[k+1],source.xyz()[k+2]);
                if(source.hat())p.sub(anchor).mul(hat).add(anchor);
                root.transformPosition(p);
                // Vec3.yRot convention (opposite the usual right-handed mathematical Y matrix).
                vertices[i]=new Vec3((p.x*cos+p.z*sin)*scale,p.y*scale,(p.z*cos-p.x*sin)*scale);
            }
            Triangle[] triangles=new Triangle[source.indices().length/3];
            for(int i=0;i<triangles.length;i++){
                triangles[i]=new Triangle(vertices[source.indices()[i*3]],vertices[source.indices()[i*3+1]],vertices[source.indices()[i*3+2]]);
            }
            Component[] components=new Component[source.components().length];
            for(int i=0;i<components.length;i++){
                int[] faces=source.components()[i];Triangle[] connected=new Triangle[faces.length];
                for(int j=0;j<faces.length;j++)connected[j]=triangles[faces[j]];
                var first=connected[0];components[i]=new Component(first.a.add(first.b).add(first.c).scale(1.0/3),connected);
            }
            Tree tree=Tree.build(triangles,0,triangles.length);parts.add(new Part(source.name(),tree,components,source.closed()));
            bounds=bounds==null?tree.bounds:bounds.minmax(tree.bounds);
        }
        return bounds==null?null:new Shape(PartTree.build(parts.toArray(Part[]::new),0,parts.size()),bounds);
    }

    private static String environmentFailure(Shape shape,Vec3 at,HatVessel.Mode mode,List<double[]> occupied){
        if(mode==null||!finite(at))return "invalid environment";
        int wallIndex=0;
        for(var wall:WALLS.get(mode)){
            String part=boxCollision(shape,at,wall);if(part!=null)return "wall="+wallIndex+" part="+part;wallIndex++;
        }
        int otherIndex=0;
        for(var other:occupied){
            if(other.length<4)return "invalid obstacle";
            double r=other.length>5&&other[5]==2?other[3]:other[3]/HatVessel.radius(1)*.5;
            double h=other.length>4?other[4]:other[3]*2;
            String part=boxCollision(shape,at,new AABB(other[0]-r,other[1],other[2]-r,other[0]+r,other[1]+h,other[2]+r));
            if(part!=null)return "obstacle="+otherIndex+" body part="+part;
            if(other.length>=10&&other[6]>0){
                part=boxCollision(shape,at,new AABB(other[0]-other[6],other[1]+other[8],other[2]-other[7],
                        other[0]+other[6],other[1]+other[8]+other[9],other[2]+other[7]));
                if(part!=null)return "obstacle="+otherIndex+" hat part="+part;
            }
            otherIndex++;
        }
        return null;
    }
    private static String boxCollision(Shape shape,Vec3 at,AABB obstacle){
        AABB box=obstacle.move(-at.x,-at.y,-at.z);
        if(!overlaps(shape.bounds,box))return null;
        return boxCollision(shape.parts,box);
    }
    private static String boxCollision(PartTree tree,AABB box){
        if(!overlaps(tree.bounds,box))return null;
        if(tree.left!=null){String hit=boxCollision(tree.left,box);return hit!=null?hit:boxCollision(tree.right,box);}
        for(int i=tree.start;i<tree.end;i++){
            var part=tree.parts[i];if(!overlaps(part.surface.bounds,box))continue;
            if(part.surface.intersects(box))return part.name;
            if(part.closed&&inside(part.surface,box.getCenter()))return part.name+" contains obstacle";
        }
        return null;
    }
    private static boolean collides(Shape a,Vec3 at,Shape b,Vec3 bt){
        Vec3 delta=bt.subtract(at);
        return collides(a.parts,b.parts,delta);
    }
    private static boolean collides(PartTree a,PartTree b,Vec3 delta){
        if(!overlaps(a.bounds,b.bounds,delta))return false;
        if(a.left!=null&&(b.left==null||a.end-a.start>=b.end-b.start))return collides(a.left,b,delta)||collides(a.right,b,delta);
        if(b.left!=null)return collides(a,b.left,delta)||collides(a,b.right,delta);
        for(int i=a.start;i<a.end;i++)for(int j=b.start;j<b.end;j++){
            var pa=a.parts[i];var pb=b.parts[j];
            if(!overlaps(pa.surface.bounds,pb.surface.bounds,delta))continue;
            spend();
            if(intersects(pa.surface,pb.surface,delta))return true;
            // No crossing: connected closed solids can still wholly contain the other surface.
            if(pa.closed&&anyInside(pb,delta,pa.surface))return true;
            if(pb.closed&&anyInside(pa,delta.scale(-1),pb.surface))return true;
        }
        return false;
    }

    private static boolean overlaps(AABB a,AABB b){return a.maxX>b.minX+EPS&&b.maxX>a.minX+EPS&&a.maxY>b.minY+EPS&&b.maxY>a.minY+EPS&&a.maxZ>b.minZ+EPS&&b.maxZ>a.minZ+EPS;}
    private static boolean overlaps(AABB a,AABB b,Vec3 d){return a.maxX>b.minX+d.x+EPS&&b.maxX+d.x>a.minX+EPS&&a.maxY>b.minY+d.y+EPS&&b.maxY+d.y>a.minY+EPS&&
            a.maxZ>b.minZ+d.z+EPS&&b.maxZ+d.z>a.minZ+EPS;}
    // Bounds must retain zero-thickness triangle planes; only solid-level broad phases use strict overlap.
    private static boolean touches(AABB a,AABB b){return a.maxX>=b.minX-EPS&&b.maxX>=a.minX-EPS&&a.maxY>=b.minY-EPS&&b.maxY>=a.minY-EPS&&a.maxZ>=b.minZ-EPS&&b.maxZ>=a.minZ-EPS;}
    private static boolean touches(AABB a,AABB b,Vec3 d){return a.maxX>=b.minX+d.x-EPS&&b.maxX+d.x>=a.minX-EPS&&a.maxY>=b.minY+d.y-EPS&&b.maxY+d.y>=a.minY-EPS&&
            a.maxZ>=b.minZ+d.z-EPS&&b.maxZ+d.z>=a.minZ-EPS;}
    /** Broad phase across parts too: the fitted furry variant contains over a thousand separate parts. */
    private static final class PartTree {
        final AABB bounds;final PartTree left,right;final Part[] parts;final int start,end;
        private PartTree(AABB bounds,PartTree left,PartTree right,Part[] parts,int start,int end){this.bounds=bounds;this.left=left;this.right=right;this.parts=parts;this.start=start;this.end=end;}
        static PartTree build(Part[] parts,int start,int end){
            AABB bounds=parts[start].surface.bounds;for(int i=start+1;i<end;i++)bounds=bounds.minmax(parts[i].surface.bounds);
            if(end-start<=4)return new PartTree(bounds,null,null,parts,start,end);
            double x=bounds.maxX-bounds.minX,y=bounds.maxY-bounds.minY,z=bounds.maxZ-bounds.minZ;int axis=x>=y&&x>=z?0:y>=z?1:2;
            Arrays.sort(parts,start,end,Comparator.comparingDouble(p->axis==0?p.surface.bounds.minX+p.surface.bounds.maxX:axis==1?p.surface.bounds.minY+
                    p.surface.bounds.maxY:p.surface.bounds.minZ+p.surface.bounds.maxZ));
            int middle=(start+end)/2;return new PartTree(bounds,build(parts,start,middle),build(parts,middle,end),parts,start,end);
        }
    }
    private record Triangle(Vec3 a,Vec3 b,Vec3 c,AABB bounds){
        Triangle(Vec3 a,Vec3 b,Vec3 c){this(a,b,c,new AABB(Math.min(a.x,Math.min(b.x,c.x)),Math.min(a.y,Math.min(b.y,c.y)),Math.min(a.z,Math.min(b.z,c.z)),
                Math.max(a.x,Math.max(b.x,c.x)),Math.max(a.y,Math.max(b.y,c.y)),Math.max(a.z,Math.max(b.z,c.z))));}
        Vec3 normal(){return b.subtract(a).cross(c.subtract(a));}
        Vec3[] edges(){return new Vec3[]{b.subtract(a),c.subtract(b),a.subtract(c)};}
    }
    private static final class Tree {
        final AABB bounds;final Tree left,right;final Triangle[] triangles;final int start,end;
        private Tree(AABB bounds,Tree left,Tree right,Triangle[] triangles,int start,
                int end){this.bounds=bounds;this.left=left;this.right=right;this.triangles=triangles;this.start=start;this.end=end;}
        static Tree build(Triangle[] triangles,int start,int end){
            AABB bounds=triangles[start].bounds;for(int i=start+1;i<end;i++)bounds=bounds.minmax(triangles[i].bounds);
            if(end-start<=8)return new Tree(bounds,null,null,triangles,start,end);
            double x=bounds.maxX-bounds.minX,y=bounds.maxY-bounds.minY,z=bounds.maxZ-bounds.minZ;
            int axis=x>=y&&x>=z?0:y>=z?1:2;
            Arrays.sort(triangles,start,end,Comparator.comparingDouble(t->axis==0?t.bounds.minX+t.bounds.maxX:axis==1?t.bounds.minY+t.bounds.maxY:t.bounds.minZ+t.bounds.maxZ));
            int middle=(start+end)/2;return new Tree(bounds,build(triangles,start,middle),build(triangles,middle,end),triangles,start,end);
        }
        boolean intersects(AABB box){
            if(!touches(bounds,box))return false;
            if(left!=null)return left.intersects(box)||right.intersects(box);
            for(int i=start;i<end;i++)if(triangleBox(triangles[i],box))return true;return false;
        }
    }
    private static boolean intersects(Tree a,Tree b,Vec3 delta){
        if(!touches(a.bounds,b.bounds,delta))return false;
        if(a.left!=null&&(b.left==null||a.end-a.start>=b.end-b.start))return intersects(a.left,b,delta)||intersects(a.right,b,delta);
        if(b.left!=null)return intersects(a,b.left,delta)||intersects(a,b.right,delta);
        for(int i=a.start;i<a.end;i++)for(int j=b.start;j<b.end;j++){
            var t=a.triangles[i];var u=b.triangles[j];
            if(touches(t.bounds,u.bounds,delta)){spend();if(triangleTriangle(t,u,delta))return true;}
        }
        return false;
    }

    private static boolean separates(Vec3 axis,Triangle a,Triangle b,Vec3 delta){
        double length=axis.length();if(length<1e-12)return false;
        double shift=axis.dot(delta),a0=axis.dot(a.a),a1=axis.dot(a.b),a2=axis.dot(a.c),b0=axis.dot(b.a)+shift,b1=axis.dot(b.b)+shift,b2=axis.dot(b.c)+shift,epsilon=EPS*length;
        return Math.max(a0,Math.max(a1,a2))<=Math.min(b0,Math.min(b1,b2))+epsilon
                ||Math.max(b0,Math.max(b1,b2))<=Math.min(a0,Math.min(a1,a2))+epsilon;
    }
    private static boolean triangleTriangle(Triangle a,Triangle b,Vec3 delta){
        Vec3 na=a.normal(),nb=b.normal();double aa=na.lengthSqr(),bb=nb.lengthSqr();
        if(aa<1e-20||bb<1e-20)return false;
        Vec3[] ae=a.edges(),be=b.edges();
        if(na.cross(nb).lengthSqr()<1e-16*aa*bb){
            if(Math.abs(na.dot(b.a.subtract(a.a))+na.dot(delta))>EPS*Math.sqrt(aa))return false;
            // In-plane axes handle coplanar triangles, including containment; shared edges alone are contact.
            for(var e:ae)if(separates(na.cross(e),a,b,delta))return false;
            for(var e:be)if(separates(na.cross(e),a,b,delta))return false;
            return true;
        }
        if(separates(na,a,b,delta)||separates(nb,a,b,delta))return false;
        for(var e:ae)for(var f:be)if(separates(e.cross(f),a,b,delta))return false;
        return true;
    }
    private static boolean triangleBox(Triangle t,AABB box){
        if(!touches(t.bounds,box))return false;
        Vec3 center=box.getCenter(),half=new Vec3((box.maxX-box.minX)/2,(box.maxY-box.minY)/2,(box.maxZ-box.minZ)/2);
        Vec3[] axes={new Vec3(1,0,0),new Vec3(0,1,0),new Vec3(0,0,1)};
        for(var axis:axes)if(separatesBox(axis,t,center,half))return false;
        if(separatesBox(t.normal(),t,center,half))return false;
        for(var edge:t.edges())for(var axis:axes)if(separatesBox(edge.cross(axis),t,center,half))return false;
        return true;
    }
    private static boolean separatesBox(Vec3 axis,Triangle t,Vec3 center,Vec3 half){
        double length=axis.length();if(length<1e-12)return false;
        double q=axis.dot(center),r=Math.abs(axis.x)*half.x+Math.abs(axis.y)*half.y+Math.abs(axis.z)*half.z;
        double a=axis.dot(t.a)-q,b=axis.dot(t.b)-q,c=axis.dot(t.c)-q;
        return Math.min(a,Math.min(b,c))>=r-EPS*length||Math.max(a,Math.max(b,c))<=-r+EPS*length;
    }

    private static boolean anyInside(Part surface,Vec3 delta,Tree solid){
        // Called only after the complete surface BVHs found no crossing. Containment is then constant on
        // each connected component: do not raycast every triangle vertex at every pickup route node.
        for(var component:surface.components){
            int classification=classify(solid,component.sample.add(delta));
            if(classification>0)return true;
            if(classification<0){
                // Exact contact at the representative is ambiguous, not proof the component is outside.
                // Preserve the former conservative check for this rare boundary case only.
                for(var t:component.faces)if(inside(solid,t.a.add(delta))||inside(solid,t.b.add(delta))||inside(solid,t.c.add(delta)))return true;
            }
        }
        return false;
    }
    private static final Vec3 RAY=new Vec3(1,.3713906763541037,.529173548271).normalize();
    private static boolean inside(Tree solid,Vec3 point){return classify(solid,point)>0;}
    /** 1 inside, 0 outside, -1 boundary/rounding ambiguity. */
    private static int classify(Tree solid,Vec3 point){
        if(point.x<solid.bounds.minX-EPS||point.x>solid.bounds.maxX+EPS||point.y<solid.bounds.minY-EPS
                ||point.y>solid.bounds.maxY+EPS||point.z<solid.bounds.minZ-EPS||point.z>solid.bounds.maxZ+EPS)return 0;
        if(point.x<=solid.bounds.minX+EPS||point.x>=solid.bounds.maxX-EPS||point.y<=solid.bounds.minY+EPS
                ||point.y>=solid.bounds.maxY-EPS||point.z<=solid.bounds.minZ+EPS||point.z>=solid.bounds.maxZ-EPS)return -1;
        var hits=new ArrayList<Double>();rayHits(solid,point,hits);hits.sort(Double::compare);
        int crossings=0;double last=-1;
        for(double hit:hits){if(hit<=EPS)return -1;if(last<0||hit-last>EPS){crossings++;last=hit;}}
        return crossings&1;
    }
    private static void rayHits(Tree tree,Vec3 origin,List<Double> hits){
        if(!rayBox(tree.bounds,origin))return;
        if(tree.left!=null){rayHits(tree.left,origin,hits);rayHits(tree.right,origin,hits);return;}
        for(int i=tree.start;i<tree.end;i++){
            spend();
            var t=tree.triangles[i];Vec3 e1=t.b.subtract(t.a),e2=t.c.subtract(t.a),p=RAY.cross(e2);double det=e1.dot(p);
            if(Math.abs(det)<1e-12)continue;double inv=1/det;Vec3 s=origin.subtract(t.a);double u=s.dot(p)*inv;
            if(u<-EPS||u>1+EPS)continue;Vec3 q=s.cross(e1);double v=RAY.dot(q)*inv;
            if(v<-EPS||u+v>1+EPS)continue;double distance=e2.dot(q)*inv;if(distance>=-EPS)hits.add(distance);
        }
    }
    private static boolean rayBox(AABB box,Vec3 p){
        // RAY has three strictly positive components; no per-node temporary slab arrays are needed.
        double near=Math.max(0,Math.max((box.minX-p.x)/RAY.x,Math.max((box.minY-p.y)/RAY.y,(box.minZ-p.z)/RAY.z)));
        double far=Math.min((box.maxX-p.x)/RAY.x,Math.min((box.maxY-p.y)/RAY.y,(box.maxZ-p.z)/RAY.z));
        return far>=near-EPS;
    }
    private HatCareGeometry(){}
}
