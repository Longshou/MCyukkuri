package io.github.longshou.mcyukkuri.item;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/** Introduces two adults. Food, familiarity, safety, time and capacity still decide reproduction. */
public final class CompanionCordItem extends Item {
    public CompanionCordItem(Properties properties) { super(properties); }
    @Override public InteractionResult interactLivingEntity(ItemStack stack,Player player,LivingEntity target,InteractionHand hand) {
        if(!(target instanceof YukkuriEntity entity))return InteractionResult.PASS;
        if(player.level() instanceof ServerLevel level) {
            var data=stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag();
            if(!data.hasUUID("Selected")||player.isSecondaryUseActive()) {
                if(!entity.family().adult()){player.displayClientMessage(Component.translatable("family.mcyukkuri.adults_only"),true);return InteractionResult.FAIL;}
                CustomData.update(DataComponents.CUSTOM_DATA,stack,tag->tag.putUUID("Selected",entity.getUUID()));
                player.displayClientMessage(Component.translatable("family.mcyukkuri.selected",entity.getName()),true);
            } else {
                var selected=level.getEntity(data.getUUID("Selected"));
                boolean success=selected instanceof YukkuriEntity first&&first.family().introduce(entity);
                player.displayClientMessage(Component.translatable(success?"family.mcyukkuri.introduced":"family.mcyukkuri.introduction_failed"),true);
                if(success)stack.remove(DataComponents.CUSTOM_DATA);
            }
        }
        return InteractionResult.sidedSuccess(player.level().isClientSide);
    }
}
