package io.github.longshou.mcyukkuri.family;

import com.google.gson.JsonParser;
import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import net.minecraft.world.phys.Vec3;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Shared rendering and birth transform; bottle attachment coordinates are exported with its mesh. */
public final class DetachedStemGeometry {
    public static final Vec3 GROUND_ROOT=new Vec3(0,.02,0);
    // Placement inflates each child by 0.04 blocks. Keep a further 0.01 above the support face.
    private static final double CHILD_FLOOR_CLEARANCE=.05;
    public static final Vec3 BOTTLE_ROOT, BOTTLE_MOUTH;
    static{
        try(var stream=DetachedStemGeometry.class.getResourceAsStream("/assets/mcyukkuri/yukkuri_props/brood_bottle.json")){
            var data=JsonParser.parseReader(new InputStreamReader(Objects.requireNonNull(stream),StandardCharsets.UTF_8)).getAsJsonObject();
            var root=data.getAsJsonArray("root_socket");var mouth=data.getAsJsonArray("socket");
            BOTTLE_ROOT=new Vec3(root.get(0).getAsDouble()/16,root.get(1).getAsDouble()/16,root.get(2).getAsDouble()/16);
            BOTTLE_MOUTH=new Vec3(mouth.get(0).getAsDouble()/16,mouth.get(1).getAsDouble()/16,mouth.get(2).getAsDouble()/16);
        }catch(IOException error){throw new UncheckedIOException(error);}
    }
    public static Vec3 offset(int count,float variation,YukkuriVariant parent,boolean bottle){
        var root=FamilyGeometry.arc(count,1,1,variation,parent).getFirst();
        if(bottle)return BOTTLE_MOUTH.subtract(root);
        double height=GROUND_ROOT.y;
        // Reserve the mature envelope, including mixed-species children. The transform stays fixed
        // while fruit grows or detaches, and is identical in placement, rendering and actual birth.
        for(int index=0;index<count;index++)for(var child:YukkuriVariant.values()){
            double bottom=FamilyGeometry.childBase(count,index,1,1,variation,parent,child,GrowthSize.largestBirth(FamilyData.BirthKind.STEM)).y-root.y;
            height=Math.max(height,CHILD_FLOOR_CLEARANCE-bottom);
        }
        return new Vec3(0,height,0).subtract(root);
    }
    public static Vec3 childBase(int count,int index,float progress,float variation,YukkuriVariant parent,YukkuriVariant child,boolean bottle){
        return childBase(count,index,progress,variation,parent,child,bottle,GrowthSize.STEM);
    }
    public static Vec3 childBase(int count,int index,float progress,float variation,YukkuriVariant parent,YukkuriVariant child,boolean bottle,float birthScale){
        return FamilyGeometry.childBase(count,index,progress,1,variation,parent,child,birthScale).add(offset(count,variation,parent,bottle));
    }
    private DetachedStemGeometry(){}
}
