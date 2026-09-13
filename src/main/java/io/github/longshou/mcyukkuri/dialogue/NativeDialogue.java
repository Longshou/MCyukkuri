package io.github.longshou.mcyukkuri.dialogue;

import com.google.gson.Gson;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Packaged, curated native dialogue. No client types or Mod dialogue fallback. */
public final class NativeDialogue {
    public record Line(String id, String key, String species, String age, List<String> events, int ticks, String sound) {}
    private static final Map<String, Line> LINES;
    private static final Map<String, List<Line>> EVENTS;
    static {
        Map<String, Line> lines = new LinkedHashMap<>();
        Map<String, List<Line>> events = new HashMap<>();
        try (var stream = NativeDialogue.class.getResourceAsStream("/assets/mcyukkuri/dialogue/catalog.json")) {
            if (stream == null) throw new IllegalStateException("Missing native dialogue catalog");
            for (Line line : new Gson().fromJson(new InputStreamReader(stream, StandardCharsets.UTF_8), Line[].class)) {
                if (line.ticks() < 1 || lines.put(line.key(), line) != null) throw new IllegalStateException("Invalid dialogue " + line.id());
                for (String event : line.events()) events.computeIfAbsent(line.species()+"/"+line.age()+"/"+event, k -> new ArrayList<>()).add(line);
            }
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
        LINES = Collections.unmodifiableMap(lines);
        EVENTS = Collections.unmodifiableMap(events);
    }
    public static Line byKey(String key) { return LINES.get(key); }
    public static Collection<Line> lines() { return LINES.values(); }
    public static Set<String> events() {
        Set<String> result = new TreeSet<>();
        for (Line line : lines()) result.addAll(line.events());
        return result;
    }
    public static Line select(String species, boolean young, String event, net.minecraft.util.RandomSource random) {
        var choices = EVENTS.get(species+"/"+(young ? "young" : "adult")+"/"+event);
        return choices == null ? null : choices.get(choices.size() == 1 ? 0 : random.nextInt(choices.size()));
    }
    public static int priority(String event) {
        return switch (event) {
            case "wounded", "afraid", "lost_accessory", "family_died", "lost_parent", "birth" -> 3;
            case "fed", "returned_accessory", "treated", "newborn", "hatched", "awake", "lesson_learned" -> 2;
            case "idle", "chew", "sleep", "house_peek", "peer", "known_peer" -> 0;
            default -> 1;
        };
    }
    private NativeDialogue() {}
}
