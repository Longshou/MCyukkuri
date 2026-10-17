package io.github.longshou.mcyukkuri.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.pet.social.BadgeArcPolicy.Arc;
import io.github.longshou.mcyukkuri.pet.social.BadgeSocial;
import java.util.Arrays;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;

/** Admin test entries for the badge social system: /yukkuri badgearc budget | reset | ledger &lt;who&gt; | force &lt;observer&gt; &lt;subject&gt; &lt;arc&gt;. */
public final class BadgeSocialCommands {
    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("badgearc")
                .then(Commands.literal("budget").executes(context -> budget(context.getSource())))
                .then(Commands.literal("reset").executes(context -> reset(context.getSource())))
                .then(Commands.literal("ledger").then(Commands.argument("who", EntityArgument.entity()).executes(BadgeSocialCommands::ledger)))
                .then(Commands.literal("force")
                        .then(Commands.argument("observer", EntityArgument.entity())
                                .then(Commands.argument("subject", EntityArgument.entity())
                                        .then(Commands.argument("arc", StringArgumentType.word())
                                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(Arrays.stream(Arc.values()).map(arc -> arc.id), builder))
                                                .executes(BadgeSocialCommands::force)))));
    }

    private static String arcs() { return String.join(", ", Arrays.stream(Arc.values()).map(arc -> arc.id).toList()); }

    private static int budget(CommandSourceStack source) {
        var lines = BadgeSocial.status(source.getLevel(), source.getPlayer());
        for (var line : lines) source.sendSuccess(() -> Component.translatable("commands.mcyukkuri.badgearc.line", line), false);
        return lines.size();
    }

    private static int reset(CommandSourceStack source) {
        BadgeSocial.reset();
        source.sendSuccess(() -> Component.translatable("commands.mcyukkuri.badgearc.reset"), true);
        return 1;
    }

    private static int ledger(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        var source = context.getSource();
        if (!(EntityArgument.getEntity(context, "who") instanceof YukkuriEntity who)) {
            source.sendFailure(Component.translatable("commands.mcyukkuri.badgearc.not_yukkuri"));
            return 0;
        }
        long now = source.getLevel().getGameTime();
        var entries = who.pet().badgeLedger().view(now);
        source.sendSuccess(() -> Component.translatable("commands.mcyukkuri.badgearc.ledger", who.getDisplayName(), entries.size()), false);
        for (var e : entries) {
            String subject = source.getLevel().getEntity(e.subject) instanceof YukkuriEntity y ? y.getDisplayName().getString() : e.subject.toString();
            source.sendSuccess(() -> Component.translatable("commands.mcyukkuri.badgearc.entry", subject, e.seenCount(), e.lastGrade(), e.impression(), e.flags(),
                    e.lastArc().isEmpty() ? "-" : e.lastArc()), false);
        }
        return entries.size();
    }

    private static int force(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        var source = context.getSource();
        String id = StringArgumentType.getString(context, "arc");
        Arc arc = Arc.byId(id);
        if (arc == null) {
            source.sendFailure(Component.translatable("commands.mcyukkuri.badgearc.unknown", id, arcs()));
            return 0;
        }
        if (!(EntityArgument.getEntity(context, "observer") instanceof YukkuriEntity observer)
                || !(EntityArgument.getEntity(context, "subject") instanceof YukkuriEntity subject)) {
            source.sendFailure(Component.translatable("commands.mcyukkuri.badgearc.not_yukkuri"));
            return 0;
        }
        String why = BadgeSocial.force(source.getLevel(), arc, observer, subject, source.getPlayer());
        if (why != null) {
            source.sendFailure(Component.translatable("commands.mcyukkuri.badgearc.failed", arc.id, why));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("commands.mcyukkuri.badgearc.started", arc.id, observer.getDisplayName(), subject.getDisplayName()), true);
        return 1;
    }

    private BadgeSocialCommands() {}
}
