package io.github.longshou.mcyukkuri.shop;

import io.github.longshou.mcyukkuri.random.ModRandom;

import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.entity.SpeciesProfile;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.human.HumanMenu;
import io.github.longshou.mcyukkuri.human.HumanNpc;
import io.github.longshou.mcyukkuri.human.HumanWorld;
import io.github.longshou.mcyukkuri.item.SwaddleStyle;
import io.github.longshou.mcyukkuri.pet.BadgeGrade;
import io.github.longshou.mcyukkuri.pet.PetLife;
import io.github.longshou.mcyukkuri.pet.PetSkill;
import io.github.longshou.mcyukkuri.pet.PetPrivileges;
import io.github.longshou.mcyukkuri.registry.ModItems;
import io.github.longshou.mcyukkuri.social.BondLevels;
import io.github.longshou.mcyukkuri.social.Temperament;
import java.util.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;

/** The one shop menu: any staff member of a shop opens it (docs/FEATURES.md, 宠物店). Rows and subjects are fixed server-side; button packets
 *  carry the displayed entity ID for live actions. The live roster is the stock physically inside the shop.
 *  Button ids: 0-3 tab (live, goods, buyback, consult); 4-6 buy live row (PAGE_ROWS = 3 rows per page); 8-10 reserve live row; 12/13 prev/next
 *  page; 14-17 buy goods row; 18 sell subject; 20/21 prev/next subject; 22-24 about (species notes and transport advice in chat) for live row;
 *  25 orders tab, 26-28 deliver order 1-3.
 *  Data slots (shorts): 0 tab, 1 the buyer's trades left today, 2 daytime, 3 page, 4 page count, 5 stock animals in the shop, 6 subject count,
 *  7 subject index+1; from 8, live rows of 12: species+1, stage, flags (grade | expired<<3 | display<<4 | ordered<<5 | reserved by you<<6 |
 *  reserved by another<<7), learned-skill mask, entity id low/high short, days on the shelf, mood ({@link Mood}), tags (a|b<<4, 15 none), health,
 *  nutrition, price due from this viewer;
 *  subject block (buyback/consult) from 8: species+1, stage, flags, learned-skill mask, health 16, nutrition 17, hygiene 18, stress 19,
 *  advice x3 (26-28), price/offer (29), sell state (34; 0 ok, else {@link #SELL_STATES} index), tags (35), title (36), 56 highlighted live row,
 *  57-60 goods shelf stock, 61-64 badge needed, 65 orders done (bit per order), 66-68 today's orders (pool index+1), 69 buyback rate percent,
 *  70 buyback stress penalty percent, 71 the buyer's own badge standing (0-3), 72/73 parents' badges. */
public final class ShopMenu extends AbstractContainerMenu {
    /** Fixed wire values; reserved slots and ranges remain unchanged. */
    public static final class ButtonId {
        public static final int LIVE_TAB = 0;
        public static final int BUY_LIVE_BASE = 4;
        public static final int BUY_LIVE_LAST = 7;
        public static final int RESERVE_BASE = 8;
        public static final int RESERVE_LAST = 11;
        public static final int PREVIOUS_PAGE = 12;
        public static final int NEXT_PAGE = 13;
        public static final int BUY_GOODS_BASE = 14;
        public static final int BUY_GOODS_LAST = 17;
        public static final int SELL = 18;
        public static final int PREVIOUS_SUBJECT = 20;
        public static final int NEXT_SUBJECT = 21;
        public static final int ABOUT_BASE = 22;
        public static final int ABOUT_LAST = 24;
        public static final int ORDERS_TAB = 25;
        public static final int DELIVER_ORDER_BASE = 26;
        public static final int DELIVER_ORDER_LAST = 28;
        public static final int COUNT = 29;
        private ButtonId() {}
    }
    /** Fixed wire values; reserved slots and ranges remain unchanged. */
    public static final class Data {
        public static final int COUNT = 76;
        public static final int TAB = 0;
        public static final int TRADES_LEFT = 1;
        public static final int DAYTIME = 2;
        public static final int PAGE = 3;
        public static final int PAGE_COUNT = 4;
        public static final int STOCK_COUNT = 5;
        public static final int SUBJECT_COUNT = 6;
        public static final int SUBJECT_INDEX = 7;
        public static final int SUBJECT_SPECIES = 8;
        public static final int SUBJECT_STAGE = 9;
        public static final int SUBJECT_FLAGS = 10;
        public static final int SUBJECT_SKILLS = 11;
        public static final int SUBJECT_HEALTH = 16;
        public static final int SUBJECT_NUTRITION = 17;
        public static final int SUBJECT_HYGIENE = 18;
        public static final int SUBJECT_STRESS = 19;
        public static final int ADVICE_BASE = 26;
        public static final int PRICE = 29;
        public static final int SELL_STATE = 34;
        public static final int SUBJECT_TAGS = 35;
        public static final int TITLE = 36;
        public static final int FOCUS_ROW = 56;
        public static final int GOODS_STOCK_BASE = 57;
        public static final int GOODS_UNLOCK_BASE = 61;
        public static final int ORDERS_DONE = 65;
        public static final int ORDER_BASE = 66;
        public static final int BUYBACK_RATE = 69;
        public static final int BUYBACK_PENALTY = 70;
        public static final int STANDING = 71;
        public static final int SUBJECT_MOTHER_BADGE = 72;
        public static final int SUBJECT_FATHER_BADGE = 73;
        public static final int ONI_SHOP = 74;
        public static final int MEMBERSHIP = 75;
        private Data() {}
    }
    /** Fixed wire values; reserved slots and ranges remain unchanged. */
    public static final class LiveRow {
        public static final int SPECIES = 0;
        public static final int STAGE = 1;
        public static final int FLAGS = 2;
        public static final int SKILLS = 3;
        /** The animal's network entity id as two shorts (low, high), for the row's portrait of the real individual. */
        public static final int ENTITY_LOW = 4, ENTITY_HIGH = 5;
        /** Whole days on the shelf (0 before the clock starts) and what it is doing right now ({@link Mood}). */
        public static final int DAYS = 6, MOOD = 7;
        public static final int TAGS = 8;
        public static final int HEALTH = 9;
        public static final int NUTRITION = 10;
        public static final int PRICE = 11;
        public static final int STRIDE = 12;
        public static final int BASE = 8;
        public static final int FLAGS_BASE = 10;
        public static final int PRICE_BASE = 19;
        private LiveRow() {}
    }
    public static final int PAGE_ROWS = 3, LIVE = 0, GOODS = 1, BUYBACK = 2, CONSULT = 3, ORDERS = 4, DATA_SIZE = Data.COUNT;
    public static final class Flags {
        public static final int GRADE_MASK = 7, EXPIRED = 8, DISPLAY = 16, ORDERED = 32, RESERVED_YOU = 64, RESERVED_OTHER = 128;
        private Flags() {}
    }
    /** Sell state codes (slot 34): the refusal the sell button would meet. */
    public static final List<String> SELL_STATES = List.of("ok", "closed", "unsuited", "outside", "busy", "no_nest", "gone", "oni_predator");
    /** Live row slot MOOD: 0 nothing to show, 1 pleading at the window, 2 posing proudly, 3 hiding shyly, 4 sulking, 5 let down, 6 asleep, 7 eating, 8 greeting politely. */
    public static final class Mood {
        public static final int NONE = 0, APPEAL = 1, PROUD = 2, SHY = 3, SULK = 4, LETDOWN = 5, ASLEEP = 6, EATING = 7, POLITE = 8,
                INSOLENT = 9, NUMB = 10;
        private Mood() {}
    }
    /** What the row shows of the animal's real state; never starts or probes a behaviour. */
    private static int mood(YukkuriEntity y) {
        if (y.life().isSleeping()) return Mood.ASLEEP;
        if (y.life().mealTicks() > 0) return Mood.EATING;
        var window = y.shopWindow();
        var mode = window.mode();
        if (mode != null) return switch (mode) {
            case APPEAL, SHOW_OFF -> Mood.APPEAL;
            case POLITE -> Mood.POLITE;
            case PROUD -> Mood.PROUD;
            case SHY -> Mood.SHY;
            case SULK -> Mood.SULK;
            case INSOLENT -> Mood.INSOLENT;
            case NUMB -> Mood.NUMB;
        };
        return window.letDown() ? Mood.LETDOWN : Mood.NONE;
    }
    public static final int GOODS_ROWS = 4;
    public boolean oniShop() { return shop == null ? value(Data.ONI_SHOP) != 0 : shop.kind == ShopKind.ONI; }
    private List<List<ShopCatalog.Good>> goodsPages() { return ShopCatalog.pages(oniShop() ? ShopKind.ONI : ShopKind.PET); }
    private List<ShopCatalog.Good> goodsPage(int page) { return goodsPages().get(Math.floorMod(page, goodsPages().size())); }
    public int goodsCategory(int page) { return goodsPage(page).getFirst().category(); }
    private ShopCatalog.Good good(int page, int row) {
        var goods = goodsPage(page);
        return row >= 0 && row < goods.size() ? goods.get(row) : null;
    }
    public ItemStack goodsStack(int page, int row) { var good = good(page, row); return good == null ? ItemStack.EMPTY : good.stack(); }
    public int goodsPrice(int page, int row) { var good = good(page, row); return good == null ? 0 : good.price(); }
    /** The deposit that reserves an animal at this price (shown on the reserve button). */
    public static int deposit(int price) { return ShopTrade.deposit(price); }

    private final HumanNpc target;
    private final Player viewer;
    /** The shop of the staff member asked (server side only). */
    private final ShopRegistry.Entry shop;
    private final List<UUID> roster = new ArrayList<>();
    private final List<UUID> mine = new ArrayList<>();
    private final ContainerData data = new SimpleContainerData(DATA_SIZE);
    private final UUID focusAnimal;
    private int tab, page, selected, standing, standingClock;

    public ShopMenu(int id, Inventory inventory, int entityId) { this(id, inventory, entityId, null); }
    /** Server side only passes a focus animal (opened by clicking it): the menu starts on the LIVE page that lists it with its row highlighted (slot 56). */
    private ShopMenu(int id, Inventory inventory, int entityId, UUID focusAnimal) {
        super(HumanWorld.SHOP_MENU.get(), id); viewer = inventory.player;
        this.focusAnimal = focusAnimal;
        target = viewer.level().getEntity(entityId) instanceof HumanNpc n ? n : null;
        shop = target != null && viewer.level() instanceof ServerLevel level ? ShopRegistry.get(level).of(target) : null;
        rebuildRoster();
        if (focusAnimal != null && roster.contains(focusAnimal)) page = roster.indexOf(focusAnimal) / PAGE_ROWS;
        addDataSlots(data); refresh();
    }
    public static void open(Player p, HumanNpc npc) { open(p, npc, null); }
    public static void open(Player p, HumanNpc npc, YukkuriEntity focusAnimal) {
        if (!(p instanceof ServerPlayer server) || !HumanMenu.valid(p, npc)) return;
        if (!npc.beginTrade(p)) { p.displayClientMessage(Component.translatable("human.mcyukkuri.busy"), true); return; }
        var registry = ShopRegistry.get(server.serverLevel());
        var shop = registry.of(npc);
        if (shop == null) { npc.endTrade(p); return; }
        ShopRestock.reconcileReservations(server.serverLevel(), shop);
        registry.payRefunds(server);
        UUID focus = focusAnimal == null ? null : focusAnimal.getUUID();
        server.openMenu(new SimpleMenuProvider((id, inv, owner) -> new ShopMenu(id, inv, npc.getId(), focus), npc.getDisplayName()), buf -> buf.writeVarInt(npc.getId()));
    }
    /** An empty-hand click on stock opens the menu of the nearest staff member of its shop on that animal's row (the animal itself is not handled). */
    public static boolean clickStock(Player p, YukkuriEntity y) {
        if (!(p instanceof ServerPlayer) || !(y.level() instanceof ServerLevel level) || !p.getMainHandItem().isEmpty()) return false;
        var registry = ShopRegistry.get(level);
        var shop = registry.shopAt(level, y.position());
        if (shop == null || !ShopTrade.stockOf(y, shop.id)) return false;
        var staff = level.getEntitiesOfClass(HumanNpc.class, shop.bounds().inflate(4), n -> n.isAlive() && registry.of(n) == shop)
                .stream().min(Comparator.comparingDouble(p::distanceToSqr)).orElse(null);
        if (staff == null) { ShopTrade.say(p, "protect.stock"); return true; } // no one to ask: still not handled before it is bought
        if (!HumanMenu.valid(p, staff)) { p.displayClientMessage(Component.translatable("shop.mcyukkuri.too_far"), true); return true; }
        open(p, staff, y); return true;
    }
    public HumanNpc target() { return target; }
    public int value(int index) { return data.get(index); }
    @Override public boolean stillValid(Player p) { return HumanMenu.valid(p, target) && (shop == null || !shop.closed()); }
    @Override public ItemStack quickMoveStack(Player p, int slot) { return ItemStack.EMPTY; }
    @Override public void removed(Player player) { super.removed(player); if (target != null) target.endTrade(player); }

    // helpers
    private static boolean room(Player p) { return p.getInventory().getFreeSlot() >= 0; }
    private static int stage(YukkuriEntity y) { return y.family().subadult() ? 3 : y.family().stage().ordinal(); }
    private static int[] tags(YukkuriEntity y) {
        var p = y.life().personality(); int[] out = {15, 15}; int n = 0;
        int[] found = {p.attachment() >= Temperament.ATTACHMENT_AT_65 ? 1 : 0, p.friendliness() <= 35 ? 2 : 0,
                p.selfControl() <= Temperament.SELF_CONTROL_AT_35 ? 3 : 0, p.selfControl() >= Temperament.SELF_CONTROL_AT_65 ? 4 : 0,
                p.boldness() <= Temperament.BOLDNESS_AT_35 ? 5 : 0, p.boldness() >= Temperament.BOLDNESS_AT_65 ? 6 : 0};
        for (int t : found) if (t != 0 && n < 2) out[n++] = t;
        if (n == 0) out[0] = 0;
        return out;
    }
    private int flags(YukkuriEntity y) {
        var b = y.pet(); var d = shop == null ? null : shop.deposit(y.getUUID());
        return b.grade().id() | (b.expired() ? Flags.EXPIRED : 0) | (b.grade().id() >= BadgeGrade.SILVER.id() ? Flags.DISPLAY : 0)
                | (ShopTrade.order(y) ? Flags.ORDERED : 0)
                | (d == null ? 0 : d.buyer().equals(viewer.getUUID()) ? Flags.RESERVED_YOU : Flags.RESERVED_OTHER);
    }
    private static int skills(YukkuriEntity y) {
        int mask = 0;
        for (var skill : PetSkill.values()) if (y.pet().lessons().knows(skill)) mask |= 1 << skill.ordinal();
        return mask;
    }
    private void put(int index, int value) { data.set(index, Math.clamp(value, -32768, 32767)); }
    private int pageCount() { return tab == LIVE ? Math.max(1, (roster.size() + PAGE_ROWS - 1) / PAGE_ROWS) : tab == GOODS ? goodsPages().size() : 1; }

    /** The current physical roster, best badges first. */
    private void rebuildRoster() {
        roster.clear();
        if (shop == null || !(viewer.level() instanceof ServerLevel level)) return;
        ShopRegistry.liveStock(level, shop).stream().sorted(Comparator.comparingInt((YukkuriEntity y) -> -y.pet().grade().id())
                .thenComparingInt(y -> y.getVariant().ordinal()).thenComparing(YukkuriEntity::getUUID)).limit(64).forEach(y -> roster.add(y.getUUID()));
    }
    private YukkuriEntity listed(int index) {
        if (shop == null || index < 0 || index >= roster.size() || !(viewer.level() instanceof ServerLevel level)) return null;
        return level.getEntity(roster.get(index)) instanceof YukkuriEntity y && y.isAlive() && shop.contains(y.position()) && ShopTrade.stockOf(y, shop.id) ? y : null;
    }
    private boolean mayHandle(YukkuriEntity y, Player p) {
        return y != null && y.isAlive() && y.pet().ownedBy(p.getUUID()) && !y.isPassenger() && y.distanceToSqr(p) <= 64;
    }
    private YukkuriEntity subject() {
        if (mine.isEmpty() || !(viewer.level() instanceof ServerLevel level)) return null;
        return level.getEntity(mine.get(Math.min(selected, mine.size() - 1))) instanceof YukkuriEntity y && y.isAlive() ? y : null;
    }
    private void rescan() {
        mine.clear(); if (!(viewer.level() instanceof ServerLevel level)) return;
        level.getEntitiesOfClass(YukkuriEntity.class, viewer.getBoundingBox().inflate(8), y -> mayHandle(y, viewer)).stream()
                .sorted(Comparator.comparing(YukkuriEntity::getUUID)).limit(32).forEach(y -> mine.add(y.getUUID()));
        selected = mine.isEmpty() ? 0 : Math.min(selected, mine.size() - 1);
    }
    // data
    private void refresh() {
        if (viewer.level().isClientSide || target == null || shop == null || !(target.level() instanceof ServerLevel level)) return;
        rebuildRoster();
        page = Math.min(page, pageCount() - 1);
        for (int i = 0; i < DATA_SIZE; i++) data.set(i, 0);
        put(Data.ONI_SHOP, oniShop() ? 1 : 0);
        put(Data.MEMBERSHIP, shop.membership(viewer.getUUID()).ordinal());
        put(Data.TAB, tab); put(Data.TRADES_LEFT, ShopStock.get(level).tradesLeft(level, shop.id, viewer.getUUID()));
        put(Data.DAYTIME, level.isDay() ? 1 : 0); put(Data.PAGE, page); put(Data.PAGE_COUNT, pageCount());
        if (standingClock-- <= 0) { standingClock = 20; standing = PetPrivileges.grade(viewer).id(); }
        put(Data.STANDING, standing);
        if (tab == GOODS) {
            var stocks = ShopStock.get(level);
            for (int row = 0; row < GOODS_ROWS; row++) {
                var good = good(page, row); if (good == null) continue;
                put(Data.GOODS_STOCK_BASE + row, stocks.stock(level, shop.id, good)); put(Data.GOODS_UNLOCK_BASE + row, good.unlock());
            }
        }
        if (tab == ORDERS) {
            var orders = ShopOrders.today(level, shop);
            for (int i = 0; i < ShopOrders.PER_DAY; i++) put(Data.ORDER_BASE + i, orders[i] + 1);
            put(Data.ORDERS_DONE, ShopStock.get(level).ordersDone(level, shop.id, viewer.getUUID()));
        }
        put(Data.STOCK_COUNT, roster.size()); put(Data.SUBJECT_COUNT, mine.size()); put(Data.SUBJECT_INDEX, mine.isEmpty() ? 0 : selected + 1);
        int focus = roster.indexOf(focusAnimal);
        if (tab == LIVE && focus >= 0 && focus / PAGE_ROWS == page) put(Data.FOCUS_ROW, focus % PAGE_ROWS + 1);
        if (tab == LIVE) {
            for (int i = 0; i < PAGE_ROWS; i++) {
                var y = listed(page * PAGE_ROWS + i); if (y == null) continue; int k = LiveRow.BASE + i * LiveRow.STRIDE; var t = tags(y);
                put(k, y.getVariant().ordinal() + 1); put(k + LiveRow.STAGE, stage(y)); put(k + LiveRow.FLAGS, flags(y)); put(k + LiveRow.SKILLS, skills(y));
                put(k + LiveRow.TAGS, t[0] | t[1] << 4); put(k + LiveRow.HEALTH, Math.round(y.getHealth())); put(k + LiveRow.NUTRITION, Math.round(y.life().nutrition()));
                var d = shop.deposit(y.getUUID());
                put(k + LiveRow.PRICE, d != null && d.buyer().equals(viewer.getUUID()) ? d.price() - d.paid() : ShopPricing.price(shop.kind, y));
                put(k + LiveRow.ENTITY_LOW, (short) y.getId()); put(k + LiveRow.ENTITY_HIGH, (short) (y.getId() >>> 16));
                put(k + LiveRow.DAYS, Math.max(0, CommodityLife.forSaleDays(y))); put(k + LiveRow.MOOD, mood(y));
            }
        } else if (tab == BUYBACK || tab == CONSULT) {
            var y = subject(); if (y == null) return; var b = y.pet(); var t = tags(y);
            put(Data.SUBJECT_SPECIES, y.getVariant().ordinal() + 1); put(Data.SUBJECT_STAGE, stage(y)); put(Data.SUBJECT_FLAGS, flags(y)); put(Data.SUBJECT_SKILLS, skills(y));
            var families = FamilyData.get(level);
            put(Data.SUBJECT_MOTHER_BADGE, families.badgeAcquired(y.family().mother())); put(Data.SUBJECT_FATHER_BADGE, families.badgeAcquired(y.family().father()));
            put(Data.SUBJECT_HEALTH, Math.round(y.getHealth())); put(Data.SUBJECT_NUTRITION, Math.round(y.life().nutrition())); put(Data.SUBJECT_HYGIENE, Math.round(y.life().hygiene()));
            put(Data.SUBJECT_STRESS, b.stress());
            List<Integer> advice = new ArrayList<>();
            if (b.title() == PetLife.TITLE_ETERNAL_SILVER) advice.add(12);
            if (b.title() == PetLife.TITLE_HIDDEN_GOLD) advice.add(13);
            if (b.stress() > BondLevels.UNEASE_CARE_LIMIT) advice.add(1);
            if (b.expired()) advice.add(9);
            if (b.unauthorizedBirths() > 0) advice.add(10);
            for (int i = 0; i < Math.min(3, advice.size()); i++) put(Data.ADVICE_BASE + i, advice.get(i));
            put(Data.PRICE, tab == BUYBACK ? ShopPricing.buyback(shop.kind, y) : ShopPricing.price(shop.kind, y));
            put(Data.BUYBACK_RATE, ShopPricing.rate(y)); put(Data.BUYBACK_PENALTY, ShopPricing.penalty(y));
            if (tab == BUYBACK) { String why = ShopTrade.sellRefusal(level, shop, y, viewer); put(Data.SELL_STATE, why == null ? 0 : SELL_STATES.indexOf(why)); }
            put(Data.SUBJECT_TAGS, t[0] | t[1] << 4); put(Data.TITLE, b.title());
        }
    }
    @Override public void broadcastChanges() { refresh(); super.broadcastChanges(); }

    // actions
    private boolean buyGood(ServerLevel level, Player p, int row) {
        var good = good(page, row);
        if (good == null) return false;
        int price = good.price(); var stack = good.stack(); var stocks = ShopStock.get(level);
        String why = !level.isDay() ? "closed" : !PetPrivileges.allows(p, good.unlock()) ? "locked." + good.unlock()
                : stocks.stock(level, shop.id, good) <= 0 ? "sold_out" : stocks.tradesLeft(level, shop.id, p.getUUID()) <= 0 ? "trade_limit"
                : ShopTrade.emeralds(p) < price ? "emeralds" : !room(p) ? "inventory_full" : null;
        if (why != null) {
            ShopTrade.say(p, "goods.refused." + why, stack.getHoverName(), price, ShopTrade.emeralds(p));
            GameplayLog.event(p, "shop", "action=buy_good result=refused shop={} item={} reason={}", shop.id, stack.getItem(), why);
            return false;
        }
        int stockBefore = stocks.stock(level, shop.id, good), delivered = stack.getCount();
        var item = stack.getItem();
        // The shelf shows the plain knit; each swaddle cloth handed over is one of the styles at random (docs/FEATURES.md).
        var style = stack.is(ModItems.SWADDLE_CLOTH.get()) ? SwaddleStyle.random(ModRandom.world(level)) : null;
        if (style != null) style.apply(stack);
        stocks.take(level, shop.id, good);
        ShopTrade.debit(p, price); if (!p.getInventory().add(stack)) p.drop(stack, false); stocks.useTrade(level, shop.id, p.getUUID());
        ShopRegistry.get(level).recordSpend(level, shop, p.getUUID(), price);
        ShopTrade.say(p, "goods.bought", stack.getHoverName(), price);
        GameplayLog.event(p, "shop", "action=buy_good result=delivered shop={} item={} count={} style={} emeralds_used={} stock={}->{}",
                shop.id, item, delivered, style == null ? null : style.id, price, stockBefore, stocks.stock(level, shop.id, good));
        return true;
    }
    /** R2: the clerk explains the species and gives transport advice, as three chat lines. */
    private boolean about(Player p, YukkuriEntity animal) {
        var v = animal.getVariant(); String id = v.assetId(); String note = SpeciesProfile.of(v).dangerToYukkuri() ? "predator" : id;
        var name = target.getDisplayName();
        p.sendSystemMessage(Component.translatable("shop.mcyukkuri.about.say", name, Component.translatable("shop.mcyukkuri.about.head", Component.translatable("entity.mcyukkuri." + id))));
        p.sendSystemMessage(Component.translatable("shop.mcyukkuri.about.say", name, Component.translatable("shop.mcyukkuri.about.note." + note)));
        p.sendSystemMessage(Component.translatable("shop.mcyukkuri.about.say", name, Component.translatable("shop.mcyukkuri.about.transport")));
        return true;
    }
    public static int liveRow(int button) {
        if (button >= ButtonId.BUY_LIVE_BASE && button <= ButtonId.BUY_LIVE_LAST) return button - ButtonId.BUY_LIVE_BASE;
        if (button >= ButtonId.RESERVE_BASE && button <= ButtonId.RESERVE_LAST) return button - ButtonId.RESERVE_BASE;
        if (button >= ButtonId.ABOUT_BASE && button <= ButtonId.ABOUT_LAST) return button - ButtonId.ABOUT_BASE;
        return -1;
    }

    public int liveEntityId(int row) {
        int k = LiveRow.BASE + row * LiveRow.STRIDE;
        return value(k + LiveRow.ENTITY_HIGH) << 16 | value(k + LiveRow.ENTITY_LOW) & 0xffff;
    }

    public void clickLive(ServerPlayer p, int button, int entityId) {
        if (p != viewer || !stillValid(p) || shop == null || tab != LIVE || liveRow(button) < 0) return;
        var level = p.serverLevel();
        if (!(level.getEntity(entityId) instanceof YukkuriEntity y) || !y.isAlive() || !roster.contains(y.getUUID())
                || !shop.contains(y.position()) || !ShopTrade.stockOf(y, shop.id)) {
            ShopTrade.say(p, "buy.refused.gone");
        } else if (button <= ButtonId.BUY_LIVE_LAST) {
            ShopRestock.reconcileReservations(level, shop);
            ShopTrade.buy(level, shop, y, p);
        } else if (button <= ButtonId.RESERVE_LAST) {
            ShopRestock.reconcileReservations(level, shop);
            ShopTrade.reserve(level, shop, y, p);
        } else about(p, y);
        broadcastChanges();
    }
    @Override public boolean clickMenuButton(Player p, int id) {
        if (p != viewer || p.level().isClientSide || !stillValid(p) || id < ButtonId.LIVE_TAB || id > ButtonId.DELIVER_ORDER_LAST || target == null
                || shop == null || !(p.level() instanceof ServerLevel level) || !(p instanceof ServerPlayer player)) return false;
        if (id < ButtonId.BUY_LIVE_BASE || id == ButtonId.ORDERS_TAB) {
            tab = id == ButtonId.ORDERS_TAB ? ORDERS : id; page = 0;
            if (tab == BUYBACK || tab == CONSULT) rescan(); else if (tab == LIVE) rebuildRoster();
            broadcastChanges(); return true;
        }
        if (id == ButtonId.PREVIOUS_PAGE || id == ButtonId.NEXT_PAGE) {
            if (tab == LIVE) rebuildRoster();
            int n = pageCount(); page = (page + (id == ButtonId.NEXT_PAGE ? 1 : n - 1)) % n; broadcastChanges(); return true;
        }
        if (id == ButtonId.PREVIOUS_SUBJECT || id == ButtonId.NEXT_SUBJECT) {
            if (tab != BUYBACK && tab != CONSULT) return false;
            rescan(); if (!mine.isEmpty()) selected = (selected + (id == ButtonId.NEXT_SUBJECT ? 1 : mine.size() - 1)) % mine.size();
            broadcastChanges(); return true;
        }
        boolean ok;
        if (liveRow(id) >= 0) return false;
        if (id <= ButtonId.BUY_GOODS_LAST) ok = tab == GOODS && buyGood(level, p, id - ButtonId.BUY_GOODS_BASE);
        else if (id == ButtonId.SELL) { ok = tab == BUYBACK && ShopTrade.sell(level, shop, subject(), player); if (ok) rescan(); }
        else if (id >= ButtonId.DELIVER_ORDER_BASE) {
            ok = tab == ORDERS && level.isDay() && ShopOrders.deliver(level, shop, p, id - ButtonId.DELIVER_ORDER_BASE);
            ShopTrade.say(p, ok ? "order.delivered" : level.isDay() ? "order.unavailable" : "goods.refused.closed");
        }
        else ok = false;
        broadcastChanges(); return ok;
    }
}
