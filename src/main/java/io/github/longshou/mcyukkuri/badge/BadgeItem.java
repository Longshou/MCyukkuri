package io.github.longshou.mcyukkuri.badge;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.interaction.CareAction;
import io.github.longshou.mcyukkuri.interaction.InteractionDispatch;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.world.*;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;

public final class BadgeItem extends Item {
    private final boolean handbook;
    public BadgeItem(boolean handbook) { super(new Properties().stacksTo(1)); this.handbook = handbook; }
    public static void pickingUp(ItemEntityPickupEvent.Pre event) {
        var player = event.getPlayer();
        if (event.getItemEntity().getItem().getItem() instanceof BadgeItem badge && !badge.handbook
                && player.hasInfiniteMaterials() && player.getInventory().getFreeSlot() < 0)
            event.setCanPickup(TriState.FALSE);
    }
    @Override public InteractionResult interactLivingEntity(ItemStack stack, Player p, LivingEntity target, InteractionHand hand) {
        if (!(target instanceof YukkuriEntity y)) return InteractionResult.PASS;
        if (!p.level().isClientSide) {
            if (handbook) {
                if (p.isSecondaryUseActive()) InteractionDispatch.perform(p, y, CareAction.REMOVE_BADGE);
                else BadgeMenu.open(p, y);
            } else if (!y.pet().attach(p, p.getItemInHand(hand))) p.displayClientMessage(Component.translatable("badge.mcyukkuri.token_refused"), true);
        }
        return InteractionResult.sidedSuccess(p.level().isClientSide);
    }
    @Override public InteractionResultHolder<ItemStack> use(Level level, Player p, InteractionHand hand) {
        if (handbook && !level.isClientSide) for (String key : new String[] {"badge.mcyukkuri.guide", "badge.mcyukkuri.guide.2",
                "badge.mcyukkuri.guide.3"}) p.sendSystemMessage(Component.translatable(key));
        return InteractionResultHolder.sidedSuccess(p.getItemInHand(hand), level.isClientSide);
    }
    @Override public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.translatable(handbook ? "badge.mcyukkuri.book_hint" : "badge.mcyukkuri.token_hint"));
    }
}
