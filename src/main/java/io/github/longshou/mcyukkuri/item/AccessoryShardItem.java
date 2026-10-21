package io.github.longshou.mcyukkuri.item;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
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

/** Physical repair material, interchangeable and stackable within the same accessory kind. */
public final class AccessoryShardItem extends Item {
    public static final int LIFESPAN = 72000 * 3;
    public AccessoryShardItem(Properties properties) { super(properties); }

    @Override public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity target, InteractionHand hand) {
        if (!(target instanceof YukkuriEntity entity)) return InteractionResult.PASS;
        // The client predicts the item interaction; only the server reads the recipient's component state.
        if (player.level().isClientSide) return InteractionResult.SUCCESS;
        if (!player.level().isClientSide) {
            if (!entity.headwear().takeShard(stack, player)) {
                GameplayLog.limited(entity,"headwear","shard_receive_refused",20,
                        "shard receive refused reason={} player={}",entity.headwear().transferredShardRefusalReason(stack),player.getUUID());
                return InteractionResult.FAIL;
            }
            int before=stack.getCount();stack.shrink(1);
            GameplayLog.event(entity,"headwear","offered shard consumed player={} count={} -> {}",player.getUUID(),before,stack.getCount());
        }
        return InteractionResult.sidedSuccess(player.level().isClientSide);
    }
    @Override public int getEntityLifespan(ItemStack stack, Level level) { return LIFESPAN; }
    @Override public void onDestroyed(ItemEntity item) {
        GameplayLog.event(item,"headwear","loose fragments destroyed count={} accessory={}",item.getItem().getCount(),
                item.getItem().getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag().getString("Accessory"));
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
