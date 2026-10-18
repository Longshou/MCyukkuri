package io.github.longshou.mcyukkuri.item;

import io.github.longshou.mcyukkuri.settlement.ShelterBlock;
import io.github.longshou.mcyukkuri.settlement.ShelterPlacement;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;

public final class ShelterItem extends Item {
    private final ShelterBlock.Kind kind;
    public ShelterItem(ShelterBlock.Kind kind,Properties properties) { super(properties);this.kind=kind; }
    @Override public InteractionResult useOn(UseOnContext context) {
        if(context.getLevel().isClientSide)return InteractionResult.SUCCESS;
        var level=(ServerLevel)context.getLevel();var player=context.getPlayer();
        BlockPos center=context.getClickedPos().relative(context.getClickedFace());
        var facing=context.getHorizontalDirection().getOpposite();
        for(var pos:ShelterPlacement.positions(center,kind))
            if(!level.mayInteract(player,pos)||player!=null&&!player.mayUseItemAt(pos,context.getClickedFace(),context.getItemInHand()))return InteractionResult.FAIL;
        var placed=ShelterPlacement.place(level,new ShelterPlacement.Plan(center,kind,facing));
        if(placed==null){if(player!=null)player.displayClientMessage(kind==ShelterBlock.Kind.BURROW?
                Component.translatable("shelter.mcyukkuri.no_cavity"):Component.translatable("shelter.mcyukkuri.no_space",kind.radius*2+1,
                kind.radius*2+1,kind.height),true);return InteractionResult.FAIL;}
        if(player==null||!player.getAbilities().instabuild)context.getItemInHand().shrink(1);
        return InteractionResult.CONSUME;
    }
}
