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

    static float pitch(YukkuriView owner) {
        return Math.clamp((float) Math.pow(size(owner), -.18), .85f, 1.65f);
    }

    private VoiceScale() {}
}
