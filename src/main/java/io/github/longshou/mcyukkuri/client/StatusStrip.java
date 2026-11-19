package io.github.longshou.mcyukkuri.client;

import io.github.longshou.mcyukkuri.body.BodySigns;
import io.github.longshou.mcyukkuri.entity.YukkuriView;
import io.github.longshou.mcyukkuri.family.FamilyLife;
import io.github.longshou.mcyukkuri.status.Identity;
import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.life.NeedWarnings.Need;
import io.github.longshou.mcyukkuri.registry.ModItems;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;

/** Shared status card built from already-synced values. */
public final class StatusStrip {
    static final int CARD_HEIGHT = 40;
    private static final int ICON = 16;
    private static final Need[] NEEDS = Need.values();
    private static final int TEXT_LEFT = 25, CHIP_GAP = 5, PAD = 6, STATUS_TOP = 20;

    static Readout of(YukkuriView view) {
        if (Identity.STOCK.translationKey().equals(view.identityTranslationKey())) {
            var status = view.visibleSyndromePrevention()
                    ? Component.translatable("magnifier.mcyukkuri.syndrome_prevention") : Component.empty();
            if (view.visibleDisqualified()) status.append(" ").append(Component.translatable("badge.mcyukkuri.profile.owner_state.3"));
            return new Readout(view.getDisplayName(), stage(view), view.identityTranslationKey(), status);
        }
        Component health = Component.translatable("magnifier.mcyukkuri.health",
                Math.round(view.getHealth()), Math.round(view.getMaxHealth()));
        Component grade = Component.translatable("badge.mcyukkuri.profile.grade." + view.visibleMedal());
        Component badge = Component.translatable("magnifier.mcyukkuri.badge", view.visibleDisqualified()
                ? Component.translatable("badge.mcyukkuri.disqualified", grade) : grade);
        int mold = BodySigns.moldTier(view.visibleSigns() & BodySigns.MOLD_MASK);
        Component dirt = mold > 0
                ? Component.translatable("magnifier.mcyukkuri.moldy", mold)
                : Component.translatable("magnifier.mcyukkuri.no_mold");

        MutableComponent readouts = Component.empty()
                .append(health).append(" · ")
                .append(badge).append(" · ")
                .append(dirt);
        if (view.liveChildCount() > Integer.bitCount(view.liveBornMask())) {
            readouts.append(" · ").append(Component.translatable("magnifier.mcyukkuri.pregnant"));
        }
        if (view.visibleSyndromePrevention())
            readouts.append(" · ").append(Component.translatable("magnifier.mcyukkuri.syndrome_prevention"));
        return new Readout(Component.literal(view.getDisplayName().getString()), stage(view), view.identityTranslationKey(), readouts);
    }

    record Readout(Component name, Component stage, String identityKey, Component status) {
        private Component title(Component displayedName) {
            return Component.translatable("magnifier.mcyukkuri.header", displayedName, stage);
        }

        private int chipWidth(Font font, Component identity) {
            return identityKey.isEmpty() ? 0 : font.width(identity) + 10;
        }

        Component renderCard(GuiGraphics g, Font font, int x, int y, int w, int h) {
            return renderCard(g, font, x, y, w, h, font.split(status, w - 2 * PAD));
        }

        private Component renderCard(GuiGraphics g, Font font, int x, int y, int w, int h, List<FormattedCharSequence> lines) {
            GuiKit.statusCard(g, x, y, w, h);
            GuiKit.icon(g, x + 5, y + 1, GuiKit.ICON_STATUS);
            Component identity = Component.translatable(identityKey);
            int chip = chipWidth(font, identity);
            int titleRoom = w - TEXT_LEFT - PAD - (chip == 0 ? 0 : chip + CHIP_GAP);
            Component shortName = GuiKit.ellipsize(font, name, titleRoom - font.width(title(Component.empty())));
            g.drawString(font, title(shortName), x + TEXT_LEFT, y + 6, GuiKit.INK, false);
            if (chip > 0) GuiKit.identityChip(g, font, identity, identityKey, x + w - PAD - chip, y + 4, chip);
            for (int line = 0; line < lines.size(); line++) {
                g.drawString(font, lines.get(line), x + PAD, y + STATUS_TOP + line * font.lineHeight, GuiKit.INK, false);
            }
            return shortName == name ? null : title(name);
        }

        HudLayout measureHud(Font font, int maxWidth, String outlookId, int needs, Component... details) {
            var rows = new ArrayList<Component>();
            rows.add(status);
            rows.addAll(List.of(details));
            if (!outlookId.isEmpty()) rows.add(Component.translatable("magnifier.mcyukkuri.outlook." + outlookId));
            int chip = chipWidth(font, Component.translatable(identityKey));
            int textWidth = rows.stream().mapToInt(font::width).max().orElseThrow() + 2 * PAD;
            int needsWidth = Integer.bitCount(needs) * ICON + 2 * PAD;
            int w = Math.min(maxWidth, Math.max(Math.max(needsWidth, textWidth), font.width(title(name)) + TEXT_LEFT + PAD + chip + CHIP_GAP));
            var lines = rows.stream().flatMap(row -> font.split(row, w - 2 * PAD).stream()).toList();
            int columns = Math.max(1, (w - 2 * PAD) / ICON);
            int iconRows = Math.ceilDiv(Integer.bitCount(needs), columns);
            return new HudLayout(w, STATUS_TOP + lines.size() * font.lineHeight + iconRows * ICON + 4, lines, columns);
        }

        void renderHud(GuiGraphics g, Font font, int bottom, String outlookId, int needs, Component... details) {
            var card = measureHud(font, g.guiWidth() - 16, outlookId, needs, details);
            int x = (g.guiWidth() - card.width()) / 2, y = bottom - card.height();
            renderCard(g, font, x, y, card.width(), card.height(), card.lines());
            int top = y + STATUS_TOP + card.lines().size() * font.lineHeight;
            int index = 0;
            for (var need : NEEDS) {
                if ((needs & need.bit()) == 0) continue;
                icon(g, need, x + PAD + index % card.columns() * ICON, top + index / card.columns() * ICON);
                index++;
            }
        }
    }

    record HudLayout(int width, int height, List<FormattedCharSequence> lines, int columns) {}

    private static void icon(GuiGraphics g, Need need, int x, int y) {
        switch (need) {
            case URINE -> g.blit(MCYukkuri.id("textures/effects/leak_puddle.png"), x, y, ICON, ICON, 0, 0, 16, 16, 16, 16);
            case STOOL -> g.renderItem(ModItems.WASTE.get().getDefaultInstance(), x, y);
            case THIRST -> g.renderItem(Items.WATER_BUCKET.getDefaultInstance(), x, y);
            case SLEEP -> g.renderItem(Items.RED_BED.getDefaultInstance(), x, y);
            case HUNGER -> g.renderItem(Items.BREAD.getDefaultInstance(), x, y);
            case COLD -> g.renderItem(Items.SNOWBALL.getDefaultInstance(), x, y);
            case OVERHEATING -> g.renderItem(Items.BLAZE_POWDER.getDefaultInstance(), x, y);
            case BREEDING -> g.blitSprite(ResourceLocation.withDefaultNamespace("hud/heart/full"), x + 2, y + 2, 12, 12);
        }
    }

    private static Component stage(YukkuriView view) {
        String id = switch (FamilyLife.Stage.values()[view.lifeStage()]) {
            case BABY -> "baby";
            case JUVENILE -> "juvenile";
            case ADULT -> "adult";
        };
        return Component.translatable("stage.mcyukkuri." + id);
    }

    private StatusStrip() {}
}
