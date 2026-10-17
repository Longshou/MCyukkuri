package io.github.longshou.mcyukkuri.command;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.settlement.SettlementOrders;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/** Museum setup and real-behaviour entry points; action outcomes remain owned by gameplay. */
public final class MuseumCommands {
    public static boolean actor(Entity entity) {
        return entity instanceof YukkuriEntity y && y.isAlive() && y.isNoAi() && y.getTags().contains("pm_actor");
    }

    /**
     * Museum buttons are command blocks (and scheduled functions): nobody sees their command output. Tell the players
     * standing near the exhibit instead, so a refused button always says why nothing happened.
     */
    static void tellNear(ServerLevel level, Vec3 at, String text, ChatFormatting colour) {
        var message = Component.literal(text).withStyle(colour);
        for (var player : level.players()) if (player.position().distanceToSqr(at) < 48.0 * 48.0) player.sendSystemMessage(message);
    }

    /** Failure for the command source and, when it is not a player who typed it, for the visitors nearby. */
    static void fail(CommandSourceStack source, Vec3 at, String text) {
        source.sendFailure(Component.literal(text));
        if (!(source.getEntity() instanceof ServerPlayer)) tellNear(source.getLevel(), at, text, ChatFormatting.RED);
    }

    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("museum").requires(s -> s.hasPermission(2))
                .then(settlementCommand())
                .then(aquaticCommand())
                .then(Commands.literal("pause").then(Commands.argument("actors", EntityArgument.entities()).executes(c -> {
                    int count = 0;
                    for (var entity : EntityArgument.getEntities(c, "actors"))
                        if (entity instanceof net.minecraft.world.entity.Mob mob && entity.getTags().contains("pm_actor")) {
                            MuseumWalkGoal.cancel(mob); count++;
                        }
                    return count;
                })))
                .then(Commands.literal("independence").then(Commands.argument("parent", EntityArgument.entity())
                        .then(Commands.argument("teen", EntityArgument.entity())
                                .then(Commands.argument("home", BlockPosArgument.blockPos()).executes(c -> {
                                    var parent = EntityArgument.getEntity(c, "parent"); var teen = EntityArgument.getEntity(c, "teen");
                                    boolean started = parent instanceof YukkuriEntity p && teen instanceof YukkuriEntity y
                                            && MuseumIndependenceSetup.prepare(p, y, BlockPosArgument.getLoadedBlockPos(c, "home"));
                                    if (!started) {
                                        fail(c.getSource(), parent.position(), "独立没有开始：成年和亚成两只都要在场并处于真实行为中，是亲子，亚成已能独立生活，"
                                                + "两个洞口要完好且没有别人占用，脚下要着地。请先按“重置本站”再试。");
                                        return 0;
                                    }
                                    return 1;
                                })))))
                .then(Commands.literal("live").then(Commands.argument("actors", EntityArgument.entities())
                        .then(Commands.argument("need", StringArgumentType.word()).executes(c -> {
                            int count = 0;
                            for (var entity : EntityArgument.getEntities(c, "actors"))
                                if (entity instanceof YukkuriEntity y) {
                                    if (MuseumLiveActors.prepare(y, StringArgumentType.getString(c, "need"))) count++;
                                    else fail(c.getSource(), y.position(), y.getName().getString() + " 现在不能开始真实行为：它可能已被收容、没有加载，"
                                            + "正在出生、接生或乘船，或者上一次的真实行为还没结束。请先按“重置本站”。");
                                }
                            return count;
                        }))))
                .then(Commands.literal("parent").then(Commands.argument("parent", EntityArgument.entity())
                        .then(Commands.argument("child", EntityArgument.entity())
                                .then(Commands.argument("firstToilet", com.mojang.brigadier.arguments.BoolArgumentType.bool()).executes(c -> {
                                    var parent = EntityArgument.getEntity(c, "parent"); var child = EntityArgument.getEntity(c, "child");
                                    boolean linked = parent instanceof YukkuriEntity p && child instanceof YukkuriEntity y
                                            && MuseumLiveActors.parent(p, y, com.mojang.brigadier.arguments.BoolArgumentType.getBool(c, "firstToilet"));
                                    if (!linked) {
                                        fail(c.getSource(), parent.position(), "亲子关系没能建立：双方都要处于真实行为中，亲油是成年、幼体未成年且还没有别的亲缘，"
                                                + "首便演示还要求幼体仍是新生。请先按“重置本站”再试。");
                                        return 0;
                                    }
                                    return 1;
                                })))))
                .then(Commands.literal("home").then(Commands.argument("actor", EntityArgument.entity())
                        .then(Commands.argument("home", BlockPosArgument.blockPos()).executes(c -> {
                            var entity = EntityArgument.getEntity(c, "actor");
                            if (!(entity instanceof YukkuriEntity y) || !y.getTags().contains("pm_live")) {
                                fail(c.getSource(), entity.position(), "指定窝位失败：这只演员还不在真实行为中。");
                                return 0;
                            }
                            var pos = BlockPosArgument.getLoadedBlockPos(c, "home");
                            boolean chosen;
                            if (y.level().getBlockEntity(pos) instanceof io.github.longshou.mcyukkuri.house.HouseNestBlockEntity nest) chosen = y.house().choose(nest);
                            else {
                                var shelter = io.github.longshou.mcyukkuri.settlement.ShelterBlock.shelter(y.level(), pos);
                                chosen = shelter != null ? y.habitat().choose(shelter)
                                        : y.life().assignHome(net.minecraft.core.GlobalPos.of(y.level().dimension(), pos));
                            }
                            if (!chosen) {
                                fail(c.getSource(), y.position(), y.getName().getString() + " 没能认下这个窝：窝位可能已满、被别人占着，或它够不到。");
                                return 0;
                            }
                            return 1;
                        }))))
                .then(Commands.literal("walk").then(Commands.argument("actors", EntityArgument.entities())
                        .then(Commands.argument("target", net.minecraft.commands.arguments.coordinates.Vec3Argument.vec3()).executes(c -> {
                            int count = 0;
                            var target = net.minecraft.commands.arguments.coordinates.Vec3Argument.getVec3(c, "target");
                            for (var entity : EntityArgument.getEntities(c, "actors"))
                                if (entity instanceof net.minecraft.world.entity.Mob mob) {
                                    if (MuseumWalkGoal.begin(mob, target)) count++;
                                    else fail(c.getSource(), mob.position(), mob.getName().getString() + " 没能开始走位（目标太远、区块没加载或它正乘着东西），这一段会原地播放。");
                                }
                            return count;
                        }))))
                .then(Commands.literal("stop").then(Commands.argument("actors", EntityArgument.entities()).executes(c -> {
                    int count = 0;
                    for (var entity : EntityArgument.getEntities(c, "actors"))
                        if (entity instanceof net.minecraft.world.entity.Mob mob && entity.getTags().contains("pm_actor")) {
                            if (mob instanceof YukkuriEntity y) {
                                if (!MuseumIndependenceSetup.stop(y) || !MuseumAquaticSetup.stop(y) || !MuseumLiveActors.restore(y)) {
                                    y.addTag("pm_stop_failed");
                                    fail(c.getSource(), y.position(), "暂不能重置 " + y.getName().getString()
                                            + "：成员或住所可能未加载，或仍在生产、接生、救援、上岸/收帽中；已保留当前状态，请等它安全结束后重试。");
                                    continue;
                                }
                            }
                            MuseumWalkGoal.cancel(mob);
                            mob.removeTag("pm_stop_failed");
                            count++;
                        }
                    return count;
                })))
                .then(Commands.literal("face").then(Commands.argument("actors", EntityArgument.entities())
                        .then(Commands.argument("yaw", FloatArgumentType.floatArg()).executes(c -> {
                            int count = 0;
                            float yaw = FloatArgumentType.getFloat(c, "yaw");
                            for (var entity : EntityArgument.getEntities(c, "actors")) if (actor(entity)) {
                                var y = (YukkuriEntity)entity;
                                y.setYRot(yaw); y.setYHeadRot(yaw); y.yBodyRot = yaw; count++;
                            }
                            return count;
                        }))))
                .then(Commands.literal("wound").then(Commands.argument("actors", EntityArgument.entities()).executes(c -> {
                    // A real, small injury through the ordinary wound path (the actors are invulnerable, so a hit cannot make one).
                    int count = 0;
                    for (var entity : EntityArgument.getEntities(c, "actors"))
                        if (entity instanceof YukkuriEntity y && y.isAlive() && y.getTags().contains("pm_actor")) {
                            y.body().injured(y.level().damageSources().generic(), 4f);
                            count++;
                        }
                    return count;
                })))
                .then(Commands.literal("contact")
                        .then(Commands.argument("first", EntityArgument.entity())
                                .then(Commands.argument("second", EntityArgument.entity())
                                        .then(Commands.argument("kind", StringArgumentType.word()).executes(c -> {
                                            var first = EntityArgument.getEntity(c, "first");
                                            var second = EntityArgument.getEntity(c, "second");
                                            String kind = StringArgumentType.getString(c, "kind");
                                            if (!actor(first) || !actor(second) || first == second
                                                    || !java.util.Set.of("nuzzle", "feed", "rear").contains(kind)) return 0;
                                            var a = (YukkuriEntity)first; var b = (YukkuriEntity)second;
                                            double distance = a.position().subtract(b.position()).horizontalDistance();
                                            double body = (a.getBbWidth() + b.getBbWidth()) * .5;
                                            if (a.level() != b.level() || distance < body - .1 || distance > body + .1
                                                    || Math.abs(a.getY() - b.getY()) > .5) {
                                                tellNear(c.getSource().getLevel(), a.position(), "接触姿态没有对上距离，这一小段不播放接触。", ChatFormatting.YELLOW);
                                                return 0;
                                            }
                                            a.bindMuseumContact(b, kind);
                                            return 1;
                                        })))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> settlementCommand() {
        return Commands.literal("settlement")
                .then(Commands.literal("start").then(Commands.argument("center", BlockPosArgument.blockPos()).executes(c ->
                        settlementResult(c.getSource(), MuseumSettlementSetup.start(c.getSource().getLevel(), BlockPosArgument.getLoadedBlockPos(c, "center"))))))
                .then(Commands.literal("reset").executes(c -> settlementResult(c.getSource(), MuseumSettlementSetup.reset(c.getSource().getLevel()))))
                .then(Commands.literal("status").executes(c -> settlementResult(c.getSource(), MuseumSettlementSetup.status(c.getSource().getLevel()))))
                .then(Commands.literal("order").then(Commands.argument("kind", StringArgumentType.word())
                        .suggests((c, b) -> SharedSuggestionProvider.suggest(new String[]{"forage", "scout", "message"}, b))
                        .executes(c -> {
                            var kind = switch (StringArgumentType.getString(c, "kind")) {
                                case "forage" -> SettlementOrders.Kind.FORAGE;
                                case "scout" -> SettlementOrders.Kind.SCOUT;
                                case "message" -> SettlementOrders.Kind.MESSAGE;
                                default -> SettlementOrders.Kind.NONE;
                            };
                            if (kind == SettlementOrders.Kind.NONE) {
                                c.getSource().sendFailure(Component.literal("聚落命令只能是 forage、scout 或 message。")); return 0;
                            }
                            return settlementResult(c.getSource(), MuseumSettlementSetup.order(c.getSource().getLevel(), kind));
                        })))
                .then(Commands.literal("threat").then(Commands.argument("amount", IntegerArgumentType.integer(1, 20)).executes(c ->
                        settlementResult(c.getSource(), MuseumSettlementSetup.threat(c.getSource().getLevel(), IntegerArgumentType.getInteger(c, "amount"))))))
                .then(Commands.literal("migrate").executes(c -> settlementResult(c.getSource(), MuseumSettlementSetup.migrate(c.getSource().getLevel()))));
    }

    private static int settlementResult(CommandSourceStack source, MuseumSettlementSetup.Result result) {
        // MuseumSettlementSetup already says every outcome to all players (its announce), so the command output is enough here.
        if (!result.success()) { source.sendFailure(Component.literal(result.message())); return 0; }
        source.sendSuccess(() -> Component.literal(result.message()), false);
        // An already-empty reset is still successful, even though it removed zero residents.
        return Math.max(1, result.count());
    }

    private static LiteralArgumentBuilder<CommandSourceStack> aquaticCommand() {
        return Commands.literal("aquatic").then(Commands.argument("mode", StringArgumentType.word())
                .suggests((c, b) -> SharedSuggestionProvider.suggest(new String[]{"low", "high", "voyage", "self", "braid", "shore", "birth", "sail"}, b))
                .then(Commands.argument("parent", EntityArgument.entity())
                        .then(Commands.argument("others", EntityArgument.entities())
                                .then(Commands.argument("bank", BlockPosArgument.blockPos())
                                        .then(Commands.argument("water", BlockPosArgument.blockPos()).executes(c -> {
                                            var selectedParent = EntityArgument.getEntity(c, "parent");
                                            if (!(selectedParent instanceof YukkuriEntity parent)) {
                                                fail(c.getSource(), selectedParent.position(), "水生演出的亲油必须是油库里。"); return 0;
                                            }
                                            var others = new java.util.ArrayList<YukkuriEntity>();
                                            for (var selected : EntityArgument.getEntities(c, "others")) {
                                                if (selected == parent) continue; // 'self' may select only the parent.
                                                if (!(selected instanceof YukkuriEntity actor)) {
                                                    fail(c.getSource(), parent.position(), "水生演出的配角必须全部是油库里。"); return 0;
                                                }
                                                others.add(actor);
                                            }
                                            String mode = StringArgumentType.getString(c, "mode");
                                            if (!MuseumAquaticSetup.prepare(mode, parent, others,
                                                    BlockPosArgument.getLoadedBlockPos(c, "bank"), BlockPosArgument.getLoadedBlockPos(c, "water"))) {
                                                fail(c.getSource(), parent.position(), "水生演出准备失败：检查模式、同展台演员、年龄、真实桨粮、相邻干岸/水面，以及上一轮是否已安全结束。");
                                                return 0;
                                            }
                                            c.getSource().sendSuccess(() -> Component.literal("水生演出 " + mode + " 已由真实行为接管；动作与结果以实际状态为准。"), false);
                                            return 1;
                                        }))))));
    }
    private MuseumCommands() {}
}
