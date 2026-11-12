package io.github.longshou.mcyukkuri.pet.exam;

import io.github.longshou.mcyukkuri.dialogue.NativeDialogue;
import io.github.longshou.mcyukkuri.mind.Naming;
import io.github.longshou.mcyukkuri.subspecies.SubspeciesLife.Congenital;
import net.minecraft.network.chat.Component;

/** The examiner waits for the candidate's own introduction before responding. */
public final class SimpleCheckItem implements ExamItem {
    private enum Phase { APPROACH, QUESTION, ANSWER, COMMENT }
    private Phase phase = Phase.APPROACH;
    private long until;
    private Component name;
    private boolean passed;

    @Override public String id() { return "simple_check"; }
    @Override public void start(ExamContext ctx) { ctx.examinerWalkTo(ctx.candidate().blockPosition()); }

    @Override public boolean tick(ExamContext ctx) {
        var y = ctx.candidate();
        var examiner = ctx.examiner();
        long now = ctx.level().getGameTime();
        ctx.examinerLookAt(y);
        if (examiner.distanceToSqr(y) > 6.25 || !examiner.hasLineOfSight(y) || !y.hasLineOfSight(examiner)) {
            if (phase != Phase.APPROACH || ctx.itemTicks() >= 160) ctx.interrupt("site");
            return false;
        }
        switch (phase) {
            case APPROACH -> {
                examiner.getNavigation().stop();
                ctx.examinerSay("exam.mcyukkuri.say.simple_name");
                phase = Phase.QUESTION;
                until = now + 60;
            }
            case QUESTION -> {
                if (now < until || now < y.speechEnd() && now < until + 180) return false;
                int fear = y.life().personality().fear(examiner.getUUID());
                if (fear >= 50 || y.mind().unease() >= 50) {
                    y.playAction("shy");
                    ctx.answer(id(), false, "too_uneasy");
                    phase = Phase.ANSWER;
                    until = now + 60;
                    return false;
                }
                boolean deficient = y.subspecies().congenital() != Congenital.NORMAL;
                String custom = y.getCustomName() == null ? "" : y.getCustomName().getString();
                String given = Naming.ownName(y, custom) ? custom : y.mind().givenName();
                name = given == null || given.isBlank() ? y.getType().getDescription() : Component.literal(given);
                y.playAction(fear > 20 ? "shy" : "bow");
                int sequence = y.speechSequence();
                boolean spoken = y.speakCue("introduced") && y.speechSequence() != sequence;
                var line = NativeDialogue.byKey(y.getDialogueKey());
                boolean voice = spoken && (deficient ? y.getDialogueKey().equals("dialogue.mcyukkuri.deficient.babble")
                        : line != null && line.events().contains("introduced"));
                int hold = voice ? (int)Math.max(60, y.speechEnd() - now) : 60;
                var reply = deficient ? Component.translatable("exam.mcyukkuri.reply.sound")
                        : Component.translatable("exam.mcyukkuri.reply.name", name);
                var shown = ctx.card(y.position().add(0, y.getBbHeight() + .6, 0), reply, hold, 1.3f);
                passed = deficient ? voice || shown != null : shown != null;
                ctx.answer(id(), passed, deficient ? "own_sound" : name.getString());
                phase = Phase.ANSWER;
                until = now + hold;
            }
            case ANSWER -> {
                if (now < until) return false;
                if (passed) {
                    if (y.subspecies().congenital() == Congenital.NORMAL) ctx.examinerSay("exam.mcyukkuri.comment.name", name);
                    else ctx.examinerSay("exam.mcyukkuri.comment.sound");
                    ctx.examinerSwing();
                    ctx.note("exam.mcyukkuri.log.simple_ok");
                } else {
                    ctx.examinerSay("exam.mcyukkuri.comment.unheard");
                    ctx.note("exam.mcyukkuri.comment.unheard");
                }
                phase = Phase.COMMENT;
                until = now + 60;
            }
            case COMMENT -> { return now >= until; }
        }
        return false;
    }

    @Override public boolean passed() { return passed; }
}
