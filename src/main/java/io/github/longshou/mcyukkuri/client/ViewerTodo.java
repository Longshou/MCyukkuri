package io.github.longshou.mcyukkuri.client;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;

/** The playground launcher writes this list outside the jar so it can follow the current TODO. */
record ViewerTodo(int format, String since, String until, List<ViewerTodo.Item> items) {
    static final String PROPERTY = "mcyukkuri.viewer";
    private static final Gson JSON = new Gson();

    record Item(String status, String title, String watch, boolean fresh, List<Scene> scenes) {
        boolean failed() { return "复测失败".equals(status); }

        private boolean valid() {
            return (failed() || "已接入待试玩".equals(status)) && title != null && watch != null
                    && scenes != null && scenes.stream().allMatch(s -> s != null && s.valid());
        }
    }

    record Scene(String id, String label, String field) {
        private boolean valid() {
            return id != null && id.matches("[a-z][a-z0-9_]*") && label != null && field != null;
        }
    }

    static ViewerTodo read() {
        try (var reader = Files.newBufferedReader(Path.of(System.getProperty(PROPERTY)))) {
            var list = JSON.fromJson(reader, ViewerTodo.class);
            if (list != null && list.format == 1 && list.until != null
                    && list.items != null && list.items.stream().allMatch(i -> i != null && i.valid())) {
                return list;
            }
        } catch (IOException | JsonParseException | InvalidPathException ignored) {
            // The screen explains how to regenerate a missing or unreadable list.
        }
        return null;
    }
}
