package io.github.longshou.mcyukkuri.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import java.util.Arrays;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** Operator-only animation demonstrations. Ordinary entity behaviour requires no commands. */
public final class YukkuriCommands {
    private static final DynamicCommandExceptionType UNKNOWN_ACTION = new DynamicCommandExceptionType(
            id -> Component.translatable("commands.mcyukkuri.action.unknown", id));
    private static final SimpleCommandExceptionType NO_YUKKURI = new SimpleCommandExceptionType(
            Component.translatable("commands.mcyukkuri.action.no_targets"));

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("yukkuri")
                .requires(source -> source.hasPermission(2))
                .then(io.github.longshou.mcyukkuri.settlement.SettlementCommands.command())
                .then(io.github.longshou.mcyukkuri.event.HomeClaimCommands.command())
                .then(BadgeSocialCommands.command())
                .then(HouseholdCommands.command())
                .then(io.github.longshou.mcyukkuri.status.StatusCommands.command())
                .then(io.github.longshou.mcyukkuri.settlement.SeasonCommands.command())
                .then(ScenarioCommands.command())
                .then(ScenarioCommands.clear())
                .then(ScenarioCommands.observe())
                .then(Commands.literal("say")
                        .then(Commands.argument("targets", EntityArgument.entities())
                                .then(Commands.argument("event", StringArgumentType.word())
                                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                                io.github.longshou.mcyukkuri.dialogue.NativeDialogue.events(), builder))
                                        .executes(context -> {
                                            String speechEvent = StringArgumentType.getString(context, "event");
                                            if (!io.github.longshou.mcyukkuri.dialogue.NativeDialogue.events().contains(speechEvent))
                                                throw UNKNOWN_ACTION.create(speechEvent);
                                            int spoken = 0;
                                            for (Entity target : EntityArgument.getEntities(context, "targets"))
                                                if (target instanceof YukkuriEntity y && y.speak(speechEvent)) spoken++;
                                            return spoken;
                                        }))))
                .then(Commands.literal("action")
                        .then(Commands.argument("targets", EntityArgument.entities())
                                .then(Commands.argument("action", StringArgumentType.word())
                                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                                Arrays.stream(YukkuriAction.values()).map(YukkuriAction::id), builder))
                                        .executes(YukkuriCommands::playAction)))));
    }

    private static int playAction(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        String id = StringArgumentType.getString(context, "action");
        if (YukkuriAction.byId(id).isEmpty()) throw UNKNOWN_ACTION.create(id);
        int changed = 0;
        for (Entity entity : EntityArgument.getEntities(context, "targets")) {
            if (entity instanceof YukkuriEntity yukkuri && yukkuri.playAction(id)) changed++;
        }
        if (changed == 0) throw NO_YUKKURI.create();
        int count = changed;
        context.getSource().sendSuccess(() -> Component.translatable("commands.mcyukkuri.action.success", id, count), true);
        return count;
    }

    private YukkuriCommands() {}
}
