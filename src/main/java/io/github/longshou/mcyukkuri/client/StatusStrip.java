package io.github.longshou.mcyukkuri.client;

import io.github.longshou.mcyukkuri.body.BodySigns;
import io.github.longshou.mcyukkuri.entity.YukkuriView;
import io.github.longshou.mcyukkuri.family.FamilyLife;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/** Shared status card built from already-synced values. */
public final class StatusStrip {
    static final int CARD_HEIGHT = 40;
    private static final int TEXT_LEFT = 25, CHIP_GAP = 5, PAD = 6, STATUS_TOP = 20;

    static Readout of(YukkuriView view) {
        Component health = Component.translatable("magnifier.mcyukkuri.health",
                Math.round(view.getHealth()), Math.round(view.getMaxHealth()));
        Component badge = Component.translatable("magnifier.mcyukkuri.badge",
                Component.translatable("badge.mcyukkuri.profile.grade." + view.visibleMedal()));
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
            return renderCard(g, font, x, y, w, h, Component.empty());
        }

        private Component renderCard(GuiGraphics g, Font font, int x, int y, int w, int h, Component outlook) {
            GuiKit.statusCard(g, x, y, w, h);
            GuiKit.icon(g, x + 5, y + 1, GuiKit.ICON_STATUS);
            Component identity = Component.translatable(identityKey);
            int chip = chipWidth(font, identity);
            int titleRoom = w - TEXT_LEFT - PAD - (chip == 0 ? 0 : chip + CHIP_GAP);
            Component shortName = GuiKit.ellipsize(font, name, titleRoom - font.width(title(Component.empty())));
            g.drawString(font, title(shortName), x + TEXT_LEFT, y + 6, GuiKit.INK, false);
            if (chip > 0) GuiKit.identityChip(g, font, identity, identityKey, x + w - PAD - chip, y + 4, chip);
            var lines = font.split(status, w - 2 * PAD);
            for (int line = 0; line < lines.size(); line++) {
                g.drawString(font, lines.get(line), x + PAD, y + STATUS_TOP + line * font.lineHeight, GuiKit.INK, false);
            }
            if (!outlook.getString().isEmpty())
                g.drawString(font, outlook, x + PAD, y + STATUS_TOP + lines.size() * font.lineHeight, GuiKit.INK, false);
            return shortName == name ? null : title(name);
        }

        void renderHud(GuiGraphics g, Font font, int bottom, String outlookId) {
            Component outlook = outlookId.isEmpty() ? Component.empty() : Component.translatable("magnifier.mcyukkuri.outlook." + outlookId);
            int chip = chipWidth(font, Component.translatable(identityKey));
            int w = Math.min(g.guiWidth() - 16,
                    Math.max(Math.max(font.width(status), font.width(outlook)) + 2 * PAD,
                            font.width(title(name)) + TEXT_LEFT + PAD + chip + CHIP_GAP));
            int h = STATUS_TOP + font.split(status, w - 2 * PAD).size() * font.lineHeight + 4;
            if (!outlookId.isEmpty()) h += font.lineHeight;
            renderCard(g, font, (g.guiWidth() - w) / 2, bottom - h, w, h, outlook);
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
