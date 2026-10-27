package io.github.longshou.mcyukkuri.entity.presentation;

import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import io.github.longshou.mcyukkuri.MCYukkuri;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Server-side sampling of authored face spans; clients only display the synchronized winner. */
final class ExpressionClips {
    private record Span(float start,float end,String face){}
    private record Clip(float duration,boolean loop,List<Span> spans,Set<String> yields){}
    private static final String ROOT="/assets/mcyukkuri/";
    private static final Map<String,Map<String,Clip>> MODELS=new ConcurrentHashMap<>();
    private static Map<String,Map<String,Clip>> revisions;
    static String sample(String asset,String action,float elapsed,String fallback){
        var clip=MODELS.computeIfAbsent(asset,ExpressionClips::load).get(action);
        if(clip==null||clip.duration<=0||clip.yields.contains(fallback))return fallback;
        float time=clip.loop?elapsed%clip.duration:Math.min(elapsed,clip.duration);
        for(var span:clip.spans)if(time>=span.start&&time<span.end)return span.face;
        return fallback;
    }
    private static Map<String,Clip> load(String asset){
        var out=new HashMap<String,Clip>();
        try(var stream=ExpressionClips.class.getResourceAsStream(ROOT+"yukkuri/"+asset+".json")){
            if(stream!=null)readModel(new JsonReader(new InputStreamReader(stream,StandardCharsets.UTF_8)),out);
        }catch(IOException|RuntimeException error){MCYukkuri.LOGGER.warn("[presentation] face spans unreadable asset={} reason={}",asset,error.toString());}
        out.putAll(revisions().getOrDefault(asset,Map.of()));return Map.copyOf(out);
    }
    private static synchronized Map<String,Map<String,Clip>> revisions(){
        if(revisions!=null)return revisions;
        var out=new HashMap<String,Map<String,Clip>>();
        try(var stream=ExpressionClips.class.getResourceAsStream(ROOT+"yukkuri_presentation/motion_corrections.json")){
            if(stream!=null){
                var reader=new JsonReader(new InputStreamReader(stream,StandardCharsets.UTF_8));reader.beginObject();
                while(reader.hasNext()){
                    if(!reader.nextName().equals("models")){reader.skipValue();continue;}
                    reader.beginObject();
                    while(reader.hasNext()){String asset=reader.nextName();var clips=new HashMap<String,Clip>();readModel(reader,clips);out.put(asset,Map.copyOf(clips));}
                    reader.endObject();
                }
                reader.endObject();
            }
        }catch(IOException|RuntimeException error){MCYukkuri.LOGGER.warn("[presentation] revised face spans unreadable reason={}",error.toString());}
        return revisions=Map.copyOf(out);
    }
    private static void readModel(JsonReader reader,Map<String,Clip> out)throws IOException{
        reader.beginObject();
        while(reader.hasNext()){
            String key=reader.nextName();
            if(!key.equals("clips")&&!key.equals("behavior_clips")){reader.skipValue();continue;}
            reader.beginObject();while(reader.hasNext()){String name=reader.nextName();out.put(name,readClip(reader));}reader.endObject();
        }
        reader.endObject();
    }
    private static Clip readClip(JsonReader reader)throws IOException{
        float duration=0;boolean loop=false;var spans=new ArrayList<Span>();var yields=new HashSet<String>();
        reader.beginObject();
        while(reader.hasNext())switch(reader.nextName()){
            case "duration"->duration=(float)reader.nextDouble();
            case "loop"->loop=reader.nextBoolean();
            case "expression_spans"->{for(var value:JsonParser.parseReader(reader).getAsJsonArray()){
                var span=value.getAsJsonArray();spans.add(new Span(span.get(0).getAsFloat(),span.get(1).getAsFloat(),span.get(2).getAsString()));
            }}
            case "expression_yield"->{for(var value:JsonParser.parseReader(reader).getAsJsonArray())yields.add(value.getAsString());}
            default->reader.skipValue();
        }
        reader.endObject();return new Clip(duration,loop,List.copyOf(spans),Set.copyOf(yields));
    }
    private ExpressionClips(){}
}
