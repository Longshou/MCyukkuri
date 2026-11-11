package io.github.longshou.mcyukkuri.body;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.registry.ModItems;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

public final class DetachedItems {
    /** Adult dropping = one volume unit; .001 keeps the smallest drop at one tenth linear size. */
    public static final double MIN_WASTE_VOLUME = .001;
    public static double wasteVolume(float scale) { return Math.max(MIN_WASTE_VOLUME, scale * (double)scale * scale); }
    public static double wasteVolume(YukkuriEntity source) {
        return wasteVolume(source.growthScale()*source.getScale()*source.profile().modelScale());
    }
    /** Bladder is relative fullness (0..100), not adult-volume units. */
    public static double wasteAmount(YukkuriEntity source) {
        return wasteVolume(source)*Math.min(1,source.life().bladder()/40.0);
    }
    public static double volume(ItemStack stack) {
        var tag=stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag();
        return stack.getCount()*(tag.contains("Volume")?tag.getDouble("Volume"):1);
    }
    public static ItemStack waste(double volume) {
        var stack=new ItemStack(ModItems.WASTE.get());
        if (volume != 1) CustomData.update(DataComponents.CUSTOM_DATA,stack,tag->tag.putDouble("Volume",volume));
        return stack;
    }
    private DetachedItems(){}
    public static ItemStack waste(YukkuriEntity source){
        return waste(wasteAmount(source));
    }
}
