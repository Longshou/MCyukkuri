package io.github.longshou.mcyukkuri.pose.cli;

import io.github.longshou.mcyukkuri.pose.DishFit;
import java.nio.file.Path;
import org.joml.Intersectionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DishFitTest {
    private static final Path ASSETS = Path.of("src/main/resources/assets/mcyukkuri");

    @Test void cornerFitsBothTheSoleAndTheJar() {
        assertTrue(DishFit.stand(.929, .78, .76).perched());
        assertFalse(DishFit.stand(3.2, 2.688, 2.61).perched());
        assertFalse(DishFit.stand(2.51, 1.4, 2).perched());
        assertFalse(DishFit.stand(2, 1.51, 2).perched());
    }

    @Test void actualLipMeetsBothRimsThroughTheClipAtEachSize() throws Exception {
        var model = new OfflineModel(ASSETS, "alice");
        var part = model.data.getAsJsonArray("parts").asList().stream().map(p -> p.getAsJsonObject())
                .filter(p -> p.get("name").getAsString().equals("face_mouth")).findFirst().orElseThrow();
        int start = part.get("start").getAsInt(), end = start + part.get("count").getAsInt();
        for (String clip : new String[]{"drink", "eat"}) {
            boolean drink = clip.equals("drink");
            int rim = drink ? 2 : 3;
            int firstContact = DishFit.firstContact(drink ? 32 : 24, drink);
            for (float scale : new float[]{.145f, .18f, .27f, .28f, .3f, .32f, .35f, .4f, .5f, 1f, 1.2f}) {
                var stand = DishFit.stand(6.4 * scale, 5.376 * scale, -model.mouth.z * scale);
                double reach = Math.hypot(stand.lipX() - stand.x(), stand.lipZ() - stand.z());
                var target = new Vector3f(0, stand.perched() ? 0 : rim / scale, (float)(-reach / scale));
                // Dry tiles at the clipped corner, relative to the centre of its middle tile.
                int[][] ceramicTiles = {{0, 0}, {-1, 1}, {1, -1}, {-2, 1}, {1, -2}};
                var ceramic = new Vector3f[ceramicTiles.length * 4];
                float yaw = (float) Math.atan2(stand.x() - stand.lipX(), stand.z() - stand.lipZ());
                for (int tile = 0; tile < ceramicTiles.length; tile++) {
                    int x = ceramicTiles[tile][0], z = ceramicTiles[tile][1];
                    for (int i = 0; i < 4; i++)
                        ceramic[tile * 4 + i] = new Vector3f(x + i % 2 - .5f, 0, z + i / 2 - .5f).rotateY(-yaw).div(scale);
                }
                for (float tick = 0; tick <= 32; tick += .5f) {
                    model.sample(clip, tick / 20f, null);
                    var fit = new DishFit();
                    fit.begin(target, model.mouth(), 1 / scale, Math.min(1, tick / firstContact));
                    var lip = new Vector3f[end - start];
                    for (int i = start; i < end; i++) lip[i - start] = model.skin(i);
                    for (int i = 0; i < lip.length; i += 3) {
                        if (model.vertices[(start + i) * 8 + 7] < -.15f)
                            fit.lipTriangle(lip[i], lip[i+1], lip[i+2]);
                    }
                    for (int i = 0; i < model.floor.size(); i += 3)
                        fit.rimTriangle(model.skin(model.floor.get(i)),model.skin(model.floor.get(i+1)),model.skin(model.floor.get(i+2)));
                    for (int i = start; i < end; i += 3) fit.rimTriangle(model.skin(i),model.skin(i+1),model.skin(i+2));
                    for (int i : model.floor) fit.sole(model.skin(i));
                    for (var p : lip) fit.press(p);
                    var p = new Vector3f();
                    float gap = Float.POSITIVE_INFINITY;
                    for (int i = 0; i < lip.length; i += 3) {
                        if (model.vertices[(start + i) * 8 + 7] >= -.15f) continue;
                        Intersectionf.findClosestPointOnTriangle(lip[i], lip[i+1], lip[i+2], target, p);
                        gap = Math.min(gap, p.distance(target));
                    }
                    if (stand.perched() || tick >= firstContact)
                        assertTrue(gap * scale < .001, clip + " scale=" + scale + " tick=" + tick + " gap=" + gap * scale);
                    float sole = Float.POSITIVE_INFINITY;
                    float ceramicGap = Float.POSITIVE_INFINITY;
                    for (int i = 0; i < model.floor.size(); i += 3) {
                        var corners = new Vector3f[]{model.skin(model.floor.get(i)), model.skin(model.floor.get(i+1)), model.skin(model.floor.get(i+2))};
                        for (var corner : corners) fit.press(corner);
                        if (stand.perched()) {
                            for (var foot : ceramic) {
                                Intersectionf.findClosestPointOnTriangle(corners[0], corners[1], corners[2], foot, p);
                                ceramicGap = Math.min(ceramicGap, p.distance(foot) * scale);
                            }
                            for (var corner : corners) {
                                assertTrue(Math.hypot(corner.x, corner.z) * scale < Math.sqrt(2) * (stand.x() - 3));
                                p.set(corner).rotateY(yaw).mul(scale);
                                for (var tile : ceramicTiles) ceramicGap = Math.min(ceramicGap, p.distance(
                                        Math.clamp(p.x, tile[0] - .5f, tile[0] + .5f), 0, Math.clamp(p.z, tile[1] - .5f, tile[1] + .5f)));
                            }
                        }
                        float outer = target.z + 1 / scale;
                        for (int j = 0; j < 3; j++) {
                            var a = corners[j]; var b = corners[(j+1)%3];
                            sole = Math.min(sole,a.y);
                            assertTrue(a.y >= 0 && (a.y >= target.y || a.z >= outer));
                            if (a.z < outer && b.z > outer) {
                                float y = a.y + (b.y-a.y)*(outer-a.z)/(b.z-a.z);
                                assertTrue(y >= target.y - .001, clip + " scale=" + scale + " tick=" + tick + " body through rim=" + (target.y-y)*scale);
                            }
                        }
                    }
                    assertTrue(sole * scale < .01, "ungrounded sole " + sole * scale);
                    if (stand.perched()) assertTrue(ceramicGap < .02,
                            clip + " scale=" + scale + " tick=" + tick + " gap over ceramic=" + ceramicGap);
                }
            }
        }
    }
}
