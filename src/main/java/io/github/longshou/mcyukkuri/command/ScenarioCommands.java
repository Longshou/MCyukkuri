package io.github.longshou.mcyukkuri.command;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.human.Custody;
import io.github.longshou.mcyukkuri.human.HumanNpc;
import io.github.longshou.mcyukkuri.pet.BadgeGrade;
import io.github.longshou.mcyukkuri.settlement.SettlementSpawner;
import java.util.ArrayList;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import io.github.longshou.mcyukkuri.family.FamilyData;
import net.minecraft.commands.CommandSource;

/**
 * Operator tools of the playground (试玩场): scenario starting conditions that need code, forced field clearing and the
 * status labels. Entities are spawned by the data pack with ordinary AI; these commands only set real state.
 */
public final class ScenarioCommands {
    /**
     * Failure text for the players standing near the scenario. A button runs these from a command block with output
     * off, so the source alone would swallow the reason.
     */
    static void tell(ServerLevel level, Vec3 at, String text, ChatFormatting colour) {
        var message = Component.literal(text).withStyle(colour);
        for (var player : level.players()) if (player.position().distanceToSqr(at) < 96.0 * 96.0) player.sendSystemMessage(message);
    }

    private static int refused(CommandSourceStack source, Entity at, String prefix, String reason) {
        tell(source.getLevel(), at.position(), "[试玩场] " + prefix + "没有成功：" + reason, ChatFormatting.RED);
        source.sendFailure(Component.literal(reason));
        return 0;
    }

    private static int done(CommandSourceStack source, String text) {
        source.sendSuccess(() -> Component.literal(text), false);
        return 1;
    }

    private static YukkuriEntity yukkuri(CommandContext<CommandSourceStack> c, String name) throws CommandSyntaxException {
        return EntityArgument.getEntity(c, name) instanceof YukkuriEntity y ? y : null;
    }

    private static BadgeGrade badgeGrade(String id) {
        return switch (id) {
            case "bronze" -> BadgeGrade.BRONZE;
            case "silver" -> BadgeGrade.SILVER;
            case "gold" -> BadgeGrade.GOLD;
            default -> BadgeGrade.NONE;
        };
    }

    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("scenario").requires(s -> s.hasPermission(2))
                .then(Commands.literal("wound").then(Commands.argument("targets", EntityArgument.entities()).executes(c -> {
                    // A real, small injury through the ordinary wound path.
                    int count = 0;
                    for (var entity : EntityArgument.getEntities(c, "targets"))
                        if (entity instanceof YukkuriEntity y && y.isAlive()) { y.hurt(y.level().damageSources().generic(), 4f); count++; }
                    return count;
                })))
                .then(Commands.literal("bond").then(Commands.argument("first", EntityArgument.entity())
                        .then(Commands.argument("second", EntityArgument.entity()).executes(c -> {
                            var first = yukkuri(c, "first"); var second = yukkuri(c, "second");
                            if (first == null || second == null || first.level() != second.level()) return 0;
                            ScenarioSetup.bond(first, second);
                            return 1;
                        }))))
                .then(Commands.literal("sumo").then(Commands.argument("first", EntityArgument.entity())
                        .then(Commands.argument("second", EntityArgument.entity()).executes(c -> {
                            var first = yukkuri(c, "first"); var second = yukkuri(c, "second");
                            if (first == null || second == null) return 0;
                            return first.groupInteraction().challenge(second) ? done(c.getSource(), "相扑已开始，会按正常规则继续。")
                                    : refused(c.getSource(), first, "发起相扑", "双方要清醒、成年、相识、相距四格内，且各自在干燥空地上。");
                        }))))
                .then(Commands.literal("badge").then(Commands.argument("target", EntityArgument.entity())
                        .then(Commands.argument("grade", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(new String[]{"bronze", "silver", "gold"}, b))
                                .then(Commands.argument("detached", BoolArgumentType.bool()).executes(c -> {
                                    var target = yukkuri(c, "target");
                                    if (target == null) return 0;
                                    var grade = badgeGrade(StringArgumentType.getString(c, "grade"));
                                    if (grade == BadgeGrade.NONE) return refused(c.getSource(), target, "签发徽章", "等级只能是 bronze、silver 或 gold。");
                                    var player = c.getSource().getPlayerOrException();
                                    if (!target.pet().ownedBy(player.getUUID()))
                                        return refused(c.getSource(), target, "签发徽章", "只能给自己的宠物签发令牌。");
                                    target.pet().issue(grade, 3, player.getUUID());
                                    if (BoolArgumentType.getBool(c, "detached")) {
                                        // The domain API creates the token from this individual's current UUID and serial; never handcraft its data.
                                        var token = target.pet().reissueToken();
                                        if (!player.getInventory().add(token)) player.drop(token, false);
                                    }
                                    return done(c.getSource(), BoolArgumentType.getBool(c, "detached") ? "已签发该个体的真实徽章令牌。" : "已签发并佩戴真实徽章。");
                                })))))
                .then(Commands.literal("stud").then(Commands.argument("keeper", EntityArgument.entity())
                        .then(Commands.argument("animal", EntityArgument.entity()).executes(c -> {
                            var keeperEntity = EntityArgument.getEntity(c, "keeper"); var stud = yukkuri(c, "animal");
                            if (!(keeperEntity instanceof HumanNpc keeper) || stud == null || keeper.level() != stud.level()) return 0;
                            if (keeper.job() != HumanNpc.Job.KEEPER)
                                return refused(c.getSource(), keeper, "登记种油", "店方必须是饲养员。");
                            Custody.assign(stud, keeper, Custody.Role.STUD);
                            return done(c.getSource(), "店方种油已按真实归属登记；能力与徽章仍须由实际行为获得。");
                        }))))
                .then(Commands.literal("mate").then(Commands.argument("first", EntityArgument.entity())
                        .then(Commands.argument("second", EntityArgument.entity()).executes(c -> {
                            var first = yukkuri(c, "first"); var second = yukkuri(c, "second");
                            if (first == null || second == null) return 0;
                            return first.family().introduce(second) ? 1 : refused(c.getSource(), first, "结为伴侣", "双方要是成年、同一维度、未亲缘相连，且没有别的伴侣。");
                        }))))
                .then(Commands.literal("aftermath").then(Commands.argument("victim", EntityArgument.entity())
                        .then(Commands.argument("source", EntityArgument.entity()).executes(c -> {
                            var victim=yukkuri(c,"victim");var source=yukkuri(c,"source");
                            if(victim==null||source==null)return 0;
                            var data=FamilyData.get(c.getSource().getLevel());
                            String reason=data.aftermathRefusal(victim,source);
                            if(reason!=null)return refused(c.getSource(),victim,"事后茎生场景",reason);
                            var brood=data.createAftermath(victim,source);
                            if(brood==null)return refused(c.getSource(),victim,"事后茎生场景","状态已改变，未建立新的胎次");
                            tell(c.getSource().getLevel(),victim.position(),"[试玩场] 已建立事后茎生胎次：从无茎开始随进度萌生，持续消耗母体营养；停育后负担仍继续。",ChatFormatting.YELLOW);
                            return done(c.getSource(),"事后状态已建立；可读取营养、体力、胎次进度和负担计时。");
                        }))))
                .then(Commands.literal("kin").then(Commands.argument("parent", EntityArgument.entity())
                        .then(Commands.argument("child", EntityArgument.entity())
                                .then(Commands.argument("newborn", BoolArgumentType.bool()).executes(c -> {
                                    var parent = yukkuri(c, "parent"); var child = yukkuri(c, "child");
                                    if (parent == null || child == null) return 0;
                                    String reason = ScenarioSetup.kin(parent, child, BoolArgumentType.getBool(c, "newborn"));
                                    return reason == null ? 1 : refused(c.getSource(), parent, "建立亲子", reason);
                                })))))
                .then(Commands.literal("home").then(Commands.argument("actor", EntityArgument.entity())
                        .then(Commands.argument("home", BlockPosArgument.blockPos()).executes(c -> {
                            var actor = yukkuri(c, "actor");
                            if (actor == null) return 0;
                            if (!ScenarioSetup.home(actor, BlockPosArgument.getLoadedBlockPos(c, "home")))
                                return refused(c.getSource(), actor, "指定窝位", actor.getName().getString() + " 没能认下这个窝：窝位要有顶、够得着、没被别人占着。");
                            return 1;
                        }))))
                .then(Commands.literal("eggs").then(Commands.argument("mother", EntityArgument.entity())
                        .then(Commands.argument("father", EntityArgument.entity()).executes(c -> {
                            var mother = yukkuri(c, "mother"); var father = yukkuri(c, "father");
                            if (mother == null || father == null) return 0;
                            String reason = ScenarioSetup.eggs(mother, father);
                            if (reason != null) return refused(c.getSource(), mother, "产卵场景", reason);
                            tell(c.getSource().getLevel(), mother.position(), "[试玩场] 真实胎次与窝中卵位已建立，照护与迁卵按普通规则进行。", ChatFormatting.GOLD);
                            return 1;
                        }))))
                .then(Commands.literal("birth").then(Commands.argument("mother", EntityArgument.entity())
                        .then(Commands.argument("father", EntityArgument.entity())
                                .then(Commands.argument("outcome", StringArgumentType.word())
                                        .suggests((c, b) -> SharedSuggestionProvider.suggest(new String[]{"natural", "caught", "missed"}, b))
                                        .executes(c -> {
                                            var mother = yukkuri(c, "mother"); var father = yukkuri(c, "father");
                                            if (mother == null || father == null) return 0;
                                            String outcome = StringArgumentType.getString(c, "outcome");
                                            String reason = ScenarioSetup.birth(mother, father, outcome);
                                            if (reason != null) return refused(c.getSource(), mother, "接生场景", reason);
                                            tell(c.getSource().getLevel(), mother.position(), "[试玩场] 母油已怀上真实胎次并进入临产（" + outcome
                                                    + "）；接下来的出生、接生和照护由普通规则发生。", ChatFormatting.GOLD);
                                            return 1;
                                        })))))
                .then(Commands.literal("aquatic_colony").then(Commands.argument("seed", EntityArgument.entity())
                        .then(Commands.argument("bank", BlockPosArgument.blockPos()).executes(c -> {
                            var founder = yukkuri(c, "seed");
                            if (founder == null) return 0;
                            var members = SettlementSpawner.spawnAquaticColony(c.getSource().getLevel(),
                                    BlockPosArgument.getLoadedBlockPos(c, "bank"), founder);
                            if (members.isEmpty())
                                return refused(c.getSource(), founder, "水族聚落", SettlementSpawner.lastFailure());
                            tell(c.getSource().getLevel(), founder.position(), "[试玩场] 已建立真实水族家庭与岸边公仓；帽船、亲缘和觅食均由普通行为启动。",
                                    ChatFormatting.GOLD);
                            return done(c.getSource(), "水族聚落已建立：" + members.size() + " 名成员、真实岸边公仓；之后不强制角色或路线。");
                        }))))
                .then(Commands.literal("boat").then(Commands.argument("mode", StringArgumentType.word())
                        .suggests((c, b) -> SharedSuggestionProvider.suggest(new String[]{"sail", "shore", "high", "voyage", "braid", "self", "birth", "drop", "shake", "afloat", "unattended", "self_far", "self_tired", "return_capsized", "capsize_family",
                                "cargo_empty", "cargo_full", "cargo_load", "cargo_rescue", "cargo_stow"}, b))
                        .then(Commands.argument("parent", EntityArgument.entity())
                                .then(Commands.argument("others", EntityArgument.entities())
                                        .then(Commands.argument("bank", BlockPosArgument.blockPos())
                                                .then(Commands.argument("water", BlockPosArgument.blockPos()).executes(c -> {
                                                    var parent = yukkuri(c, "parent");
                                                    if (parent == null) return 0;
                                                    var others = new ArrayList<YukkuriEntity>();
                                                    for (var selected : EntityArgument.getEntities(c, "others")) {
                                                        if (selected == parent) continue;
                                                        if (selected instanceof YukkuriEntity other) others.add(other);
                                                    }
                                                    String mode = StringArgumentType.getString(c, "mode");
                                                    String reason = ScenarioBoats.prepare(mode, parent, others,
                                                            BlockPosArgument.getLoadedBlockPos(c, "bank"), BlockPosArgument.getLoadedBlockPos(c, "water"));
                                                    if (reason != null) return refused(c.getSource(), parent, "水上场景 " + mode, reason);
                                                    return done(c.getSource(), "水上场景 " + mode + " 已设置初始条件；之后由普通行为接管。");
                                                })))))))
                .then(Commands.literal("shore").then(Commands.argument("parent", EntityArgument.entity()).executes(c -> {
                    var parent = yukkuri(c, "parent");
                    if (parent == null) return 0;
                    return parent.aquaticLife().requestShoreVisit() ? 1
                            : refused(c.getSource(), parent, "返岸", "亲油现在不能返岸：要在自己的船上、船在水面、八格内有干岸、没有别的任务。");
                })))
                .then(Commands.literal("independence").then(Commands.argument("parent", EntityArgument.entity())
                        .then(Commands.argument("teen", EntityArgument.entity())
                                .then(Commands.argument("home", BlockPosArgument.blockPos()).executes(c -> {
                                    var parent = yukkuri(c, "parent"); var teen = yukkuri(c, "teen");
                                    if (parent == null || teen == null) return 0;
                                    String reason = ScenarioSetup.independence(parent, teen, BlockPosArgument.getLoadedBlockPos(c, "home"));
                                    if (reason != null) return refused(c.getSource(), parent, "独立场景", reason);
                                    tell(c.getSource().getLevel(), parent.position(), "[试玩场] 亚成已准备独立：下一个白天机会离巢（亲油在近旁会先告别），之后自己找洞或挖穴（可用倍速）。",
                                            ChatFormatting.GOLD);
                                    return 1;
                                })))));
    }

    /**
     * {@code /yukkuri clear <from> <to> [tag]}: removes the mod's entities and all dropped items in the box. With a field mark
     * {@code tag} it also removes the loaded entities carrying it and makes the world records forget what the field left behind
     * (PlaygroundTools.clear). Never refuses; unloaded parts of the box are skipped instead of failing the whole clear.
     */
    public static LiteralArgumentBuilder<CommandSourceStack> clear() {
        return Commands.literal("clear").requires(s -> s.hasPermission(2))
                .then(Commands.argument("from", BlockPosArgument.blockPos())
                        .then(Commands.argument("to", BlockPosArgument.blockPos())
                                .executes(c -> clearField(c, null))
                                .then(Commands.argument("tag", StringArgumentType.word())
                                        .executes(c -> clearField(c, StringArgumentType.getString(c, "tag"))))));
    }

    private static int clearField(CommandContext<CommandSourceStack> c, String tag) {
        int removed = PlaygroundTools.clear(c.getSource().getLevel(),
                BlockPosArgument.getBlockPos(c, "from"), BlockPosArgument.getBlockPos(c, "to"), tag);
        return done(c.getSource(), "已清除 " + removed + " 个实体。") * Math.max(1, removed);
    }

    /** {@code /yukkuri observe on|off}: status labels over every nearby yukkuri (current behaviour, hunger, sleep, age, pregnancy, injury). */
    public static LiteralArgumentBuilder<CommandSourceStack> observe() {
        return Commands.literal("observe").requires(s -> s.hasPermission(2))
                .then(Commands.literal("on").executes(c -> {
                    PlaygroundTools.observe(true, c.getSource().getServer());
                    return done(c.getSource(), "观察标签已开启：附近油库里头顶显示 行为/动画 饥 困 年龄 孕 伤。");
                }))
                .then(Commands.literal("off").executes(c -> {
                    PlaygroundTools.observe(false, c.getSource().getServer());
                    return done(c.getSource(), "观察标签已关闭，名字已恢复。");
                }));
    }


    /**
     * {@code /yukkuri speed rate <20..400>} and {@code speed sprint <ticks>}: the playground's speed buttons. Vanilla {@code /tick}
     * needs permission level 3, but data-pack functions and command blocks only run at level 2, so the buttons cannot call it.
     */
    public static LiteralArgumentBuilder<CommandSourceStack> speed() {
        return Commands.literal("speed").requires(s -> s.hasPermission(2))
                .then(Commands.literal("rate").then(Commands.argument("rate", FloatArgumentType.floatArg(20f, 400f)).executes(c -> {
                    float rate = FloatArgumentType.getFloat(c, "rate");
                    c.getSource().getServer().tickRateManager().setTickRate(rate);
                    tell(c.getSource().getLevel(), c.getSource().getPosition(), "[试玩场] 游戏速度 " + (int)(rate / 20f) + "x（tick rate " + (int)rate
                            + "）。成长、孕期、筑巢都按这个速度前进。", ChatFormatting.GRAY);
                    return 1;
                })))
                .then(Commands.literal("sprint").then(Commands.argument("ticks", IntegerArgumentType.integer(20, 72000)).executes(c -> {
                    int ticks = IntegerArgumentType.getInteger(c, "ticks");
                    c.getSource().getServer().tickRateManager().requestGameToSprint(ticks);
                    tell(c.getSource().getLevel(), c.getSource().getPosition(), "[试玩场] 快速推进 " + ticks + " tick（约 " + ticks / 1200 + " 分钟游戏时间），完成后恢复原速度。",
                            ChatFormatting.GRAY);
                    return 1;
                })));
    }

    /**
     * {@code /yukkuri relay <command>}: runs another command with its answer sent to the players near the command position.
     * A button runs from a command block whose output nobody sees; the status, shop and household readings would vanish there.
     * The relayed command runs at permission level 2, like the button itself.
     */
    public static LiteralArgumentBuilder<CommandSourceStack> relay() {
        return Commands.literal("relay").requires(s -> s.hasPermission(2))
                .then(Commands.argument("command", StringArgumentType.greedyString()).executes(c -> {
                    var source = c.getSource();
                    var level = source.getLevel();
                    var at = source.getPosition();
                    var sink = new CommandSource() {
                        @Override public void sendSystemMessage(Component message) {
                            var line = message.copy().withStyle(ChatFormatting.WHITE);
                            for (var player : level.players()) if (player.position().distanceToSqr(at) < 96.0 * 96.0) player.sendSystemMessage(line);
                        }
                        @Override public boolean acceptsSuccess() { return true; }
                        @Override public boolean acceptsFailure() { return true; }
                        @Override public boolean shouldInformAdmins() { return false; }
                    };
                    var relayed = new CommandSourceStack(sink, at, source.getRotation(), level, 2, "playground", Component.literal("playground"),
                            source.getServer(), source.getEntity());
                    source.getServer().getCommands().performPrefixedCommand(relayed, StringArgumentType.getString(c, "command"));
                    return 1;
                }));
    }

    /** Like relay, but sends the complete captured result to one player's paginated read-only report. */
    public static LiteralArgumentBuilder<CommandSourceStack> readout() {
        return Commands.literal("readout").requires(s -> s.hasPermission(2))
                .then(Commands.argument("command", StringArgumentType.greedyString()).executes(ReadoutReport.EXECUTOR));
    }

    private ScenarioCommands() {}
}
