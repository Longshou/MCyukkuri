package io.github.longshou.mcyukkuri.pose;

import java.util.Arrays;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Per-draw broad phase for the fitted hat/body and hat/hair triangle pairs. */
public final class ContactBounds {
    public static final int BODY = 0, HAIR = 1, HAT = 2;
    private final float[] low = new float[9], high = new float[9];
    private final Vector3f min = new Vector3f(), max = new Vector3f();

    /** Only a similarity preserves fitted depths when the common bone frame rotates. */
    public static boolean uniformFrame(Matrix4f m) {
        float x = m.m00()*m.m00() + m.m01()*m.m01() + m.m02()*m.m02();
        float y = m.m10()*m.m10() + m.m11()*m.m11() + m.m12()*m.m12();
        float z = m.m20()*m.m20() + m.m21()*m.m21() + m.m22()*m.m22();
        float rounding = 8 * Math.ulp(Math.max(x, Math.max(y, z)));
        return Math.abs(x-y) <= rounding && Math.abs(x-z) <= rounding
                && Math.abs(m.m00()*m.m10() + m.m01()*m.m11() + m.m02()*m.m12()) <= rounding
                && Math.abs(m.m00()*m.m20() + m.m01()*m.m21() + m.m02()*m.m22()) <= rounding
                && Math.abs(m.m10()*m.m20() + m.m11()*m.m21() + m.m12()*m.m22()) <= rounding;
    }

    public void clear() {
        Arrays.fill(low, Float.POSITIVE_INFINITY);
        Arrays.fill(high, Float.NEGATIVE_INFINITY);
    }

    public void add(int group, Vector3f point) {
        int i = group * 3;
        low[i] = Math.min(low[i], point.x); high[i] = Math.max(high[i], point.x);
        low[i+1] = Math.min(low[i+1], point.y); high[i+1] = Math.max(high[i+1], point.y);
        low[i+2] = Math.min(low[i+2], point.z); high[i+2] = Math.max(high[i+2], point.z);
    }

    public void place(Matrix4f frame) {
        for (int i = 0; i < low.length; i += 3) {
            if (low[i] > high[i]) continue;
            min.set(low[i], low[i+1], low[i+2]);
            max.set(high[i], high[i+1], high[i+2]);
            frame.transformAab(min, max, min, max);
            low[i] = Math.nextDown(min.x); low[i+1] = Math.nextDown(min.y); low[i+2] = Math.nextDown(min.z);
            high[i] = Math.nextUp(max.x); high[i+1] = Math.nextUp(max.y); high[i+2] = Math.nextUp(max.z);
        }
    }

    public boolean separate(ContactBounds other) {
        return separate(HAT, other, BODY) && separate(HAT, other, HAIR)
                && separate(BODY, other, HAT) && separate(HAIR, other, HAT);
    }

    private boolean separate(int a, ContactBounds other, int b) {
        for (int k = 0; k < 3; k++) {
            int i = a * 3 + k, j = b * 3 + k;
            if (high[i] < other.low[j] || other.high[j] < low[i]) return true;
        }
        return false;
    }
}
