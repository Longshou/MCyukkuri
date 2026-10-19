package io.github.longshou.mcyukkuri.family;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.util.Mth;

/** The life-stage size standard (family.json "growth", from standards/yukkuri-v1/profiles/growth.json): sizes relative
 *  to the variant's standard adult. Size follows nourishment along one continuous curve, from the live-birth size
 *  through the baby, juvenile and adult spans; the stage reached only caps it, and it never shrinks. */
public final class GrowthSize {
    private static final JsonObject DATA=FamilyGeometry.DATA.getAsJsonObject("growth");
    private record Span(float min,float max,float from,float to){}
    private static final Span[] ORDINARY=spans(DATA.getAsJsonArray("stages"),DATA.getAsJsonArray("stages")),
            HOUSE=spans(DATA.getAsJsonObject("house").getAsJsonArray("stages"),DATA.getAsJsonArray("stages"));
    private static final JsonObject BIRTH=DATA.getAsJsonObject("birth"),FRUIT=DATA.getAsJsonObject("fruit");
    /** Born alive, dropped from the stem (already a juvenile), hatched (house, relative to the house adult). */
    public static final float LIVE=birth("live"),STEM=birth("stem"),EGG=birth("egg");
    private static final float FRUIT_FROM=FRUIT.get("from").getAsFloat(),FRUIT_TO=FRUIT.get("to").getAsFloat(),
            FRUIT_VISIBLE=FRUIT.get("visible_from").getAsFloat(),FRUIT_FULL=FRUIT.get("full_at").getAsFloat();
    private static float birth(String kind){return BIRTH.getAsJsonObject(kind).get("scale").getAsFloat();}
    /** Each stage's size range with the nourishment span (in maturity times) of the ordinary stages. */
    private static Span[] spans(JsonArray sizes,JsonArray ordinary){
        var out=new Span[3];
        for(int i=0;i<3;i++){
            var s=sizes.get(i).getAsJsonObject();var n=ordinary.get(i).getAsJsonObject().getAsJsonArray("nourishment");
            out[i]=new Span(s.get("min_scale").getAsFloat(),s.get("max_scale").getAsFloat(),n.get(0).getAsFloat(),n.get(1).getAsFloat());
        }
        return out;
    }
    public static float birth(FamilyData.BirthKind kind){return kind==FamilyData.BirthKind.STEM?STEM:kind==FamilyData.BirthKind.LIVE?LIVE:EGG;}
    public static FamilyLife.Stage birthStage(FamilyData.BirthKind kind){
        String key=kind==FamilyData.BirthKind.STEM?"stem":kind==FamilyData.BirthKind.LIVE?"live":"egg";
        return "juvenile".equals(BIRTH.getAsJsonObject(key).get("stage").getAsString())?FamilyLife.Stage.JUVENILE:FamilyLife.Stage.BABY;
    }
    /** A fruit on the stem at this gestation progress (0-1): visible from FRUIT_VISIBLE, full grown at FRUIT_FULL. */
    public static float fruit(float progress){
        return Mth.lerp(Math.clamp((progress-FRUIT_VISIBLE)/(FRUIT_FULL-FRUIT_VISIBLE),0,1),FRUIT_FROM,FRUIT_TO);
    }
    /** The size for this nourishment (ticks) and maturity: along the curve, capped by the stage reached; an adult is at
     *  least the adult minimum and grows on to its own target. */
    public static float scale(FamilyLife.Stage stage,long nourishment,long maturity,float adultScale,boolean house){
        var s=house?HOUSE:ORDINARY;float q=nourishment/(float)Math.max(1,maturity);
        float start=house?EGG:LIVE,size;
        if(q<=s[0].to())size=Mth.lerp(q/Math.max(1e-6f,s[0].to()),start,s[0].max());
        else if(q<=s[1].to())size=Mth.lerp((q-s[1].from())/(s[1].to()-s[1].from()),s[1].min(),s[1].max());
        else size=Mth.lerp(Math.min(1,(q-s[2].from())/(s[2].to()-s[2].from())),s[2].min(),house?s[2].max():adultScale);
        return switch(stage){
            case BABY->Math.min(size,s[0].max());
            case JUVENILE->Math.min(size,s[1].max());
            default->Math.max(size,s[2].min());
        };
    }
    /** Relative locomotion within the species: small children gradually acquire the adult's stride. */
    public static float movement(FamilyLife.Stage stage,float size,boolean house){
        if(stage==FamilyLife.Stage.ADULT)return 1;
        var spans=house?HOUSE:ORDINARY;
        float start=house?EGG:LIVE, baby=spans[0].max(), adult=spans[2].min();
        if(size<=baby)return Mth.lerp(Math.clamp((size-start)/Math.max(.001f,baby-start),0,1),.45f,.55f);
        return Mth.lerp(Math.clamp((size-baby)/Math.max(.001f,adult-baby),0,1),.55f,1f);
    }
    private GrowthSize(){}
}
