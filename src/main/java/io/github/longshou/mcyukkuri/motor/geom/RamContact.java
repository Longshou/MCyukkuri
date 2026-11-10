package io.github.longshou.mcyukkuri.motor.geom;

import com.google.gson.*;
import io.github.longshou.mcyukkuri.body.ModelFingerprint;
import io.github.longshou.mcyukkuri.pose.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Contact envelope of the actual skinned parts, using the pose checker's splice/compose/skin operations. */
public record RamContact(double impact, double turn, double[] front, double[] peakTimes, double[] peaks,
                         float launch, float strike, float settled) {
    public static final String CLIP = "lunge_ram";
    // Geometry sampling only: one hundred samples/second, including every authored impact marker.
    private static final int SAMPLES = 100;
    // Ternary narrowing of each local maximum's two-sample bracket: (2/3)^24 of 20 ms is under a microsecond.
    private static final int PEAK_STEPS = 24;
    private static final Map<String,RamContact> CACHE = new ConcurrentHashMap<>();
    public static RamContact of(String model) { return CACHE.computeIfAbsent(model,RamContact::read); }

    /** Keep the admission stand. Launch closes its measured gap to the swept front, then recoil gives that travel back. */
    public double distance(double stand, double seconds) {
        if (seconds < launch) return stand;
        int i = Math.min(front.length-1,(int)Math.ceil(seconds*SAMPLES));
        // Every position clears the rest of the stroke's sweep: the brim peaks before impact, and the drawn body trails
        // this position (network interpolation) while the drawn clip clock does not, so a later pose meets an earlier position.
        double ahead = 0;
        for (int sample=Math.max(0,i);sample<front.length;sample++) ahead=Math.max(ahead,front[sample]);
        for (int p=0;p<peaks.length;p++) if (peakTimes[p]>=seconds) ahead=Math.max(ahead,peaks[p]);
        if (seconds <= strike) return Math.max(impact,ahead);
        double recoil = Math.clamp((seconds-strike)/(settled-strike),0,1);
        return Math.max(ahead,impact+(stand-impact)*recoil);
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
        var front=new double[(int)Math.ceil(settled*SAMPLES)+1]; double impact=0; double[] turn={0};
        var point=new Vector3f();var scratch=new Vector3f();var inverse=new Matrix4f();
        // Frontmost reach of every visible part at one clip time; each evaluated pose also widens the turning envelope.
        BiFunction<Clips.Clip,Float,Double> reach=(clip,time)->{
            for(int i=0;i<pose.length;i++)System.arraycopy(defaults[i],0,pose[i],0,9);
            clip.sample(time,pose);compose.world(pose,world,-1,1,null,null);
            transforms.forEach((b,m)->Compose.feature(bones,world,b,m,inverse));
            double f=0;
            for(int i:visible){Skin.base(i,point,scratch,vertices,bindings,world,null,0,null,features,transforms);f=Math.max(f,-point.z);turn[0]=Math.max(turn[0],Math.hypot(point.x,point.z));}
            return f;
        };
        for(String name:List.of(CLIP,"idle","droop","startle")) {
            var clip=Clips.read(clips.getAsJsonObject(name),bones.length);
            int last=(int)Math.ceil(clip.duration*SAMPLES);
            for(int sample=0;sample<=last;sample++) {
                double f=reach.apply(clip,Math.min(sample/(float)SAMPLES,Math.nextDown(clip.duration)));
                if(name.equals(CLIP)){front[Math.min(sample,front.length-1)]=f;if(sample==Math.round(strike*SAMPLES))impact=f;}
            }
        }
        // A forward swing can peak between two samples (the brim at 0.47 s does): locate each local maximum's true top.
        var ram=Clips.read(clips.getAsJsonObject(CLIP),bones.length);
        var times=new ArrayList<Double>(); var peaks=new ArrayList<Double>();
        for(int i=1;i+1<front.length;i++) if(front[i]>=front[i-1]&&front[i]>=front[i+1]) {
            double lo=(i-1.0)/SAMPLES,hi=(i+1.0)/SAMPLES;
            for(int step=0;step<PEAK_STEPS;step++){
                double a=lo+(hi-lo)/3,b=hi-(hi-lo)/3;
                if(reach.apply(ram,(float)a)<reach.apply(ram,(float)b))lo=a;else hi=b;
            }
            times.add((lo+hi)/2); peaks.add(Math.max(front[i],reach.apply(ram,(float)((lo+hi)/2))));
        }
        return new RamContact(impact,turn[0],front,times.stream().mapToDouble(Double::doubleValue).toArray(),
                peaks.stream().mapToDouble(Double::doubleValue).toArray(),launch,strike,settled);
    }
}
