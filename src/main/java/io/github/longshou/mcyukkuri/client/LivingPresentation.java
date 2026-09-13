package io.github.longshou.mcyukkuri.client;

import com.google.gson.JsonParser;
import io.github.longshou.mcyukkuri.MCYukkuri;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.server.packs.resources.ResourceManager;

/** Parameters exported from the care-facility recipe; no character source geometry is changed. */
final class LivingPresentation {
    record Variant(List<String> accessoryPrefixes, float x, float y, float z, float foodScale) {}
    static Map<String, Variant> variants = Map.of();
    static float chewPeriod = 8, mouthMinimum = .82f, mouthMaximum = 1.1f;
    static String mouthBone = "face_mouth";
    static void reload(ResourceManager resources) {
        try (var reader = resources.getResourceOrThrow(MCYukkuri.id("yukkuri_presentation/living.json")).openAsReader()) {
            var data = JsonParser.parseReader(reader).getAsJsonObject();
            if (data.get("format").getAsInt() != 1) throw new IllegalArgumentException("Unsupported living presentation format");
            Map<String, Variant> loaded = new HashMap<>();
            for (var entry : data.getAsJsonObject("variants").entrySet()) {
                var value = entry.getValue().getAsJsonObject(); var mouth = value.getAsJsonArray("mouth");
                loaded.put(entry.getKey(), new Variant(value.getAsJsonArray("accessory_prefixes").asList().stream().map(e -> e.getAsString()).toList(),
                        mouth.get(0).getAsFloat()/16, mouth.get(1).getAsFloat()/16, mouth.get(2).getAsFloat()/16, value.get("food_scale").getAsFloat()));
            }
            try (var extra = resources.getResourceOrThrow(MCYukkuri.id("yukkuri_presentation/subspecies.json")).openAsReader()) {
                for (var entry : JsonParser.parseReader(extra).getAsJsonObject().entrySet()) {
                    var value=entry.getValue().getAsJsonObject();var mouth=value.getAsJsonArray("mouth");
                    loaded.put(entry.getKey(),new Variant(value.getAsJsonArray("accessory_prefixes").asList().stream().map(e->e.getAsString()).toList(),
                            mouth.get(0).getAsFloat()/16,mouth.get(1).getAsFloat()/16,mouth.get(2).getAsFloat()/16,value.get("food_scale").getAsFloat()));
                }
            }
            variants = Map.copyOf(loaded); chewPeriod = Math.max(1,data.get("chew_period_ticks").getAsFloat());
            mouthMinimum=data.get("mouth_scale_min").getAsFloat(); mouthMaximum=data.get("mouth_scale_max").getAsFloat();
            mouthBone=data.get("mouth_bone").getAsString();
        } catch (IOException error) { throw new UncheckedIOException(error); }
    }
    private LivingPresentation() {}
}
