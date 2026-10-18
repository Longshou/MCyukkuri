package io.github.longshou.mcyukkuri.dialogue;

/**
 * Forms of address (komicolle 40720 floor 11518764): the term depends on the relation and on whether the other
 * party is well liked ("人类先生 / 大哥哥 / 大姐姐 / 饲主先生" versus "臭老太婆 / 糞老頭"). Family terms are a
 * project design (the lecture does not list them). Each method returns a dialogue event; the tier chosen by the
 * speaker's age supplies the babble, half-mature or adult wording.
 */
public final class SpeechAddress {
    public enum HumanRelation { OWNER, FRIEND, STRANGER, HOSTILE }
    public enum Kin { MOTHER, FATHER, PARTNER, CHILD, ELDER_SIBLING, YOUNGER_SIBLING, LEADER }

    public static String human(HumanRelation relation, boolean female, boolean elder) {
        return switch (relation) {
            case OWNER -> "addr_human_owner";
            case FRIEND -> female ? "addr_human_sis" : "addr_human_bro";
            case STRANGER -> "addr_human_kind";
            // Insults only fit an elder; anyone else is simply addressed as a stranger.
            case HOSTILE -> elder ? (female ? "addr_human_hostile_old" : "addr_human_hostile_man") : "addr_human_kind";
        };
    }

    public static String kin(Kin kin) {
        return switch (kin) {
            case MOTHER -> "addr_mother";
            case FATHER -> "addr_father";
            case PARTNER -> "addr_partner";
            case CHILD -> "addr_child";
            case ELDER_SIBLING -> "addr_sibling_elder";
            case YOUNGER_SIBLING -> "addr_sibling_younger";
            case LEADER -> "addr_leader";
        };
    }

    /** Address for a predator species name ({@code remilia} / {@code flandre}); {@code null} for anything else. */
    public static String predator(String species) {
        return switch (species) {
            case "remilia" -> "addr_predator_remilia";
            case "flandre" -> "addr_predator_flandre";
            default -> null;
        };
    }

    private SpeechAddress() {}
}
