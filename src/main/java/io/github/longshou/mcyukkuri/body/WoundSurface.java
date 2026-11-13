package io.github.longshou.mcyukkuri.body;
import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.lang.Math;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntPredicate;
import java.util.stream.IntStream;
import java.util.zip.GZIPInputStream;
import org.joml.Matrix4f;
import io.github.longshou.mcyukkuri.pose.Clips;
import io.github.longshou.mcyukkuri.pose.Compose;
import io.github.longshou.mcyukkuri.pose.Overlays;
import io.github.longshou.mcyukkuri.pose.Skin;
import io.github.longshou.mcyukkuri.motor.geom.GeometryJson;
import org.joml.Vector3f;
import io.github.longshou.mcyukkuri.headwear.HatGeometry;
import io.github.longshou.mcyukkuri.family.PregnancyAttachments;
import io.github.longshou.mcyukkuri.family.LiveBirthGeometry;

/** Immutable selected skin geometry, shared by server hit tests and client attachments. No client classes. */
public final class WoundSurface {
    private static final Map<String,WoundSurface> CACHE=new ConcurrentHashMap<>();
    public final float[] points;
    public final float[][] weights;
    public final int skinCount;
    public final Set<String> replaced;
    public final String runtimeSourceHash;
    public final String assetId;
    public record FacePart(int start,int count,int bone,int parent){}
    public final List<FacePart> faceParts;
    private final JsonArray bones;
    private final Map<String,WoundMotion.Clip> clips;
    private final JsonArray envelope,accessoryPrefixes;
    private final Node tree;
    private final float[][] pivots;
    private final int[] parents;
    private record Node(float[] box,Node left,Node right,int[] triangles) {}
    public record Hit(int triangle,float u,float v,float distance) {}
    public record AttachmentLifts(Vector3f crown,Vector3f braid) {}
    private static final String BASE="/assets/mcyukkuri/yukkuri_wounds/";
    private record Patch(int start,float[] points) {}
    private final String resource;
    private volatile Map<String,List<Patch>> expressionPatches;
    private final Map<String,float[]> expressionPoints=new ConcurrentHashMap<>();
    private static final Set<String> REQUESTED=ConcurrentHashMap.newKeySet();
    private static final ExecutorService LOADER=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"yukkuri-wound-loader");t.setDaemon(true);return t;});
    /** Builds the surface of a body model present in the world off the tick thread, one model at a time; {@link #get} waits only for what is still loading. */
    public static void warm(String id){if(REQUESTED.add(id))LOADER.execute(()->{try{get(id);}catch(RuntimeException e){REQUESTED.remove(id);}});}
    public static WoundSurface get(String id){return CACHE.computeIfAbsent(id,key->{
        try(var reader=open(key)){return new WoundSurface(key,new JsonReader(reader));}
        catch(IOException e){throw new UncheckedIOException(e);}
    });}
    private static InputStreamReader open(String key)throws IOException{
        var stream=WoundSurface.class.getResourceAsStream(BASE+key+".json.gz");
        if(stream==null)throw new IllegalStateException("Missing wound surface "+key);
        return new InputStreamReader(new GZIPInputStream(stream,1<<16),StandardCharsets.UTF_8);
    }
    /** Streams the file into primitive arrays; the (server-unused) expression point sets are skipped and read on first request. */
    private WoundSurface(String key,JsonReader in)throws IOException{
        resource=key;
        String hash=null,id=null;float[] pts=null;float[][] w=null;int count=0;JsonArray bonesIn=null,envelopeIn=null,prefixesIn=null,replacedIn=null,partsIn=null;Map<String,WoundMotion.Clip> clipsIn=null;
        in.beginObject();
        while(in.hasNext()){
            switch(in.nextName()){
                case "runtimeSourceHash"->hash=in.nextString();
                case "id"->id=in.nextString();
                case "points"->pts=readFloats(in);
                case "skinCount"->count=in.nextInt();
                case "weights"->w=readWeights(in);
                case "replaced"->replacedIn=JsonParser.parseReader(in).getAsJsonArray();
                case "bones"->bonesIn=JsonParser.parseReader(in).getAsJsonArray();
                case "clips"->clipsIn=WoundMotion.readClips(in);
                case "envelope"->envelopeIn=JsonParser.parseReader(in).getAsJsonArray();
                case "accessoryPrefixes"->prefixesIn=JsonParser.parseReader(in).getAsJsonArray();
                case "faceParts"->partsIn=JsonParser.parseReader(in).getAsJsonArray();
                default->in.skipValue();
            }
        }
        in.endObject();
        runtimeSourceHash=hash;assetId=id;points=pts;skinCount=count;weights=w;bones=bonesIn;clips=clipsIn;envelope=envelopeIn;accessoryPrefixes=prefixesIn;
        WoundMotion.apply(assetId,bones,clips);
        replaced=new HashSet<>();replacedIn.forEach(v->replaced.add(v.getAsString()));
        faceParts=new ArrayList<>();for(var rawPart:partsIn){var p=rawPart.getAsJsonObject();
                faceParts.add(new FacePart(p.get("start").getAsInt(),p.get("count").getAsInt(),p.get("bone").getAsInt(),p.get("parent").getAsInt()));}
        pivots=new float[bones.size()][];parents=new int[bones.size()];
        for(int i=0;i<pivots.length;i++){var b=bones.get(i).getAsJsonObject();pivots[i]=floats(b.getAsJsonArray("pivot"));parents[i]=b.get("parent").getAsInt();}
        tree=tree(IntStream.range(0,skinCount/3).map(i->i*3).boxed().toArray(Integer[]::new),0,skinCount/3);
    }
    private static float[] readFloats(JsonReader in)throws IOException{
        float[] out=new float[1<<16];int n=0;in.beginArray();
        while(in.hasNext()){if(n==out.length)out=Arrays.copyOf(out,n*2);out[n++]=Float.parseFloat(in.nextString());}
        in.endArray();return n==out.length?out:Arrays.copyOf(out,n);
    }
    private static float[][] readWeights(JsonReader in)throws IOException{
        var rows=new ArrayList<float[]>(1<<17);in.beginArray();
        while(in.hasNext()){
            float[] row=new float[8];int n=0;in.beginArray();
            while(in.hasNext()){if(n+2>row.length)row=Arrays.copyOf(row,row.length*2);in.beginArray();row[n++]=in.nextInt();row[n++]=Float.parseFloat(in.nextString());in.endArray();}
            in.endArray();rows.add(n==row.length?row:Arrays.copyOf(row,n));
        }
        in.endArray();return rows.toArray(new float[0][]);
    }
    private Map<String,List<Patch>> patches(){
        var known=expressionPatches;if(known!=null)return known;
        synchronized(this){
            if(expressionPatches!=null)return expressionPatches;
            var found=new HashMap<String,List<Patch>>();
            try(var reader=open(resource)){
                var in=new JsonReader(reader);in.beginObject();
                while(in.hasNext()){
                    if(!in.nextName().equals("expressionPoints")){in.skipValue();continue;}
                    in.beginObject();
                    while(in.hasNext()){
                        var list=new ArrayList<Patch>();String name=in.nextName();in.beginArray();
                        while(in.hasNext()){
                            int start=0;float[] changed=null;in.beginObject();
                            while(in.hasNext())switch(in.nextName()){case "start"->start=in.nextInt()*5;case "points"->changed=readFloats(in);default->in.skipValue();}
                            in.endObject();list.add(new Patch(start,changed));
                        }
                        in.endArray();found.put(name,list);
                    }
                    in.endObject();
                }
            }catch(IOException e){throw new UncheckedIOException(e);}
            return expressionPatches=found;
        }
    }
    private float[] expressionValues(String expression){
        if(expression==null||expression.isEmpty())return points;
        var values=expressionPoints.get(expression);if(values!=null)return values;
        var list=patches().get(expression);if(list==null)return points;
        return expressionPoints.computeIfAbsent(expression,k->{
            float[] out=points.clone();
            for(var patch:list){
                if(patch.start<skinCount*5||patch.start+patch.points.length>out.length)throw new IllegalArgumentException("Invalid wound expression range");
                System.arraycopy(patch.points,0,out,patch.start,patch.points.length);
            }return out;});
    }
    private Node tree(Integer[] triangles,int from,int to){float[] box={Float.MAX_VALUE,Float.MAX_VALUE,Float.MAX_VALUE,-Float.MAX_VALUE,-Float.MAX_VALUE,-Float.MAX_VALUE};
        for(int n=from;n<to;n++)for(int j=0;j<3;j++)for(int k=0;k<3;k++){float v=points[(triangles[n]+j)*5+k];box[k]=Math.min(box[k],v);box[k+3]=Math.max(box[k+3],v);}
        if(to-from<=12)return new Node(box,null,null,Arrays.stream(triangles,from,to).mapToInt(i->i).toArray());
        int axis=0;for(int k=1;k<3;k++)if(box[k+3]-box[k]>box[axis+3]-box[axis])axis=k;final int a=axis;
        Arrays.sort(triangles,from,to,Comparator.comparingDouble(i->points[i*5+a]+points[(i+1)*5+a]+points[(i+2)*5+a]));int mid=(from+to)/2;
        return new Node(box,tree(triangles,from,mid),tree(triangles,mid,to),null);
    }
    private Hit restRay(Node node,Vector3f o,Vector3f d,Hit best){float low=0,high=best==null?Float.MAX_VALUE:best.distance;
        for(int k=0;k<3;k++){float direction=d.get(k),origin=o.get(k);if(Math.abs(direction)<1e-8){if(origin<node.box[k]-.0001||origin>node.box[k+3]+.0001)return best;}
            else{float a=(node.box[k]-origin)/direction,b=(node.box[k+3]-origin)/direction;low=Math.max(low,Math.min(a,b));high=Math.min(high,Math.max(a,b));if(low>high+.0001)return best;}}
        if(node.triangles==null)return restRay(node.right,o,d,restRay(node.left,o,d,best));
        for(int i:node.triangles){Hit hit=intersect(i,o,d,rest(i),rest(i+1),rest(i+2));if(hit!=null&&(best==null||hit.distance<best.distance))best=hit;}return best;
    }
    /** Read-only rest-skin broad phase. The predicate receives a triangle's first vertex index and may stop the query. */
    public boolean anySkinTriangle(float minX,float minY,float minZ,float maxX,float maxY,float maxZ,IntPredicate hit){
        return minX<=maxX&&minY<=maxY&&minZ<=maxZ
                &&anySkinTriangle(tree,minX,minY,minZ,maxX,maxY,maxZ,hit);
    }
    private boolean anySkinTriangle(Node node,float minX,float minY,float minZ,float maxX,float maxY,float maxZ,IntPredicate hit){
        var box=node.box;
        if(box[3]<minX||box[4]<minY||box[5]<minZ||box[0]>maxX||box[1]>maxY||box[2]>maxZ)return false;
        if(node.triangles==null)return anySkinTriangle(node.left,minX,minY,minZ,maxX,maxY,maxZ,hit)
                ||anySkinTriangle(node.right,minX,minY,minZ,maxX,maxY,maxZ,hit);
        for(int first:node.triangles)if(hit.test(first))return true;
        return false;
    }
    private Hit intersect(int i,Vector3f origin,Vector3f direction,Vector3f a,Vector3f b,Vector3f c){var e=b.sub(a);var f=c.sub(a);var h=
            new Vector3f(direction).cross(f);float det=e.dot(h);if(det<1e-7)return null;
        var s=new Vector3f(origin).sub(a);float u=s.dot(h)/det;if(u<-.0001||u>1.0001)return null;var q=s.cross(e);float v=direction.dot(q)/det;
                if(v<-.0001||u+v>1.0001)return null;float t=f.dot(q)/det;return t>=0?new Hit(i,u,v,t):null;
    }
    private static float[] floats(JsonArray a){float[] f=new float[a.size()];for(int i=0;i<f.length;i++)f[i]=a.get(i).getAsFloat();return f;}
    public Vector3f rest(int i){return new Vector3f(points[i*5],points[i*5+1],points[i*5+2]);}
    /** Identity of the point set rest(i,expression) reads: equal sets give identical points. */
    public Object restSet(String expression){return expressionValues(expression);}
    public Vector3f rest(int i,String expression){var values=expressionValues(expression);return new Vector3f(values[i*5],values[i*5+1],values[i*5+2]);}
    private static final class AuthoredHair {
        static final JsonObject MODELS=GeometryJson.read("yukkuri_presentation/hair_wounds.json").data().getAsJsonObject("models");
    }
    /** Contact on the authored hair or worn accessory, in resting model pixels. */
    Vector3f headContact(int cut,int previousLoss,boolean accessory){
        var hat=accessory?HatGeometry.worn(assetId):null;
        if(hat!=null)return new Vector3f(floats(hat.getAsJsonArray("seat")));
        var model=accessory?null:AuthoredHair.MODELS.getAsJsonObject(assetId);
        var authored=new HashSet<String>();
        if(model!=null)for(var part:model.getAsJsonArray("hair_parts"))authored.add(part.getAsString());
        var bounds=bounds();
        var scalp=new Vector3f((bounds[0]+bounds[3])*.5f,bounds[4],(bounds[2]+bounds[5])*.5f);
        Vector3f contact=null;
        float best=Float.POSITIVE_INFINITY;
        for(var raw:envelope){
            var part=raw.getAsJsonObject();
            String name=part.get("name").getAsString();
            float x=part.get("x").getAsFloat();
            if(accessory){
                if(accessoryPrefixes.asList().stream().noneMatch(prefix->name.startsWith(prefix.getAsString())))continue;
            }else{
                int mask=HairParts.lossMask(name,x,authored.contains(name));
                if((mask&cut)==0||(mask&previousLoss)!=0)continue;
            }
            var yz=part.getAsJsonArray("yz");
            var top=yz.get(0).getAsJsonArray();
            float z=(top.get(1).getAsFloat()+yz.get(1).getAsJsonArray().get(1).getAsFloat())*.5f;
            var root=new Vector3f(x,top.get(0).getAsFloat(),z);
            float score=root.distanceSquared(scalp);
            if(score<best){best=score;contact=root;}
        }
        return contact==null?scalp:contact;
    }
    public float contactLift(float angle,boolean accessory,int loss){float min=Float.MAX_VALUE,c=(float)Math.cos(Math.toRadians(angle)),s=(float)Math.sin(Math.toRadians(angle));
        for(var raw:envelope){var p=raw.getAsJsonObject();String name=p.get("name").getAsString();if(!accessory&&
                accessoryPrefixes.asList().stream().anyMatch(v->name.startsWith(v.getAsString())))continue;
            if(loss!=0&&(name.contains("hair")||name.contains("bang")||name.contains("braid")||name.startsWith("wasa_"))){if((loss&4)!=0)continue;
                    if(!(name.contains("bang")||name.contains("crown")||name.contains("wrap")||name.startsWith("wasa_scalp"))&&(p.get("x").getAsFloat()<0?(loss&1)!=0:(loss&2)!=0))continue;}
            for(var pair:p.getAsJsonArray("yz")){var yz=pair.getAsJsonArray();min=Math.min(min,(yz.get(0).getAsFloat()*c-yz.get(1).getAsFloat()*s)/16);}}
        return min==Float.MAX_VALUE?0:.006f-min;
    }
    public Vector3f normal(int i){return rest(i+1).sub(rest(i)).cross(rest(i+2).sub(rest(i))).normalize();}
    public boolean valid(BodyWound w){return w.triangle()>=0&&w.triangle()<=skinCount-3;}
    public boolean valid(BodyWound.Site site){return site!=null&&site.validNumbers()&&site.triangle()<=skinCount-3;}
    public BodyWound.Site displaySite(BodyWound wound){return wound.origin();}
    public boolean valid(SkinDecal w){return w.triangle()>=0&&w.triangle()<=skinCount-3;}
    /** Keep the legacy arithmetic at each caller: JOML's fma can use fused rounding. */
    public enum AnchorMath { MUL_ADD, FMA }
    public Vector3f anchor(BodyWound w){return anchor(w.triangle(),w.u(),w.v());}
    public Vector3f anchor(SkinDecal w){return anchor(w.triangle(),w.u(),w.v());}
    public Vector3f anchor(Hit h){return anchor(h.triangle(),h.u(),h.v());}
    public Vector3f anchor(Hit h,AnchorMath math){return anchor(h.triangle(),h.u(),h.v(),math);}
    public Vector3f anchor(int triangle,float u,float v){return anchor(triangle,u,v,AnchorMath.MUL_ADD);}
    public Vector3f anchor(int triangle,float u,float v,AnchorMath math){
        if(math==AnchorMath.FMA)return rest(triangle).mul(1-u-v).fma(u,rest(triangle+1)).fma(v,rest(triangle+2));
        return rest(triangle).mul(1-u-v).add(rest(triangle+1).mul(u)).add(rest(triangle+2).mul(v));
    }
    public float[] bounds(){return tree.box.clone();}
    public boolean hasClip(String id){return clips.containsKey(id);}
    /** Deep tissue follows the central body stretch, while the rim inherits its actual skin weights. */
    public int tissueBone(){
        for(int i=0;i<bones.size();i++)if(bones.get(i).getAsJsonObject().get("name").getAsString().equals("body_stretch"))return i;
        for(int i=0;i<bones.size();i++)if(bones.get(i).getAsJsonObject().get("name").getAsString().equals("root"))return i;
        return 0;
    }
    /** Preserve the surface region when the selected body changes shape or triangulation. */
    public BodyWound remap(BodyWound wound,Vector3f anchor,Vector3f outward,float[] oldBounds){
        var hit=remapSite(anchor,outward,oldBounds);
        if(hit==null)return wound;
        return new BodyWound(wound.id(),wound.kind(),hit.triangle(),hit.u(),hit.v(),wound.severity(),wound.size(),wound.emitted(),wound.lastRelease(),wound.peakSeverity(),wound.shape(),wound.points());
    }
    public BodyWound.Site remapSite(Vector3f anchor,Vector3f outward,float[] oldBounds){
        var point=new Vector3f(anchor);
        if(oldBounds!=null&&oldBounds.length==6)for(int k=0;k<3;k++){
            float span=oldBounds[k+3]-oldBounds[k];if(span>1e-6f)point.setComponent(k,tree.box[k]+(anchor.get(k)-oldBounds[k])/span*(tree.box[k+3]-tree.box[k]));
        }
        var direction=new Vector3f(outward);if(!direction.isFinite()||direction.lengthSquared()<1e-8)direction.set(0,0,-1);direction.normalize();
        Hit hit=ray(new Vector3f(point).fma(32,direction),new Vector3f(direction).negate(),null,List.of());
        if(hit==null)hit=nearest(point);
        return hit==null?null:new BodyWound.Site(hit.triangle(),hit.u(),hit.v());
    }
    private Hit nearest(Vector3f point){
        Hit best=null;float distance=Float.MAX_VALUE;
        for(int i=0;i<skinCount;i+=3){
            Vector3f a=rest(i),b=rest(i+1),c=rest(i+2),ab=new Vector3f(b).sub(a),ac=new Vector3f(c).sub(a),ap=new Vector3f(point).sub(a);
            float aa=ab.dot(ab),bb=ac.dot(ac),cross=ab.dot(ac),det=aa*bb-cross*cross;
            if(det>1e-12f){float u=(ap.dot(ab)*bb-ap.dot(ac)*cross)/det,v=(ap.dot(ac)*aa-ap.dot(ab)*cross)/det;
                if(u>=0&&v>=0&&u+v<=1){float d=new Vector3f(a).fma(u,ab).fma(v,ac).distanceSquared(point);if(d<distance){distance=d;best=new Hit(i,u,v,0);}}}
            Vector3f[] vertices={a,b,c};for(int e=0;e<3;e++){
                var from=vertices[e];var edge=new Vector3f(vertices[(e+1)%3]).sub(from);float len=edge.lengthSquared();
                float t=len<1e-12f?0:Math.clamp(new Vector3f(point).sub(from).dot(edge)/len,0,1);float d=new Vector3f(from).fma(t,edge).distanceSquared(point);
                if(d<distance){distance=d;best=new Hit(i,e==0?t:e==1?1-t:0,e==1?t:e==2?1-t:0,0);}
            }
        }return best;
    }
    public Vector3f deform(Vector3f p,List<BodyWound> wounds){return deformDisplay(p,wounds);}
    public Vector3f deformDisplay(Vector3f p,List<BodyWound> wounds){return deformer(wounds).apply(new Vector3f(p));}
    public Deformer deformer(List<BodyWound> wounds){return new Deformer(wounds);}
    /**
     * The display deformation (blunt dents, whole-body burn) of one wound list, with every per-wound value computed once so that
     * moving many vertices allocates nothing. Not thread safe: each user keeps its own instance.
     */
    public final class Deformer {
        private record Dent(Vector3f n,Vector3f center,Vector3f anchor,Vector3f u,Vector3f v,float near,float far,float depth,float radiusU,float radiusV) {}
        private final List<Dent> dents=new ArrayList<>();
        private final int whole;
        private final Vector3f scratch=new Vector3f(),delta=new Vector3f();
        Deformer(List<BodyWound> wounds){
            boolean house=assetId.startsWith("house_");
            int strongest=0;
            for(var w:wounds){
                if(w.kind()==WoundKind.SCORCH&&w.shape()==WoundShape.WHOLE_BURN)strongest=Math.max(strongest,w.severity());
                if(house||!valid(w)||w.kind()!=WoundKind.DENT||w.shape()!=WoundShape.BLUNT)continue;
                var n=normal(w.triangle());if(!n.isFinite())continue;
                var fit=WoundProfiles.fit(assetId);var scale=fit.scale();
                float normalScale=new Vector3f(n).mul(scale).length();
                var grade=WoundProfiles.grade(w.shape(),w.severity());
                var u=tangent(n);var v=new Vector3f(n).cross(u);
                float radiusU=grade.radius()[0]*w.size()*new Vector3f(u).mul(scale).length();
                float radiusV=grade.radius()[1]*w.size()*new Vector3f(v).mul(scale).length()*WoundProfiles.verticalScale(assetId,n);
                dents.add(new Dent(n,fit.center(),anchor(w),u,v,.15f*normalScale,2.5f*normalScale,-grade.depth()*w.size()*normalScale,radiusU,radiusV));
            }
            whole=strongest>0&&house?3:strongest;
        }
        /** Deforms {@code p} in place and returns it. */
        public Vector3f apply(Vector3f p){
            if(dents.isEmpty()&&whole<=0)return p;
            float ox=p.x,oy=p.y,oz=p.z;
            for(var dent:dents){
                if(scratch.set(ox,oy,oz).sub(dent.center).dot(dent.n)<=dent.near)continue;
                delta.set(ox,oy,oz).sub(dent.anchor);
                if(Math.abs(delta.dot(dent.n))>=dent.far)continue;
                float x=delta.dot(dent.u)/dent.radiusU,y=delta.dot(dent.v)/dent.radiusV,r=x*x+y*y;
                if(r<1)p.fma(dent.depth*(1-r)*(1-r)*(1-r),dent.n);
            }
            return WoundProfiles.thermalPoint(assetId,p,whole);
        }
    }
    public static Vector3f tangent(Vector3f n){return Math.abs(n.y)>.9?new Vector3f(1,0,0):new Vector3f(0,1,0).cross(n).normalize();}
    public Vector3f skin(int i,Vector3f p,Matrix4f[] world){return Skin.weighted(weights[i],p,world,new Vector3f(),new Vector3f());}
    public Vector3f posed(int i,Matrix4f[] world,List<BodyWound> wounds){return skin(i,deform(rest(i),wounds),world);}
    public Vector3f posedDisplay(int i,Matrix4f[] world,List<BodyWound> wounds){return skin(i,deformDisplay(rest(i),wounds),world);}
    public Vector3f pointDisplay(BodyWound w,Matrix4f[] world,List<BodyWound> wounds){var h=displaySite(w);
        return posedDisplay(h.triangle(),world,wounds).mul(1-h.u()-h.v()).add(posedDisplay(h.triangle()+1,world,wounds).mul(h.u())).add(posedDisplay(h.triangle()+2,world,wounds).mul(h.v()));}
    public Vector3f point(Hit h,Matrix4f[] world,List<BodyWound> wounds){return posed(h.triangle,world,wounds).mul(1-h.u-h.v).add(posed(h.triangle+1,
            world,wounds).mul(h.u)).add(posed(h.triangle+2,world,wounds).mul(h.v));}
    public Vector3f point(BodyWound w,Matrix4f[] world,List<BodyWound> wounds){return point(new Hit(w.triangle(),w.u(),w.v(),0),world,wounds);}
    public Hit ray(Vector3f origin,Vector3f direction,Matrix4f[] world,List<BodyWound> wounds){return ray(origin,direction,world,wounds,0);}
    public Hit ray(Vector3f origin,Vector3f direction,Matrix4f[] world,List<BodyWound> wounds,float pregnancy){Hit best=null;
        if(world==null)return restRay(tree,origin,direction,null);
        for(int i=0;i<skinCount;i+=3){var a=world==null?rest(i):posed(i,world,wounds);var b=world==null?rest(i+1):posed(i+1,world,wounds);var c=world==null?rest(i+2):posed(i+2,world,wounds);
            if(pregnancy>.001f){LiveBirthGeometry.deform(a,pregnancy,0);LiveBirthGeometry.deform(b,pregnancy,0);LiveBirthGeometry.deform(c,pregnancy,0);}
            Vector3f e=b.sub(a),f=c.sub(a),h=new Vector3f(direction).cross(f);float det=e.dot(h);if(det<1e-7)continue;
            var s=new Vector3f(origin).sub(a);float u=s.dot(h)/det;if(u<-.0001||u>1.0001)continue;var q=s.cross(e);float v=direction.dot(q)/det;if(v<-.0001||u+v>1.0001)continue;
            float t=f.dot(q)/det;if(t>=0&&(best==null||t<best.distance))best=new Hit(i,u,v,t);
        }return best;
    }
    private float[][] poseValues(String action,float elapsed,String hatMotion,float hatPhase){float[][] values=new float[bones.size()][9];for(var v:values)v[6]=v[7]=v[8]=1;sample(clips.get("idle"),0,values);
        sample(clips.getOrDefault(action,clips.get("idle")),elapsed,values);
        if(hatMotion.equals("HAT_REMOVE")||hatMotion.equals("HAT_RETURN")){
            var hat=HatGeometry.worn(assetId);if(hat!=null)Overlays.hat(values[hat.get("bone").getAsInt()],hatPhase,hatMotion.equals("HAT_RETURN"),hat.get("lift").getAsFloat(),hat.get("forward").getAsFloat());
        }
        return values;
    }
    public AttachmentLifts pregnancyLifts(String action,float elapsed,String hatMotion,float hatPhase,float belly) {
        if(belly<=.001f)return new AttachmentLifts(new Vector3f(),new Vector3f());
        var values=poseValues(action,elapsed,hatMotion,hatPhase);int hat=-1,braid=-1;
        for(int i=0;i<bones.size();i++){String name=bones.get(i).getAsJsonObject().get("name").getAsString();
            if(name.equals("hat_follow"))hat=i;if(name.equals("hand_right"))braid=i;}
        var root=braid<0?null:new Vector3f(floats(bones.get(braid).getAsJsonObject().getAsJsonArray("pivot")));
        return new AttachmentLifts(PregnancyAttachments.crownLift(assetId,belly,hat<0?null:values[hat]),
                PregnancyAttachments.braidLift(root,belly,braid<0?null:values[braid]));
    }
    public Matrix4f[] pose(String action,float elapsed){return pose(action,elapsed,"",0);}
    public Matrix4f[] pose(String action,float elapsed,String hatMotion,float hatPhase){
        var values=poseValues(action,elapsed,hatMotion,hatPhase);var world=new Matrix4f[bones.size()];
        for(int i=0;i<world.length;i++){var p=pivots[i];int parent=
                parents[i];var v=values[i];world[i]=parent<0?new Matrix4f():new Matrix4f(world[parent]);Compose.woundLocal(world[i],p,v);
        }return world;
    }
    /** Compose an authored stable root track on the selected common-side skeleton. */
    public Matrix4f rootPose(float[] values){
        for(int i=0;i<bones.size();i++){
            var bone=bones.get(i).getAsJsonObject();if(!bone.get("name").getAsString().equals("root"))continue;
            float[] pivot=floats(bone.getAsJsonArray("pivot"));int parent=bone.get("parent").getAsInt();
            var root=parent<0?new Matrix4f():new Matrix4f(pose("idle",0)[parent]);
            return Compose.woundLocal(root,pivot,values);
        }
        throw new IllegalStateException("Selected skin has no root bone");
    }
    private void sample(WoundMotion.Clip clip,float elapsed,float[][] values){float duration=clip.duration();float time=
            clip.loop()?Math.max(0,elapsed)%duration:Math.clamp(elapsed,0,duration);
        for(var t:clip.tracks())Clips.sample(t.keys(),t.offset(),time,values[t.bone()]);
    }
}
