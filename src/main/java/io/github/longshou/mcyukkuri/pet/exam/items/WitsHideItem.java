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

/** Hide so you will not be found (approx. 10-13 s). 100 hides silently in the box; 50 hides but blurts once; 0 hides only its face and shouts (the M02 gag). */
public final class WitsHideItem extends Scene {
    private int out; private boolean entered, found; private BlockPos entrance, stopShort;
    @Override public Ability ability() { return Ability.WITS; }
    @Override public void start(ExamContext c) {
        var h = c.hall(); out = roll3(c, Ability.WITS); score = out;
        entrance = h.hideBox.relative(h.hideOpen); stopShort = h.hideBox.relative(h.hideOpen, 2);
        c.examinerSay(P + "wits_hide.say"); log(c, "wits_hide", out);
    }
    @Override boolean run(ExamContext c) {
        var y = c.candidate(); var a = act(c, y); var h = c.hall(); var ex = c.examiner();
        switch (ph) {
            case 0 -> { if (pt == 1) a.face(ex); if (pt >= 35) next(); }
            case 1 -> { // examiner turned away for ~4 s
                Vec3 away = ex.position().add(ex.position().subtract(y.position()).multiply(1, 0, 1).normalize().scale(6)).add(0, ex.getEyeHeight(), 0);
                ex.getLookControl().setLookAt(away.x, away.y, away.z);
                if (pt == 1) { a.face((Vec3) null); a.go(y, out == 0 ? stopShort : entrance); }
                if (a.done() && !entered) {
                    entered = true;
                    if (out == 0) { a.face(stand(h.hideBox).add(0, .5, 0)); y.playAction("hide_face"); y.showReaction("neutral", "exam_hide_fail", 90); }
                    else { a.go(y, h.hideBox); }
                }
                if (out == 0 && entered && pt % 45 == 0) y.speak("exam_hide_fail");
                if (out != 0 && entered && a.done() && !found) { found = true; y.playAction("idle"); if (out == 50) y.showReaction("surprise", "exam_hide_fail", 70); }
                if (pt >= 85) next();
            }
            case 2 -> {
                if (pt == 1) { c.examinerLookAt(y); if (out == 100) c.examinerWalkTo(h.room); else c.examinerWalkTo(y.blockPosition()); }
                boolean there = out == 100 ? c.examinerAt(h.room) : close(ex, y, 2.5);
                if (out == 100 && pt % 40 == 20) c.examinerLookAt(y);
                if (there && pt > 30 || pt > 110) {
                    if (out == 100) c.examinerSay(P + "wits_hide.cantfind"); else { c.examinerSwing(); c.examinerSay(P + "wits_hide.found"); }
                    next();
                }
            }
            default -> { return wrap(c, 40); }
        }
        return false;
    }
}
