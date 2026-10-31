package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import io.github.longshou.mcyukkuri.registry.ModItems;
import io.github.longshou.mcyukkuri.headwear.PlacedHatEntity;
import io.github.longshou.mcyukkuri.headwear.PlacedHatView;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import org.joml.Quaternionf;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class PlacedHatRenderer extends EntityRenderer<PlacedHatEntity> {
    /** Something an add-on draws with the hat (its stores in the hollow), in the hull's frame: blocks, origin at its bottom. */
    @FunctionalInterface public interface Layer { void render(PlacedHatView hat,float partial,PoseStack pose,MultiBufferSource buffers,int light); }
    private static final List<Layer> LAYERS=new CopyOnWriteArrayList<>();
    /** The client registration entry adds an add-on's layer; the base never names it. */
    public static void layer(Layer layer){LAYERS.add(layer);}
    public PlacedHatRenderer(EntityRendererProvider.Context context) { super(context); shadowRadius=.45f; }
    public static void register(EntityRenderersEvent.RegisterRenderers event) { event.registerEntityRenderer(ModEntities.PLACED_HAT.get(),PlacedHatRenderer::new); }
    public static void items(RegisterClientExtensionsEvent event) {
        event.registerItem(new IClientItemExtensions(){
            private PropItemRenderer renderer;
            @Override public BlockEntityWithoutLevelRenderer getCustomRenderer(){if(renderer==null)renderer=new PropItemRenderer();return renderer;}
        },ModItems.MARISA_SHELL.get());
    }
    @Override public ResourceLocation getTextureLocation(PlacedHatEntity hat) { return MCYukkuri.id("textures/entity/marisa/neutral.png"); }
    /** Marisa's own shared hat turned upside down (HatScale: one size in every form), its soft crown pressed along the hat's
     *  axis: on the ground until the brim stands on the squashed crown. The braid clips end with the worn hat in this same pose.
     *  The hull is drawn in its synced frame (yaw, roll, pitch about its bottom), the frame its riders stand in. */
    @Override public void render(PlacedHatEntity source,float yaw,float partial,PoseStack pose,MultiBufferSource buffers,int light) {
        PlacedHatView hat=source;
        if(hat.state()==PlacedHatEntity.State.RESCUED)return;  // back on its owner's head (the braid clip carries it there)
        float scale=hat.hullScale(), unit=scale/16;
        var at=hat.getPosition(partial);
        pose.pushPose();
        pose.translate(at.x-source.getX(),at.y-source.getY(),at.z-source.getZ());
        pose.mulPose(new Quaternionf().rotationY((180-Mth.rotLerp(partial,hat.previousYaw(),hat.getYRot()))*Mth.DEG_TO_RAD)
                .rotateZ(hat.roll(partial)*Mth.DEG_TO_RAD).rotateX(hat.pitch(partial)*Mth.DEG_TO_RAD));
        for(var layer:LAYERS){pose.pushPose();layer.render(hat,partial,pose,buffers,light);pose.popPose();}
        pose.translate(0,hat.profile().top()*unit,0);
        pose.scale(scale,scale*(float)hat.profile().squash(),scale);
        // The hull is the owner's own hat: its wear shows as the same square-cell damage as when worn.
        int damage=hat.hullDamage();
        if(damage>0)PropModels.renderHat(hat.profile().hull(),damage,-1,pose,buffers,light,OverlayTexture.NO_OVERLAY);
        else PropModels.render(hat.profile().hull(),1,pose,buffers,light,OverlayTexture.NO_OVERLAY);
        pose.popPose();
        super.render(source,yaw,partial,pose,buffers,light);
    }
}
