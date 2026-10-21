package io.github.longshou.mcyukkuri.item;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * A soft warming cloth for a baby or young yukkuri (docs/PET_SHOP.md). PROJECT-ORIGINAL: the only wiki entry on a swaddle (EH30) is a
 * restraint context and is deliberately not used. The cloth is open at the face, never limits movement, growth or learning, and comes off by itself
 * once the young one can live independently. Right-click the owner's own young pet to wrap it up; the care menu takes it off.
 */
public final class SwaddleItem extends Item {
    public SwaddleItem(Properties properties) { super(properties); }
    @Override public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity target, InteractionHand hand) {
        if (!(target instanceof YukkuriEntity entity)) return InteractionResult.PASS;
        if (!player.level().isClientSide && !entity.pet().care().putOnSwaddle(player, stack)) return InteractionResult.FAIL;
        return InteractionResult.sidedSuccess(player.level().isClientSide);
    }
}
