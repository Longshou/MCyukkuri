package io.github.longshou.mcyukkuri.body;

import com.google.gson.JsonObject;
import io.github.longshou.mcyukkuri.pose.Splice;

/** Authored placement corrections shared by rendering and server wound visibility. */
public final class ModelPlacementFit {
    private ModelPlacementFit() {}
    public static boolean apply(String id,JsonObject data,JsonObject fit,ModelFingerprint hash) {
        return Splice.fit(data,fit,hash::matches);
    }
}
