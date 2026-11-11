package io.github.longshou.mcyukkuri.pose.cli;

import com.google.gson.*;
import io.github.longshou.mcyukkuri.pose.*;
import java.util.*;
import java.io.*;
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
        for(int pi=0;pi<data.getAsJsonArray("parts").size();pi++){
            var part=data.getAsJsonArray("parts").get(pi).getAsJsonObject();
            if(part.has("anatomy")&&!part.get("anatomy").getAsString().isEmpty()&&!action.startsWith("anatomy_"+part.get("anatomy").getAsString()+"_"))continue;
            boolean smooth=false;
            if(puff>0){if(data.has("puff_surface")&&data.getAsJsonObject("puff_surface").get("part_index").getAsInt()==pi){part=data.getAsJsonObject("puff_surface");smooth=true;}
                if(data.has("puff_attachments"))for(var raw:data.getAsJsonArray("puff_attachments"))if(raw.getAsJsonObject().get("part_index").getAsInt()==pi)part=raw.getAsJsonObject();}
            int start=start(part),end=start+count(part);
            for(int i=start;i<end;i++){var p=skin(i).mul(scale);int k=i*8;output[k]=p.x;output[k+1]=p.y;output[k+2]=p.z;output[k+3]=source[k+3];output[k+4]=source[k+4];}
            for(int i=start;i<end;i+=3){int k=i*8;
                String name=data.getAsJsonArray("parts").get(pi).getAsJsonObject().get("name").getAsString();
                if(data.has("house")&&data.get("house").getAsBoolean()&&name.startsWith("face_")&&source[k+7]>=-.15){Arrays.fill(output,k,k+24,0);continue;}
                var a=new Vector3f(output[k+8]-output[k],output[k+9]-output[k+1],output[k+10]-output[k+2]);
                var b=new Vector3f(output[k+16]-output[k],output[k+17]-output[k+1],output[k+18]-output[k+2]);a.cross(b);
                if(a.length()>1e-8f){a.normalize();for(int j=0;j<3;j++){output[k+j*8+5]=a.x;output[k+j*8+6]=a.y;output[k+j*8+7]=a.z;}}
            }
            if(smooth)smooth(output,start,end,puff);
        }
        var out=new JsonObject();out.add("vertices",OfflineModel.array(output));
        var matrices=new JsonArray();for(var m:world){var flat=new JsonArray();for(var row:Authoring.rows(m))for(var x:row.getAsJsonArray())flat.add(x);matrices.add(flat);}out.add("world",matrices);
        out.add("root_pose",OfflineModel.array(pose[root]));return out;
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
