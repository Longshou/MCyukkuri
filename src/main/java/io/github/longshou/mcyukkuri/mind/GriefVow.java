package io.github.longshou.mcyukkuri.mind;

/** The relationship determines what is said; temperament determines how it is said. */
public final class GriefVow {
    public enum Lost { PARTNER, MOTHER, FATHER, CHILD, OTHER }

    private GriefVow() {}

    public static String event(Lost lost, boolean selfish, boolean dependants) {
        return switch (lost) {
            case PARTNER -> dependants ? "vow_partner_young" : null;
            case MOTHER -> selfish ? "vow_blame_parent" : "vow_to_mother";
            case FATHER -> selfish ? "vow_blame_parent" : "vow_to_father";
            case CHILD -> dependants ? "vow_for_child" : null;
            case OTHER -> null;
        };
    }

    public static String style(boolean attached, boolean stoic, boolean bold) {
        if (attached && !stoic) return "timid";
        return stoic || bold ? "proud" : "plain";
    }
}
