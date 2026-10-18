package io.github.longshou.mcyukkuri.body;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.headwear.Headwear;
import io.github.longshou.mcyukkuri.subspecies.HatVessel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Server-owned display placement on the fitted reference skin. Never changes the injury's logical site.
 * The reference exterior includes solid damaged-hat caps; transient animation gaps are not safe sites.
 * Exhausted refinement or a narrow exposed sliver is NOT evidence that a body part is fully covered. */
public final class WoundVisibility {
    private static final Logger LOGGER=LoggerFactory.getLogger(WoundVisibility.class);
    private static final float RAY_EPSILON=.002f, CONE_SLOPE=.12f, PATCH_MARGIN=1.35f;
    private static final int PATCH_SPOKES=16, COVER_POLYGONS=512, CACHE_LIMIT=24;
    private static final Map<String,Model> MODELS=new HashMap<>();
    private static final Map<GeometryKey,Geometry> GEOMETRY=new LinkedHashMap<>(16,.75f,true);
    private static final Set<String> WARNED=new HashSet<>();
    public record State(String asset,int hairLoss,boolean accessory,float hatScale,float hatFit) {}
    public enum Outcome { SAME_PART, EXPOSED_EDGE, NARROW_EXPOSURE, UNCERTAIN_COVERAGE, NO_EXPOSED_SKIN, INVALID_DATA }
    public record Selection(BodyWound.Site site,Outcome outcome) {
        boolean success(){return outcome==Outcome.SAME_PART||outcome==Outcome.EXPOSED_EDGE;}
    }
    private record GeometryKey(State state,String fingerprint) {}
    private record Model(JsonObject data,List<String> accessories,String fingerprint) {}
    private WoundVisibility() {}

    public static State state(YukkuriEntity entity){
        int visual=entity.visibleHeadwear();
        boolean accessory=entity.hasVisibleAccessory()||Headwear.visualLevel(visual)==3||entity.contactPose().equals("HAT_REMOVE");
        return new State(entity.subspecies().assetId(),entity.body().hairLoss(),accessory,
                HatVessel.hatMultiplier(entity.subspeciesFlags()),Headwear.visualFit(visual));
    }
    public static BodyWound place(YukkuriEntity entity,BodyWound wound){
        if(entity.level().isClientSide)return wound;
        // Legacy cosmetic entries remain readable, but do not acquire new injury placement rules.
        if(wound.kind()!=WoundKind.SCRATCH&&wound.kind()!=WoundKind.TEAR&&wound.kind()!=WoundKind.DENT&&wound.kind()!=WoundKind.SCORCH)return wound;
        var state=state(entity);Selection selection;
        try{selection=geometry(state).select(wound);}
        catch(IOException|RuntimeException failure){
            warn(state,Outcome.INVALID_DATA,failure.toString());return wound;
        }
        if(!selection.success()){
            warn(state,selection.outcome(),"No certified visible display site at logical "+wound.origin());
            // In particular, a former cross-part site is no longer authorized once the original
            // region has an exposed sliver. Keep the injury, without claiming placement succeeded.
            return wound.withDisplay(null);
        }
        return wound.withDisplay(selection.site().equals(wound.origin())?null:selection.site());
    }
    private static void warn(State state,Outcome result,String detail){
        synchronized(WARNED){if(WARNED.add(state.asset()+":"+result))
            LOGGER.warn("Wound visibility unresolved for {} ({}): {}",state.asset(),result,detail);}
    }
    private static synchronized Geometry geometry(State state)throws IOException{
        var model=MODELS.get(state.asset());
        if(model==null){model=load(state.asset());MODELS.put(state.asset(),model);}
        var key=new GeometryKey(state,model.fingerprint());var found=GEOMETRY.get(key);if(found!=null)return found;
        var geometry=new Geometry(state,model);GEOMETRY.put(key,geometry);
        if(GEOMETRY.size()>CACHE_LIMIT)GEOMETRY.remove(GEOMETRY.keySet().iterator().next());
        return geometry;
    }
    private static byte[] bytes(String path)throws IOException{
        try(var stream=WoundVisibility.class.getResourceAsStream("/assets/mcyukkuri/"+path)){
            if(stream==null)throw new IOException("Missing visibility resource "+path);return stream.readAllBytes();
        }
    }
    private static JsonObject json(String path)throws IOException{return JsonParser.parseString(new String(bytes(path),StandardCharsets.UTF_8)).getAsJsonObject();}
    private static Model load(String id)throws IOException{
        byte[] bytes=bytes("yukkuri/"+id+".json");var hash=ModelFingerprint.of(bytes);
        var data=JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject();
        if(!hash.matches(WoundSurface.get(id).runtimeSourceHash))throw new IOException("Wound/model fingerprint mismatch: "+id);
        var fits=json("yukkuri_presentation/placement_fit.json");
        if(fits.get("format").getAsInt()!=1)throw new IOException("Unsupported placement fit");
        var fit=fits.getAsJsonObject("models").getAsJsonObject(id);
        if(fit!=null&&!ModelPlacementFit.apply(id,data,fit,hash))throw new IOException("Placement fit fingerprint mismatch: "+id);
        var variants=json("yukkuri_presentation/living.json").getAsJsonObject("variants");
        var extra=json("yukkuri_presentation/subspecies.json");
        var variant=extra.has(id)?extra.getAsJsonObject(id):variants.getAsJsonObject(id);
        if(variant==null)throw new IOException("Missing accessory classification: "+id);
        var prefixes=variant.getAsJsonArray("accessory_prefixes").asList().stream().map(v->v.getAsString()).toList();
        // All inputs are immutable packaged resources for the lifetime of this server process.
        return new Model(data,prefixes,hash.lf()+":"+ModelFingerprint.of(fits.toString().getBytes(StandardCharsets.UTF_8)).lf());
    }

    private static final class Geometry {
        final WoundSurface mesh;
        final Node exterior,skin;
        final Candidate[] candidates;
        final byte[] exposed;
        final EnumMap<BodyPart,Boolean> covered=new EnumMap<>(BodyPart.class);
        Geometry(State state,Model model){
            mesh=WoundSurface.get(state.asset());var data=model.data();
            var values=data.getAsJsonArray("vertices");var points=new Vector3f[values.size()/8];
            for(int i=0;i<points.length;i++){points[i]=new Vector3f(values.get(i*8).getAsFloat(),values.get(i*8+1).getAsFloat(),values.get(i*8+2).getAsFloat());
                if(!points[i].isFinite())throw new IllegalArgumentException("Non-finite exterior vertex");}
            var parts=data.getAsJsonArray("parts");var accessories=new boolean[parts.size()];
            float low=Float.POSITIVE_INFINITY,high=Float.NEGATIVE_INFINITY;
            for(int i=0;i<parts.size();i++){var part=parts.get(i).getAsJsonObject();String name=part.get("name").getAsString();
                accessories[i]=model.accessories().stream().anyMatch(name::startsWith);
                if(accessories[i])for(int v=part.get("start").getAsInt(),end=v+part.get("count").getAsInt();v<end;v++){low=Math.min(low,points[v].y);high=Math.max(high,points[v].y);}}
            var seat=new Vector3f();int seatCount=0;
            for(int i=0;i<parts.size();i++)if(accessories[i]){var part=parts.get(i).getAsJsonObject();
                for(int v=part.get("start").getAsInt(),end=v+part.get("count").getAsInt();v<end;v++)if(points[v].y<=low+(high-low)*.15f){seat.add(points[v]);seatCount++;}}
            if(seatCount>0)seat.div(seatCount);
            var hatAnchor=HatVessel.anchor();seat.sub(hatAnchor).mul(state.hatScale()).add(hatAnchor);
            var triangles=new ArrayList<Triangle>();
            for(int i=0;i<parts.size();i++){var part=parts.get(i).getAsJsonObject();String name=part.get("name").getAsString();
                int start=part.get("start").getAsInt(),count=part.get("count").getAsInt();
                if(mesh.replaced.contains(name)||!part.get("anatomy").getAsString().isEmpty()||name.contains("puff"))continue;
                if(accessories[i]&&!state.accessory()||hairHidden(name,points,start,count,state.hairLoss()))continue;
                for(int v=start;v<start+count;v+=3){var a=new Vector3f(points[v]);var b=new Vector3f(points[v+1]);var c=new Vector3f(points[v+2]);
                    if(accessories[i])for(var p:List.of(a,b,c))p.sub(hatAnchor).mul(state.hatScale()).add(hatAnchor).sub(seat).mul(state.hatFit()).add(seat);
                    add(triangles,a,b,c);}
            }
            exterior=Node.of(triangles);triangles=new ArrayList<>();
            candidates=new Candidate[mesh.skinCount/3];exposed=new byte[candidates.length];
            for(int t=0;t<mesh.skinCount;t+=3){var a=mesh.rest(t);var b=mesh.rest(t+1);var c=mesh.rest(t+2);add(triangles,a,b,c);
                var site=new BodyWound.Site(t,1f/3,1f/3);var point=mesh.anchor(site.hit());var normal=mesh.normal(t);
                candidates[t/3]=new Candidate(site,point,normal,BodyPart.of(mesh,t,point));}
            skin=Node.of(triangles);
        }
        Selection select(BodyWound wound){
            if(!mesh.valid(wound))return new Selection(null,Outcome.INVALID_DATA);
            var origin=mesh.anchor(wound);var part=BodyPart.of(mesh,wound);var original=wound.origin();
            if(fits(original,wound,part))return new Selection(original,Outcome.SAME_PART);
            var previous=mesh.displaySite(wound);
            if(BodyPart.of(mesh,previous.triangle(),mesh.anchor(previous.hit()))==part&&fits(previous,wound,part))return new Selection(previous,Outcome.SAME_PART);
            var ordered=candidates.clone();Arrays.sort(ordered,Comparator.comparingDouble((Candidate c)->c.point.distanceSquared(origin)).thenComparingInt(c->c.site.triangle()));
            boolean bare=false;
            for(var c:ordered)if(c.part==part){
                if(centerExposed(c)){bare=true;if(fits(c.site,wound,part))return new Selection(c.site,Outcome.SAME_PART);}
                // Refine every triangle in this region, not just the local search radius.
                for(int u=0;u<=3;u++)for(int v=0;v<=3-u;v++){
                    var site=new BodyWound.Site(c.site.triangle(),u/3f,v/3f);var at=mesh.anchor(site.hit());
                    if(BodyPart.of(mesh,site.triangle(),at)!=part||!clear(at,c.normal))continue;
                    bare=true;if(fits(site,wound,part))return new Selection(site,Outcome.SAME_PART);
                }
            }
            if(bare)return new Selection(null,Outcome.NARROW_EXPOSURE);
            // A finite candidate search is not a full-coverage proof. Certify each skin triangle;
            // uncertain seams/part boundaries/refinement limits forbid cross-part relocation.
            if(!covered.computeIfAbsent(part,this::fullyCovered))return new Selection(null,Outcome.UNCERTAIN_COVERAGE);
            for(var c:ordered)if(c.part!=part&&centerExposed(c)&&fits(c.site,wound,null))return new Selection(c.site,Outcome.EXPOSED_EDGE);
            return new Selection(null,Outcome.NO_EXPOSED_SKIN);
        }
        boolean centerExposed(Candidate candidate){int i=candidate.site.triangle()/3;
            if(exposed[i]==0)exposed[i]=(byte)(clear(candidate.point,candidate.normal)?1:-1);return exposed[i]>0;}
        boolean clear(Vector3f p,Vector3f normal){
            if(!normal.isFinite())return false;
            for(var d:directions(normal)){var from=new Vector3f(p).fma(RAY_EPSILON,normal);
                if(Node.hit(exterior,from,d)!=null||Node.hit(skin,from,d)!=null)return false;}
            return true;
        }
        boolean fits(BodyWound.Site site,BodyWound wound,BodyPart required){
            if(!mesh.valid(site))return false;var at=mesh.anchor(site.hit());var n=mesh.normal(site.triangle());
            if(!clear(at,n))return false;
            var u=WoundSurface.tangent(n);var v=new Vector3f(n).cross(u);
            float radius=wound.size()*(.6f+wound.severity()*.16f)*PATCH_MARGIN;
            for(int ring=1;ring<=2;ring++)for(int spoke=0;spoke<PATCH_SPOKES;spoke++){
                double angle=spoke*Math.PI*2/PATCH_SPOKES;float r=radius*ring/2;
                var target=new Vector3f(at).fma(r*(float)Math.cos(angle),u).fma(r*.8f*(float)Math.sin(angle),v);
                var hit=mesh.ray(new Vector3f(target).fma(4,n),new Vector3f(n).negate(),null,List.of());
                if(hit==null||mesh.normal(hit.triangle()).dot(n)<.5f)return false;
                var p=mesh.anchor(hit);if(Math.abs(new Vector3f(p).sub(at).dot(n))>1.6f)return false;
                if(required!=null&&BodyPart.of(mesh,hit.triangle(),p)!=required)return false;
                // The common outward cone must clear the entire footprint, not unrelated angles per sample.
                if(!clear(p,n))return false;
            }
            return true;
        }
        boolean fullyCovered(BodyPart part){
            boolean found=false;
            for(var c:candidates){int t=c.site.triangle();var a=mesh.rest(t);var b=mesh.rest(t+1);var d=mesh.rest(t+2);
                // Include boundary triangles touching this part, even if their centroid is in another part.
                if(c.part!=part&&BodyPart.of(mesh,t,a)!=part&&BodyPart.of(mesh,t,b)!=part&&BodyPart.of(mesh,t,d)!=part)continue;
                found=true;for(var direction:directions(c.normal))if(!coveredTriangle(a,b,d,direction))return false;
            }return found;
        }
        boolean coveredTriangle(Vector3f a,Vector3f b,Vector3f c,Vector3f direction){
            var ab=new Vector3f(b).sub(a);var ac=new Vector3f(c).sub(a);
            var pending=new ArrayList<List<float[]>>();pending.add(new ArrayList<>(List.of(new float[]{0,0},new float[]{1,0},new float[]{0,1})));
            int visited=0;
            while(!pending.isEmpty()){
                if(++visited>COVER_POLYGONS)return false;
                var polygon=pending.removeLast();float u=0,v=0;
                for(var p:polygon){u+=p[0];v+=p[1];}u/=polygon.size();v/=polygon.size();
                var center=new Vector3f(a).fma(u,ab).fma(v,ac).fma(RAY_EPSILON,direction);
                var blocker=Node.hit(exterior,center,direction);if(blocker==null)return false;
                // Project the blocker into this skin triangle's barycentric plane. Subtract its
                // convex coverage, including positive ray distance; inspect every leftover polygon.
                // Unlike vertex sampling, unions of neighbouring outer triangles cover their seams.
                var qa=blocker.coordinates(new Vector3f(a).fma(RAY_EPSILON,direction),direction);
                var qb=blocker.coordinates(new Vector3f(b).fma(RAY_EPSILON,direction),direction);
                var qc=blocker.coordinates(new Vector3f(c).fma(RAY_EPSILON,direction),direction);
                if(qa==null||qb==null||qc==null)return false;
                float[][] planes={{qa[0],qb[0],qc[0]},{qa[1],qb[1],qc[1]},
                        {1-qa[0]-qa[1],1-qb[0]-qb[1],1-qc[0]-qc[1]},{qa[2],qb[2],qc[2]}};
                var inside=polygon;
                for(var plane:planes){
                    var outside=clip(inside,plane,false);if(area(outside)>1e-10f)pending.add(outside);
                    inside=clip(inside,plane,true);if(inside.isEmpty())return false;
                }
                if(area(inside)<1e-10f)return false;
            }return true;
        }
        private static List<float[]> clip(List<float[]> polygon,float[] plane,boolean positive){
            var out=new ArrayList<float[]>();if(polygon.isEmpty())return out;
            var previous=polygon.getLast();float before=value(plane,previous)*(positive?1:-1);
            for(var current:polygon){float after=value(plane,current)*(positive?1:-1);
                if((before>=0)!=(after>=0)){float q=before/(before-after);out.add(new float[]{previous[0]+q*(current[0]-previous[0]),previous[1]+q*(current[1]-previous[1])});}
                if(after>=0)out.add(current);previous=current;before=after;
            }return out;
        }
        private static float value(float[] plane,float[] p){return plane[0]+p[0]*(plane[1]-plane[0])+p[1]*(plane[2]-plane[0]);}
        private static float area(List<float[]> polygon){float area=0;for(int i=0;i<polygon.size();i++){var p=polygon.get(i);var q=polygon.get((i+1)%polygon.size());area+=p[0]*q[1]-p[1]*q[0];}return Math.abs(area)*.5f;}

    }
    private record Candidate(BodyWound.Site site,Vector3f point,Vector3f normal,BodyPart part) {}
    private static Vector3f[] directions(Vector3f normal){var u=WoundSurface.tangent(normal);var v=new Vector3f(normal).cross(u);
        return new Vector3f[]{normal,new Vector3f(normal).fma(CONE_SLOPE,u),new Vector3f(normal).fma(-CONE_SLOPE,u),new Vector3f(normal).fma(CONE_SLOPE,v),new Vector3f(normal).fma(-CONE_SLOPE,v)};}
    private static boolean hairHidden(String name,Vector3f[] points,int start,int count,int loss){
        if(loss==0||!(name.contains("hair")||name.contains("bang")||name.contains("braid")||name.startsWith("wasa_")))return false;
        if((loss&4)!=0)return true;
        if(name.contains("bang")||name.contains("crown")||name.contains("wrap")||name.startsWith("wasa_scalp"))return false;
        float x=0;for(int i=start;i<start+count;i++)x+=points[i].x;return x<0?(loss&1)!=0:(loss&2)!=0;
    }
    private static void add(List<Triangle> triangles,Vector3f a,Vector3f b,Vector3f c){
        var e=new Vector3f(b).sub(a);var f=new Vector3f(c).sub(a);if(new Vector3f(e).cross(f).lengthSquared()>1e-16f)triangles.add(new Triangle(a,e,f));
    }
    private record Triangle(Vector3f a,Vector3f e,Vector3f f){
        float[] coordinates(Vector3f origin,Vector3f direction){
            var h=new Vector3f(direction).cross(f);float det=e.dot(h);if(Math.abs(det)<1e-8f)return null;
            var s=new Vector3f(origin).sub(a);float u=s.dot(h)/det;var q=s.cross(e);
            return new float[]{u,direction.dot(q)/det,f.dot(q)/det};
        }
        boolean hit(Vector3f origin,Vector3f direction){
            var h=new Vector3f(direction).cross(f);float det=e.dot(h);if(Math.abs(det)<1e-8f)return false;
            var s=new Vector3f(origin).sub(a);float u=s.dot(h)/det;if(u<0||u>1)return false;
            var q=s.cross(e);float v=direction.dot(q)/det;if(v<0||u+v>1)return false;return f.dot(q)/det>0;
        }
        float center(int axis){return a.get(axis)+(e.get(axis)+f.get(axis))/3;}
    }
    private static final class Node {
        final float[] box;final Node left,right;final Triangle[] triangles;
        Node(float[] box,Node left,Node right,Triangle[] triangles){this.box=box;this.left=left;this.right=right;this.triangles=triangles;}
        static Node of(List<Triangle> list){return list.isEmpty()?null:build(list.toArray(Triangle[]::new),0,list.size());}
        static Node build(Triangle[] values,int from,int to){
            float[] box={Float.MAX_VALUE,Float.MAX_VALUE,Float.MAX_VALUE,-Float.MAX_VALUE,-Float.MAX_VALUE,-Float.MAX_VALUE};
            for(int i=from;i<to;i++)for(int k=0;k<3;k++){var t=values[i];float a=t.a.get(k),b=a+t.e.get(k),c=a+t.f.get(k);
                box[k]=Math.min(box[k],Math.min(a,Math.min(b,c)));box[k+3]=Math.max(box[k+3],Math.max(a,Math.max(b,c)));}
            if(to-from<=12)return new Node(box,null,null,Arrays.copyOfRange(values,from,to));
            int axis=0;for(int k=1;k<3;k++)if(box[k+3]-box[k]>box[axis+3]-box[axis])axis=k;final int split=axis;
            Arrays.sort(values,from,to,Comparator.comparingDouble(t->t.center(split)));int mid=(from+to)/2;
            return new Node(box,build(values,from,mid),build(values,mid,to),null);
        }
        static Triangle hit(Node node,Vector3f origin,Vector3f direction){
            if(node==null)return null;float low=0,high=Float.MAX_VALUE;
            for(int k=0;k<3;k++){float d=direction.get(k),o=origin.get(k);
                if(Math.abs(d)<1e-8f){if(o<node.box[k]||o>node.box[k+3])return null;}
                else{float a=(node.box[k]-o)/d,b=(node.box[k+3]-o)/d;low=Math.max(low,Math.min(a,b));high=Math.min(high,Math.max(a,b));if(low>high)return null;}}
            if(node.triangles!=null){for(var t:node.triangles)if(t.hit(origin,direction))return t;return null;}
            var found=hit(node.left,origin,direction);return found==null?hit(node.right,origin,direction):found;
        }
    }
}
