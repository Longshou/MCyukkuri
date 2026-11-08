package io.github.longshou.mcyukkuri.pose;

import com.google.gson.*;
import io.github.longshou.mcyukkuri.pose.check.PoseGeometry;
import java.util.*;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.longshou.mcyukkuri.pose.PoseMetricsTest.*;

class PoseCaptureContextTest {
    private JsonObject actor(String id,double x) {
        var f=frame(0);f.addProperty("u",id);f.add("position",array(x,0,0));
        f.getAsJsonObject("sup").add("host",JsonNull.INSTANCE);
        f.add("head",f.get("root"));var meshes=new JsonObject();meshes.add("body",box(x,0,0,4));f.add("meshes",meshes);return f;
    }
    @Test void capturePairsDetectBraidAndPartnerHatWithoutInventingMissingGeometry() {
        var a=actor("a",0);var b=actor("b",4);
        a.getAsJsonObject("meshes").add("braid",box(1,1,1,1));
        b.getAsJsonObject("meshes").add("hat",box(1,1,1,1));
        PoseGeometry.context(a,Map.of("a",a,"b",b));
        assertEquals(2,a.getAsJsonArray("pairs").size());verdict("fail","pass",a);
        assertFalse(a.getAsJsonArray("skin_points").isEmpty());
        a.remove("pairs");verdict("error","pass",a);
    }
    @Test void carrierSupportAndMissingHost() {
        var a=actor("child",0);var b=actor("parent",.5);
        b.getAsJsonObject("meshes").add("body",box(.5,.5,.5,4));
        a.getAsJsonObject("sup").addProperty("kind","carrier");a.getAsJsonObject("sup").addProperty("host","parent");
        PoseGeometry.context(a,Map.of("child",a,"parent",b));
        assertEquals(b.get("head"),a.getAsJsonObject("sup").get("frame"));
        assertTrue(a.getAsJsonObject("sup").has("mesh"));verdict("fail","pass",a);
        var missing=actor("child",0);missing.getAsJsonObject("sup").addProperty("kind","carrier");missing.getAsJsonObject("sup").addProperty("host","missing");
        PoseGeometry.context(missing,Map.of("child",missing));verdict("error","gap",missing);verdict("error","tilt",missing);
    }
    @Test void rootRingDoesNotTransformTwiceAndEmptyScopeIsNotMissingContext() {
        var meshes=new JsonObject();var triangles=box(0,0,0,1).getAsJsonArray("triangles");
        PoseGeometry.append(meshes,"braid",triangles,false);PoseGeometry.append(meshes,"root_ring",triangles,false);
        PoseGeometry.transform(meshes,new Matrix4f().translation(10,0,0));
        assertEquals(meshes.get("braid"),meshes.get("root_ring"));
        assertEquals(10,PoseGeometry.points(meshes.getAsJsonObject("braid")).get(0).getAsJsonArray().get(0).getAsFloat());
        var a=actor("a",0);PoseGeometry.context(a,Map.of("a",a));verdict("skipped","pass",a);
        a.addProperty("geometry_error","damaged surface not sampled");verdict("error","pass",a);
    }
    @Test void slowConsecutiveRenderedFramesAreTimeNormalizedButMissingFramesAreErrors() {
        var a=frame(0);var b=frame(4);a.addProperty("f",0);b.addProperty("f",1);
        verdict("pass","posejump",a,b);
        b.addProperty("f",2);verdict("error","posejump",a,b);
        b.addProperty("transition",true);verdict("skipped","posejump",a,b);
    }
}
