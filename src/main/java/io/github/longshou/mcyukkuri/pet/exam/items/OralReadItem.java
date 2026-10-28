package io.github.longshou.mcyukkuri.pet.exam.items;

import io.github.longshou.mcyukkuri.pet.BadgeGrade;
import io.github.longshou.mcyukkuri.pet.PetSkill;
import io.github.longshou.mcyukkuri.pet.exam.ExamTaskArea;
import io.github.longshou.mcyukkuri.pet.exam.ExamContext;
import io.github.longshou.mcyukkuri.pet.stage.Staging;
import io.github.longshou.mcyukkuri.subspecies.SubspeciesLife.Congenital;
import net.minecraft.network.chat.Component;

/** Read one simple word aloud (approx. 8-10 s). It passes only when the concrete reading skill is known. */
public final class OralReadItem extends Scene {
    private static final String[] WORDS = {"cat", "flower", "water", "stone", "sky"};
    private boolean ok, cannotAnswer; private int word;
    @Override public ExamTaskArea area() { return ExamTaskArea.PODIUM; }
    @Override public BadgeGrade minimumGrade() { return BadgeGrade.SILVER; }
    @Override public String id() { return "oral_read"; }
    @Override public void start(ExamContext c) {
        cannotAnswer = c.candidate().subspecies().congenital() != Congenital.NORMAL;
        ok = !cannotAnswer && c.candidate().pet().lessons().knows(PetSkill.READING); passed = ok; word = c.random().nextInt(WORDS.length);
        if (cannotAnswer) c.note("exam.mcyukkuri.log.cannot_answer"); else log(c, "oral_read", passed);
    }
    @Override public String failureReason() { return cannotAnswer ? "cannot_answer" : null; }
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
                    else { y.showReaction("confused", cannotAnswer ? "cannot_answer" : "exam_unsure", 70); Staging.sweat(y); }
                    next();
                }
            }
            case 2 -> { if (pt >= 40) next(); }
            default -> { if (pt == 1) mark(c, passed, deskTop(c).add(0, .9, 0)); return pt >= 40; }
        }
        return false;
    }
}
