package io.github.longshou.mcyukkuri.pet.social;

import io.github.longshou.mcyukkuri.mind.EmotionEvent;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.pet.stage.Staging;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;

/** B7: after a pet reported a wild visitor to its owner there is a short window (20 s) in which the owner's gentle care counts as praise:
 *  a small Poise step (the same daily-capped wild-poise gain as ignoring a wild one) and some stress relief. Transient; owned entirely here. */
public final class BadgePraise {
    private static final int WINDOW_TICKS = 400;
    private record Window(UUID owner, long until) {}
    private static final Map<UUID, Window> WINDOWS = new HashMap<>();
    private BadgePraise() {}

    public static void reset() { WINDOWS.clear(); }
    public static boolean open(YukkuriEntity pet) { return WINDOWS.containsKey(pet.getUUID()); }

    /** The pet just reported to this owner; the window starts now. */
    public static void start(YukkuriEntity pet, Player owner) {
        if (!(pet.level() instanceof ServerLevel level)) return;
        long now = level.getGameTime();
        if (WINDOWS.size() > 64) WINDOWS.values().removeIf(w -> w.until() <= now);
        WINDOWS.put(pet.getUUID(), new Window(owner.getUUID(), now + WINDOW_TICKS));
    }

    /** Called from PetLife.contact for every kind of caring owner contact (feeding, gentle touch, washing...). */
    public static void contacted(YukkuriEntity pet, UUID who) {
        var w = WINDOWS.get(pet.getUUID());
        if (w == null || !(pet.level() instanceof ServerLevel level)) return;
        if (w.until() < level.getGameTime()) { WINDOWS.remove(pet.getUUID()); return; }
        if (!w.owner().equals(who)) return;
        WINDOWS.remove(pet.getUUID());
        boolean gain = pet.pet().wildPoiseGain();
        pet.pet().addStress(-3);
        pet.mind().felt(EmotionEvent.PRAISED); pet.showReaction("happy", "lesson_praised", 80);
        Staging.sparkle(level, pet.position().add(0, pet.getBbHeight(), 0));
        pet.life().personality().decided(gain ? "badge_praised_poise" : "badge_praised");
    }
}
