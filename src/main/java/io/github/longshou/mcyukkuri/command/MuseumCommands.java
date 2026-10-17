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
