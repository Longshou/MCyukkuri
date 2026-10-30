package io.github.longshou.mcyukkuri.intent;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import io.github.longshou.mcyukkuri.MCYukkuri;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.CRC32;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;

/** The desire data ({@code drive/<domain>.json}): names to numbers, each intent id's weight and the domain's own terms, so {@code /reload} retunes what a domain wants. */
public final class DriveWeights extends SimpleJsonResourceReloadListener {
    public static final String DIRECTORY = "drive";
    private static final Gson GSON = new Gson();
    private static volatile Map<String, Map<String, Double>> domains = Map.of();
    private static volatile String fingerprint = print(domains);

    public DriveWeights() { super(GSON, DIRECTORY); }

    /** The weight of {@code intentId} in {@code domain}; 1 when the data names none. */
    public static double of(String domain, String intentId) { return of(domain, intentId, 1); }

    /** The number {@code key} of {@code domain}, or {@code fallback} when the data names none. */
    public static double of(String domain, String key, double fallback) {
        var values = domain == null ? null : domains.get(domain);
        return values == null ? fallback : values.getOrDefault(key, fallback);
    }

    public static String fingerprint() { return fingerprint; }

    @Override protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager manager, ProfilerFiller profiler) {
        var loaded = new TreeMap<String, Map<String, Double>>();
        var ignored = new ArrayList<String>();
        files.forEach((file, json) -> {
            if (!MCYukkuri.MOD_ID.equals(file.getNamespace())) return;
            var values = new TreeMap<String, Double>();
            try {
                for (var field : GsonHelper.convertToJsonObject(json, file.getPath()).entrySet()) {
                    var v = field.getValue();
                    if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber() && Double.isFinite(v.getAsDouble()) && v.getAsDouble() >= 0)
                        values.put(field.getKey(), v.getAsDouble());
                    else ignored.add(file.getPath() + "." + field.getKey());
                }
            } catch (RuntimeException broken) { ignored.add(file.getPath() + " (" + broken + ")"); }
            if (!values.isEmpty()) loaded.put(file.getPath(), Map.copyOf(values));
        });
        domains = Map.copyOf(loaded);
        fingerprint = print(domains);
        if (!ignored.isEmpty()) MCYukkuri.LOGGER.warn("[intent] drive data ignored {}", ignored);
        MCYukkuri.LOGGER.info("[intent] drive data loaded fp={} domains={}", fingerprint, loaded.keySet());
    }

    /** CRC32 over the sorted names and values, so file order never matters. */
    private static String print(Map<String, Map<String, Double>> source) {
        var text = new StringBuilder();
        new TreeMap<>(source).forEach((domain, values) -> {
            text.append(domain).append('{');
            new TreeMap<>(values).forEach((k, v) -> text.append(k).append('=').append(v).append(','));
            text.append('}');
        });
        var crc = new CRC32();
        crc.update(text.toString().getBytes(StandardCharsets.UTF_8));
        return String.format(Locale.ROOT, "%08x", crc.getValue());
    }
}
