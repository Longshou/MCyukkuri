package io.github.longshou.mcyukkuri.life;

import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NestLootTest {
    @Test void onlyTheOwningQuarterDropsTheSalvagedMaterials() throws Exception {
        for(String nest:new String[]{"large_yukkuri_nest","large_wild_nest"}){
            try(var in=new InputStreamReader(getClass().getResourceAsStream(
                    "/data/mcyukkuri/loot_table/blocks/"+nest+".json"),StandardCharsets.UTF_8)){
                var pools=JsonParser.parseReader(in).getAsJsonObject().getAsJsonArray("pools");
                var materials=new LinkedHashMap<String,Integer>();
                for(var raw:pools){
                    var pool=raw.getAsJsonObject();
                    var condition=pool.getAsJsonArray("conditions").asList().stream().map(e->e.getAsJsonObject())
                            .filter(c->c.get("condition").getAsString().equals("minecraft:block_state_property")).findFirst().orElseThrow();
                    assertEquals("mcyukkuri:"+nest,condition.get("block").getAsString());
                    assertEquals("0",condition.getAsJsonObject("properties").get("part").getAsString());
                    var item=pool.getAsJsonArray("entries").get(0).getAsJsonObject();
                    int count=item.has("functions")?item.getAsJsonArray("functions").get(0).getAsJsonObject().get("count").getAsInt():1;
                    materials.merge(item.get("name").getAsString(),count,Integer::sum);
                }
                assertEquals(nest.equals("large_wild_nest")?Map.of("minecraft:short_grass",1):
                        Map.of("mcyukkuri:yukkuri_nest",2,"minecraft:white_wool",1),materials);
            }
        }
    }
}
