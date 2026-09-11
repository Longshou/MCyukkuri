package io.github.longshou.mcyukkuri.client;

import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

public final class YukkuriModel extends GeoModel<YukkuriEntity> {
    private final ResourceLocation geometry;
    private final ResourceLocation texture;
    private final ResourceLocation animation;

    public YukkuriModel(String species) {
        geometry = MCYukkuri.id("geo/entity/" + species + ".geo.json");
        texture = MCYukkuri.id("textures/entity/" + species + ".png");
        animation = MCYukkuri.id("animations/entity/" + species + ".animation.json");
    }

    @Override
    public ResourceLocation getModelResource(YukkuriEntity entity) {
        return geometry;
    }

    @Override
    public ResourceLocation getTextureResource(YukkuriEntity entity) {
        return texture;
    }

    @Override
    public ResourceLocation getAnimationResource(YukkuriEntity entity) {
        return animation;
    }
}
