package io.github.longshou.mcyukkuri.subspecies;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import net.minecraft.world.phys.Vec3;

/** Marisa's own hat turned upside down, its soft crown pressed down (interactions/marisa-hat-vessel, hat_vessel.json):
 *  on the ground until it stands on the squashed crown, afloat a little; its size, the young's places and where a catcher
 *  sets it. Model units, 16 per block. */
public final class HatVessel {
    private static final JsonObject DATA=read();
    private static JsonObject read(){
        try(var stream=HatVessel.class.getResourceAsStream("/assets/mcyukkuri/yukkuri_presentation/hat_vessel.json")){
            if(stream==null)throw new IllegalStateException("Missing hat vessel metrics");
            return com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(stream,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        }catch(java.io.IOException ex){throw new java.io.UncheckedIOException(ex);}
    }
    /** On dry ground (NEST: the crown pressed flat under it) or afloat (a boat, or floated at a bank). */
    public enum Mode { NEST, FLOAT }
    public enum Role { HELPER, SELF }
    public static final float AQUATIC_SCALE=DATA.get("aquatic_scale").getAsFloat();
    private static final JsonObject BOWL=DATA.getAsJsonObject("bowl");
    public static final float FREEBOARD=BOWL.get("freeboard").getAsFloat();
    /** Where the owner rests afloat (on the ledge inside the opening), above the water line. */
    public static final float OWNER_Y=BOWL.get("owner_y").getAsFloat();
    /** The hat turned over: the opening (x0, x1, z0, z1) and the brim's half extents (X across, Z along). */
    public static final float[] OPENING=floats(DATA.getAsJsonArray("opening")), BRIM=floats(DATA.getAsJsonArray("brim"));
    /** How far the crown is pressed along the hat's axis: on the ground (the brim then stands NEST_HEIGHT up) and afloat. */
    public static final float SQUASH_NEST=DATA.getAsJsonObject("squash").get("nest").getAsFloat(), SQUASH_FLOAT=DATA.getAsJsonObject("squash").get("bowl").getAsFloat();
    public static final float NEST_HEIGHT=DATA.getAsJsonObject("nest").get("height").getAsFloat();
    private static final float[] ANCHOR=floats(DATA.getAsJsonArray("anchor_worn"));
    private static final float[][] MASK=points(BOWL.getAsJsonArray("mask"));
    private static float[] floats(JsonArray a){float[] out=new float[a.size()];for(int i=0;i<out.length;i++)out[i]=a.get(i).getAsFloat();return out;}
    private static float[][] points(JsonArray a){float[][] out=new float[a.size()][];for(int i=0;i<out.length;i++)out[i]=floats(a.get(i).getAsJsonArray());return out;}
    /** The worn hat's seat on the head: the larger aquatic hat scales about it. */
    public static org.joml.Vector3f anchor(){return new org.joml.Vector3f(ANCHOR[0],ANCHOR[1],ANCHOR[2]);}
    /** The opening at the water line (x, z pairs), where the water is hidden. */
    public static float[][] mask(){return MASK;}
    public static boolean aquatic(YukkuriEntity y){return y.subspecies().kind()==SubspeciesLife.Kind.AQUATIC_MARISA;}
    /** The size of this Marisa's hat relative to the worn standard. */
    public static float hatScale(YukkuriEntity y){return y.growthScale()*(aquatic(y)?AQUATIC_SCALE:1);}
    private static Vec3 seat(String list,int seat){
        var seats=DATA.getAsJsonObject("seats").getAsJsonArray(list);var s=seats.get(Math.floorMod(seat,seats.size())).getAsJsonArray();
        return new Vec3(s.get(0).getAsDouble(),s.get(1).getAsDouble(),s.get(2).getAsDouble());
    }
    /** Young on the floor of the pressed hat on the ground (origin on the ground). */
    public static Vec3 nestSeat(int seat){return seat("nest",seat);}
    /** Young on the floor inside the hat afloat (origin at the water line): by day before the owner, at night under her. */
    public static Vec3 boatSeat(int seat,boolean night){return seat(night?"night":"day",seat);}
    /** Where a catcher's braid clips set the hat, relative to the catcher, and where it lies before the mother. */
    public record Placement(Mode mode,Vec3 offset,float yaw,Vec3 hatInMother,float hatYawInMother){}
    public static Placement placement(boolean aquatic,Role role){
        var p=DATA.getAsJsonObject("placements").getAsJsonObject(aquatic?"aquatic":"land").getAsJsonObject(role==Role.HELPER?"helper":"self");
        var o=p.getAsJsonArray("offset");var h=p.getAsJsonObject("hat_in_mother");var a=h.getAsJsonArray("origin");
        return new Placement("bowl".equals(p.get("mode").getAsString())?Mode.FLOAT:Mode.NEST,
                new Vec3(o.get(0).getAsDouble(),o.get(1).getAsDouble(),o.get(2).getAsDouble()),p.get("yaw").getAsFloat(),
                new Vec3(a.get(0).getAsDouble(),a.get(1).getAsDouble(),a.get(2).getAsDouble()),h.get("yaw").getAsFloat());
    }
    /** The live exit lies this far inside the pressed hat's opening, toward its centre. */
    public static final float INSIDE_EXIT=DATA.get("inside_exit").getAsFloat();
    /** How far the floated brim slips under the bank. */
    public static final float UNDER_BANK=DATA.getAsJsonObject("float").get("under_bank").getAsFloat();
    private HatVessel(){}
}
