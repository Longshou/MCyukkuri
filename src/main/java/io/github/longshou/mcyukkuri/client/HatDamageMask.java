package io.github.longshou.mcyukkuri.client;

import com.google.gson.JsonParser;
import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.headwear.HatDamageGeometry;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;

/**
 * Pixel-like accessory damage (docs/HEADWEAR.md).
 *
 * <p>The complete accessory mesh stays.  Each front/back/top/bottom box face is split into
 * square cells and a few cells are omitted; side faces stay whole.  The holes get a close-colour
 * inner cap sampled from the local cloth and a same-hue rim.  This is deliberately chunkier than
 * the old sphere cuts: MC is a pixel game, and a handful of big square notches reads much better
 * than a field of tiny pinholes.
 *
 * <p>The same rule is used for the worn hat (client/YukkuriModel) and a detached accessory item
 * (client/PropModels).  Shards are one shared cloth patch tinted by the source accessory, so
 * piece 0/1/2 look identical and are easy to manage.
 */
public final class HatDamageMask {
    public static final byte HIDDEN = 0, DRAW = 1, EDGE = 2;
    /** Kept for source compatibility with the old triangle mask; no caller should use it. */
    @Deprecated public static final byte FRAYED = EDGE;

    private static HatDamageGeometry.Pattern pattern=HatDamageGeometry.defaults();
    private static Map<String, int[]> tints = Map.of();
    private static String shard = "accessory_shard";
    private static int generation;
    public static final ResourceManagerReloadListener RELOAD = HatDamageMask::reload;

    private static void reload(ResourceManager resources) {
        var parsed = new HatDamageGeometry.Builder(pattern);
        Map<String, int[]> parsedTints = new HashMap<>();
        String parsedShard = "accessory_shard";
        try (var reader = resources.getResourceOrThrow(MCYukkuri.id("yukkuri_presentation/hat_damage.json")).openAsReader()) {
            var data = JsonParser.parseReader(reader).getAsJsonObject();
            parsed.read(data);
            if (data.has("shard")) parsedShard = data.get("shard").getAsString();
            if (data.has("tints")) for (var entry : data.getAsJsonObject("tints").entrySet()) {
                var row = entry.getValue().getAsJsonArray();
                if (row.size() >= 3) parsedTints.put(entry.getKey(), new int[]{row.get(0).getAsInt(), row.get(1).getAsInt(), row.get(2).getAsInt()});
            }
        } catch (Exception error) {
            MCYukkuri.LOGGER.warn("Accessory damage presentation unavailable: {}", error.toString());
        }
        pattern = parsed.build();
        tints = Map.copyOf(parsedTints);
        shard = parsedShard;
        generation++;
    }

    /** Changes on every resource reload, so cached sub-triangle patterns can be rebuilt. */
    public static int generation() { return generation; }
    public static String shard() { return shard; }
    public static float edgeShade() { return pattern.edgeShade(); }

    /** Tint for the shared shard model.  Longest matching family wins, e.g. marisa_sparse -> marisa. */
    public static int[] tint(String model) {
        int[] exact = tints.get(model);
        if (exact != null) return exact;
        String best = null;
        for (String key : tints.keySet())
            if (!key.equals("fallback") && model.startsWith(key) && (best == null || key.length() > best.length())) best = key;
        if (best != null) return tints.get(best);
        int[] fallback = tints.get("fallback");
        return fallback == null ? new int[]{196, 190, 180} : fallback;
    }

    public static HatDamageGeometry.Pattern pattern(){return pattern;}
    public static int subdivisions(float[] v,int first){return pattern.subdivisions(v,first);}
    public static int cells(float length){return pattern.cells(length);}
    public static byte code(int level,String part,float nx,float ny,float nz){return pattern.code(level,part,nx,ny,nz);}
    public static float[] bounds(float[] v,int[] ranges){return HatDamageGeometry.bounds(v,ranges);}
    private HatDamageMask() {}
}
