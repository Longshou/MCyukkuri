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

/** Situational interview, gold only (approx. 10-12 s). 100 sensible answer; 0 silly answer. */
public final class WitsInterviewItem extends Scene {
    private boolean ok; private int q, ans;
    @Override public Ability ability() { return Ability.WITS; }
    @Override public void start(ExamContext c) {
        ok = c.roll(Ability.WITS); score = ok ? 100 : 0; q = 1 + c.random().nextInt(3); ans = 1 + c.random().nextInt(3); log(c, "wits_interview", score);
    }
    @Override boolean run(ExamContext c) {
        var y = c.candidate();
        switch (ph) {
            case 0 -> { if (podiumReady(c)) next(); }
            case 1 -> {
                if (pt == 1) { c.examinerLookAt(y); c.examinerSay(P + "wits_interview.say"); c.card(deskTop(c),
                        Component.translatable(P + "wits_interview.q." + q), 360, 1.0f); y.playAction("think"); }
                if (pt % 10 == 5) Staging.sweat(y);
                if (pt >= 70) {
                    y.playAction("idle"); y.speak("exam_answer");
                    c.card(top(y, .6), Component.translatable(P + "wits_interview." + (ok ? "good." : "silly.") + (ok ? q : ans)), 110, 1.0f);
                    next();
                }
            }
            case 2 -> { if (pt >= 50) { y.showReaction(ok ? "happy" : "confused", "", 50); next(); } }
            default -> { if (pt == 1) mark(c, score, deskTop(c).add(0, .9, 0)); return pt >= 40; }
        }
        return false;
    }
}
