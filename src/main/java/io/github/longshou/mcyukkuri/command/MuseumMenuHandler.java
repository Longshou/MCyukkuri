package io.github.longshou.mcyukkuri.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.github.longshou.mcyukkuri.network.MuseumMenuPayload;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.status.TraceRecorder;
import java.util.Set;
import java.util.regex.Pattern;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/** The menu can request only functions exposed by the museum pack. */
public final class MuseumMenuHandler {
    private static final String SCENE = "mcyukkuri.museumScene", FIELD = "mcyukkuri.museumField";
    private static final Pattern ID = Pattern.compile("[a-z0-9_]+");
    private static final Pattern CLEAR = Pattern.compile("field/[a-z0-9_]+/clear");
    private static final Set<String> UTIL = Set.of("go/lobby", "util/clear_all", "util/lead",
            "util/speed_1x", "util/speed_3x", "util/speed_5x", "util/speed_10x", "util/speed_20x",
            "util/sprint_1200", "util/sprint_6000", "util/sprint_24000",
            "util/time_dawn", "util/time_noon", "util/time_dusk", "util/time_night",
            "util/weather_clear", "util/weather_rain", "util/weather_thunder",
            "util/observe_on", "util/observe_off", "util/spectator", "util/creative");
    private static final Set<String> EXTRA = Set.of("extra/house_activity", "extra/house_sleepover",
            "extra/shop_visitor", "extra/shop_member", "extra/shop_patron", "village/next",
            "village/select_plains", "village/select_desert", "village/select_savanna",
            "village/select_snowy", "village/select_taiga");

    public static void handle(ServerPlayer player, MuseumMenuPayload request) {
        var server = player.getServer();
        var functions = server.getFunctions();
        if (!player.hasPermissions(2) || functions.get(id("menu/ready")).isEmpty()) {
            if (request.action() != MuseumMenuPayload.Action.READ_STATE)
                player.displayClientMessage(Component.translatable("viewer.mcyukkuri.todo.world"), false);
            return;
        }
        String target = request.target();
        if (request.action() == MuseumMenuPayload.Action.READ_STATE) {
            var data = player.getPersistentData();
            PacketDistributor.sendToPlayer(player, new MuseumMenuPayload.State(data.getString(SCENE), data.getString(FIELD)));
            return;
        }
        String path = switch (request.action()) {
            case JUMP_SCENE -> jump(target);
            case RERUN_SCENE -> jump(player.getPersistentData().getString(SCENE));
            case RUN_UTIL -> UTIL.contains(target) ? target : CLEAR.matcher(target).matches() ? "menu/" + target : null;
            case RUN_EXTRA -> EXTRA.contains(target) ? target : null;
            case READ_STATE -> throw new IllegalStateException();
        };
        if (path == null || functions.get(id(path)).isEmpty()
                || path.startsWith("jump/") && functions.get(id("start/" + path.substring(5))).isEmpty()) {
            player.displayClientMessage(Component.translatable("viewer.mcyukkuri.todo.world"), false);
            return;
        }
        var source = player.createCommandSourceStack().withLevel(server.overworld());
        GameplayLog.world(source.getLevel(), "museum", "player={} action={} function={}", player.getUUID(), request.action(), path);
        server.getCommands().performPrefixedCommand(source, "function " + id(path));
    }

    private static String jump(String scene) {
        return ID.matcher(scene).matches() ? "jump/" + scene : null;
    }

    private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath("mcylab", path); }

    static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("museum").then(Commands.literal("menu_seed")
                .then(Commands.argument("scene", StringArgumentType.word())
                        .then(Commands.argument("field", StringArgumentType.word()).executes(context -> {
                            var source = context.getSource();
                            var player = source.getPlayerOrException();
                            TraceRecorder.setRunSeed(source.getLevel(), source.getLevel().getRandom().nextLong());
                            var data = player.getPersistentData();
                            data.putString(SCENE, StringArgumentType.getString(context, "scene"));
                            data.putString(FIELD, StringArgumentType.getString(context, "field"));
                            return 1;
                        }))));
    }

    private MuseumMenuHandler() {}
}
