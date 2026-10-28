package io.github.longshou.mcyukkuri.social;

/** Pure five-axis queries and behavior thresholds; this class never owns personality state. */
public final class Temperament {
    public static final int HARSH_FRIENDLINESS = 45;
    public static final int PROTECT_FRIENDLINESS = 55;
    public static final int LENIENT_FRIENDLINESS = 60;
    public static final int LENIENT_SELF_CONTROL = 65;

    public static boolean harsh(YukkuriPersonality personality) {
        return personality.friendliness() < HARSH_FRIENDLINESS;
    }
    public static boolean harshForContempt(YukkuriPersonality personality) { return harsh(personality); }
    public static boolean harshForSettlement(YukkuriPersonality personality) { return harsh(personality); }
    public static boolean lenient(YukkuriPersonality personality) {
        return personality.selfControl() >= LENIENT_SELF_CONTROL && personality.friendliness() >= LENIENT_FRIENDLINESS;
    }
    public static boolean protects(YukkuriPersonality personality) {
        return personality.friendliness() >= PROTECT_FRIENDLINESS;
    }

    public static final int ATTACHMENT_AT_55 = 55;
    public static final int ATTACHMENT_AT_65 = 65;
    public static final int BOLDNESS_AT_35 = 35;
    public static final int BOLDNESS_AT_40 = 40;
    public static final int BOLDNESS_AT_55 = 55;
    public static final int BOLDNESS_AT_60 = 60;
    public static final int BOLDNESS_AT_65 = 65;
    public static final int CARE_AT_60 = 60;
    public static final int SELF_CONTROL_AT_35 = 35;
    public static final int SELF_CONTROL_AT_45 = 45;
    public static final int SELF_CONTROL_AT_50 = 50;
    public static final int SELF_CONTROL_AT_60 = 60;
    public static final int SELF_CONTROL_AT_65 = 65;
    public static final int SELF_CONTROL_AT_70 = 70;
    public static final int SOCIABILITY_AT_65 = 65;

    private Temperament() {}
}
