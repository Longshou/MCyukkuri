package io.github.longshou.mcyukkuri.pose;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPInputStream;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Frozen pre-extraction client outputs, all vertices (not selected probes), no Minecraft on the classpath. */
class PoseGoldenTest {
    /** Rows are streamed one at a time: the 62 MB golden does not fit a CI test worker heap as one tree. */
    interface RowSink { void accept(JsonObject row)throws Exception; }
    static void rows(RowSink sink)throws Exception{
        try(var in=new GZIPInputStream(PoseGoldenTest.class.getResourceAsStream("/pose/golden.json.gz"));
            var reader=new JsonReader(new InputStreamReader(in,StandardCharsets.UTF_8))){
            reader.beginArray();
            while(reader.hasNext())sink.accept(JsonParser.parseReader(reader).getAsJsonObject());
            reader.endArray();
        }
    }
    static JsonObject model(String id)throws IOException{
        JsonObject source;
        try(var in=new GZIPInputStream(PoseGoldenTest.class.getResourceAsStream("/pose/"+id+".json.gz"))){source=JsonParser.parseReader(new InputStreamReader(in,StandardCharsets.UTF_8)).getAsJsonObject();}
        var data=source.getAsJsonObject("base");
        if(source.has("fit"))assertTrue(Splice.fit(data,source.getAsJsonObject("fit"),source.get("sha").getAsString()::equals));
        for(String file:List.of("motion_corrections","house_ecology","toilet_contact")){
            if(source.has(file))Splice.revision(data,source.getAsJsonObject(file));
        }
        assertTrue(source.get("expected").equals(JsonParser.parseString(data.toString())),"fit / motion / house / toilet serialized splice parity: "+id);
        data.add("fixture_mouth",source.get("mouth"));
        return data;
    }
    static void near(JsonArray expected,float[] actual,String label){
        assertEquals(expected.size(),actual.length,label);
        float max=0;int at=-1;
        for(int i=0;i<actual.length;i++){
            assertTrue(Float.isFinite(actual[i]),label+" nonfinite at "+i);
            float diff=Math.abs(expected.get(i).getAsFloat()-actual[i]);if(diff>max){max=diff;at=i;}
        }
        assertTrue(max<=1e-5f,label+" max="+max+" at="+at);
    }
    @Test void clientGoldenParity()throws Exception{
        var sources=new HashMap<String,JsonObject>();var framesByCase=new HashMap<String,Frame>();int[] frames={0};
        rows(row->{
            String name=row.get("case").getAsString();if(name.equals("wounds"))return;
            String id=row.get("model").getAsString();if(!sources.containsKey(id))sources.put(id,model(id));
            if(!framesByCase.containsKey(name))framesByCase.put(name,new Frame(sources.get(id),id));
            var frame=framesByCase.get(name);frame.run(row);
            for(int i=0;i<frame.world.length;i++)near(row.getAsJsonArray("world").get(i).getAsJsonArray(),frame.world[i].get(new float[16]),name+" bone "+i);
            var p=new Vector3f();float[] points=new float[frame.vertices.length/8*3];
            for(int i=0;i<points.length/3;i++){
                frame.skin(i,p);
                if(name.equals("pregnancy"))Skin.pregnancy(p,.7f,.4f,row.getAsJsonArray("groups").get(i).getAsInt(),new Vector3f(Rig.floats(row.getAsJsonArray("crownLift"))),new Vector3f(Rig.floats(row.getAsJsonArray("braidLift"))));
                points[i*3]=p.x;points[i*3+1]=p.y;points[i*3+2]=p.z;
            }
            near(row.getAsJsonArray("vertices"),points,name+" vertices");frames[0]++;
        });
        assertEquals(33,frames[0]);
        System.out.println("PASS pose golden: 33 client frames, every matrix and vertex <= 1e-5 model units");
    }
    @Test void woundGoldenParity()throws Exception{
        JsonObject[] found={null};rows(row->{if(row.get("case").getAsString().equals("wounds"))found[0]=row;});
        JsonObject row=found[0];
        assertNotNull(row);
        // Common-side selected skeleton, local pose and wound-deformed rest points are explicit inputs.
        var bones=Rig.bones(row.getAsJsonArray("bones"));
        var values=Rig.values(bones.length);
        sampleCompact(row.getAsJsonObject("idleClip"),0,values);sampleCompact(row.getAsJsonObject("clip"),.37f,values);
        var hat=row.getAsJsonObject("hat");Overlays.hat(values[hat.get("bone").getAsInt()],.6f,false,hat.get("lift").getAsFloat(),hat.get("forward").getAsFloat());
        var world=Rig.matrices(row.getAsJsonArray("world").size());
        for(int i=0;i<world.length;i++){
            var b=bones[i];if(b.parent>=0)world[i].set(world[b.parent]);
            near(row.getAsJsonArray("local").get(i).getAsJsonArray(),values[i],"wound track "+i);
            Compose.woundLocal(world[i],new float[]{b.x,b.y,b.z},values[i]);
            near(row.getAsJsonArray("world").get(i).getAsJsonArray(),world[i].get(new float[16]),"wound bone "+i);
        }
        var input=Rig.floats(row.getAsJsonArray("input"));var out=new float[input.length];var p=new Vector3f();var temp=new Vector3f();
        for(int i=0;i<input.length/3;i++){
            Skin.weighted(Rig.floats(row.getAsJsonArray("weights").get(i).getAsJsonArray()),new Vector3f(input[i*3],input[i*3+1],input[i*3+2]),world,p,temp);
            out[i*3]=p.x;out[i*3+1]=p.y;out[i*3+2]=p.z;
        }
        near(row.getAsJsonArray("vertices"),out,"wound-deformed skin");
    }
    static void sampleCompact(JsonObject clip,float elapsed,float[][] values){
        float duration=clip.get("duration").getAsFloat();float time=clip.get("loop").getAsBoolean()?Math.max(0,elapsed)%duration:Math.clamp(elapsed,0,duration);
        for(var raw:clip.getAsJsonArray("tracks")){var t=raw.getAsJsonObject();Clips.sample(Rig.floats(t.getAsJsonArray("keys")),t.get("offset").getAsInt(),time,values[t.get("bone").getAsInt()]);}
    }
    static final class Frame implements Overlays.Contact {
        final Rig.Bone[] bones;
        final float[] vertices,puff;
        final Skin.Binding[] bindings;
        final float[][] pose,defaults;
        final Matrix4f[] world;
        final Compose compose;
        final int[] features;
        final Map<Integer,Matrix4f> transforms=new HashMap<>();
        final JsonObject data;
        final Vector3f mouth,second=new Vector3f(),point=new Vector3f();
        final List<Integer> floor=new ArrayList<>();
        final int root;
        float puffAmount,belly;
        Frame(JsonObject data,String id)throws Exception{
            this.data=data;bones=Rig.bones(data.getAsJsonArray("bones"));vertices=Rig.floats(data.getAsJsonArray("vertices"));
            pose=Rig.values(bones.length);defaults=Rig.values(bones.length);world=Rig.matrices(bones.length);compose=new Compose(bones);root=bone("root");
            var rows=data.getAsJsonArray("weights");bindings=new Skin.Binding[rows.size()];
            for(int i=0;i<bindings.length;i++){var v=rows.get(i).getAsJsonArray();bindings[i]=new Skin.Binding(v.get(0).getAsInt(),v.get(1).getAsInt(),v.get(2).getAsFloat());}
            features=new int[bindings.length];Arrays.fill(features,-1);puff=new float[bindings.length*3];
            for(var raw:data.getAsJsonArray("parts")){
                var part=raw.getAsJsonObject();int start=part.get("start").getAsInt(),count=part.get("count").getAsInt();String name=part.get("name").getAsString();
                if(name.startsWith("soft_head_slice_")||name.equals("house_body_surface")||name.equals("predator_body_surface")||name.equals("deficient_body_surface")||name.equals("rounded_body_surface"))for(int i=start;i<start+count;i++)floor.add(i);
                if(part.has("feature_bone")){int f=part.get("feature_bone").getAsInt();Arrays.fill(features,start,start+count,f);transforms.put(f,new Matrix4f());}
            }
            if(data.has("puff_surface")){
                offsets(data.getAsJsonObject("puff_surface"));if(data.has("puff_attachments"))for(var p:data.getAsJsonArray("puff_attachments"))offsets(p.getAsJsonObject());
                if(data.has("puff_offsets"))for(var raw:data.getAsJsonArray("puff_offsets")){var p=raw.getAsJsonObject();int start=data.getAsJsonArray("parts").get(p.get("part_index").getAsInt()).getAsJsonObject().get("start").getAsInt();var v=Rig.floats(p.getAsJsonArray("offsets"));System.arraycopy(v,0,puff,start*3,v.length);}
            }
            mouth=new Vector3f(Rig.floats(data.getAsJsonArray("fixture_mouth")));
        }
        void offsets(JsonObject p){var v=Rig.floats(p.getAsJsonArray("offsets"));System.arraycopy(v,0,puff,p.get("start").getAsInt()*3,v.length);}
        int bone(String name){for(int i=0;i<bones.length;i++)if(bones[i].name.equals(name))return i;return -1;}
        public void compose(){compose.world(pose,world,-1,1,null,null);}
        void skin(int i,Vector3f out){Skin.base(i,out,second,vertices,bindings,world,null,puffAmount,puff,features,transforms);}
        public float minimumY(){float min=Float.MAX_VALUE;for(int i:floor){skin(i,point);min=Math.min(min,point.y);}return min==Float.MAX_VALUE?0:min;}
        public Vector3f mouth(){var p=new Vector3f(mouth);Skin.deformPregnancy(p,belly,0);return world[root].transformPosition(p);}
        void run(JsonObject row){
            String name=row.get("case").getAsString(),selected=row.get("clip").getAsString();float time=row.get("t").getAsFloat();
            var clips=data.getAsJsonObject("clips");var overrides=data.getAsJsonObject("behavior_clips");
            if(name.equals("expression")){
                var patches=new ArrayList<Skin.Patch>();
                for(var raw:data.getAsJsonObject("expression_patches").getAsJsonArray(row.get("expression").getAsString())){
                    var p=raw.getAsJsonObject();int start=data.getAsJsonArray("parts").get(p.get("index").getAsInt()).getAsJsonObject().get("start").getAsInt();
                    patches.add(new Skin.Patch(start*8,Rig.floats(p.getAsJsonArray("vertices"))));
                }
                Skin.expression(vertices.clone(),vertices,List.of(),patches);
            }
            Clips.read(clips.getAsJsonObject("idle"),bones.length).sample(0,defaults);
            for(int i=0;i<pose.length;i++)System.arraycopy(defaults[i],0,pose[i],0,9);
            var clip=overrides!=null&&overrides.has(selected)?overrides.getAsJsonObject(selected):clips.getAsJsonObject(selected);
            Clips.read(clip,bones.length).sample(time,pose);
            if(name.equals("puff")){puffAmount=row.get("puff").getAsFloat();var offsets=Rig.floats(row.getAsJsonArray("puffOffsets"));System.arraycopy(offsets,0,puff,0,puff.length);}
            if(name.equals("boarding"))Overlays.boarding(pose[root],17,-9,.85f,.6f);
            if(name.equals("pregnancy"))belly=.7f;
            compose();
            if(name.equals("feed")||name.equals("rear"))Overlays.care(this,pose,world,root,name.equals("feed"),.3f,2,-7,.8f);
            if(name.equals("braid")){
                var chain=new ArrayList<Integer>();chain.add(bone("hand_right"));for(int k=1,b;(b=bone("hand_right_braid_"+k))>=0;k++)chain.add(b);chain.add(bone("hand_right_tip"));
                float[] rest=new float[chain.size()*3+3];for(int i=0;i<chain.size();i++){var b=bones[chain.get(i)];rest[i*3]=b.x;rest[i*3+1]=b.y;rest[i*3+2]=b.z;}
                var b=bones[chain.getFirst()];float best=-1;
                for(var raw:data.getAsJsonArray("parts")){var p=raw.getAsJsonObject();if(!p.get("name").getAsString().startsWith("braid_lock_tuft"))continue;
                    for(int v=p.get("start").getAsInt(),end=v+p.get("count").getAsInt();v<end;v++){float x=vertices[v*8]-b.x,y=vertices[v*8+1]-b.y,z=vertices[v*8+2]-b.z,d=x*x+y*y+z*z;if(d>best){best=d;System.arraycopy(vertices,v*8,rest,chain.size()*3,3);}}}
                Overlays.braid(bones,world,pose,chain.stream().mapToInt(Integer::intValue).toArray(),rest,new Vector3f(6,8,-5),.7f,new Overlays.BraidScratch());
            }
            transforms.forEach((b,m)->{m.set(world[b]);if(bones[b].parent>=0)m.mul(new Matrix4f(world[bones[b].parent]).invert());});
        }
    }
}
