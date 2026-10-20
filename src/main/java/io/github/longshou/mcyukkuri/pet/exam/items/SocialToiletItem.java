package io.github.longshou.mcyukkuri.pet.exam.items;
import io.github.longshou.mcyukkuri.pet.BadgeGrade;
import io.github.longshou.mcyukkuri.pet.PetSkill;
import io.github.longshou.mcyukkuri.pet.exam.ExamTaskArea;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
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

/** Bell, then use the toilet (approx. 8-12 s). It passes only if the concrete toilet skill is known. */
public final class SocialToiletItem extends Scene {
    private boolean ok; private BlockPos toilet, spot;
    @Override public ExamTaskArea area() { return ExamTaskArea.CARE_ROOM; }
    @Override public BadgeGrade minimumGrade() { return BadgeGrade.BRONZE; }
    @Override public String id() { return "social_toilet"; }
    @Override public void start(ExamContext c) {
        var h = c.hall(); toilet = first(h.toilets, h.examinerSpot);
        ok = c.candidate().pet().lessons().knows(PetSkill.TOILET); passed = ok;
        spot = ok ? toilet : toward(toilet, c.candidate().blockPosition(), 1.6);
        c.examinerWalkTo(h.examinerSpot); c.examinerSay(P + "social_toilet.say"); log(c, "social_toilet", passed);
        Staging.sound(c.level(), c.examiner().position(), SoundEvents.EXPERIENCE_ORB_PICKUP, 2f);
    }
    @Override boolean run(ExamContext c) {
        var y = c.candidate(); var a = act(c, y);
        switch (ph) {
            case 0 -> { if (pt == 1) a.face(c.examiner()); if (pt >= 40) next(); }
            case 1 -> {
                if (pt == 1) { a.go(y, spot); a.face(stand(toilet)); }
                if (a.done() || pt > 160) next();
            }
            case 2 -> {
                if (pt == 1) { y.playAction("brace"); y.showReaction(ok ? "chewing_closed" : "teary", "", 45); }
                if (pt >= 45) {
                    y.playAction("idle");
                    if (ok) { y.showReaction("happy", "toilet_done", 60); Staging.sparkle(c.level(), top(y, .2)); }
                    next();
                }
            }
            case 3 -> {
                if (ok) { if (pt >= 20) next(); break; }
                if (pt == 1) { a.face(c.examiner()); y.showReaction("confused", "", 60); }
                if (pt >= 50) next();
            }
            default -> { return wrap(c, 35); }
        }
        return false;
    }
}
