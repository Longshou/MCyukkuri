package io.github.longshou.mcyukkuri.human;

import io.github.longshou.mcyukkuri.item.CarrierItem;
import io.github.longshou.mcyukkuri.registry.ModItems;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

/**
 * What an ordinary resident does when a player talks to it. A caretaker (爱派) resident is not a shop and not a trader: it may keep a pet that it walks on
 * the lead and it feeds and pets wild yukkuri, nothing more. The only dealings with a player are social: a word, a gift of grain for its feeding, and
 * the old missing-companion errand (a resident whose pet ran off asks for it back). Every trade belongs to the pet shop's staff.
 */
final class HumanFolk {
    private HumanFolk() {}

    static void interact(Player player, HumanNpc npc) {
        var hand = player.getMainHandItem();
        if (npc.lostAnimal() != null && !npc.questComplete() && hand.is(ModItems.CARRIER.get()) && CarrierItem.token(hand) != null) {
            boolean ok = HumanMenu.returnLost(player, npc);
            player.displayClientMessage(Component.translatable(ok ? "human.mcyukkuri.talk.returned" : "human.mcyukkuri.unavailable"), true);
            return;
        }
        if (hand.is(ModItems.GRAIN_REGULAR.get()) && hand.getCount() >= 8) {
            if (npc.donateFood()) {
                if (!player.getAbilities().instabuild) hand.shrink(8);
                player.displayClientMessage(Component.translatable("human.mcyukkuri.talk.grain"), true);
                return;
            }
        }
        if (npc.acceptQuest(player)) return;
        String mood = npc.attitude() == HumanNpc.Attitude.CRUEL ? "cruel" : npc.walking() ? "walk" : npc.walkPetId() != null ? "pet" : "friendly";
        int variants = mood.equals("cruel") ? 2 : 3;
        player.sendSystemMessage(Component.translatable("human.mcyukkuri.talk.say", npc.getDisplayName(),
                Component.translatable("human.mcyukkuri.talk." + mood + "." + npc.getRandom().nextInt(variants))));
    }
}
