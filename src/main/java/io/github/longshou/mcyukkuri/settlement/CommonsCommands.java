package io.github.longshou.mcyukkuri.settlement;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

/**
 * Operator report and test doors for package 12 ({@code /yukkuri settlement rules ...}). None of these gives a
 * community an order: they only skip the waiting (time of day, daily limit) of what the leader does on its own, or read
 * the record. Targets the ruled community nearest to the command position.
 */
public final class CommonsCommands {
    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("rules")
                .executes(context -> report(context.getSource()))
                .then(Commands.literal("hunt").executes(context -> hunt(context.getSource())))
                .then(Commands.literal("ration").executes(context -> ration(context.getSource())))
                .then(Commands.literal("judge").executes(context -> judge(context.getSource())))
                .then(Commands.literal("famine")
                        .then(Commands.argument("level", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(new String[]{"auto", "0", "1", "2"}, b))
                                .executes(context -> famine(context.getSource(), StringArgumentType.getString(context, "level")))))
                .then(Commands.literal("birthlimit")
                        .then(Commands.argument("young", IntegerArgumentType.integer(1, 8))
                                .executes(context -> birthLimit(context.getSource(), IntegerArgumentType.getInteger(context, "young")))))
                .then(Commands.literal("offend")
                        .then(Commands.argument("offender", EntityArgument.entity())
                                .then(Commands.argument("offence", StringArgumentType.word())
                                        .suggests((c, b) -> {
                                            var ids = new String[CommunityRules.Offence.values().length];
                                            for (int i = 0; i < ids.length; i++) ids[i] = CommunityRules.Offence.values()[i].id;
                                            return SharedSuggestionProvider.suggest(ids, b);
                                        })
                                        .executes(context -> offend(context.getSource(), EntityArgument.getEntity(context, "offender"),
                                                StringArgumentType.getString(context, "offence"))))));
    }

    private static SettlementData.Group nearest(CommandSourceStack source) {
        var data = SettlementData.get(source.getLevel());
        BlockPos here = BlockPos.containing(source.getPosition());
        SettlementData.Group nearest = null;
        for (var group : data.groups()) {
            if (!CommunityRules.ruled(group)) continue;
            if (nearest == null || group.center.distSqr(here) < nearest.center.distSqr(here)) nearest = group;
        }
        return nearest;
    }
    private static String id(UUID id) { return id == null ? "-" : id.toString().substring(0, 8); }
    private static int fail(CommandSourceStack source, String text) { source.sendFailure(Component.literal(text)); return 0; }
    private static int ok(CommandSourceStack source, String text) { source.sendSuccess(() -> Component.literal(text), false); return 1; }

    /** Everything a check needs in one reply: rules, store, each household's share, the hunt, the record. */
    private static int report(CommandSourceStack source) {
        var level = source.getLevel();
        var group = nearest(source);
        if (group == null) return fail(source, "no community with rules nearby");
        var c = group.commons;
        long now = level.getGameTime();
        var out = new StringBuilder();
        out.append("community ").append(id(group.id)).append(" center ").append(group.center.toShortString())
                .append(" leader ").append(id(SettlementData.get(level).activeLeader(group))).append(" rules ").append(c.rulesText())
                .append(" birthLimit ").append(c.birthLimit).append('\n');
        out.append("store ").append(CommonStore.stock(level, group)).append('/').append(CommonStore.capacity(level, group))
                .append(" piles ").append(CommonStore.piles(level, group).size()).append(" famine ").append(c.famine)
                .append(c.famineOverride >= 0 ? " (forced " + c.famineOverride + ")" : "").append(" organised ").append(CommonHunt.organised(level, group)).append('\n');
        out.append("ration day ").append(c.rationDay).append(" given ").append(c.rationGiven).append('/').append(c.rationWanted)
                .append(" stock_then ").append(c.rationStock).append('\n');
        c.rations.forEach((key, r) -> out.append("  home ").append(BlockPos.of(key).toShortString()).append(" mouths ").append(r.mouths)
                .append(" weight ").append(r.weight).append(" allotted ").append(r.allotted).append(" left ").append(r.left)
                .append(" own ").append(r.own).append(' ').append(r.note).append('\n'));
        var h = c.hunt;
        out.append("hunt ").append(h.phase.name().toLowerCase(java.util.Locale.ROOT)).append(" chief ").append(id(h.chief))
                .append(" dest ").append(h.dest == null ? "-" : h.dest.toShortString()).append(" party ").append(h.party.size())
                .append(" done ").append(h.done.size()).append(" delivered ").append(h.delivered).append(" today ").append(h.today).append('\n');
        for (CompoundTag record : c.hunts) out.append("  hunt day ").append(record.getLong("Day")).append(" party ").append(record.getInt("Party"))
                .append(" defied ").append(record.getInt("Defied")).append(" delivered ").append(record.getInt("Delivered"))
                .append(" radius ").append(record.getInt("Radius")).append(" dest ").append(record.getString("Dest"))
                .append(" outcome ").append(record.getString("Outcome")).append(" ticks ").append(record.getLong("Ended") - record.getLong("Called")).append('\n');
        out.append("pending ").append(c.pending.size());
        for (var p : c.pending) out.append(" [").append(p.offence).append(' ').append(id(p.offender)).append(" age ").append(now - p.time).append(']');
        out.append('\n');
        c.standing.forEach((who, s) -> {
            if (s.offences == 0 && s.rationCutUntil <= now && s.banishedUntil <= now && s.intrusions == 0) return;
            out.append("  standing ").append(id(who)).append(" offences ").append(s.offences)
                    .append(s.rationCutUntil > now ? " ration_cut " + (s.rationCutUntil - now) : "")
                    .append(s.banishedUntil > now ? " banished " + (s.banishedUntil - now) : "")
                    .append(s.intrusions > 0 ? " intrusions " + s.intrusions : "").append('\n');
        });
        int skip = Math.max(0, c.log.size() - 20), i = 0;
        for (CompoundTag e : c.log) {
            if (i++ < skip) continue;
            out.append("  log ").append(e.getLong("T")).append(' ').append(e.getString("K")).append(' ')
                    .append(e.hasUUID("W") ? id(e.getUUID("W")) : "-").append(' ').append(e.getString("D")).append('\n');
        }
        return ok(source, out.toString());
    }

    private static YukkuriEntity leader(ServerLevel level, SettlementData.Group group) { return CommunityRules.leader(level, group); }

    private static int hunt(CommandSourceStack source) {
        var level = source.getLevel();
        var group = nearest(source);
        if (group == null) return fail(source, "no community with rules nearby");
        var leader = leader(level, group);
        if (leader == null) return fail(source, "the leader is not loaded");
        String why = CommonHunt.call(leader, group, true);
        if (why != null) return fail(source, "hunt not called: " + why);
        return ok(source, "hunt called: party " + group.commons.hunt.party.size() + " dest " + group.commons.hunt.dest.toShortString());
    }

    private static int ration(CommandSourceStack source) {
        var level = source.getLevel();
        var group = nearest(source);
        if (group == null) return fail(source, "no community with rules nearby");
        return ok(source, "rations shared: " + Rations.distribute(level, group, leader(level, group)));
    }

    /** Judge every pending offence now, whether or not the offender stands before the leader. */
    private static int judge(CommandSourceStack source) {
        var level = source.getLevel();
        var group = nearest(source);
        if (group == null) return fail(source, "no community with rules nearby");
        var leader = leader(level, group);
        var c = group.commons;
        var out = new StringBuilder();
        int n = 0;
        for (var p : java.util.List.copyOf(c.pending)) {
            var offence = CommunityRules.Offence.byId(p.offence);
            if (offence == null || !(level.getEntity(p.offender) instanceof YukkuriEntity offender) || !offender.isAlive()) continue;
            c.pending.remove(p);
            boolean present = leader != null && offender.distanceToSqr(leader) <= CommunityRules.JUDGE_RANGE * CommunityRules.JUDGE_RANGE && leader.hasLineOfSight(offender);
            var punishment = CommunityRules.judge(level, group, leader, offender, offence, p.detail, present);
            out.append(id(p.offender)).append(' ').append(offence.id).append(" -> ").append(punishment.id()).append("; ");
            n++;
        }
        return n == 0 ? fail(source, "nothing to judge") : ok(source, out.toString());
    }

    private static int famine(CommandSourceStack source, String value) {
        var group = nearest(source);
        if (group == null) return fail(source, "no community with rules nearby");
        int level;
        if ("auto".equals(value)) level = -1;
        else try { level = Math.clamp(Integer.parseInt(value), 0, 2); } catch (NumberFormatException e) { return fail(source, "auto, 0, 1 or 2"); }
        group.commons.famineOverride = level;
        if (level >= 0) group.commons.famine = level;
        SettlementData.get(source.getLevel()).setDirty();
        return ok(source, "famine " + (level < 0 ? "judged from the store" : "forced to " + level) + " (applies from the next sharing)");
    }

    private static int birthLimit(CommandSourceStack source, int young) {
        var group = nearest(source);
        if (group == null) return fail(source, "no community with rules nearby");
        group.commons.birthLimit = young;
        SettlementData.get(source.getLevel()).setDirty();
        return ok(source, "birth limit " + young);
    }

    /** Test door: record an offence as if the nearest real witness had seen it (none: the leader learns of it anyway). */
    private static int offend(CommandSourceStack source, net.minecraft.world.entity.Entity target, String name) {
        if (!(target instanceof YukkuriEntity offender)) return fail(source, "not a yukkuri");
        var offence = CommunityRules.Offence.byId(name);
        if (offence == null) return fail(source, "unknown offence " + name);
        var group = offender.settlement().group();
        if (group == null) group = nearest(source);
        if (!CommunityRules.ruled(group)) return fail(source, "the offender's community has no rules");
        var witness = CommunityRules.witness(offender, group, CommunityRules.WITNESS_RANGE);
        if (!CommunityRules.report(offender, group, offence, witness, "command")) return fail(source, "not recorded (rule off, leader, or a duplicate)");
        return ok(source, "recorded " + offence.id + " by " + id(offender.getUUID()) + " witness " + (witness == null ? "-" : id(witness.getUUID())));
    }

    private CommonsCommands() {}
}
