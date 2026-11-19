package io.github.longshou.mcyukkuri.life;

import java.util.function.BooleanSupplier;

/** Relative fullness. The saved Bladder value is the solid load carried by older worlds. */
public final class ToiletNeeds {
    public enum Kind { STOOL, URINE }

    public static final int STOOL_PORTION = 40;
    float bowel, urine;

    void ate(float nutritionGain) { bowel = Math.min(100, bowel + nutritionGain * .5f); }
    void drank() { urine = Math.min(100, urine + 2); }
    void awakeInterval() { urine = Math.min(100, urine + 1); }

    public float pressure(Kind kind) { return kind == Kind.STOOL ? bowel : urine; }

    Kind leading(boolean stoolAvailable) {
        return stoolAvailable && bowel >= urine ? Kind.STOOL : Kind.URINE;
    }

    void passStool() { bowel = Math.max(0, bowel - STOOL_PORTION); }

    /** A voluntary release spends only the selected load after its physical output succeeds. */
    boolean release(Kind kind, BooleanSupplier depositStool, BooleanSupplier emitUrine) {
        if (pressure(kind) < 40) return false;
        if (kind == Kind.STOOL) {
            if (!depositStool.getAsBoolean()) return false;
            passStool();
        } else {
            if (!emitUrine.getAsBoolean()) return false;
            dischargeUrine(urine);
        }
        return true;
    }

    float dischargeUrine(float amount) {
        float released = Math.min(urine, amount);
        urine -= released;
        return released;
    }
}
