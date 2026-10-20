package io.github.longshou.mcyukkuri.pet.exam.items;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.pet.BadgeGrade;
import io.github.longshou.mcyukkuri.pet.PetSkill;
import io.github.longshou.mcyukkuri.pet.exam.ExamTaskArea;
import io.github.longshou.mcyukkuri.pet.exam.ExamContext;
import io.github.longshou.mcyukkuri.pet.stage.Staging;
import io.github.longshou.mcyukkuri.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/** "Stay quiet and you get a snack" (approx. 9-12 s). The trainee is served first. 100 waits and is served; 0 protests ずるい, cries, hops, no snack. */
public final class WitsPromiseItem extends Scene {
    private boolean ok; private YukkuriEntity mate;
    @Override public ExamTaskArea area() { return ExamTaskArea.PODIUM; }
    @Override public BadgeGrade minimumGrade() { return BadgeGrade.SILVER; }
    @Override public String id() { return "wits_promise"; }
    @Override public void start(ExamContext c) {
        ok = c.candidate().pet().lessons().knows(PetSkill.WAIT); passed = ok; mate = c.trainees().isEmpty() ? null : c.trainees().get(0);
        c.examinerWalkTo(c.hall().examinerSpot); c.examinerSay(P + "wits_promise.say"); log(c, "wits_promise", passed);
    }
    @Override boolean run(ExamContext c) {
        var y = c.candidate(); var a = act(c, y); var h = c.hall(); var lvl = c.level(); var p = h.promise;
        switch (ph) {
            case 0 -> {
                if (pt == 1) { a.go(y, first(p, y.blockPosition())); a.face(c.examiner());
                    if (mate != null && p.size() > 1) { act(c, mate).go(mate, p.get(1)); act(c, mate).face(c.examiner()); } }
                if (pt >= 40 && a.done() || pt > 160) next();
            }
            case 1 -> { // trainee is served first
                if (pt == 1) { c.examinerLookAt(mate != null ? mate : y); c.examinerSwing();
                    if (mate != null) { c.itemCard(top(mate, .5), new ItemStack(Items.COOKIE), 60, .5f); mate.playAction("eat"); mate.showReaction("chewing", "", 50); } }
                if (mate != null && pt == 45) { mate.playAction("shiawase"); mate.showReaction("happy", "", 50); }
                if (pt == 8) { y.playAction("tremble"); y.speak("exam_promise_wait"); }
                if (!ok && pt == 55) { y.playAction("idle"); y.showReaction("angry", "exam_promise_fail", 60); Staging.anger(lvl, top(y, .2)); }
                if (!ok && pt == 85) { y.playAction("hop"); y.showReaction("cry", "", 70); }
                if (pt >= (ok ? 80 : 130)) { if (!ok) y.playAction("idle"); next(); }
            }
            case 2 -> {
                if (pt == 1 && ok) { c.examinerLookAt(y); c.examinerSwing(); c.itemCard(top(y, .5), new ItemStack(Items.COOKIE), 60, .5f); y.playAction("eat"); y.showReaction("chewing", "", 50); }
                if (pt == 45 && ok) { y.playAction("shiawase"); y.showReaction("happy", "", 50); Staging.sparkle(lvl, top(y, .2)); }
                if (pt >= (ok ? 75 : 5)) next();
            }
            default -> { return wrap(c, 35); }
        }
        return false;
    }
}
