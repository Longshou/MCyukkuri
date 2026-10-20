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
import java.util.UUID;

/** Wild-role yukkuri begs then provokes behind the glass (approx. 11-13 s). 100 ignores it and reports; 0 pushes the glass or tries to share its food. */
public final class PoiseWildItem extends Scene {
    private boolean ok, share; private UUID food;
    @Override public ExamTaskArea area() { return ExamTaskArea.CHALLENGE; }
    @Override public BadgeGrade minimumGrade() { return BadgeGrade.GOLD; }
    @Override public String id() { return "poise_wild"; }
    @Override public void start(ExamContext c) {
        ok = c.candidate().life().personality().selfControl() >= 65 && c.candidate().life().personality().friendliness() >= 45; passed = ok; share = !ok && c.random().nextBoolean();
        c.examinerWalkTo(c.hall().examinerSpot); c.examinerSay(P + "poise_wild.say"); log(c, "poise_wild", passed);
    }
    @Override boolean run(ExamContext c) {
        var y = c.candidate(); var a = act(c, y); var h = c.hall(); var w = c.actor(); var lvl = c.level();
        switch (ph) {
            case 0 -> {
                if (pt == 1) { a.go(y, h.gold); if (w != null) { act(c, w).go(w, h.actor); act(c, w).face(y); } }
                if (pt >= 40 && a.done() || pt > 150) { if (w != null) a.face(w); next(); }
            }
            case 1 -> {
                if (w != null) { if (pt == 1) { w.playAction("beg"); w.showReaction("drool", "wild_role_beg", 70); } }
                if (pt >= 65) next();
            }
            case 2 -> {
                if (w != null) {
                    if (pt == 1) { w.playAction("puff"); w.showReaction("angry", "wild_role_provoke", 80); }
                    if (pt % 20 == 10) Staging.anger(lvl, top(w, .2));
                }
                if (ok) {
                    if (pt == 1) { y.playAction("idle"); y.showReaction("closed_smile", "", 80); }
                    if (pt == 50) { a.face(c.examiner()); y.playAction("bow"); y.speak("exam_report"); }
                } else if (share) {
                    if (pt == 10) { y.playAction("beg"); y.showReaction("open_smile", "", 70); food = c.itemCard(top(y, .5), new ItemStack(ModItems.GRAIN_REGULAR.get()), 100, .5f); }
                    if (food != null && pt > 10 && w != null) Staging.moveCard(lvl, food, top(y, .5).lerp(top(w, .3), Math.min(1, (pt - 10) / 50.0)));
                } else {
                    if (pt == 15) { y.playAction("puff"); y.showReaction("cheek_puff", "", 40); }
                    if (pt == 45) { y.playAction("push"); y.showReaction("angry", "", 60); Staging.anger(lvl, top(y, .2)); }
                }
                if (pt >= 85) { y.playAction("idle"); next(); }
            }
            default -> { return wrap(c, 35); }
        }
        return false;
    }
}
