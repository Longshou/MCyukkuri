package io.github.longshou.mcyukkuri.motor;

/** The clip an action plays, when it started (clip phase) and whether it loops for the action's whole life. */
public record MotionPose(String clip, long startTick, boolean loop) {
    /** For an action that animates nothing. */
    public static final MotionPose NONE = new MotionPose("", 0L, false);

    public MotionPose {
        clip = clip == null ? "" : clip;
    }
}
