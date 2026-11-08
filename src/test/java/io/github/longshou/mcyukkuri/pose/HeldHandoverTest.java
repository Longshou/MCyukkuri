package io.github.longshou.mcyukkuri.pose;

import com.google.gson.JsonNull;
import org.joml.Matrix4d;
import org.junit.jupiter.api.Test;
import static io.github.longshou.mcyukkuri.pose.PoseMetricsTest.*;
import static org.junit.jupiter.api.Assertions.*;

class HeldHandoverTest {
    @Test void gripMustFollowTheTimedBlendNotEitherEndpointOrAnyPointOnSegment() {
        var f = frame(0);
        var item = json("""
                {"held":true,"id":"stick","place":"MOUTH","grip":[0,0,0],
                 "anchor":[0,0,0],"handover":{"from":[0,0,0],"to":[16,0,0],"share":0.25}}
                """);
        f.add("item", item);
        item.add("drawn", matrix(new Matrix4d().translation(4,0,0)));
        verdict("pass", "held", f);
        var band = result("held", f).getAsJsonObject("band");
        assertEquals(.02, band.get("pass_max").getAsDouble());
        assertEquals(.08, band.get("suspect_max").getAsDouble());
        item.add("drawn", matrix(new Matrix4d().translation(8,0,0)));
        verdict("fail", "held", f); // On the segment, but not at this share.
        item.add("drawn", matrix(new Matrix4d().translation(4,2,0)));
        verdict("fail", "held", f); // 0.125 block off the path.
        item.add("drawn", JsonNull.INSTANCE);
        verdict("fail", "held", f);
        item.add("drawn", matrix(new Matrix4d().translation(16,0,0)));
        var path = item.getAsJsonObject("handover");
        path.add("from", JsonNull.INSTANCE);
        verdict("pass", "held", f); // Same single-endpoint branch as the renderer.
        path.add("from", array(16,0,0)); path.add("to", JsonNull.INSTANCE);
        verdict("pass", "held", f);
        path.addProperty("share", 1.1);
        verdict("error", "held", f);
        path.addProperty("share", .25); path.remove("to");
        verdict("error", "held", f);
        path.add("from", JsonNull.INSTANCE); path.add("to", JsonNull.INSTANCE);
        verdict("error", "held", f);
    }
}
