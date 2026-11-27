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

    @Test void partnerFaceLetsLoosePartsGiveWayAtTheCheekSeam() {
        var face = WallYield.partnerFace(1, 0, .94, .48, .49, -.1f, 1);
        solve(face);
        assertTrue(wing.turned());
        var tip = new Vector3f();
        skin(2, tip);
        wing.apply(2, tip);
        blocks.transformPosition(tip);
        float tuck = WallYield.press(List.of(face), tip.x, tip.y, tip.z);
        assertTrue(tip.x * tuck <= .45);
        assertEquals(1, WallYield.press(List.of(face), .8f, 1.1f, 0));
        assertFalse(WallYield.loose("rounded_body_surface"));
        assertFalse(WallYield.loose("face_eye_left"));
        assertFalse(WallYield.loose("mamu_left"));
    }

    @Test void rigidHeadwearGivesWayToWallsButKeepsItsBrimBesideAnotherBody() {
        var wall = new WallYield.Face(1, 0, .4f, 0, 1);
        var body = WallYield.partnerFace(1, 0, .4f, .3, .3, 0, 1);
        assertTrue(wall.wall());
        assertFalse(body.wall());
        // The brim beside another yukkuri keeps its full width (its texture is never dragged); a real wall still presses it.
        assertEquals(1, WallYield.press(List.of(body), 1, .5f, 0, true), 1e-6);
        assertEquals(.4f, WallYield.press(List.of(wall, body), 1, .5f, 0, true), 1e-6);
        // Soft parts still give way to that body.
        assertEquals(body.distance(), WallYield.press(List.of(body), 1, .5f, 0), 1e-6);
    }

    @Test void turningAndSeparatingPartnersYieldTowardEachOtherUntilTheirLoosePartsClear() {
        var adult = new WallYield.Bounds(.95, -.25, 1.2);
        var young = new WallYield.Bounds(.4, -.1, .55);
        for (double distance : new double[]{1.3, .65, .7, 1.34}) {
            var a = WallYield.passingFace(.6 * distance, .125, .8 * distance, .5, .2, adult, young);
            var b = WallYield.passingFace(-.6 * distance, -.125, -.8 * distance, .2, .5, young, adult);
            assertEquals(.6, a.x(), 1e-6);
            assertEquals(-.8, b.z(), 1e-6);
            assertTrue(a.distance() + b.distance() < distance);
            assertEquals(a.high(), b.high() + .125, 1e-6);
        }
        assertNull(WallYield.passingFace(1.36, 0, 0, .5, .2, adult, young));
        assertNull(WallYield.passingFace(.65, 1.4, 0, .5, .2, adult, young));
        // A turning adult's posed skin projects past its walking collision radius.
        var face = WallYield.passingFace(.635, 0, 0, .19, .51, young, adult);
        assertTrue(face.distance() < .635 - .51);
        assertTrue(.3 * WallYield.press(List.of(face), .3f, .25f, 0) < .635 - .51);
    }
}
