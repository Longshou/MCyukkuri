package io.github.longshou.mcyukkuri.item;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.registry.ModItems;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SpyglassItem;
import net.neoforged.neoforge.common.ItemAbility;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/** Hand-held lens: hold right click to zoom in on small bodies, like a shorter-range spyglass. */
public final class MagnifyingGlassItem extends SpyglassItem {
    public MagnifyingGlassItem(Properties properties) { super(properties); }

    @Override public boolean canPerformAction(ItemStack stack, ItemAbility ability) {
        return false; // The magnifier supplies its own FOV, mouse scaling and HUD.
    }

    /** Pass through to item use, including the off hand when the main hand is empty. */
    public static void blockCare(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getTarget() instanceof YukkuriEntity)) return;
        var glass = ModItems.MAGNIFYING_GLASS.get();
        var stack = event.getItemStack();
        if (stack.is(glass) || (event.getHand() == InteractionHand.MAIN_HAND && stack.isEmpty()
                && event.getEntity().getOffhandItem().is(glass))) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.PASS);
        }
    }
}
