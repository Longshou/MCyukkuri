package io.github.longshou.mcyukkuri.headwear;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Seat of a small (not yet regrown) accessory: it is drawn scaled about the lowest vertices of the accessory.
 *  A model may register seat parts and a smallest drawn size in yukkuri_presentation/hat_seat.json; then only those
 *  parts' lowest vertices count, so a kotatsu whose futon reaches the ground still shrinks about the tabletop resting
 *  on the head, and is never drawn so small that the head pokes through it. Only the drawing changes, not Headwear.fit. */
public final class HatSeat {
    private record Seat(Set<String> parts,float minFit) {}
    private static final Seat NONE=new Seat(Set.of(),0);
    private static volatile Map<String,Seat> models;
    private HatSeat() {}

    private static Seat seat(String model) {
        Map<String,Seat> loaded=models;
        if(loaded==null)loaded=load();
        return loaded.getOrDefault(model,NONE);
    }

    /** Registered seat part names of a model; empty means the whole accessory. */
    public static Set<String> parts(String model) { return seat(model).parts(); }

    /** Drawn accessory size of a model for the visual fit (Headwear.visualFit), raised to its registered minimum. */
    public static float fit(String model,float fit) { return Math.max(fit,seat(model).minFit()); }

    /** Rest vertex indices in the lowest 15% (by height) of the seat ranges, or of the accessory ranges when no seat
     *  part is registered. Ranges are (start,count) pairs into an 8-float-per-vertex array. */
    public static int[] lowest(float[] vertices,int[] accessoryRanges,int[] seatRanges) {
        int[] ranges=seatRanges.length>0?seatRanges:accessoryRanges;
        float[] bounds=HatDamageGeometry.bounds(vertices,ranges);
        float low=bounds[1]+(bounds[4]-bounds[1])*.15f;
        List<Integer> seat=new ArrayList<>();
        for(int r=0;r<ranges.length;r+=2)for(int i=ranges[r];i<ranges[r]+ranges[r+1];i++)if(vertices[i*8+1]<=low)seat.add(i);
        return seat.stream().mapToInt(Integer::intValue).toArray();
    }

    private static synchronized Map<String,Seat> load() {
        if(models!=null)return models;
        Map<String,Seat> result=new HashMap<>();
        var input=HatSeat.class.getResourceAsStream("/assets/mcyukkuri/yukkuri_presentation/hat_seat.json");
        if(input!=null){
            try(var reader=new InputStreamReader(input,StandardCharsets.UTF_8)){
                var entries=JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("models");
                for(var entry:entries.entrySet()){
                    var value=entry.getValue().getAsJsonObject();
                    Set<String> names=new HashSet<>();
                    for(var name:value.getAsJsonArray("parts"))names.add(name.getAsString());
                    float minFit=value.has("min_fit")?Math.min(1f,value.get("min_fit").getAsFloat()):0f;
                    result.put(entry.getKey(),new Seat(Set.copyOf(names),minFit));
                }
            }catch(IOException ex){throw new UncheckedIOException(ex);}
        }
        models=Map.copyOf(result);
        return models;
    }
}
