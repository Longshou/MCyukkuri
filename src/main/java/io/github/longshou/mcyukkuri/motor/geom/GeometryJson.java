package io.github.longshou.mcyukkuri.motor.geom;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Classpath geometry is immutable for the lifetime of the jar, on both client and server. */
public record GeometryJson(String file, JsonObject data) {
    public static GeometryJson read(String file) {
        var stream = GeometryJson.class.getResourceAsStream("/assets/mcyukkuri/" + file);
        if (stream == null) throw new IllegalArgumentException(file + ": missing resource");
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return new GeometryJson(file, JsonParser.parseReader(reader).getAsJsonObject());
        } catch (IOException e) { throw new UncheckedIOException(file, e); }
        catch (RuntimeException e) { throw new IllegalArgumentException(file + ": invalid JSON", e); }
    }
    public JsonElement field(String path) {
        JsonElement value = data;
        for (var key : path.split("\\.")) {
            check(value.isJsonObject() && value.getAsJsonObject().has(key), path, "missing field");
            value = value.getAsJsonObject().get(key);
        }
        return value;
    }
    public double number(String path) {
        var value = field(path);
        check(value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber(), path, "expected number");
        double n = value.getAsDouble(); check(Double.isFinite(n), path, "expected finite number"); return n;
    }
    public String string(String path) {
        var value=field(path);
        check(value.isJsonPrimitive()&&value.getAsJsonPrimitive().isString(),path,"expected string");
        return value.getAsString();
    }
    public void check(boolean valid, String field, String reason) {
        if (!valid) throw new IllegalArgumentException(file + ": " + field + ": " + reason);
    }
}
