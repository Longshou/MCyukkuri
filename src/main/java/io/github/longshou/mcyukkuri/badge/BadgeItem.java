package io.github.longshou.mcyukkuri.badge;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import net.minecraft.network.chat.Component;
import net.minecraft.world.*;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;

public final class BadgeItem extends Item {
    private final boolean handbook;
    public BadgeItem(boolean handbook) { super(new Properties().stacksTo(1)); this.handbook = handbook; }
    @Override public InteractionResult interactLivingEntity(ItemStack stack, Player p, LivingEntity target, InteractionHand hand) {
        if (!(target instanceof YukkuriEntity y)) return InteractionResult.PASS;
        if (!p.level().isClientSide) {
            if (handbook) BadgeMenu.open(p, y);
            else if (!y.pet().attach(p, stack)) p.displayClientMessage(Component.translatable("badge.mcyukkuri.token_refused"), true);
        }
        return InteractionResult.sidedSuccess(p.level().isClientSide);
    }
    @Override public InteractionResultHolder<ItemStack> use(net.minecraft.world.level.Level level, Player p, InteractionHand hand) {
        if (handbook && !level.isClientSide) for (String key : new String[] {"badge.mcyukkuri.guide", "badge.mcyukkuri.guide.2", "badge.mcyukkuri.guide.3"}) p.sendSystemMessage(Component.translatable(key));
        return InteractionResultHolder.sidedSuccess(p.getItemInHand(hand), level.isClientSide);
    }
    @Override public void appendHoverText(ItemStack stack, TooltipContext context, java.util.List<Component> lines, TooltipFlag flag) {
        lines.add(Component.translatable(handbook ? "badge.mcyukkuri.book_hint" : "badge.mcyukkuri.token_hint"));
    }
}
