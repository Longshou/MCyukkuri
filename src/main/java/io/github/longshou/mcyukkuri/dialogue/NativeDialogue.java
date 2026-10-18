package io.github.longshou.mcyukkuri.dialogue;

import com.google.gson.Gson;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Packaged, curated native dialogue. No client types or Mod dialogue fallback. */
public final class NativeDialogue {
    public record Line(String id, String key, String species, String age, List<String> events, int ticks, String sound, String quirk) {}
    private static final Map<String, Line> LINES;
    private static final Map<String, List<Line>> EVENTS;
    static {
        Map<String, Line> lines = new LinkedHashMap<>();
        Map<String, List<Line>> events = new HashMap<>();
        try (var stream = NativeDialogue.class.getResourceAsStream("/assets/mcyukkuri/dialogue/catalog.json")) {
            if (stream == null) throw new IllegalStateException("Missing native dialogue catalog");
            for (Line line : new Gson().fromJson(new InputStreamReader(stream, StandardCharsets.UTF_8), Line[].class)) {
                if (line.ticks() < 1 || lines.put(line.key(), line) != null) throw new IllegalStateException("Invalid dialogue " + line.id());
                for (String event : line.events()) events.computeIfAbsent(indexKey(line.species(), line.age(), line.quirk(), event), k -> new ArrayList<>()).add(line);
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
    private static String indexKey(String species, String tier, String quirk, String event) {
        return species + "/" + tier + "/" + event + (quirk == null || quirk.isEmpty() ? "" : "/" + quirk);
    }
    /** Older two-tier entry point: {@code young} maps to the legacy childish tier. */
    public static Line select(String species, boolean young, String event, net.minecraft.util.RandomSource random) {
        return select(species, young ? "young" : "adult", null, event, random);
    }
    /**
     * Pick a line for a speech tier ({@code baby}, {@code juvenile}, {@code young}, {@code adult}). A tier with no line
     * for the event falls back along the chain: baby to young, juvenile to young, young to juvenile; adult never
     * borrows a child's wording. A speaker with a {@code quirk} only sees lines of that quirk.
     */
    public static Line select(String species, String tier, String quirk, String event, net.minecraft.util.RandomSource random) {
        String[] chain = switch (tier) {
            case "baby" -> new String[] {"baby", "young"};
            case "juvenile" -> new String[] {"juvenile", "young"};
            case "young" -> new String[] {"young", "juvenile"};
            default -> new String[] {tier};
        };
        for (String step : chain) {
            var choices = EVENTS.get(indexKey(species, step, quirk, event));
            if (choices != null) return choices.get(choices.size() == 1 ? 0 : random.nextInt(choices.size()));
        }
        return null;
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
                 // Badge social arcs: every beat's line must stay in step with its animation, so none may be swallowed by the quiet-gap rule.
                 "badge_envy", "badge_admire", "badge_glance", "badge_ask", "badge_show_kind", "badge_pull_back", "badge_envy_snark", "badge_humble",
                 "badge_beg", "badge_share_offer", "badge_share_refuse", "badge_thanks", "badge_mock", "badge_retort", "pet_report_hurt", "plea_sad",
                 "badge_pity", "elder_warn", "elder_kind",
                 // Treats, requests to the owner, mood, harness walks and the swaddle answer something the owner just did or ask for something now.
                 "snack_eat", "snack_greedy", "snack_refuse", "ask_food", "ask_snack", "ask_play", "ask_walk", "request_thanks", "request_sulk", "mood_good", "mood_ok", "mood_bad", "harness_on", "harness_tug", "walk_enjoy", "swaddle_on" -> 2;
            // Addresses, counts and self-narration answer a person or a situation right now.
            case "thing_san", "narrate_steal", "narrate_flee", "narrate_hide" -> 2;
            case "idle", "chew", "sleep", "house_peek", "peer", "known_peer" -> 0;
            default -> event.startsWith("addr_") || event.startsWith("count_") ? 2 : 1;
        };
    }
    private NativeDialogue() {}
}
