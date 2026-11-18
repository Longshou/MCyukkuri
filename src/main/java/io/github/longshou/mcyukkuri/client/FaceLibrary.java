package io.github.longshou.mcyukkuri.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.entity.YukkuriView;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.server.packs.resources.ResourceManager;
import org.slf4j.LoggerFactory;

/** Client-only frame clock with per-frame durations; the synchronized expression remains the authored face id. */
final class FaceLibrary {
    private static JsonObject data;
    private static Map<String, Set<String>> selectable;
    private static Map<String, Integer> cycles;
    private static Map<String, String> labels;

    static JsonObject reload(ResourceManager manager) {
        try (var reader = manager.getResourceOrThrow(MCYukkuri.id("yukkuri_presentation/face_library.json")).openAsReader()) {
            var library = JsonParser.parseReader(reader).getAsJsonObject();
            var models = library.getAsJsonObject("models");
            var faces = new HashMap<String, Set<String>>();
            for (var model : models.entrySet()) {
                var ids = new HashSet<String>();
                for (var face : model.getValue().getAsJsonObject().getAsJsonArray("selectable")) ids.add(face.getAsString());
                faces.put(model.getKey(), ids);
            }
            var durations = new HashMap<String, Integer>();
            for (var animation : library.getAsJsonObject("animations").entrySet()) {
                int cycle = 0;
                for (var frame : animation.getValue().getAsJsonObject().getAsJsonArray("frame_ticks")) cycle += frame.getAsInt();
                durations.put(animation.getKey(), cycle);
            }
            var names = new HashMap<String, String>();
            for (var label : library.getAsJsonObject("labels").entrySet()) {
                var pair = label.getValue().getAsJsonArray();
                names.put(label.getKey(), pair.get(0).getAsString() + " · " + pair.get(1).getAsString());
            }
            selectable = faces;
            cycles = durations;
            labels = names;
            data = library;
            return models;
        } catch (IOException error) { throw new UncheckedIOException("Cannot load face library", error); }
    }

    /** Faces one form can wear, for the menu's picker. */
    static Set<String> selectable(String asset) { return selectable.get(asset); }

    /** `组 · 名` for a face with a preset; a legacy face keeps its raw id. */
    static String label(String face) { return labels.getOrDefault(face, face); }

    static String expression(YukkuriView entity, String expression) {
        var aliases = data.getAsJsonObject("aliases");
        if (aliases.has(expression)) expression = aliases.get(expression).getAsString();
        var model = data.getAsJsonObject("models").getAsJsonObject(entity.assetId());
        var localAliases = model.getAsJsonObject("face_aliases");
        if (localAliases.has(expression)) expression = localAliases.get(expression).getAsString();
        if (!selectable.get(entity.assetId()).contains(expression)) {
            LoggerFactory.getLogger(FaceLibrary.class).error("Unknown face id {} on {}", expression, entity.assetId());
            throw new IllegalArgumentException("Unknown face id: " + expression);
        }
        var animation = data.getAsJsonObject("animations").getAsJsonObject(expression);
        if (animation != null) {
            // One duration per frame (e.g. a long welling hold, then a short running drop).
            var ticks = animation.getAsJsonArray("frame_ticks");
            long tick = entity.level().getGameTime() % cycles.get(expression);
            int frame = 0;
            while (tick >= ticks.get(frame).getAsInt()) tick -= ticks.get(frame++).getAsInt();
            expression = animation.getAsJsonArray("frames").get(frame).getAsString();
        }
        return expression;
    }

    private FaceLibrary() {}
}
