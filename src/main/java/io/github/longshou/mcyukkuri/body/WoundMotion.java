package io.github.longshou.mcyukkuri.body;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Existing selected motion corrections, with the same skeleton contract as the visible model. */
final class WoundMotion {
    private record Revision(JsonArray names,JsonObject clips) {}
    private static final Map<String,Revision> REVISIONS=read();
    private WoundMotion() {}
    private static Map<String,Revision> read() {
        var input=WoundMotion.class.getResourceAsStream("/assets/mcyukkuri/yukkuri_presentation/motion_corrections.json");
        if(input==null)throw new IllegalStateException("Missing selected body motions");
        try(var reader=new InputStreamReader(input,StandardCharsets.UTF_8)){
            var root=JsonParser.parseReader(reader).getAsJsonObject();
            if(root.get("format").getAsInt()!=2)throw new IllegalStateException("Unsupported body motion corrections");
            var result=new HashMap<String,Revision>();
            for(var entry:root.getAsJsonObject("models").entrySet()){
                var revision=entry.getValue().getAsJsonObject();
                result.put(entry.getKey(),new Revision(revision.getAsJsonArray("bone_names"),revision.getAsJsonObject("clips")));
            }
            return Map.copyOf(result);
        }catch(IOException ex){throw new UncheckedIOException(ex);}
    }
    static void apply(String id,JsonArray bones,JsonObject clips) {
        var revision=REVISIONS.get(id);if(revision==null)return;
        if(revision.names.size()!=bones.size())throw new IllegalStateException("Wound motion skeleton size differs for "+id);
        for(int i=0;i<bones.size();i++)if(!revision.names.get(i).getAsString().equals(bones.get(i).getAsJsonObject().get("name").getAsString()))
            throw new IllegalStateException("Wound motion bone differs for "+id+" at "+i);
        revision.clips.entrySet().forEach(entry->clips.add(entry.getKey(),entry.getValue()));
    }
}
