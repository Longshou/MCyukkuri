package io.github.longshou.mcyukkuri.entity.presentation;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** The server samples the face beats from the same recipe as the rendered body cues. */
public final class AdultFaces {
    private static final JsonObject FACES = read();

    private AdultFaces() {}

    public static String face(String cue, float progress) {
        var table = FACES.getAsJsonArray(cue);
        int index = 0;
        while (index + 1 < table.size() && table.get(index + 1).getAsJsonArray().get(0).getAsFloat() <= progress)
            index++;
        return table.get(index).getAsJsonArray().get(1).getAsString();
    }

    private static JsonObject read() {
        var stream = AdultFaces.class.getResourceAsStream("/assets/mcyukkuri/yukkuri_presentation/adult_states.json");
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("faces");
        } catch (IOException error) { throw new UncheckedIOException(error); }
    }
}
