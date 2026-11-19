package io.github.longshou.mcyukkuri.status;

import io.github.longshou.mcyukkuri.social.Temperament;
import java.util.Optional;

public enum StruckStance {
    FLEE, FREEZE, PUFF_FIGHT, PLEAD, ENDURE, LEAD_AWAY, SEAL;

    public record Inputs(Temperament temperament,
                         double unease, int fearAfter, double healthRatio, double loss, double maxHealth,
                         Identity identity, boolean baby, int dependantsNearby, boolean exitOpen,
                         boolean trustedOwner, boolean canFight, boolean wasFrozen) {
        private double paralysis() {
            return (1 - temperament.boldness() / 100.) * (.35 + .65 * unease / 100) * (1 - .70 * temperament.selfControl() / 100);
        }

        public int freezeTicks() { return 40 + (int) Math.round(60 * paralysis()); }
    }

    public static boolean breaksFreeze(int elapsed, double distanceSqr, double initialDistanceSqr, boolean exitOpen) {
        return elapsed >= 6 && exitOpen && distanceSqr < Math.min(9, initialDistanceSqr * .55);
    }

    /** Family tactics may take precedence when the household can act on the threat. */
    private static Optional<StruckStance> familyResponse(Inputs i) { return Optional.empty(); }

    public static StruckStance decide(Inputs i) {
        var family = familyResponse(i);
        if (family.isPresent()) return family.get();
        var t = i.temperament();
        double b = t.boldness() / 100., sc = t.selfControl() / 100., intel = t.intelligence() / 100.;
        double fr = t.friendliness() / 100., att = t.attachment() / 100., un = i.unease / 100.;
        double wary = Math.clamp((i.fearAfter - 10.) / 45, 0, 1);
        double pain = Math.clamp(i.loss / Math.max(4, i.maxHealth * .35), .18, 1);
        double instinct = Math.clamp(.58 * (1 - .55 * wary) + .42 * pain + .35 * wary, 0, 1);
        double weak = 1 - i.healthRatio, paralysis = i.paralysis();
        double gesu = Math.clamp((b - .5) * 1.1 + (.45 - fr) * .9, 0, 1);
        double tame = i.identity.companion() || i.identity == Identity.STOCK ? 1 : i.identity == Identity.STRAY ? .35 : 0;
        boolean soft = i.trustedOwner && i.loss <= 1.5;
        double dep = Math.min(1, i.dependantsNearby * .5);

        double flee = i.exitOpen ? (.54 * instinct + .20 * (1 - b) + .16 * intel + .18 * wary + .14 * weak)
                * (1 - .52 * paralysis) * (soft ? .40 : 1) + (i.wasFrozen ? .35 : 0) : 0;
        double freeze = i.wasFrozen ? 0 : i.exitOpen ? (i.baby ? 1.18 : .95) * paralysis * (1 - .30 * intel)
                : (.28 + .44 * (1 - b) + .28 * sc + .18 * un) * (1 - .55 * weak);
        // An actual first human blow gives even a bold adult 19 fear. Repeated blows erode its nerve.
        double cowed = Math.clamp((i.fearAfter - 19.) / 16, 0, 1);
        double drive = Math.clamp((t.boldness() + 25 * dep - 45.) / 27, 0, 1);
        double fight = i.canFight && !i.baby && i.healthRatio > .45
                ? Math.max(0, .54 * b + .26 * gesu + .30 * dep + .14 * (1 - un) - .22 * tame * (1 - dep))
                * (1 - .65 * cowed) * (.55 + .45 * i.healthRatio) * drive : 0;
        double plead = !i.baby && gesu < .30 && (tame > 0 || !i.exitOpen || fr >= .65)
                ? Math.max(tame, .60 * fr + .40 * att) * (.42 * un + .48 * weak + .28 * (1 - sc) + (soft ? .32 : 0))
                * (!i.exitOpen ? 1.85 : soft ? 1.05 : .45) : 0;
        double endure = i.baby ? 0 : Math.max(0, .28 + .54 * sc - .30 * un - .28 * weak)
                * (!i.exitOpen ? .82 : soft ? .95 : .30);

        double best = Math.max(Math.max(flee, freeze), Math.max(fight, Math.max(plead, endure)));
        if (best > 0 && best == flee) return FLEE;
        if (best > 0 && best == freeze) return FREEZE;
        if (best > 0 && best == fight) return PUFF_FIGHT;
        if (best > 0 && best == plead) return PLEAD;
        return ENDURE;
    }
}
