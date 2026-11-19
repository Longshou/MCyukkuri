package io.github.longshou.mcyukkuri.pose.cli;

import com.google.gson.*;
import io.github.longshou.mcyukkuri.pose.*;
import java.util.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.zip.*;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Preview transport: core splice, clips, composition, care and skin; triangle normals for the software rasterizer. */
final class RenderPose implements Overlays.Contact {
    private static final Map<String,RenderPose> MODELS=new LinkedHashMap<>(4,.75f,true);
    private static final Map<String,byte[]> SOURCES=new HashMap<>();
    private static int serial;
    final JsonObject data;
    final Rig.Bone[] bones;
    final float[][] pose,defaults;
    final Matrix4f[] world;
    final Compose composer;
    final float[] rest,offsets;
    float[] source;
    final Skin.Binding[] bindings;
    final int[] features;
    final Map<Integer,Matrix4f> featureTransforms=new HashMap<>();
    final Map<String,Clips.Clip> clips=new HashMap<>();
    final int root;
    final Vector3f scratch=new Vector3f();
    Vector3f anchor;
    float puff,retention;
    /** Rest body frame of the swaddle retention swelling (Skin.retentionFrame); the request's "retention" is its amount, 0..1. */
    final Skin.RetentionFrame body;
    private final BitSet overSkin=new BitSet();
    private final Skin.Deform retain;
    /** Limbs that give way to the request's "wall" (faces around the body axis in blocks, after its yaw), built on first use. */
    private WallYield wallYield;
    private boolean wallYieldBuilt;
    RenderPose(JsonObject request) {
        data=request.getAsJsonObject("model");
        if(request.has("fit")&&!request.get("fit").isJsonNull())Splice.fit(data,request.getAsJsonObject("fit"),hash->true);
        if(request.has("revision")&&!request.get("revision").isJsonNull()&&!Splice.revision(data,request.getAsJsonObject("revision")))
            throw new IllegalArgumentException("Revision skeleton mismatch");
        bones=Rig.bones(data.getAsJsonArray("bones"));pose=Rig.values(bones.length);defaults=Rig.values(bones.length);
        world=Rig.matrices(bones.length);composer=new Compose(bones);
        int found=-1;for(int i=0;i<bones.length;i++)if(bones[i].name.equals("root"))found=i;
        if(found<0)throw new IllegalArgumentException("Missing root");root=found;
        rest=Rig.floats(data.getAsJsonArray("vertices"));source=rest;offsets=new float[rest.length/8*3];
        bindings=new Skin.Binding[rest.length/8];features=new int[bindings.length];Arrays.fill(features,-1);
        var weights=data.getAsJsonArray("weights");for(int i=0;i<bindings.length;i++){var w=Rig.floats(weights.get(i).getAsJsonArray());bindings[i]=new Skin.Binding((int)w[0],(int)w[1],w[2]);}
        for(var raw:data.getAsJsonArray("parts")){var p=raw.getAsJsonObject();if(p.has("feature_bone")){
            int b=p.get("feature_bone").getAsInt();Arrays.fill(features,start(p),start(p)+count(p),b);featureTransforms.put(b,new Matrix4f());}}
        if(data.has("puff_surface"))offsets(data.getAsJsonObject("puff_surface"));
        if(data.has("puff_attachments"))for(var raw:data.getAsJsonArray("puff_attachments"))offsets(raw.getAsJsonObject());
        if(data.has("puff_offsets"))for(var raw:data.getAsJsonArray("puff_offsets")){
            var p=raw.getAsJsonObject();var part=data.getAsJsonArray("parts").get(p.get("part_index").getAsInt()).getAsJsonObject();
            System.arraycopy(Rig.floats(p.getAsJsonArray("offsets")),0,offsets,start(part)*3,count(part)*3);}
        data.getAsJsonObject("clips").entrySet().forEach(e->clips.put(e.getKey(),Clips.read(e.getValue().getAsJsonObject(),bones.length)));
        if(data.has("behavior_clips"))data.getAsJsonObject("behavior_clips").entrySet().forEach(e->clips.put(e.getKey(),Clips.read(e.getValue().getAsJsonObject(),bones.length)));
        clips.get("idle").sample(0,defaults);
        var ranges=new ArrayList<Integer>();
        for(var raw:data.getAsJsonArray("parts")){
            var p=raw.getAsJsonObject();String name=p.get("name").getAsString(),group=OfflineModel.group(name);
            if(group.equals("body")){ranges.add(start(p));ranges.add(count(p));}
            if(group.equals("hair")||group.equals("braid")||name.contains("back_lock"))overSkin.set(start(p),start(p)+count(p));
        }
        body=Skin.retentionFrame(rest,ranges.stream().mapToInt(Integer::intValue).toArray());
        retain=(index,point)->Skin.deformRetention(point,retention,body,overSkin.get(index));
    }
    private static int start(JsonObject p){return p.get("start").getAsInt();}
    private static int count(JsonObject p){return p.get("count").getAsInt();}
    private void offsets(JsonObject p){var v=Rig.floats(p.getAsJsonArray("offsets"));System.arraycopy(v,0,offsets,start(p)*3,v.length);}
    public void compose(){composer.world(pose,world,-1,1,null,null);updateFeatures();}
    private void updateFeatures(){featureTransforms.forEach((b,m)->Compose.feature(bones,world,b,m,new Matrix4f()));}
    private Vector3f skin(int i){var p=new Vector3f();Skin.base(i,p,scratch,source,bindings,world,retention>0?retain:null,puff,offsets,features,featureTransforms);return p;}
    public float minimumY(){float min=Float.POSITIVE_INFINITY;for(var raw:data.getAsJsonArray("parts")){
        var p=raw.getAsJsonObject();if(OfflineModel.group(p.get("name").getAsString()).equals("body"))for(int i=start(p);i<start(p)+count(p);i++)min=Math.min(min,skin(i).y);
    }return min;}
    public Vector3f mouth(){return world[root].transformPosition(new Vector3f(anchor));}
    private JsonObject frame(JsonObject request){
        String action=request.get("action").getAsString();float t=request.get("time").getAsFloat();
        for(int i=0;i<pose.length;i++)System.arraycopy(defaults[i],0,pose[i],0,9);
        clips.get(action).sample(t,pose);
        if(request.has("intensity")&&!request.get("intensity").isJsonNull()){
            float q=request.get("intensity").getAsFloat();for(int b=0;b<pose.length;b++)for(int c=0;c<9;c++)if(c!=3&&c!=5)pose[b][c]=defaults[b][c]+(pose[b][c]-defaults[b][c])*q;
            float tilt=Math.abs(pose[root][3])+Math.abs(pose[root][5]);if(tilt>12){pose[root][3]*=12/tilt;pose[root][5]*=12/tilt;}
        }
        if(request.has("adjustment")&&!request.get("adjustment").isJsonNull()){
            var a=request.getAsJsonObject("adjustment");if(a.has("translation")){var v=Rig.floats(a.getAsJsonArray("translation"));for(int i=0;i<3;i++)pose[root][i]+=v[i];}
            if(a.has("pitch"))pose[root][3]+=a.get("pitch").getAsFloat();
        }
        var clip=data.getAsJsonObject("clips").getAsJsonObject(action);
        if(data.has("behavior_clips")&&data.getAsJsonObject("behavior_clips").has(action))clip=data.getAsJsonObject("behavior_clips").getAsJsonObject(action);
        puff=clip.has("puff_envelope")?Clips.puffEnvelope(Rig.floats(clip.getAsJsonArray("puff_envelope")),clips.get(action).time(t)):0;
        retention=request.has("retention")?request.get("retention").getAsFloat():0;
        source=rest.clone();String expression=request.get("expression").getAsString();
        var patches=data.getAsJsonObject("expression_patches");
        if(patches!=null&&!patches.has(expression)&&data.has("expression_aliases")&&data.getAsJsonObject("expression_aliases").has(expression))expression=data.getAsJsonObject("expression_aliases").get(expression).getAsString();
        if(patches!=null&&patches.has(expression))for(var raw:patches.getAsJsonArray(expression)){
            var patch=raw.getAsJsonObject();var part=data.getAsJsonArray("parts").get(patch.get("index").getAsInt()).getAsJsonObject();
            var values=Rig.floats(patch.getAsJsonArray("vertices"));System.arraycopy(values,0,source,start(part)*8,values.length);
        }
        compose();
        if(request.has("care")){
            var care=request.getAsJsonObject("care");anchor=new Vector3f(Rig.floats(care.getAsJsonArray("anchor")));
            var target=Rig.floats(care.getAsJsonArray("target"));
            Overlays.care(this,pose,world,root,!action.equals("first_toilet_assist"),target[0],target[1],target[2],care.get("blend").getAsFloat());updateFeatures();
        }
        float scale=request.get("scale").getAsFloat();float[] output=new float[rest.length];
        // A wall beside the body: the entity's yaw turns the output, and with "yield" the loose parts give way as in the game.
        var wall=request.has("wall")&&!request.get("wall").isJsonNull()?request.getAsJsonObject("wall"):null;
        var faces=new ArrayList<WallYield.Face>();Matrix4f turn=null;boolean yield=false;
        if(wall!=null){
            turn=new Matrix4f().rotateY((float)Math.toRadians(wall.get("yaw").getAsFloat()));yield=wall.get("yield").getAsBoolean();
            for(var raw:wall.getAsJsonArray("faces")){var f=Rig.floats(raw.getAsJsonArray());faces.add(new WallYield.Face(f[0],f[1],f[2],f[3],f[4]));}
            if(!wallYieldBuilt){wallYieldBuilt=true;var parts=data.getAsJsonArray("parts");int n=parts.size();
                var names=new String[n];var starts=new int[n];var counts=new int[n];
                for(int i=0;i<n;i++){var p=parts.get(i).getAsJsonObject();names[i]=p.get("name").getAsString();starts[i]=start(p);counts[i]=count(p);}
                wallYield=WallYield.of(data.get("id").getAsString(),bones,bindings,rest,names,starts,counts);}
            if(wallYield!=null){if(yield)wallYield.solve((i,target)->target.set(skin(i)),world,new Matrix4f(turn).scale(scale/16),faces,kind->false);else wallYield.clear();}
        }
        boolean wantPaper=request.has("paperCover")&&request.get("paperCover").isJsonArray();
        List<String> accPrefixes=new ArrayList<>();
        if(wantPaper)for(var raw:request.getAsJsonArray("paperCover"))accPrefixes.add(raw.getAsString());
        List<Float> pBody=wantPaper?new ArrayList<>():null,pMesh=wantPaper?new ArrayList<>():null,pHat=wantPaper?new ArrayList<>():null;
        Matrix4f localToWall=turn!=null?new Matrix4f(turn).scale(1/16f):new Matrix4f().scale(1/16f);
        Matrix4f wallToLocal=new Matrix4f(localToWall).invert();
        for(int pi=0;pi<data.getAsJsonArray("parts").size();pi++){
            var part=data.getAsJsonArray("parts").get(pi).getAsJsonObject();
            if(part.has("anatomy")&&!part.get("anatomy").getAsString().isEmpty()&&!action.startsWith("anatomy_"+part.get("anatomy").getAsString()+"_"))continue;
            boolean smooth=false;
            if(puff>0){if(data.has("puff_surface")&&data.getAsJsonObject("puff_surface").get("part_index").getAsInt()==pi){part=data.getAsJsonObject("puff_surface");smooth=true;}
                if(data.has("puff_attachments"))for(var raw:data.getAsJsonArray("puff_attachments"))if(raw.getAsJsonObject().get("part_index").getAsInt()==pi)part=raw.getAsJsonObject();}
            int start=start(part),end=start+count(part);
            String origName=data.getAsJsonArray("parts").get(pi).getAsJsonObject().get("name").getAsString();
            boolean loose=yield&&WallYield.loose(origName);
            boolean isBody=OfflineModel.group(origName).equals("body")||origName.endsWith("_rump_mark");
            boolean isAcc=wantPaper&&accPrefixes.stream().anyMatch(origName::startsWith);
            boolean skipTent=origName.startsWith("face_")||origName.contains("puff");
            for(int i=start;i<end;i++){var p=skin(i);if(yield&&wallYield!=null)wallYield.apply(i,p);p.mul(scale);
                if(wantPaper&&!skipTent){
                    var tp=new Vector3f(p);
                    if(loose)PaperTent.pressPoint(tp,localToWall,wallToLocal,faces,0f);
                    if(isBody){pBody.add(tp.x);pBody.add(tp.y);pBody.add(tp.z);}
                    if(isAcc){pHat.add(tp.x);pHat.add(tp.y);pHat.add(tp.z);}
                    else{pMesh.add(tp.x);pMesh.add(tp.y);pMesh.add(tp.z);}
                }
                if(turn!=null)turn.transformPosition(p);
                if(loose){float keep=WallYield.press(faces,p.x/16,p.y/16,p.z/16);p.x*=keep;p.z*=keep;}
                int k=i*8;output[k]=p.x;output[k+1]=p.y;output[k+2]=p.z;output[k+3]=source[k+3];output[k+4]=source[k+4];}
            for(int i=start;i<end;i+=3){int k=i*8;
                String name=origName;
                if(data.has("house")&&data.get("house").getAsBoolean()&&name.startsWith("face_")&&source[k+7]>=-.15){Arrays.fill(output,k,k+24,0);continue;}
                var a=new Vector3f(output[k+8]-output[k],output[k+9]-output[k+1],output[k+10]-output[k+2]);
                var b=new Vector3f(output[k+16]-output[k],output[k+17]-output[k+1],output[k+18]-output[k+2]);a.cross(b);
                if(a.length()>1e-8f){a.normalize();for(int j=0;j<3;j++){output[k+j*8+5]=a.x;output[k+j*8+6]=a.y;output[k+j*8+7]=a.z;}}
            }
            if(smooth)smooth(output,start,end,puff);
        }
        var out=new JsonObject();
        if(request.has("vertexFormat")&&request.get("vertexFormat").getAsString().equals("float32le")){
            var bytes=ByteBuffer.allocate(output.length*Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
            bytes.asFloatBuffer().put(output);
            out.addProperty("vertices",Base64.getEncoder().encodeToString(bytes.array()));
            return out;
        }
        out.add("vertices",OfflineModel.array(output));
        if(wantPaper){
            var grid=PaperTent.build(floatsOf(pBody),pBody.size(),floatsOf(pMesh),pMesh.size(),floatsOf(pHat),pHat.size(),
                    yield?faces:List.of(),yield&&!faces.isEmpty()?localToWall:null,Double.POSITIVE_INFINITY);
            if(grid!=null){
                out.add("paper_top",OfflineModel.array(sheetTris(grid.top(),grid.rows(),grid.cols(),false,turn)));
                out.add("paper_under",OfflineModel.array(sheetTris(grid.under(),grid.rows(),grid.cols(),true,turn)));
            }
        }
        var matrices=new JsonArray();for(var m:world){var flat=new JsonArray();for(var row:Authoring.rows(m))for(var x:row.getAsJsonArray())flat.add(x);matrices.add(flat);}out.add("world",matrices);
        out.add("root_pose",OfflineModel.array(pose[root]));return out;
    }
    private static float[] floatsOf(List<Float> list){float[] r=new float[list.size()];for(int i=0;i<r.length;i++)r[i]=list.get(i);return r;}
    private static float[] sheetTris(float[][][] pts,int rows,int cols,boolean inward,Matrix4f turn){
        float[] out=new float[rows*cols*6*8];int at=0;
        Vector3f pa=new Vector3f(),pb=new Vector3f(),pc=new Vector3f(),pd=new Vector3f(),n=new Vector3f();
        for(int r=0;r<rows;r++){
            float v0=rows==8&&r>6?.75f-.20f*(r-6):.25f+.50f*r/Math.min(6,rows);
            float v1=rows==8&&r+1>6?.75f-.20f*(r+1-6):.25f+.50f*(r+1)/Math.min(6,rows);
            for(int c=0;c<cols;c++){
                float u0=.1875f+.625f*c/cols,u1=.1875f+.625f*(c+1)/cols;
                pa.set(pts[r][c]);pb.set(pts[r+1][c]);pc.set(pts[r+1][c+1]);pd.set(pts[r][c+1]);
                if(turn!=null){turn.transformPosition(pa);turn.transformPosition(pb);turn.transformPosition(pc);turn.transformPosition(pd);}
                n.set(pb).sub(pa).cross(new Vector3f(pd).sub(pa));
                if(inward)n.negate();
                if(n.length()>1e-8f)n.normalize();
                if(!inward){
                    at=emitVert(out,at,pa,u0,v0,n);at=emitVert(out,at,pb,u0,v1,n);at=emitVert(out,at,pc,u1,v1,n);
                    at=emitVert(out,at,pa,u0,v0,n);at=emitVert(out,at,pc,u1,v1,n);at=emitVert(out,at,pd,u1,v0,n);
                }else{
                    at=emitVert(out,at,pa,u0,v0,n);at=emitVert(out,at,pd,u1,v0,n);at=emitVert(out,at,pc,u1,v1,n);
                    at=emitVert(out,at,pa,u0,v0,n);at=emitVert(out,at,pc,u1,v1,n);at=emitVert(out,at,pb,u0,v1,n);
                }
            }
        }
        return out;
    }
    private static int emitVert(float[] out,int at,Vector3f p,float u,float v,Vector3f n){
        out[at]=p.x;out[at+1]=p.y;out[at+2]=p.z;out[at+3]=u;out[at+4]=v;out[at+5]=n.x;out[at+6]=n.y;out[at+7]=n.z;return at+8;
    }
    private void smooth(float[] output,int start,int end,float amount){
        var groups=new HashMap<String,Vector3f>();
        for(int i=start;i<end;i+=3){int k=i*8;var n=new Vector3f(output[k+8]-output[k],output[k+9]-output[k+1],output[k+10]-output[k+2]).cross(new Vector3f(output[k+16]-output[k],output[k+17]-output[k+1],output[k+18]-output[k+2]));
            for(int j=i;j<i+3;j++)groups.computeIfAbsent(key(j),s->new Vector3f()).add(n);}
        for(int i=start;i<end;i++){var n=new Vector3f(groups.get(key(i)));if(n.length()>1e-8f){n.normalize();int k=i*8;var blended=new Vector3f(output[k+5],output[k+6],output[k+7]).lerp(n,amount);if(blended.length()>1e-8f){blended.normalize();output[k+5]=blended.x;output[k+6]=blended.y;output[k+7]=blended.z;}}}
    }
    private String key(int i){return Math.round(rest[i*8]*1e5)+","+Math.round(rest[i*8+1]*1e5)+","+Math.round(rest[i*8+2]*1e5);}
    static JsonObject run(JsonObject request) throws IOException {
        if(request.get("op").getAsString().equals("register")){
            String id=Integer.toString(++serial);
            var bytes=new ByteArrayOutputStream();try(var zip=new GZIPOutputStream(bytes)){zip.write(request.toString().getBytes(StandardCharsets.UTF_8));}
            SOURCES.put(id,bytes.toByteArray());var model=new RenderPose(request);cache(id,model);
            var out=new JsonObject();out.addProperty("id",id);out.add("model",model.data);return out;
        }
        String id=request.get("id").getAsString();
        if(request.get("op").getAsString().equals("release")){MODELS.remove(id);SOURCES.remove(id);return new JsonObject();}
        if(!SOURCES.containsKey(id))throw new IllegalArgumentException("Unknown registered pose: "+id);
        var model=MODELS.get(id);
        if(model==null){try(var zip=new GZIPInputStream(new ByteArrayInputStream(SOURCES.get(id)))){
            model=new RenderPose(JsonParser.parseReader(new InputStreamReader(zip,StandardCharsets.UTF_8)).getAsJsonObject());cache(id,model);
        }}
        return model.frame(request);
    }
    private static void cache(String id,RenderPose model){
        // Two active actors cover ordinary strips; compressed inputs retain larger scenes without retaining every parsed rig.
        if(MODELS.size()==2)MODELS.remove(MODELS.keySet().iterator().next());MODELS.put(id,model);
    }
}
