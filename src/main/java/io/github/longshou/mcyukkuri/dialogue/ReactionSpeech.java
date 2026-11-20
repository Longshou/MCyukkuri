package io.github.longshou.mcyukkuri.dialogue;

import io.github.longshou.mcyukkuri.mind.Ousting.Answer;
import io.github.longshou.mcyukkuri.status.Identity;
import io.github.longshou.mcyukkuri.status.StruckStance;

/** The words available for an already chosen response. An empty event leaves the response silent. */
public final class ReactionSpeech {
    public static String struck(StruckStance stance, boolean hurt, boolean trustedKeeper, boolean willStrike) {
        return switch (stance) {
            case FLEE -> hurt ? "hurt_flee" : "afraid";
            case FREEZE -> "hurt_freeze";
            case PUFF_FIGHT -> willStrike && hurt ? "hurt_defy" : "puff";
            case PLEAD -> !hurt ? "plea_spare" : trustedKeeper ? "hurt_tame_plea" : "hurt_plea";
            case ENDURE, SEAL -> hurt ? "hurt_endure" : "";
            case LEAD_AWAY -> "";
        };
    }

    public static boolean trustsCareFrom(Identity identity, boolean trustedKeeper) {
        return trustedKeeper && (identity.companion() || identity == Identity.OWNED || identity == Identity.STOCK);
    }

    public static String leadTaunt(boolean dependants) { return dependants ? "protect_taunt" : "puff"; }

    /** The scorned one's hurt: it wants to belong when the scorner is its own group, plain hurt when it is a stranger. */
    public static String contempt(boolean byOwnGroup) { return byOwnGroup ? "settlement_hurt" : "scorn_hurt"; }

    /** Only a weakened animal is too tired to do more than say no to the basket. */
    public static String basketRefusal(boolean weary) { return weary ? "basket_refuse_weary" : "basket_refuse"; }

    public static String ousted(Answer answer, boolean byGuardian) {
        return switch (answer) {
            case CURSE -> byGuardian ? "child_curse_parent" : "driven_curse";
            case FAREWELL -> "driven_farewell";
            case SHRUG -> "";
        };
    }

    /** Ranks are the badges actually worn at delivery. A human or a missing addressee has no rank. */
    public static String addressed(String event, int ownRank, int otherRank, Identity otherIdentity) {
        return switch (event) {
            case "badge_envy", "badge_envy_snark" -> otherRank > ownRank ? event : "";
            case "badge_admire", "badge_glance", "badge_ask", "badge_pull_back" -> otherRank > 0 ? event : "";
            case "badge_proud", "badge_show_kind" -> ownRank > 0 ? event : "";
            case "badge_pity" -> otherIdentity != null && otherRank == 0 ? event : "";
            case "badge_retort" -> otherIdentity != null && otherIdentity.free() ? event : "puff";
            case "badge_mock" -> otherIdentity != null && otherIdentity.companion() ? event : "";
            default -> event;
        };
    }

    private ReactionSpeech() {}
}
