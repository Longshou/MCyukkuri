package io.github.longshou.mcyukkuri.body;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.registry.ModItems;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

public final class DetachedItems {
    private DetachedItems(){}
    public static ItemStack waste(YukkuriEntity source){
        var stack=new ItemStack(ModItems.WASTE.get());
        CustomData.update(DataComponents.CUSTOM_DATA,stack,tag->{tag.putUUID("Source",source.getUUID());
            tag.putString("Variant",source.getVariant().assetId());tag.putFloat("SourceScale",source.growthScale()*source.getScale()*(source.getVariant().isHouse()?.32f:1));});
        return stack;
    }
}
