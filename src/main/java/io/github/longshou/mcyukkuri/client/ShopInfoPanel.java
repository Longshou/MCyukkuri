package io.github.longshou.mcyukkuri.client;

import io.github.longshou.mcyukkuri.entity.SpeciesProfile;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import io.github.longshou.mcyukkuri.network.ShopInfoPayload;
import io.github.longshou.mcyukkuri.shop.ShopHint;
import io.github.longshou.mcyukkuri.shop.ShopMembership;
import java.util.ArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import org.lwjgl.glfw.GLFW;

/** Modal stock information shared by the in-store card and the clerk's roster. */
final class ShopInfoPanel {
    private static final int PAD = 10, PORTRAIT = 52, LINE_GAP = 4;
    private final Button close = Button.builder(Component.translatable("shop.mcyukkuri.info.close"), button -> info = null)
            .bounds(0, 0, 80, 18).build(GuiKit.ActionButton::new);
    private ShopInfoPayload info;

    static void setup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> ShopInfoPayload.installClientReceiver(ShopInfoPanel::accept));
    }

    private static void accept(ShopInfoPayload payload) {
        var screen = Minecraft.getInstance().screen;
        if (screen instanceof CareScreen care && care.getMenu().shop() && care.getMenu().containerId == payload.menuId()) {
            care.setFocused(null);
            care.shopInfo.show(payload);
        } else if (screen instanceof ShopScreen shop && shop.getMenu().containerId == payload.menuId()) {
            shop.setFocused(null);
            shop.shopInfo.show(payload);
        }
    }

    private void show(ShopInfoPayload payload) { info = payload; }
    boolean open() { return info != null; }

    boolean mouseClicked(double x, double y, int button) {
        if (!open()) return false;
        close.mouseClicked(x, y, button);
        close.onRelease(x, y);
        return true;
    }

    boolean keyPressed(int key) {
        if (!open()) return false;
        if (key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER
                || key == GLFW.GLFW_KEY_SPACE) info = null;
        return true;
    }

    void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        var mc = Minecraft.getInstance();
        var font = mc.font;
        var variant = YukkuriVariant.values()[info.species()];
        String prefix = "shop.mcyukkuri.info." + (info.oni() ? "oni." : "pet.");
        var heading = Component.translatable(prefix + "title");
        var name = Component.translatable("magnifier.mcyukkuri.header", ShopScreen.species(info.species() + 1),
                Component.translatable("human.mcyukkuri.stage." + info.stage()));
        var tier = Component.translatable("shop.mcyukkuri.info.tier", ShopMembership.byId(info.requiredTier()).nameText());
        int w = Math.min(340, g.guiWidth() - 24);
        int textWidth = w - 2 * PAD;
        var body = new ArrayList<Component>();
        if (!info.oni()) {
            String note = SpeciesProfile.of(variant).dangerToYukkuri() ? "predator" : variant.assetId();
            body.add(Component.translatable("shop.mcyukkuri.about.note." + note));
        }
        for (int hint : info.hints()) body.add(Component.translatable(prefix + ShopHint.values()[hint].key()));
        var paragraphs = body.stream().map(text -> font.split(text, textWidth)).toList();
        int h = 2 * PAD + PORTRAIT + 32 + font.lineHeight
                + paragraphs.stream().mapToInt(lines -> lines.size() * font.lineHeight + LINE_GAP).sum();
        int x = (g.guiWidth() - w) / 2, y = (g.guiHeight() - h) / 2;
        int tx = x + PAD + PORTRAIT + PAD, tw = textWidth - PORTRAIT - PAD;
        g.pose().pushPose();
        g.pose().translate(0, 0, 400);
        g.fill(0, 0, g.guiWidth(), g.guiHeight(), 0xA0000000);
        GuiKit.panel(g, x, y, w, h);
        GuiKit.statusCard(g, x + PAD, y + PAD, PORTRAIT, PORTRAIT);
        if (mc.level.getEntity(info.entityId()) instanceof YukkuriEntity animal && animal.isAlive())
            CareInspection.drawModel(g, animal, x + PAD, y + PAD, PORTRAIT, PORTRAIT, 18, 12, 1);
        g.drawString(font, GuiKit.ellipsize(font, heading, tw), tx, y + PAD + 10, GuiKit.SOFT, false);
        g.drawString(font, GuiKit.ellipsize(font, name, tw), tx, y + PAD + 27, GuiKit.INK, false);
        int lineY = y + PAD + PORTRAIT + LINE_GAP;
        for (var lines : paragraphs) {
            for (var line : lines) {
                g.drawString(font, line, x + PAD, lineY, GuiKit.INK, false);
                lineY += font.lineHeight;
            }
            lineY += LINE_GAP;
        }
        g.drawString(font, GuiKit.ellipsize(font, tier, textWidth), x + PAD, lineY, GuiKit.SOFT, false);
        close.setX(x + (w - close.getWidth()) / 2);
        close.setY(y + h - PAD - close.getHeight());
        close.render(g, mouseX, mouseY, partial);
        g.pose().popPose();
    }
}
