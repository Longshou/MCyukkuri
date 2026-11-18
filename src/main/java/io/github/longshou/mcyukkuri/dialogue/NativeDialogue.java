package io.github.longshou.mcyukkuri.dialogue;

import com.google.gson.Gson;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.util.RandomSource;

/** Packaged, curated native dialogue. No client types or Mod dialogue fallback. */
public final class NativeDialogue {
    public record Line(String id, String key, String species, String age, List<String> events, int ticks, String sound, String quirk,
                       String variant, Map<String, String> voices) {
        /**
         * A shared line has one recording per speaker species and stage. Species-specific lines
         * carry a fixed recording, so this returns their ordinary sound unchanged.
         */
        public String soundFor(String speakerSpecies, String speakerTier) {
            if (voices == null || voices.isEmpty()) return sound == null ? "" : sound;
            String chosen = voices.get(speakerSpecies + "." + speakerTier);
            if (chosen == null) chosen = switch (speakerTier) {
                case "baby", "juvenile" -> voices.get(speakerSpecies + ".young");
                case "young" -> voices.get(speakerSpecies + ".juvenile");
                default -> null;
            };
            if (chosen == null) chosen = voices.get(speakerSpecies + ".adult");
            if (chosen == null) chosen = sound;
            return chosen == null ? "" : chosen;
        }
    }
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
        events.replaceAll((key, choices) -> List.copyOf(choices));
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
    /**
     * Baby and juvenile can fall back to young, and young to juvenile. Species lines keep their own quirk and
     * adults keep their own tier. Shared lines are used when the species has no applicable line.
     */
    public static List<Line> choices(String species, String tier, String quirk, String event) {
        var exact = EVENTS.get(indexKey(species, tier, quirk, event));
        if (exact != null) return exact;
        var fallback = switch (tier) {
            case "baby", "juvenile" -> EVENTS.get(indexKey(species, "young", quirk, event));
            case "young" -> EVENTS.get(indexKey(species, "juvenile", quirk, event));
            default -> null;
        };
        return fallback != null ? fallback : EVENTS.getOrDefault(indexKey("*", "*", null, event), List.of());
    }
    /** Prefer a line neighbours haven't just said, then a fresh line in this voice, then the least recently spoken. */
    public static Line select(List<Line> choices, String style, List<String> recent, Set<String> nearby, RandomSource random) {
        var candidates = choices.stream().filter(line -> !nearby.contains(line.key())).toList();
        if (candidates.isEmpty()) candidates = choices;
        var fresh = candidates.stream().filter(line -> !recent.contains(line.key())).toList();
        if (fresh.isEmpty()) return Collections.min(candidates, Comparator.comparingInt(line -> recent.indexOf(line.key())));
        var styled = fresh.stream().filter(line -> style.equals(line.variant())
                || (style.equals("plain") && line.variant() == null)).toList();
        var neutral = fresh.stream().filter(line -> line.variant() == null).toList();
        var pool = !styled.isEmpty() ? styled : !neutral.isEmpty() ? neutral : fresh;
        return pool.get(pool.size() == 1 ? 0 : random.nextInt(pool.size()));
    }
    /** Whether this exact tier (no fallback) has a line for the event. */
    public static boolean has(String species, String tier, String quirk, String event) { return EVENTS.containsKey(indexKey(species, tier, quirk, event)); }
    public static int priority(String event) {
        return switch (event) {
            case "family_alarm_call", "family_alarm_answer" -> 4;
            case "wounded", "afraid", "lost_accessory", "family_died", "lost_parent", "birth", "non_yukkuri", "renamed_upset",
                    "dying_kin", "kin_dying", "sacrifice",
                    "offer_ask", "offer_insist", "offer_give", "offer_refuse", "offer_eat", "offer_grief", "offer_spared",
                    "boat_rock_startle", "family_alarm_flee", "water_help",
                    "braid_rescue_offer", "braid_rescue_grip", "braid_rescue_mouth_grip",
                    "self_rescue_hat", "self_rescue_hat_seek", "self_rescue_grip",
                    "self_rescue_climb", "self_rescue_slip", "hat_drifting", "hat_out_of_reach" -> 3;
            case "airborne", "fed", "returned_accessory", "treated", "newborn", "hatched", "awake", "lesson_learned",
                    "family_alarm_calm", "braid_rescue_safe", "braid_rescue_safe_ashore", "self_rescue_safe", "hat_recovered",
                    "glass_wait", "glass_child_answer", "glass_ram" -> 2;
            case "give_up_search" -> 3;
            // Period onset, the staged sukkiri completion, its unmet ending and the aftermath realization
            // are one-off state beats; they must not be swallowed by ordinary chatter.
            case "heat_start", "heat_relief_1", "heat_relief_2", "heat_relief_3", "heat_unmet", "aftermath_realize",
                 "heat_refuse", "rapem_hunt", "rapem_act", "rapem_moan", "rapem_review_good", "rapem_review_bad", "rapem_buffet" -> 2;
            // The hunted victim's alarm, flight and struggle outrank everything but alarms and wounds.
            case "rapem_alarm", "rapem_flee", "rapem_victim", "rapem_victim_climax", "rapem_victim_safe" -> 3;
            // Exam, shop and wild-role performances interrupt ordinary chatter but never urgent distress.
            case "exam_nervous", "exam_answer", "exam_unsure", "exam_hide_fail", "exam_promise_wait", "exam_promise_fail",
                 "exam_report", "exam_sorry", "exam_pass", "exam_fail", "badge_proud", "shop_appeal", "shop_bought",
                 "shop_sold_sad", "shop_sold_proud", "wild_role_beg", "wild_role_provoke",
                 "beg_ask", "beg_offer", "beg_refuse", "beg_plead_again", "beg_pester", "beg_cry", "beg_thanks",
                 "beg_robbed", "beg_chase", "beg_grudge", "beg_help", "beg_curse",
                 // Matchmaking, permit, badge-wearing, basket, spray and wild-plea reactions are one-off moments tied to a player action.
                 "date_meet", "date_fond", "permit_glad", "courtship_held", "badge_pinned", "badge_taken", "spray_drowsy",
                 "basket_refuse", "basket_enter", "basket_exit", "factory_receive_insult", "wild_plea_keep", "pet_report_owner",
                 "predator_restrain", "predator_obey", "predator_defy",
                 // Badge social arcs: every beat's line must stay in step with its animation, so none may be swallowed by the quiet-gap rule.
                 "badge_envy", "badge_admire", "badge_glance", "badge_ask", "badge_show_kind", "badge_pull_back", "badge_envy_snark", "badge_humble",
                 "badge_beg", "badge_share_offer", "badge_share_refuse", "badge_thanks", "badge_mock", "badge_retort", "pet_report_hurt", "plea_sad",
                 "badge_pity", "elder_warn", "elder_kind",
                 // Treats, requests to the owner, mood, harness walks and the swaddle answer something the owner just did or ask for something now.
                 "snack_eat", "snack_greedy", "snack_refuse", "ask_food", "ask_snack", "ask_play", "ask_walk", "request_thanks", "request_sulk", "mood_good",
                 "human_food_ask", "human_food_demand", "food_complain", "food_delight", "food_grudge",
                 "food_reluctant", "food_spit", "uneasy", "bitter", "glow",
                         "mood_ok", "mood_bad", "harness_on", "harness_tug", "walk_enjoy", "swaddle_on" -> 2;
            // These lines answer an event as it happens.
            case "thing_san", "treasure_stolen", "narrate_steal", "narrate_flee", "narrate_hide",
                 "addr_human_hostile_wary", "addr_human_hostile_old", "addr_human_hostile_man",
                 "addr_predator_remilia", "addr_predator_flandre" -> 2;
            // A junk headwear being put on, refused, mocked or taken off answers the person handling the wearer right now.
            case "junk_thanks", "junk_unsure", "junk_indignant", "junk_afraid" -> 2;
            // The dead and the smell of them: the stench, the taboo meal and the hunt of a hatless monster answer something happening right now.
            case "stench", "eat_kin", "hunt_sweet" -> 2;
            // Settlement calls and headwear beats: a hunt call, a ration, a scolding or a torn / mended ornament answers something that just happened.
            case "accessory_torn", "famine" -> 3;
            // The farewell's and the mould exile's beats are in step with their poses: none may be swallowed by chatter.
            case "farewell_say", "farewell_say_teary", "farewell_bless", "farewell_cry", "farewell_go", "farewell_look_back",
                 "mold_notice", "mold_warn", "mold_scorn", "mold_sorrow", "mold_plea", "mold_resist", "mold_leave", "mold_child_cry" -> 2;
            case "hunt_call", "hunt_join", "hunt_refuse", "hunt_back", "ration_give", "ration_get", "rule_blocked",
                 "migrate_discuss", "migrate_object", "migrate_agree", "migrate_pack", "shard_gathered", "accessory_mended",
                 "hatless_scorn", "hatless_monster", "home_declare" -> 2;
            case "idle", "chew", "sleep", "house_peek", "peer", "known_peer" -> 0;
            // Ambient restlessness during a still-running period yields to everything else.
            case "heat_want" -> 0;
            default -> event.startsWith("count_children_") || event.startsWith("count_stock_") ? 2 : 1;
        };
    }
    private NativeDialogue() {}
}
