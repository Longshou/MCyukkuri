package io.github.longshou.mcyukkuri.item;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * A body band with a ring on the back (docs/PET_SHOP.md section 12; project-original, no wiki basis). Yukkuri have no neck, so the lead is tied
 * to this ring and never to the accessory, whose loss would count as a serious event. Right-click the owner's own pet to fit it; the pet can then be
 * led on a vanilla lead by its owner only. Take it off with the care menu.
 */
public final class PetHarnessItem extends Item {
    public PetHarnessItem(Properties properties) { super(properties); }
    @Override public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity target, InteractionHand hand) {
        if (!(target instanceof YukkuriEntity entity)) return InteractionResult.PASS;
        if (!player.level().isClientSide && !entity.pet().care().putOnHarness(player, stack)) return InteractionResult.FAIL;
        return InteractionResult.sidedSuccess(player.level().isClientSide);
    }
}
