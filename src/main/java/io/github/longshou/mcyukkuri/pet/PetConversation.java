package io.github.longshou.mcyukkuri.pet;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.subspecies.SubspeciesLife.Congenital;
import net.minecraft.network.chat.Component;

/** The aptitude for complete explanations in daily life. */
public final class PetConversation {
    private PetConversation() {}

    private static double range(int value, int low, int high) {
        return Math.clamp((value - low) / (double)(high - low), 0, 1);
    }

    public static double explanation(YukkuriEntity y) {
        if (!y.family().adult() || y.subspecies().congenital() != Congenital.NORMAL) return 0;
        var personality = y.life().personality();
        return Math.min(range(personality.intelligence(), 79, 85), range(personality.selfControl(), 79, 85))
                * (1 - y.mind().unease() / 100.0);
    }

    public static Component fullStudyReply(Component answer) {
        return Component.translatable("pet.mcyukkuri.reply.full_count", answer);
    }
}
