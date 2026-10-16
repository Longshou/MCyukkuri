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

/** Fixed quotes per menu prevent two customers or a changing stock order from selecting another animal. */
public final class HumanMenu extends AbstractContainerMenu {
    private record Quote(UUID identity, int price) {}
    private final HumanNpc target;
    private int page;
    private int animalPage;
    public int page() { return display.get(30); }
    private final List<Quote> quotes = new ArrayList<>();
    private final ContainerData display = new SimpleContainerData(54);
    public HumanMenu(int id, Inventory inventory, int entityId) {
        super(HumanWorld.MENU.get(), id);
        target = inventory.player.level().getEntity(entityId) instanceof HumanNpc n ? n : null;
        if (!inventory.player.level().isClientSide && target != null && target.job() == HumanNpc.Job.SHOPKEEPER)
            target.animals("stock").stream().sorted(Comparator.comparingInt((YukkuriEntity y)->y.getVariant().ordinal())
                    .thenComparing(Comparator.comparingInt((YukkuriEntity y)->y.life().medal()).reversed()).thenComparing(YukkuriEntity::getUUID))
                    .limit(64).forEach(y -> quotes.add(new Quote(y.getUUID(), price(y))));
        addDataSlots(display); refresh();
    }
    public HumanNpc target() { return target; }
    public int value(int index) { return display.get(index); }
    public static int price(YukkuriEntity y) { return 6 + y.life().medal() * 12 + y.life().personality().lessons() * 2
            + (y.getVariant().isPredator() ? 24 : y.getVariant() == YukkuriVariant.PATCHOULI ? 4 : 0); }
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
        display.set(0, target.remainingTrades()); display.set(1, target.careFood()); display.set(2, target.canReceiveInput() ? 1 : 0);
        display.set(3, target.lostAnimal() == null ? 0 : target.questComplete() ? 2 : 1);
        display.set(4, target.level().isDay() ? 1 : 0);
        display.set(30,page);display.set(31,target.production().outputCount());display.set(32,target.production().material());
        display.set(33,switch(target.production().kind()){case "labor"->1;case "manju"->2;case "bag"->3;default->0;});
        display.set(38,target.production().productPrice());
        var flow=target.production().pipeline();display.set(39,flow.purposeMode());display.set(40,flow.saleFilter());display.set(41,flow.status());
        display.set(42,flow.inspected());display.set(43,flow.held());display.set(44,target.animals("breeder").size());
        display.set(45,target.animals("nursery").size());display.set(46,target.animals("input").size());display.set(47,target.canReceiveParent()?1:0);
        display.set(49,flow.activeStage());display.set(50,flow.activeWork());
        display.set(48,FactoryPipeline.purpose(target.production().product()));
        display.set(52, animalPage); display.set(53, Math.max(1, (quotes.size()+3)/4));
        for (int i = 0; i < 4; i++) {
            var y = quotedAnimal(i); int k = 6 + i * 6;
            display.set(k, y == null ? 0 : y.getVariant().ordinal() + 1);
            display.set(k + 1, i + animalPage*4 < quotes.size() ? quotes.get(i + animalPage*4).price : 0);
            display.set(k + 2, y == null ? 0 : Math.round(y.getHealth()));
            display.set(k + 3, y == null ? 0 : Math.round(y.life().nutrition()));
            display.set(k + 4, y == null ? 0 : !y.family().adult() && y.childhood().canLiveIndependently() ? 3 : y.family().stage().ordinal());
            display.set(34+i,y==null?0:y.life().medal());
            display.set(k + 5, y == null ? 0 : y.life().personality().lessons());
        }
    }
    @Override public void broadcastChanges() { refresh(); super.broadcastChanges(); }
    private YukkuriEntity quotedAnimal(int row) {
        if (row < 0 || row >= 4) return null;
        row += animalPage * 4;
        if (target == null || row < 0 || row >= quotes.size() || !(target.level() instanceof ServerLevel level)) return null;
        return level.getEntity(quotes.get(row).identity) instanceof YukkuriEntity y && y.isAlive() && target.distanceToSqr(y) <= 48 * 48
                && HumanCommerce.ownedBy(y, target.getUUID()) && HumanCommerce.data(y).getString("Use").equals("stock") ? y : null;
    }
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
    private boolean buyAnimal(Player p, int row) {
        var animal = quotedAnimal(row);
        if (animal == null || target.job() != HumanNpc.Job.SHOPKEEPER || !room(p)) return false;
        int price = quotes.get(row + animalPage*4).price;
        if (count(p, Items.EMERALD) < price) return false;
        var previous = HumanCommerce.data(animal).copy();
        HumanCommerce.assign(animal, p.getUUID(), "companion");
        UUID token = YukkuriWorldData.get((ServerLevel)p.level()).capture(animal);
        if (token == null) { HumanCommerce.set(animal, previous); return false; }
        debit(p, Items.EMERALD, price); give(p, HumanCommerce.carrier(animal, token)); return true;
    }
    /** Inspect the authoritative token record, never the item's editable display UUID. */
    private boolean deliver(Player p, boolean rescue) {return deliver(p,rescue,false);}
    private boolean deliver(Player p, boolean rescue,boolean parent) {
        if (!(p.level() instanceof ServerLevel level)) return false;
        ItemStack hand = p.getMainHandItem(); var snapshot = HumanCommerce.heldData(level, hand);
        if (!snapshot.hasUUID("UUID")) return false;
        UUID identity = snapshot.getUUID("UUID");
        var custody = snapshot.getCompound("NeoForgeData").getCompound("HumanCommerce");
        if (rescue) {
            if (target.questComplete() || !identity.equals(target.lostAnimal()) || !p.getUUID().equals(target.questPlayer())) return false;
        } else if (!(parent?target.canReceiveParent():target.canReceiveInput()) || !custody.hasUUID("Owner") || !custody.getUUID("Owner").equals(p.getUUID())
                || custody.getString("Use").equals("lost")) return false;
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
        HumanCommerce.assign(released, target.getUUID(), rescue ? "companion" : parent ? "reserve_parent" : "input");
        hand.remove(DataComponents.CUSTOM_DATA);
        if (rescue) target.completeQuest(); else target.receivedInput();
        give(p, new ItemStack(Items.EMERALD, rescue ? 5 : 3)); return true;
    }
    @Override public boolean clickMenuButton(Player p, int id) {
        if (p.level().isClientSide || !stillValid(p) || id < 0 || id > 21) return false;
        boolean result;
        if (id == 21) {
            if (target.job() != HumanNpc.Job.SHOPKEEPER) return false;
            animalPage = (animalPage + 1) % Math.max(1, (quotes.size()+3)/4); broadcastChanges(); return true;
        }
        if(id>=17){
            if(!p.level().isDay()||target.job()!=HumanNpc.Job.FACTORY)return false;
            var flow=target.production().pipeline();
            if(id==17)flow.cyclePurpose();else if(id==18)flow.cycleSales();
            else if(id==19)return deliver(p,false,true);else if(id==20)return flow.repair(p.getMainHandItem());
            broadcastChanges();return true;
        }
        if(id==16) {
            if (target.job()!=HumanNpc.Job.SHOPKEEPER) return false;
            io.github.longshou.mcyukkuri.badge.BadgeMenu.open(p,target,null); return true;
        }
        if(id==13){page=(page+1)%5;broadcastChanges();return true;}
        if(id==14){
            int cost=target.production().productPrice();
            if(!p.level().isDay()||target.job()!=HumanNpc.Job.FACTORY||target.production().outputCount()==0||!room(p)||cost<=0||count(p,Items.EMERALD)<cost)return false;
            var product=target.production().take();debit(p,Items.EMERALD,cost);give(p,product);broadcastChanges();return true;
        }
        if(id==15){boolean ok=p.level().isDay()&&target.job()==HumanNpc.Job.FACTORY&&target.production().replenish(p.getMainHandItem());broadcastChanges();return ok;}
        if (id == 12) {
            p.sendSystemMessage(Component.translatable("human.mcyukkuri.guide." + target.job().name().toLowerCase(Locale.ROOT))); result = true;
        } else if (!p.level().isDay()) result = false;
        else if (id < 4) result = buySupply(p, id);
        else if (id < 8) result = buyAnimal(p, id - 4);
        else if (id == 8) result = deliver(p, false);
        else if (id == 9) result = target.acceptQuest(p);
        else if (id == 10) result = deliver(p, true);
        else {
            result = count(p, ModItems.GRAIN_REGULAR.get()) >= 8 && target.donateFood();
            if (result) debit(p, ModItems.GRAIN_REGULAR.get(), 8);
        }
        p.displayClientMessage(Component.translatable(result ? "human.mcyukkuri.success" : "human.mcyukkuri.unavailable"), true);
        broadcastChanges(); return result;
    }
}
