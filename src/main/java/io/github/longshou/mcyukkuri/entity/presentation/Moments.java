package io.github.longshou.mcyukkuri.entity.presentation;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.longshou.mcyukkuri.motor.ClipMarkers;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Shared presentation data. Selection observes behavior; rows never drive gameplay. */
public final class Moments {
    private static final JsonObject DATA = read();
    public record Frame(JsonObject step, int index, float seconds, float start) {
        public String clip() { return step.get("clip").getAsString(); }
        public float intensity() { return step.get("intensity").getAsFloat(); }
        public float tempo() { return step.get("tempo").getAsFloat(); }
        public String optional(String key) { return step.has(key) ? step.get(key).getAsString() : ""; }
        public String face(String fallback) {
            for (var value : step.getAsJsonArray("faces")) {
                var pair = value.getAsJsonArray();
                if (seconds >= pair.get(0).getAsFloat()) fallback = pair.get(1).getAsString();
            }
            return fallback;
        }
    }
    private static JsonObject read() {
        var stream = Moments.class.getResourceAsStream("/assets/mcyukkuri/yukkuri_presentation/moments.json");
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
    }
    public static String select(String table, String key, String fallback) {
        var value = DATA.getAsJsonObject(table).get(key);
        return value == null ? fallback : value.getAsString();
    }
    public static String predatorFace(String expression) {
        var value = DATA.getAsJsonObject("predator_faces").get(expression);
        return value == null ? expression : value.getAsString();
    }
    public static JsonObject row(String id) { return DATA.getAsJsonObject("moments").getAsJsonObject(id); }
    public static String clip(String id) { return row(id).getAsJsonArray("steps").get(0).getAsJsonObject().get("clip").getAsString(); }
    public static int ticks(String id) { return Math.max(1, (int)Math.ceil(duration(id)*20)); }
    public static float duration(String id) {
        float duration = 0;
        for (var step : row(id).getAsJsonArray("steps")) duration += duration(step.getAsJsonObject());
        return duration;
    }
    private static float duration(JsonObject step) {
        return ClipMarkers.of(step.get("clip").getAsString()).seconds() / step.get("tempo").getAsFloat();
    }
    public static Frame frame(String id, float elapsed) {
        var row = row(id);
        float total = duration(id);
        float start = row.get("loop").getAsBoolean() ? (float)Math.floor(Math.max(0, elapsed)/total)*total : 0;
        float time = row.get("loop").getAsBoolean() ? Math.max(0, elapsed)-start : Math.clamp(elapsed, 0, total);
        var steps = row.getAsJsonArray("steps");
        for (int i = 0; i < steps.size(); i++) {
            var step = steps.get(i).getAsJsonObject();
            float length = duration(step);
            if (time < length || i == steps.size()-1) return new Frame(step, i, time, start);
            time -= length;
            start += length;
        }
        throw new IllegalStateException("Empty moment " + id);
    }
    public static String face(String id, float elapsed, String fallback) {
        return frame(id, elapsed).face(fallback);
    }
    private Moments() {}
}
