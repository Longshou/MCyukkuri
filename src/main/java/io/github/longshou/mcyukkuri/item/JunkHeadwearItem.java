package io.github.longshou.mcyukkuri.item;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.headwear.Sanction;
import io.github.longshou.mcyukkuri.human.Custody;
import io.github.longshou.mcyukkuri.interaction.InteractionDispatch;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.social.SocialDisposition;
import io.github.longshou.mcyukkuri.status.GameplayLog;
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
        if (!(player.level() instanceof ServerLevel level)) return InteractionResult.SUCCESS;
        String refusal = refusal(player, animal);
        if (refusal != null) {
            InteractionDispatch.refuse(player, animal, "junk_headwear", refusal);
            return InteractionResult.CONSUME;
        }

        var relation = YukkuriWorldData.get(level).relation(animal.getUUID(), player.getUUID());
        double esteem = SocialDisposition.selfEsteem(animal);
        double chance = (1 - SocialDisposition.ramp(esteem, 22, 44))
                * (.80 + .20 * SocialDisposition.ramp(relation.closeness(), 0, 20))
                * (1 - SocialDisposition.ramp(relation.fear(), 15, 60));
        var id = animal.getUUID();
        double preference = ((id.getMostSignificantBits() ^ id.getLeastSignificantBits()) >>> 11) * 0x1.0p-53;
        var head = animal.life().custody();
        if (preference >= chance) {
            animal.showReaction(esteem >= 44 ? "cheek_puff" : "teary", esteem >= 44 ? "puff" : "", 60);
            GameplayLog.limited(animal, "headwear", "junk_refusal", 40,
                    "junk refused player={} esteem={} chance={}", player.getUUID(), esteem, chance);
        } else if (head.equipJunk()) {
            if (!player.getAbilities().instabuild) stack.shrink(1);
            Sanction.covered(animal);
            animal.showReaction(esteem <= 22 ? "happy" : "teary", "", 60);
            GameplayLog.event(animal, "headwear", "junk equipped variant={} player={} esteem={}",
                    head.junk().id(), player.getUUID(), esteem);
        }
        return InteractionResult.CONSUME;
    }

    private static String refusal(Player player, YukkuriEntity animal) {
        String reach = InteractionDispatch.reachRefusal(player, animal, 2);
        if (reach != null) return reach;
        var head = animal.life().custody();
        if (!head.lost() || head.junk() != null || animal.headwear().reassembling()) return "accessory_fit";
        var ownership = Custody.view(animal);
        if (ownership.hasPendingStaffRole()
                || ownership.owner() != null && !ownership.ownedBy(player.getUUID())
                || animal.pet().isPet() && !animal.pet().owned(player)
                || animal.status().identity() == Identity.STOCK && !ownership.ownedBy(player.getUUID())) return "custody";
        if (!animal.onGround() || animal.isInWaterOrBubble() || animal.isPassenger() || animal.isVehicle()
                || !SurfaceApproach.supportedGround(animal, animal.position())) return "space";
        if (!player.mayBuild() || !animal.level().mayInteract(player, animal.blockPosition())) return "custody";
        if (animal.operations().active() || animal.family().awaitingLanding() || animal.family().active()
                || animal.isPanicking() || animal.isOnFire() || animal.body().dying() || animal.life().isSleeping()
                || animal.life().mealTicks() > 0 || animal.status().reactions().active())
            return "busy";
        return null;
    }
}
