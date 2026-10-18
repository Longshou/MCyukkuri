package io.github.longshou.mcyukkuri.human;

import io.github.longshou.mcyukkuri.entity.*;
import io.github.longshou.mcyukkuri.item.CarrierItem;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.registry.ModItems;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.*;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.*;
import net.minecraft.world.phys.Vec3;

/** Trade menu of resident and factory workers; shopkeepers, examiners and keepers use their own menus. */
public final class HumanMenu extends AbstractContainerMenu {
    /** Fixed wire values; reserved slots and ranges remain unchanged. */
    public static final class ButtonId {
        public static final int SUPPLY_FIRST = 0;
        public static final int SUPPLY_SECOND = 1;
        public static final int SUPPLY_THIRD = 2;
        public static final int SUPPLY_LAST = 3;
        public static final int SUPPLY_END = 4;
        public static final int DELIVER_INPUT = 8;
        public static final int ACCEPT_QUEST = 9;
        public static final int RETURN_LOST = 10;
        public static final int DONATE = 11;
        public static final int GUIDE = 12;
        public static final int NEXT_PAGE = 13;
        public static final int BUY_PRODUCT = 14;
        public static final int REPLENISH = 15;
        public static final int UNUSED = 16;
        public static final int CYCLE_PURPOSE = 17;
        public static final int CYCLE_SALES = 18;
        public static final int DELIVER_PARENT = 19;
        public static final int REPAIR = 20;
        public static final int COUNT = 22;
        private ButtonId() {}
    }
    /** Fixed wire values; reserved slots and ranges remain unchanged. */
    public static final class Data {
        public static final int COUNT = 54;
        public static final int TRADES_LEFT = 0;
        public static final int CARE_FOOD = 1;
        public static final int INPUT_READY = 2;
        public static final int QUEST_STATE = 3;
        public static final int DAYTIME = 4;
        public static final int PAGE = 30;
        public static final int OUTPUT = 31;
        public static final int MATERIAL = 32;
        public static final int PRODUCTION_KIND = 33;
        public static final int PRODUCT_PRICE = 38;
        public static final int PURPOSE_MODE = 39;
        public static final int SALE_FILTER = 40;
        public static final int LINE_STATUS = 41;
        public static final int INSPECTED = 42;
        public static final int HELD = 43;
        public static final int BREEDERS = 44;
        public static final int NURSERY = 45;
        public static final int INPUT = 46;
        public static final int PARENT_READY = 47;
        public static final int PRODUCT_PURPOSE = 48;
        public static final int STAGE = 49;
        public static final int WORK = 50;
        private Data() {}
    }
    private final HumanNpc target;
    private int page;
    public int page() { return display.get(Data.PAGE); }
    private final ContainerData display = new SimpleContainerData(Data.COUNT);
    public HumanMenu(int id, Inventory inventory, int entityId) {
        super(HumanWorld.MENU.get(), id);
        target = inventory.player.level().getEntity(entityId) instanceof HumanNpc n ? n : null;
        addDataSlots(display); refresh();
    }
    public HumanNpc target() { return target; }
    public int value(int index) { return display.get(index); }
    public static boolean valid(Player p, HumanNpc n) {
        return n != null && n.isAlive() && p.isAlive() && !p.isSpectator() && p.level() == n.level()
                && p.distanceToSqr(n) <= 36 && p.hasLineOfSight(n);
    }
    public static void open(Player p, HumanNpc npc) {
        if (!(p instanceof ServerPlayer server) || !valid(p, npc)) return;
        if (!npc.beginTrade(p)) { p.displayClientMessage(Component.translatable("human.mcyukkuri.busy"), true); return; }
        server.openMenu(new SimpleMenuProvider((id, inv, owner) -> new HumanMenu(id, inv, npc.getId()), npc.getDisplayName()), buf -> buf.writeVarInt(npc.getId()));
    }
    @Override public boolean stillValid(Player p) { return valid(p, target); }
    @Override public ItemStack quickMoveStack(Player p, int slot) { return ItemStack.EMPTY; }
    @Override public void removed(Player player) { super.removed(player); if (target != null) target.endTrade(player); }
    public ItemStack supply(int row) {
        // The catalog is fixed; an entity spawn packet may arrive after the menu on the client.
        return switch (page() * 4 + row) {
            case 0 -> new ItemStack(ModItems.GRAIN_REGULAR.get(), 8);
            case 1 -> new ItemStack(ModItems.CARRIER.get());
            case 2 -> new ItemStack(ModItems.WASH_CLOTH.get());
            case 3 -> new ItemStack(ModItems.ORANGE_JUICE.get());
            case 4 -> new ItemStack(ModItems.GRAIN_BUDGET.get(),16);
            case 5 -> new ItemStack(ModItems.GRAIN_PREMIUM.get(),8);
            case 6 -> new ItemStack(ModItems.FOOD_BOWL.get());
            case 7 -> new ItemStack(ModItems.NEST.get());
            case 8 -> new ItemStack(ModItems.TOILET.get());
            case 9 -> new ItemStack(ModItems.BANDAGE.get(),4);
            case 10 -> new ItemStack(ModItems.MEDICINE.get());
            case 11 -> new ItemStack(ModItems.MOLD_REMOVER.get());
            case 12 -> new ItemStack(ModItems.WHISTLE.get());
            case 13 -> new ItemStack(ModItems.TOY.get());
            case 14 -> new ItemStack(ModItems.COMPANION_CORD.get());
            case 15 -> new ItemStack(ModItems.RIBBON.get());
            case 16 -> new ItemStack(ModItems.HAT.get());
            case 17 -> new ItemStack(ModItems.CHEN_HAT.get());
            case 18 -> new ItemStack(ModItems.ALICE_HEADBAND.get());
            case 19 -> new ItemStack(ModItems.PATCHOULI_BONNET.get());
            default -> ItemStack.EMPTY;
        };
    }
    public static int supplyPrice(int row) { return switch (row) { case 0 -> 1; case 1 -> 3; case 2, 3, 4, 13 -> 2; case 5, 6, 8, 9, 10, 11 -> 3; case 7, 12, 14, 15, 16, 17, 18, 19 -> 4; default -> 0; }; }
    private void refresh() {
        if (target == null || target.level().isClientSide) return;
        display.set(Data.TRADES_LEFT, target.remainingTrades()); display.set(Data.CARE_FOOD, target.careFood()); display.set(Data.INPUT_READY, target.canReceiveInput() ? 1 : 0);
        display.set(Data.QUEST_STATE, target.lostAnimal() == null ? 0 : target.questComplete() ? 2 : 1);
        display.set(Data.DAYTIME, target.level().isDay() ? 1 : 0);
        display.set(Data.PAGE,page);display.set(Data.OUTPUT,target.production().outputCount());display.set(Data.MATERIAL,target.production().material());
        display.set(Data.PRODUCTION_KIND,switch(target.production().kind()){case "labor"->1;case "manju"->2;case "bag"->3;default->0;});
        display.set(Data.PRODUCT_PRICE,target.production().productPrice());
        var flow=target.production().pipeline();display.set(Data.PURPOSE_MODE,flow.purposeMode());display.set(Data.SALE_FILTER,flow.saleFilter());display.set(Data.LINE_STATUS,flow.status());
        display.set(Data.INSPECTED,flow.inspected());display.set(Data.HELD,flow.held());display.set(Data.BREEDERS,target.animals(Custody.Role.BREEDER).size());
        display.set(Data.NURSERY,target.animals(Custody.Role.NURSERY).size());display.set(Data.INPUT,target.animals(Custody.Role.INPUT).size());display.set(Data.PARENT_READY,target.canReceiveParent()?1:0);
        display.set(Data.STAGE,flow.activeStage());display.set(Data.WORK,flow.activeWork());
        display.set(Data.PRODUCT_PURPOSE,FactoryPipeline.purpose(target.production().product()));
    }
    @Override public void broadcastChanges() { refresh(); super.broadcastChanges(); }
    private static int count(Player p, Item item) { return p.getInventory().items.stream().filter(s -> s.is(item)).mapToInt(ItemStack::getCount).sum(); }
    private static void debit(Player p, Item item, int amount) {
        for (ItemStack stack : p.getInventory().items) if (stack.is(item)) { int used = Math.min(amount, stack.getCount()); stack.shrink(used); amount -= used; if (amount == 0) return; }
    }
    private static boolean room(Player p) { return p.getInventory().getFreeSlot() >= 0; }
    private static void give(Player p, ItemStack stack) { if (!p.getInventory().add(stack)) p.drop(stack, false); }
    private boolean buySupply(Player p, int row) {
        int price = supplyPrice(page * 4 + row);
        if(price<=0||supply(row).isEmpty())return false;
        if (target.remainingTrades() <= 0 || count(p, Items.EMERALD) < price || !room(p)) return false;
        debit(p, Items.EMERALD, price); give(p, supply(row)); target.usedTrade(); return true;
    }
    /** Inspect the authoritative token record, never the item's editable display UUID. */
    private boolean deliver(Player p, boolean rescue) {return deliver(p,rescue,false);}
    private boolean deliver(Player p, boolean rescue,boolean parent) {return deliver(target,p,rescue,parent);}
    /** A missing companion brought back to the resident who asked for it (also used by residents without a menu). */
    public static boolean returnLost(Player p, HumanNpc npc) {return deliver(npc,p,true,false);}
    private static boolean deliver(HumanNpc target,Player p, boolean rescue,boolean parent) {
        if (!(p.level() instanceof ServerLevel level)) return false;
        if (CustodyData.get(level).deadNpc(target.getUUID())) return false;
        ItemStack hand = p.getMainHandItem(); var snapshot = HumanCommerce.heldData(level, hand);
        if (!snapshot.hasUUID("UUID")) return false;
        UUID identity = snapshot.getUUID("UUID");
        var custody = Custody.fromSnapshot(snapshot);
        if (rescue) {
            if (target.questComplete() || !identity.equals(target.lostAnimal()) || !p.getUUID().equals(target.questPlayer())) return false;
        } else if (!(parent?target.canReceiveParent():target.canReceiveInput()) || custody.owner() == null || !custody.owner().equals(p.getUUID())
                || custody.role() == Custody.Role.LOST) return false;
        if(parent){var family=snapshot.getCompound("YukkuriFamily");
            if(!family.getString("Stage").equals("ADULT")||family.getBoolean("Sterile")||snapshot.getString("id").contains("house_"))return false;}
        // A bounded search in loaded blocks allows obstructions to refuse delivery without retiring the token.
        YukkuriEntity released = null;
        for (BlockPos pos : BlockPos.betweenClosed(target.blockPosition().offset(-2, 0, -2), target.blockPosition().offset(2, 0, 2))) {
            if (!level.hasChunkAt(pos) || !level.getBlockState(pos.below()).isSolidRender(level, pos.below())) continue;
            released = YukkuriWorldData.get(level).release(CarrierItem.token(hand), level, Vec3.atBottomCenterOf(pos), 0);
            if (released != null) break;
        }
        if (released == null) return false;
        Custody.assign(released, target, rescue ? Custody.Role.COMPANION : parent ? Custody.Role.RESERVE_PARENT : Custody.Role.INPUT);
        hand.remove(DataComponents.CUSTOM_DATA);
        if (rescue) target.completeQuest(); else target.receivedInput();
        give(p, new ItemStack(Items.EMERALD, rescue ? 5 : 3)); return true;
    }
    @Override public boolean clickMenuButton(Player p, int id) {
        if (p.level().isClientSide || !stillValid(p) || id < ButtonId.SUPPLY_FIRST || id > ButtonId.REPAIR) return false;
        boolean result;
        if(id>=ButtonId.CYCLE_PURPOSE){
            if(!p.level().isDay()||target.job()!=HumanNpc.Job.FACTORY)return false;
            var flow=target.production().pipeline();
            if(id==ButtonId.CYCLE_PURPOSE)flow.cyclePurpose();else if(id==ButtonId.CYCLE_SALES)flow.cycleSales();
            else if(id==ButtonId.DELIVER_PARENT)return deliver(p,false,true);else if(id==ButtonId.REPAIR)return flow.repair(p.getMainHandItem());
            broadcastChanges();return true;
        }
        if(id==ButtonId.NEXT_PAGE){page=(page+1)%5;broadcastChanges();return true;}
        if(id==ButtonId.BUY_PRODUCT){
            int cost=target.production().productPrice();
            if(!p.level().isDay()||target.job()!=HumanNpc.Job.FACTORY||target.production().outputCount()==0||!room(p)||cost<=0||count(p,Items.EMERALD)<cost)return false;
            var product=target.production().take();debit(p,Items.EMERALD,cost);give(p,product);broadcastChanges();return true;
        }
        if(id==ButtonId.REPLENISH){boolean ok=p.level().isDay()&&target.job()==HumanNpc.Job.FACTORY&&target.production().replenish(p.getMainHandItem());broadcastChanges();return ok;}
        if (id == ButtonId.GUIDE) {
            p.sendSystemMessage(Component.translatable("human.mcyukkuri.guide." + target.job().name().toLowerCase(Locale.ROOT))); result = true;
        } else if (!p.level().isDay()) result = false;
        else if (id < ButtonId.SUPPLY_END) result = buySupply(p, id);
        else if (id < ButtonId.DELIVER_INPUT || id == ButtonId.UNUSED) return false;
        else if (id == ButtonId.DELIVER_INPUT) result = deliver(p, false);
        else if (id == ButtonId.ACCEPT_QUEST) result = target.acceptQuest(p);
        else if (id == ButtonId.RETURN_LOST) result = deliver(p, true);
        else {
            result = count(p, ModItems.GRAIN_REGULAR.get()) >= 8 && target.donateFood();
            if (result) debit(p, ModItems.GRAIN_REGULAR.get(), 8);
        }
        p.displayClientMessage(Component.translatable(result ? "human.mcyukkuri.success" : "human.mcyukkuri.unavailable"), true);
        broadcastChanges(); return result;
    }
}
