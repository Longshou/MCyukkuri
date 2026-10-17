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
        String key=species+"/"+(young ? "young" : "adult")+"/";
        var choices = EVENTS.get(key+event);
        return choices == null ? null : choices.get(choices.size() == 1 ? 0 : random.nextInt(choices.size()));
    }
    public static int priority(String event) {
        return switch (event) {
            case "family_alarm_call", "family_alarm_answer" -> 4;
            case "wounded", "afraid", "lost_accessory", "family_died", "lost_parent", "birth",
                    "boat_rock_startle", "family_alarm_flee", "water_help",
                    "braid_rescue_offer", "braid_rescue_grip", "self_rescue_hat", "self_rescue_grip",
                    "self_rescue_climb", "self_rescue_slip", "hat_drifting", "hat_out_of_reach" -> 3;
            case "fed", "returned_accessory", "treated", "newborn", "hatched", "awake", "lesson_learned",
                    "family_alarm_calm", "braid_rescue_safe", "self_rescue_safe", "hat_recovered" -> 2;
            // Exam, shop and wild-role performances interrupt ordinary chatter but never urgent distress.
            case "exam_nervous", "exam_answer", "exam_unsure", "exam_hide_fail", "exam_promise_wait", "exam_promise_fail",
                 "exam_report", "exam_sorry", "exam_pass", "exam_fail", "badge_proud", "shop_appeal", "shop_bought",
                 "shop_sold_sad", "shop_sold_proud", "wild_role_beg", "wild_role_provoke",
                 // Matchmaking, permit, badge-wearing, basket, spray and wild-plea reactions are one-off moments tied to a player action.
                 "date_meet", "date_fond", "permit_glad", "courtship_held", "badge_pinned", "badge_taken", "spray_drowsy",
                 "basket_refuse", "basket_enter", "basket_exit", "wild_plea_keep", "pet_report_owner",
                 "predator_restrain", "predator_obey", "predator_defy",
                 // Treats, requests to the owner, mood, harness walks and the swaddle answer something the owner just did or ask for something now.
                 "snack_eat", "snack_greedy", "snack_refuse", "ask_food", "ask_snack", "ask_play", "ask_walk", "request_thanks", "request_sulk", "mood_good", "mood_ok", "mood_bad", "harness_on", "harness_tug", "walk_enjoy", "swaddle_on" -> 2;
            case "idle", "chew", "sleep", "house_peek", "peer", "known_peer" -> 0;
            default -> 1;
        };
    }
    private NativeDialogue() {}
}
