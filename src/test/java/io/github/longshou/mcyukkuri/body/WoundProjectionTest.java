package io.github.longshou.mcyukkuri.body;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.longshou.mcyukkuri.body.WoundInk.Bary;
import io.github.longshou.mcyukkuri.body.WoundInk.Piece;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class WoundProjectionTest {
    private final WoundSurface mesh = WoundSurface.get("reimu");

    // The expected triangles and UVs come from the original game-only projection.
    @Test void wetBandKeepsSkinTrianglesAndSeam() {
        var marks = new ArrayList<SurfaceMark>();
        SurfaceMark.soleTexture(mesh, SurfaceMark.LEAK_WET_MATERIAL, marks);
        assertEquals(5389, marks.size());
        assertMark(marks.getFirst(), 0, new float[]{.7749732f,.625f,.78087926f,.5725f,.7888466f,.59000003f});
        assertMark(marks.get(2694), 8082, new float[]{.5649689f,.455f,.56976676f,.52f,.5635411f,.52f});
        assertMark(marks.getLast(), 48789, new float[]{.6718915f,.8f,.6685319f,.8f,.66411513f,.8f});
        var seam = marks.stream().filter(mark -> mark.uv()[0] > 1 || mark.uv()[2] > 1 || mark.uv()[4] > 1).toList();
        assertEquals(68, seam.size());
        assertMark(seam.getFirst(), 909, new float[]{1,.52f,1.0067726f,.52f,1.0040573f,.59000003f});

        marks.clear();
        SurfaceMark.supineWetness(mesh, marks);
        assertEquals(5191, marks.size());
        assertMark(marks.get(2595), 11532, new float[]{.43591312f,.6708656f,.42625737f,.68125397f,.43693852f,.68471664f});
    }

    @Test void clippedInkKeepsOriginalFanAndBarycentricCoordinates() {
        var wound = new BodyWound(new UUID(0, 0), WoundKind.SCRATCH, 0, 0, 0, 2, 1, 0, 0);
        var plane = new WoundInk.Plane(mesh, wound, new Vector3f(1), new Vector3f(), 1, 1, null);
        var polygon = List.of(new Piece(-2, .4f, WoundInk.A), new Piece(0, -2, WoundInk.B), new Piece(0, 2, WoundInk.C));
        var ink = plane.ink(polygon);
        assertEquals(3, ink.size());
        var a = new Bary(.5f, .07340668f, .42659333f);
        var b = new Bary(.5f, .5f, 0);
        var c = new Bary(.45567787f, .54432213f, 0);
        var d = new Bary(0, .7265933f, .27340668f);
        var e = new Bary(0, .27340668f, .7265933f);
        assertTriangle(ink.getFirst(), a, b, c);
        assertTriangle(ink.get(1), a, c, d);
        assertTriangle(ink.getLast(), a, d, e);
    }

    @Test void scratchProjectionKeepsOriginalFloatBits() {
        float[] source = new float[32];
        var a = new Vector3f(2.31f,-1.63f,-0f);
        var b = new Vector3f(-6.57f,4.13f,-0f);
        var c = new Vector3f(9.49f,-8.91f,-0f);
        for (int i=0;i<3;i++) {
            source[8+i]=a.get(i);
            source[16+i]=b.get(i);
            source[24+i]=c.get(i);
        }
        var out = new Vector3f();
        for (var bary : List.of(WoundInk.A,WoundInk.B,WoundInk.C,new Bary(.31f,.27f,.42f))) {
            var original = new Vector3f(a).mul(bary.a()).fma(bary.b(),b).fma(bary.c(),c);
            assertEquals(original,bary.point(source,1,out));
        }
    }

    private static void assertTriangle(Bary[] actual, Bary... expected) {
        for (int i = 0; i < 3; i++) {
            var p = actual[i];
            var q = expected[i];
            assertArrayEquals(new float[]{q.a(), q.b(), q.c()}, new float[]{p.a(), p.b(), p.c()}, 1e-6f);
        }
    }

    private static void assertMark(SurfaceMark mark, int triangle, float[] uv) {
        assertEquals(triangle, mark.a().triangle());
        assertEquals(new WoundSurface.Hit(triangle, 1, 0, 0), mark.b());
        assertEquals(new WoundSurface.Hit(triangle, 0, 1, 0), mark.c());
        assertEquals(.027f, mark.offset());
        assertArrayEquals(uv, mark.uv(), 1e-6f);
    }
}
