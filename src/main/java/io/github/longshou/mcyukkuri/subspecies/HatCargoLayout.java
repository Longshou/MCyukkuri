package io.github.longshou.mcyukkuri.subspecies;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Cargo geometry exported with the heap meshes. Coordinates are in the unsquashed hat frame. */
final class HatCargoLayout {
    private static final JsonObject DATA = read();

    private static JsonObject read() {
        try (var stream = HatCargoLayout.class.getResourceAsStream("/assets/mcyukkuri/yukkuri_presentation/hat_cargo_layout.json")) {
            if (stream == null) throw new IllegalStateException("Missing hat cargo layout");
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException error) { throw new UncheckedIOException(error); }
    }

    static List<HatVessel.ProvisionZone> zones(HatVessel.Mode mode, int tier, boolean aside) {
        if (tier <= 0) return List.of();
        var result = new ArrayList<HatVessel.ProvisionZone>();
        for (var raw : DATA.getAsJsonArray(aside ? "aside_tiers" : "tiers").get(Math.min(3, tier)).getAsJsonArray()) {
            var value = raw.getAsJsonObject();
            double squash = HatVessel.squash(mode);
            result.add(new HatVessel.ProvisionZone(new Vec3(value.get("x").getAsDouble(),
                    HatVessel.top(mode) - value.get("depth").getAsDouble() * squash, value.get("z").getAsDouble()),
                    value.get("radius").getAsDouble(), value.get("height").getAsDouble() * squash));
        }
        return List.copyOf(result);
    }

    private HatCargoLayout() {}
}
