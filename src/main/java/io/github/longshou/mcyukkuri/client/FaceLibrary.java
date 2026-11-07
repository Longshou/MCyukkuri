package io.github.longshou.mcyukkuri.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.entity.YukkuriView;
import java.io.IOException;
import java.io.UncheckedIOException;
import net.minecraft.server.packs.resources.ResourceManager;
import org.slf4j.LoggerFactory;

/** Client-only frame clock with per-frame durations; the synchronized expression remains the authored face id. */
final class FaceLibrary {
    private static JsonObject data;

    static JsonObject reload(ResourceManager manager) {
        try (var reader = manager.getResourceOrThrow(MCYukkuri.id("yukkuri_presentation/face_library.json")).openAsReader()) {
            data = JsonParser.parseReader(reader).getAsJsonObject();
            return data.getAsJsonObject("models");
        } catch (IOException error) { throw new UncheckedIOException("Cannot load face library", error); }
    }

    static String expression(YukkuriView entity, String expression) {
        var aliases = data.getAsJsonObject("aliases");
        if (aliases.has(expression)) expression = aliases.get(expression).getAsString();
        var model = data.getAsJsonObject("models").getAsJsonObject(entity.assetId());
        var localAliases = model.getAsJsonObject("face_aliases");
        if (localAliases.has(expression)) expression = localAliases.get(expression).getAsString();
        var faces = model.getAsJsonArray("selectable");
        boolean known = false;
        for (var face : faces) if (face.getAsString().equals(expression)) { known = true; break; }
        if (!known) {
            LoggerFactory.getLogger(FaceLibrary.class).error("Unknown face id {} on {}", expression, entity.assetId());
            throw new IllegalArgumentException("Unknown face id: " + expression);
        }
        var animation = data.getAsJsonObject("animations").getAsJsonObject(expression);
        if (animation != null) {
            // One duration per frame (e.g. a long welling hold, then a short running drop).
            var ticks = animation.getAsJsonArray("frame_ticks");
            int cycle = 0;
            for (var frame : ticks) cycle += frame.getAsInt();
            long tick = entity.level().getGameTime() % cycle;
            int frame = 0;
            while (tick >= ticks.get(frame).getAsInt()) tick -= ticks.get(frame++).getAsInt();
            expression = animation.getAsJsonArray("frames").get(frame).getAsString();
        }
        return expression;
    }

    private FaceLibrary() {}
}
