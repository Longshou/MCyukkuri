package io.github.longshou.mcyukkuri.pet.exam.items;

import io.github.longshou.mcyukkuri.pet.BadgeGrade;
import io.github.longshou.mcyukkuri.pet.PetSkill;
import io.github.longshou.mcyukkuri.pet.exam.ExamTaskArea;
import io.github.longshou.mcyukkuri.pet.exam.ExamContext;
import io.github.longshou.mcyukkuri.pet.stage.Staging;
import io.github.longshou.mcyukkuri.subspecies.SubspeciesLife.Congenital;
import net.minecraft.network.chat.Component;

/** Oral arithmetic at the podium (approx. 9-12 s). It passes only when the concrete arithmetic skill is known. */
public final class OralSumItem extends Scene {
    private int a, b, answer, shown; private boolean sub, ok, cannotAnswer;
    @Override public ExamTaskArea area() { return ExamTaskArea.PODIUM; }
    @Override public BadgeGrade minimumGrade() { return BadgeGrade.SILVER; }
    @Override public String id() { return "oral_sum"; }
    @Override public void start(ExamContext c) {
        cannotAnswer = c.candidate().subspecies().congenital() != Congenital.NORMAL;
        ok = !cannotAnswer && c.candidate().pet().lessons().knows(PetSkill.ARITHMETIC); passed = ok;
        int max = c.tier().id() <= BadgeGrade.SILVER.id() ? 5 : 10; var r = c.random();
        a = 1 + r.nextInt(max); b = 1 + r.nextInt(max); sub = r.nextInt(4) == 0;
        if (sub && b > a) { int x = a; a = b; b = x; }
        answer = sub ? a - b : a + b; shown = answer;
        if (!ok && !cannotAnswer) { do shown = answer + (r.nextBoolean() ? 1 : -1) * (1 + r.nextInt(2)); while (shown < 0 || shown == answer); }
        if (cannotAnswer) c.note("exam.mcyukkuri.log.cannot_answer"); else log(c, "oral_sum", passed);
    }
    @Override public String failureReason() { return cannotAnswer ? "cannot_answer" : null; }
    @Override boolean run(ExamContext c) {
        var y = c.candidate(); var lvl = c.level();
        switch (ph) {
            case 0 -> { if (podiumReady(c)) next(); }
            case 1 -> {
                if (pt == 1) {
                    c.examinerLookAt(y); c.examinerSay(P + "oral_sum.say");
                    c.card(deskTop(c), Component.translatable(sub ? "pet.mcyukkuri.card.diff" : "pet.mcyukkuri.card.sum", a, b), 400, 1.4f);
                    y.playAction("think");
                }
                if (pt % 10 == 5) Staging.sweat(y);
                if (pt >= 50) {
                    y.playAction("idle");
                    if (cannotAnswer) { y.showReaction("confused", "cannot_answer", 70); Staging.sweat(y); }
                    else { y.speak("exam_answer"); c.card(top(y, .55), Component.translatable("pet.mcyukkuri.card.answer", shown), 80, 1.3f); }
                    next();
                }
            }
            case 2 -> { if (pt >= 35) { y.showReaction(ok ? "happy" : "confused", cannotAnswer ? "cannot_answer" : "", 50); next(); } }
            default -> { if (pt == 1) mark(c, passed, deskTop(c).add(0, .9, 0)); return pt >= 40; }
        }
        return false;
    }
}
