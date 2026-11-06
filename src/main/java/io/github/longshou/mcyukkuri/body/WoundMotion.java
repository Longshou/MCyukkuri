package io.github.longshou.mcyukkuri.body;

import com.google.gson.JsonArray;
import com.google.gson.stream.JsonReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Existing selected motion corrections, with the same skeleton contract as the visible model. */
final class WoundMotion {
    record Track(int bone,int offset,float[] keys) {}
    record Clip(float duration,boolean loop,Track[] tracks) {}
    private record Revision(List<String> names,Map<String,Clip> clips) {}
    private static final Map<String,Revision> REVISIONS=read();
    private WoundMotion() {}
    private static Map<String,Revision> read() {
        var input=WoundMotion.class.getResourceAsStream("/assets/mcyukkuri/yukkuri_presentation/motion_corrections.json");
        if(input==null)throw new IllegalStateException("Missing selected body motions");
        try(var reader=new InputStreamReader(input,StandardCharsets.UTF_8)){
            var in=new JsonReader(reader);var result=new HashMap<String,Revision>();int format=0;
            in.beginObject();
            while(in.hasNext()){
                switch(in.nextName()){
                    case "format"->format=in.nextInt();
                    case "models"->{
                        in.beginObject();
                        while(in.hasNext()){
                            String model=in.nextName();var names=new ArrayList<String>();Map<String,Clip> clips=Map.of();
                            in.beginObject();
                            while(in.hasNext())switch(in.nextName()){
                                case "bone_names"->{in.beginArray();while(in.hasNext())names.add(in.nextString());in.endArray();}
                                case "clips"->clips=readClips(in);
                                default->in.skipValue();
                            }
                            in.endObject();result.put(model,new Revision(names,clips));
                        }
                        in.endObject();
                    }
                    default->in.skipValue();
                }
            }
            if(format!=2)throw new IllegalStateException("Unsupported body motion corrections");
            return Map.copyOf(result);
        }catch(IOException ex){throw new UncheckedIOException(ex);}
    }
    static void apply(String id,JsonArray bones,Map<String,Clip> clips) {
        var revision=REVISIONS.get(id);if(revision==null)return;
        if(revision.names.size()!=bones.size())throw new IllegalStateException("Wound motion skeleton size differs for "+id);
        for(int i=0;i<bones.size();i++)if(!revision.names.get(i).equals(bones.get(i).getAsJsonObject().get("name").getAsString()))
            throw new IllegalStateException("Wound motion bone differs for "+id+" at "+i);
        clips.putAll(revision.clips);
    }
    static Map<String,Clip> readClips(JsonReader in)throws IOException{
        var out=new HashMap<String,Clip>();in.beginObject();
        while(in.hasNext()){String name=in.nextName();out.put(name,readClip(in));}
        in.endObject();return out;
    }
    private static Clip readClip(JsonReader in)throws IOException{
        float duration=0;boolean loop=false;var tracks=new ArrayList<Track>();in.beginObject();
        while(in.hasNext())switch(in.nextName()){
            case "duration"->duration=Float.parseFloat(in.nextString());
            case "loop"->loop=in.nextBoolean();
            case "tracks"->{in.beginArray();while(in.hasNext())tracks.add(readTrack(in));in.endArray();}
            default->in.skipValue();
        }
        in.endObject();return new Clip(duration,loop,tracks.toArray(new Track[0]));
    }
    private static Track readTrack(JsonReader in)throws IOException{
        int bone=0,offset=0;float[] keys=new float[0];in.beginObject();
        while(in.hasNext())switch(in.nextName()){
            case "bone"->bone=in.nextInt();
            case "channel"->offset=switch(in.nextString()){case "position"->0;case "rotation"->3;default->6;};
            case "keys"->{
                float[] out=new float[16*4];int n=0;in.beginArray();
                while(in.hasNext()){
                    if(n+4>out.length)out=Arrays.copyOf(out,out.length*2);
                    in.beginArray();for(int k=0;in.hasNext();k++){float v=Float.parseFloat(in.nextString());if(k<4)out[n+k]=v;}in.endArray();n+=4;
                }
                in.endArray();keys=Arrays.copyOf(out,n);
            }
            default->in.skipValue();
        }
        in.endObject();return new Track(bone,offset,keys);
    }
}
