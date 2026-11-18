package io.github.longshou.mcyukkuri.interaction;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.registry.ModMenus;
import io.github.longshou.mcyukkuri.shop.ShopTrade;
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
    private final boolean shop;
    private final CareLineage lineage;
    private final SimpleContainerData available=new SimpleContainerData(4);

    public CareMenu(int id,Inventory inventory,int entityId,boolean cutter,boolean shop,CareLineage lineage){
        super(ModMenus.CARE.get(),id);
        player=inventory.player;
        this.cutter=cutter;
        this.shop=shop;
        this.lineage=lineage;
        target=player.level().getEntity(entityId) instanceof YukkuriEntity value?value:null;
        addDataSlots(available);
        if(!player.level().isClientSide)updateActions();
    }
    public YukkuriEntity target(){return target;}
    public boolean cutter(){return cutter;}
    public boolean shop(){return shop;}
    public CareLineage lineage(){return lineage;}
    public long actions(){
        // Vanilla menu data slots carry signed shorts.
        return (available.get(0)&0xffffL)|(available.get(1)&0xffffL)<<16|(available.get(2)&0xffffL)<<32|(available.get(3)&0xffffL)<<48;
    }
    private boolean lists(CareAction action){
        return shop ? action.group == CareAction.SHOP : cutter ? action.shears() : action.group >= 0 && action.group != CareAction.SHOP;
    }
    private void updateActions(){
        long mask;
        if (shop) {
            mask = ShopTrade.cardActions(player, target);
        } else {
            mask = 0;
            for(var action:CareAction.values()){
                if(lists(action)&&InteractionDispatch.refusal(player,target,action)==null)mask|=1L<<action.id();
            }
        }
        for(int slot=0;slot<available.getCount();slot++)available.set(slot,(short)(mask>>>(slot*16)));
    }
    @Override public void broadcastChanges(){
        updateActions();
        super.broadcastChanges();
    }
    @Override public boolean stillValid(Player player){
        return InteractionDispatch.requireReach(player,target,5) && shop == (ShopTrade.shopOfStock(target) != null);
    }
    @Override public ItemStack quickMoveStack(Player player,int slot){return ItemStack.EMPTY;}
    @Override public boolean clickMenuButton(Player player,int id){
        var action=CareAction.byId(id);
        if(action==null||!lists(action))return false;
        if (shop) {
            if (!stillValid(player)) return false;
            var server = (ServerPlayer) player;
            boolean done = ShopTrade.card(server, target, action);
            if (action != CareAction.SHOP_ABOUT) server.closeContainer();
            return done;
        }
        if (target != null && ShopTrade.shopOfStock(target) != null) return false;
        boolean done=InteractionDispatch.perform(player,target,action);
        if(done||!cutter)((ServerPlayer)player).closeContainer();
        else broadcastChanges();
        return done;
    }
}
