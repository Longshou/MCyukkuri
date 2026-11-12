package io.github.longshou.mcyukkuri.pet;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.social.YukkuriPersonality.Trait;
import io.github.longshou.mcyukkuri.subspecies.SubspeciesLife.Congenital;
import net.minecraft.network.chat.Component;

/** The same aptitude for complete, considerate explanations in daily life and at an exam. */
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

    public static double considerate(YukkuriEntity y) {
        var personality = y.life().personality();
        return Math.min(range(personality.friendliness(), 79, 85), range(personality.attachment(), 77, 85))
                * (1 - y.mind().unease() / 100.0);
    }

    public static boolean hasPotential(YukkuriEntity y) {
        var personality = y.life().personality();
        return personality.potential(Trait.INTELLIGENCE) >= 80 && personality.potential(Trait.SELF_CONTROL) >= 80
                && personality.potential(Trait.FRIENDLINESS) >= 80 && personality.potential(Trait.ATTACHMENT) >= 78;
    }

    public static Component fullStudyReply(PetSkill skill, Component answer) {
        return Component.translatable(skill == PetSkill.READING ? "pet.mcyukkuri.reply.full_read" : "pet.mcyukkuri.reply.full_count", answer);
    }
}
