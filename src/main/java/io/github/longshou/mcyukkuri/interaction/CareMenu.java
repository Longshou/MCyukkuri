package io.github.longshou.mcyukkuri.interaction;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.item.OperationToolItem.Tool;
import io.github.longshou.mcyukkuri.registry.ModMenus;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;

/** Sends only the available action IDs. A button request is rechecked against current server state. */
public final class CareMenu extends AbstractContainerMenu {
    private final YukkuriEntity target;
    private final Player player;
    private final boolean cutter;
    private final SimpleContainerData available=new SimpleContainerData(3);

    public CareMenu(int id,Inventory inventory,int entityId,boolean cutter){
        super(ModMenus.CARE.get(),id);
        player=inventory.player;
        this.cutter=cutter;
        target=player.level().getEntity(entityId) instanceof YukkuriEntity value?value:null;
        addDataSlots(available);
        if(!player.level().isClientSide)updateActions();
    }
    public YukkuriEntity target(){return target;}
    public boolean cutter(){return cutter;}
    public long actions(){
        // Vanilla menu data slots carry signed shorts.
        return (available.get(0)&0xffffL)|(available.get(1)&0xffffL)<<16|(available.get(2)&0xffffL)<<32;
    }
    private boolean lists(CareAction action){
        return cutter?action.tool==Tool.CUTTER:action.tool==null;
    }
    private void updateActions(){
        long mask=0;
        for(var action:CareAction.values()){
            if(lists(action)&&InteractionDispatch.refusal(player,target,action)==null)mask|=1L<<action.id();
        }
        for(int slot=0;slot<available.getCount();slot++)available.set(slot,(short)(mask>>>(slot*16)));
    }
    @Override public void broadcastChanges(){
        updateActions();
        super.broadcastChanges();
    }
    @Override public boolean stillValid(Player player){return InteractionDispatch.requireReach(player,target,5);}
    @Override public ItemStack quickMoveStack(Player player,int slot){return ItemStack.EMPTY;}
    @Override public boolean clickMenuButton(Player player,int id){
        var action=CareAction.byId(id);
        if(action==null||!lists(action))return false;
        boolean done=InteractionDispatch.perform(player,target,action);
        ((ServerPlayer)player).closeContainer();
        return done;
    }
}
