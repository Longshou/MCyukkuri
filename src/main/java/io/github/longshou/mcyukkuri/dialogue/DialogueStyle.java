package io.github.longshou.mcyukkuri.dialogue;

import io.github.longshou.mcyukkuri.social.Temperament;
import io.github.longshou.mcyukkuri.social.Temperament.Gate;
import io.github.longshou.mcyukkuri.status.Identity;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Ordered dialogue variant preferences derived from temperament, identity, emotion and species. */
public final class DialogueStyle {
    public static List<String> preferences(Temperament t, Identity identity, boolean subdued, boolean joy, boolean angry,
                                           float unease, String species) {
        boolean isTimid = subdued || unease >= 55f || t.is(Gate.TIMID);
        boolean isCocky = !subdued && unease < 65f && (t.cocky() >= 0.60 || (t.is(Gate.HARSH) && t.is(Gate.BOLDISH)));
        boolean isPolite = !subdued && !angry && !isCocky
                && ((identity.companion() && (t.is(Gate.CLEVER) || t.is(Gate.PATIENT) || "patchouli".equals(species)))
                || ("patchouli".equals(species) && t.is(Gate.CLEVER) && !t.is(Gate.HARSH)));
        boolean isWarm = !subdued && !angry && !isCocky && t.is(Gate.WARM) && !t.is(Gate.HARSH);
        boolean isProud = joy || (!isTimid && (angry || (t.is(Gate.BOLD) && unease < 40f)));
        String idTag = identityTag(identity);

        Set<String> order = new LinkedHashSet<>();
        if (isTimid) {
            addTagged(order, idTag, "timid");
            if (isPolite) order.add("polite");
        } else if (isCocky) {
            addTagged(order, idTag, "cocky");
            addTagged(order, idTag, "proud");
        } else if (isPolite) {
            addTagged(order, idTag, "polite");
            if (isWarm) order.add("warm");
            if (joy) order.add("proud");
        } else if (isWarm) {
            addTagged(order, idTag, "warm");
            if (joy) order.add("proud");
        } else if (isProud) {
            addTagged(order, idTag, "proud");
        }
        if (idTag != null) order.add(idTag);
        order.add("plain");
        return List.copyOf(new ArrayList<>(order));
    }

    /** Legacy 3-style helper preserved for movement/claim callers. */
    public static String legacy(boolean subdued, boolean joy, Temperament t, float unease) {
        if (subdued) return "timid";
        if (joy) return "proud";
        if (unease >= 55f || t.is(Gate.TIMID)) return "timid";
        if (t.is(Gate.BOLD) && unease < 40f) return "proud";
        return "plain";
    }

    private static String identityTag(Identity identity) {
        if (identity == null) return null;
        if (identity.companion() || identity == Identity.OWNED) return "pet";
        if (identity == Identity.STRAY) return "stray";
        if (identity.wild()) return "wild";
        return null;
    }

    private static void addTagged(Set<String> order, String idTag, String style) {
        if (idTag != null) order.add(idTag + "_" + style);
        order.add(style);
    }

    private DialogueStyle() {}
}
