package io.github.longshou.mcyukkuri.shop;

import io.github.longshou.mcyukkuri.social.BondLevels;
import io.github.longshou.mcyukkuri.social.Temperament;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.human.HumanCommerce;
import io.github.longshou.mcyukkuri.human.HumanMenu;
import io.github.longshou.mcyukkuri.human.HumanNpc;
import io.github.longshou.mcyukkuri.human.HumanWorld;
import io.github.longshou.mcyukkuri.life.CareBlock;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.pet.Ability;
import io.github.longshou.mcyukkuri.pet.BadgeGrade;
import io.github.longshou.mcyukkuri.pet.PetLife;
import io.github.longshou.mcyukkuri.pet.PetPrivileges;
import io.github.longshou.mcyukkuri.registry.ModItems;
import io.github.longshou.mcyukkuri.social.YukkuriPersonality.Trait;
import java.util.*;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Shopkeeper UI with four tabs. Rows and subjects are fixed server-side; button packets never carry an animal id.
 *  Button ids: 0-3 tab (live, goods, buyback, consult); 4-6 buy live row (PAGE_ROWS = 3 rows per page); 8-10 locate row; 12/13 prev/next page;
 *  14-17 buy goods row; 18 sell subject; 19 exam forecast; 20/21 prev/next subject; 22-24 about (species notes and transport advice in chat) for live row.
 *  Data slots (shorts): 0 tab, 1 trades left, 2 daytime, 3 page, 4 page count, 5 shop stock count (Use stock only, capped at STOCK_CAPACITY), 6 subject count, 7 subject index+1;
 *  from 8, live rows of 12: species+1, stage, flags (grade | expired<<2 | display<<3 | locked<<4 | factory<<5), pedigree, 4 abilities, tags (a|b<<4, 15 none), health, nutrition, price;
 *  subject block (buyback/consult) from 8: species+1, stage, flags, pedigree, 4 abilities, health, nutrition, hygiene, stress, indulgence,
 *  bowl/toilet/sleep routines (21-23), unauthorized (24), weakest ability (25), advice x3 (26-28), price/offer (29), forecast bronze/silver/gold (30-32; 0 none, 1 high, 2 mid, 3 low),
 *  forecast bought (33), buyback refusal (34; 0 ok, 1 shop full, 2 no free pen), tags (35), title (36; 0 none, 1 eternal silver, 2 hidden gold).
 *  Version 2 (goods stock, delivery orders, privileges; existing ids and slots unchanged): button 25 orders tab, 26-28 deliver order 1-3. Slot 1 is now the buyer's own trades left today at this shopkeeper.
 *  Slots 57-60 shelf stock of the four goods rows of the page (0 = sold out), 61-64 badge needed for each (0 none, 1 bronze, 2 silver), 65 orders delivered today (bit per order),
 *  66-68 today's three orders (pool index+1), 69 buyback rate percent, 70 buyback penalty percent, 71 the buyer's own badge standing (0-3). */
public final class ShopMenu extends AbstractContainerMenu {
    public static final int PAGE_ROWS = 3, LIVE = 0, GOODS = 1, BUYBACK = 2, CONSULT = 3, ORDERS = 4, CAPACITY = 16, STOCK_CAPACITY = 12, DATA_SIZE = 72;
    private record Quote(UUID id, int price) {}
    public static int goodsPages() { return ShopCatalog.pages(); }
    public static int goodsCategory(int page) { return ShopCatalog.category(page); }
    public static ItemStack goodsStack(int page, int row) { var good = ShopCatalog.at(ShopCatalog.index(page, row)); return good == null ? ItemStack.EMPTY : good.stack(); }
    public static int goodsPrice(int page, int row) { var good = ShopCatalog.at(ShopCatalog.index(page, row)); return good == null ? 0 : good.price(); }
    /** Badge needed for the good in this row (0 none, 1 bronze, 2 silver). */
    public static int goodsUnlock(int page, int row) { var good = ShopCatalog.at(ShopCatalog.index(page, row)); return good == null ? 0 : good.unlock(); }

    private final HumanNpc target;
    private final Player viewer;
    private final List<Quote> quotes = new ArrayList<>();
    private final List<UUID> mine = new ArrayList<>();
    private final Map<UUID, int[]> forecasts = new HashMap<>();
    private final ContainerData data = new SimpleContainerData(DATA_SIZE);
    private int tab, page, selected, shopCount, focus = -1, standing, standingClock;
    private boolean penOk, custom;
    private final Set<UUID> locating = new HashSet<>();

    public ShopMenu(int id, Inventory inventory, int entityId) { this(id, inventory, entityId, null); }
    /** Server side only passes a focus animal (opened by clicking it): the menu starts on the LIVE page that lists it with its row highlighted (slot 56). */
    private ShopMenu(int id, Inventory inventory, int entityId, UUID focusAnimal) {
        super(HumanWorld.SHOP_MENU.get(), id); viewer = inventory.player;
        target = viewer.level().getEntity(entityId) instanceof HumanNpc n ? n : null;
        if (!viewer.level().isClientSide && target != null && target.job() == HumanNpc.Job.SHOPKEEPER)
            target.animals("stock").stream().sorted(Comparator.comparingInt((YukkuriEntity y) -> -y.pet().grade().id())
                    .thenComparingInt(y -> y.getVariant().ordinal()).thenComparing(YukkuriEntity::getUUID))
                    .limit(64).forEach(y -> quotes.add(new Quote(y.getUUID(), ShopPricing.price(y))));
        if (focusAnimal != null) for (int i = 0; i < quotes.size(); i++) if (quotes.get(i).id.equals(focusAnimal)) { focus = i; page = i / PAGE_ROWS; }
        addDataSlots(data); refresh();
    }
    public static void open(Player p, HumanNpc npc) { open(p, npc, null); }
    public static void open(Player p, HumanNpc npc, YukkuriEntity focusAnimal) {
        if (!(p instanceof ServerPlayer server) || !HumanMenu.valid(p, npc)) return;
        if (!npc.beginTrade(p)) { p.displayClientMessage(Component.translatable("human.mcyukkuri.busy"), true); return; }
        UUID focus = focusAnimal == null ? null : focusAnimal.getUUID();
        server.openMenu(new SimpleMenuProvider((id, inv, owner) -> new ShopMenu(id, inv, npc.getId(), focus), npc.getDisplayName()), buf -> buf.writeVarInt(npc.getId()));
    }
    /** Right-click on a stock animal (empty hand, not sneaking) with its shopkeeper owner within 12 blocks: the animal appeals (bold) or bows, and that shopkeeper's menu opens on its row. */
    public static boolean clickStock(Player p, YukkuriEntity y) {
        if (!(p instanceof ServerPlayer) || !(y.level() instanceof ServerLevel level)) return false;
        var commerce = HumanCommerce.data(y);
        if (!commerce.getString("Use").equals("stock") || !commerce.hasUUID("Owner")) return false;
        if (!(level.getEntity(commerce.getUUID("Owner")) instanceof HumanNpc npc) || npc.job() != HumanNpc.Job.SHOPKEEPER || !npc.isAlive() || npc.distanceToSqr(p) > 144) return false;
        if (y.life().personality().trait(Trait.BOLDNESS) >= Temperament.BOLDNESS_AT_40) { y.playAction("beg"); y.speak("shop_appeal"); } else y.playAction("bow");
        if (!HumanMenu.valid(p, npc)) { p.displayClientMessage(Component.translatable("shop.mcyukkuri.too_far"), true); return true; }
        open(p, npc, y); return true;
    }
    public HumanNpc target() { return target; }
    public int value(int index) { return data.get(index); }
    @Override public boolean stillValid(Player p) { return HumanMenu.valid(p, target); }
    @Override public ItemStack quickMoveStack(Player p, int slot) { return ItemStack.EMPTY; }
    @Override public void removed(Player player) { super.removed(player); if (target != null) target.endTrade(player); }

    // helpers
    private static int count(Player p, Item item) { return p.getInventory().items.stream().filter(s -> s.is(item)).mapToInt(ItemStack::getCount).sum(); }
    private static void debit(Player p, Item item, int amount) {
        for (ItemStack stack : p.getInventory().items) if (stack.is(item)) { int used = Math.min(amount, stack.getCount()); stack.shrink(used); amount -= used; if (amount == 0) return; }
    }
    /** Emeralds in stacks of at most 64. */
    private static void giveEmeralds(Player p, int amount) { for (; amount > 0; amount -= 64) give(p, new ItemStack(Items.EMERALD, Math.min(64, amount))); }
    static void deliverEmeralds(ServerLevel level, UUID player, Vec3 at, int amount) { for (; amount > 0; amount -= 64) deliver(level, player, at, new ItemStack(Items.EMERALD, Math.min(64, amount))); }
    private static boolean room(Player p) { return p.getInventory().getFreeSlot() >= 0; }
    static void give(Player p, ItemStack stack) { if (!p.getInventory().add(stack)) p.drop(stack, false); }
    /** Hands over to the player if still online, otherwise leaves it on the ground where the scene happened. */
    static void deliver(ServerLevel level, UUID player, Vec3 at, ItemStack stack) {
        ServerPlayer sp = level.getServer().getPlayerList().getPlayer(player);
        if (sp != null) give(sp, stack);
        else { var item = new ItemEntity(level, at.x, at.y + .15, at.z, stack); item.setDefaultPickUpDelay(); level.addFreshEntity(item); }
    }
    static boolean stockOf(YukkuriEntity y, UUID shop) { return HumanCommerce.ownedBy(y, shop) && HumanCommerce.data(y).getString("Use").equals("stock"); }
    private static int stage(YukkuriEntity y) { return !y.family().adult() && y.childhood().canLiveIndependently() ? 3 : y.family().stage().ordinal(); }
    private static int[] tags(YukkuriEntity y) {
        var p = y.life().personality(); int[] out = {15, 15}; int n = 0;
        int[] found = {p.attachment() >= Temperament.ATTACHMENT_AT_65 ? 1 : 0, p.selfishness() >= Temperament.SELFISHNESS_AT_65 ? 2 : 0, p.selfControl() <= Temperament.SELF_CONTROL_AT_35 ? 3 : 0, p.selfControl() >= Temperament.SELF_CONTROL_AT_65 ? 4 : 0,
                p.trait(Trait.BOLDNESS) <= Temperament.BOLDNESS_AT_35 ? 5 : 0, p.trait(Trait.CURIOSITY) >= Temperament.CURIOSITY_AT_65 ? 6 : 0};
        for (int t : found) if (t != 0 && n < 2) out[n++] = t;
        if (n == 0) out[0] = 0;
        return out;
    }
    private static int flags(YukkuriEntity y, boolean locked) {
        var b = y.pet(); return b.grade().id() | (b.expired() ? 4 : 0) | (b.grade().id() >= BadgeGrade.SILVER.id() ? 8 : 0) | (locked ? 16 : 0) | (ShopDeliveries.factoryOrigin(y) ? 32 : 0);
    }
    private void put(int index, int value) { data.set(index, Math.clamp(value, -32768, 32767)); }
    private int pageCount() { return tab == LIVE ? Math.max(1, (quotes.size() + PAGE_ROWS - 1) / PAGE_ROWS) : tab == GOODS ? goodsPages() : 1; }
    private UUID shopId() { return target.getUUID(); }

    private YukkuriEntity quoted(int index) {
        if (target == null || index < 0 || index >= quotes.size() || !(target.level() instanceof ServerLevel level)) return null;
        return level.getEntity(quotes.get(index).id) instanceof YukkuriEntity y && y.isAlive() && target.distanceToSqr(y) <= 48 * 48 && stockOf(y, target.getUUID()) ? y : null;
    }
    private boolean mayHandle(YukkuriEntity y, Player p) {
        return y != null && y.isAlive() && y.pet().ownedBy(p.getUUID()) && !y.pet().busy() && !y.isPassenger() && y.distanceToSqr(p) <= 64;
    }
    private YukkuriEntity subject() {
        if (mine.isEmpty() || !(viewer.level() instanceof ServerLevel level)) return null;
        return level.getEntity(mine.get(Math.min(selected, mine.size() - 1))) instanceof YukkuriEntity y && y.isAlive() ? y : null;
    }
    private void rescan() {
        mine.clear(); if (!(viewer.level() instanceof ServerLevel level)) return;
        level.getEntitiesOfClass(YukkuriEntity.class, viewer.getBoundingBox().inflate(8), y -> mayHandle(y, viewer)).stream()
                .sorted(Comparator.comparing(YukkuriEntity::getUUID)).limit(32).forEach(y -> mine.add(y.getUUID()));
        selected = mine.isEmpty() ? 0 : Math.min(selected, mine.size() - 1); evaluate();
    }
    private int population(ServerLevel level) {
        return ShopPens.stock(level, target);
    }
    private void evaluate() {
        shopCount = 0; penOk = false;
        if (tab != BUYBACK || target == null || !(viewer.level() instanceof ServerLevel level)) return;
        shopCount = population(level); var y = subject(); penOk = y != null && ShopPens.freePen(level, target, y) != null;
    }
    // data
    private void refresh() {
        if (viewer.level().isClientSide || target == null) return;
        for (int i = 0; i < DATA_SIZE; i++) data.set(i, 0);
        var shopLevel = target.level() instanceof ServerLevel sl ? sl : null;
        put(0, tab); put(1, shopLevel == null ? 0 : ShopStock.get(shopLevel).tradesLeft(shopLevel, shopId(), viewer.getUUID())); put(2, target.level().isDay() ? 1 : 0); put(3, page); put(4, pageCount());
        if (standingClock-- <= 0) { standingClock = 20; standing = PetPrivileges.grade(viewer).id(); }
        put(71, standing);
        if (shopLevel != null && tab == GOODS) {
            var stocks = ShopStock.get(shopLevel);
            for (int row = 0; row < 4; row++) {
                var good = ShopCatalog.at(ShopCatalog.index(page, row)); if (good == null) continue;
                put(57 + row, stocks.stock(shopLevel, shopId(), good)); put(61 + row, good.unlock());
            }
        }
        if (shopLevel != null && tab == ORDERS) {
            var orders = ShopOrders.today(shopLevel, shopId());
            for (int i = 0; i < ShopOrders.PER_DAY; i++) put(66 + i, orders[i] + 1);
            put(65, ShopStock.get(shopLevel).ordersDone(shopLevel, shopId(), viewer.getUUID()));
        }
        put(5, shopCount); put(6, mine.size()); put(7, mine.isEmpty() ? 0 : selected + 1);
        if (tab == LIVE && focus >= 0 && focus / PAGE_ROWS == page) put(56, focus % PAGE_ROWS + 1);
        if (tab == LIVE) {
            for (int i = 0; i < PAGE_ROWS; i++) {
                int idx = page * PAGE_ROWS + i; var y = quoted(idx); if (y == null) continue; int k = 8 + i * 12; var t = tags(y); var b = y.pet();
                put(k, y.getVariant().ordinal() + 1); put(k + 1, stage(y)); put(k + 2, flags(y, b.busy())); put(k + 3, b.pedigree());
                for (var a : Ability.values()) put(k + 4 + a.ordinal(), b.ability(a));
                put(k + 8, t[0] | t[1] << 4); put(k + 9, Math.round(y.getHealth())); put(k + 10, Math.round(y.life().nutrition())); put(k + 11, quotes.get(idx).price);
            }
        } else if (tab == BUYBACK || tab == CONSULT) {
            var y = subject(); if (y == null) return; var b = y.pet(); var t = tags(y);
            put(8, y.getVariant().ordinal() + 1); put(9, stage(y)); put(10, flags(y, b.busy())); put(11, b.pedigree());
            int weakest = 0;
            for (var a : Ability.values()) { put(12 + a.ordinal(), b.ability(a)); if (b.ability(a) < b.ability(Ability.values()[weakest])) weakest = a.ordinal(); }
            put(16, Math.round(y.getHealth())); put(17, Math.round(y.life().nutrition())); put(18, Math.round(y.life().hygiene()));
            put(19, b.stress()); put(20, b.indulgence()); put(21, b.routine(0)); put(22, b.routine(1)); put(23, b.routine(2)); put(24, b.unauthorizedBirths()); put(25, weakest);
            List<Integer> advice = new ArrayList<>();
            if (b.title() == PetLife.TITLE_ETERNAL_SILVER) advice.add(12);
            if (b.title() == PetLife.TITLE_HIDDEN_GOLD) advice.add(13);
            if (b.stress() > BondLevels.UNEASE_CARE_LIMIT) advice.add(1);
            if (b.indulgence() > 50) advice.add(2);
            if (b.routine(1) < 2) advice.add(3);
            if (b.routine(0) < 2) advice.add(4);
            if (b.ability(Ability.values()[weakest]) < 80) advice.add(5 + weakest);
            if (b.expired()) advice.add(9);
            if (b.unauthorizedBirths() > 0) advice.add(10);
            if (b.pedigree() < 30) advice.add(11);
            for (int i = 0; i < Math.min(3, advice.size()); i++) put(26 + i, advice.get(i));
            put(29, tab == BUYBACK ? ShopPricing.buyback(y) : ShopPricing.price(y)); put(69, ShopPricing.rate(y)); put(70, ShopPricing.penalty(y));
            var f = forecasts.get(y.getUUID()); put(33, f == null ? 0 : 1);
            if (f != null) for (int i = 0; i < 3; i++) put(30 + i, f[i]);
            put(34, tab != BUYBACK ? 0 : shopCount >= STOCK_CAPACITY ? 1 : penOk ? 0 : 2); put(35, t[0] | t[1] << 4); put(36, b.title());
        }
    }
    @Override public void broadcastChanges() { refresh(); super.broadcastChanges(); }

    // actions
    private boolean buyLive(Player p, int row) {
        if (row < 0 || row >= PAGE_ROWS) return false;
        int idx = page * PAGE_ROWS + row; var animal = quoted(idx);
        if (animal == null || animal.pet().busy() || animal.isPassenger() || !room(p) || !(p.level() instanceof ServerLevel level)) return false;
        int price = quotes.get(idx).price;
        if (count(p, Items.EMERALD) < price || !ShopTrade.begin(level, target, animal, p.getUUID(), price)) return false;
        debit(p, Items.EMERALD, price);
        return true;
    }
    /** The clerk puts an empty basket away. */
    static void handBack(HumanNpc clerk) {
        var held = clerk.getMainHandItem();
        if (held.is(ModItems.CARRIER.get()) && io.github.longshou.mcyukkuri.item.CarrierItem.token(held) == null) clerk.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
    }
    private boolean locate(Player p, int row) {
        if (row < 0 || row >= PAGE_ROWS) return false;
        var animal = quoted(page * PAGE_ROWS + row); if (animal == null || !(p.level() instanceof ServerLevel level)) return false;
        UUID id = animal.getUUID();
        if (!locating.add(id)) { custom = true; return false; } // its beam is still running
        p.displayClientMessage(Component.translatable("shop.mcyukkuri.locate_hint", animal.getDisplayName()), true); custom = true;
        ShopPerformance.schedule(level, age -> {
            var e = level.getEntity(id); if (e == null) { locating.remove(id); return true; }
            if (age % 3 == 0) for (int k = 0; k < 6; k++) level.sendParticles(ParticleTypes.END_ROD, e.getX(), e.getY() + e.getBbHeight() + .3 + k * .35, e.getZ(), 1, .05, .05, .05, 0);
            if (age >= 100) { locating.remove(id); return true; }
            return false;
        });
        return true;
    }
    /** R2: the clerk explains the species and gives transport advice, as three chat lines. */
    private boolean about(Player p, int row) {
        if (row < 0 || row >= PAGE_ROWS) return false;
        var animal = quoted(page * PAGE_ROWS + row); if (animal == null) return false;
        var v = animal.getVariant(); String id = v.assetId(); String note = v.isPredator() ? "predator" : id;
        var name = target.getDisplayName();
        p.sendSystemMessage(Component.translatable("shop.mcyukkuri.about.say", name, Component.translatable("shop.mcyukkuri.about.head", Component.translatable("entity.mcyukkuri." + id))));
        p.sendSystemMessage(Component.translatable("shop.mcyukkuri.about.say", name, Component.translatable("shop.mcyukkuri.about.note." + note)));
        p.sendSystemMessage(Component.translatable("shop.mcyukkuri.about.say", name, Component.translatable("shop.mcyukkuri.about.transport")));
        custom = true; return true;
    }
    private boolean buyGood(Player p, int row) {
        var good = ShopCatalog.at(ShopCatalog.index(page, row));
        if (good == null || !(p.level() instanceof ServerLevel level)) return false;
        int price = good.price(); var stack = good.stack(); var stocks = ShopStock.get(level);
        if (price <= 0 || stack.isEmpty()) return false;
        if (!PetPrivileges.allows(p, good.unlock())) { p.displayClientMessage(Component.translatable("shop.mcyukkuri.locked." + good.unlock()), true); custom = true; return false; }
        if (stocks.stock(level, shopId(), good) <= 0) { p.displayClientMessage(Component.translatable("shop.mcyukkuri.sold_out", stack.getHoverName()), true); custom = true; return false; }
        if (stocks.tradesLeft(level, shopId(), p.getUUID()) <= 0 || count(p, Items.EMERALD) < price || !room(p) || !stocks.take(level, shopId(), good)) return false;
        debit(p, Items.EMERALD, price); give(p, stack); stocks.useTrade(level, shopId(), p.getUUID()); return true;
    }
    /** Delivery order 0-2 of today. */
    private boolean deliverOrder(Player p, int slot) {
        if (!(p.level() instanceof ServerLevel level)) return false;
        boolean ok = ShopOrders.deliver(level, shopId(), p, slot);
        if (!ok) { p.displayClientMessage(Component.translatable("shop.mcyukkuri.order.unavailable"), true); custom = true; }
        return ok;
    }
    private boolean sell(Player p) {
        var a = subject(); if (a == null || !mayHandle(a, p) || !(p.level() instanceof ServerLevel level)) return false;
        shopCount = population(level);
        if (shopCount >= STOCK_CAPACITY) { p.displayClientMessage(Component.translatable("shop.mcyukkuri.full"), true); custom = true; return false; }
        BlockPos pen = ShopPens.freePen(level, target, a);
        if (pen == null) { p.displayClientMessage(Component.translatable("shop.mcyukkuri.no_pen"), true); custom = true; return false; }
        if (!a.pet().lock("sale", p.getUUID())) return false;
        ShopPerformance.reserve(level, pen);
        giveEmeralds(p, ShopPricing.buyback(a));
        a.pet().rotateSerial(); // a badge token the seller still holds must not outlive the sale
        io.github.longshou.mcyukkuri.notebook.SurvivalProgress.award(p, "pet_sold");
        HumanCommerce.assign(a, target.getUUID(), "stock");
        ShopRegistry.get(level).stocked(target, a);
        var pers = a.life().personality();
        if (pers.attachment() >= Temperament.ATTACHMENT_AT_60 || pers.trust(p.getUUID()) >= BondLevels.TRUST_AT_20) { a.playAction("tremble"); a.showReaction("cry", "shop_sold_sad", 80); a.getLookControl().setLookAt(p, 30, 30); }
        else if (pers.selfishness() >= Temperament.SELFISHNESS_AT_60) { a.playAction("proud"); a.showReaction("smug", "shop_sold_proud", 80); }
        else a.playAction("bow");
        UUID id = a.getUUID(); HumanNpc clerk = target;
        ShopPerformance.schedule(level, age -> {
            if (age < 60) return false;
            // After the farewell a keeper fetches the animal, it climbs into a basket and is carried to its pen; busy keepers are waited for (up to 20 seconds), no keeper means the direct move.
            int courier = ShopCourier.dispatchSale(level, clerk, id, pen);
            if (courier > 0) return true;
            if (courier == 0 && age < 460) return false;
            ShopPerformance.release(level, pen);
            if (level.getEntity(id) instanceof YukkuriEntity y && y.isAlive()) {
                y.pet().unlock("sale");
                level.sendParticles(ParticleTypes.POOF, y.getX(), y.getY() + .3, y.getZ(), 8, .25, .15, .25, .02);
                y.getNavigation().stop(); y.moveTo(pen.getX() + .5, pen.getY() + .12, pen.getZ() + .5, y.getYRot(), 0);
                level.sendParticles(ParticleTypes.CLOUD, y.getX(), y.getY() + .3, y.getZ(), 8, .25, .15, .25, .02);
            }
            return true;
        });
        rescan(); return true;
    }
    private boolean forecast(Player p) {
        var a = subject(); if (a == null || !mayHandle(a, p) || forecasts.containsKey(a.getUUID()) || count(p, Items.EMERALD) < 1) return false;
        debit(p, Items.EMERALD, 1);
        int[] result = new int[3]; var tiers = new BadgeGrade[] {BadgeGrade.BRONZE, BadgeGrade.SILVER, BadgeGrade.GOLD};
        for (int i = 0; i < 3; i++) {
            Ability[] need = switch (tiers[i]) { case BRONZE -> new Ability[] {Ability.MANNERS};
                case SILVER -> new Ability[] {Ability.MANNERS, Ability.ACADEMICS, Ability.WITS}; default -> Ability.values(); };
            boolean high = true, low = false;
            for (var ab : need) { double c = a.pet().successChance(ab, tiers[i]); if (c < 0.7) high = false; if (c < 0.35) low = true; }
            result[i] = low ? 3 : high ? 1 : 2;
        }
        forecasts.put(a.getUUID(), result); io.github.longshou.mcyukkuri.notebook.SurvivalProgress.award(p, "shop_consult"); return true;
    }
    @Override public boolean clickMenuButton(Player p, int id) {
        if (p != viewer || p.level().isClientSide || !stillValid(p) || id < 0 || id > 28 || target == null) return false;
        if (id < 4 || id == 25) { tab = id == 25 ? ORDERS : id; page = 0; if (tab == BUYBACK || tab == CONSULT) rescan(); else evaluate(); broadcastChanges(); return true; }
        if (id == 12 || id == 13) { int n = pageCount(); page = (page + (id == 13 ? 1 : n - 1)) % n; broadcastChanges(); return true; }
        if (id == 20 || id == 21) {
            if (tab != BUYBACK && tab != CONSULT) return false;
            rescan(); if (!mine.isEmpty()) selected = (selected + (id == 21 ? 1 : mine.size() - 1)) % mine.size();
            evaluate(); broadcastChanges(); return true;
        }
        boolean ok; custom = false;
        if (id >= 22 && id <= 24) ok = tab == LIVE && about(p, id - 22);
        else if (id >= 8 && id <= 11) ok = tab == LIVE && locate(p, id - 8);
        else if (!p.level().isDay()) ok = false;
        else if (id <= 7) ok = tab == LIVE && buyLive(p, id - 4);
        else if (id <= 17) ok = tab == GOODS && buyGood(p, id - 14);
        else if (id == 18) ok = tab == BUYBACK && sell(p);
        else if (id >= 26) ok = tab == ORDERS && deliverOrder(p, id - 26);
        else ok = tab == CONSULT && forecast(p);
        if (!custom) p.displayClientMessage(Component.translatable(ok ? "human.mcyukkuri.success" : "human.mcyukkuri.unavailable"), true);
        broadcastChanges(); return ok;
    }
}
