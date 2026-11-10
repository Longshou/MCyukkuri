package io.github.longshou.mcyukkuri.item;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.registry.ModItems;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUtils;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/** Hand-held lens: hold right click to zoom in on small bodies, like a shorter-range spyglass. */
public final class MagnifyingGlassItem extends Item {
    public MagnifyingGlassItem(Properties properties) { super(properties); }

    @Override public int getUseDuration(ItemStack stack, LivingEntity entity) { return 1200; }
    @Override public UseAnim getUseAnimation(ItemStack stack) { return UseAnim.SPYGLASS; }
    @Override public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        player.playSound(SoundEvents.SPYGLASS_USE, 1.0f, 1.0f);
        player.awardStat(Stats.ITEM_USED.get(this));
        return ItemUtils.startUsingInstantly(level, player, hand);
    }
    @Override public void releaseUsing(ItemStack stack, Level level, LivingEntity entity, int timeLeft) {
        entity.playSound(SoundEvents.SPYGLASS_STOP_USING, 1.0f, 1.0f);
    }

    /** While this item is the used item, aiming it at a yukkuri must start the zoom instead of the care interactions. */
    public static void blockCare(PlayerInteractEvent.EntityInteract event) {
        if (event.getTarget() instanceof YukkuriEntity && event.getItemStack().is(ModItems.MAGNIFYING_GLASS.get())) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.PASS);
        }
    }
}
