package io.github.longshou.mcyukkuri.item;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.headwear.JunkHeadwear;
import io.github.longshou.mcyukkuri.human.Custody;
import io.github.longshou.mcyukkuri.interaction.InteractionDispatch;
import io.github.longshou.mcyukkuri.social.SocialDisposition;
import io.github.longshou.mcyukkuri.status.Identity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public final class JunkHeadwearItem extends Item {
    public JunkHeadwearItem(Properties properties) { super(properties); }

    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity target, InteractionHand hand) {
        if (!(target instanceof YukkuriEntity animal)) return InteractionResult.PASS;
        if (!(player.level() instanceof ServerLevel)) return InteractionResult.SUCCESS;
        String refusal = refusal(player, animal);
        if (refusal != null) {
            InteractionDispatch.refuse(player, animal, "junk_headwear", refusal);
            return InteractionResult.CONSUME;
        }

        var contact = SocialDisposition.contact(animal, player);
        if (!JunkHeadwear.accepts(animal, contact)) JunkHeadwear.refused(animal, player, contact);
        else if (JunkHeadwear.fit(animal, player, contact, stack)) {
            if (!player.getAbilities().instabuild) stack.shrink(1);
        } else InteractionDispatch.refuse(player, animal, "junk_headwear", "busy");
        return InteractionResult.CONSUME;
    }

    private static String refusal(Player player, YukkuriEntity animal) {
        String reach = InteractionDispatch.reachRefusal(player, animal, 2);
        if (reach != null) return reach;
        if (!JunkHeadwear.fits(animal)) return "accessory_fit";
        var ownership = Custody.view(animal);
        if (ownership.hasPendingStaffRole()
                || ownership.owner() != null && !ownership.ownedBy(player.getUUID())
                || animal.pet().isPet() && !animal.pet().owned(player)
                || animal.status().identity() == Identity.STOCK && !ownership.ownedBy(player.getUUID())) return "custody";
        if (!JunkHeadwear.onDryGround(animal)) return "space";
        if (!player.mayBuild() || !animal.level().mayInteract(player, animal.blockPosition())) return "custody";
        return JunkHeadwear.available(animal) ? null : "busy";
    }
}
