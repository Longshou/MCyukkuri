package io.github.longshou.mcyukkuri.body;

import com.google.gson.JsonParser;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriView;
import io.github.longshou.mcyukkuri.family.LiveBirthGeometry;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/** Same exported mouth coordinates used by held food; common code never loads a client class. */
public final class MouthPlacement {
    private static final Map<String,Vector3f> ANCHORS=new HashMap<>();
    static {
        for(var resource:new String[]{"living.json","subspecies.json"})
        try(var input=MouthPlacement.class.getResourceAsStream("/assets/mcyukkuri/yukkuri_presentation/"+resource);var reader=new InputStreamReader(input,StandardCharsets.UTF_8)){
            var root=JsonParser.parseReader(reader).getAsJsonObject();
            var variants=root.has("variants")?root.getAsJsonObject("variants"):root;
            for(var entry:variants.entrySet()){
                var a=entry.getValue().getAsJsonObject().getAsJsonArray("mouth");ANCHORS.put(entry.getKey(),new Vector3f(a.get(0).getAsFloat(),a.get(1).getAsFloat(),a.get(2).getAsFloat()));
            }
        }catch(IOException error){throw new UncheckedIOException(error);}
    }
    /** The unscaled mouth anchor in model units for this form (a copy). */
    public static Vector3f anchor(YukkuriView view){
        return new Vector3f(ANCHORS.getOrDefault(view.assetId(),ANCHORS.get(view.getVariant().assetId())));
    }
    public static Vec3 position(YukkuriEntity entity){
        var point=new Vector3f(ANCHORS.getOrDefault(entity.subspecies().assetId(),ANCHORS.get(entity.getVariant().assetId())));
        LiveBirthGeometry.deform(point,entity.livePregnancyProgress(),0);
        return entity.position().add(new Vec3(WoundPlacement.transform(entity).transformPosition(point)));
    }
    public static Vec3 outward(YukkuriEntity entity){return new Vec3(WoundPlacement.transform(entity).transformDirection(new Vector3f(0,0,-1)).normalize());}
    private MouthPlacement(){}
}
