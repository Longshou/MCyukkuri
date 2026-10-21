package io.github.longshou.mcyukkuri.pet;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Vanilla ties a lead to any mob that allows it. A yukkuri may only be led when it carries its harness (YukkuriEntity.canBeLeashed) and only by
 * its owner: shop stock, other people's pets and wild yukkuri cannot be taken away on a lead (docs/PET_SHOP.md section 12).
 * Runs on the server only: the client cannot see ownership, so it must not cancel the interaction itself.
 */
public final class LeashGuard {
    private LeashGuard() {}


    public static void interact(PlayerInteractEvent.EntityInteract event) {
        if (event.getLevel().isClientSide() || !(event.getTarget() instanceof YukkuriEntity animal) || !event.getItemStack().is(Items.LEAD) || animal.isLeashed()) return;
        var player = event.getEntity();
        String why = animal.pet().care().leashRefusal(player);
        if (why == null && animal.isPassenger()) {
            boolean pending = animal.pet().care().walk().requestLeadOnShore(player, event.getHand());
            event.setCanceled(true);
            event.setCancellationResult(pending ? InteractionResult.SUCCESS : InteractionResult.FAIL);
            if (!pending) GameplayLog.limited(animal, "interaction", "leash.shore_unavailable", 200,
                    "leash refused player={} reason=shore_unavailable", player.getUUID());
            return;
        }
        if (why == null) return; // the owner with a harnessed pet: vanilla ties the lead
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.FAIL);
        GameplayLog.limited(animal,"interaction","leash."+why,200,
                "leash refused player={} reason={}",player.getUUID(),why);
        player.displayClientMessage(Component.translatable("pet.mcyukkuri.leash.refuse." + why, animal.getDisplayName()), true);
    }
}
