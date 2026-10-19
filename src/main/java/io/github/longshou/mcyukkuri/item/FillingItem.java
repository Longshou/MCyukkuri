package io.github.longshou.mcyukkuri.item;

import io.github.longshou.mcyukkuri.body.FillingResource;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

/** Read-only compatibility item for ordinary filling saved before the shared corpse balance. */
public final class FillingItem extends Item {
    public FillingItem(Properties properties){super(properties);}

    @Override public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        // Old stacks can still carry their saved FOOD component; only legacy cores remain edible.
        return FillingResource.isCore(stack) ? super.use(level, player, hand) : InteractionResultHolder.fail(stack);
    }

    @Override public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        if (!FillingResource.isCore(stack)) lines.add(Component.translatable("item.mcyukkuri.leaked_filling.legacy"));
    }
}
