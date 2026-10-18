package io.github.longshou.mcyukkuri.item;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.headwear.Headwear;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;

/**
 * A piece of one individual's shattered accessory (headwear.Headwear). It is drawn as that very piece of the owner's hat.
 * Only its owner can use it: the owner licks the pieces together itself, or a player hands one back (right click). Lying
 * on the ground it lasts three days; destroyed in fire or lava, the hat can never be made whole again.
 */
public final class AccessoryShardItem extends Item {
    public static final int LIFESPAN = 72000 * 3;
    public AccessoryShardItem(Properties properties) { super(properties); }

    @Override public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity target, InteractionHand hand) {
        if (!(target instanceof YukkuriEntity entity)) return InteractionResult.PASS;
        if (player.level().isClientSide) {
            var tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
            return tag.hasUUID("Owner") && entity.getUUID().equals(tag.getUUID("Owner"))
                    ? InteractionResult.SUCCESS : InteractionResult.FAIL;
        }
        if (!player.level().isClientSide) {
            if (!entity.headwear().takeShard(stack, player)) return InteractionResult.FAIL;
            stack.shrink(1);
        }
        return InteractionResult.sidedSuccess(player.level().isClientSide);
    }
    @Override public int getEntityLifespan(ItemStack stack, Level level) { return LIFESPAN; }
    @Override public void onDestroyed(ItemEntity item) {
        if (item.level() instanceof ServerLevel level) Headwear.shardDestroyed(level, item.getItem());
    }
    @Override public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        var tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        if (tag.contains("Accessory")) {
            var id = ResourceLocation.tryParse(tag.getString("Accessory"));
            var item = id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
            if (item != null) lines.add(Component.translatable("item.mcyukkuri.accessory_shard.of", new ItemStack(item).getHoverName()).withStyle(ChatFormatting.GRAY));
        }
        lines.add(Component.translatable("item.mcyukkuri.accessory_shard.hint").withStyle(ChatFormatting.DARK_GRAY));
    }
}
