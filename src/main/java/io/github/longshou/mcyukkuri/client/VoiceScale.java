package io.github.longshou.mcyukkuri.client;

import io.github.longshou.mcyukkuri.entity.YukkuriView;
import io.github.longshou.mcyukkuri.dialogue.VoicePitch;

/** Voice coefficients from the same synchronized size used to draw the body. */
final class VoiceScale {
    private static float body(YukkuriView owner) {
        return owner.growthScale() * owner.getScale() * owner.profile().modelScale();
    }

    static float volume(YukkuriView owner) {
        return Math.clamp((float) Math.sqrt(VoicePitch.size(body(owner))), .25f, 1.2f);
    }

    static float pitch(YukkuriView owner) {
        return VoicePitch.pitch(body(owner), owner.getUUID().getLeastSignificantBits());
    }

    private VoiceScale() {}
}
