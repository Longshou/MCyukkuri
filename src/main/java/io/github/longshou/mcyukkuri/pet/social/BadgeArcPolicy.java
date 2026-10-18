package io.github.longshou.mcyukkuri.pet.social;

import io.github.longshou.mcyukkuri.social.BondLevels;
import io.github.longshou.mcyukkuri.social.Temperament;
import java.util.ArrayList;
import java.util.List;

/** Pure decision: given plain numbers about the observer, the subject, what the observer remembers and the surroundings, which arc (if any) does
 *  one sighting turn into. No Minecraft types, so every branch can be checked without a world. Most sightings are silent records. */
public final class BadgeArcPolicy {
    public enum Arc {
        GLANCE("glance", 1), ENVY("envy", 1), BOAST("boast", 1), BEG_BITE("beg_bite", 2), PROVOKE("provoke", 2), PLEA("plea", 2),
        PITY("pity", 1), ELDER_WARN("elder_warn", 1), HOME_CLAIM("home_claim", 2), SNATCH("snatch", 2);
        public final String id;
        /** Budget tokens: 1 = one-liner scene, 2 = multi-beat arc. */
        public final int cost;
        Arc(String id, int cost) { this.id = id; this.cost = cost; }
        public static Arc byId(String id) { for (var a : values()) if (a.id.equals(id)) return a; return null; }
    }

    /** Dispositions and needs are 0..100; grade is 0 none .. 3 gold. A wild observer never compares ranks ({@code ownGrade} is then 0). */
    public record Observer(boolean pet, boolean child, boolean elder, boolean childNear, int curiosity, int boldness, int selfishness, int aggression,
                           int attachment, int selfControl, int nutrition, int stress, int ownGrade, int sizePercent) {}
    public record Subject(int grade, boolean pet, boolean eating, boolean ownerNear, boolean trustedPlayerNear, boolean homeNear, boolean aloneFromOwner,
                          boolean hasAccessory, int sizePercent) {}
    /** What the observer remembers (ledger) plus the directed affinity. {@code cooling}: an arc with this subject played within the last game day. */
    public record Relation(int affinity, boolean related, boolean first, boolean changed, boolean lapsed, boolean cooling, int impression, int flags, int seenCount) {}
    public record Context(boolean playerNear, boolean waryRumour, boolean kindRumour, boolean snatchEnabled, boolean homeClaimEnabled) {}
    public record Candidate(Arc arc, String variant, double probability) {}
    public record Choice(Arc arc, String variant) {
        public static final Choice SILENT = new Choice(null, "");
        public boolean silent() { return arc == null; }
    }

    private static final double CAP = .9;

    private BadgeArcPolicy() {}

    private static double clamp01(double v) { return Math.max(0, Math.min(1, v)); }

    /** Every arc the situation allows with its own probability (unscaled). Empty means a silent record. */
    public static List<Candidate> candidates(Observer o, Subject s, Relation r, Context c) {
        List<Candidate> out = new ArrayList<>();
        if (r.lapsed()) {
            // The badge the observer remembers is gone (accessory lost, registry void or lapsed).
            if (o.selfishness() >= Temperament.SELFISHNESS_AT_65) out.add(new Candidate(Arc.PITY, "scorn", .45));
            else out.add(new Candidate(Arc.PITY, "pity", o.selfishness() < Temperament.SELFISHNESS_AT_50 ? .6 : .15));
            return out;
        }
        if (r.cooling()) return out;
        double nov = r.first() ? 1 : r.changed() ? .8 : .4;
        boolean novel = r.first() || r.changed();
        boolean hungry = o.nutrition() < 50, grudge = (r.flags() & (BadgeLedger.ENVIED | BadgeLedger.SNUBBED)) != 0 || r.impression() <= -1 || r.affinity() < BondLevels.AFFINITY_NEUTRAL;
        if (s.grade() > 0 && s.pet() && s.ownerNear() && o.elder() && o.childNear() && !o.pet() && novel)
            out.add(new Candidate(Arc.ELDER_WARN, c.kindRumour() && !c.waryRumour() ? "kind" : "warn", clamp01(.35 * (c.waryRumour() ? 1.5 : 1))));
        if (c.snatchEnabled() && !o.pet() && s.hasAccessory() && s.aloneFromOwner() && s.grade() > 0 && o.selfishness() >= Temperament.SELFISHNESS_AT_65
                && o.selfControl() < Temperament.SELF_CONTROL_AT_40
                && r.seenCount() >= 2 && (r.flags() & (BadgeLedger.ENVIED | BadgeLedger.MOCKED)) != 0 && !r.related())
            out.add(new Candidate(Arc.SNATCH, "", .12));
        if (c.homeClaimEnabled() && !o.pet() && s.pet() && s.homeNear() && o.selfishness() >= Temperament.SELFISHNESS_AT_55
                && o.aggression() >= Temperament.AGGRESSION_AT_45 && o.nutrition() < 60 && !r.related())
            out.add(new Candidate(Arc.HOME_CLAIM, "", .2));
        if (!o.pet() && s.eating() && s.grade() > 0 && o.nutrition() >= 30 && o.nutrition() < 50 && o.boldness() < Temperament.BOLDNESS_AT_70)
            out.add(new Candidate(Arc.BEG_BITE, "", clamp01(.45 + ((r.flags() & BadgeLedger.FED_BY) != 0 ? .15 : 0) - ((r.flags() & BadgeLedger.SNUBBED) != 0 ? .2 : 0))));
        if (!o.pet() && s.grade() > 0) {
            if (s.trustedPlayerNear()) out.add(new Candidate(Arc.PLEA, "", clamp01(.4 * (c.waryRumour() ? .3 : c.kindRumour() ? 1.3 : 1))));
            else if (c.playerNear() && (r.flags() & (BadgeLedger.ADMIRED | BadgeLedger.FED_BY)) != 0 && o.attachment() >= Temperament.ATTACHMENT_AT_55)
                out.add(new Candidate(Arc.PLEA, "", .12 * (c.waryRumour() ? .3 : 1)));
        }
        if (!o.pet() && grudge && o.aggression() >= Temperament.AGGRESSION_AT_55 && o.selfishness() >= Temperament.SELFISHNESS_AT_50
                && o.selfControl() < Temperament.SELF_CONTROL_AT_60 && s.grade() > 0 && !r.related()
                && o.nutrition() >= 30 && o.sizePercent() * 10 >= s.sizePercent() * 9)
            out.add(new Candidate(Arc.PROVOKE, "", clamp01(.3 + ((r.flags() & BadgeLedger.SNUBBED) != 0 ? .15 : 0))));
        if (!o.pet() && s.grade() > 0 && novel)
            out.add(new Candidate(Arc.GLANCE, "", clamp01(nov * (.1 + .002 * o.curiosity() + .001 * (100 - o.boldness()) + (o.child() ? .12 : 0)))));
        boolean above = o.pet() ? s.grade() > o.ownGrade() : s.grade() > 0;
        if (above && novel) {
            double p = .03 + .002 * (o.selfishness() - 40) + (hungry ? .1 : 0) + (o.stress() >= BondLevels.UNEASE_CARE_LIMIT ? .06 : 0) + ((r.flags() & BadgeLedger.ENVIED) != 0 ? .08 : 0);
            if (p > .01) out.add(new Candidate(Arc.ENVY, "", clamp01(nov * p * (1 - o.attachment() / 200.0))));
        }
        if (o.pet() && o.ownGrade() > s.grade() && novel) {
            boolean kind = o.selfishness() < Temperament.SELFISHNESS_AT_45;
            boolean proud = o.boldness() >= Temperament.BOLDNESS_AT_55 && o.selfishness() >= Temperament.SELFISHNESS_AT_45;
            out.add(new Candidate(Arc.BOAST, kind ? "humble" : "boast", nov * (proud ? .28 : kind ? .2 : .08)));
        }
        return out;
    }

    /** One roll in [0,1) decides everything: the candidates share a cumulative scale (rescaled down if their sum exceeds .9). */
    public static Choice choose(Observer o, Subject s, Relation r, Context c, double roll) {
        var list = candidates(o, s, r, c);
        if (list.isEmpty()) return Choice.SILENT;
        double sum = 0; for (var cand : list) sum += cand.probability();
        double scale = sum > CAP ? CAP / sum : 1, at = 0;
        for (var cand : list) {
            at += cand.probability() * scale;
            if (roll < at) return new Choice(cand.arc(), cand.variant());
        }
        return Choice.SILENT;
    }
}
