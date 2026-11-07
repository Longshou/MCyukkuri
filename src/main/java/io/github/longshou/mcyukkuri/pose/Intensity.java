package io.github.longshou.mcyukkuri.pose;

/** Modify sampled channels before Compose; never compose a second skeleton. */
public final class Intensity {
    public static void apply(float[][] pose, float[][] rest, int root, float amount) {
        for (int b = 0; b < pose.length; b++) {
            for (int c = 0; c < 9; c++) {
                if (c == 3 || c == 5) continue;
                pose[b][c] = rest[b][c] + (pose[b][c]-rest[b][c])*amount;
            }
        }
        // Body tilt budget is in degrees in the authored channels, before the shared composition.
        float tilt = Math.abs(pose[root][3]) + Math.abs(pose[root][5]);
        if (tilt > 12) { pose[root][3] *= 12/tilt; pose[root][5] *= 12/tilt; }
    }
    private Intensity() {}
}
