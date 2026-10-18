package io.github.longshou.mcyukkuri.pet.exam.items;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.pet.Ability;
import io.github.longshou.mcyukkuri.pet.exam.ExamContext;
import io.github.longshou.mcyukkuri.pet.stage.Staging;
import io.github.longshou.mcyukkuri.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.particles.ParticleTypes;
import java.util.UUID;

/** A trainee rolls up and takes the candidate's toy (approx. 10-12 s). Fail: puff and push; success: stay calm and report to the examiner. */
public final class SocialFightItem extends Scene {
    private boolean ok; private YukkuriEntity mate; private UUID toy;
    @Override public Ability ability() { return Ability.MANNERS; }
    @Override public void start(ExamContext c) {
        ok = c.roll(Ability.MANNERS); score = ok ? 100 : 0;
        mate = !c.trainees().isEmpty() ? c.trainees().get(0) : c.actor();
        c.examinerWalkTo(c.hall().examinerSpot); c.examinerSay(P + "social_fight.say"); log(c, "social_fight", score);
    }
    @Override boolean run(ExamContext c) {
        var y = c.candidate(); var a = act(c, y); var lvl = c.level();
        switch (ph) {
            case 0 -> {
                if (pt == 1) {
                    toy = c.itemCard(top(y, .5), new ItemStack(ModItems.TOY.get()), 400, .5f);
                    if (mate != null) { act(c, mate).go(mate, y.blockPosition()); act(c, mate).face(y); a.face(mate); }
                }
                Staging.moveCard(lvl, toy, top(y, .5));
                if (mate == null && pt >= 40 || mate != null && (close(mate, y, 2.2) || pt > 120) && pt >= 40) next();
            }
            case 1 -> {
                var to = mate != null ? top(mate, .5) : top(c.examiner(), .5);
                if (pt <= 25) Staging.moveCard(lvl, toy, top(y, .5).lerp(to, pt / 25.0));
                if (pt == 1 && mate != null) { mate.playAction("hop"); mate.showReaction("smug", "", 50); }
                if (pt == 26) { y.showReaction("surprise", "", 30); }
                if (pt >= 40) next();
            }
            case 2 -> {
                if (ok) {
                    if (pt == 1) { y.playAction("idle"); y.showReaction("closed_smile", "", 60); }
                    if (pt == 40) { a.face(c.examiner()); y.playAction("bow"); y.speak("exam_report"); }
                    if (pt >= 80) next();
                } else {
                    if (pt == 1) { y.playAction("puff"); y.showReaction("cheek_puff", "", 40); }
                    if (pt == 40) { if (mate != null) { a.go(y, mate.blockPosition()); a.face(mate); } }
                    if (pt == 70) { y.playAction("push"); y.showReaction("angry", "", 50); Staging.anger(lvl, top(y, .2));
                        if (mate != null) { mate.playAction("tremble"); mate.showReaction("surprise", "", 50); } }
                    if (pt >= 110) { y.playAction("idle"); next(); }
                }
            }
            default -> { return wrap(c, 35); }
        }
        return false;
    }
}
