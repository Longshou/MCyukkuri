package io.github.longshou.mcyukkuri.client;

import java.util.List;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

/** Museum viewer, enabled by the playground launcher. */
final class ViewerScreen extends Screen {
    private static final KeyMapping KEY = new KeyMapping("key.mcyukkuri.viewer", GLFW.GLFW_KEY_M, "key.categories.mcyukkuri");
    private static final String KEY_PREFIX = "viewer.mcyukkuri.";
    private static final int LINE = 12, BUTTON = 18, PITCH = 20, PAD = 8;
    private static int scroll;

    private final ViewerTodo todo = ViewerTodo.read();
    private List<FormattedCharSequence> summary;
    private List<Row> rows = List.of();
    private int left, right, listTop, listBottom, contentHeight;

    private record Row(ViewerTodo.Item item, List<FormattedCharSequence> text) {
        int buttonOffset() { return 2 * PAD + LINE + text.size() * LINE; }
        int height() { return buttonOffset() + item.scenes().size() * PITCH + PAD; }
    }

    private ViewerScreen() { super(Component.translatable("key.mcyukkuri.viewer")); }

    static void keys(RegisterKeyMappingsEvent event) { event.register(KEY); }

    static void tick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        while (KEY.consumeClick()) {
            if (mc.screen == null && mc.player != null) mc.setScreen(new ViewerScreen());
        }
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override protected void init() {
        left = Math.max(PAD, (width - 420) / 2);
        right = width - left;
        listBottom = height - 2 * PAD;
        Component heading = tr("todo.missing");
        if (todo != null) {
            long failed = todo.items().stream().filter(ViewerTodo.Item::failed).count();
            long fresh = todo.items().stream().filter(ViewerTodo.Item::fresh).count();
            heading = tr("todo.summary", failed, todo.items().size() - failed, fresh, shortCommit(todo.since()), shortCommit(todo.until()));
            int textWidth = right - left - 5 * PAD;
            rows = todo.items().stream().map(item -> {
                var detail = Component.literal(item.title());
                if (!item.watch().isEmpty()) detail.append("\n").append(Component.literal(item.watch()).withColor(GuiKit.SOFT & 0xffffff));
                if (item.scenes().isEmpty()) detail.append("\n").append(tr("todo.no_scene").withColor(GuiKit.SOFT & 0xffffff));
                return new Row(item, font.split(detail, textWidth));
            }).toList();
        }
        summary = font.split(heading, right - left - 2 * PAD);
        listTop = 2 * PAD + BUTTON + 4 + summary.size() * LINE + PAD;
        contentHeight = rows.stream().mapToInt(row -> row.height() + 4).sum();
        rebuildButtons();
    }

    private void rebuildButtons() {
        clearFocus();
        clearWidgets();
        int tabWidth = (right - left - 4 * PAD) / 3;
        int x = left + PAD;
        addRenderableWidget(new GuiKit.Tab(x, 2 * PAD, tabWidth, BUTTON, tr("tab.todo"), GuiKit.ICON_STATUS, b -> {})).active = false;
        x += tabWidth + PAD;
        for (String tab : List.of("clips", "faces")) {
            addRenderableWidget(Button.builder(tr("tab." + tab), b -> {})
                    .bounds(x, 2 * PAD, tabWidth, BUTTON).build(GuiKit.ActionButton::new)).active = false;
            x += tabWidth + PAD;
        }

        scroll = Mth.clamp(scroll, 0, maxScroll());
        int y = listTop - scroll;
        for (Row row : rows) {
            int buttonY = y + row.buttonOffset();
            for (var scene : row.item().scenes()) {
                if (buttonY >= listTop && buttonY + BUTTON <= listBottom) {
                    addRenderableWidget(Button.builder(Component.literal(scene.label()), b -> jump(scene))
                            .bounds(left + 2 * PAD, buttonY, right - left - 5 * PAD, BUTTON)
                            .tooltip(Tooltip.create(Component.literal(scene.field()))).build(GuiKit.ActionButton::new));
                }
                buttonY += PITCH;
            }
            y += row.height() + 4;
        }
    }

    private void jump(ViewerTodo.Scene scene) {
        var mc = minecraft;
        var player = mc.player;
        var server = mc.getSingleplayerServer();
        var id = ResourceLocation.fromNamespaceAndPath("mcylab", "jump/" + scene.id());
        onClose();
        if (server == null || player.connection.getCommands().getRoot().getChild("function") == null) {
            player.displayClientMessage(tr("todo.world"), false);
            return;
        }
        server.execute(() -> {
            boolean available = server.getFunctions().get(id).isPresent();
            mc.execute(() -> {
                if (mc.player != player) return;
                if (available) player.connection.sendCommand("function " + id);
                else player.displayClientMessage(tr("todo.world"), false);
            });
        });
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partial) {
        renderBackground(graphics, mouseX, mouseY, partial);
        GuiKit.panel(graphics, left, PAD, right - left, height - 2 * PAD);
        drawLines(graphics, summary, left + PAD, 2 * PAD + BUTTON + 4, GuiKit.INK);
        graphics.enableScissor(left + PAD, listTop, right - 2 * PAD, listBottom);
        int y = listTop - scroll;
        for (Row row : rows) {
            if (y + row.height() > listTop && y < listBottom) {
                GuiKit.card(graphics, left + PAD, y, right - left - 3 * PAD, row.height(), row.item().fresh());
                int textX = left + 2 * PAD;
                boolean failed = row.item().failed();
                Component status = tr(failed ? "todo.failed" : "todo.ready");
                int chipWidth = font.width(status) + 8;
                graphics.fill(textX, y + PAD - 2, textX + chipWidth, y + PAD + 10, failed ? 0xfff4d1cb : 0xfff6e6a6);
                graphics.drawString(font, status, textX + 4, y + PAD, failed ? GuiKit.WARN : 0xff75500e, false);
                if (row.item().fresh()) graphics.drawString(font, tr("todo.fresh"), textX + chipWidth + 6, y + PAD, GuiKit.GOOD, false);
                drawLines(graphics, row.text(), textX, y + PAD + LINE, row.item().scenes().isEmpty() ? GuiKit.SOFT : GuiKit.INK);
            }
            y += row.height() + 4;
        }
        graphics.disableScissor();
        if (maxScroll() > 0) {
            int track = listBottom - listTop;
            int thumb = Math.max(PAD, track * track / contentHeight);
            int top = listTop + (track - thumb) * scroll / maxScroll();
            graphics.fill(right - 12, listTop, right - PAD, listBottom, 0xffdfd4bd);
            graphics.fill(right - 12, top, right - PAD, top + thumb, 0xff9b815e);
        }
        super.render(graphics, mouseX, mouseY, partial);
    }

    private void drawLines(GuiGraphics graphics, List<FormattedCharSequence> lines, int x, int y, int color) {
        for (var line : lines) {
            graphics.drawString(font, line, x, y, color, false);
            y += LINE;
        }
    }

    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (vertical == 0 || maxScroll() == 0) return super.mouseScrolled(x, y, horizontal, vertical);
        scroll -= (int) Math.round(vertical * 3 * LINE);
        rebuildButtons();
        return true;
    }

    @Override public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (KEY.matches(key, scanCode)) {
            onClose();
            return true;
        }
        return super.keyPressed(key, scanCode, modifiers);
    }

    private int maxScroll() { return Math.max(0, contentHeight - (listBottom - listTop)); }

    private static MutableComponent tr(String key, Object... args) { return Component.translatable(KEY_PREFIX + key, args); }

    private static String shortCommit(String commit) { return commit == null ? "—" : commit.substring(0, Math.min(9, commit.length())); }
}
