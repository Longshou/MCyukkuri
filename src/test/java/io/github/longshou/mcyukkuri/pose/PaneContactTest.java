package io.github.longshou.mcyukkuri.pose;

import com.google.gson.*;
import io.github.longshou.mcyukkuri.motor.geom.RamContact;
import io.github.longshou.mcyukkuri.pose.cli.PoseCli;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PaneContactTest {
    @Test void brimAndTurnUseSkinnedPartsAndAuthoredImpact() throws Exception {
        var contact=RamContact.of("marisa");
        assertTrue(contact.turn()>contact.impact(),"turning the wide brim needs more room than a frontal ram");
        assertEquals(contact.impact(),contact.distance(contact.strike()),1e-5);
        assertEquals(contact.turn(),contact.distance(contact.settled()),1e-5);
        var assets=Path.of(getClass().getResource("/assets/mcyukkuri/yukkuri/marisa.json").toURI()).getParent().getParent();
        var request=JsonParser.parseString("""
                {"model":"marisa","clip":"lunge_ram","t":"0.52","metric":["plane"],
                 "context":{"plane":{"normal":[0,0,1],"offset":0}}}
                """).getAsJsonObject();
        request.getAsJsonObject("context").getAsJsonObject("plane").addProperty("offset",-contact.impact());
        var result=PoseCli.run(assets,request).getAsJsonArray("results").get(0).getAsJsonObject();
        assertEquals("pass",result.get("verdict").getAsString(),result.toString());
        assertEquals(0,result.get("clearance").getAsDouble(),1e-5);
        // Deliberately place the same real pose one model unit through the pane.
        request.getAsJsonObject("context").getAsJsonObject("plane").addProperty("offset",1-contact.impact());
        result=PoseCli.run(assets,request).getAsJsonArray("results").get(0).getAsJsonObject();
        assertEquals("fail",result.get("verdict").getAsString());
        assertEquals(1,result.get("value").getAsDouble(),1e-5);
    }
    @Test void planeRejectsMissingMeshesAndChecksTheHat() {
        var f=PoseMetricsTest.frame(0);f.add("plane",PoseMetricsTest.json("{\"normal\":[0,0,1],\"offset\":0}"));
        PoseMetricsTest.verdict("error","plane",f);
        var meshes=new JsonObject();f.add("meshes",meshes);
        meshes.add("body",PoseMetricsTest.box(0,0,1,1));
        meshes.add("hat",PoseMetricsTest.box(0,1,-.2,1));
        PoseMetricsTest.verdict("fail","plane",f);
        meshes.add("hat",PoseMetricsTest.box(0,1,0,1));
        meshes.add("partner/hat",PoseMetricsTest.box(0,1,-2,1));
        meshes.add("partner.hat",PoseMetricsTest.box(0,1,-2,1));
        PoseMetricsTest.verdict("pass","plane",f);
    }
}
