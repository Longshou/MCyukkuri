package io.github.longshou.mcyukkuri.subspecies;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import net.minecraft.world.phys.Vec3;

/** Marisa's own hat as a vessel (interactions/marisa-hat-vessel, hat_vessel.json): its size, seats and where a catcher sets it. Model units, 16 per block. */
public final class HatVessel {
    private static final JsonObject DATA=read();
    private static JsonObject read(){
        try(var stream=HatVessel.class.getResourceAsStream("/assets/mcyukkuri/yukkuri_presentation/hat_vessel.json")){
            if(stream==null)throw new IllegalStateException("Missing hat vessel metrics");
            return com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(stream,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        }catch(java.io.IOException ex){throw new java.io.UncheckedIOException(ex);}
    }
    /** How a turned-over hat sits: lying on the back of its crown or on its bow, or floating opening up. */
    public enum Mode { BACK, BOW, FLOAT }
    public enum Role { HELPER, SELF }
    public static final float AQUATIC_SCALE=DATA.get("aquatic_scale").getAsFloat();
    public static final float FREEBOARD=DATA.getAsJsonObject("boat").get("freeboard").getAsFloat();
    public static final float OWNER_SINK=DATA.getAsJsonObject("boat").get("owner_sink").getAsFloat();
    public static final float FLOOR=DATA.get("floor").getAsFloat();
    public static final float BANK_DROP=DATA.getAsJsonObject("float").get("bank_drop").getAsFloat();
    private static final float[] ANCHOR=floats(DATA.getAsJsonArray("anchor_worn"));
    private static final float[] OPENING=floats(DATA.getAsJsonArray("opening"));
    private static float[] floats(JsonArray a){float[] out=new float[a.size()];for(int i=0;i<out.length;i++)out[i]=a.get(i).getAsFloat();return out;}
    /** The worn hat's seat on the head: the larger aquatic hat scales about it. */
    public static org.joml.Vector3f anchor(){return new org.joml.Vector3f(ANCHOR[0],ANCHOR[1],ANCHOR[2]);}
    /** Opening of the dry well at the rim: x0, x1, z0, z1. */
    public static float[] opening(){return OPENING.clone();}
    public static boolean aquatic(YukkuriEntity y){return y.subspecies().kind()==SubspeciesLife.Kind.AQUATIC_MARISA;}
    /** The size of this Marisa's hat relative to the worn standard. */
    public static float hatScale(YukkuriEntity y){return y.growthScale()*(aquatic(y)?AQUATIC_SCALE:1);}
    private static JsonObject cradle(Mode mode){return DATA.getAsJsonObject("cradle").getAsJsonObject(mode==Mode.BOW?"bow":"back");}
    public static float tilt(Mode mode){return cradle(mode).get("tilt").getAsFloat();}
    public static float lift(Mode mode){return cradle(mode).get("lift").getAsFloat();}
    /** Young sitting in a lying hat, in the cradle's frame. */
    public static Vec3 cradleSeat(Mode mode,int seat){
        var seats=cradle(mode).getAsJsonArray("seats");var s=seats.get(Math.floorMod(seat,seats.size())).getAsJsonArray();
        return new Vec3(s.get(0).getAsDouble(),s.get(1).getAsDouble(),s.get(2).getAsDouble());
    }
    /** Young on the floor of the well afloat: by day before the owner, at night under her. */
    public static Vec3 boatSeat(int seat,boolean night){
        var seats=DATA.getAsJsonObject("boat").getAsJsonArray(night?"young_night":"young_day");var s=seats.get(Math.floorMod(seat,seats.size())).getAsJsonArray();
        return new Vec3(s.get(0).getAsDouble(),FREEBOARD+FLOOR,s.get(1).getAsDouble());
    }
    /** Where a catcher's braid clips set the hat, relative to the catcher (land: lying, aquatic: floated at a bank). */
    public record Placement(Mode mode,Vec3 offset,float yaw,Vec3 hatInMother,float hatYawInMother){}
    public static Placement placement(boolean aquatic,Role role){
        var p=DATA.getAsJsonObject("placements").getAsJsonObject(aquatic?"aquatic":"land").getAsJsonObject(role==Role.HELPER?"helper":"self");
        var o=p.getAsJsonArray("offset");var mode=Mode.valueOf(p.get("mode").getAsString().toUpperCase(java.util.Locale.ROOT));
        Vec3 inMother=null;float yawInMother=p.get("yaw").getAsFloat();
        if(p.has("hat_in_mother")){var h=p.getAsJsonObject("hat_in_mother");var a=h.getAsJsonArray("origin");inMother=new Vec3(a.get(0).getAsDouble(),a.get(1).getAsDouble(),a.get(2).getAsDouble());yawInMother=h.get("yaw").getAsFloat();}
        else inMother=new Vec3(o.get(0).getAsDouble(),o.get(1).getAsDouble(),o.get(2).getAsDouble());
        return new Placement(mode,new Vec3(o.get(0).getAsDouble(),o.get(1).getAsDouble(),o.get(2).getAsDouble()),p.get("yaw").getAsFloat(),inMother,yawInMother);
    }
    /** The lying hat's lip (toward the mother) from the cradle origin, along the cradle's -Z. */
    public static float lipZ(Mode mode){return cradle(mode).get("lip_z").getAsFloat();}
    /** The lip lies this far short of the live exit, so the newborn comes down just inside it. */
    public static final float LIP_BEFORE_EXIT=DATA.get("lip_before_exit").getAsFloat();
    /** Brim beyond the opening on the side toward the mother when floated (vessel +X), and how far it slips under the bank. */
    public static final float NEAR_BRIM=DATA.getAsJsonArray("brim_x").get(1).getAsFloat();
    public static final float UNDER_BANK=DATA.getAsJsonObject("float").get("under_bank").getAsFloat();
    private HatVessel(){}
}
