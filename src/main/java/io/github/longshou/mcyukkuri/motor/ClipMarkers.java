package io.github.longshou.mcyukkuri.motor;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import io.github.longshou.mcyukkuri.MCYukkuri;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Authored clip timing, generalised from {@code subspecies/crew/CrewClips}: a clip's own markers are the server clock, so
 * an action and the drawn clip agree without the client deciding anything (spec 8.4 / 9.4).
 *
 * <p>The exported clip data ({@code /assets/mcyukkuri/yukkuri_presentation/motion_corrections.json}) is streamed once;
 * only durations and markers are kept, and the result is cached per clip name. Every model shares the same clip data
 * except {@code waddle}, so the first model that authors a clip name defines it.
 */
public final class ClipMarkers {
    /** One clip: its authored length in seconds and its named markers in seconds. */
    public record Clip(float seconds, Map<String, Float> markers) {
        /** A marker in game ticks, or {@code fallback} when the clip does not author it. */
        public int ticks(String marker, int fallback) {
            Float at = markers.get(marker);
            return at == null ? fallback : Math.max(0, Math.round(at * 20f));
        }

        public int length() { return Math.max(1, Math.round(seconds * 20f)); }
    }

    private static final String DATA = "/assets/mcyukkuri/yukkuri_presentation/motion_corrections.json";
    /** A clip the file does not author: one second and no markers, so every {@link Clip#ticks} call uses its fallback. */
    private static final Clip MISSING = new Clip(1f, Map.of());
    private static volatile Map<String, Clip> clips;

    /** The clip authored under {@code clip}, or the {@link #MISSING} default when the data has no such clip. */
    public static Clip of(String clip) {
        var loaded = clips;
        if (loaded == null) synchronized (ClipMarkers.class) {
            if ((loaded = clips) == null) clips = loaded = read();
        }
        return loaded.getOrDefault(clip == null ? "" : clip, MISSING);
    }

    private static Map<String, Clip> read() {
        var out = new HashMap<String, Clip>();
        try (var stream = ClipMarkers.class.getResourceAsStream(DATA)) {
            if (stream == null) throw new IOException("missing " + DATA);
            var reader = new JsonReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            reader.beginObject();
            while (reader.hasNext()) {
                if (!reader.nextName().equals("models")) { reader.skipValue(); continue; }
                reader.beginObject();
                while (reader.hasNext()) {
                    reader.nextName();
                    reader.beginObject();
                    while (reader.hasNext()) {
                        if (!reader.nextName().equals("clips")) { reader.skipValue(); continue; }
                        reader.beginObject();
                        while (reader.hasNext()) out.putIfAbsent(reader.nextName(), clip(reader));
                        reader.endObject();
                    }
                    reader.endObject();
                }
                reader.endObject();
            }
            reader.endObject();
        } catch (IOException | RuntimeException failure) {
            MCYukkuri.LOGGER.warn("[body] clip markers unreadable, using built-in timing: {}", failure.toString());
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

    private ClipMarkers() {}
}
