package io.github.longshou.mcyukkuri.pose;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Resource-pack corruption is an external boundary; rejection must precede any mutation. */
class PoseBoundaryTest {
    @Test void incompatibleRevisionLeavesModelUntouched(){
        var data=JsonParser.parseString("{\"bones\":[{\"name\":\"root\"}],\"clips\":{}}").getAsJsonObject();
        var before=data.deepCopy();
        var revision=JsonParser.parseString("{\"bone_names\":[\"different_root\"],\"clips\":{\"idle\":{}}}").getAsJsonObject();
        assertFalse(Splice.revision(data,revision));assertEquals(before,data);
        revision.getAsJsonArray("bone_names").add("extra_bone");
        assertFalse(Splice.revision(data,revision));assertEquals(before,data);
    }
    @Test void foreignPlacementHashLeavesModelUntouched(){
        var data=JsonParser.parseString("{\"vertices\":[1,2,3]}").getAsJsonObject();var before=data.deepCopy();
        var fit=JsonParser.parseString("{\"base_sha256\":\"foreign\"}").getAsJsonObject();
        assertFalse(Splice.fit(data,fit,"selected"::equals));assertEquals(before,data);
    }
    @Test void unorderedOrNonFiniteRigFailsAtTheInputBoundary(){
        var bones=JsonParser.parseString("[{\"name\":\"root\",\"parent\":0,\"pivot\":[0,0,0]}]").getAsJsonArray();
        assertThrows(IllegalArgumentException.class,()->Rig.bones(bones));
        bones.get(0).getAsJsonObject().addProperty("parent",-1);
        bones.get(0).getAsJsonObject().getAsJsonArray("pivot").set(0,new com.google.gson.JsonPrimitive(Float.NaN));
        assertThrows(IllegalArgumentException.class,()->Rig.bones(bones));
    }
}
