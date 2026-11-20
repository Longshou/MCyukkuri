package io.github.longshou.mcyukkuri.headwear;

import com.google.gson.JsonObject;
import io.github.longshou.mcyukkuri.motor.geom.GeometryJson;

/** The authored worn-hat motion and contact shapes. */
public final class WornHatGeometry {
    static final JsonObject DATA = GeometryJson.read("yukkuri_presentation/hat_interactions.json").data();

    public static JsonObject worn(String assetId) {
        return DATA.getAsJsonObject("variants").getAsJsonObject(assetId);
    }

    private WornHatGeometry() {}
}
