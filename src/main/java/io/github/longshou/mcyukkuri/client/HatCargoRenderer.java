package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import io.github.longshou.mcyukkuri.life.InfantFood;
import io.github.longshou.mcyukkuri.subspecies.HatBoatCargo;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity;
import io.github.longshou.mcyukkuri.subspecies.HatVessel;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Vector3f;

/** One model per real synchronized serving, resting inside the shared circular cargo berths. */
final class HatCargoRenderer {
    private record Geometry(boolean flat,double centerX,double minY,double centerZ,double radius,double height) {}
    private static final Map<BakedModel,Geometry> GEOMETRY = new WeakHashMap<>();

    /** Display slot in hat units about the heap centre: sideways and along-ledge offset, lift above the ledge, yaw, size.
     *  Mirrors cargo.py layout() in standards/yukkuri-library/drafts/care-props: bow-end servings on leaf pads (four on the
     *  wide ledge, one on the narrow one), then three nested above them. */
    private record Slot(double dx,double dz,double lift,float yaw,float scale) {}
    private static final double SCALE=1.1,NARROW_SCALE=.9,NARROW_DX=-7.52,MAT_LIFT=.16,LAYER=.72,PITCH=.95,ITEM_DIAMETER=1.1;
    // Bow end first, like the server's berth order, so the first servings stay clear of a seated captain.
    private static final java.util.List<Slot> SLOTS=java.util.List.of(
            new Slot(.1,-2*PITCH,MAT_LIFT,40,(float)SCALE),new Slot(NARROW_DX,-2*PITCH,MAT_LIFT,-15,(float)NARROW_SCALE),
            new Slot(-.1,-PITCH,MAT_LIFT,25,(float)SCALE),new Slot(.1,0,MAT_LIFT,-18,(float)SCALE),new Slot(-.1,PITCH,MAT_LIFT,-30,(float)SCALE),
            new Slot(0,-1.5*PITCH,MAT_LIFT+LAYER,35,(float)SCALE),new Slot(0,-.5*PITCH,MAT_LIFT+LAYER,5,(float)SCALE),
            new Slot(0,.5*PITCH,MAT_LIFT+LAYER,-22,(float)SCALE));
    private static final int FLOOR_SLOTS=5;
    /** Authored servings are sized in hat units, so the hull scale alone places them. */
    private static final Map<String,String> SERVINGS=Map.ofEntries(
            Map.entry("minecraft:apple","hat_cargo_apple"),Map.entry("minecraft:sweet_berries","hat_cargo_berries"),
            Map.entry("minecraft:carrot","hat_cargo_carrot"),Map.entry("minecraft:bread","hat_cargo_bread"),
            Map.entry("minecraft:cookie","hat_cargo_cookie"),Map.entry("minecraft:melon_slice","hat_cargo_melon"),
            Map.entry("minecraft:short_grass","hat_cargo_grass"),Map.entry("minecraft:tall_grass","hat_cargo_tall_grass"),
            Map.entry("minecraft:wheat","hat_cargo_wheat"),Map.entry("mcyukkuri:sweet_candy","hat_cargo_candy"),
            Map.entry("mcyukkuri:custard_pudding","hat_cargo_pudding"));

    static void render(HatBoatEntity boat,float partial,PoseStack pose,MultiBufferSource buffers,int light) {
        var portions=boat.cargo().portions();
        if(portions.isEmpty())return;
        var berths=HatVessel.cargoBerths(boat.mode(),HatBoatCargo.CAPACITY);
        if(berths.size()<8)return;
        // The heap sits on the wide ledge: x from the front berths, z centred on their rows (cargo.py layout()).
        double x0=berths.get(1).position().x-.08,z0=(berths.get(1).position().z+berths.get(7).position().z)/2+.1;
        double support=HatVessel.surface(boat.mode(),x0,z0);
        if(!Double.isFinite(support)||!Double.isFinite(x0)||!Double.isFinite(z0))return;
        double unit=boat.hullScale()/16.0,top=HatVessel.top(boat.mode());
        double squash=boat.hullSquash(partial)/(boat.isCradle()?HatVessel.SQUASH_NEST:HatVessel.SQUASH_FLOAT);
        double base=(support-top)*unit*squash;
        if(PropModels.has("hat_cargo_mat")) {
            pose.pushPose();
            pose.translate(x0*unit,base,z0*unit);
            float scale=boat.hullScale();pose.scale(scale,scale,scale);
            PropModels.render("hat_cargo_mat",Math.min(portions.size(),FLOOR_SLOTS),pose,buffers,light,OverlayTexture.NO_OVERLAY);
            pose.popPose();
        }
        for(int i=0;i<Math.min(portions.size(),SLOTS.size());i++) {
            var stack=portions.get(i); var slot=SLOTS.get(i);
            if(stack.isEmpty())continue;
            pose.pushPose();
            pose.translate((x0+slot.dx())*unit,base+slot.lift()*unit,(z0+slot.dz())*unit);
            pose.mulPose(Axis.YP.rotationDegrees(slot.yaw()));
            var id=BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            String serving=SERVINGS.get(id);
            boolean authored=serving!=null&&PropModels.has(serving);
            String prop=authored?null:prop(stack);
            if(authored) {
                // Authored in hat units (16 per block at hull scale 1): the hull scale alone sizes it.
                float scale=(float)(boat.hullScale()*slot.scale());
                pose.scale(scale,scale,scale);
                PropModels.render(serving,Integer.MAX_VALUE,pose,buffers,light,OverlayTexture.NO_OVERLAY);
            } else if(prop!=null) {
                // Filling uses one of its twelve quantity parts; other props draw one complete item.
                int units=prop.equals("leaked_filling")?1:Integer.MAX_VALUE;
                var bounds=PropModels.bounds(prop,units);
                double radius=Math.hypot(bounds.getXsize()*.5,bounds.getZsize()*.5);
                float fit=fit(ITEM_DIAMETER*.5*unit*slot.scale(),radius,bounds.getYsize());
                pose.scale(fit,fit,fit);
                pose.translate(-(bounds.minX+bounds.maxX)*.5,-bounds.minY,-(bounds.minZ+bounds.maxZ)*.5);
                PropModels.render(prop,units,pose,buffers,light,OverlayTexture.NO_OVERLAY);
            } else {
                var renderer=Minecraft.getInstance().getItemRenderer();
                var model=renderer.getModel(stack,boat.level(),null,boat.getId()+i);
                var geometry=GEOMETRY.computeIfAbsent(model,HatCargoRenderer::geometry);
                float fit=fit(ITEM_DIAMETER*.5*unit*slot.scale(),geometry.radius(),geometry.height());
                pose.scale(fit,fit,fit);
                pose.translate(-geometry.centerX(),-geometry.minY(),-geometry.centerZ());
                if(geometry.flat())pose.mulPose(Axis.XP.rotationDegrees(90));
                renderer.render(stack,ItemDisplayContext.GROUND,false,pose,buffers,light,OverlayTexture.NO_OVERLAY,model);
            }
            pose.popPose();
        }
    }

    /** A serving fills about ITEM_DIAMETER hat units: at most its horizontal radius and a height of one diameter. */
    private static float fit(double radiusBudget,double radius,double height) {
        return (float)Math.min(radiusBudget*.92/Math.max(.001,radius),
                radiusBudget*2*.92/Math.max(.001,height));
    }

    private static String prop(ItemStack stack) {
        if(InfantFood.isMash(stack)&&PropModels.has("food_mash_serving"))return "food_mash_serving";
        var id=BuiltInRegistries.ITEM.getKey(stack.getItem());
        if(!id.getNamespace().equals("mcyukkuri"))return null;
        String small=HouseScrapRenderer.smallProp(stack);
        String name=small!=null?small:id.getPath();
        return PropModels.has(name)?name:null;
    }

    /** Measure the same ground transform used by ItemRenderer, then put its actual lowest vertex on the hat surface. */
    private static Geometry geometry(BakedModel model) {
        boolean flat=!model.isGui3d()&&!model.isCustomRenderer();
        var transform=new PoseStack();
        if(flat)transform.mulPose(Axis.XP.rotationDegrees(90));
        var shown=net.neoforged.neoforge.client.ClientHooks.handleCameraTransforms(transform,model,ItemDisplayContext.GROUND,false);
        transform.translate(-.5,-.5,-.5);
        var matrix=transform.last().pose();
        double[] bounds={Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY};
        var random=RandomSource.create(42);
        for(int face=-1;face<Direction.values().length;face++) {
            random.setSeed(42);
            for(BakedQuad quad:shown.getQuads(null,face<0?null:Direction.values()[face],random)) {
                int[] data=quad.getVertices();int stride=data.length/4;
                for(int corner=0;corner<4;corner++) {
                    int offset=corner*stride;
                    var point=matrix.transformPosition(new Vector3f(Float.intBitsToFloat(data[offset]),Float.intBitsToFloat(data[offset+1]),Float.intBitsToFloat(data[offset+2])));
                    include(bounds,point);
                }
            }
        }
        if(!Double.isFinite(bounds[0]))for(int x=0;x<=1;x++)for(int y=0;y<=1;y++)for(int z=0;z<=1;z++)
            include(bounds,matrix.transformPosition(new Vector3f(x,y,z)));
        double centerX=(bounds[0]+bounds[1])*.5,centerZ=(bounds[4]+bounds[5])*.5;
        double radius=Math.hypot((bounds[1]-bounds[0])*.5,(bounds[5]-bounds[4])*.5);
        return new Geometry(flat,centerX,bounds[2],centerZ,radius,bounds[3]-bounds[2]);
    }
    private static void include(double[] bounds,Vector3f point) {
        bounds[0]=Math.min(bounds[0],point.x);bounds[1]=Math.max(bounds[1],point.x);
        bounds[2]=Math.min(bounds[2],point.y);bounds[3]=Math.max(bounds[3],point.y);
        bounds[4]=Math.min(bounds[4],point.z);bounds[5]=Math.max(bounds[5],point.z);
    }
    private HatCargoRenderer() {}
}
