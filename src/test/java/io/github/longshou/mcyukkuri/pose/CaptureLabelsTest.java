package io.github.longshou.mcyukkuri.pose;

import com.google.gson.*;
import io.github.longshou.mcyukkuri.pose.check.Geometry;
import io.github.longshou.mcyukkuri.pose.check.PoseChecks;
import java.nio.file.*;
import java.util.*;
import org.joml.Matrix4d;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.longshou.mcyukkuri.pose.PoseMetricsTest.*;

/** Small measured inputs; unresolved labels are coverage gaps, never clean controls. */
class CaptureLabelsTest {
    private static List<JsonObject> labels(String metric) throws Exception {
        var path=Path.of("scripts/fixtures/capture_readout/labels",metric+".json");
        assertTrue(Files.size(path)<200_000,"fixture exceeds the agreed small-file budget");
        var rows=new ArrayList<JsonObject>();
        for(var raw:JsonParser.parseString(Files.readString(path)).getAsJsonArray())rows.add(raw.getAsJsonObject());
        return rows;
    }
    private static JsonObject replay(JsonObject row) {
        String metric=row.get("metric").getAsString();
        if(metric.equals("gap")) {
            var input=row.getAsJsonObject("inputs");double low=input.get("lowest_signed").getAsDouble();
            double value=input.has("contact_distance")?Geometry.supportedGap(low,input.get("contact_distance").getAsDouble()):low;
            return PoseChecks.gapReading(input.getAsJsonObject("frame"),value);
        }
        var frames=new ArrayList<JsonObject>();
        for(var raw:row.getAsJsonArray("inputs"))frames.add(raw.getAsJsonObject());
        var results=PoseChecks.evaluate(frames,List.of(metric));
        int index=row.has("result_index")?row.get("result_index").getAsInt():results.size()-1;
        return results.get(index).getAsJsonObject();
    }
    private static boolean alarm(JsonObject result) {
        return List.of("suspect","fail").contains(result.get("verdict").getAsString());
    }
    private static void check(JsonObject row) {
        var result=replay(row);String label=row.get("label").getAsString();
        assertNotEquals("error",result.get("verdict").getAsString(),result.toString());
        if(label.equals("unresolved"))return;
        assertEquals(label.equals("real"),alarm(result),row.get("view")+" frame "+row.get("frame")+": "+result);
    }
    @Test void replayReviewedLabelsAndReportUnresolvedSeparately() throws Exception {
        for(String metric:List.of("gap","yaw","posejump")) {
            var rows=labels(metric);var scenes=new HashSet<String>();int captured=0,real=0,fp=0,unknown=0;
            for(var row:rows) {
                assertFalse(row.get("reason").getAsString().isBlank());
                if(!row.get("capture").getAsString().equals("F")) {captured++;scenes.add(row.get("view").getAsString());}
                check(row);
                switch(row.get("label").getAsString()) {
                    case "real" -> real++;
                    case "false" -> {if(alarm(replay(row)))fp++;}
                    case "unresolved" -> unknown++;
                    default -> fail("invalid label");
                }
            }
            assertTrue(captured>=30);assertTrue(scenes.size()>=4);
            System.out.printf("LABEL %s: captured=%d scenes=%d real=%d false-alarms=%d unresolved=%d%n",metric,captured,scenes.size(),real,fp,unknown);
        }
    }
    @Test void deliberateRegressionPerMetricIsCaughtByLabels() throws Exception {
        for(String metric:List.of("gap","yaw","posejump")) {
            var broken=labels(metric).stream().filter(r->r.get("label").getAsString().equals("false")
                    && r.get("verdict").getAsString().matches("suspect|fail")).findFirst().orElseThrow().deepCopy();
            switch(metric) {
                case "gap" -> broken.getAsJsonObject("inputs").remove("contact_distance");
                case "yaw" -> broken.getAsJsonArray("inputs").get(0).getAsJsonObject().getAsJsonObject("server").remove("window");
                case "posejump" -> broken.getAsJsonArray("inputs").get(1).getAsJsonObject().remove("authored_loop");
            }
            assertThrows(AssertionError.class,()->check(broken),metric);
            System.out.println("MUTATION caught: "+metric);
        }
    }
    @Test void rimContactUsesBodySurfaceAndDoesNotErasePenetration() {
        var f=frame(0);var support=surface();
        support.getAsJsonArray("triangles").add(JsonParser.parseString("[[3,3,-1],[5,3,-1],[4,3,1]]"));
        f.getAsJsonObject("sup").add("mesh",support);
        f.add("skin_points",JsonParser.parseString("[[0,2,0],[4,3,0]]"));
        verdict("pass","gap",f); // lowest point is above the bowl, another point rests on its rim
        f.add("skin_points",JsonParser.parseString("[[0,-2,0],[4,3,0]]"));
        verdict("fail","gap",f); // contact cannot excuse penetration under the support
    }
    @Test void interpolationWindowExpiresAndCannotBridgeMissingTicks() {
        var frames=new ArrayList<JsonObject>();
        for(int t=0;t<12;t++) {
            var f=frame(t);f.addProperty("yaw",50);
            var server=f.getAsJsonObject("server");var window=new JsonArray();
            for(int k=t-4;k<=t;k++){var sample=new JsonObject();sample.addProperty("t",k);sample.addProperty("by",k<0?50:0);window.add(sample);}
            server.add("window",window);frames.add(f);
        }
        verdict("pass","yaw",frames.getFirst());
        verdict("fail","yaw",frames.toArray(JsonObject[]::new));
        frames.getLast().getAsJsonObject("server").getAsJsonArray("window").remove(0);
        verdict("error","yaw",frames.getLast());
    }
    @Test void discontinuousAuthoredSeamAndAdditionalRuntimeJumpRemainAlarms() throws Exception {
        var sample=labels("posejump").stream().filter(r->r.get("label").getAsString().equals("false")
                &&r.get("verdict").getAsString().equals("suspect")).findFirst().orElseThrow();
        var f=sample.deepCopy();var current=f.getAsJsonArray("inputs").get(1).getAsJsonObject();
        String bone=current.getAsJsonObject("bones").keySet().iterator().next();
        var actual=PoseChecks.matrix(current.getAsJsonObject("bones").get(bone)).rotateX(Math.PI);
        current.getAsJsonObject("bones").add(bone,matrix(actual));
        assertTrue(alarm(replay(f)),"a broken runtime pose must survive authored subtraction");
        f=sample.deepCopy();current=f.getAsJsonArray("inputs").get(1).getAsJsonObject();
        current.getAsJsonObject("authored_loop").getAsJsonObject("end").add(bone,matrix(new Matrix4d().rotateX(Math.PI/2)));
        assertTrue(alarm(replay(f)),"a discontinuous authored seam must not enable subtraction");
        current.getAsJsonObject("authored_loop").addProperty("fp","different-assets");
        assertEquals("error",replay(f).get("verdict").getAsString());
    }
}
