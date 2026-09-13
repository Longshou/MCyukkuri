package io.github.longshou.mcyukkuri.item;

import io.github.longshou.mcyukkuri.entity.MaterialDrop;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public final class FillingItem extends Item {
    public FillingItem(Properties properties){super(properties);}
    @Override public boolean onEntityItemUpdate(ItemStack stack,ItemEntity original){
        if(original.level().isClientSide||!original.isAlive()||original instanceof MaterialDrop)return false;
        var replacement=ModEntities.MATERIAL_DROP.get().create(original.level());
        var tag=new CompoundTag();original.saveWithoutId(tag);tag.remove("UUID");replacement.load(tag);
        // A failed insertion leaves the original stack and its timer untouched.
        if(!original.level().addFreshEntity(replacement))return false;
        original.discard();return true;
    }
}
