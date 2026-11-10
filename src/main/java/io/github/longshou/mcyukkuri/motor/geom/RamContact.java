package io.github.longshou.mcyukkuri.motor.geom;

import com.google.gson.*;
import io.github.longshou.mcyukkuri.body.ModelFingerprint;
import io.github.longshou.mcyukkuri.pose.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Contact envelope of the actual skinned parts, using the pose checker's splice/compose/skin operations. */
public record RamContact(double impact, double turn, double[] front, float launch, float strike, float settled) {
    public static final String CLIP = "lunge_ram";
    // Geometry sampling only: one hundred samples/second, including every authored impact marker.
    private static final int SAMPLES = 100;
    private static final Map<String,RamContact> CACHE = new ConcurrentHashMap<>();
    public static RamContact of(String model) { return CACHE.computeIfAbsent(model,RamContact::read); }

    /** Root-to-plane distance in model units. Launch closes the gap; authored recoil opens it again. */
    public double distance(double seconds) {
        double q = seconds <= strike ? Math.clamp((seconds-launch)/(strike-launch),0,1)
                : Math.clamp((settled-seconds)/(settled-strike),0,1);
        int i = Math.min(front.length-1,(int)Math.ceil(seconds*SAMPLES));
        return Math.max(front[Math.max(0,i)],turn+(impact-turn)*q);
    }

    private static JsonObject json(String path) { return JsonParser.parseString(new String(bytes(path),StandardCharsets.UTF_8)).getAsJsonObject(); }
    private static byte[] bytes(String path) {
        try (var stream=RamContact.class.getResourceAsStream("/assets/mcyukkuri/"+path)) {
            if(stream==null)throw new IllegalArgumentException("missing model resource "+path);
            return stream.readAllBytes();
        } catch(IOException e) { throw new UncheckedIOException(e); }
    }
    private static RamContact read(String id) {
        byte[] base=bytes("yukkuri/"+id+".json"); var hash=ModelFingerprint.of(base);
        var data=JsonParser.parseString(new String(base,StandardCharsets.UTF_8)).getAsJsonObject();
        for(String file:List.of("placement_fit","motion_corrections","house_ecology","toilet_contact","face_library")) {
            var revision=json("yukkuri_presentation/"+file+".json").getAsJsonObject("models").getAsJsonObject(id);
            if(revision==null)continue;
            boolean applied=file.equals("placement_fit")?Splice.fit(data,revision,hash::matches):Splice.revision(data,revision);
            if(!applied)throw new IllegalArgumentException("incompatible "+file+" for "+id);
        }
        var bones=Rig.bones(data.getAsJsonArray("bones")); var compose=new Compose(bones);
        var defaults=Rig.values(bones.length); var pose=Rig.values(bones.length); var world=Rig.matrices(bones.length);
        var clips=data.getAsJsonObject("clips"); var overrides=data.getAsJsonObject("behavior_clips");
        if(overrides!=null)for(var entry:overrides.entrySet())clips.add(entry.getKey(),entry.getValue());
        Clips.read(clips.getAsJsonObject("idle"),bones.length).sample(0,defaults);
        var vertices=Rig.floats(data.getAsJsonArray("vertices")); var weights=data.getAsJsonArray("weights");
        var bindings=new Skin.Binding[weights.size()]; var features=new int[weights.size()]; Arrays.fill(features,-1);
        for(int i=0;i<bindings.length;i++){var w=weights.get(i).getAsJsonArray();bindings[i]=new Skin.Binding(w.get(0).getAsInt(),w.get(1).getAsInt(),w.get(2).getAsFloat());}
        var transforms=new HashMap<Integer,Matrix4f>(); var visible=new ArrayList<Integer>();
        for(var raw:data.getAsJsonArray("parts")) {
            var part=raw.getAsJsonObject(); if(part.has("anatomy")&&!part.get("anatomy").getAsString().isEmpty())continue;
            int first=part.get("start").getAsInt(),end=first+part.get("count").getAsInt();
            for(int i=first;i<end;i++)visible.add(i);
            if(part.has("feature_bone")){int b=part.get("feature_bone").getAsInt();Arrays.fill(features,first,end,b);transforms.put(b,new Matrix4f());}
        }
        var markers=clips.getAsJsonObject(CLIP).getAsJsonObject("markers");
        float launch=markers.get("launch").getAsFloat(),strike=markers.get("impact").getAsFloat(),settled=markers.get("settled").getAsFloat();
        var front=new double[(int)Math.ceil(settled*SAMPLES)+1]; double impact=0,turn=0;
        var point=new Vector3f();var scratch=new Vector3f();var inverse=new Matrix4f();
        for(String name:List.of(CLIP,"idle","droop","startle")) {
            var clip=Clips.read(clips.getAsJsonObject(name),bones.length);
            int last=(int)Math.ceil(clip.duration*SAMPLES);
            for(int sample=0;sample<=last;sample++) {
                float time=Math.min(sample/(float)SAMPLES,Math.nextDown(clip.duration));
                for(int i=0;i<pose.length;i++)System.arraycopy(defaults[i],0,pose[i],0,9);
                clip.sample(time,pose);compose.world(pose,world,-1,1,null,null);
                transforms.forEach((b,m)->Compose.feature(bones,world,b,m,inverse));
                double f=0;
                for(int i:visible){Skin.base(i,point,scratch,vertices,bindings,world,null,0,null,features,transforms);f=Math.max(f,-point.z);turn=Math.max(turn,Math.hypot(point.x,point.z));}
                if(name.equals(CLIP)){front[Math.min(sample,front.length-1)]=f;if(sample==Math.round(strike*SAMPLES))impact=f;}
            }
        }
        return new RamContact(impact,turn,front,launch,strike,settled);
    }
}
