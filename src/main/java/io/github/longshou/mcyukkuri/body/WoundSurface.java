package io.github.longshou.mcyukkuri.body;
import com.google.gson.*;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.lang.Math;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;
import java.util.zip.GZIPInputStream;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Immutable selected skin geometry, shared by server hit tests and client attachments. No client classes. */
public final class WoundSurface {
    private static final Map<String,WoundSurface> CACHE=new ConcurrentHashMap<>();
    public final float[] points;
    public final float[][] weights;
    public final int skinCount;
    public final Set<String> replaced;
    public final String runtimeSourceHash;
    public record FacePart(int start,int count,int bone,int parent){}
    public final List<FacePart> faceParts;
    private final float bodyScale;
    private final JsonArray bones;
    private final JsonObject clips;
    private final JsonArray envelope,accessoryPrefixes;
    private final Map<String,float[]> expressionPoints=new HashMap<>();
    private final Node tree;
    private record Node(float[] box,Node left,Node right,int[] triangles) {}
    public record Hit(int triangle,float u,float v,float distance) {}
    public static WoundSurface get(String id){return CACHE.computeIfAbsent(id,key->{
        var stream=WoundSurface.class.getResourceAsStream("/assets/mcyukkuri/yukkuri_wounds/"+key+".json.gz");
        if(stream==null)throw new IllegalStateException("Missing wound surface "+key);
        try(var reader=new InputStreamReader(new GZIPInputStream(stream),StandardCharsets.UTF_8)){return new WoundSurface(JsonParser.parseReader(reader).getAsJsonObject());}
        catch(IOException e){throw new UncheckedIOException(e);}
    });}
    private WoundSurface(JsonObject data){
        runtimeSourceHash=data.get("runtimeSourceHash").getAsString();
        bodyScale=data.get("id").getAsString().startsWith("house_")?.32f:1;
        points=floats(data.getAsJsonArray("points"));skinCount=data.get("skinCount").getAsInt();
        var raw=data.getAsJsonArray("weights");weights=new float[raw.size()][];
        for(int i=0;i<raw.size();i++){var a=raw.get(i).getAsJsonArray();weights[i]=new float[a.size()*2];for(int j=0;j<a.size();j++){var w=
                a.get(j).getAsJsonArray();weights[i][j*2]=w.get(0).getAsInt();weights[i][j*2+1]=w.get(1).getAsFloat();}}
        replaced=new HashSet<>();data.getAsJsonArray("replaced").forEach(v->replaced.add(v.getAsString()));bones=data.getAsJsonArray("bones");clips=
                data.getAsJsonObject("clips");envelope=data.getAsJsonArray("envelope");accessoryPrefixes=data.getAsJsonArray("accessoryPrefixes");
        faceParts=new ArrayList<>();for(var rawPart:data.getAsJsonArray("faceParts")){var p=rawPart.getAsJsonObject();
                faceParts.add(new FacePart(p.get("start").getAsInt(),p.get("count").getAsInt(),p.get("bone").getAsInt(),p.get("parent").getAsInt()));}
        if(data.has("expressionPoints"))for(var entry:data.getAsJsonObject("expressionPoints").entrySet()){
            float[] values=points.clone();for(var rawPatch:entry.getValue().getAsJsonArray()){
                var patch=rawPatch.getAsJsonObject();int start=patch.get("start").getAsInt()*5;float[] changed=floats(patch.getAsJsonArray("points"));
                if(start<skinCount*5||start+changed.length>values.length)throw new IllegalArgumentException("Invalid wound expression range");
                System.arraycopy(changed,0,values,start,changed.length);
            }expressionPoints.put(entry.getKey(),values);
        }
        tree=tree(IntStream.range(0,skinCount/3).map(i->i*3).boxed().toArray(Integer[]::new),0,skinCount/3);
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
    private Hit intersect(int i,Vector3f origin,Vector3f direction,Vector3f a,Vector3f b,Vector3f c){var e=b.sub(a);var f=c.sub(a);var h=
            new Vector3f(direction).cross(f);float det=e.dot(h);if(det<1e-7)return null;
        var s=new Vector3f(origin).sub(a);float u=s.dot(h)/det;if(u<-.0001||u>1.0001)return null;var q=s.cross(e);float v=direction.dot(q)/det;
                if(v<-.0001||u+v>1.0001)return null;float t=f.dot(q)/det;return t>=0?new Hit(i,u,v,t):null;
    }
    private static float[] floats(JsonArray a){float[] f=new float[a.size()];for(int i=0;i<f.length;i++)f[i]=a.get(i).getAsFloat();return f;}
    public Vector3f rest(int i){return new Vector3f(points[i*5],points[i*5+1],points[i*5+2]);}
    public Vector3f rest(int i,String expression){var values=expressionPoints.getOrDefault(expression,points);return new Vector3f(values[i*5],values[i*5+1],values[i*5+2]);}
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
    /** Preserve the surface region when the selected body changes shape or triangulation. */
    public BodyWound remap(BodyWound wound,Vector3f anchor,Vector3f outward,float[] oldBounds){
        var hit=remapSite(anchor,outward,oldBounds);
        if(hit==null)return wound;
        return new BodyWound(wound.id(),wound.kind(),hit.triangle(),hit.u(),hit.v(),wound.severity(),wound.size(),wound.emitted(),wound.lastRelease(),wound.peakSeverity());
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
    public Vector3f deform(Vector3f p,List<BodyWound> wounds){return deform(p,wounds,false);}
    public Vector3f deformDisplay(Vector3f p,List<BodyWound> wounds){return deform(p,wounds,true);}
    private Vector3f deform(Vector3f p,List<BodyWound> wounds,boolean display){var result=new Vector3f(p);
        for(var w:wounds){if(!valid(w)||!(w.kind()==WoundKind.TEAR||w.kind()==WoundKind.DENT))continue;
            var site=display?displaySite(w):w.origin();
            var n=normal(site.triangle());var d=new Vector3f(p).sub(anchor(site.hit()));float distance=d.dot(n);if(Math.abs(distance)>.16)continue;
            var u=tangent(n);var v=new Vector3f(n).cross(u);float radius=w.size()*(.6f+w.severity()*.16f);
            float r=(float)(Math.pow(d.dot(u)/(1.15*radius),2)+Math.pow(d.dot(v)/(.85*radius),2));
            if(r<1){float depth=w.kind()==WoundKind.DENT?new float[]{0,.25f,.6f,1}[w.severity()]:.18f*w.severity();result.sub(n.mul(depth*bodyScale*(1-r)*(1-r)));}
        }return result;
    }
    public static Vector3f tangent(Vector3f n){return Math.abs(n.y)>.9?new Vector3f(1,0,0):new Vector3f(0,1,0).cross(n).normalize();}
    public Vector3f skin(int i,Vector3f p,Matrix4f[] world){var out=new Vector3f();var temp=new Vector3f();var w=weights[i];for(int k=0;k<w.length;
            k+=2){world[(int)w[k]].transformPosition(p,temp);out.fma(w[k+1],temp);}return out;}
    public Vector3f posed(int i,Matrix4f[] world,List<BodyWound> wounds){return skin(i,deform(rest(i),wounds),world);}
    public Vector3f posedDisplay(int i,Matrix4f[] world,List<BodyWound> wounds){return skin(i,deformDisplay(rest(i),wounds),world);}
    public Vector3f pointDisplay(BodyWound w,Matrix4f[] world,List<BodyWound> wounds){var h=displaySite(w);
        return posedDisplay(h.triangle(),world,wounds).mul(1-h.u()-h.v()).add(posedDisplay(h.triangle()+1,world,wounds).mul(h.u())).add(posedDisplay(h.triangle()+2,world,wounds).mul(h.v()));}
    public Vector3f point(Hit h,Matrix4f[] world,List<BodyWound> wounds){return posed(h.triangle,world,wounds).mul(1-h.u-h.v).add(posed(h.triangle+1,
            world,wounds).mul(h.u)).add(posed(h.triangle+2,world,wounds).mul(h.v));}
    public Vector3f point(BodyWound w,Matrix4f[] world,List<BodyWound> wounds){return point(new Hit(w.triangle(),w.u(),w.v(),0),world,wounds);}
    public Hit ray(Vector3f origin,Vector3f direction,Matrix4f[] world,List<BodyWound> wounds){Hit best=null;
        if(world==null)return restRay(tree,origin,direction,null);
        for(int i=0;i<skinCount;i+=3){var a=world==null?rest(i):posed(i,world,wounds);var b=world==null?rest(i+1):posed(i+1,world,wounds);var c=world==null?rest(i+2):posed(i+2,world,wounds);
            Vector3f e=b.sub(a),f=c.sub(a),h=new Vector3f(direction).cross(f);float det=e.dot(h);if(det<1e-7)continue;
            var s=new Vector3f(origin).sub(a);float u=s.dot(h)/det;if(u<-.0001||u>1.0001)continue;var q=s.cross(e);float v=direction.dot(q)/det;if(v<-.0001||u+v>1.0001)continue;
            float t=f.dot(q)/det;if(t>=0&&(best==null||t<best.distance))best=new Hit(i,u,v,t);
        }return best;
    }
    public Matrix4f[] pose(String action,float elapsed){float[][] values=new float[bones.size()][9];for(var v:values)v[6]=v[7]=v[8]=1;sample(clips.getAsJsonObject("idle"),0,values);
        sample(clips.has(action)?clips.getAsJsonObject(action):clips.getAsJsonObject("idle"),elapsed,values);var world=new Matrix4f[bones.size()];
        for(int i=0;i<world.length;i++){var b=bones.get(i).getAsJsonObject();var p=floats(b.getAsJsonArray("pivot"));int parent=
                b.get("parent").getAsInt();var v=values[i];world[i]=parent<0?new Matrix4f():new Matrix4f(world[parent]);world[i].translate(p[0]+v[0],
                p[1]+v[1],p[2]+v[2]).rotateZYX(v[5]*(float)Math.PI/180,v[4]*(float)Math.PI/180,v[3]*(float)Math.PI/180).scale(v[6],v[7],v[8]).translate(-p[0],-p[1],-p[2]);}return world;
    }
    /** Compose an authored stable root track on the selected common-side skeleton. */
    public Matrix4f rootPose(float[] values){
        for(int i=0;i<bones.size();i++){
            var bone=bones.get(i).getAsJsonObject();if(!bone.get("name").getAsString().equals("root"))continue;
            float[] pivot=floats(bone.getAsJsonArray("pivot"));int parent=bone.get("parent").getAsInt();
            var root=parent<0?new Matrix4f():new Matrix4f(pose("idle",0)[parent]);
            return root.translate(pivot[0]+values[0],pivot[1]+values[1],pivot[2]+values[2])
                    .rotateZYX(values[5]*(float)Math.PI/180,values[4]*(float)Math.PI/180,values[3]*(float)Math.PI/180)
                    .scale(values[6],values[7],values[8]).translate(-pivot[0],-pivot[1],-pivot[2]);
        }
        throw new IllegalStateException("Selected skin has no root bone");
    }
    private void sample(JsonObject clip,float elapsed,float[][] values){float duration=clip.get("duration").getAsFloat();float time=
            clip.get("loop").getAsBoolean()?Math.max(0,elapsed)%duration:Math.clamp(elapsed,0,duration);
        for(var raw:clip.getAsJsonArray("tracks")){var t=raw.getAsJsonObject();var keys=t.getAsJsonArray("keys");int lo=0,hi=keys.size()-1;
                while(hi-lo>1){int mid=(lo+hi)>>>1;if(keys.get(mid).getAsJsonArray().get(0).getAsFloat()<=time)lo=mid;else hi=mid;}
            var a=floats(keys.get(lo).getAsJsonArray());var b=floats(keys.get(Math.min(lo+1,keys.size()-1)).getAsJsonArray());float q=a[0]==b[0]?0:
                    Math.clamp((time-a[0])/(b[0]-a[0]),0,1);int off=switch(t.get("channel").getAsString()){case "position"->0;case "rotation"->3;
                    default->6;};var target=values[t.get("bone").getAsInt()];for(int k=0;k<3;k++)target[off+k]=a[k+1]+(b[k+1]-a[k+1])*q;
        }
    }
}
