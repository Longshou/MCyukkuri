package io.github.longshou.mcyukkuri.client;

import io.github.longshou.mcyukkuri.entity.YukkuriView;

/** Voice coefficients from the same synchronized size used to draw the body. */
final class VoiceScale {
    private static float size(YukkuriView owner) {
        return Math.clamp(owner.growthScale() * owner.getScale() * owner.profile().modelScale(), .05f, 2f);
    }

    static float volume(YukkuriView owner) {
        return Math.clamp((float) Math.sqrt(size(owner)), .25f, 1.2f);
    }

    /** Stable individual timbre: the same body keeps the same ±3% offset across sessions. */
    private static float individuality(YukkuriView owner) {
        return .97f + Math.floorMod(owner.getUUID().getLeastSignificantBits(), 601L) / 10000f;
    }

    static float pitch(YukkuriView owner) {
        // Clamp the size coefficient first so the individual offset is not flattened at the top of the range.
        float scaled = Math.clamp((float) Math.pow(size(owner), -.18), .85f, 1.65f);
        return scaled * individuality(owner);
    }

    private VoiceScale() {}
}
