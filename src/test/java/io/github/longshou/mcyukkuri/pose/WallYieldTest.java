package io.github.longshou.mcyukkuri.pose;

import java.util.List;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WallYieldTest {
    private static final Rig.Bone[] BONES = {new Rig.Bone("root", -1, 0, 0, 0), new Rig.Bone("predator_wing_right", 0, 5.8f, 6.8f, 5.4f)};
    /** A right wing as a flat strip from its root to 13 model units out, one triangle fan bound to the wing bone. */
    private static final float[][] WING = {{5.8f, 6.8f, 5.4f}, {9, 7.6f, 5.6f}, {13, 6.8f, 6.2f}, {5.8f, 6.8f, 5.4f}, {13, 6.8f, 6.2f}, {9, 5.6f, 5.6f}};
    private final float[] rest = new float[WING.length * 8];
    private final Skin.Binding[] bindings = new Skin.Binding[WING.length];
    private final Matrix4f[] world = {new Matrix4f(), new Matrix4f()};
    private final Matrix4f blocks = new Matrix4f().scale(1 / 16f);
    private final WallYield wing;

    WallYieldTest() {
        for (int i = 0; i < WING.length; i++) { System.arraycopy(WING[i], 0, rest, i * 8, 3); bindings[i] = new Skin.Binding(1, 1, 0); }
        wing = WallYield.of("remilia", BONES, bindings, rest, new String[]{"predator_wing_ridge_right_0"}, new int[]{0}, new int[]{WING.length});
    }
    private void skin(int index, Vector3f target) { target.set(rest[index * 8], rest[index * 8 + 1], rest[index * 8 + 2]); }
    /** Farthest reach toward +x in blocks after this frame's turn. */
    private float reach() {
        float reach = 0;
        var point = new Vector3f();
        for (int i = 0; i < WING.length; i++) { skin(i, point); wing.apply(i, point); reach = Math.max(reach, point.x / 16); }
        return reach;
    }
    private void solve(WallYield.Face face) { wing.solve(this::skin, world, blocks, List.of(face), kind -> false); }

    @Test void wingTurnsOnlyAsFarAsTheWallAsks() {
        solve(new WallYield.Face(1, 0, .9f, 0, 2));
        assertFalse(wing.turned(), "a wall beyond the wing tip leaves it as posed");
        solve(new WallYield.Face(1, 0, .7f, 0, 2));
        assertTrue(wing.turned());
        float near = reach();
        assertEquals(.7f, near, .01f, "the wing stops at the face, not short of it");
        solve(new WallYield.Face(1, 0, .5f, 0, 2));
        assertTrue(reach() <= .5f + .005f, "a closer wall folds it further: " + reach());
    }
    @Test void wingKeepsItsPoseForFacesItCannotClearOrDoesNotMeet() {
        solve(new WallYield.Face(0, 1, .3f, 0, 2));
        assertFalse(wing.turned(), "drooping does not clear a wall behind the root");
        solve(new WallYield.Face(1, 0, .5f, 0, .2f));
        assertFalse(wing.turned(), "a low kerb beside the body is below the spread wing");
        solve(new WallYield.Face(1, 0, .5f, 0, 2));
        wing.solve(this::skin, world, blocks, List.of(new WallYield.Face(1, 0, .5f, 0, 2)), kind -> kind.equals("wing"));
        assertFalse(wing.turned(), "a busy limb is left to the press");
    }
    @Test void wingUnwindsSmoothlyPastRearQuarterWall() {
        var point = new Vector3f();
        float previous = Float.NaN;
        for (int yaw = 60; yaw <= 85; yaw++) {
            var toWall = new Matrix4f().rotateY((float)Math.toRadians(yaw)).scale(1 / 16f);
            wing.solve(this::skin, world, toWall, List.of(new WallYield.Face(1, 0, .55f, 0, 2)), kind -> false);
            skin(2, point);
            wing.apply(2, point);
            if (!Float.isNaN(previous))
                assertTrue(Math.abs(point.y - previous) < 1.2f, "wing tip y jumped at yaw " + yaw + ": " + previous + " -> " + point.y);
            previous = point.y;
        }
    }
    @Test void pressKeepsPointsOnTheFreeSideAtTheirHeight() {
        var faces = List.of(new WallYield.Face(1, 0, .5f, 0, 1));
        assertEquals(.5f, WallYield.press(faces, 1, .5f, 0), 1e-6);
        assertEquals(1, WallYield.press(faces, .4f, .5f, 0), 1e-6);
        assertEquals(1, WallYield.press(faces, 1, 1.2f, 0), 1e-6, "above the wall's top");
        assertTrue(WallYield.loose("braid_lock_core_1") && WallYield.loose("predator_wing_ridge_right_0"));
        assertFalse(WallYield.loose("rounded_body_surface") || WallYield.loose("face_eye_left") || WallYield.loose("rounded_rump_mark"));
    }
}
