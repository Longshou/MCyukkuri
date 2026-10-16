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

/** Oral arithmetic at the podium (approx. 9-12 s). 100 correct; 50 correct after a long hesitation; 0 off by 1-2. */
public final class OralSumItem extends Scene {
    private int out, a, b, answer, shown; private boolean sub;
    @Override public Ability ability() { return Ability.ACADEMICS; }
    @Override public void start(ExamContext c) {
        out = roll3(c, Ability.ACADEMICS); score = out;
        int max = c.tier().id() <= BadgeGrade.SILVER.id() ? 5 : 10; var r = c.random();
        a = 1 + r.nextInt(max); b = 1 + r.nextInt(max); sub = r.nextInt(4) == 0;
        if (sub && b > a) { int x = a; a = b; b = x; }
        answer = sub ? a - b : a + b; shown = answer;
        if (out == 0) { do shown = answer + (r.nextBoolean() ? 1 : -1) * (1 + r.nextInt(2)); while (shown < 0 || shown == answer); }
        log(c, "oral_sum", out);
    }
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
                if (out == 50 && pt == 60) y.showReaction("confused", "exam_unsure", 50);
                if (pt >= (out == 50 ? 110 : 50)) {
                    y.playAction("idle"); y.speak("exam_answer");
                    c.card(top(y, .55), Component.translatable("pet.mcyukkuri.card.answer", shown), 80, 1.3f);
                    next();
                }
            }
            case 2 -> { if (pt >= 35) { y.showReaction(out > 0 ? "happy" : "confused", "", 50); next(); } }
            default -> { if (pt == 1) mark(c, score, deskTop(c).add(0, .9, 0)); return pt >= 40; }
        }
        return false;
    }
}
