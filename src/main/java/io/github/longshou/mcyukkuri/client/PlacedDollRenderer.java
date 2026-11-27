package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.life.PlacedDollEntity;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/** The doll's placed form, drawn with the same prop its item icon uses, standing on its own bottom and turned as it was set. */
public final class PlacedDollRenderer extends EntityRenderer<PlacedDollEntity> {
    public PlacedDollRenderer(EntityRendererProvider.Context context) { super(context); shadowRadius = .25f; }

    public static void register(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.PLACED_DOLL.get(), PlacedDollRenderer::new);
    }

    @Override public ResourceLocation getTextureLocation(PlacedDollEntity doll) { return MCYukkuri.id("textures/entity/marisa/neutral.png"); }

    @Override public void render(PlacedDollEntity doll, float yaw, float partial, PoseStack pose, MultiBufferSource buffers, int light) {
        if (doll.doll().isEmpty()) return;
        String name = BuiltInRegistries.ITEM.getKey(doll.doll().getItem()).getPath();
        if (!PropModels.has(name)) return;
        pose.pushPose();
        pose.mulPose(Axis.YP.rotationDegrees(180 - yaw));
        PropModels.center(name, pose, true);
        PropModels.render(name, Integer.MAX_VALUE, pose, buffers, light, OverlayTexture.NO_OVERLAY);
        pose.popPose();
        super.render(doll, yaw, partial, pose, buffers, light);
    }
}
