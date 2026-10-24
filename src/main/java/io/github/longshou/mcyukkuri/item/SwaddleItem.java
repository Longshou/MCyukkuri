package io.github.longshou.mcyukkuri.item;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/**
 * A soft warming cloth for a baby or young yukkuri (docs/PET_SHOP.md). The warming and calming rules are project design; the styles of
 * {@link SwaddleStyle} follow the wiki's swaddle table (wiki/pet-life.md) and change only the look. The cloth is open at the face, never
 * limits movement, growth or learning, and comes off by itself once the young one can live independently. Right-click the owner's own
 * young pet to wrap it up; the care menu takes it off.
 */
public final class SwaddleItem extends Item {
    public SwaddleItem(Properties properties) { super(properties); }
    @Override public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity target, InteractionHand hand) {
        if (!(target instanceof YukkuriEntity entity)) return InteractionResult.PASS;
        if (!player.level().isClientSide && !entity.pet().care().putOnSwaddle(player, stack)) return InteractionResult.FAIL;
        return InteractionResult.sidedSuccess(player.level().isClientSide);
    }
    @Override public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.mcyukkuri.swaddle_cloth.style." + SwaddleStyle.of(stack).id).withStyle(ChatFormatting.GRAY));
    }
}
