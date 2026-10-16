package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import io.github.longshou.mcyukkuri.life.InfantFood;
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

/** Real synchronized cargo, resting on the shared hat ledges; no icon bobbing or synthetic stack contents. */
final class HatCargoRenderer {
    private record Geometry(boolean flat,double centerX,double minY,double centerZ,double radius,double height) {}
    private static final Map<BakedModel,Geometry> GEOMETRY = new WeakHashMap<>();

    static void render(HatBoatEntity boat,PoseStack pose,MultiBufferSource buffers,int light) {
        var portions=boat.cargo().portions();
        if(portions.isEmpty())return;
        var berths=boat.cargoBerths();
        double unit=boat.hullScale()/16.0,top=HatVessel.top(boat.mode());
        for(int i=0;i<Math.min(portions.size(),berths.size());i++) {
            var stack=portions.get(i); var berth=berths.get(i); var at=berth.position();
            if(stack.isEmpty()||!Double.isFinite(at.y)||berth.radius()<=0)continue;
            pose.pushPose();
            pose.translate(at.x*unit,(at.y-top)*unit,at.z*unit);
            String prop=prop(stack);
            if(prop!=null) {
                // Existing mod prop geometry already knows its real floor. Each draw is one stored item.
                // extent bounds every dimension: the diagonal also stays inside the circular berth.
                float fit=(float)(berth.radius()*unit*Math.sqrt(2)*.92/(Math.max(.001,PropModels.extent(prop))/16));
                pose.scale(fit,fit,fit); PropModels.center(prop,pose,true);
                PropModels.render(prop,prop.equals("leaked_filling")?stack.getCount():8,pose,buffers,light,OverlayTexture.NO_OVERLAY);
            } else {
                var renderer=Minecraft.getInstance().getItemRenderer();
                var model=renderer.getModel(stack,boat.level(),null,boat.getId()+i);
                var geometry=GEOMETRY.computeIfAbsent(model,HatCargoRenderer::geometry);
                float fit=(float)Math.min(berth.radius()*unit*.92/Math.max(.001,geometry.radius()),
                        berth.radius()*unit*2*.92/Math.max(.001,geometry.height()));
                pose.scale(fit,fit,fit);
                pose.translate(-geometry.centerX(),-geometry.minY(),-geometry.centerZ());
                if(geometry.flat())pose.mulPose(Axis.XP.rotationDegrees(90));
                renderer.render(stack,ItemDisplayContext.GROUND,false,pose,buffers,light,OverlayTexture.NO_OVERLAY,model);
            }
            pose.popPose();
        }
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
