package io.github.longshou.mcyukkuri.body;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.headwear.Headwear;
import io.github.longshou.mcyukkuri.headwear.HatDamageGeometry;
import io.github.longshou.mcyukkuri.subspecies.HatVessel;
import java.io.ByteArrayInputStream;
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
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
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
    public record State(String asset,int hairLoss,boolean accessory,float hatScale,float hatFit,
                        int damageLevel,String recipeHash,String hatOperation) {}
    public enum Outcome { SAME_PART, EXPOSED_EDGE, NARROW_EXPOSURE, UNCERTAIN_COVERAGE, NO_EXPOSED_SKIN, INVALID_DATA }
    public record Selection(BodyWound.Site site,Outcome outcome) {
        boolean success(){return outcome==Outcome.SAME_PART||outcome==Outcome.EXPOSED_EDGE;}
    }
    private record GeometryKey(State state,String fingerprint) {}
    private record Part(String name,float[] vertices,boolean accessory) {}
    private record Model(List<Part> parts,Alpha alpha,String fingerprint) {}
    /** Packaged exterior UVs have equal binary alpha across the audited expression PNGs.
     * Neutral is their static representative; no expression tick decoding or client resource state.
     * Store private immutable pixels and a summed-area table of transparent pixels. */
    private static final class Alpha {
        final int width,height;
        private final byte[] pixels;
        private final int[] transparent;
        final String fingerprint;
        Alpha(int width,int height,byte[] pixels,String fingerprint){
            this.width=width;this.height=height;this.pixels=pixels.clone();this.fingerprint=fingerprint;
            transparent=new int[(width+1)*(height+1)];
            for(int y=0;y<height;y++)for(int x=0;x<width;x++){
                int i=(y+1)*(width+1)+x+1;
                transparent[i]=(pixels[y*width+x]==0?1:0)+transparent[i-1]+transparent[i-width-1]-transparent[i-width-2];
            }
        }
        boolean opaque(int x,int y){return pixels[Math.floorMod(y,height)*width+Math.floorMod(x,width)]!=0;}
        boolean solid(int x0,int y0,int x1,int y1){
            if(x0<0||y0<0||x1>=width||y1>=height)return false;
            return count(x0,y0,x1,y1)==0;
        }
        boolean empty(int x0,int y0,int x1,int y1){
            return x0>=0&&y0>=0&&x1<width&&y1<height&&count(x0,y0,x1,y1)==(x1-x0+1)*(y1-y0+1);
        }
        private int count(int x0,int y0,int x1,int y1){
            int stride=width+1;return transparent[(y1+1)*stride+x1+1]-transparent[y0*stride+x1+1]
                    -transparent[(y1+1)*stride+x0]+transparent[y0*stride+x0];
        }
    }
    private static Alpha loadAlpha(String id)throws IOException{
        String path="textures/entity/"+id+"/neutral.png";byte[] raw=bytes(path);
        // Explicit memory-backed input avoids ImageIO's process-wide disk-cache setting and temp files.
        try(var input=new MemoryCacheImageInputStream(new ByteArrayInputStream(raw))){
            var readers=ImageIO.getImageReaders(input);if(!readers.hasNext())throw new IOException("Cannot decode "+path);
            var reader=readers.next();
            try{
                reader.setInput(input,true,true);var image=reader.read(0);int width=image.getWidth(),height=image.getHeight();
                var pixels=new byte[width*height];
                for(int y=0;y<height;y++)for(int x=0;x<width;x++){
                    int alpha=image.getRGB(x,y)>>>24;
                    if(alpha!=0&&alpha!=255)throw new IOException("Non-binary reference alpha: "+path);
                    pixels[y*width+x]=(byte)alpha;
                }
                return new Alpha(width,height,pixels,"external-alpha-v1:"+path+":"+ModelFingerprint.of(raw).raw());
            }finally{reader.dispose();}
        }
    }
    private record Recipe(HatDamageGeometry.Pattern pattern,String hash,String error) {}
    private static final class RecipeHolder { static final Recipe VALUE=loadRecipe(); }
    private static Recipe loadRecipe(){
        try{var raw=bytes("yukkuri_presentation/hat_damage.json");
            return new Recipe(HatDamageGeometry.read(JsonParser.parseString(new String(raw,StandardCharsets.UTF_8)).getAsJsonObject()),ModelFingerprint.of(raw).lf(),null);
        }catch(IOException|RuntimeException error){return new Recipe(null,"unavailable",error.toString());}
    }
    private WoundVisibility() {}

    public static State state(YukkuriEntity entity){
        int visual=entity.headwear().visual();
        String operation=entity.contactPose().equals("HAT_REMOVE")||entity.contactPose().equals("HAT_RETURN")?entity.contactPose():"";
        boolean accessory=entity.life().hasAccessory()||Headwear.visualLevel(visual)==3||!operation.isEmpty();
        return new State(entity.subspecies().assetId(),entity.body().hairLoss(),accessory,
                HatVessel.hatMultiplier(entity.subspeciesFlags()),Headwear.visualFit(visual),
                Headwear.visualLevel(visual),RecipeHolder.VALUE.hash(),operation);
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
        if(RecipeHolder.VALUE.error()!=null)throw new IOException(RecipeHolder.VALUE.error());
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
        var parts=new ArrayList<Part>();var source=data.getAsJsonArray("vertices");var mesh=WoundSurface.get(id);
        for(var raw:data.getAsJsonArray("parts")){
            var part=raw.getAsJsonObject();String name=part.get("name").getAsString();
            if(mesh.replaced.contains(name)||!part.get("anatomy").getAsString().isEmpty()||name.contains("puff"))continue;
            int start=part.get("start").getAsInt(),count=part.get("count").getAsInt();var vertices=new float[count*8];
            if(start<0||count<0||count%3!=0||(long)(start+count)*8>source.size())throw new IOException("Invalid exterior part: "+name);
            for(int i=0;i<vertices.length;i++){vertices[i]=source.get(start*8+i).getAsFloat();
                if(!Float.isFinite(vertices[i]))throw new IOException("Non-finite exterior vertex: "+name);}
            parts.add(new Part(name,vertices,prefixes.stream().anyMatch(name::startsWith)));
        }
        // Keep the packed exterior vertices including UVs used to recognize damage quads.
        // Packaged resources are immutable for the lifetime of this server process.
        var alpha=loadAlpha(id);
        return new Model(List.copyOf(parts),alpha,hash.lf()+":"+ModelFingerprint.of(fits.toString().getBytes(StandardCharsets.UTF_8)).lf()+":"+alpha.fingerprint);
    }

    private static final class Geometry {
        final WoundSurface mesh;
        final Node exterior,skin;
        final boolean hatOperation;
        final EnumMap<BodyPart,List<Region>> regions=new EnumMap<>(BodyPart.class);
        final EnumMap<BodyPart,Boolean> covered=new EnumMap<>(BodyPart.class);
        Geometry(State state,Model model){
            mesh=WoundSurface.get(state.asset());hatOperation=!state.hatOperation().isEmpty();var parts=model.parts();
            float[] bounds={Float.MAX_VALUE,Float.MAX_VALUE,Float.MAX_VALUE,-Float.MAX_VALUE,-Float.MAX_VALUE,-Float.MAX_VALUE};
            for(var part:parts)if(part.accessory())for(int i=0;i<part.vertices().length;i+=8)for(int k=0;k<3;k++){
                bounds[k]=Math.min(bounds[k],part.vertices()[i+k]);bounds[k+3]=Math.max(bounds[k+3],part.vertices()[i+k]);}
            var seat=new Vector3f();int seatCount=0;
            for(var part:parts)if(part.accessory())for(int i=0;i<part.vertices().length;i+=8)
                if(part.vertices()[i+1]<=bounds[1]+(bounds[4]-bounds[1])*.15f){
                    seat.add(part.vertices()[i],part.vertices()[i+1],part.vertices()[i+2]);seatCount++;}
            if(seatCount>0)seat.div(seatCount);
            var hatAnchor=HatVessel.anchor();seat.sub(hatAnchor).mul(state.hatScale()).add(hatAnchor);
            var triangles=new ArrayList<Triangle>();
            var cell=new Vector3f[]{new Vector3f(),new Vector3f(),new Vector3f(),new Vector3f()};
            var normal=new Vector3f();var temp=new Vector3f();
            for(var part:parts){var raw=part.vertices();var points=new Vector3f[raw.length/8];
                for(int i=0;i<points.length;i++)points[i]=new Vector3f(raw[i*8],raw[i*8+1],raw[i*8+2]);
                if(part.accessory()&&!state.accessory()||hairHidden(part.name(),points,0,points.length,state.hairLoss()))continue;
                if(part.accessory())for(var point:points){point.sub(hatAnchor).mul(state.hatScale()).add(hatAnchor);
                    if(state.hatFit()<.999f)point.sub(seat).mul(state.hatFit()).add(seat);}
                if(!part.accessory()||state.damageLevel()==0){
                    for(int v=0;v<points.length;v+=3)addSource(triangles,points,raw,v,model.alpha());continue;}
                for(var face:HatDamageGeometry.layout(raw,0,points.length,part.name(),bounds,state.damageLevel(),RecipeHolder.VALUE.pattern())){
                    if(face.original()){
                        for(int v=face.originalStart();v<face.originalStart()+face.originalCount();v+=3)addSource(triangles,points,raw,v,model.alpha());
                        continue;
                    }
                    var indices=face.corners();var corners=new Vector3f[]{points[indices[0]],points[indices[1]],points[indices[2]],points[indices[3]]};
                    for(int i=0;i<face.n();i++)for(int j=0;j<face.m();j++){
                        if(!HatDamageGeometry.cell(corners,face.n(),face.m(),i,j,face.code(i,j),face.flip(),cell,normal,temp))continue;
                        // Cell scratch is reused; the BVH must own the final post-fit coordinates.
                        if(face.code(i,j)==HatDamageGeometry.DRAW){
                            float[] uv=new float[8];float u0=i/(float)face.n(),u1=(i+1f)/face.n(),v0=j/(float)face.m(),v1=(j+1f)/face.m();
                            for(int k=0;k<4;k++)for(int axis=0;axis<2;axis++)uv[k*2+axis]=HatDamageGeometry.bilerp(
                                    raw[indices[0]*8+3+axis],raw[indices[1]*8+3+axis],raw[indices[2]*8+3+axis],raw[indices[3]*8+3+axis],
                                    k==1||k==2?u1:u0,k>=2?v1:v0);
                            textured(triangles,new Vector3f(cell[0]),cell[1],cell[2],uv[0],uv[1],uv[2],uv[3],uv[4],uv[5],model.alpha());
                            textured(triangles,new Vector3f(cell[0]),cell[2],cell[3],uv[0],uv[1],uv[4],uv[5],uv[6],uv[7],model.alpha());
                        }else{
                            // EDGE/HIDDEN use the opaque inner texture and a solid sampled colour.
                            add(triangles,new Vector3f(cell[0]),cell[1],cell[2]);
                            add(triangles,new Vector3f(cell[0]),cell[2],cell[3]);
                        }
                    }
                }
            }
            exterior=Node.of(triangles);triangles=new ArrayList<>();
            for(var part:BodyPart.values())regions.put(part,new ArrayList<>());
            for(int t=0;t<mesh.skinCount;t+=3){add(triangles,mesh.rest(t),mesh.rest(t+1),mesh.rest(t+2));partition(t);}
            skin=Node.of(triangles);
        }
        static void addSource(List<Triangle> out,Vector3f[] points,float[] raw,int v,Alpha alpha){
            int k=v*8;
            textured(out,points[v],points[v+1],points[v+2],raw[k+3],raw[k+4],raw[k+11],raw[k+12],raw[k+19],raw[k+20],alpha);
        }
        /** Clip in barycentric space, not UV area: a real cloth side can have a degenerate UV line.
         * Opaque texel runs are half-open rectangles under the entity texture's nearest/repeat sampling.
         * The resulting one exterior BVH serves both candidate rejection and coverage certification. */
        static void textured(List<Triangle> out,Vector3f a,Vector3f b,Vector3f c,
                             float u0,float v0,float u1,float v1,float u2,float v2,Alpha alpha){
            float[] x={u0*alpha.width,u1*alpha.width,u2*alpha.width},y={v0*alpha.height,v1*alpha.height,v2*alpha.height};
            float minX=Math.min(x[0],Math.min(x[1],x[2])),maxX=Math.max(x[0],Math.max(x[1],x[2]));
            float minY=Math.min(y[0],Math.min(y[1],y[2])),maxY=Math.max(y[0],Math.max(y[1],y[2]));
            int left=(int)Math.floor(minX),right=(int)Math.floor(maxX),top=(int)Math.floor(minY),bottom=(int)Math.floor(maxY);
            if(!Float.isFinite(minX)||!Float.isFinite(maxX)||!Float.isFinite(minY)||!Float.isFinite(maxY)
                    ||minX<=Integer.MIN_VALUE+1024f||minY<=Integer.MIN_VALUE+1024f
                    ||maxX>=Integer.MAX_VALUE-1024f||maxY>=Integer.MAX_VALUE-1024f
                    ||(long)right-left>alpha.width||(long)bottom-top>alpha.height)
                throw new IllegalArgumentException("Unsupported exterior texture span");
            if(alpha.solid(left,top,right,bottom)){add(out,a,b,c);return;}
            if(alpha.empty(left,top,right,bottom))return;
            boolean hole=false;
            for(int row=top;row<=bottom&&!hole;row++)for(int column=left;column<=right;){
                if(alpha.opaque(column,row)){column++;continue;}
                int start=column;while(column<right&&!alpha.opaque(column+1,row))column++;int end=column++;
                if(area(texelPolygon(x,y,minX,minY,start,end,row))>0){hole=true;break;}
            }
            // UV bounding boxes can touch transparent atlas padding without any real hole.
            // Keep those original triangles intact, avoiding needless strips and numerical seams.
            if(!hole){add(out,a,b,c);return;}
            var ab=new Vector3f(b).sub(a);var ac=new Vector3f(c).sub(a);
            for(int row=top;row<=bottom;row++)for(int column=left;column<=right;){
                if(!alpha.opaque(column,row)){column++;continue;}
                int start=column;while(column<right&&alpha.opaque(column+1,row))column++;int end=column++;
                var polygon=texelPolygon(x,y,minX,minY,start,end,row);
                if(area(polygon)==0)continue;
                var first=polygon.getFirst();var p=new Vector3f(a).fma(first[0],ab).fma(first[1],ac);
                for(int i=1;i+1<polygon.size();i++){
                    var q=polygon.get(i);var r=polygon.get(i+1);
                    add(out,p,new Vector3f(a).fma(q[0],ab).fma(q[1],ac),new Vector3f(a).fma(r[0],ab).fma(r[1],ac));
                }
            }
        }
        static List<float[]> texelPolygon(float[] x,float[] y,float minX,float minY,int start,int end,int row){
            // Constant UV on an upper edge belongs to the following texel, even when UV area is zero.
            if(minX>=end+1f||minY>=row+1f)return List.of();
            List<float[]> polygon=new ArrayList<>(List.of(new float[]{0,0},new float[]{1,0},new float[]{0,1}));
            polygon=clip(polygon,new float[]{x[0]-start,x[1]-start,x[2]-start},true);
            polygon=clip(polygon,new float[]{end+1f-x[0],end+1f-x[1],end+1f-x[2]},true);
            polygon=clip(polygon,new float[]{y[0]-row,y[1]-row,y[2]-row},true);
            return clip(polygon,new float[]{row+1f-y[0],row+1f-y[1],row+1f-y[2]},true);
        }
        /** BodyPart's current classifier is a bottom/middle/top height partition for each fixed normal.
         * Find its transitions through the production classifier, so no private .22/.82 copy can drift.
         * Endpoints are adjacent representable floats. Candidate sites are classified again after anchoring. */
        void partition(int triangle){
            var bounds=mesh.bounds();float low=bounds[1],high=bounds[4],mid=low+(high-low)*.5f;
            var bottom=partAt(triangle,low);var middle=partAt(triangle,mid);var top=partAt(triangle,high);
            float[] first=bottom==middle?new float[]{low,low}:transition(triangle,low,mid,bottom);
            float[] second=middle==top?new float[]{high,high}:transition(triangle,mid,high,middle);
            if(bottom!=middle)addRegion(triangle,bottom,low,first[0]);
            addRegion(triangle,middle,bottom==middle?low:first[1],middle==top?high:second[0]);
            if(middle!=top)addRegion(triangle,top,second[1],high);
        }
        BodyPart partAt(int triangle,float y){return BodyPart.of(mesh,triangle,new Vector3f(0,y,0));}
        float[] transition(int triangle,float low,float high,BodyPart lower){
            while(true){float mid=low+(high-low)*.5f;if(mid==low||mid==high)return new float[]{low,high};
                if(partAt(triangle,mid)==lower)low=mid;else high=mid;}
        }
        void addRegion(int triangle,BodyPart part,float low,float high){
            float a=mesh.rest(triangle).y,b=mesh.rest(triangle+1).y,c=mesh.rest(triangle+2).y;
            List<float[]> polygon=new ArrayList<>(List.of(new float[]{0,0},new float[]{1,0},new float[]{0,1}));
            polygon=clip(polygon,new float[]{a-low,b-low,c-low},true);
            polygon=clip(polygon,new float[]{high-a,high-b,high-c},true);
            if(area(polygon)>1e-10f)regions.get(part).add(new Region(triangle,part,mesh.normal(triangle),polygon));
        }
        Selection select(BodyWound wound){
            if(!mesh.valid(wound))return new Selection(null,Outcome.INVALID_DATA);
            var origin=mesh.anchor(wound);var part=BodyPart.of(mesh,wound);var original=wound.origin();
            if(fits(original,wound,part))return new Selection(original,Outcome.SAME_PART);
            var previous=mesh.displaySite(wound);
            if(BodyPart.of(mesh,previous.triangle(),mesh.anchor(previous.hit()))==part&&fits(previous,wound,part))return new Selection(previous,Outcome.SAME_PART);
            var same=search(wound,origin,part,false);
            if(same.site()!=null)return new Selection(same.site(),Outcome.SAME_PART);
            if(same.bare())return new Selection(null,Outcome.NARROW_EXPOSURE);
            // A moving hat cannot authorize a new cross-part anchor from its static reference pose.
            // The State transition on operation exit schedules another check; no phase tick scanning.
            if(hatOperation||!covered.computeIfAbsent(part,this::fullyCovered))return new Selection(null,Outcome.UNCERTAIN_COVERAGE);
            var other=search(wound,origin,part,true);
            return other.site()==null?new Selection(null,Outcome.NO_EXPOSED_SKIN):new Selection(other.site(),Outcome.EXPOSED_EDGE);
        }
        private record Search(BodyWound.Site site,boolean bare) {}
        private record Nearby(Region region,float[] closest,float lowerBound) {}
        /** Compare actual anchored Sites across every eligible triangle. Refinement is deterministic
         * and finite (10 bisections toward the closest point), not a continuous global optimum claim. */
        Search search(BodyWound wound,Vector3f origin,BodyPart logical,boolean crossPart){
            var ordered=new ArrayList<Nearby>();
            for(var entry:regions.entrySet())if((entry.getKey()!=logical)==crossPart)for(var region:entry.getValue()){
                var closest=closest(region,origin);ordered.add(new Nearby(region,closest,at(region,closest).distanceSquared(origin)));}
            ordered.sort(Comparator.comparingDouble(Nearby::lowerBound).thenComparingInt(n->n.region().triangle()));
            BodyWound.Site best=null;float bestDistance=Float.POSITIVE_INFINITY;boolean bare=false;
            var originalNormal=mesh.normal(wound.triangle());
            for(var nearby:ordered){
                if(nearby.lowerBound()>bestDistance+1e-4f)break;
                var region=nearby.region();var samples=new ArrayList<float[]>();samples.add(nearby.closest());
                float u=0,v=0;for(var point:region.polygon()){u+=point[0];v+=point[1];}
                samples.add(new float[]{u/region.polygon().size(),v/region.polygon().size()});
                for(int i=0;i<region.polygon().size();i++){
                    var p=region.polygon().get(i);var q=region.polygon().get((i+1)%region.polygon().size());samples.add(p);
                    samples.add(mix(p,q,1f/3));samples.add(mix(p,q,2f/3));}
                for(int i=0;i<=3;i++)for(int j=0;j<=3-i;j++){
                    var p=new float[]{i/3f,j/3f};if(inside(region.polygon(),p))samples.add(p);}
                for(var sample:samples){
                    var site=site(region,sample);var point=mesh.anchor(site.hit());
                    if(BodyPart.of(mesh,region.triangle(),point)!=region.part()||!clear(point,region.normal()))continue;
                    bare=true;if(!fits(site,wound,crossPart?null:logical))continue;
                    float[] accepted=sample,rejected=nearby.closest();
                    var refined=site;float refinedDistance=point.distanceSquared(origin);
                    // Walk the exposed edge toward the logical anchor while keeping the full patch clear.
                    for(int step=0;step<10&&sample!=nearby.closest();step++){
                        var trial=mix(accepted,rejected,.5f);var trialSite=site(region,trial);
                        var trialPoint=mesh.anchor(trialSite.hit());
                        if(BodyPart.of(mesh,region.triangle(),trialPoint)==region.part()&&fits(trialSite,wound,crossPart?null:logical)){
                            accepted=trial;float distance=trialPoint.distanceSquared(origin);
                            if(distance<refinedDistance||distance==refinedDistance&&preferred(trialSite,refined,origin,originalNormal)){
                                refined=trialSite;refinedDistance=distance;}
                        }
                        else rejected=trial;
                    }
                    site=refined;float distance=refinedDistance;
                    if(best==null||distance<bestDistance||distance==bestDistance&&preferred(site,best,origin,originalNormal)){
                        best=site;bestDistance=distance;}
                    if(sample==nearby.closest())break;
                }
            }
            return new Search(best,bare);
        }
        boolean preferred(BodyWound.Site a,BodyWound.Site b,Vector3f origin,Vector3f originalNormal){
            // Equal-distance sites prefer the original side, then the neighbouring surface normal.
            boolean aSide=Math.signum(mesh.anchor(a.hit()).x)==Math.signum(origin.x),bSide=Math.signum(mesh.anchor(b.hit()).x)==Math.signum(origin.x);
            if(aSide!=bSide)return aSide;
            float an=mesh.normal(a.triangle()).dot(originalNormal),bn=mesh.normal(b.triangle()).dot(originalNormal);
            if(an!=bn)return an>bn;
            if(a.triangle()!=b.triangle())return a.triangle()<b.triangle();
            return a.u()!=b.u()?a.u()<b.u():a.v()<b.v();
        }
        BodyWound.Site site(Region region,float[] p){
            float u=Math.max(0,Math.min(1,p[0])),v=Math.max(0,Math.min(1-u,p[1]));
            return new BodyWound.Site(region.triangle(),u,v);
        }
        Vector3f at(Region region,float[] p){return mesh.anchor(site(region,p).hit());}
        float[] closest(Region region,Vector3f origin){
            var a=mesh.rest(region.triangle());var ab=mesh.rest(region.triangle()+1).sub(a);var ac=mesh.rest(region.triangle()+2).sub(a);var delta=new Vector3f(origin).sub(a);
            float aa=ab.dot(ab),bb=ac.dot(ac),cross=ab.dot(ac),det=aa*bb-cross*cross;
            if(det>1e-16f){float x=delta.dot(ab),y=delta.dot(ac);var p=new float[]{(x*bb-y*cross)/det,(y*aa-x*cross)/det};
                if(inside(region.polygon(),p))return p;}
            float[] best=region.polygon().getFirst();float distance=Float.POSITIVE_INFINITY;
            for(int i=0;i<region.polygon().size();i++){
                var p=region.polygon().get(i);var q=region.polygon().get((i+1)%region.polygon().size());var from=at(region,p);var edge=at(region,q).sub(from);
                float length=edge.lengthSquared(),f=length==0?0:Math.max(0,Math.min(1,new Vector3f(origin).sub(from).dot(edge)/length));
                var candidate=mix(p,q,f);float d=at(region,candidate).distanceSquared(origin);if(d<distance){distance=d;best=candidate;}
            }return best;
        }
        static boolean inside(List<float[]> polygon,float[] p){
            for(int i=0;i<polygon.size();i++){var a=polygon.get(i);var b=polygon.get((i+1)%polygon.size());
                if((b[0]-a[0])*(p[1]-a[1])-(b[1]-a[1])*(p[0]-a[0])<0)return false;}
            return true;
        }
        static float[] mix(float[] a,float[] b,float f){return new float[]{a[0]+f*(b[0]-a[0]),a[1]+f*(b[1]-a[1])};}
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
            var selected=regions.get(part);if(selected.isEmpty())return false;
            for(var region:selected){var polygon=region.polygon();var a=at(region,polygon.getFirst());
                for(int i=1;i+1<polygon.size();i++)for(var direction:directions(region.normal()))
                    if(!coveredTriangle(a,at(region,polygon.get(i)),at(region,polygon.get(i+1)),direction))return false;
            }return true;
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
    private record Region(int triangle,BodyPart part,Vector3f normal,List<float[]> polygon) {}
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
