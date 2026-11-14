package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.systems.RenderSystem;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import io.github.longshou.mcyukkuri.shop.ShopMenu;
import io.github.longshou.mcyukkuri.shop.ShopMembership;
import io.github.longshou.mcyukkuri.shop.ShopOrders;
import io.github.longshou.mcyukkuri.network.ShopRowPayload;
import io.github.longshou.mcyukkuri.pet.PetSkill;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import org.lwjgl.opengl.GL11;

import static io.github.longshou.mcyukkuri.client.GuiKit.*;

/** Tabbed shopkeeper screen: live stock, goods, buyback, consultation. Button and data slot ids are documented on ShopMenu (slot 56: highlighted live row, 1-4, 0 none). */
public final class ShopScreen extends AbstractContainerScreen<ShopMenu> {
    private static final int ROW = 57, ROW_X = 8, ROW_W = 256, ROW_H = 56, TOP = 32, FOOT = 214, LOW_HEALTH = 6, LOW_NUTRITION = 30;
    /** Live rows: the real individual's portrait on the left, four text lines beside it. */
    private static final int PORTRAIT = 48, TEXT_X = ROW_X + PORTRAIT + 10, TEXT_W = ROW_W - PORTRAIT - 16, LINE = 12;
    private static final float PORTRAIT_YAW = 18, PORTRAIT_PITCH = 12;
    private final Button[] actions = new Button[ShopMenu.ButtonId.COUNT];
    public ShopScreen(ShopMenu menu, Inventory inv, Component title) { super(menu, inv, title); imageWidth = 340; imageHeight = 252; }
    @Override protected void init() {
        super.init();
        int[] icons = {ICON_LIVE, ICON_GOODS, ICON_BUYBACK, ICON_CONSULT, ICON_PROFILE};
        for (int i = 0; i < 5; i++) {
            int tabId = i == 4 ? ShopMenu.ButtonId.ORDERS_TAB : i;
            var tab = addRenderableWidget(new GuiKit.Tab(leftPos + 8 + i * 65, topPos + 6, 64, 22, Component.translatable("shop.mcyukkuri.tab." + i), icons[i], b -> press(tabId)));
            if (i < 4) actions[i] = tab; else actions[ShopMenu.ButtonId.ORDERS_TAB] = tab;
        }
        for (int i = 0; i < ShopMenu.GOODS_ROWS; i++) add(ShopMenu.ButtonId.BUY_GOODS_BASE + i, 36, 52 + i * 34, 290, Component.empty());
        for (int i = 0; i < ShopOrders.PER_DAY; i++) add(ShopMenu.ButtonId.DELIVER_ORDER_BASE + i, 270, 49 + i * 34, 62, 22, Component.translatable("shop.mcyukkuri.order.deliver"));
        for (int i = 0; i < ShopMenu.PAGE_ROWS; i++) {
            add(ShopMenu.ButtonId.BUY_LIVE_BASE + i, 270, TOP + i * ROW + 3, 62, 16, Component.translatable("shop.mcyukkuri.buy"));
            add(ShopMenu.ButtonId.RESERVE_BASE + i, 270, TOP + i * ROW + 20, 62, 16, Component.translatable("shop.mcyukkuri.reserve"));
            add(ShopMenu.ButtonId.ABOUT_BASE + i, 270, TOP + i * ROW + 37, 62, 16, Component.translatable("shop.mcyukkuri.about"));
        }
        add(ShopMenu.ButtonId.PREVIOUS_PAGE, 8, FOOT, 40, Component.literal("<")); add(ShopMenu.ButtonId.NEXT_PAGE, 52, FOOT, 40, Component.literal(">"));
        add(ShopMenu.ButtonId.PREVIOUS_SUBJECT, 8, FOOT, 40, Component.literal("<")); add(ShopMenu.ButtonId.NEXT_SUBJECT, 52, FOOT, 40, Component.literal(">"));
        add(ShopMenu.ButtonId.SELL, 100, FOOT, 160, Component.translatable("shop.mcyukkuri.sell"));
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).bounds(leftPos + 272, topPos + FOOT, 60, 20).build(ActionButton::new));
    }
    private void press(int id) {
        int row = ShopMenu.liveRow(id);
        if (row >= 0) {
            int entity = menu.liveEntityId(row);
            if (entity != 0) net.neoforged.neoforge.network.PacketDistributor.sendToServer(new ShopRowPayload(menu.containerId, id, entity));
        } else if (minecraft.gameMode != null) minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id);
    }
    private void add(int id, int x, int y, int w, Component title) { add(id, x, y, w, 20, title); }
    private void add(int id, int x, int y, int w, int h, Component title) {
        actions[id] = addRenderableWidget(Button.builder(title, b -> press(id)).bounds(leftPos + x, topPos + y, w, h).build(ActionButton::new));
    }
    @Override protected void containerTick() {
        super.containerTick();
        int tab = menu.value(ShopMenu.Data.TAB); boolean day = menu.value(ShopMenu.Data.DAYTIME) != 0, own = menu.value(ShopMenu.Data.SUBJECT_COUNT) > 0;
        actions[ShopMenu.ButtonId.ORDERS_TAB].active = tab != ShopMenu.ORDERS;
        for (int i = 0; i < ShopOrders.PER_DAY; i++) {
            actions[ShopMenu.ButtonId.DELIVER_ORDER_BASE + i].visible = tab == ShopMenu.ORDERS;
            actions[ShopMenu.ButtonId.DELIVER_ORDER_BASE + i].active = day && (menu.value(ShopMenu.Data.ORDERS_DONE) >> i & 1) == 0 && menu.value(ShopMenu.Data.ORDER_BASE + i) > 0;
        }
        for (int i = 0; i < ShopMenu.GOODS_ROWS; i++) {
            actions[i].active = tab != i;
            if (i < ShopMenu.PAGE_ROWS) {
                int f = menu.value(ShopMenu.LiveRow.FLAGS_BASE + i * ShopMenu.LiveRow.STRIDE), price = menu.value(ShopMenu.LiveRow.PRICE_BASE + i * ShopMenu.LiveRow.STRIDE);
                boolean has = menu.value(ShopMenu.LiveRow.BASE + i * ShopMenu.LiveRow.STRIDE) > 0, ordered = (f & ShopMenu.Flags.ORDERED) != 0,
                        mineReserved = (f & ShopMenu.Flags.RESERVED_YOU) != 0, otherReserved = (f & ShopMenu.Flags.RESERVED_OTHER) != 0;
                actions[ShopMenu.ButtonId.BUY_LIVE_BASE + i].visible = actions[ShopMenu.ButtonId.RESERVE_BASE + i].visible = actions[ShopMenu.ButtonId.ABOUT_BASE + i].visible = tab == ShopMenu.LIVE;
                // Clicks stay possible whenever the row exists: the server answers with the exact reason (closed, reserved, emeralds).
                actions[ShopMenu.ButtonId.BUY_LIVE_BASE + i].active = has && !ordered; actions[ShopMenu.ButtonId.ABOUT_BASE + i].active = has;
                actions[ShopMenu.ButtonId.RESERVE_BASE + i].active = has && !ordered && !mineReserved && !otherReserved;
                if (has) {
                    actions[ShopMenu.ButtonId.BUY_LIVE_BASE + i].setMessage(Component.translatable(mineReserved ? "shop.mcyukkuri.buy_rest" : "shop.mcyukkuri.buy_price", price));
                    actions[ShopMenu.ButtonId.RESERVE_BASE + i].setMessage(Component.translatable(mineReserved || otherReserved ? "shop.mcyukkuri.reserved"
                            : "shop.mcyukkuri.reserve_price", ShopMenu.deposit(price)));
                }
            }
            var stack = menu.goodsStack(menu.value(ShopMenu.Data.PAGE), i); int price = menu.goodsPrice(menu.value(ShopMenu.Data.PAGE), i);
            actions[ShopMenu.ButtonId.BUY_GOODS_BASE + i].visible = tab == ShopMenu.GOODS && !stack.isEmpty();
            int left = menu.value(ShopMenu.Data.GOODS_STOCK_BASE + i), need = menu.value(ShopMenu.Data.GOODS_UNLOCK_BASE + i), standing = menu.value(ShopMenu.Data.STANDING);
            boolean soldOut = left <= 0, locked = standing < need;
            actions[ShopMenu.ButtonId.BUY_GOODS_BASE + i].active = day && menu.value(ShopMenu.Data.TRADES_LEFT) > 0 && !soldOut && !locked;
            if (!stack.isEmpty()) actions[ShopMenu.ButtonId.BUY_GOODS_BASE + i].setMessage(soldOut ? Component.translatable("shop.mcyukkuri.sold_out_label", stack.getHoverName())
                    : locked ? Component.translatable("shop.mcyukkuri.locked_label." + need, stack.getHoverName())
                    : Component.translatable("shop.mcyukkuri.buy_stock", stack.getHoverName(), stack.getCount(), price, left));
        }
        actions[ShopMenu.ButtonId.PREVIOUS_PAGE].visible = actions[ShopMenu.ButtonId.NEXT_PAGE].visible = tab <= ShopMenu.GOODS;
                actions[ShopMenu.ButtonId.PREVIOUS_PAGE].active = actions[ShopMenu.ButtonId.NEXT_PAGE].active = menu.value(ShopMenu.Data.PAGE_COUNT) > 1;
        actions[ShopMenu.ButtonId.PREVIOUS_SUBJECT].visible = actions[ShopMenu.ButtonId.NEXT_SUBJECT].visible = tab == ShopMenu.BUYBACK ||
                tab == ShopMenu.CONSULT; actions[ShopMenu.ButtonId.PREVIOUS_SUBJECT].active = actions[ShopMenu.ButtonId.NEXT_SUBJECT].active = menu.value(ShopMenu.Data.SUBJECT_COUNT) > 1;
        actions[ShopMenu.ButtonId.SELL].visible = tab == ShopMenu.BUYBACK; actions[ShopMenu.ButtonId.SELL].active = own;
    }
    @Override protected void renderBg(GuiGraphics g, float partial, int x, int y) {
        panel(g, leftPos, topPos, imageWidth, imageHeight);
        int tab = menu.value(ShopMenu.Data.TAB);
        if (tab == ShopMenu.LIVE) {
            boolean drewModel = false;
            for (int i = 0; i < ShopMenu.PAGE_ROWS; i++) {
                int k = ShopMenu.LiveRow.BASE + i * ShopMenu.LiveRow.STRIDE, rowY = topPos + TOP + i * ROW;
                card(g, leftPos + ROW_X, rowY, ROW_W, ROW_H, menu.value(ShopMenu.Data.FOCUS_ROW) == i + 1 && menu.value(k) > 0);
                if (menu.value(k) == 0) continue;
                GuiKit.statusCard(g, leftPos + ROW_X + 4, rowY + 4, PORTRAIT, PORTRAIT);
                // The animal itself, live: its current clip and face (pleading at the glass, sulking, asleep) show in the row.
                if (portrait(k) instanceof YukkuriEntity animal && animal.isAlive() && !animal.isRemoved()) {
                    CareInspection.drawModel(g, animal, leftPos + ROW_X + 4, rowY + 4, PORTRAIT, PORTRAIT, PORTRAIT_YAW, PORTRAIT_PITCH, 1);
                    drewModel = true;
                }
            }
            // Keep the row text and buttons in front of a model that reaches beyond its box.
            if (drewModel) RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
        } else if (tab == ShopMenu.GOODS) {
            for (int i = 0; i < ShopMenu.GOODS_ROWS; i++) {
                var stack = menu.goodsStack(menu.value(ShopMenu.Data.PAGE), i); if (stack.isEmpty()) continue;
                card(g, leftPos + 8, topPos + 46 + i * 34, 324, 30, false);
                g.renderItem(stack, leftPos + 14, topPos + 53 + i * 34);
            }
        } else if (tab == ShopMenu.ORDERS) {
            for (int i = 0; i < ShopOrders.PER_DAY; i++) {
                card(g, leftPos + 8, topPos + 46 + i * 34, 324, 30, false);
                if (menu.value(ShopMenu.Data.ORDER_BASE + i) > 0) g.renderItem(ShopOrders.icon(menu.value(ShopMenu.Data.ORDER_BASE + i) - 1), leftPos + 14, topPos + 53 + i * 34);
            }
        } else card(g, leftPos + 8, topPos + 32, 324, menu.value(ShopMenu.Data.SUBJECT_COUNT) == 0 ? 28 : tab == ShopMenu.BUYBACK ? 120 : 178, false);
    }
    private void line(GuiGraphics g, int y, int color, Component text) { g.drawString(font, text, 16, y, color, false); }
    /** Short species name (no Yukkuri suffix) when the lang file has one, else the entity name. */
    private static Component species(int v) {
        if (v <= 0 || v > YukkuriVariant.values().length) return Component.empty();
        String id = YukkuriVariant.values()[v - 1].assetId();
        return Component.translatable(I18n.exists("shop.mcyukkuri.sp." + id) ? "shop.mcyukkuri.sp." + id : "entity.mcyukkuri." + id);
    }
    private static Component grade(int flags) {
        var c = GuiKit.gradeName(flags & ShopMenu.Flags.GRADE_MASK);
        return (flags & ShopMenu.Flags.EXPIRED) != 0 ? c.append(Component.translatable("shop.mcyukkuri.expired")) : c;
    }
    private static MutableComponent tags(int packed) {
        var c = Component.translatable("shop.mcyukkuri.tag." + (packed & 15)); int second = packed >> 4 & 15;
        return second == 15 ? c : c.append(" / ").append(Component.translatable("shop.mcyukkuri.tag." + second));
    }
    private static Component skills(int mask) {
        MutableComponent result = Component.empty(); boolean any = false;
        for (var skill : PetSkill.values()) if ((mask & 1 << skill.ordinal()) != 0) {
            if (any) result.append(", ");
            result.append(Component.translatable("pet.mcyukkuri.skill." + skill.key())); any = true;
        }
        return any ? result : Component.translatable("pet.mcyukkuri.profile.skills_none");
    }
    private static Component parentBadge(int grade) {
        return grade < 0 ? Component.translatable("pet.mcyukkuri.profile.unknown") : GuiKit.gradeName(grade);
    }
    /** The row's animal on this client (its network id travels as two shorts), or null when it is not loaded here. */
    private Entity portrait(int k) {
        int id = menu.liveEntityId((k - ShopMenu.LiveRow.BASE) / ShopMenu.LiveRow.STRIDE);
        return minecraft.level == null ? null : minecraft.level.getEntity(id);
    }
    private void drawRow(GuiGraphics g, int k, int y) {
        int f = menu.value(k + ShopMenu.LiveRow.FLAGS); boolean expired = (f & ShopMenu.Flags.EXPIRED) != 0;
        if (expired) expiredChip(g, TEXT_X, y + 4); else chip(g, TEXT_X, y + 4, f & ShopMenu.Flags.GRADE_MASK);
        g.drawString(font, ellipsize(font, Component.translatable("shop.mcyukkuri.row.head2", expired ? GuiKit.expiredGradeName(f & ShopMenu.Flags.GRADE_MASK) :
                GuiKit.gradeName(f & ShopMenu.Flags.GRADE_MASK), species(menu.value(k)), Component.translatable("human.mcyukkuri.stage." +
                menu.value(k + ShopMenu.LiveRow.STAGE))), TEXT_W - CHIP_WIDTH), TEXT_X + CHIP_WIDTH, y + 5, INK, false);
        Component knownSkills = Component.translatable("pet.mcyukkuri.profile.skills", skills(menu.value(k + ShopMenu.LiveRow.SKILLS)));
        g.drawString(font, ellipsize(font, knownSkills, TEXT_W), TEXT_X, y + 5 + LINE, SOFT, false);
        var tag = tags(menu.value(k + ShopMenu.LiveRow.TAGS)); int tx = TEXT_X;
        g.drawString(font, tag, tx, y + 5 + 2 * LINE, SOFT, false); tx += font.width(tag) + 10;
        if (menu.value(k + ShopMenu.LiveRow.HEALTH) < LOW_HEALTH) { var w = Component.translatable("shop.mcyukkuri.warn.hurt"); g.drawString(font, w,
                tx, y + 5 + 2 * LINE, WARN, false); tx += font.width(w) + 8; }
        if (menu.value(k + ShopMenu.LiveRow.NUTRITION) < LOW_NUTRITION) g.drawString(font, Component.translatable("shop.mcyukkuri.warn.hungry"), tx, y + 5 + 2 * LINE, WARN, false);
        // Fourth line: days on the shelf, whether it is being handed over or reserved, and what it is doing right now.
        boolean sold = (f & ShopMenu.Flags.ORDERED) != 0, mine = (f & ShopMenu.Flags.RESERVED_YOU) != 0, other = (f & ShopMenu.Flags.RESERVED_OTHER) != 0;
        MutableComponent zone = Component.translatable("shop.mcyukkuri.days", menu.value(k + ShopMenu.LiveRow.DAYS));
        String state = sold ? "zone.sold" : mine ? "zone.reserved_you" : other ? "zone.reserved_other" : (f & ShopMenu.Flags.DISPLAY) != 0 ? "zone.display" : null;
        if (state != null) zone.append(" · ").append(Component.translatable("shop.mcyukkuri." + state));
        sold |= other;
        int mood = menu.value(k + ShopMenu.LiveRow.MOOD);
        int zx = TEXT_X; var shown = ellipsize(font, zone, TEXT_W);
        g.drawString(font, shown, zx, y + 5 + 3 * LINE, sold ? WARN : SOFT, false); zx += font.width(shown) + 8;
        if (!sold && mood != ShopMenu.Mood.NONE) {
            var doing = ellipsize(font, Component.translatable("shop.mcyukkuri.mood." + mood), TEXT_X + TEXT_W - zx);
            g.drawString(font, doing, zx, y + 5 + 3 * LINE, mood == ShopMenu.Mood.SULK || mood == ShopMenu.Mood.LETDOWN ? WARN : GOOD, false);
        }
    }
    @Override protected void renderLabels(GuiGraphics g, int mx, int my) {
        var membership = ShopMembership.byId(menu.value(ShopMenu.Data.MEMBERSHIP));
        var membershipLabel = membership.label();
        g.drawString(font, membershipLabel, imageWidth - 8 - font.width(membershipLabel), FOOT + 24, SOFT, false);
        int tab = menu.value(ShopMenu.Data.TAB);
        if (tab == ShopMenu.LIVE) {
            for (int i = 0; i < ShopMenu.PAGE_ROWS; i++) {
                int k = ShopMenu.LiveRow.BASE + i * ShopMenu.LiveRow.STRIDE, y = TOP + i * ROW;
                if (menu.value(k) == 0) { if (i == 0) g.drawString(font, Component.translatable("human.mcyukkuri.empty"), ROW_X + 6, y + 6, SOFT, false); continue; }
                drawRow(g, k, y);
            }
            g.drawString(font, Component.translatable("shop.mcyukkuri.page", menu.value(ShopMenu.Data.PAGE) + 1, menu.value(ShopMenu.Data.PAGE_COUNT)), 100, FOOT + 6, SOFT, false);
        } else if (tab == ShopMenu.GOODS) {
            line(g, 36, INK, Component.translatable("shop.mcyukkuri.category." + menu.goodsCategory(menu.value(ShopMenu.Data.PAGE))));
            line(g, 184, SOFT, Component.translatable("shop.mcyukkuri.trades_left", menu.value(ShopMenu.Data.TRADES_LEFT)));
            line(g, 196, SOFT, Component.translatable("shop.mcyukkuri.restock_hint"));
            g.drawString(font, Component.translatable("shop.mcyukkuri.page", menu.value(ShopMenu.Data.PAGE) + 1, menu.value(ShopMenu.Data.PAGE_COUNT)), 100, FOOT + 6, SOFT, false);
        } else if (tab == ShopMenu.ORDERS) {
            line(g, 36, INK, Component.translatable("shop.mcyukkuri.order.title"));
            for (int i = 0; i < ShopOrders.PER_DAY; i++) {
                int pool = menu.value(ShopMenu.Data.ORDER_BASE + i) - 1; if (pool < 0) continue;
                var order = ShopOrders.order(pool); boolean done = (menu.value(ShopMenu.Data.ORDERS_DONE) >> i & 1) != 0;
                var item = Component.translatable(order.item().get().getDescriptionId());
                g.drawString(font, Component.translatable("shop.mcyukkuri.order.line", item, order.count(), order.reward()), 36, 56 + i * 34, done ? SOFT : INK, false);
                if (done) g.drawString(font, Component.translatable("shop.mcyukkuri.order.done"), 36, 66 + i * 34, GOOD, false);
            }
            line(g, 156, SOFT, Component.translatable("shop.mcyukkuri.order.hint"));
        } else {
            if (menu.value(ShopMenu.Data.SUBJECT_COUNT) == 0) { line(g, 42, SOFT, Component.translatable("shop.mcyukkuri.no_subject")); return; }
            boolean buy = tab == ShopMenu.BUYBACK; int f = menu.value(ShopMenu.Data.SUBJECT_FLAGS);
            line(g, 38, INK, Component.translatable("shop.mcyukkuri.subject", menu.value(ShopMenu.Data.SUBJECT_INDEX),
                    menu.value(ShopMenu.Data.SUBJECT_COUNT), species(menu.value(ShopMenu.Data.SUBJECT_SPECIES)), grade(f),
                    Component.translatable("human.mcyukkuri.stage." + menu.value(ShopMenu.Data.SUBJECT_STAGE))));
            Component knownSkills = Component.translatable("pet.mcyukkuri.profile.skills", skills(menu.value(ShopMenu.Data.SUBJECT_SKILLS)));
            line(g, 52, INK, ellipsize(font, knownSkills, 308));
            chip(g, 16, 65, f & ShopMenu.Flags.GRADE_MASK);
            g.drawString(font, tags(menu.value(ShopMenu.Data.SUBJECT_TAGS)), 42, 66, SOFT, false);
            line(g, 90, SOFT, Component.translatable("shop.mcyukkuri.vitals3", level(Math.min(100, menu.value(ShopMenu.Data.SUBJECT_HEALTH) * 10)),
                    level(menu.value(ShopMenu.Data.SUBJECT_NUTRITION)), level(menu.value(ShopMenu.Data.SUBJECT_HYGIENE))));
            if (buy) {
                line(g, 104, INK, Component.translatable(menu.oniShop() ? "shop.mcyukkuri.offer_nuggets" : "shop.mcyukkuri.offer",
                        menu.value(ShopMenu.Data.PRICE)));
                line(g, 116, menu.value(ShopMenu.Data.BUYBACK_PENALTY) > 0 && !menu.oniShop() ? WARN : SOFT, menu.oniShop()
                        ? Component.translatable("shop.mcyukkuri.offer_oni")
                        : Component.translatable("shop.mcyukkuri.offer_detail", menu.value(ShopMenu.Data.BUYBACK_RATE), menu.value(ShopMenu.Data.BUYBACK_PENALTY)));
                int sellState = menu.value(ShopMenu.Data.SELL_STATE);
                line(g, 128, sellState == 0 ? SOFT : WARN, Component.translatable("shop.mcyukkuri.sell_state." + ShopMenu.SELL_STATES.get(Math.clamp(sellState, 0, ShopMenu.SELL_STATES.size() - 1))));
                line(g, 140, SOFT, Component.translatable("shop.mcyukkuri.sell_hint"));
                return;
            }
            line(g, 104, SOFT, Component.translatable("shop.mcyukkuri.consult.stress", level(menu.value(ShopMenu.Data.SUBJECT_STRESS))));
            line(g, 116, SOFT, Component.translatable("shop.mcyukkuri.consult.exam_tasks"));
            line(g, 128, SOFT, Component.translatable("pet.mcyukkuri.profile.parent_badges", parentBadge(menu.value(ShopMenu.Data.SUBJECT_MOTHER_BADGE)),
                    parentBadge(menu.value(ShopMenu.Data.SUBJECT_FATHER_BADGE))));
            line(g, 154, INK, Component.translatable("shop.mcyukkuri.advice_title"));
            boolean any = false;
            for (int i = 0; i < 3; i++) if (menu.value(ShopMenu.Data.ADVICE_BASE + i) > 0) { any = true; line(g, 166 + i * 12, INK,
                    Component.literal("- ").append(Component.translatable("shop.mcyukkuri.advice." + menu.value(ShopMenu.Data.ADVICE_BASE + i)))); }
            if (!any) line(g, 166, SOFT, Component.translatable("shop.mcyukkuri.advice.0"));
            if (menu.value(ShopMenu.Data.TITLE) > 0) g.drawString(font, Component.translatable("shop.mcyukkuri.title",
                    Component.translatable("title.mcyukkuri.pet." + menu.value(ShopMenu.Data.TITLE))), 130, 154, menu.value(ShopMenu.Data.TITLE) == 2 ? GOOD : INK, false);
        }
    }
    @Override public void render(GuiGraphics g, int x, int y, float partial) {
        super.render(g, x, y, partial); renderTooltip(g, x, y);
        int tab = menu.value(ShopMenu.Data.TAB);
        if (tab == ShopMenu.LIVE) for (int i = 0; i < ShopMenu.PAGE_ROWS; i++) {
            int k = ShopMenu.LiveRow.BASE + i * ShopMenu.LiveRow.STRIDE, rowY = TOP + i * ROW;
            Component knownSkills = Component.translatable("pet.mcyukkuri.profile.skills", skills(menu.value(k + ShopMenu.LiveRow.SKILLS)));
            if (menu.value(k) != 0 && font.width(knownSkills) > TEXT_W && over(x, y, leftPos + TEXT_X, topPos + rowY + 5 + LINE, TEXT_W, font.lineHeight))
                g.renderTooltip(font, knownSkills, x, y);
        } else if (tab == ShopMenu.BUYBACK || tab == ShopMenu.CONSULT) {
            Component knownSkills = Component.translatable("pet.mcyukkuri.profile.skills", skills(menu.value(ShopMenu.Data.SUBJECT_SKILLS)));
            if (font.width(knownSkills) > 308 && over(x, y, leftPos + 16, topPos + 52, 308, font.lineHeight)) g.renderTooltip(font, knownSkills, x, y);
        }
    }
}
