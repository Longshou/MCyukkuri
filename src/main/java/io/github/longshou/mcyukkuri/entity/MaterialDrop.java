package io.github.longshou.mcyukkuri.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;

/** Ordinary item physics and inventory semantics, with a single quantity-aware render pass. */
public final class MaterialDrop extends ItemEntity {
    public MaterialDrop(EntityType<? extends ItemEntity> type,Level level){super(type,level);}
}
