package io.github.longshou.mcyukkuri.pose.cli;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.longshou.mcyukkuri.body.BodyConstants;
import io.github.longshou.mcyukkuri.dialogue.VoicePitch;
import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import io.github.longshou.mcyukkuri.pose.EmoteCue;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

/** Physical dimensions for an uninjured review actor at unit growth. */
final class ReviewActor {
    static JsonObject describe(Path assets, String id, float scale) throws IOException {
        var variant = YukkuriVariant.valueOf((id.startsWith("house_") ? id : id.split("_")[0]).toUpperCase(Locale.ROOT));
        var bodies = read(assets, "body_geometry").getAsJsonObject("variants");
        float width = variant.width(), height = variant.height();
        if (bodies.has(id)) {
            var body = bodies.getAsJsonObject(id);
            var x = body.getAsJsonArray("bounds").get(0).getAsJsonArray();
            width = (x.get(1).getAsFloat() - x.get(0).getAsFloat()) / 16;
            height = body.get("height").getAsFloat() / 16;
        } else if (id.endsWith("_bean")) {
            width *= .82f;
            height *= 1.55f;
        }
        var out = new JsonObject();
        out.addProperty("width", width);
        out.add("outlet", bodies.getAsJsonObject(bodies.has(id) ? id : "reimu").get("outlet"));
        var living = read(assets, "living").getAsJsonObject("variants");
        var form = (living.has(id) ? living : read(assets, "subspecies")).getAsJsonObject(id);
        out.add("mouth", form.get("mouth"));
        out.add("paperAccessories", form.get("accessory_prefixes"));
        float modelScale = variant.isHouse() ? BodyConstants.HOUSE_SOURCE_SCALE : 1;
        out.addProperty("modelScale", modelScale);
        long individual = UUID.nameUUIDFromBytes(id.getBytes(StandardCharsets.UTF_8)).getLeastSignificantBits();
        out.addProperty("voicePitch", VoicePitch.pitch(scale * modelScale, individual));
        out.addProperty("speaker", variant.dialogueSpecies());
        out.addProperty("emoteHeight", EmoteCue.height(variant.isHouse(), variant.assetId(), height, 1));
        out.addProperty("emoteSize", EmoteCue.size(1));
        return out;
    }

    static JsonObject read(Path assets, String name) throws IOException {
        try (var reader = Files.newBufferedReader(assets.resolve("yukkuri_presentation/" + name + ".json"))) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private ReviewActor() {}
}
