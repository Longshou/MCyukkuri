package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

@EventBusSubscriber(modid=MCYukkuri.MOD_ID,bus=EventBusSubscriber.Bus.MOD,value=Dist.CLIENT)
public final class HatBoatRenderer extends EntityRenderer<HatBoatEntity> {
    private final java.util.List<float[]> waterMask=new java.util.ArrayList<>();
    public HatBoatRenderer(EntityRendererProvider.Context context) {
        super(context); shadowRadius=.45f;
        // Follow the actual exported inner brim instead of guessing an ellipse.
        try(var reader=net.minecraft.client.Minecraft.getInstance().getResourceManager().getResourceOrThrow(MCYukkuri.id("yukkuri_props/hat_boat.json")).openAsReader()){
            var model=com.google.gson.JsonParser.parseReader(reader).getAsJsonObject();var vertices=model.getAsJsonArray("vertices");
            for(var raw:model.getAsJsonArray("parts")){
                var part=raw.getAsJsonObject();String name=part.get("name").getAsString();
                if(!name.startsWith("boat_brim_")||!name.endsWith("_-1"))continue;
                int start=part.get("start").getAsInt(),end=start+part.get("count").getAsInt();
                float inner=-Float.MAX_VALUE,top=-Float.MAX_VALUE,z0=Float.MAX_VALUE,z1=-Float.MAX_VALUE;
                for(int i=start;i<end;i++){inner=Math.max(inner,vertices.get(i*8).getAsFloat());top=Math.max(top,vertices.get(i*8+1).getAsFloat());z0=Math.min(z0,vertices.get(i*8+2).getAsFloat());z1=Math.max(z1,vertices.get(i*8+2).getAsFloat());}
                if(inner<-.001f)waterMask.add(new float[]{-inner/16,(top-.02f)/16,z0/16,z1/16});
            }
        }catch(java.io.IOException error){throw new java.io.UncheckedIOException(error);}
    }
    @SubscribeEvent public static void register(EntityRenderersEvent.RegisterRenderers event) { event.registerEntityRenderer(ModEntities.HAT_BOAT.get(),HatBoatRenderer::new); }
    @SubscribeEvent public static void items(net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent event) {
        event.registerItem(new net.neoforged.neoforge.client.extensions.common.IClientItemExtensions(){
            private PropItemRenderer renderer;
            @Override public net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer getCustomRenderer(){if(renderer==null)renderer=new PropItemRenderer();return renderer;}
        },io.github.longshou.mcyukkuri.registry.ModItems.MARISA_SHELL.get());
    }
    @Override public ResourceLocation getTextureLocation(HatBoatEntity boat) { return MCYukkuri.id("textures/props/hat_boat.png"); }
    @Override public void render(HatBoatEntity boat,float yaw,float partial,PoseStack pose,MultiBufferSource buffers,int light) {
        pose.pushPose();pose.mulPose(Axis.YP.rotationDegrees(180-yaw));
        if(boat.state()==HatBoatEntity.State.CAPSIZED)pose.mulPose(Axis.ZP.rotationDegrees(150));
        float scale=boat.hullScale();
        // On dry ground the turned-over hat rests on its crown (4.05 model units deep) instead of sinking into the ground.
        if(boat.isCradle())pose.translate(0,4.05f/16*scale,0);
        pose.scale(scale,scale,scale);
        PropModels.render("hat_boat",1,pose,buffers,light,OverlayTexture.NO_OVERLAY);
        if(boat.isAfloat()){
            var mask=buffers.getBuffer(net.minecraft.client.renderer.RenderType.waterMask());var matrix=pose.last().pose();
            for(var row:waterMask){float x=row[0],y=row[1],near=row[2],far=row[3];
                mask.addVertex(matrix,-x,y,near);mask.addVertex(matrix,-x,y,far);mask.addVertex(matrix,x,y,far);mask.addVertex(matrix,x,y,near);
            }
        }
        pose.popPose();
        super.render(boat,yaw,partial,pose,buffers,light);
    }
}
