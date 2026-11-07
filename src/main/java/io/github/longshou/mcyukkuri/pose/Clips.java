package io.github.longshou.mcyukkuri.pose;

import com.google.gson.*;
import java.util.*;

/** Authored track sampling; selection of actions remains with the caller. */
public final class Clips {
    private Clips() {}
    public static final class Track {
        public final int bone,offset;
        public final float[][] keys;
        public Track(int bone,int offset,float[][] keys){this.bone=bone;this.offset=offset;this.keys=keys;}
        public void sample(float time, float[] target) {
            int low = 0, high = keys.length - 1;
            while (high - low > 1) {
                int middle = (low + high) >>> 1;
                if (keys[middle][0] <= time) low = middle;
                else high = middle;
            }
            float[] a = keys[low], b = keys[Math.min(low + 1, keys.length - 1)];
            float q = b[0] == a[0] ? 0 : Math.clamp((time - a[0]) / (b[0] - a[0]), 0f, 1f);
            for (int i = 0; i < a.length - 1; i++) target[offset + i] = a[i + 1] + (b[i + 1] - a[i + 1]) * q;
        }
    }
    public static final class Clip {
        public final float duration;
        public final boolean loop;
        public final List<Track> tracks;
        public Clip(float duration,boolean loop,List<Track> tracks){this.duration=duration;this.loop=loop;this.tracks=tracks;}
        public float time(float elapsed) { return loop ? Math.max(0, elapsed) % duration : Math.clamp(elapsed, 0f, duration); }
        public void sample(float elapsed, float[][] pose) {
            float time = time(elapsed);
            for (Track track : tracks) track.sample(time, pose[track.bone]);
        }
    }

    public static Clip read(JsonObject clip,int boneCount){
            List<Track> tracks = new ArrayList<>();
            for (var value : clip.getAsJsonArray("tracks")) {
                JsonObject track = value.getAsJsonObject();
                int offset = switch (track.get("channel").getAsString()) {
                    case "position" -> 0;
                    case "rotation" -> 3;
                    case "scale" -> 6;
                    default -> throw new IllegalArgumentException("Unknown animation channel");
                };
                JsonArray keys = track.getAsJsonArray("keys");
                float[][] samples = new float[keys.size()][];
                for (int i = 0; i < samples.length; i++) samples[i] = Rig.floats(keys.get(i).getAsJsonArray());
                int bone = track.get("bone").getAsInt();
                if (bone < 0 || bone >= boneCount || samples.length == 0)
                    throw new IllegalArgumentException("Invalid animation track");
                tracks.add(new Track(bone, offset, samples));
            }
            float duration = clip.get("duration").getAsFloat();
            if (!(duration > 0)) throw new IllegalArgumentException("Invalid clip duration");
            return new Clip(duration, clip.get("loop").getAsBoolean(), tracks);
    }
    public static float smooth(float x) { x=Math.clamp(x,0,1); return x*x*(3-2*x); }

    /** Inflate, hold (with an optional tremor that only shrinks the cheeks) and release. */
    public static float puffEnvelope(float[] e,float time){
        float u=(time-e[0])/(e[1]-e[0]),base=smooth(u/e[2])*smooth((1-u)/e[2]);
        if(base<=0||e.length<5)return base;
        float hold=smooth((u-e[2])/e[2])*smooth((1-e[2]-u)/e[2]);
        return base*(1-e[3]*hold*(float)(.5-.5*Math.cos(2*Math.PI*e[4]*(time-e[0]))));
    }

    /** Compact common-side wound tracks retain their existing storage. */
    public static void sample(float[] keys,int offset,float time,float[] target){
        int lo=0,hi=keys.length/4-1;
        while(hi-lo>1){int mid=(lo+hi)>>>1;if(keys[mid*4]<=time)lo=mid;else hi=mid;}
        int a=lo*4,b=Math.min(lo+1,keys.length/4-1)*4;
        float q=keys[a]==keys[b]?0:Math.clamp((time-keys[a])/(keys[b]-keys[a]),0,1);
        for(int k=0;k<3;k++)target[offset+k]=keys[a+k+1]+(keys[b+k+1]-keys[a+k+1])*q;
    }
}
