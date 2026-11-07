package io.github.longshou.mcyukkuri.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.entity.YukkuriView;
import java.io.IOException;
import java.io.UncheckedIOException;
import net.minecraft.server.packs.resources.ResourceManager;
import org.slf4j.LoggerFactory;

/** Client-only A/B phase clock; the synchronized expression remains the authored face id. */
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
        var faces = data.getAsJsonObject("models").getAsJsonObject(entity.assetId()).getAsJsonArray("selectable");
        boolean known = false;
        for (var face : faces) if (face.getAsString().equals(expression)) { known = true; break; }
        if (!known) {
            LoggerFactory.getLogger(FaceLibrary.class).error("Unknown face id {} on {}", expression, entity.assetId());
            throw new IllegalArgumentException("Unknown face id: " + expression);
        }
        var animation = data.getAsJsonObject("animations").getAsJsonObject(expression);
        if (animation != null) {
            long phase = entity.level().getGameTime() / animation.get("frame_ticks").getAsInt();
            expression = animation.getAsJsonArray("frames").get((int)(phase % 2)).getAsString();
        }
        return expression;
    }

    private FaceLibrary() {}
}
