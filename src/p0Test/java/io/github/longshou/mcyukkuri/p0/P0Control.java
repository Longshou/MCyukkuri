package io.github.longshou.mcyukkuri.p0;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;

/** A local, opt-in test controller; no network control endpoint or release code. */
public final class P0Control {
    public static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    public static final Path DIRECTORY = Path.of("p0-control").toAbsolutePath();
    private static String lastId = "";

    public static boolean enabled() { return Boolean.getBoolean("mcyukkuri.p0"); }

    public static JsonObject poll() {
        Path path = DIRECTORY.resolve("request.json");
        if (!Files.isRegularFile(path)) return null;
        try {
            JsonObject request = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            String id = request.get("id").getAsString();
            if (id.equals(lastId)) return null;
            lastId = id;
            return request;
        } catch (IOException error) {
            throw new IllegalStateException("Cannot read P0 request", error);
        }
    }

    public static void write(String name, Object value) {
        try {
            Files.createDirectories(DIRECTORY);
            Path target = DIRECTORY.resolve(name + ".json");
            Path temporary = DIRECTORY.resolve(name + ".tmp");
            Files.writeString(temporary, JSON.toJson(value));
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException error) {
            throw new IllegalStateException("Cannot write P0 result", error);
        }
    }

    public static JsonObject reply(JsonObject request, boolean pass, Object result) {
        JsonObject response = new JsonObject();
        response.addProperty("id", request.get("id").getAsString());
        response.addProperty("op", request.get("op").getAsString());
        response.addProperty("pass", pass);
        response.addProperty("time", Instant.now().toString());
        response.add("result", JSON.toJsonTree(result));
        write("response", response);
        return response;
    }

    private P0Control() {}
}
