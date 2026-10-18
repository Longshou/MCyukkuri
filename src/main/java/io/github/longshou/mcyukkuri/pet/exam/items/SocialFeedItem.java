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

/** Eat from your own bowl (approx. 11-14 s). Fail: grab the neighbour's grain (push, neighbour cries), or scatter it without a neighbour. */
public final class SocialFeedItem extends Scene {
    private int out; private boolean steal; private YukkuriEntity mate; private BlockPos b0, b1; private Runnable own = () -> {}, other = () -> {};
    @Override public Ability ability() { return Ability.MANNERS; }
    @Override public void start(ExamContext c) {
        var h = c.hall(); out = roll3(c, Ability.MANNERS); score = out;
        b0 = first(h.bowls, h.examinerSpot); b1 = h.bowls.size() > 1 ? h.bowls.get(1) : null;
        mate = b1 != null && !c.trainees().isEmpty() ? c.trainees().get(0) : null;
        steal = out == 0 && mate != null;
        own = grain(c, b0); if (mate != null) other = grain(c, b1);
        c.examinerWalkTo(h.examinerSpot); c.examinerSay(P + "social_feed.say"); log(c, "social_feed", out);
        act(c, c.candidate()).face(c.examiner());
    }
    @Override boolean run(ExamContext c) {
        var y = c.candidate(); var a = act(c, y); var lvl = c.level();
        switch (ph) {
            case 0 -> {
                if (pt == 1) { c.itemCard(stand(b0).add(0, .9, 0), new ItemStack(ModItems.GRAIN_REGULAR.get()), 100, .45f);
                    if (mate != null) c.itemCard(stand(b1).add(0, .9, 0), new ItemStack(ModItems.GRAIN_REGULAR.get()), 100, .45f); }
                if (pt >= 45) next();
            }
            case 1 -> {
                if (pt == 1) { a.go(y, steal ? b1 : b0); a.face((Vec3) null); if (mate != null) act(c, mate).go(mate, b1); }
                if (a.done() || pt > 160) next();
            }
            case 2 -> {
                a.face(stand(steal ? b1 : b0));
                if (pt == 1) {
                    if (mate != null) { act(c, mate).face(stand(b1)); mate.playAction("eat"); mate.showReaction("chewing", "", 70); }
                    if (steal) { y.playAction("push"); y.showReaction("angry", "", 70); Staging.anger(lvl, top(y, .2)); }
                    else if (out == 50) { y.playAction("eat"); y.showReaction("drool", "", 70); }
                    else if (out == 0) { y.playAction("idle"); y.showReaction("confused", "exam_unsure", 70); lvl.sendParticles(ParticleTypes.POOF, y.getX(),
                            y.getY() + .2, y.getZ(), 6, .3, .1, .3, .02); }
                    else { y.playAction("eat"); y.showReaction("chewing", "", 70); }
                }
                if (out == 50 && pt % 12 == 6) lvl.sendParticles(ParticleTypes.POOF, y.getX(), y.getY() + .3, y.getZ(), 3, .25, .1, .25, .02);
                if (steal && pt == 25 && mate != null) { mate.playAction("tremble"); mate.showReaction("teary", "", 60); other.run(); }
                if (steal && pt == 45 && mate != null) mate.showReaction("cry", "", 40);
                if (pt == 60) { if (out == 100 || out == 50) own.run(); }
                if (pt >= 70) { y.playAction("idle"); if (out == 100) { y.showReaction("happy", "", 40); Staging.sparkle(lvl, top(y, .2)); } next(); }
            }
            default -> { return wrap(c, 35); }
        }
        return false;
    }
}
