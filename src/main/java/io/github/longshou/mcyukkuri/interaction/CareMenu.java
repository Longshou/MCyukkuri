package io.github.longshou.mcyukkuri.interaction;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.registry.ModMenus;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/** The client selects an action; distance, tools, ownership and effects are checked again on the server. */
public final class CareMenu extends AbstractContainerMenu {
    private final YukkuriEntity target;
    public CareMenu(int id,Inventory inventory,int entityId){
        super(ModMenus.CARE.get(),id);
        target=inventory.player.level().getEntity(entityId) instanceof YukkuriEntity value?value:null;
    }
    public YukkuriEntity target(){return target;}
    public static void open(Player player,YukkuriEntity target){
        if(player instanceof ServerPlayer server&&valid(player,target))server.openMenu(
                new SimpleMenuProvider((id,inventory,owner)->new CareMenu(id,inventory,target.getId()),target.getDisplayName()),
                buffer->buffer.writeVarInt(target.getId()));
    }
    public static boolean valid(Player player,YukkuriEntity target){
        return target!=null&&target.isAlive()&&player.isAlive()&&!player.isSpectator()
                &&player.level()==target.level()&&player.distanceToSqr(target)<=25&&player.hasLineOfSight(target);
    }
    @Override public boolean stillValid(Player player){return valid(player,target);}
    @Override public ItemStack quickMoveStack(Player player,int slot){return ItemStack.EMPTY;}
    @Override public boolean clickMenuButton(Player player,int id){
        if(player.level().isClientSide||!stillValid(player)||id<0||id>=CareAction.values().length)return false;
        boolean success=target.operations().perform(player,CareAction.values()[id]);
        if(!success)player.displayClientMessage(Component.translatable("operation.mcyukkuri.unavailable"),true);
        return success;
    }
}
