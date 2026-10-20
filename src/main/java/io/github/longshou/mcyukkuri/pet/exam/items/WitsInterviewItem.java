package io.github.longshou.mcyukkuri.pet.exam.items;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.pet.BadgeGrade;
import io.github.longshou.mcyukkuri.pet.PetSkill;
import io.github.longshou.mcyukkuri.pet.exam.ExamTaskArea;
import io.github.longshou.mcyukkuri.pet.exam.ExamContext;
import io.github.longshou.mcyukkuri.pet.stage.Staging;
import io.github.longshou.mcyukkuri.registry.ModItems;
import io.github.longshou.mcyukkuri.subspecies.SubspeciesLife.Congenital;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/** Situational interview, gold only (approx. 10-12 s). 100 sensible answer; 0 silly answer. */
public final class WitsInterviewItem extends Scene {
    private boolean ok, cannotAnswer; private int q, ans;
    @Override public ExamTaskArea area() { return ExamTaskArea.PODIUM; }
    @Override public BadgeGrade minimumGrade() { return BadgeGrade.GOLD; }
    @Override public String id() { return "wits_interview"; }
    @Override public void start(ExamContext c) {
        cannotAnswer = c.candidate().subspecies().congenital() != Congenital.NORMAL;
        ok = !cannotAnswer && c.candidate().life().personality().intelligence() >= 70 && c.candidate().life().personality().selfControl() >= 60;
        passed = ok; q = 1 + c.random().nextInt(3); ans = 1 + c.random().nextInt(3);
        if (cannotAnswer) c.note("exam.mcyukkuri.log.cannot_answer"); else log(c, "wits_interview", passed);
    }
    @Override public String failureReason() { return cannotAnswer ? "cannot_answer" : null; }
    @Override boolean run(ExamContext c) {
        var y = c.candidate();
        switch (ph) {
            case 0 -> { if (podiumReady(c)) next(); }
            case 1 -> {
                if (pt == 1) { c.examinerLookAt(y); c.examinerSay(P + "wits_interview.say"); c.card(deskTop(c),
                        Component.translatable(P + "wits_interview.q." + q), 360, 1.0f); y.playAction("think"); }
                if (pt % 10 == 5) Staging.sweat(y);
                if (pt >= 70) {
                    y.playAction("idle");
                    if (cannotAnswer) { y.showReaction("confused", "cannot_answer", 70); Staging.sweat(y); }
                    else { y.speak("exam_answer"); c.card(top(y, .6), Component.translatable(P + "wits_interview." + (ok ? "good." : "silly.") + (ok ? q : ans)), 110, 1.0f); }
                    next();
                }
            }
            case 2 -> { if (pt >= 50) { if (!cannotAnswer) y.showReaction(ok ? "happy" : "confused", "", 50); next(); } }
            default -> { if (pt == 1) mark(c, passed, deskTop(c).add(0, .9, 0)); return pt >= 40; }
        }
        return false;
    }
}
