package io.github.longshou.mcyukkuri.entity.presentation;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.subspecies.crew.BodyClass;
import io.github.longshou.mcyukkuri.subspecies.crew.DeckLayout;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The resting clip and face of each body aboard a hat boat (hat_deck_layout.json {@code crew}, project design, ecology D 3.4),
 * chosen on the server by seat kind, body class and day or night. Only a resting body reads it; any running activity, transfer,
 * incident or care keeps its own clip. A missing row returns null and the caller keeps its own choice.
 */
public final class CrewPoseTable {
    /** A clip and a face; a null face keeps the clip's own. */
    public record Pose(String clip, String face) {}
    private record Young(DeckLayout.Kind slot, int classes, Pose day, Pose night) {}

    private static volatile CrewPoseTable table;
    private final Pose captainDay, captainNight, captainCover, captainThreat, highDay, highNight;
    private final List<Young> young;

    private CrewPoseTable(JsonObject crew) {
        var captain = crew == null ? null : crew.getAsJsonObject("captain");
        var high = crew == null ? null : crew.getAsJsonObject("captain_high");
        captainDay = pose(captain, "day");
        captainNight = pose(captain, "night");
        captainCover = pose(captain, "night_with_young");
        captainThreat = pose(captain, "threat");
        highDay = pose(high, "day");
        highNight = pose(high, "night");
        var rows = new ArrayList<Young>();
        if (crew != null && crew.has("young")) for (var raw : crew.getAsJsonArray("young")) {
            var row = raw.getAsJsonObject();
            DeckLayout.Kind slot;
            try { slot = DeckLayout.Kind.valueOf(row.get("slot").getAsString()); } catch (IllegalArgumentException unknown) { continue; }
            int classes = 0;
            for (var c : row.getAsJsonArray("classes")) {
                try { classes |= BodyClass.of(c.getAsString()).mask(); } catch (IllegalArgumentException unknown) { /* a class this build does not know */ }
            }
            rows.add(new Young(slot, classes, pose(row, "day"), pose(row, "night")));
        }
        young = List.copyOf(rows);
    }

    private static Pose pose(JsonObject parent, String key) {
        if (parent == null || !parent.has(key) || !parent.get(key).isJsonObject()) return null;
        var o = parent.getAsJsonObject(key);
        return o.has("clip") ? new Pose(o.get("clip").getAsString(), o.has("face") ? o.get("face").getAsString() : null) : null;
    }

    public static CrewPoseTable get() {
        var loaded = table;
        if (loaded == null) synchronized (CrewPoseTable.class) {
            if ((loaded = table) == null) table = loaded = new CrewPoseTable(read());
        }
        return loaded;
    }

    private static JsonObject read() {
        try (var stream = CrewPoseTable.class.getResourceAsStream("/assets/mcyukkuri/yukkuri_presentation/hat_deck_layout.json")) {
            if (stream == null) throw new IOException("missing hat_deck_layout.json");
            var reader = new JsonReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            reader.beginObject();
            while (reader.hasNext()) {
                if (reader.nextName().equals("crew")) return JsonParser.parseReader(reader).getAsJsonObject();
                reader.skipValue();
            }
        } catch (IOException | RuntimeException failure) {
            MCYukkuri.LOGGER.warn("[aquatic] crew pose table unreadable, boats keep their own clips: {}", failure.toString());
        }
        return null;
    }

    /**
     * The captain at rest: on the high stern seat she broods (no cover by night); by night she sleeps, covering the young in the
     * nest hollow or the front well before her; in rain with young aboard she covers them awake.
     */
    public Pose captain(boolean high, boolean asleep, boolean youngBefore, boolean threat) {
        if (high) return asleep ? highNight : highDay;
        if (asleep) return youngBefore && captainCover != null ? captainCover : captainNight;
        if (threat && captainThreat != null) return captainThreat;
        return captainDay;
    }

    /** A young at rest on this seat kind: by day its seat's clip, asleep the night row (a seat closed at night returns null). */
    public Pose young(DeckLayout.Kind seat, BodyClass cls, boolean asleep) {
        for (var row : young)
            if (row.slot() == seat && (row.classes() & cls.mask()) != 0) return asleep ? row.night() : row.day();
        return null;
    }
}
