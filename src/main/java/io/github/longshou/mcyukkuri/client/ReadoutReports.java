package io.github.longshou.mcyukkuri.client;

import com.google.gson.JsonParser;
import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.network.ReadoutPayload;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;

/** One bounded in-flight report; no book item, menu, entity polling or gameplay progress. */
public final class ReadoutReports {
    private static final int TEXT_WIDTH = 114, LINES_PER_PAGE = 128 / 9;
    private static final long ASSEMBLY_TIMEOUT_NANOS = 30_000_000_000L;
    private static Assembly pending;

    private static final class Assembly {
        final UUID id;
        final int count;
        final WeakReference<Connection> connection;
        final long started = System.nanoTime();
        final StringBuilder json = new StringBuilder();
        int next;
        Assembly(ReadoutPayload payload, Connection connection) {
            id = payload.reportId(); count = payload.count(); this.connection = new WeakReference<>(connection);
        }
    }

    public static void accept(ReadoutPayload payload, Connection connection) {
        var minecraft = Minecraft.getInstance();
        if (minecraft.getConnection() == null || minecraft.level == null || minecraft.player == null) {
            pending = null;
            return;
        }
        // A queued packet from a closed connection must not clear the new connection's partial report.
        if (minecraft.getConnection().getConnection() != connection) return;
        if (pending != null && (pending.connection.get() != connection
                || System.nanoTime() - pending.started > ASSEMBLY_TIMEOUT_NANOS)) pending = null;
        if (payload.index() == 0) pending = new Assembly(payload, connection);
        var report = pending;
        if (report == null || !report.id.equals(payload.reportId()) || report.count != payload.count()
                || report.next != payload.index()
                || report.json.length() > ReadoutPayload.MAX_REPORT_CHARS - payload.fragment().length()) {
            pending = null;
            failed();
            return;
        }
        report.json.append(payload.fragment());
        if (++report.next != report.count) return;
        pending = null;
        try {
            var lines = new ArrayList<FormattedText>();
            for (var value : JsonParser.parseString(report.json.toString()).getAsJsonArray()) {
                Component message = Component.Serializer.fromJson(value, minecraft.level.registryAccess());
                if (message == null) throw new IllegalArgumentException("Empty readout component");
                lines.addAll(minecraft.font.getSplitter().splitLines(message, TEXT_WIDTH, Style.EMPTY));
                lines.add(FormattedText.EMPTY);
            }
            var pages = new ArrayList<Component>();
            for (int start = 0; start < lines.size(); start += LINES_PER_PAGE) {
                var page = Component.empty();
                for (int i = start; i < Math.min(lines.size(), start + LINES_PER_PAGE); i++) {
                    if (i > start) page.append("\n");
                    // Keep the splitter's styled logical text, not a second approximation by character count.
                    lines.get(i).visit((style, text) -> {
                        page.append(Component.literal(text).setStyle(style.withClickEvent(null).withInsertion(null)));
                        return Optional.empty();
                    }, Style.EMPTY);
                }
                pages.add(page);
            }
            if (pages.isEmpty()) pages.add(Component.literal("本次命令没有读数。"));
            minecraft.setScreen(new BookViewScreen(new BookViewScreen.BookAccess(List.copyOf(pages))));
        } catch (RuntimeException exception) {
            MCYukkuri.LOGGER.warn("Could not display complete readout {}", report.id, exception);
            failed();
        }
    }

    private static void failed() {
        var player = Minecraft.getInstance().player;
        if (player != null) player.displayClientMessage(Component.literal("读数报告未完整接收或无法显示，请重新按读数按钮。"), false);
    }

    private ReadoutReports() {}
}
