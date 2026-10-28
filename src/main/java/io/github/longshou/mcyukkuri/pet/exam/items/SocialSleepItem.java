package io.github.longshou.mcyukkuri.pet.exam.items;
import io.github.longshou.mcyukkuri.pet.BadgeGrade;
import io.github.longshou.mcyukkuri.pet.PetSkill;
import io.github.longshou.mcyukkuri.pet.exam.ExamTaskArea;

import io.github.longshou.mcyukkuri.pet.exam.ExamContext;
import io.github.longshou.mcyukkuri.pet.stage.Staging;
import net.minecraft.world.phys.Vec3;

/** Lights out, then sleep on the nests for about 5 s (approx. 11 s). Fail: keep hopping and singing; a trainee wakes up angry. */
public final class SocialSleepItem extends Scene {
    private boolean ok;
    @Override public ExamTaskArea area() { return ExamTaskArea.CARE_ROOM; }
    @Override public BadgeGrade minimumGrade() { return BadgeGrade.BRONZE; }
    @Override public String id() { return "social_sleep"; }
    @Override public void start(ExamContext c) {
        ok = c.candidate().pet().lessons().knows(PetSkill.BEDTIME); passed = ok;
        c.examinerWalkTo(c.hall().examinerSpot); c.examinerSay(P + "social_sleep.say"); log(c, "social_sleep", passed);
    }
    @Override boolean run(ExamContext c) {
        var y = c.candidate(); var a = act(c, y); var nests = c.hall().nests; var tr = c.trainees();
        switch (ph) {
            case 0 -> { if (pt == 1) a.face(c.examiner()); if (pt >= 40) next(); }
            case 1 -> {
                if (pt == 1) {
                    a.face((Vec3) null);
                    if (!nests.isEmpty()) a.go(y, nests.get(0));
                    for (int i = 0; i < tr.size() && i + 1 < nests.size(); i++) act(c, tr.get(i)).go(tr.get(i), nests.get(i + 1));
                }
                if (a.done() || pt > 160) next();
            }
            case 2 -> {
                if (pt == 1) {
                    for (var m : tr) { act(c, m).face((Vec3) null); m.playAction("sleep"); m.showReaction("sleepy", "", 140); }
                    if (ok) { y.playAction("sleep"); y.showReaction("sleepy", "", 140); } else y.playAction("hop");
                }
                if (ok && pt == 20) y.speak("sleep");
                if (!ok) {
                    if (pt % 40 == 20) { y.playAction("sing"); y.showReaction("open_smile", "", 40); Staging.note(c.level(), top(y, .3)); }
                    if (pt % 40 == 0) { y.playAction("hop"); y.showReaction("open_smile", "", 40); }
                    if (pt == 50 && !tr.isEmpty()) { tr.get(0).playAction("wake"); }
                    if (pt == 70 && !tr.isEmpty()) { tr.get(0).playAction("idle"); tr.get(0).showReaction("angry", "", 60); Staging.anger(c.level(), top(tr.get(0), .2)); }
                }
                if (pt >= 110) { for (var m : tr) m.playAction("idle"); y.playAction("idle"); next(); }
            }
            default -> { return wrap(c, 35); }
        }
        return false;
    }
}
