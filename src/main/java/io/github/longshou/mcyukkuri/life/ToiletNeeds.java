package io.github.longshou.mcyukkuri.life;

/** Relative fullness. The saved Bladder value is the solid load carried by older worlds. */
public final class ToiletNeeds {
    public enum Kind { STOOL, URINE }

    float bowel, urine;

    void ate(float nutritionGain) { bowel = Math.min(100, bowel + nutritionGain * .5f); }
    void drank() { urine = Math.min(100, urine + 2); }
    void awakeInterval() { urine = Math.min(100, urine + 1); }

    public float pressure(Kind kind) { return kind == Kind.STOOL ? bowel : urine; }

    Kind leading(boolean stoolAvailable) {
        return stoolAvailable && bowel >= urine ? Kind.STOOL : Kind.URINE;
    }

    float dischargeUrine(float amount) {
        float released = Math.min(urine, amount);
        urine -= released;
        return released;
    }
}
