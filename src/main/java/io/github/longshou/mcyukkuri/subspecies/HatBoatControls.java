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
        if(event.getHand()!=InteractionHand.MAIN_HAND
                || !event.getItemStack().is(Items.STICK) || !(event.getTarget() instanceof YukkuriEntity y))return;
        if (!event.getEntity().isShiftKeyDown()) {
            if (!HatVessel.aquatic(y)) return;
            if (!event.getLevel().isClientSide) {
                boolean accepted = y.getVehicle() instanceof HatBoatEntity boat
                        ? boat.cargo().givePaddle(event.getItemStack(),event.getEntity().getAbilities().instabuild)
                        : y.aquaticLife().giveStick(event.getItemStack(),event.getEntity().getAbilities().instabuild);
                if (!accepted) return;
            }
            event.setCancellationResult(InteractionResult.sidedSuccess(event.getLevel().isClientSide)); event.setCanceled(true); return;
        }
        if(y.getVehicle() instanceof HatBoatEntity boat) {
            if(!event.getLevel().isClientSide)boat.capsize();
        } else if(!event.getLevel().isClientSide) {
            if (HatVessel.aquatic(y) && y.aquaticLife().firstVoyage().needsLesson()) {
                y.aquaticLife().giveStick(event.getItemStack(),event.getEntity().getAbilities().instabuild);
                y.aquaticLife().firstVoyage().tryStart();
                event.setCancellationResult(InteractionResult.CONSUME); event.setCanceled(true); return;
            }
            var boat = HatBoatEntity.tryLaunchNearby(y); if (boat == null) return;
            boat.cargo().givePaddle(event.getItemStack(), event.getEntity().getAbilities().instabuild);
        }
        event.setCancellationResult(InteractionResult.sidedSuccess(event.getLevel().isClientSide));event.setCanceled(true);
    }
    private HatBoatControls(){}
}
