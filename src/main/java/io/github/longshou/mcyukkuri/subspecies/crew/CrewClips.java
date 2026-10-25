package io.github.longshou.mcyukkuri.subspecies.crew;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import io.github.longshou.mcyukkuri.MCYukkuri;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * The authored timing of the four transfer clips, read from the exported clip data ({@code motion_corrections.json}, model
 * {@code marisa}): a transfer's CONTACT, MOVE and SETTLE lengths are the clip's own markers, so the server clock and the
 * clip agree without the client choosing anything. The file is streamed once and only these clips are kept.
 */
public final class CrewClips {
    public record Clip(float seconds, Map<String, Float> markers) {
        /** A marker in game ticks, or {@code fallback} when the clip does not author it. */
        public int ticks(String marker, int fallback) {
            Float at = markers.get(marker);
            return at == null ? fallback : Math.max(0, Math.round(at * 20f));
        }
        public int length() { return Math.max(1, Math.round(seconds * 20f)); }
    }

    public static final String BRAID_CRADLE_LIFT = "braid_cradle_lift", BE_CRADLED = "be_cradled", MOUTH_BITE_HOLD = "mouth_bite_hold",
            WATER_HAT_CLIMB = "water_hat_climb";
    private static final Set<String> WANTED = Set.of(BRAID_CRADLE_LIFT, BE_CRADLED, MOUTH_BITE_HOLD, WATER_HAT_CLIMB);
    private static final String DATA = "/assets/mcyukkuri/yukkuri_presentation/motion_corrections.json";
    private static volatile Map<String, Clip> clips;

    public static Clip of(String name) {
        var loaded = clips;
        if (loaded == null) synchronized (CrewClips.class) {
            if ((loaded = clips) == null) clips = loaded = read();
        }
        return loaded.getOrDefault(name, new Clip(1f, Map.of()));
    }

    private static Map<String, Clip> read() {
        var out = new HashMap<String, Clip>();
        try (var stream = CrewClips.class.getResourceAsStream(DATA)) {
            if (stream == null) throw new IOException("missing " + DATA);
            var reader = new JsonReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            reader.beginObject();
            while (reader.hasNext()) {
                if (!reader.nextName().equals("models")) { reader.skipValue(); continue; }
                reader.beginObject();
                while (reader.hasNext()) {
                    if (!reader.nextName().equals("marisa")) { reader.skipValue(); continue; }
                    reader.beginObject();
                    while (reader.hasNext()) {
                        if (!reader.nextName().equals("clips")) { reader.skipValue(); continue; }
                        reader.beginObject();
                        while (reader.hasNext()) {
                            String name = reader.nextName();
                            if (WANTED.contains(name)) out.put(name, clip(reader)); else reader.skipValue();
                        }
                        reader.endObject();
                    }
                    reader.endObject();
                }
                reader.endObject();
            }
        } catch (IOException | RuntimeException failure) {
            MCYukkuri.LOGGER.warn("[aquatic] transfer clip markers unreadable, using built-in timing: {}", failure.toString());
        }
        return Map.copyOf(out);
    }

    private static Clip clip(JsonReader reader) throws IOException {
        float seconds = 1f;
        var markers = new HashMap<String, Float>();
        reader.beginObject();
        while (reader.hasNext()) {
            switch (reader.nextName()) {
                case "duration" -> seconds = (float) reader.nextDouble();
                case "markers" -> {
                    reader.beginObject();
                    while (reader.hasNext()) {
                        String key = reader.nextName();
                        if (reader.peek() == JsonToken.NUMBER) markers.put(key, (float) reader.nextDouble()); else reader.skipValue();
                    }
                    reader.endObject();
                }
                default -> reader.skipValue();
            }
        }
        reader.endObject();
        return new Clip(seconds, Map.copyOf(markers));
    }

    private CrewClips() {}
}
