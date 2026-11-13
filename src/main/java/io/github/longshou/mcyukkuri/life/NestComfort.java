package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyLife;
import io.github.longshou.mcyukkuri.social.SocialDisposition;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.status.Identity;
import java.util.Locale;

import static io.github.longshou.mcyukkuri.social.SocialDisposition.ramp;

/**
 * How a bed's grade (CareBlock.grade: 1 straw or wild, 2 cushion or woven, 3 velvet) sits with the one lying in it, read from
 * its own values when it settles in and while it sleeps there (docs/FEATURES.md 宠物窝档次). Nothing is stored: the read is
 * the bed's grade against the individual's self-esteem (SocialDisposition.selfEsteem), unease and age.
 * <ul>
 * <li>expectation: one kept by people (pet, shop stock, owned) expects 1 + 2 R(E; 44, 60), a free one expects 1 (it knows no
 *     bought beds);</li>
 * <li>unworthiness 1 - R(E; 22, 36): one whose esteem has fallen that low (an unun-slave, years without its accessory) is ill at
 *     ease in a better bed;</li>
 * <li>anxiety R(U; 35, 80): the soft, enclosing roll of a better bed settles an anxious one faster;</li>
 * <li>a baby or an elder feels every difference 1.5 times.</li>
 * </ul>
 */
public final class NestComfort {
    private NestComfort() {}

    public record Read(int grade, double esteem, double expect, double deficit, double surplus, double unworthy, double anxiety,
                       double sensitivity) {
        /** Share of the ordinary sleep's energy return: .82-1.18 from straw to velvet, wider for babies and elders. */
        public float rest() { return (float) (1 + .12 * sensitivity * (grade - 2)); }
        /** Unease per minute while it sleeps in this bed (negative eases): softness and the anxious one's relief against
         *  hurt pride and unworthiness. */
        public float unease() {
            double soft = .4 * (grade - 1) * sensitivity + .8 * anxiety * (grade - 1) / 2;
            return (float) (-soft + .6 * deficit * sensitivity + .5 * unworthy * (grade - 1));
        }
    }

    public static Read read(YukkuriEntity e, int grade) {
        double esteem = SocialDisposition.selfEsteem(e);
        var identity = e.status().identity();
        boolean kept = !identity.free() && identity != Identity.HOUSE;
        double expect = kept ? 1 + 2 * ramp(esteem, 44, 60) : 1;
        double sensitivity = e.family().stage() == FamilyLife.Stage.BABY || e.family().elder() ? 1.5 : 1;
        return new Read(grade, esteem, expect, Math.max(0, expect - grade), Math.max(0, grade - expect),
                1 - ramp(esteem, 22, 36), ramp(e.mind().unease(), 35, 80), sensitivity);
    }

    /** What it does on lying down in its own bed. */
    public enum Settle {
        /** Nothing to remark: the ordinary good-night. */
        CONTENT(null, "", "", 0),
        /** A bed better than it hoped for: a happy shiver. */
        DELIGHTED(YukkuriAction.SHIAWASE, "happy", "mood_good", 0),
        /** An anxious one sinking into a soft roll. */
        SOOTHED(null, "closed_smile", "mood_ok", 30),
        /** Beneath it, but it lies down: puffed cheeks first. */
        GRUMBLE(YukkuriAction.PUFF, "cheek_puff", "puff", 0),
        /** Far beneath it: it puffs up and will not lie down yet. */
        REFUSE(YukkuriAction.PUFF, "cheek_puff", "request_sulk", 0),
        /** Back after refusing: it gives in with a sulk and lies down. */
        GIVE_IN(YukkuriAction.DROOP, "look_down", "mood_bad", 0),
        /** Too fine for how little it thinks of itself: it looks round, ill at ease, then lies down small. */
        UNEASY(YukkuriAction.LOOK_AROUND, "look_down", "shy", 0);

        final YukkuriAction action;
        final String face, line;
        final int faceTicks;
        Settle(YukkuriAction action, String face, String line, int faceTicks) {
            this.action = action; this.face = face; this.line = line; this.faceTicks = faceTicks;
        }
    }

    /** The chosen reaction and how long to wait before lying down (0: lie down now). */
    public record Look(Settle settle, int delay) { public boolean refused() { return settle == Settle.REFUSE; } }

    /**
     * Chooses and shows the settling reaction. A refusal stays up {@link #REFUSAL_WAIT}; the next look after a refusal gives in.
     * {@code lesson}: the owner is teaching it bedtime, so it does not refuse; at {@code energy} 20 or less it is too tired to.
     */
    public static Look settle(YukkuriEntity e, CareBlockEntity bed, boolean refusedBefore, float energy, boolean lesson) {
        var r = read(e, bed.grade());
        double pride = r.deficit() * r.sensitivity(), joy = r.surplus() * r.sensitivity(), unworthy = r.unworthy() * (r.grade() - 1) / 2;
        double roll = e.getRandom().nextDouble();
        Settle settle;
        if (refusedBefore) settle = Settle.GIVE_IN;
        else if (!lesson && energy > 20 && roll < ramp(pride, 1.0, 1.6)) settle = Settle.REFUSE;
        else if (roll < ramp(pride, .3, 1.0)) settle = Settle.GRUMBLE;
        else if (roll < ramp(unworthy, .2, .8)) settle = Settle.UNEASY;
        else if (roll < ramp(joy, .5, 1.5)) settle = Settle.DELIGHTED;
        else if (roll < ramp(r.anxiety() * (r.grade() - 1) / 2, .2, .7)) settle = Settle.SOOTHED;
        else settle = Settle.CONTENT;
        int wait = 0;
        if (settle.action != null) { if (e.showReaction(settle.action, settle.face, settle.line)) wait = (int) Math.ceil(settle.action.duration() * 20); }
        else if (settle.faceTicks > 0 && e.showReaction(settle.face, settle.line, settle.faceTicks)) wait = settle.faceTicks;
        if (settle == Settle.REFUSE) wait = REFUSAL_WAIT;
        GameplayLog.event(e, "home", "bed settle verdict={} nest={} grade={} esteem={} expect={} deficit={} surplus={} unworthy={} anxiety={} sensitivity={} energy={} roll={} wait={}",
                settle.name().toLowerCase(Locale.ROOT), bed.getBlockPos(), r.grade(), Math.round(r.esteem()), round(r.expect()),
                round(r.deficit()), round(r.surplus()), round(r.unworthy()), round(r.anxiety()), r.sensitivity(), Math.round(energy), round(roll), wait);
        return new Look(settle, wait);
    }
    /** After a refusal it stays up this long (half a minute) before it looks at the bed again and gives in. */
    public static final int REFUSAL_WAIT = 600;

    private static double round(double v) { return Math.round(v * 100) / 100.0; }
}
