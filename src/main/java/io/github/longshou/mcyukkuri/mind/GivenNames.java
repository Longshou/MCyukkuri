package io.github.longshou.mcyukkuri.mind;

import com.google.gson.JsonParser;
import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Each individual's own name: a sound-alike of its species name (Marisa -> 麻啾 / まりちゃ / Marichya), drawn at random
 * from {@code data/mcyukkuri/yukkuri_names/<species>.json} so individuals of one species can be told apart without
 * a player name tag. Siblings and neighbours of the species avoid each other's names where the pool allows.
 * The stored value is the zh form (the key); ja and en are looked up from the same entry.
 */
public final class GivenNames {
    public record Entry(String zh, String ja, String en, String src) {}

    private static final Map<String, List<Entry>> POOLS = new HashMap<>();

    public static synchronized List<Entry> pool(String species) {
        return POOLS.computeIfAbsent(species, s -> {
            var list = new ArrayList<Entry>();
            try (var in = GivenNames.class.getResourceAsStream("/data/" + MCYukkuri.MOD_ID + "/yukkuri_names/" + s + ".json")) {
                if (in != null) {
                    var names = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("names");
                    for (var e : names) {
                        var o = e.getAsJsonObject();
                        list.add(new Entry(o.get("zh").getAsString(), o.get("ja").getAsString(), o.get("en").getAsString(),
                                o.has("src") ? o.get("src").getAsString() : ""));
                    }
                }
            } catch (Exception ex) {
                MCYukkuri.LOGGER.warn("[names] cannot read names for {}: {}", s, ex.toString());
            }
            return list;
        });
    }

    public static Entry find(String species, String zh) {
        for (var e : pool(species)) if (e.zh().equals(zh)) return e;
        return null;
    }

    /** True when {@code normalized} (lower case, separators removed) contains any variant of the species in any language. */
    public static boolean matchesAny(String species, String normalized) {
        for (var e : pool(species))
            for (String s : new String[] {e.zh(), e.ja(), e.en()}) {
                String n = s.toLowerCase(Locale.ROOT).replaceAll("[\s・·._-]", "");
                if (!n.isEmpty() && normalized.contains(n)) return true;
            }
        return false;
    }

    /** Draw a name not used by same-species neighbours (siblings share the spot), or any if all are taken. */
    public static String draw(YukkuriEntity y) {
        String species = y.getVariant().dialogueSpecies();
        var pool = pool(species);
        if (pool.isEmpty()) return "";
        Set<String> used = new HashSet<>();
        for (var other : y.level().getEntitiesOfClass(YukkuriEntity.class, y.getBoundingBox().inflate(24)))
            if (other != y && other.getVariant().dialogueSpecies().equals(species)) used.add(other.mind().givenName());
        var free = new ArrayList<Entry>();
        for (var e : pool) if (!used.contains(e.zh())) free.add(e);
        var from = free.isEmpty() ? pool : free;
        return from.get(y.getRandom().nextInt(from.size())).zh();
    }

    private GivenNames() {}
}
