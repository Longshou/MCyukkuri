package io.github.longshou.mcyukkuri.mind;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Naming denies identity (komicolle 40720 No.11518829): to a yukkuri, "Reimu is Reimu"; a personal name given from
 * outside denies that and makes it unable to take it easy. The lecture adds that a baby may even vomit filling and
 * die of it; by the user's decision (2026-10-18) naming here is never fatal.
 *
 * <ul>
 *   <li>Young (baby or juvenile): strong denial, crying, one small portion of filling vomited (never at 3 portions or
 *       fewer, so it cannot kill), unease +25, +10 for every earlier naming still remembered.</li>
 *   <li>Adult: refusal ("that is not the name"), a sulk, unease +12, +6 per earlier naming.</li>
 *   <li>Called by its own species name: no denial, it introduces itself happily ("Reimu is Reimu!").</li>
 *   <li>Its owner naming a pet softens the blow (x0.6, project design): the pet line has no naming system of its own,
 *       and the exam already has the yukkuri answer with its species name.</li>
 * </ul>
 * The vanilla name tag still writes the custom name; naming changes neither species, identity nor kinship.
 * {@code /yukkuri name <targets> <name>} drives the same path for headless checks and the playground.
 */
@EventBusSubscriber(modid = MCYukkuri.MOD_ID)
public final class Naming {
    private static final Map<String, List<String>> OWN = Map.of(
            "reimu", List.of("reimu", "灵梦", "靈夢", "霊夢", "れいむ", "レイム", "れいみゅ"),
            "marisa", List.of("marisa", "魔理沙", "魔里沙", "まりさ", "マリサ", "まりちゃ"),
            "alice", List.of("alice", "arisu", "爱丽丝", "愛麗絲", "アリス", "ありす", "ありちゅ"),
            "patchouli", List.of("patchouli", "pachuri", "帕秋莉", "帕邱莉", "パチュリー", "ぱちゅりー", "ぱちぇ"),
            "chen", List.of("chen", "橙", "ちぇん", "チェン"),
            "remilia", List.of("remilia", "蕾米莉亚", "蕾咪", "レミリア", "れみりゃ"),
            "flandre", List.of("flandre", "芙兰", "芙蘭", "フランドール", "ふらん"));

    /** Naming by itself never lifts unease past this (below MindLife.BREAKING), so it cannot start the fatal sickness. */
    public static final float NAMING_CEILING = 90;

    /** Vanilla applies the tag after this event, inside Mob.interact; this only reacts (server side). */
    @SubscribeEvent
    public static void nameTag(PlayerInteractEvent.EntityInteract event) {
        if (event.getLevel().isClientSide() || !(event.getTarget() instanceof YukkuriEntity y) || !y.isAlive()) return;
        var stack = event.getItemStack();
        if (!stack.is(Items.NAME_TAG)) return;
        var name = stack.get(DataComponents.CUSTOM_NAME);
        if (name == null) return;
        named(y, event.getEntity().getUUID(), name.getString());
    }

    public static boolean ownName(YukkuriEntity y, String name) {
        String n = name.toLowerCase(Locale.ROOT).replaceAll("[\\s・·._-]", "");
        for (String alias : OWN.getOrDefault(y.getVariant().dialogueSpecies(), List.of())) if (n.contains(alias.toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    /** The reaction to being given {@code name} by {@code namer} (null: a command). Returns a short description. */
    public static String named(YukkuriEntity y, UUID namer, String name) {
        if (y.level().isClientSide || !y.isAlive()) return "not a living server-side individual";
        var mind = y.mind();
        boolean young = !y.family().adult();
        if (ownName(y, name)) {
            mind.add(-2, "own_name");
            y.showReaction("happy", "introduced", 80);
            MCYukkuri.LOGGER.info("[mind] {} #{} called by its own name '{}': accepted", y.getVariant().assetId(), y.getId(), name);
            return "own name: accepted";
        }
        int count = mind.named();
        float soften = namer != null && y.pet().isPet() && y.pet().ownedBy(namer) ? .6f : 1f;
        float delta;
        int vomited = 0;
        if (young) {
            delta = Math.min(50, 25 + 10 * (count - 1)) * soften;
            y.playAction("struggle");
            y.showReaction("cry", mind.pick("renamed_upset", "afraid"), 120);
            int portions = count >= 3 ? 2 : 1;
            // Never fatal: it keeps at least three portions (body filling 0 is death).
            for (int i = 0; i < portions && y.body().filling() > 3; i++) if (y.body().releaseFilling("vomited")) vomited++;
        } else {
            delta = Math.min(30, 12 + 6 * (count - 1)) * soften;
            y.playAction(count >= 3 ? "hide_face" : "sulk");
            y.showReaction(count >= 3 ? "teary" : "angry", mind.pick("renamed_upset", "request_sulk"), 100);
        }
        // Never fatal, not even through the sickness: naming alone stops short of the breaking point.
        delta = Math.max(0, Math.min(delta, NAMING_CEILING - mind.unease()));
        mind.add(delta, "renamed");
        String result = (young ? "young" : "adult") + " denied name '" + name + "' (naming " + count + ", unease +" + Math.round(delta)
                + " -> " + Math.round(mind.unease()) + ", vomited " + vomited + ", filling " + y.body().filling() + ")";
        MCYukkuri.LOGGER.info("[mind] {} #{} {}", y.getVariant().assetId(), y.getId(), result);
        return result;
    }

    /** {@code /yukkuri name <targets> <name>}: give the name as a name tag would, through the same reaction. */
    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("name").then(Commands.argument("targets", EntityArgument.entities())
                .then(Commands.argument("name", StringArgumentType.greedyString()).executes(context -> {
                    String name = StringArgumentType.getString(context, "name");
                    int done = 0;
                    for (Entity target : EntityArgument.getEntities(context, "targets")) {
                        if (!(target instanceof YukkuriEntity y) || !y.isAlive()) continue;
                        var namer = context.getSource().getEntity() == null ? null : context.getSource().getEntity().getUUID();
                        String result = named(y, namer, name);
                        y.setCustomName(Component.literal(name));
                        y.setPersistenceRequired();
                        done++;
                        context.getSource().sendSuccess(() -> Component.literal("#" + y.getId() + " " + result), true);
                    }
                    if (done == 0) context.getSource().sendFailure(Component.literal("no living yukkuri among the targets"));
                    return done;
                })));
    }

    private Naming() {}
}
