package io.github.longshou.mcyukkuri.motor;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.motor.geom.AbilityMath;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.CRC32;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;

/** The ability data ({@code body/ability.json}), read on the vanilla resource reload so {@code /reload} is the only way numbers change; broken data is one WARN and the defaults. */
public final class AbilityTable extends SimpleJsonResourceReloadListener {
    /** The data path: {@code data/<namespace>/body/ability.json}. */
    public static final String DIRECTORY = "body", FILE = "ability";
    private static final Gson GSON = new Gson();
    private static volatile AbilityMath.Coefficients coefficients = AbilityMath.Coefficients.DEFAULTS;
    private static volatile Map<String, AbilityMath.Multipliers> modifiers = Map.of();
    private static volatile String fingerprint = print(coefficients, modifiers);

    public AbilityTable() { super(GSON, DIRECTORY); }

    /** The coefficients in force, read by every {@link BodyAbility#of}. */
    public static AbilityMath.Coefficients current() { return coefficients; }

    /** 8 hex digits over the parsed data; the same file always yields the same value. */
    public static String fingerprint() { return fingerprint; }

    /** The species' factors, {@link AbilityMath.Multipliers#IDENTITY} when the data authors none for that key. */
    public static AbilityMath.Multipliers modifier(String speciesKey) {
        if (speciesKey == null) return AbilityMath.Multipliers.IDENTITY;
        return modifiers.getOrDefault(speciesKey, AbilityMath.Multipliers.IDENTITY);
    }

    @Override protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager manager,
                                   ProfilerFiller profiler) {
        Data data = Data.FALLBACK;
        JsonElement element = files.get(ResourceLocation.fromNamespaceAndPath(MCYukkuri.MOD_ID, FILE));
        if (element == null) MCYukkuri.LOGGER.warn("[body] ability data missing {}/{}; using code defaults",
                DIRECTORY, FILE + ".json");
        else try {
            data = read(element);
        } catch (RuntimeException broken) {
            MCYukkuri.LOGGER.warn("[body] ability data broken {}; using code defaults", broken.toString());
        }
        coefficients = data.coefficients();
        modifiers = data.modifiers();
        fingerprint = print(coefficients, modifiers);
        MCYukkuri.LOGGER.info("[body] ability table loaded fp={} modifiers={}", fingerprint, new TreeMap<>(modifiers).keySet());
    }

    /** One reloaded file: coefficients plus the modifiers keyed by species. */
    private record Data(AbilityMath.Coefficients coefficients, Map<String, AbilityMath.Multipliers> modifiers) {
        static final Data FALLBACK = new Data(AbilityMath.Coefficients.DEFAULTS, Map.of());
    }

    private static Data read(JsonElement element) {
        JsonObject root = GsonHelper.convertToJsonObject(element, FILE);
        var base = AbilityMath.Coefficients.DEFAULTS;
        JsonObject numbers = root.has("coefficients")
                ? GsonHelper.convertToJsonObject(root.get("coefficients"), "coefficients") : new JsonObject();
        var coefficients = new AbilityMath.Coefficients(
                number(numbers, "hop_height", base.hopHeight()), number(numbers, "hop_reach", base.hopReach()),
                number(numbers, "step_up", base.stepUp()), number(numbers, "walk_speed", base.walkSpeed()),
                number(numbers, "turn_rate", base.turnRate()), number(numbers, "turn_floor", base.turnFloor()),
                number(numbers, "swim_speed", base.swimSpeed()), number(numbers, "climb_rise", base.climbRise()),
                number(numbers, "climb_floor", base.climbFloor()), number(numbers, "energy_ref", base.energyRef()),
                number(numbers, "min_jump_energy", base.minJumpEnergy()), number(numbers, "pain_weight", base.painWeight()),
                number(numbers, "burn_penalty", base.burnPenalty()), number(numbers, "load_ref", base.loadRef()),
                number(numbers, "size_min", base.sizeMin()), number(numbers, "size_max", base.sizeMax()),
                number(numbers, "fitness_floor", base.fitnessFloor()));
        var known = coefficients.asMap().keySet();
        var unknown = new ArrayList<String>();
        for (var key : numbers.keySet()) if (!known.contains(key)) unknown.add("coefficients." + key);
        var modifiers = new LinkedHashMap<String, AbilityMath.Multipliers>();
        if (root.has("modifiers")) {
            JsonObject species = GsonHelper.convertToJsonObject(root.get("modifiers"), "modifiers");
            for (var entry : species.entrySet()) {
                if (!entry.getValue().isJsonObject()) throw new IllegalArgumentException("modifiers." + entry.getKey());
                var factors = new TreeMap<String, Double>();
                for (var factor : GsonHelper.convertToJsonObject(entry.getValue(), entry.getKey()).entrySet()) {
                    if (!known.contains(factor.getKey()) && !AbilityModifier.FLAGS.contains(factor.getKey())) {
                        unknown.add(entry.getKey() + "." + factor.getKey());
                        continue;
                    }
                    factors.put(factor.getKey(), GsonHelper.convertToDouble(factor.getValue(), factor.getKey()));
                }
                modifiers.put(entry.getKey(), new AbilityMath.Multipliers(factors));
            }
        }
        if (!unknown.isEmpty()) MCYukkuri.LOGGER.warn("[body] ability data ignored unknown keys {}", unknown);
        if (coefficients.sizeMin() < 0 || coefficients.sizeMax() < coefficients.sizeMin()
                || coefficients.energyRef() <= 0 || coefficients.loadRef() <= 0) throw new IllegalArgumentException(
                "coefficient bounds: size_min=" + coefficients.sizeMin() + " size_max=" + coefficients.sizeMax()
                        + " energy_ref=" + coefficients.energyRef() + " load_ref=" + coefficients.loadRef());
        return new Data(coefficients, Map.copyOf(modifiers));
    }

    private static double number(JsonObject source, String key, double fallback) {
        if (!source.has(key)) return fallback;
        double value = GsonHelper.convertToDouble(source.get(key), key);
        if (Double.isFinite(value) && value >= 0) return value;
        MCYukkuri.LOGGER.warn("[body] ability data rejected coefficient {}={}; using default {}", key, value, fallback);
        return fallback;
    }

    /** The canonical string a fingerprint is taken over: sorted keys, so key order in the file does not matter. */
    private static String print(AbilityMath.Coefficients coefficients, Map<String, AbilityMath.Multipliers> modifiers) {
        var text = new StringBuilder("{\"coefficients\":{");
        var names = new TreeMap<>(coefficients.asMap());
        String comma = "";
        for (var entry : names.entrySet()) {
            text.append(comma).append('"').append(entry.getKey()).append("\":").append(number(entry.getValue()));
            comma = ",";
        }
        text.append("},\"modifiers\":{");
        comma = "";
        for (var species : new TreeMap<>(modifiers).entrySet()) {
            text.append(comma).append('"').append(species.getKey()).append("\":{");
            String inner = "";
            for (var factor : new TreeMap<>(species.getValue().factors()).entrySet()) {
                text.append(inner).append('"').append(factor.getKey()).append("\":").append(number(factor.getValue()));
                inner = ",";
            }
            text.append('}');
            comma = ",";
        }
        text.append("}}");
        var crc = new CRC32();
        crc.update(text.toString().getBytes(StandardCharsets.UTF_8));
        return String.format(Locale.ROOT, "%08x", crc.getValue());
    }

    private static String number(double value) {
        return value == Math.rint(value) && Math.abs(value) < 1e15
                ? Long.toString((long) value) : Double.toString(value);
    }
}
