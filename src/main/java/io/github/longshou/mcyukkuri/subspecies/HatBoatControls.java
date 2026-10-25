package io.github.longshou.mcyukkuri.subspecies;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/** Direct paddle handoff only. Launching, lessons, landing and capsizing remain autonomous or hull interactions. */
public final class HatBoatControls {
    public static void deliverStick(PlayerInteractEvent.EntityInteract event) {
        if(event.getHand()!=InteractionHand.MAIN_HAND || !(event.getTarget() instanceof YukkuriEntity y)
                || !event.getItemStack().is(Items.STICK) || event.getEntity().isShiftKeyDown() || !y.profile().aquatic())return;
        if(!event.getLevel().isClientSide){
            if(y.getVehicle() instanceof HatBoatEntity boat && y.getUUID().equals(boat.ownerYukkuri()))
                boat.cargo().givePaddle(event.getItemStack(),event.getEntity().getAbilities().instabuild);
            else y.aquaticLife().giveStick(event.getItemStack(),event.getEntity().getAbilities().instabuild);
        }
        // Accepted or refused, this is only a delivery attempt; never fall through to a behavior request.
        event.setCancellationResult(InteractionResult.sidedSuccess(event.getLevel().isClientSide));event.setCanceled(true);
    }
    private HatBoatControls(){}
}
