package io.github.longshou.mcyukkuri.client;

import com.google.gson.Gson;
import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.network.MuseumMenuPayload;
import io.github.longshou.mcyukkuri.network.MuseumMenuPayload.Action;
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
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/** Museum viewer, enabled by the playground launcher. */
final class ViewerScreen extends Screen {
    private static final KeyMapping KEY = new KeyMapping("key.mcyukkuri.viewer", GLFW.GLFW_KEY_M, "key.categories.mcyukkuri");
    private static final String KEY_PREFIX = "viewer.mcyukkuri.";
    private static final int LINE = 12, BUTTON = 18, PITCH = 20, PAD = 8;
    private static final int PANEL = 520, BAR_WIDTH = 4;
    private static final int TODO = 0, SCENES = 1, UTIL = 2, CLIPS = 3;
    private static final int SCENE_ROW = 2 * PITCH;
    private static int tab, familyIndex;

    private final ViewerTodo todo = ViewerTodo.read();
    private final MuseumIndex museum = MuseumIndex.read();
    private final List<MuseumIndex.Field> fields = museum == null ? List.of() : museum.fields();
    private int fieldIndex;
    private MuseumMenuPayload.State activeScene = new MuseumMenuPayload.State("", "");
    private int museumTop, museumSplit, museumHeight, fieldHeight;
    private boolean fieldBarDrag;
    private ViewerCatalog catalog;
    private Map<String, String> clipNames;
    private List<String> forms;
    private List<FormattedCharSequence> summary;
    private List<Row> rows = List.of();
    private int left, right, listTop, listBottom, contentHeight;
    private int scroll, listScroll, listContent;
    private float yaw = 18, pitch = 12, zoom = 1;
    private boolean dragging;
    private boolean barDrag;
    private double scrollGrab;
    private int columnX, columnW, rowFormY, rowFamilyY, listTopY, listBottomY;
    private int previewX, previewY, previewW, previewH, controlY, sliderY, timeY;
    private Scrub scrub;

    private record Row(ViewerTodo.Item item, List<FormattedCharSequence> text) {
        int buttonOffset() { return 2 * PAD + LINE + text.size() * LINE; }
        int height() { return buttonOffset() + item.scenes().size() * PITCH + PAD; }
    }

    private record UtilityGroup(String title, Action action, List<String> keys) {}
    private static final List<UtilityGroup> UTILITIES = List.of(
            new UtilityGroup("speed", Action.RUN_UTIL, List.of("util/speed_1x", "util/speed_3x", "util/speed_5x", "util/speed_10x", "util/speed_20x",
                    "util/sprint_1200", "util/sprint_6000", "util/sprint_24000")),
            new UtilityGroup("environment", Action.RUN_UTIL, List.of("util/time_dawn", "util/time_noon", "util/time_dusk", "util/time_night",
                    "util/weather_clear", "util/weather_rain", "util/weather_thunder")),
            new UtilityGroup("observation", Action.RUN_UTIL, List.of("util/observe_on", "util/observe_off", "util/spectator", "util/creative")),
            new UtilityGroup("house", Action.RUN_EXTRA, List.of("extra/house_activity", "extra/house_sleepover")),
            new UtilityGroup("shop", Action.RUN_EXTRA, List.of("extra/shop_visitor", "extra/shop_member", "extra/shop_patron")),
            new UtilityGroup("village", Action.RUN_EXTRA, List.of("village/select_plains", "village/select_desert", "village/select_savanna",
                    "village/select_snowy", "village/select_taiga", "village/next")));

    private record Bar(int x, int top, int track, int max, int thumb) {
        static Bar of(int x, int top, int track, int max) {
            int thumb = max > 0 && track > 1
                    ? Math.min(track - 1, Math.max(PAD, track * track / (track + max))) : 0;
            return new Bar(x, top, track, max, thumb);
        }

        int travel() { return track - thumb; }
        double thumbOffset(int value) { return (double) travel() * value / max; }
        int thumbTop(int value) { return top + (int) thumbOffset(value); }
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
            if (mc.screen == null && mc.player != null) {
                mc.setScreen(new ViewerScreen());
                send(Action.READ_STATE, "");
            }
        }
    }

    static void receiveState(MuseumMenuPayload.State state) {
        if (Minecraft.getInstance().screen instanceof ViewerScreen viewer) {
            viewer.activeScene = state;
            if (tab == UTIL) viewer.refresh();
        }
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override protected void init() {
        fieldBarDrag = barDrag = dragging = false;
        catalog = ViewerCatalog.read();
        clipNames = new HashMap<>();
        for (var family : catalog.families()) for (var clip : family.clips()) clipNames.put(clip[0], clip[1]);
        forms = List.copyOf(catalog.models().keySet());
        ViewerStage.open(forms.get(0));
        familyIndex = Math.min(familyIndex, familyCount() - 1);
        left = Math.max(PAD, (width - PANEL) / 2);
        right = width - left;
        listBottom = height - 2 * PAD;
        museumTop = 3 * PAD + BUTTON;
        museumSplit = left + (right - left) * 2 / 5;
        fieldHeight = museum == null ? 0 : museum.groups().size() * LINE + fields.size() * PITCH;
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
                if (!item.watch().isEmpty()) {
                    detail.append("\n").append(Component.literal(watchPreview(item.watch(), textWidth)).withColor(GuiKit.SOFT & 0xffffff));
                }
                if (item.scenes().isEmpty()) detail.append("\n").append(tr("todo.no_scene").withColor(GuiKit.SOFT & 0xffffff));
                return new Row(item, font.split(detail, textWidth));
            }).toList();
        }
        summary = font.split(heading, right - left - 2 * PAD);
        listTop = 2 * PAD + BUTTON + 4 + summary.size() * LINE + PAD;
        contentHeight = rows.stream().mapToInt(row -> row.height() + 4).sum();
    }

    private String watchPreview(String watch, int width) {
        var lines = font.getSplitter().splitLines(watch, width, Style.EMPTY);
        if (lines.size() <= 3) return watch;
        var last = GuiKit.ellipsize(font, Component.literal(lines.get(2).getString() + "…"), width);
        return String.join("\n", lines.get(0).getString(), lines.get(1).getString(), last.getString());
    }

    private void refresh() {
        clearFocus();
        clearWidgets();
        buildTabs();
        switch (tab) {
            case TODO -> buildTodoRows();
            case SCENES -> buildScenes(null);
            case UTIL -> buildUtilities(null);
            default -> buildStage();
        }
    }

    private void buildTabs() {
        int gap = 3;
        int tabWidth = (right - left - 2 * PAD - 4 * gap) / 5;
        int x = left + PAD;
        String[] names = {"todo", "scenes", "util", "clips", "faces"};
        for (int i = 0; i < names.length; i++) {
            int index = i;
            var widget = addRenderableWidget(new GuiKit.Tab(x, 2 * PAD, tabWidth, BUTTON, tr("tab." + names[i]), GuiKit.ICON_STATUS,
                    b -> switchTab(index)));
            widget.active = tab != i;
            x += tabWidth + gap;
        }
    }

    private void switchTab(int index) {
        fieldBarDrag = barDrag = dragging = false;
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
                    addRenderableWidget(Button.builder(Component.literal(scene.label()), b -> dispatch(Action.JUMP_SCENE, scene.id()))
                            .bounds(left + 2 * PAD, buttonY, right - left - 5 * PAD, BUTTON)
                            .tooltip(Tooltip.create(Component.literal(scene.field()))).build(GuiKit.ActionButton::new));
                }
                buttonY += PITCH;
            }
            y += row.height() + 4;
        }
    }

    private void buildScenes(GuiGraphics graphics) {
        if (museum == null) {
            museumHeight = 0;
            museumText(graphics, tr("scenes.missing"), left + PAD, museumTop, right - left - 2 * PAD);
            return;
        }
        var selected = fields.get(fieldIndex);
        museumHeight = selected.scenes().size() * SCENE_ROW;
        int track = listBottom - museumTop;
        listScroll = Mth.clamp(listScroll, 0, Math.max(0, fieldHeight - track));
        scroll = Mth.clamp(scroll, 0, Math.max(0, museumHeight - track));
        int index = 0, y = museumTop - listScroll;
        int x = left + PAD, w = museumSplit - x - 2 * PAD;
        for (var group : museum.groups()) {
            museumText(graphics, Component.literal(group.title()), x, y, w);
            y += LINE;
            for (var field : group.fields()) {
                int next = index++;
                if (graphics == null && museumVisible(y, BUTTON)) {
                    var button = museumButton(Component.literal(field.title()), x, y, w, () -> selectField(next), null);
                    button.active = next != fieldIndex;
                }
                y += PITCH;
            }
        }
        x = museumSplit + PAD;
        w = right - x - 2 * PAD;
        if (selected.scenes().isEmpty()) museumText(graphics, tr("scenes.empty"), x, museumTop, w);
        y = museumTop - scroll;
        for (var scene : selected.scenes()) {
            var label = Component.literal(scene.label());
            if (!scene.tag().isEmpty()) label.append(" · " + scene.tag());
            if (graphics == null && museumVisible(y, BUTTON))
                museumButton(label, x, y, w, () -> dispatch(Action.JUMP_SCENE, scene.id()), Component.literal(scene.watch()));
            museumText(graphics, Component.literal(scene.watch()), x + 2, y + PITCH, w - 4);
            y += SCENE_ROW;
        }
    }

    private void selectField(int index) {
        fieldIndex = index;
        if (tab == SCENES) scroll = 0;
        refresh();
    }

    private void buildUtilities(GuiGraphics graphics) {
        int x = left + PAD, width = right - left - 3 * PAD;
        int cols = Math.max(2, width / 100);
        int cell = (width + 4) / cols;
        var activeField = fields.stream().filter(field -> field.id().equals(activeScene.field())).findFirst();
        var active = activeField.flatMap(field -> field.scenes().stream().filter(scene -> scene.id().equals(activeScene.scene())).findFirst());
        var label = active.map(scene -> scene.label() + " · " + activeField.orElseThrow().title()).orElse(activeScene.scene());
        boolean chooseField = activeField.isEmpty() && !fields.isEmpty();
        museumHeight = LINE + (chooseField && cols == 2 ? 2 : 1) * PITCH + Math.ceilDiv(3, cols) * PITCH
                + UTILITIES.stream().mapToInt(group -> PAD + LINE + Math.ceilDiv(group.keys().size(), cols) * PITCH).sum();
        scroll = Mth.clamp(scroll, 0, Math.max(0, museumHeight - (listBottom - museumTop)));
        int y = museumTop - scroll;
        museumText(graphics, label.isEmpty() ? tr("util.no_scene") : tr("util.current", label), x, y, width);
        y += LINE;
        if (graphics == null && museumVisible(y, BUTTON)) {
            museumButton(tr("util.rerun"), x, y, cell - 4, () -> dispatch(Action.RERUN_SCENE, ""), null)
                    .active = !activeScene.scene().isEmpty();
            if (!fields.isEmpty()) {
                var field = activeField.orElse(fields.get(fieldIndex));
                museumButton(tr("util.clear_field", field.title()), x + cell, y, cell - 4,
                        () -> dispatch(Action.RUN_UTIL, "field/" + field.id() + "/clear"), null);
            }
        }
        if (chooseField) {
            int fieldY = y + (cols == 2 ? PITCH : 0);
            if (graphics == null && museumVisible(fieldY, BUTTON))
                museumButton(tr("util.next_field"), x + (cols == 2 ? 0 : 2 * cell), fieldY,
                        cell - 4, () -> selectField((fieldIndex + 1) % fields.size()), null);
            y = fieldY;
        }
        y += PITCH;
        y = utilityButtons(graphics, List.of("util/clear_all", "util/lead", "go/lobby"), Action.RUN_UTIL, x, y, cols, cell);
        for (var group : UTILITIES) {
            y += PAD;
            museumText(graphics, tr("util.group." + group.title()), x, y, width);
            y = utilityButtons(graphics, group.keys(), group.action(), x, y + LINE, cols, cell);
        }
    }

    private int utilityButtons(GuiGraphics graphics, List<String> keys, Action action, int x, int y, int cols, int cell) {
        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            int buttonY = y + i / cols * PITCH;
            if (graphics == null && museumVisible(buttonY, BUTTON))
                museumButton(tr("util." + key.replace('/', '.')), x + i % cols * cell, buttonY, cell - 4,
                        () -> dispatch(action, key), null);
        }
        return y + Math.ceilDiv(keys.size(), cols) * PITCH;
    }

    private Button museumButton(Component label, int x, int y, int width, Runnable action, Component tooltip) {
        var shortLabel = GuiKit.ellipsize(font, label, width - 8);
        var button = Button.builder(shortLabel, b -> action.run()).bounds(x, y, width, BUTTON);
        if (tooltip != null) button.tooltip(Tooltip.create(Component.empty().append(label).append("\n").append(tooltip)));
        else if (shortLabel != label) button.tooltip(Tooltip.create(label));
        return addRenderableWidget(button.build(GuiKit.ActionButton::new));
    }

    private boolean museumVisible(int y, int size) { return y >= museumTop && y + size <= listBottom; }

    private void museumText(GuiGraphics graphics, Component label, int x, int y, int width) {
        if (graphics != null && museumVisible(y, LINE))
            graphics.drawString(font, GuiKit.ellipsize(font, label, width), x, y, GuiKit.SOFT, false);
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
        if (tab == CLIPS) {
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
        var ids = tab == CLIPS ? familyClips() : selectableFaces();
        int view = listBottomY - listTopY;
        listContent = ids.size() * PITCH;
        listScroll = Mth.clamp(listScroll, 0, Math.max(0, listContent - view));
        int y = listTopY - listScroll;
        for (var id : ids) {
            if (y >= listTopY && y + BUTTON <= listBottomY) {
                boolean selected = tab == CLIPS ? id.equals(ViewerStage.clip) : id.equals(ViewerStage.face);
                String name = tab == CLIPS ? clipNames.getOrDefault(id, id) : FaceLibrary.label(id);
                var button = addRenderableWidget(Button.builder(Component.literal(name), b -> {
                    if (tab == CLIPS) ViewerStage.setClip(id); else ViewerStage.setFace(id);
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

    private void dispatch(Action action, String target) {
        send(action, target);
        onClose();
    }

    private static void send(Action action, String target) {
        PacketDistributor.sendToServer(new MuseumMenuPayload(action, target));
    }

    // ------------------------------------------------------------------ rendering
    /** Background, panel and tab content, all before {@link Screen#render} draws the widgets on top. */
    @Override public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partial) {
        super.renderBackground(graphics, mouseX, mouseY, partial);
        GuiKit.panel(graphics, left, PAD, right - left, height - 2 * PAD);
        switch (tab) {
            case TODO -> renderTodo(graphics);
            case SCENES -> {
                buildScenes(graphics);
                renderBar(graphics, fieldBar(), listScroll);
                renderBar(graphics, bar(), scroll);
            }
            case UTIL -> {
                buildUtilities(graphics);
                renderBar(graphics, bar(), scroll);
            }
            default -> renderStage(graphics);
        }
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
        renderBar(graphics, bar(), scroll);
    }

    private void renderStage(GuiGraphics graphics) {
        ViewerStage.advance();
        if (scrub != null) scrub.show(position());
        centered(graphics, catalog.models().get(ViewerStage.form), columnX, columnW, rowFormY + 5, GuiKit.INK);
        centered(graphics, familyIndex < catalog.families().size() ? catalog.families().get(familyIndex).title() : tr("family.other").getString(),
                columnX, columnW, rowFamilyY + 5, GuiKit.SOFT);
        renderBar(graphics, bar(), listScroll);
        GuiKit.card(graphics, previewX, previewY, previewW, previewH, false);
        ViewerStage.draw(graphics, previewX + 2, previewY + 2, previewW - 4, previewH - 4, yaw, pitch, zoom);
        graphics.drawString(font, GuiKit.ellipsize(font, tr("orbit"), previewW - 2 * PAD), previewX + PAD, previewY + previewH - LINE,
                GuiKit.SOFT, false);
        graphics.drawString(font, tr("clip.time", ViewerStage.clip, number(ViewerStage.seconds), number(ViewerStage.duration())),
                previewX, timeY, GuiKit.INK, false);
    }

    private void renderBar(GuiGraphics graphics, Bar bar, int value) {
        if (bar.thumb() == 0) return;
        graphics.fill(bar.x(), bar.top(), bar.x() + BAR_WIDTH, bar.top() + bar.track(), 0xffdfd4bd);
        int top = bar.thumbTop(value);
        graphics.fill(bar.x(), top, bar.x() + BAR_WIDTH, top + bar.thumb(), 0xff9b815e);
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
        if (button == 0) {
            boolean field = tab == SCENES && x < museumSplit;
            Bar bar = field ? fieldBar() : bar();
            if (bar.thumb() > 0 && GuiKit.over((int) x, (int) y, bar.x(), bar.top(), BAR_WIDTH, bar.track())) {
                int value = field || tab >= CLIPS ? listScroll : scroll;
                double offset = y - bar.thumbTop(value);
                clearFocus();
                barDrag = true;
                fieldBarDrag = field;
                if (offset >= 0 && offset < bar.thumb()) {
                    scrollGrab = y - bar.top() - bar.thumbOffset(value);
                } else {
                    scrollGrab = bar.thumb() / 2.0;
                    dragBar(bar, y);
                }
                return true;
            }
            if (tab >= CLIPS && GuiKit.over((int) x, (int) y, previewX, previewY, previewW, previewH)) {
                dragging = true;
                return true;
            }
        }
        return false;
    }

    @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (button == 0 && barDrag) {
            dragBar(fieldBarDrag ? fieldBar() : bar(), y);
            return true;
        }
        if (button == 0 && dragging) {
            yaw = Mth.wrapDegrees(yaw + (float) dx * 2);
            pitch = Mth.wrapDegrees(pitch + (float) dy * 2);
            return true;
        }
        return super.mouseDragged(x, y, button, dx, dy);
    }

    @Override public boolean mouseReleased(double x, double y, int button) {
        if (button == 0) {
            dragging = false;
            if (barDrag) {
                fieldBarDrag = barDrag = false;
                return true;
            }
        }
        return super.mouseReleased(x, y, button);
    }

    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (vertical == 0) return super.mouseScrolled(x, y, horizontal, vertical);
        if (tab < CLIPS) {
            boolean field = tab == SCENES && x < museumSplit;
            if ((field ? fieldBar() : bar()).thumb() == 0) return super.mouseScrolled(x, y, horizontal, vertical);
            int delta = (int) Math.round(vertical * 3 * LINE);
            if (field) listScroll -= delta; else scroll -= delta;
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

    private Bar bar() {
        if (tab == TODO) {
            return Bar.of(right - PAD - BAR_WIDTH, listTop, listBottom - listTop, maxScroll());
        }
        if (tab < CLIPS) return museumBar(right - PAD - BAR_WIDTH, museumHeight);
        int track = listBottomY - listTopY;
        return Bar.of(columnX + columnW - 2 * BAR_WIDTH - 2, listTopY, track, listContent - track);
    }

    private Bar fieldBar() { return museumBar(museumSplit - PAD - BAR_WIDTH, fieldHeight); }

    private Bar museumBar(int x, int content) {
        int track = listBottom - museumTop;
        return Bar.of(x, museumTop, track, content - track);
    }

    /** Moves the bar's list so the grabbed point of the thumb follows the cursor, then rebuilds the list. */
    private void dragBar(Bar bar, double y) {
        double at = Mth.clamp(y - scrollGrab - bar.top(), 0, bar.travel());
        int value = (int) Math.round(at * bar.max() / bar.travel());
        if (fieldBarDrag || tab >= CLIPS) listScroll = value; else scroll = value;
        refresh();
    }

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
