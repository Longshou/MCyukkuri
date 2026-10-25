package io.github.longshou.mcyukkuri.entity.presentation;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import io.github.longshou.mcyukkuri.MCYukkuri;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What the shipped assets can actually show, read once on the server from the same resources the client draws: the clip names
 * of each model (its own {@code yukkuri/<asset>.json} clips and the motion revisions), the markers of the revised clips and
 * whether a face texture exists. The presentation watchdog checks every published clip and face against it; nothing here
 * chooses a clip.
 */
public final class PresentationCatalog {
    private record Model(Set<String> clips, Map<String, Map<String, Float>> markers) {}
    private static final String ROOT = "/assets/mcyukkuri/";
    private static final Map<String, Model> MODELS = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> FACES = new ConcurrentHashMap<>();
    private static volatile Map<String, Model> revisions;

    /** The model ships this clip (its own or a motion revision). An unreadable model accepts every clip. */
    public static boolean hasClip(String asset, String clip) {
        var model = model(asset);
        return model == null || model.clips().contains(clip);
    }

    /** A named second of a revised clip, or the fallback. */
    public static float marker(String asset, String clip, String marker, float fallback) {
        var model = model(asset);
        var markers = model == null ? null : model.markers().get(clip);
        return markers == null ? fallback : markers.getOrDefault(marker, fallback);
    }

    /** The face texture of this model exists. */
    public static boolean hasFace(String asset, String face) {
        return FACES.computeIfAbsent(asset + "/" + face, key -> PresentationCatalog.class.getResource(ROOT + "textures/entity/" + key + ".png") != null);
    }

    private static Model model(String asset) {
        var cached = MODELS.get(asset);
        if (cached != null) return cached.clips().isEmpty() ? null : cached;
        var revised = revisions();
        var clips = new HashSet<String>();
        var markers = new HashMap<String, Map<String, Float>>();
        var revision = revised.get(asset);
        if (revision != null) { clips.addAll(revision.clips()); markers.putAll(revision.markers()); }
        try (var stream = PresentationCatalog.class.getResourceAsStream(ROOT + "yukkuri/" + asset + ".json")) {
            if (stream != null) {
                var reader = new JsonReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
                reader.beginObject();
                while (reader.hasNext()) {
                    if (!reader.nextName().equals("clips")) { reader.skipValue(); continue; }
                    reader.beginObject();
                    while (reader.hasNext()) { clips.add(reader.nextName()); reader.skipValue(); }
                    reader.endObject();
                }
            }
        } catch (IOException | RuntimeException failure) {
            MCYukkuri.LOGGER.warn("[presentation] clip catalogue of {} unreadable: {}", asset, failure.toString());
            clips.clear();
        }
        var model = new Model(Set.copyOf(clips), Map.copyOf(markers));
        MODELS.put(asset, model);
        return clips.isEmpty() ? null : model;
    }

    private static Map<String, Model> revisions() {
        var loaded = revisions;
        if (loaded != null) return loaded;
        synchronized (PresentationCatalog.class) {
            if (revisions != null) return revisions;
            var out = new HashMap<String, Model>();
            try (var stream = PresentationCatalog.class.getResourceAsStream(ROOT + "yukkuri_presentation/motion_corrections.json")) {
                if (stream == null) throw new IOException("missing motion_corrections.json");
                var reader = new JsonReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
                reader.beginObject();
                while (reader.hasNext()) {
                    if (!reader.nextName().equals("models")) { reader.skipValue(); continue; }
                    reader.beginObject();
                    while (reader.hasNext()) {
                        String asset = reader.nextName();
                        var clips = new HashSet<String>();
                        var markers = new HashMap<String, Map<String, Float>>();
                        reader.beginObject();
                        while (reader.hasNext()) {
                            if (!reader.nextName().equals("clips")) { reader.skipValue(); continue; }
                            reader.beginObject();
                            while (reader.hasNext()) {
                                String clip = reader.nextName();
                                clips.add(clip);
                                var found = clipMarkers(reader);
                                if (!found.isEmpty()) markers.put(clip, found);
                            }
                            reader.endObject();
                        }
                        reader.endObject();
                        out.put(asset, new Model(Set.copyOf(clips), Map.copyOf(markers)));
                    }
                    reader.endObject();
                }
            } catch (IOException | RuntimeException failure) {
                MCYukkuri.LOGGER.warn("[presentation] motion revisions unreadable: {}", failure.toString());
            }
            revisions = Map.copyOf(out);
            return revisions;
        }
    }

    private static Map<String, Float> clipMarkers(JsonReader reader) throws IOException {
        var markers = new HashMap<String, Float>();
        reader.beginObject();
        while (reader.hasNext()) {
            if (!reader.nextName().equals("markers")) { reader.skipValue(); continue; }
            reader.beginObject();
            while (reader.hasNext()) {
                String key = reader.nextName();
                if (reader.peek() == JsonToken.NUMBER) markers.put(key, (float) reader.nextDouble()); else reader.skipValue();
            }
            reader.endObject();
        }
        reader.endObject();
        return markers;
    }

    private PresentationCatalog() {}
}
