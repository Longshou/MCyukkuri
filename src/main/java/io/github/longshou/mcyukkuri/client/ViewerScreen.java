package io.github.longshou.mcyukkuri.client;

import com.google.gson.Gson;
import io.github.longshou.mcyukkuri.MCYukkuri;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
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
    private static final int PANEL = 520;
    private static int tab, familyIndex;

    private final ViewerTodo todo = ViewerTodo.read();
    private ViewerCatalog catalog;
    private Map<String, String> clipNames;
    private List<String> forms;
    private List<FormattedCharSequence> summary;
    private List<Row> rows = List.of();
    private int left, right, listTop, listBottom, contentHeight;
    private int scroll, listScroll, listContent;
    private float yaw = 18, pitch = 12, zoom = 1;
    private boolean dragging;
    private int columnX, columnW, rowFormY, rowFamilyY, listTopY, listBottomY;
    private int previewX, previewY, previewW, previewH, controlY, sliderY, timeY;
    private Scrub scrub;

    private record Row(ViewerTodo.Item item, List<FormattedCharSequence> text) {
        int buttonOffset() { return 2 * PAD + LINE + text.size() * LINE; }
        int height() { return buttonOffset() + item.scenes().size() * PITCH + PAD; }
    }

    private record ViewerCatalog(Map<String, String> models, List<Family> families) {
        record Family(String title, String[][] clips) {}

        static ViewerCatalog read() {
            try (var reader = Minecraft.getInstance().getResourceManager()
                    .getResourceOrThrow(MCYukkuri.id("yukkuri_presentation/viewer_catalog.json")).openAsReader()) {
                return new Gson().fromJson(reader, ViewerCatalog.class);
            } catch (IOException error) {
                throw new UncheckedIOException("Cannot load viewer catalog", error);
            }
        }
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
        catalog = ViewerCatalog.read();
        clipNames = new HashMap<>();
        for (var family : catalog.families()) for (var clip : family.clips()) clipNames.put(clip[0], clip[1]);
        forms = List.copyOf(catalog.models().keySet());
        ViewerStage.open(forms.get(0));
        familyIndex = Math.min(familyIndex, familyCount() - 1);
        left = Math.max(PAD, (width - PANEL) / 2);
        right = width - left;
        listBottom = height - 2 * PAD;
        buildTodo();
        refresh();
    }

    @Override public void onClose() {
        ViewerStage.close();
        super.onClose();
    }

    private void buildTodo() {
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
    }

    private void refresh() {
        clearFocus();
        clearWidgets();
        buildTabs();
        if (tab == 0) buildTodoRows(); else buildStage();
    }

    private void buildTabs() {
        int tabWidth = (right - left - 4 * PAD) / 3;
        int x = left + PAD;
        String[] names = {"todo", "clips", "faces"};
        for (int i = 0; i < names.length; i++) {
            int index = i;
            var widget = addRenderableWidget(new GuiKit.Tab(x, 2 * PAD, tabWidth, BUTTON, tr("tab." + names[i]), GuiKit.ICON_STATUS,
                    b -> switchTab(index)));
            widget.active = tab != i;
            x += tabWidth + PAD;
        }
    }

    private void switchTab(int index) {
        tab = index;
        scroll = 0;
        listScroll = 0;
        refresh();
    }

    private void buildTodoRows() {
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

    // ------------------------------------------------------------------ 动作 / 表情
    private int formIndex() { return forms.indexOf(ViewerStage.form); }

    private int familyCount() { return catalog.families().size() + 1; }

    private void cycleForm(int delta) {
        ViewerStage.setForm(forms.get(Math.floorMod(formIndex() + delta, forms.size())));
        refresh();
    }

    private void cycleFamily(int delta) {
        familyIndex = Math.floorMod(familyIndex + delta, familyCount());
        listScroll = 0;
        refresh();
    }

    private void buildStage() {
        int contentTop = 2 * PAD + BUTTON + 8;
        int contentBottom = height - 2 * PAD - PAD;
        columnX = left + PAD;
        columnW = (right - left - 3 * PAD) * 2 / 5;
        previewX = columnX + columnW + PAD;
        previewW = right - PAD - previewX;
        previewY = contentTop;
        previewH = Math.min(170, contentBottom - contentTop - 3 * PITCH - 40);
        controlY = previewY + previewH + 4;
        sliderY = controlY + BUTTON + 4;
        timeY = sliderY + 16 + 4;
        int y = contentTop;
        addRenderableWidget(arrow(columnX, y, "◀", b -> cycleForm(-1)));
        addRenderableWidget(arrow(columnX + columnW - 16, y, "▶", b -> cycleForm(1)));
        rowFormY = y;
        y += BUTTON + 4;
        if (tab == 1) {
            addRenderableWidget(arrow(columnX, y, "◀", b -> cycleFamily(-1)));
            addRenderableWidget(arrow(columnX + columnW - 16, y, "▶", b -> cycleFamily(1)));
            rowFamilyY = y;
            y += BUTTON + 4;
        } else {
            addRenderableWidget(Button.builder(tr("face.follow"), b -> { ViewerStage.setFace(null); refresh(); })
                    .bounds(columnX, y, columnW, BUTTON).build(GuiKit.ActionButton::new)).active = ViewerStage.face != null;
            rowFamilyY = y;
            y += BUTTON + 4;
        }
        listTopY = y;
        listBottomY = contentBottom;
        buildList();
        buildControls();
    }

    private Button arrow(int x, int y, String glyph, Button.OnPress press) {
        return Button.builder(Component.literal(glyph), press).bounds(x, y, 16, BUTTON).build(GuiKit.ActionButton::new);
    }

    private void buildList() {
        var ids = tab == 1 ? familyClips() : selectableFaces();
        int view = listBottomY - listTopY;
        listContent = ids.size() * PITCH;
        listScroll = Mth.clamp(listScroll, 0, Math.max(0, listContent - view));
        int y = listTopY - listScroll;
        for (var id : ids) {
            if (y >= listTopY && y + BUTTON <= listBottomY) {
                boolean selected = tab == 1 ? id.equals(ViewerStage.clip) : id.equals(ViewerStage.face);
                String name = tab == 1 ? clipNames.getOrDefault(id, id) : FaceLibrary.label(id);
                var button = addRenderableWidget(Button.builder(Component.literal(name), b -> {
                    if (tab == 1) ViewerStage.setClip(id); else ViewerStage.setFace(id);
                    refresh();
                }).bounds(columnX, y, columnW - 12, BUTTON).build(GuiKit.ActionButton::new));
                button.active = !selected;
            }
            y += PITCH;
        }
    }

    private List<String> familyClips() {
        var clips = YukkuriModel.get(ViewerStage.form).clips();
        var ids = new ArrayList<String>();
        if (familyIndex < catalog.families().size()) {
            for (var clip : catalog.families().get(familyIndex).clips()) if (clips.containsKey(clip[0])) ids.add(clip[0]);
        } else {
            for (var id : clips.keySet()) if (!clipNames.containsKey(id)) ids.add(id);
            ids.sort(Comparator.naturalOrder());
        }
        return ids;
    }

    private List<String> selectableFaces() {
        return FaceLibrary.selectable(ViewerStage.form).stream().sorted(Comparator.comparing(FaceLibrary::label)).toList();
    }

    private void buildControls() {
        int width = (previewW - 3 * PAD) / 4;
        int x = previewX;
        addRenderableWidget(Button.builder(tr("clip.once"), b -> ViewerStage.playOnce())
                .bounds(x, controlY, width, BUTTON).build(GuiKit.ActionButton::new));
        x += width + PAD;
        addRenderableWidget(Button.builder(tr("clip.loop"), b -> ViewerStage.playLoop())
                .bounds(x, controlY, width, BUTTON).build(GuiKit.ActionButton::new));
        x += width + PAD;
        addRenderableWidget(Button.builder(tr("clip.speed", ViewerStage.speedLabel()), b -> {
            ViewerStage.cycleSpeed();
            refresh();
        }).bounds(x, controlY, width, BUTTON).build(GuiKit.ActionButton::new));
        x += width + PAD;
        addRenderableWidget(Button.builder(tr(ViewerStage.playing ? "clip.pause" : "clip.resume"), b -> {
            ViewerStage.togglePlay();
            refresh();
        }).bounds(x, controlY, width, BUTTON).build(GuiKit.ActionButton::new));
        scrub = addRenderableWidget(new Scrub(previewX, sliderY, previewW, 16, position()));
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

    // ------------------------------------------------------------------ rendering
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partial) {
        renderBackground(graphics, mouseX, mouseY, partial);
        GuiKit.panel(graphics, left, PAD, right - left, height - 2 * PAD);
        if (tab == 0) renderTodo(graphics); else renderStage(graphics);
        super.render(graphics, mouseX, mouseY, partial);
    }

    private void renderTodo(GuiGraphics graphics) {
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
    }

    private void renderStage(GuiGraphics graphics) {
        ViewerStage.advance();
        if (scrub != null) scrub.show(position());
        centered(graphics, catalog.models().get(ViewerStage.form), columnX, columnW, rowFormY + 5, GuiKit.INK);
        centered(graphics, familyIndex < catalog.families().size() ? catalog.families().get(familyIndex).title() : tr("family.other").getString(),
                columnX, columnW, rowFamilyY + 5, GuiKit.SOFT);
        int view = listBottomY - listTopY;
        if (listContent > view) {
            int thumb = Math.max(PAD, view * view / listContent);
            int top = listTopY + (view - thumb) * listScroll / (listContent - view);
            graphics.fill(columnX + columnW - 10, listTopY, columnX + columnW - 6, listBottomY, 0xffdfd4bd);
            graphics.fill(columnX + columnW - 10, top, columnX + columnW - 6, top + thumb, 0xff9b815e);
        }
        GuiKit.card(graphics, previewX, previewY, previewW, previewH, false);
        ViewerStage.draw(graphics, previewX + 2, previewY + 2, previewW - 4, previewH - 4, yaw, pitch, zoom);
        graphics.drawString(font, GuiKit.ellipsize(font, tr("orbit"), previewW - 2 * PAD), previewX + PAD, previewY + previewH - LINE,
                GuiKit.SOFT, false);
        graphics.drawString(font, tr("clip.time", ViewerStage.clip, number(ViewerStage.seconds), number(ViewerStage.duration())),
                previewX, timeY, GuiKit.INK, false);
    }

    private void centered(GuiGraphics graphics, String text, int x, int width, int y, int color) {
        var label = GuiKit.ellipsize(font, Component.literal(text), width - 36);
        graphics.drawString(font, label, x + (width - font.width(label)) / 2, y, color, false);
    }

    private void drawLines(GuiGraphics graphics, List<FormattedCharSequence> lines, int x, int y, int color) {
        for (var line : lines) {
            graphics.drawString(font, line, x, y, color, false);
            y += LINE;
        }
    }

    // ------------------------------------------------------------------ input
    @Override public boolean mouseClicked(double x, double y, int button) {
        if (super.mouseClicked(x, y, button)) return true;
        if (button == 0 && tab != 0 && GuiKit.over((int) x, (int) y, previewX, previewY, previewW, previewH)) {
            dragging = true;
            return true;
        }
        return false;
    }

    @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (dragging) {
            yaw = Mth.wrapDegrees(yaw + (float) dx * 2);
            pitch = Mth.wrapDegrees(pitch + (float) dy * 2);
            return true;
        }
        return super.mouseDragged(x, y, button, dx, dy);
    }

    @Override public boolean mouseReleased(double x, double y, int button) {
        dragging = false;
        return super.mouseReleased(x, y, button);
    }

    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (vertical == 0) return super.mouseScrolled(x, y, horizontal, vertical);
        if (tab == 0) {
            if (maxScroll() == 0) return super.mouseScrolled(x, y, horizontal, vertical);
            scroll -= (int) Math.round(vertical * 3 * LINE);
            refresh();
            return true;
        }
        if (GuiKit.over((int) x, (int) y, previewX, previewY, previewW, previewH)) {
            zoom = Mth.clamp((float) (zoom * Math.pow(1.12, vertical)), .5f, 2.5f);
            return true;
        }
        listScroll -= (int) Math.round(vertical * 3 * PITCH);
        refresh();
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

    private static String number(float value) { return String.format(Locale.ROOT, "%.2f", value); }

    /** The clip clock as the slider's 0..1 position. */
    private static double position() {
        float duration = ViewerStage.duration();
        return duration <= 0 ? 0 : ViewerStage.seconds / duration;
    }

    private static String shortCommit(String commit) { return commit == null ? "—" : commit.substring(0, Math.min(9, commit.length())); }

    /** Scrub bar over the clip; dragging it pauses the clock at the dragged time. */
    private static final class Scrub extends AbstractSliderButton {
        Scrub(int x, int y, int width, int height, double value) {
            super(x, y, width, height, Component.empty(), value);
        }

        /** Follows the clock without being mistaken for the player dragging it. */
        void show(double at) { value = at; }

        @Override protected void updateMessage() {}

        @Override protected void applyValue() { ViewerStage.scrub((float) (value * ViewerStage.duration())); }
    }
}
