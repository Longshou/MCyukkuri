package io.github.longshou.mcyukkuri.pet.exam;

import io.github.longshou.mcyukkuri.pet.BadgeGrade;
import io.github.longshou.mcyukkuri.pet.stage.Staging;
import net.minecraft.world.phys.Vec3;

/** Simple exam (about 10 s): the examiner walks over, looks the animal over, and asks its name. Passes unless it is badly hurt, starving or burning. */
public final class SimpleCheckItem implements ExamItem {
    private int phase, t;
    private boolean passed = true;
    @Override public ExamTaskArea area() { return ExamTaskArea.CARE_ROOM; }
    @Override public BadgeGrade minimumGrade() { return BadgeGrade.BRONZE; }
    @Override public String id() { return "simple_check"; }
    @Override public void start(ExamContext ctx) { phase = t = 0; ctx.examinerWalkTo(ctx.candidate().blockPosition()); }
    @Override public boolean tick(ExamContext ctx) {
        var c = ctx.candidate(); var ex = ctx.examiner(); ctx.examinerLookAt(c); t++;
        switch (phase) {
            case 0 -> { if (ex.distanceToSqr(c) <= 6.25 || ctx.itemTicks() > 160) { phase = 1; t = 0; ctx.examinerSay("exam.mcyukkuri.say.simple_look"); ctx.examinerSwing(); } }
            case 1 -> {
                if (t % 12 == 0) Staging.sparkle(ctx.level(), new Vec3(c.getX(), c.getY() + c.getBbHeight() * .6, c.getZ()));
                if (t == 36) ctx.examinerSwing();
                if (t >= 60) {
                    phase = 2; t = 0; ctx.examinerSay("exam.mcyukkuri.say.simple_name");
                    if (ctx.random().nextBoolean()) c.playAction("greet"); else c.playAction("bow");
                    c.speak("hello");
                }
            }
            default -> {
                if (t < 40) return false;
                if (c.getHealth() < c.getMaxHealth() * .3f) { passed = false; ctx.note("exam.mcyukkuri.log.simple_hurt"); }
                else if (c.life().nutrition() < 10) { passed = false; ctx.note("exam.mcyukkuri.log.simple_hungry"); }
                else if (c.isOnFire()) { passed = false; ctx.note("exam.mcyukkuri.log.simple_fire"); }
                else ctx.note("exam.mcyukkuri.log.simple_ok");
                return true;
            }
        }
        return false;
    }
    @Override public boolean passed() { return passed; }
}
