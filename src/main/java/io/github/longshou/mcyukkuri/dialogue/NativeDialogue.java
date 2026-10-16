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
    /** Short, actually recorded utterances selected for each aquatic beat. Text and audio remain the same line. */
    private static final Map<String, String> AQUATIC_EVENTS = Map.ofEntries(
            Map.entry("boat_rock_startle", "heard"),
            Map.entry("family_alarm_call", "wait_family"),
            Map.entry("family_alarm_answer", "wait_learned"),
            Map.entry("family_alarm_flee", "afraid"),
            Map.entry("family_alarm_calm", "unrecognized"),
            Map.entry("water_help", "afraid"),
            Map.entry("braid_rescue_offer", "wait_family"),
            Map.entry("braid_rescue_grip", "exercise"),
            Map.entry("braid_rescue_safe", "wait_turn"));
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
        result.addAll(AQUATIC_EVENTS.keySet());
        return result;
    }
    public static Line select(String species, boolean young, String event, net.minecraft.util.RandomSource random) {
        String key=species+"/"+(young ? "young" : "adult")+"/";
        var choices = EVENTS.get(key+event);
        if(choices==null&&AQUATIC_EVENTS.containsKey(event))choices=EVENTS.get(key+AQUATIC_EVENTS.get(event));
        return choices == null ? null : choices.get(choices.size() == 1 ? 0 : random.nextInt(choices.size()));
    }
    public static int priority(String event) {
        return switch (event) {
            case "family_alarm_call", "family_alarm_answer" -> 4;
            case "wounded", "afraid", "lost_accessory", "family_died", "lost_parent", "birth",
                    "boat_rock_startle", "family_alarm_flee", "water_help",
                    "braid_rescue_offer", "braid_rescue_grip" -> 3;
            case "fed", "returned_accessory", "treated", "newborn", "hatched", "awake", "lesson_learned",
                    "family_alarm_calm", "braid_rescue_safe" -> 2;
            // Exam, shop and wild-role performances interrupt ordinary chatter but never urgent distress.
            case "exam_nervous", "exam_answer", "exam_unsure", "exam_hide_fail", "exam_promise_wait", "exam_promise_fail",
                 "exam_report", "exam_sorry", "exam_pass", "exam_fail", "badge_proud", "shop_appeal", "shop_bought",
                 "shop_sold_sad", "shop_sold_proud", "wild_role_beg", "wild_role_provoke" -> 2;
            case "idle", "chew", "sleep", "house_peek", "peer", "known_peer" -> 0;
            default -> 1;
        };
    }
    private NativeDialogue() {}
}
