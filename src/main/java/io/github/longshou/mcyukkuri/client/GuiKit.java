package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.systems.RenderSystem;
import io.github.longshou.mcyukkuri.MCYukkuri;
import java.util.ArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;

/** Shared menu and HUD kit drawn from art/props/ui/author_shop_ui.py. */
public final class GuiKit {
    public static final ResourceLocation TEX = MCYukkuri.id("textures/gui/shop_ui.png");
    private static final int ATLAS = 256;
    public static final int INK = 0xff34453e, SOFT = 0xff6a7a70, WARN = 0xff9a3b32, GOOD = 0xff2f6b3f;
    /**
     * Text colours for grades 0-3 (none, bronze, silver, gold): bronze shade[0], silver engrave, gold shade[0] darkened for contrast
     * (#ba781e -> #9a6214) from PALETTES in art/props/badges/author_badges.py.
     */
    private static final int[] GRADE_TEXT = {SOFT, 0xff8f5428, 0xff5a6676, 0xff9a6214};
    public static final int ICON_LIVE = 0, ICON_GOODS = 1, ICON_BUYBACK = 2, ICON_CONSULT = 3, ICON_EXAM = 4, ICON_PROFILE = 5, ICON_KEEPER = 6;

    private GuiKit() {}

    private static void blit(GuiGraphics g, int x, int y, int u, int v, int w, int h) { g.blit(TEX, x, y, u, v, w, h, ATLAS, ATLAS); }

    /** Fills a rectangle by repeating a source tile, cutting the last repeat. */
    private static void tile(GuiGraphics g, int x, int y, int w, int h, int u, int v, int tw, int th) {
        for (int j = 0; j < h; j += th) for (int i = 0; i < w; i += tw) blit(g, x + i, y + j, u, v, Math.min(tw, w - i), Math.min(th, h - j));
    }

    private static void nine(GuiGraphics g, int x, int y, int w, int h, int u, int v, int size, int c) {
        int m = size - 2 * c;
        blit(g, x, y, u, v, c, c); blit(g, x + w - c, y, u + size - c, v, c, c);
        blit(g, x, y + h - c, u, v + size - c, c, c); blit(g, x + w - c, y + h - c, u + size - c, v + size - c, c, c);
        tile(g, x + c, y, w - 2 * c, c, u + c, v, m, c); tile(g, x + c, y + h - c, w - 2 * c, c, u + c, v + size - c, m, c);
        tile(g, x, y + c, c, h - 2 * c, u, v + c, c, m); tile(g, x + w - c, y + c, c, h - 2 * c, u + size - c, v + c, c, m);
        tile(g, x + c, y + c, w - 2 * c, h - 2 * c, u + c, v + c, m, m);
    }

    public static void panel(GuiGraphics g, int x, int y, int w, int h) { nine(g, x, y, w, h, 0, 0, 24, 4); }
    /** Inset row background; the selected variant has a gold rim. */
    public static void card(GuiGraphics g, int x, int y, int w, int h, boolean selected) { nine(g, x, y, w, h, selected ? 40 : 24, 0, 16, 3); }

    static void statusCard(GuiGraphics g, int x, int y, int w, int h) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        nine(g, x, y, w, h, 80, 80, 16, 3);
        RenderSystem.disableBlend();
    }

    static void identityChip(GuiGraphics g, Font font, Component label, String key, int x, int y, int w) {
        int cell = switch (key) {
            case "identity.mcyukkuri.pet" -> 0;
            case "identity.mcyukkuri.stray" -> 1;
            case "identity.mcyukkuri.wild_settlement" -> 2;
            case "identity.mcyukkuri.wild_family" -> 3;
            default -> 4;
        };
        nine(g, x, y, w, 13, cell * 16, 80, 16, 3);
        g.drawString(font, label, x + 5, y + 2, INK, false);
    }

    public static void icon(GuiGraphics g, int x, int y, int icon) { blit(g, x, y, icon * 16, 32, 16, 16); }
    /** Little round badge for grade 1-3 (bronze, silver, gold), 9x9; grade 0 draws nothing. */
    public static void chip(GuiGraphics g, int x, int y, int grade) { if (grade >= 1 && grade <= 3) blit(g, x, y, (grade - 1) * 9, 48, 9, 9); }
    /** Grey chip with a red cross for a badge that has expired. */
    public static void expiredChip(GuiGraphics g, int x, int y) { blit(g, x, y, 27, 48, 9, 9); }
    public static final int CHIP_WIDTH = 11;

    /** 0-100 values shown as words (Low / Mid / High); the thresholds live only here. */
    public static final int LEVEL_MID = 30, LEVEL_HIGH = 60;
    public static MutableComponent level(int value) {
        return Component.translatable("shop.mcyukkuri.level." + (value < LEVEL_MID ? 0 : value < LEVEL_HIGH ? 1 : 2));
    }
    /** Keeps a one-line label inside its reserved width. The caller retains the original for its hover tooltip. */
    public static Component ellipsize(Font font, Component text, int maxWidth) {
        if (font.width(text) <= maxWidth) return text;
        String mark = "…";
        int remaining = maxWidth - font.width(mark);
        return remaining < 0 ? Component.empty()
                : Component.literal(font.plainSubstrByWidth(text.getString(), remaining) + mark);
    }
    /** Mouse inside the rectangle (all in the same, absolute screen coordinates). */
    public static boolean over(int mx, int my, int x, int y, int w, int h) { return mx >= x && mx < x + w && my >= y && my < y + h; }

    /** Grade name in its badge colour. */
    public static MutableComponent gradeName(int grade) {
        int id = Math.max(0, Math.min(3, grade));
        return Component.translatable("badge.mcyukkuri.profile.grade." + id).withStyle(s -> s.withColor(GRADE_TEXT[id] & 0xffffff));
    }

    /** Grade name greyed and struck through (expired badge). */
    public static MutableComponent expiredGradeName(int grade) {
        return Component.translatable("badge.mcyukkuri.profile.grade." + Math.max(0, Math.min(3, grade))).withStyle(s -> s.withColor(SOFT & 0xffffff).withStrikethrough(true));
    }

    /** Keeps vanilla click handling and narration while drawing the shared action sprites. */
    public static final class ActionButton extends Button {
        private boolean pressed;

        public ActionButton(Builder builder) {
            super(builder);
        }

        @Override public boolean mouseClicked(double x, double y, int button) {
            pressed = super.mouseClicked(x, y, button);
            return pressed;
        }

        @Override public void onRelease(double x, double y) {
            pressed = false;
        }

        @Override protected void renderWidget(GuiGraphics g, int mx, int my, float partial) {
            int u = !active ? 48 : pressed ? 32 : isHoveredOrFocused() ? 16 : 0;
            nine(g, getX(), getY(), getWidth(), getHeight(), u, 64, 16, 3);
            var minecraft = Minecraft.getInstance();
            var font = minecraft.font;
            Component full = getMessage();
            Component label = ellipsize(font, full, getWidth() - 10);
            int x = getX() + (getWidth() - font.width(label)) / 2;
            int y = getY() + (getHeight() - 9) / 2 + (pressed && active ? 1 : 0);
            g.drawString(font, label, x, y, active ? INK : 0xff60665d, false);
            if (label != full && (isHovered() || isFocused() && minecraft.getLastInputType().isKeyboard())) {
                var lines = new ArrayList<>(Tooltip.splitTooltip(minecraft, full));
                if (getTooltip() != null) lines.addAll(getTooltip().toCharSequence(minecraft));
                minecraft.screen.setTooltipForNextRenderPass(lines);
            }
        }
    }

    /** Tab with a 16x16 icon; the inactive (current) tab is drawn as selected. Click behaviour is the plain Button's. */
    public static final class Tab extends Button {
        private final int icon;
        public Tab(int x, int y, int w, int h, Component title, int icon, Button.OnPress press) { super(x, y, w, h, title, press, DEFAULT_NARRATION); this.icon = icon; }
        @Override public void renderWidget(GuiGraphics g, int mx, int my, float partial) {
            nine(g, getX(), getY(), getWidth(), getHeight(), active ? (isHoveredOrFocused() ? 72 : 56) : 88, 0, 16, 3);
            int top = getY() + (getHeight() - 16) / 2 - (active ? 0 : 1);
            boolean compact = getWidth() < 70; // five tabs share the row: icon and label sit closer together
            icon(g, getX() + (compact ? 3 : 4), top, icon);
            var font = Minecraft.getInstance().font;
            g.drawString(font, getMessage(), getX() + (compact ? 21 : 23), getY() + (getHeight() - 8) / 2 - (active ? 0 : 1), active ? SOFT : INK, false);
        }
    }
}
