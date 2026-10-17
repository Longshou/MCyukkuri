package io.github.longshou.mcyukkuri.command;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.world.entity.Entity;

/** Presentation-only bindings for the fixed museum cast. Never starts natural care or awards progress. */
public final class MuseumCommands {
    public static boolean actor(Entity entity) {
        return entity instanceof YukkuriEntity y && y.isAlive() && y.isNoAi() && y.getTags().contains("pm_actor");
    }

    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("museum").requires(s -> s.hasPermission(2))
                .then(Commands.literal("independence").then(Commands.argument("parent", EntityArgument.entity())
                        .then(Commands.argument("teen", EntityArgument.entity())
                                .then(Commands.argument("home", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos()).executes(c -> {
                                    var parent = EntityArgument.getEntity(c, "parent"); var teen = EntityArgument.getEntity(c, "teen");
                                    return parent instanceof YukkuriEntity p && teen instanceof YukkuriEntity y
                                            && MuseumIndependenceSetup.prepare(p, y,
                                            net.minecraft.commands.arguments.coordinates.BlockPosArgument.getLoadedBlockPos(c, "home")) ? 1 : 0;
                                })))))
                .then(Commands.literal("live").then(Commands.argument("actors", EntityArgument.entities())
                        .then(Commands.argument("need", StringArgumentType.word()).executes(c -> {
                            int count = 0;
                            for (var entity : EntityArgument.getEntities(c, "actors"))
                                if (entity instanceof YukkuriEntity y && MuseumLiveActors.prepare(y, StringArgumentType.getString(c, "need"))) count++;
                            return count;
                        }))))
                .then(Commands.literal("parent").then(Commands.argument("parent", EntityArgument.entity())
                        .then(Commands.argument("child", EntityArgument.entity())
                                .then(Commands.argument("firstToilet", com.mojang.brigadier.arguments.BoolArgumentType.bool()).executes(c -> {
                                    var parent = EntityArgument.getEntity(c, "parent"); var child = EntityArgument.getEntity(c, "child");
                                    return parent instanceof YukkuriEntity p && child instanceof YukkuriEntity y
                                            && MuseumLiveActors.parent(p, y, com.mojang.brigadier.arguments.BoolArgumentType.getBool(c, "firstToilet")) ? 1 : 0;
                                })))))
                .then(Commands.literal("home").then(Commands.argument("actor", EntityArgument.entity())
                        .then(Commands.argument("home", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos()).executes(c -> {
                            var entity = EntityArgument.getEntity(c, "actor");
                            if (!(entity instanceof YukkuriEntity y) || !y.getTags().contains("pm_live")) return 0;
                            var pos = net.minecraft.commands.arguments.coordinates.BlockPosArgument.getLoadedBlockPos(c, "home");
                            if (y.level().getBlockEntity(pos) instanceof io.github.longshou.mcyukkuri.house.HouseNestBlockEntity nest)
                                return y.house().choose(nest) ? 1 : 0;
                            var shelter = io.github.longshou.mcyukkuri.settlement.ShelterBlock.shelter(y.level(), pos);
                            return (shelter != null ? y.habitat().choose(shelter)
                                    : y.life().assignHome(net.minecraft.core.GlobalPos.of(y.level().dimension(), pos))) ? 1 : 0;
                        }))))
                .then(Commands.literal("walk").then(Commands.argument("actors", EntityArgument.entities())
                        .then(Commands.argument("target", net.minecraft.commands.arguments.coordinates.Vec3Argument.vec3()).executes(c -> {
                            int count = 0;
                            var target = net.minecraft.commands.arguments.coordinates.Vec3Argument.getVec3(c, "target");
                            for (var entity : EntityArgument.getEntities(c, "actors"))
                                if (entity instanceof net.minecraft.world.entity.Mob mob && MuseumWalkGoal.begin(mob, target)) count++;
                            return count;
                        }))))
                .then(Commands.literal("stop").then(Commands.argument("actors", EntityArgument.entities()).executes(c -> {
                    int count = 0;
                    for (var entity : EntityArgument.getEntities(c, "actors"))
                        if (entity instanceof net.minecraft.world.entity.Mob mob && entity.getTags().contains("pm_actor")) {
                            MuseumWalkGoal.cancel(mob);
                            if (mob instanceof YukkuriEntity y) {
                                if (!MuseumIndependenceSetup.stop(y)) {
                                    c.getSource().sendFailure(net.minecraft.network.chat.Component.literal("独立展台仍有未加载成员或住所；先带回成员再重置。"));
                                    continue;
                                }
                                MuseumLiveActors.restore(y);
                            }
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
                                                    || Math.abs(a.getY() - b.getY()) > .5) return 0;
                                            a.bindMuseumContact(b, kind);
                                            return 1;
                                        })))))
                .then(Commands.literal("seat")
                        .then(Commands.argument("actor", EntityArgument.entity())
                                .then(Commands.argument("x", DoubleArgumentType.doubleArg(-12, 12))
                                        .then(Commands.argument("z", DoubleArgumentType.doubleArg(-12, 12)).executes(c -> {
                                            var entity = EntityArgument.getEntity(c, "actor");
                                            if (!actor(entity) || !(entity.getVehicle() instanceof HatBoatEntity hat)) return 0;
                                            return hat.museumSeat((YukkuriEntity)entity, DoubleArgumentType.getDouble(c, "x"),
                                                    DoubleArgumentType.getDouble(c, "z")) ? 1 : 0;
                                        })))));
    }
    private MuseumCommands() {}
}
