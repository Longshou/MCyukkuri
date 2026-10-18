package io.github.longshou.mcyukkuri.interaction;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;

/** Server entry for care menus and their actions. Callers retain their item/hand routing and client prediction. */
public final class InteractionDispatch {
    private InteractionDispatch() {}
    public static void openCare(Player player,YukkuriEntity target){
        if(player instanceof ServerPlayer server&&validCare(player,target))server.openMenu(
                new SimpleMenuProvider((id,inventory,owner)->new CareMenu(id,inventory,target.getId()),target.getDisplayName()),
                buffer->buffer.writeVarInt(target.getId()));
    }
    public static boolean validCare(Player player,YukkuriEntity target){
        return target!=null&&target.isAlive()&&player.isAlive()&&!player.isSpectator()
                &&player.level()==target.level()&&player.distanceToSqr(target)<=25&&player.hasLineOfSight(target);
    }
    public static boolean perform(Player player,YukkuriEntity target,int actionId){
        if(player.level().isClientSide)return false;
        var action=CareAction.byId(actionId);if(action==null||target==null)return false;
        boolean success=target.operations().perform(player,action);
        if(!success)player.displayClientMessage(Component.translatable("operation.mcyukkuri.unavailable"),true);
        return success;
    }
}
