package io.github.longshou.mcyukkuri.dialogue;

/** Size coefficient and stable individual timbre of a recorded line. */
public final class VoicePitch {
    public static float size(float scaledBody) { return Math.clamp(scaledBody, .05f, 2f); }

    public static float pitch(float scaledBody, long individual) {
        float scaled = Math.clamp((float) Math.pow(size(scaledBody), -.18), .85f, 1.65f);
        return scaled * (.97f + Math.floorMod(individual, 601L) / 10000f);
    }

    private VoicePitch() {}
}
