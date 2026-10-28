package io.github.longshou.mcyukkuri.pet.exam.items;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.pet.BadgeGrade;
import io.github.longshou.mcyukkuri.pet.exam.ExamTaskArea;
import io.github.longshou.mcyukkuri.pet.exam.ExamContext;
import io.github.longshou.mcyukkuri.pet.stage.Staging;

/** A lost crying baby near the candidate (approx. 10-13 s). 100 comforts it (nuzzle) and reports; 0 ignores or pushes it. */
public final class PoiseTroubleItem extends Scene {
    private boolean ok, push, real; private YukkuriEntity baby;
    @Override public ExamTaskArea area() { return ExamTaskArea.CHALLENGE; }
    @Override public BadgeGrade minimumGrade() { return BadgeGrade.GOLD; }
    @Override public String id() { return "poise_trouble"; }
    @Override public void start(ExamContext c) {
        ok = c.candidate().life().personality().friendliness() >= 55 && c.candidate().life().personality().selfControl() >= 50; passed = ok; push = !ok && c.random().nextBoolean();
        baby = !c.trainees().isEmpty() ? c.trainees().get(0) : c.actor(); real = baby != null && !c.trainees().isEmpty();
        c.examinerWalkTo(c.hall().examinerSpot); c.examinerSay(P + "poise_trouble.say"); log(c, "poise_trouble", passed);
    }
    @Override boolean run(ExamContext c) {
        var y = c.candidate(); var a = act(c, y); var h = c.hall(); var lvl = c.level();
        switch (ph) {
            case 0 -> {
                if (pt == 1) { a.go(y, h.gold); if (baby != null) { var b = act(c, baby); if (real) b.go(baby, toward(h.gold, h.examinerSpot, 2.5)); else b.go(baby, h.actor); } }
                if (pt >= 40 && a.done() || pt > 150) next();
            }
            case 1 -> {
                if (baby != null) { act(c, baby).face(y); if (pt == 1) { baby.playAction("tremble"); baby.showReaction("cry", "lost_parent",
                        100); } if (pt == 50) baby.showReaction("cry", "lost_parent", 80); }
                a.face(baby != null ? baby : c.examiner());
                if (pt >= 50) next();
            }
            case 2 -> {
                if (ok) {
                    if (pt == 1 && real) a.go(y, baby.blockPosition());
                    if (pt >= 30 && (!real || a.done()) || pt > 100) {
                        y.playAction("nuzzle"); y.speak("nuzzle"); Staging.sparkle(lvl, top(y, .2));
                        if (baby != null) baby.showReaction("happy", "", 50);
                        next();
                    }
                } else {
                    if (pt == 1) { if (push && real) a.go(y, baby.blockPosition()); else { a.face(c.examiner()); y.showReaction("neutral", "", 60); } }
                    if (pt >= 30 && (!(push && real) || a.done()) || pt > 100) {
                        if (push) { y.playAction("push"); y.showReaction("angry", "", 50); Staging.anger(lvl, top(y, .2)); if (baby != null) baby.showReaction("teary", "", 50); }
                        next();
                    }
                }
            }
            case 3 -> {
                if (pt == 1) { a.face(c.examiner()); if (baby != null && !ok) baby.playAction("tremble"); }
                if (pt == 30 && ok) { y.playAction("bow"); y.speak("exam_report"); }
                if (pt >= (ok ? 70 : 45)) { y.playAction("idle"); next(); }
            }
            default -> { return wrap(c, 35); }
        }
        return false;
    }
}
