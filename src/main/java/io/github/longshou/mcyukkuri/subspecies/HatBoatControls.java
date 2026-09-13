package io.github.longshou.mcyukkuri.subspecies;

import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/** Survival control: sneak with a stick at a dry bank to launch the individual's own hat. */
@EventBusSubscriber(modid=MCYukkuri.MOD_ID)
public final class HatBoatControls {
    @SubscribeEvent public static void launch(PlayerInteractEvent.EntityInteract event) {
        if(event.getHand()!=InteractionHand.MAIN_HAND || !event.getEntity().isShiftKeyDown()
                || !event.getItemStack().is(Items.STICK) || !(event.getTarget() instanceof YukkuriEntity y))return;
        if(y.getVehicle() instanceof HatBoatEntity boat) {
            if(!event.getLevel().isClientSide)boat.capsize();
        } else if(!event.getLevel().isClientSide && HatBoatEntity.tryLaunchNearby(y)==null)return;
        event.setCancellationResult(InteractionResult.sidedSuccess(event.getLevel().isClientSide));event.setCanceled(true);
    }
    private HatBoatControls(){}
}
