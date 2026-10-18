package io.github.longshou.mcyukkuri.pet.exam.items;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.pet.Ability;
import io.github.longshou.mcyukkuri.pet.BadgeGrade;
import io.github.longshou.mcyukkuri.pet.exam.ExamContext;
import io.github.longshou.mcyukkuri.pet.stage.Staging;
import io.github.longshou.mcyukkuri.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/** Read one simple word aloud (approx. 8-10 s). 100 reads it; 0 says it cannot. */
public final class OralReadItem extends Scene {
    private static final String[] WORDS = {"cat", "flower", "water", "stone", "sky"};
    private boolean ok; private int word;
    @Override public Ability ability() { return Ability.ACADEMICS; }
    @Override public void start(ExamContext c) {
        ok = c.roll(Ability.ACADEMICS); score = ok ? 100 : 0; word = c.random().nextInt(WORDS.length); log(c, "oral_read", score);
    }
    @Override boolean run(ExamContext c) {
        var y = c.candidate();
        switch (ph) {
            case 0 -> { if (podiumReady(c)) next(); }
            case 1 -> {
                if (pt == 1) { c.examinerLookAt(y); c.examinerSay(P + "oral_read.say"); c.card(deskTop(c),
                        Component.translatable(P + "oral_read.word." + WORDS[word]), 300, 2.2f); y.playAction("think"); }
                if (pt % 10 == 5) Staging.sweat(y);
                if (pt >= 45) {
                    y.playAction("idle");
                    if (ok) { y.speak("exam_answer"); c.card(top(y, .55), Component.translatable(P + "oral_read.say." + WORDS[word]), 80, 1.3f); }
                    else { y.showReaction("confused", "exam_unsure", 70); Staging.sweat(y); }
                    next();
                }
            }
            case 2 -> { if (pt >= 40) next(); }
            default -> { if (pt == 1) mark(c, score, deskTop(c).add(0, .9, 0)); return pt >= 40; }
        }
        return false;
    }
}
