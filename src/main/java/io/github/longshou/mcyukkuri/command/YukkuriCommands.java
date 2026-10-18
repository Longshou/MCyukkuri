package io.github.longshou.mcyukkuri.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
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
                .then(io.github.longshou.mcyukkuri.mind.Naming.command())
                .then(io.github.longshou.mcyukkuri.settlement.SeasonCommands.command())
                .then(HumanCommands.command())
                .then(ScenarioCommands.command())
                .then(ScenarioCommands.clear())
                .then(ScenarioCommands.observe())
                .then(ScenarioCommands.speed())
                .then(ScenarioCommands.relay())
                .then(io.github.longshou.mcyukkuri.subspecies.AquaDiag.command())
                .then(Commands.literal("say")
                        .then(Commands.argument("targets", EntityArgument.entities())
                                .then(Commands.argument("event", StringArgumentType.word())
                                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                                io.github.longshou.mcyukkuri.dialogue.NativeDialogue.events(), builder))
                                        .executes(context -> sayEvent(context, StringArgumentType.getString(context, "event"), -1))
                                        .then(Commands.argument("quantity", IntegerArgumentType.integer(0, 99))
                                                .executes(context -> sayEvent(context, StringArgumentType.getString(context, "event"),
                                                        IntegerArgumentType.getInteger(context, "quantity")))))))
                .then(Commands.literal("action")
                        .then(Commands.argument("targets", EntityArgument.entities())
                                .then(Commands.argument("action", StringArgumentType.word())
                                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                                Arrays.stream(YukkuriAction.values()).map(YukkuriAction::id), builder))
                                        .executes(YukkuriCommands::playAction)))));
    }

    /** {@code quantity >= 0} treats the event as a count base (count_food, ...) and applies the "1, 2, 3, lots" rule. */
    private static int sayEvent(CommandContext<CommandSourceStack> context, String event, int quantity) throws CommandSyntaxException {
        var events = io.github.longshou.mcyukkuri.dialogue.NativeDialogue.events();
        boolean counting = quantity >= 0;
        if (counting ? !events.contains(event + "_1") : !events.contains(event)) throw UNKNOWN_ACTION.create(event);
        int spoken = 0;
        for (Entity target : EntityArgument.getEntities(context, "targets"))
            if (target instanceof YukkuriEntity y && (counting ? y.speakCount(event, quantity) : y.speak(event))) {
                spoken++;
                String report = "say " + y.getName().getString() + " tier=" + y.speechTier() + " species=" + y.getVariant().dialogueSpecies()
                        + " key=" + y.getDialogueKey();
                context.getSource().sendSuccess(() -> Component.literal(report), false);
            }
        return spoken;
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
