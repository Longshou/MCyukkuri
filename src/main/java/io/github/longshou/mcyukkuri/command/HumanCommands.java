package io.github.longshou.mcyukkuri.command;

import io.github.longshou.mcyukkuri.human.Custody;
import io.github.longshou.mcyukkuri.entity.SpeciesProfile;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import io.github.longshou.mcyukkuri.human.HumanNpc;
import io.github.longshou.mcyukkuri.human.HumanScenes;
import io.github.longshou.mcyukkuri.shop.ShopPricing;
import io.github.longshou.mcyukkuri.shop.ShopRegistry;
import io.github.longshou.mcyukkuri.shop.ShopTrade;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import io.github.longshou.mcyukkuri.human.HumanCommerce;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;

/**
 * {@code /yukkuri human ...}: operator tools that put the human world in the playground. All of them call {@code HumanScenes} / {@code ShopRegistry} /
 * {@code ShopTrade}, i.e. the real buildings, entities and sale, never a stand-in.
 *
 * <pre>
 * human build shop|labor|manju|bag &lt;entrance pos&gt; [north|east|south|west] [clear]   a whole building; the position is the street in front of the main door
 * human walker &lt;pos&gt; [brother|sister] [variant]                                    a caretaker with a pet already out on the lead
 * human oniisan &lt;pos&gt; [prey]                                                      a cruel resident (with prey: a wild Reimu 5 blocks east)
 * human shop list                                                                  the shop register: keepers, posts, shelves
 * human shop stock &lt;keeper&gt; &lt;variant&gt; &lt;pos&gt;                                       one more shelf individual, bound to the keeper
 * human shop buy &lt;keeper&gt; &lt;animal&gt; &lt;buyer uuid&gt;                                   the sale of a shelf individual (no payment is taken)
 * </pre>
 */
public final class HumanCommands {
    private HumanCommands() {}

    private static final String[] FACING = {"north", "east", "south", "west"};

    private static void say(CommandContext<CommandSourceStack> c, String text) { c.getSource().sendSuccess(() -> Component.literal(text), false); }
    private static String at(Entity e) { return String.format(Locale.ROOT, "%s @ %.1f %.1f %.1f", e.getType().getDescriptionId().replace("entity.mcyukkuri.", ""), e.getX(), e.getY(), e.getZ()); }
    private static YukkuriVariant variant(String id) {
        for (var v : YukkuriVariant.values()) if (v.assetId().equals(id)) return v;
        return null;
    }

    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("human").requires(s -> s.hasPermission(2))
                .then(Commands.literal("build").then(Commands.argument("kind", StringArgumentType.word())
                        .suggests((c, b) -> SharedSuggestionProvider.suggest(Arrays.stream(HumanScenes.Kind.values()).map(k -> k.name().toLowerCase(Locale.ROOT)), b))
                        .then(Commands.argument("entrance", BlockPosArgument.blockPos())
                                .executes(c -> build(c, "north", true))
                                .then(Commands.argument("front", StringArgumentType.word())
                                        .suggests((c, b) -> SharedSuggestionProvider.suggest(FACING, b))
                                        .executes(c -> build(c, StringArgumentType.getString(c, "front"), true))
                                        .then(Commands.argument("clear", BoolArgumentType.bool())
                                                .executes(c -> build(c, StringArgumentType.getString(c, "front"), BoolArgumentType.getBool(c, "clear"))))))))
                .then(Commands.literal("walker").then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .executes(c -> walker(c, false, YukkuriVariant.REIMU))
                        .then(Commands.argument("who", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(new String[] {"brother", "sister"}, b))
                                .executes(c -> walker(c, StringArgumentType.getString(c, "who").equals("sister"), YukkuriVariant.REIMU))
                                .then(Commands.argument("variant", StringArgumentType.word())
                                        .suggests((c,
                                                b) -> SharedSuggestionProvider.suggest(Arrays.stream(YukkuriVariant.values()).filter(v -> !SpeciesProfile.of(v)
                                                        .houseBody() && !SpeciesProfile.of(v).dangerToYukkuri()).map(YukkuriVariant::assetId), b))
                                        .executes(c -> {
                                            var v = variant(StringArgumentType.getString(c, "variant"));
                                            if (v == null) { say(c, "unknown variant"); return 0; }
                                            return walker(c, StringArgumentType.getString(c, "who").equals("sister"), v);
                                        })))))
                .then(Commands.literal("oniisan").then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .executes(c -> oniisan(c, false))
                        .then(Commands.literal("prey").executes(c -> oniisan(c, true)))))
                .then(Commands.literal("shop")
                        .then(Commands.literal("list").executes(HumanCommands::list))
                        .then(Commands.literal("stock").then(Commands.argument("keeper", EntityArgument.entity())
                                .then(Commands.argument("variant", StringArgumentType.word())
                                        .suggests((c,
                                                b) -> SharedSuggestionProvider.suggest(Arrays.stream(YukkuriVariant.values()).filter(v -> !SpeciesProfile.of(v)
                                                        .houseBody()).map(YukkuriVariant::assetId), b))
                                        .then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(HumanCommands::stock)))))
                        .then(Commands.literal("carrier").then(Commands.argument("item", EntityArgument.entity()).executes(HumanCommands::carrier)))
                        .then(Commands.literal("buy").then(Commands.argument("keeper", EntityArgument.entity())
                                .then(Commands.argument("animal", EntityArgument.entity())
                                        .then(Commands.argument("buyer", UuidArgument.uuid()).executes(HumanCommands::buy))))));
    }

    private static int build(CommandContext<CommandSourceStack> c, String front, boolean clear) throws CommandSyntaxException {
        var kind = HumanScenes.Kind.byName(StringArgumentType.getString(c, "kind"));
        var dir = Direction.byName(front);
        if (kind == null || dir == null || dir.getAxis().isVertical()) { say(c, "kind: shop|labor|manju|bag; front: north|east|south|west"); return 0; }
        var pos = BlockPosArgument.getLoadedBlockPos(c, "entrance");
        var placed = HumanScenes.place(c.getSource().getLevel(), kind, pos, dir, clear);
        if (placed == null) { say(c, "template missing: " + kind.template); return 0; }
        var b = placed.box();
        say(c, "placed " + kind + " box " + b.minX() + " " + b.minY() + " " + b.minZ() + " .. " + b.maxX() + " " + b.maxY() + " " + b.maxZ()
                + "; staff " + placed.staff().size() + ", animals " + placed.animals().size());
        for (var n : placed.staff()) say(c, "  staff " + n.siteRole() + " job=" + n.job() + " " + at(n) + " uuid=" + n.getUUID());
        return 1;
    }

    private static int walker(CommandContext<CommandSourceStack> c, boolean sister, YukkuriVariant v) throws CommandSyntaxException {
        var pos = BlockPosArgument.getLoadedBlockPos(c, "pos");
        var npc = HumanScenes.spawnWalker(c.getSource().getLevel(), Vec3.atBottomCenterOf(pos), sister, v, true);
        if (npc == null) { say(c, "could not spawn"); return 0; }
        say(c, "walker " + at(npc) + " uuid=" + npc.getUUID() + " pet=" + npc.walkPetId() + " walking=" + npc.walking());
        return 1;
    }

    private static int oniisan(CommandContext<CommandSourceStack> c, boolean prey) throws CommandSyntaxException {
        var pos = BlockPosArgument.getLoadedBlockPos(c, "pos");
        var npc = HumanScenes.spawnCruel(c.getSource().getLevel(), Vec3.atBottomCenterOf(pos), prey);
        if (npc == null) { say(c, "could not spawn"); return 0; }
        say(c, "oniisan " + at(npc) + " uuid=" + npc.getUUID() + " attitude=" + npc.attitude());
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> c) {
        var level = c.getSource().getLevel(); var registry = ShopRegistry.get(level);
        int n = 0;
        for (var shop : registry.shops()) {
            n++;
            var keeper = level.getEntity(shop.keeper) instanceof HumanNpc k ? k : null;
            say(c, "shop keeper=" + shop.keeper + " post=" + shop.pos.toShortString() + " dim=" + shop.dimension + " keeperLoaded=" + (keeper != null && keeper.isAlive())
                    + " recordedStock=" + shop.stock.size());
            if (keeper == null) continue;
            for (var y : registry.liveStock(level, keeper))
                say(c, "  stock " + y.getType().getDescriptionId().replace("entity.mcyukkuri.", "") + " uuid=" + y.getUUID() + " grade=" + y.pet().grade() + " owner=" + HumanCommercePeek.owner(y)
                        + " price=" + ShopPricing.price(y));
        }
        say(c, n + " shop(s)");
        return n;
    }

    private static int stock(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        var keeper = EntityArgument.getEntity(c, "keeper") instanceof HumanNpc k ? k : null;
        var v = variant(StringArgumentType.getString(c, "variant"));
        if (keeper == null || v == null || keeper.job() != HumanNpc.Job.SHOPKEEPER) { say(c, "keeper must be a shopkeeper; variant unknown?"); return 0; }
        var pos = BlockPosArgument.getLoadedBlockPos(c, "pos");
        var y = HumanScenes.addStock(c.getSource().getLevel(), keeper, v, Vec3.atBottomCenterOf(pos));
        if (y == null) { say(c, "could not add stock"); return 0; }
        say(c, "stock " + at(y) + " uuid=" + y.getUUID() + " grade=" + y.pet().grade() + " price=" + ShopPricing.price(y));
        return 1;
    }

    /** Reads the individual stored in a dropped carrier (the token's saved snapshot): who owns it now, what it is for, which badge it wears. */
    private static int carrier(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        var level = c.getSource().getLevel();
        if (!(EntityArgument.getEntity(c, "item") instanceof ItemEntity item)) { say(c, "not an item entity"); return 0; }
        var snapshot = HumanCommerce.heldData(level, item.getItem());
        if (!snapshot.hasUUID("UUID")) { say(c, "no captured individual in this item"); return 0; }
        var custody = Custody.fromSnapshot(snapshot);
        say(c, "carrier holds " + snapshot.getString("id") + " uuid=" + snapshot.getUUID("UUID") + " owner=" + ((custody.owner() != null) ? custody.owner() : "none")
                + " use=" + custody.rawUse() + " badgeGrade=" + snapshot.getCompound("YukkuriPet").getInt("Grade"));
        return 1;
    }

    private static int buy(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        var keeper = EntityArgument.getEntity(c, "keeper") instanceof HumanNpc k ? k : null;
        var animal = EntityArgument.getEntity(c, "animal") instanceof YukkuriEntity y ? y : null;
        UUID buyer = UuidArgument.getUuid(c, "buyer");
        if (keeper == null || animal == null) { say(c, "keeper must be an NPC and animal a yukkuri"); return 0; }
        boolean ok = ShopTrade.begin(c.getSource().getLevel(), keeper, animal, buyer, ShopPricing.price(animal));
        say(c, ok ? "sale started for " + animal.getUUID() + " (done in ~46 ticks)" : "refused: not this keeper's stock, or busy");
        return ok ? 1 : 0;
    }

    /** Reads the custody owner without widening HumanCommerce. */
    private static final class HumanCommercePeek {
        static String owner(YukkuriEntity y) {
            var custody = Custody.view(y);
            return custody.owner() != null ? custody.owner().toString() : "none";
        }
    }
}
