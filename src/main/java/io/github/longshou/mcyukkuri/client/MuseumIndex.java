package io.github.longshou.mcyukkuri.client;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;

/** The launcher writes the field and scenario registry beside the playtest list. */
record MuseumIndex(int format, List<Group> groups) {
    record Group(String title, List<Field> fields) {
        boolean valid() { return title != null && fields != null && fields.stream().allMatch(f -> f != null && f.valid()); }
    }

    record Field(String id, String title, List<Scene> scenes) {
        boolean valid() { return validId(id) && title != null && scenes != null && scenes.stream().allMatch(s -> s != null && s.valid()); }
    }

    record Scene(String id, String label, String tag, String watch) {
        boolean valid() { return validId(id) && label != null && tag != null && watch != null; }
    }

    private static boolean validId(String id) { return id != null && id.matches("[a-z][a-z0-9_]{0,63}"); }

    List<Field> fields() { return groups.stream().flatMap(group -> group.fields().stream()).toList(); }

    static MuseumIndex read() {
        try (var reader = Files.newBufferedReader(Path.of(System.getProperty(ViewerTodo.PROPERTY)).resolveSibling("scenes.json"))) {
            var index = new Gson().fromJson(reader, MuseumIndex.class);
            if (index != null && index.format == 1 && index.groups != null
                    && index.groups.stream().allMatch(g -> g != null && g.valid()) && !index.fields().isEmpty()) return index;
        } catch (IOException | JsonParseException | InvalidPathException ignored) {
            // The scene tab displays how to regenerate the catalog.
        }
        return null;
    }
}
