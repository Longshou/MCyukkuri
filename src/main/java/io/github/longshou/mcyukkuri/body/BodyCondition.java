package io.github.longshou.mcyukkuri.body;

/** Server-side value snapshot. Each query preserves the existing rule for its named use. */
public record BodyCondition(int wound, int burns, int infection, int mold, int filling,
                            boolean weak, boolean dying, boolean sick, boolean footBurned) {
    // D2 is applied in W1-D, after equivalent call-site migration.
    public boolean readyForReproduction() { return wound == 0 && infection == 0; }
    public boolean readyForDelivery() { return wound == 0 && infection == 0; }
    public boolean readyForPlay() { return wound == 0; }
    public boolean readyForAffection() { return wound == 0 && infection == 0; }
    public boolean readyForGroupPlay() { return wound == 0 && infection == 0; }
    public boolean readyForWildEvent() { return wound == 0 && infection == 0; }
    public boolean readyForHouseCare() { return wound == 0 && infection == 0; }
    public boolean readyForAgitation() { return wound == 0 && infection == 0; }
    public boolean readyForFactory() { return wound == 0 && infection == 0 && mold == 0; }
    public boolean readyForAutumnStock() { return wound == 0 && burns == 0 && infection == 0 && mold == 0; }
    public boolean readyToLick() { return wound == 0 && burns == 0 && infection == 0; }
    public boolean hasSkinOrBurnDamage() { return wound > 0 || burns > 0; }
    public boolean needsPainExpression() { return wound > 0 || burns > 0 || infection > 0; }
    public boolean needsExtraRation() { return wound > 0 || infection > 0; }
    public boolean blocksBreedingApproach() { return wound > 0 || infection > 0; }
    public boolean hasSicknessPressure() { return infection > 0 || mold > 0; }
    public int painSeverity() { return Math.max(wound, burns); }
}
