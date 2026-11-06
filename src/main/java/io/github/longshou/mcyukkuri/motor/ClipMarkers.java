package io.github.longshou.mcyukkuri.motor;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.motor.geom.Vec;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Authored clip timing: a clip's own markers are the server clock, so an action and the drawn clip agree. Durations and markers are read once and kept per clip name. */
public final class ClipMarkers {
    /** One clip: its authored length in seconds, its named markers in seconds and, for a braid-key clip, its tuft end per sample
     *  ({@code tip}: t, x, y, z rows flattened, model units). */
    public record Clip(float seconds, Map<String, Float> markers, Map<String, Float> parameters, float[] tip) {
        /** A marker in game ticks, or {@code fallback} when the clip does not author it. */
        public int ticks(String marker, int fallback) {
            Float at = markers.get(marker);
            return at == null ? fallback : Math.max(0, Math.round(at * 20f));
        }

        public int length() { return Math.max(1, Math.round(seconds * 20f)); }

        /** The tuft end at {@code at} seconds (model units), linear between the exported samples and held past either end. */
        public Vec tip(float at) {
            int last = tip.length / 4 - 1, i = 0;
            while (i < last && tip[4 * (i + 1)] <= at) i++;
            float q = i == last ? 0 : Math.clamp((at - tip[4 * i]) / (tip[4 * i + 4] - tip[4 * i]), 0, 1);
            int a = 4 * i, b = 4 * Math.min(i + 1, last);
            return new Vec(tip[a + 1] + (tip[b + 1] - tip[a + 1]) * q, tip[a + 2] + (tip[b + 2] - tip[a + 2]) * q, tip[a + 3] + (tip[b + 3] - tip[a + 3]) * q);
        }
    }

    private static final String DATA = "/assets/mcyukkuri/yukkuri_presentation/motion_corrections.json";
    /** A clip the file does not author: one second and no markers, so every {@link Clip#ticks} call uses its fallback. */
    private static final Clip MISSING = new Clip(1f, Map.of(), Map.of(), new float[0]);
    private record Data(Map<String, Clip> clips, Set<String> tipped) {}
    private static volatile Data data;

    /** The clip authored under {@code clip}, or the {@link #MISSING} default when the data has no such clip. */
    public static Clip of(String clip) { return loaded().clips().getOrDefault(clip == null ? "" : clip, MISSING); }

    /** Whether the model {@code model} has its own authored tuft track for {@code clip} (the braid-key clips are authored per model). */
    public static boolean tipped(String model, String clip) { return loaded().tipped().contains(model + "/" + clip); }

    private static Data loaded() {
        var loaded = data;
        if (loaded == null) synchronized (ClipMarkers.class) {
            if ((loaded = data) == null) data = loaded = read();
        }
        return loaded;
    }

    private static Data read() {
        var out = new HashMap<String, Clip>();
        var tipped = new HashSet<String>();
        try (var stream = ClipMarkers.class.getResourceAsStream(DATA)) {
            if (stream == null) throw new IOException("missing " + DATA);
            var reader = new JsonReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            reader.beginObject();
            while (reader.hasNext()) {
                if (!reader.nextName().equals("models")) { reader.skipValue(); continue; }
                reader.beginObject();
                while (reader.hasNext()) {
                    String model = reader.nextName();
                    reader.beginObject();
                    while (reader.hasNext()) {
                        if (!reader.nextName().equals("clips")) { reader.skipValue(); continue; }
                        reader.beginObject();
                        while (reader.hasNext()) {
                            String name = reader.nextName();
                            var clip = clip(reader);
                            if (clip.tip().length > 0) tipped.add(model + "/" + name);
                            out.putIfAbsent(name, clip);
                        }
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
        return new Data(Map.copyOf(out), Set.copyOf(tipped));
    }

    private static Clip clip(JsonReader reader) throws IOException {
        float seconds = 1f;
        var markers = new HashMap<String, Float>();
        var parameters = new HashMap<String, Float>();
        var tip = new ArrayList<Float>();
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
                case "parameters" -> {
                    reader.beginObject();
                    while (reader.hasNext()) parameters.put(reader.nextName(), (float) reader.nextDouble());
                    reader.endObject();
                }
                case "tip" -> {
                    reader.beginArray();
                    while (reader.hasNext()) {
                        reader.beginArray();
                        while (reader.hasNext()) tip.add((float) reader.nextDouble());
                        reader.endArray();
                    }
                    reader.endArray();
                }
                default -> reader.skipValue();
            }
        }
        reader.endObject();
        var rows = new float[tip.size()];
        for (int i = 0; i < rows.length; i++) rows[i] = tip.get(i);
        return new Clip(seconds, Map.copyOf(markers), Map.copyOf(parameters), rows);
    }

    private ClipMarkers() {}
}
