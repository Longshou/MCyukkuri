package io.github.longshou.mcyukkuri.human;

import io.github.longshou.mcyukkuri.item.CarrierItem;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.CustomData;

/** A sealed, single-use live package backed by the world's existing captive ledger. */
public final class LiveBagItem extends Item {
    public LiveBagItem(Properties p) { super(p); }
    @Override public InteractionResult useOn(UseOnContext c) {
        var token = CarrierItem.token(c.getItemInHand());
        if (token == null || c.getPlayer() == null) return InteractionResult.FAIL;
        if (c.getLevel() instanceof ServerLevel level) {
            var pos = c.getClickedPos().relative(c.getClickedFace());
            if (!level.hasChunkAt(pos) || !level.getBlockState(pos.below()).isSolidRender(level,pos.below())) return InteractionResult.FAIL;
            var animal = YukkuriWorldData.get(level).release(token, level, Vec3.atBottomCenterOf(pos), c.getRotation());
            if (animal == null) return InteractionResult.FAIL;
            Custody.assign(animal, c.getPlayer(), Custody.Role.COMPANION);
            c.getItemInHand().shrink(1);
        }
        return InteractionResult.sidedSuccess(c.getLevel().isClientSide);
    }
    @Override public void appendHoverText(ItemStack s, TooltipContext c, List<Component> text, TooltipFlag f) {
        text.add(Component.translatable("human.mcyukkuri.bag_description"));
        var tag=s.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag();
        if(tag.contains("Purpose")){
            text.add(Component.translatable("human.mcyukkuri.package_spec",Component.translatable("human.mcyukkuri.purpose."+tag.getInt("Purpose")),
                    tag.getInt("BatchNumber"),Component.translatable("entity.mcyukkuri."+tag.getString("Variant")),Component.translatable("human.mcyukkuri.size."+tag.getInt("SizeGrade"))));
            text.add(Component.translatable("human.mcyukkuri.package_state",tag.getInt("Occupants"),tag.getInt("HealthPercent"),tag.getInt("Nutrition"),tag.getInt("FillingPortions")));
            text.add(Component.translatable("human.mcyukkuri.package_treatment"));
            text.add(Component.translatable("human.mcyukkuri.package_activity",tag.getInt("Energy"),tag.getInt("Burns"),
                    Component.translatable("human.mcyukkuri.voice."+(tag.getBoolean("YoungVoice")?"young":"normal"))));
        }
    }
}
