package io.github.longshou.mcyukkuri.social;

/** Pure personality queries; no experience, affinity or emotion is written here. */
public final class Temperament {
    // 待统一（D6）：hatless contempt and settlement sanctions currently differ.
    public static final int CONTEMPT_AGGRESSION = 55;
    public static final int SETTLEMENT_AGGRESSION = 65;
    public static final int HARSH_SELFISHNESS = 65;
    public static final int HARSH_SELF_CONTROL = 65;
    public static final int LENIENT_SELF_CONTROL = 65;
    public static final int LENIENT_AGGRESSION = 40;
    public static final int PROTECT_SELFISHNESS = 55;
    public static final int PROTECT_RESOLVE = 50;

    public static boolean harshForContempt(YukkuriPersonality p) {
        return p.aggression() >= CONTEMPT_AGGRESSION
                || p.selfishness() >= HARSH_SELFISHNESS && p.selfControl() < HARSH_SELF_CONTROL;
    }
    public static boolean harshForSettlement(YukkuriPersonality p) {
        return p.aggression() >= SETTLEMENT_AGGRESSION
                || p.selfishness() >= HARSH_SELFISHNESS && p.selfControl() < HARSH_SELF_CONTROL;
    }
    public static boolean lenient(YukkuriPersonality p) {
        return p.selfControl() >= LENIENT_SELF_CONTROL && p.aggression() < LENIENT_AGGRESSION;
    }
    public static boolean protects(YukkuriPersonality p) {
        return p.selfishness() < PROTECT_SELFISHNESS && p.aggression() + p.selfControl() / 3 >= PROTECT_RESOLVE;
    }
    // Additional legacy thresholds; call-site contexts are listed in Appendix A (D6).
    public static final int AGGRESSION_AT_40 = 40;
    public static final int AGGRESSION_AT_45 = 45;
    public static final int AGGRESSION_AT_50 = 50;
    public static final int AGGRESSION_AT_55 = 55;
    public static final int AGGRESSION_AT_65 = 65;
    public static final int ATTACHMENT_AT_40 = 40;
    public static final int ATTACHMENT_AT_55 = 55;
    public static final int ATTACHMENT_AT_60 = 60;
    public static final int ATTACHMENT_AT_65 = 65;
    public static final int BOLDNESS_AT_35 = 35;
    public static final int BOLDNESS_AT_40 = 40;
    public static final int BOLDNESS_AT_55 = 55;
    public static final int BOLDNESS_AT_60 = 60;
    public static final int BOLDNESS_AT_65 = 65;
    public static final int CARE_AT_55 = 55;
    public static final int CARE_AT_60 = 60;
    public static final int CURIOSITY_AT_60 = 60;
    public static final int CURIOSITY_AT_65 = 65;
    public static final int PATIENCE_AT_55 = 55;
    public static final int SELFISHNESS_AT_35 = 35;
    public static final int SELFISHNESS_AT_45 = 45;
    public static final int SELFISHNESS_AT_50 = 50;
    public static final int SELFISHNESS_AT_55 = 55;
    public static final int SELFISHNESS_AT_60 = 60;
    public static final int SELFISHNESS_AT_65 = 65;
    public static final int SELF_CONTROL_AT_30 = 30;
    public static final int SELF_CONTROL_AT_35 = 35;
    public static final int SELF_CONTROL_AT_40 = 40;
    public static final int SELF_CONTROL_AT_45 = 45;
    public static final int SELF_CONTROL_AT_50 = 50;
    public static final int SELF_CONTROL_AT_60 = 60;
    public static final int SELF_CONTROL_AT_65 = 65;
    public static final int SELF_CONTROL_AT_70 = 70;
    public static final int SOCIABILITY_AT_65 = 65;

    private Temperament() {}
}
