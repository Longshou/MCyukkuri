package io.github.longshou.mcyukkuri.client.capture;

import com.google.gson.Gson;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSource;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.ScoreHolder;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.lwjgl.glfw.GLFW;

/** Opt-in render-target recorder. Scene actions still run through the museum's normal start functions. */
public final class MuseumCapture {
    private record Scene(String id, String field, int[] ticks, int[][] chunks, String view, String[] reads) {}
    private record Frame(int requested, long tick, double seconds, String file, boolean visible, boolean focused) {}
    private static final Gson JSON = new Gson();
    private static final Path ROOT = Path.of(System.getProperty("mcyukkuri.capture.output"));
    private static final Scene[] PLAN = readPlan();
    private static final List<Frame> FRAMES = new ArrayList<>();
    private static final List<CompletableFuture<Void>> WRITES = new ArrayList<>();
    private static CompletableFuture<Long> start;
    private static Scene current;
    private static int nextScene;
    private static int nextFrame;
    private static long began;
    private static long nextReadyCheck;

    private MuseumCapture() {}

    private static Scene[] readPlan() {
        try {
            return JSON.fromJson(Files.readString(ROOT.resolve("plan.json")), Scene[].class);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    public static void commands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("museumcapture")
                .then(Commands.literal("shot").then(Commands.argument("delay", IntegerArgumentType.integer(1))
                        .executes(ctx -> manual(new int[] {IntegerArgumentType.getInteger(ctx, "delay")}))))
                .then(Commands.literal("sequence")
                        .then(Commands.argument("seconds", IntegerArgumentType.integer(1, 30))
                                .then(Commands.argument("fps", IntegerArgumentType.integer(1, 20))
                                        .executes(ctx -> {
                                            int fps = IntegerArgumentType.getInteger(ctx, "fps");
                                            return manual(IntStream.range(0, IntegerArgumentType.getInteger(ctx, "seconds") * fps)
                                                    .map(i -> 1 + Math.round(i * 20f / fps)).toArray());
                                        })))));
    }

    private static int manual(int[] ticks) {
        if (current != null) throw new IllegalStateException("A capture is already running");
        Minecraft mc = Minecraft.getInstance();
        begin(new Scene("manual-" + System.currentTimeMillis(), "", ticks, new int[0][], "", new String[0]));
        start = CompletableFuture.completedFuture(mc.level.getGameTime());
        return 1;
    }

    private static void begin(Scene scene) {
        current = scene;
        nextFrame = 0;
        FRAMES.clear();
        WRITES.clear();
        began = System.nanoTime();
        try {
            Files.createDirectory(ROOT.resolve(scene.id));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static void readScene(Scene completed, Minecraft mc) {
        var server = mc.getSingleplayerServer();
        var observer = mc.player.getUUID();
        // Direct commands retain their output; scheduled functions suppress it. Never open the readout GUI here.
        server.submit(() -> {
            var source = server.getPlayerList().getPlayer(observer).createCommandSourceStack().withPermission(4)
                    .withSource(new CommandSource() {
                        public void sendSystemMessage(Component message) {
                            LogUtils.getLogger().info("[capture-read] scene={} observer={} tick={} {}",
                                    completed.id, observer, server.overworld().getGameTime(), message.getString());
                        }
                        public boolean acceptsSuccess() { return true; }
                        public boolean acceptsFailure() { return true; }
                        public boolean shouldInformAdmins() { return false; }
                    });
            for (String command : completed.reads) server.getCommands().performPrefixedCommand(source, command);
        }).join();
    }

    public static void frame(RenderFrameEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        // Loading screens and remote worlds have no integrated museum to drive.
        if (mc.level == null || mc.player == null || mc.getSingleplayerServer() == null
                || mc.screen != null || mc.getOverlay() != null) return;
        if (current == null) {
            if (System.nanoTime() < nextReadyCheck) return;
            if (start == null) {
                var server = mc.getSingleplayerServer();
                start = server.submit(() -> {
                    var board = server.getScoreboard();
                    var objective = board.getObjective("museum");
                    var ready = objective == null ? null : board.getPlayerScoreInfo(ScoreHolder.forNameOnly("#ready"), objective);
                    return ready != null && ready.value() == 1 ? 1L : 0L;
                });
                return;
            }
            if (!start.isDone()) return;
            long ready = start.join();
            start = null;
            if (ready == 0) {
                nextReadyCheck = System.nanoTime() + 1_000_000_000L;
                return;
            }
            if (nextScene == PLAN.length) return;
            begin(PLAN[nextScene++]);
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            Scene scene = current;
            start = server.submit(() -> {
                var source = server.getPlayerList().getPlayer(uuid).createCommandSourceStack().withPermission(4);
                if (nextScene > 1) server.getCommands().performPrefixedCommand(source,
                        "function mcylab:field/" + PLAN[nextScene - 2].field + "/clear");
                // A human reaches the field before pressing its button; load that same terrain first.
                for (int[] chunk : scene.chunks) server.overworld().getChunk(chunk[0] >> 4, chunk[1] >> 4);
                server.getCommands().performPrefixedCommand(source, scene.view);
                // Give the client two seconds to receive/render the terrain before the button starts the scene.
                server.getCommands().performPrefixedCommand(source, "schedule function mcylab:capture/" + scene.id + " 40t");
                return server.overworld().getGameTime() + 40;
            });
            return;
        }
        if (!start.isDone()) return;
        long tick = mc.level.getGameTime() - start.join();
        if (nextFrame < current.ticks.length && tick >= current.ticks[nextFrame]) {
            int requested = current.ticks[nextFrame++];
            // Never fabricate missed frames by saving the same render repeatedly.
            while (nextFrame < current.ticks.length && current.ticks[nextFrame] <= tick) nextFrame++;
            String file = String.format("tick-%06d-at-%06d.png", requested, tick);
            long window = mc.getWindow().getWindow();
            FRAMES.add(new Frame(requested, tick, (System.nanoTime() - began) / 1e9, file,
                    GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_VISIBLE) != 0,
                    GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_FOCUSED) != 0));
            Path output = ROOT.resolve(current.id).resolve(file);
            NativeImage pixels = Screenshot.takeScreenshot(mc.getMainRenderTarget());
            if (nextFrame == current.ticks.length) readScene(current, mc);
            WRITES.add(CompletableFuture.runAsync(() -> {
                try (pixels) {
                    pixels.writeToFile(output);
                } catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                }
            }, Util.ioPool()));
        }
        if (nextFrame != current.ticks.length || WRITES.stream().anyMatch(write -> !write.isDone())) return;
        WRITES.forEach(CompletableFuture::join);
        try {
            Files.writeString(ROOT.resolve(current.id).resolve("frames.json"), JSON.toJson(FRAMES));
            LogUtils.getLogger().info("[capture] scene={} frames={} seconds={}", current.id, FRAMES.size(), (System.nanoTime() - began) / 1e9);
            current = null;
            start = null;
            if (PLAN.length > 0 && nextScene == PLAN.length) {
                Files.writeString(ROOT.resolve("complete.json"), "{\"scenes\":" + nextScene + "}");
                mc.stop();
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
