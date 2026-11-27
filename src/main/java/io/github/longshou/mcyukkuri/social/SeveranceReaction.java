package io.github.longshou.mcyukkuri.social;

import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.mind.Ousting.Answer;
import io.github.longshou.mcyukkuri.social.Temperament.Gate;

/**
 * Pure stat-driven face, clip, style and dialogue-event choices for overload/guardian severance (docs/features/aquatic-marisa.md
 * 超载断亲): both the severed child's opening/follow-up beats and each witnessing sibling's stance. Kept free of Minecraft types
 * so every branch is directly unit-tested.
 */
public final class SeveranceReaction {
    private SeveranceReaction() {}

    /** One beat of the severed child's opening response: beat id for [choreo], declared face, optional clip, dialogue event id. */
    public record ChildBeat(String beat, String face, YukkuriAction clip, String line) {}

    /** How a witnessing sibling takes the severance, chosen from its own five traits. None of its faces is ever a plain smile. */
    public enum SiblingStance {
        /** Attached or caring sibling: shocked, then cries after the one leaving and enters PARTING. */
        SORROW("surprise", "teary", "cry", null, "sibling_sever_cry"),
        /** Bold and caring sibling: shocked, then protests against the parent's order and enters PARTING. */
        PROTEST("surprise", "teary_frown", "teary_frown", null, "sibling_sever_protest"),
        /** Timid sibling: afraid from the verdict on, trembling lest it be next. */
        SCARED("afraid", "afraid", "afraid", YukkuriAction.TREMBLE, "sibling_sever_scared"),
        /** Harsh, unattached sibling: glares coldly and flicks away, never smiles or cries. */
        COLD("down_angry", "down_angry", "angry_stare", YukkuriAction.MOOD_FLICK, "sibling_sever_cold"),
        /** Detached sibling: uneasy and tight-lipped, never smiles or cries. */
        UNEASY("confused_small", "pursed", "pursed", null, "sibling_sever_uneasy");

        private final String verdictFace, rebuffFace, turnFace, turnLine;
        private final YukkuriAction turnClip;

        SiblingStance(String verdictFace, String rebuffFace, String turnFace, YukkuriAction turnClip, String turnLine) {
            this.verdictFace = verdictFace;
            this.rebuffFace = rebuffFace;
            this.turnFace = turnFace;
            this.turnClip = turnClip;
            this.turnLine = turnLine;
        }

        public String verdictFace() { return verdictFace; }
        public String rebuffFace() { return rebuffFace; }
        public String turnFace() { return turnFace; }
        public YukkuriAction turnClip() { return turnClip; }
        public String turnLine() { return turnLine; }

        /** Whether this sibling grieves with a PARTING course once the driven child is gone (cold/detached ones do not cry). */
        public boolean partsWithGrief(Temperament t) {
            return switch (this) {
                case SORROW, PROTEST -> true;
                case SCARED -> t.is(Gate.ATTACHED) || t.is(Gate.WARM);
                case COLD, UNEASY -> false;
            };
        }

        /** The face a non-grieving sibling settles into once the driven child is gone (never a smile or a tear). */
        public String detachedAfterFace() {
            return this == COLD ? "hmph" : "pursed";
        }
    }

    /** Immediate non-speaking face the targeted child adopts as soon as its parent starts pronouncing severance (overrides any
     *  lingering post-capsize tearful face on an aloof child, and shows shock on attached/timid ones). */
    public static String childListenFace(Temperament t, boolean resists, Answer answer) {
        if (answer == Answer.SHRUG) return "flick_dull";
        return resists ? "angry" : "surprise";
    }

    /** First spoken beat of a severed child facing its parent: angry protest, cold scoff (zero tears), or stunned shock. */
    public static ChildBeat childOpening(Temperament t, boolean resists, boolean pleads, Answer answer) {
        if (resists) return new ChildBeat("resist", "angry", YukkuriAction.STAND_GROUND, "sever_protest");
        if (answer == Answer.SHRUG) return new ChildBeat("scoff", "down_angry", YukkuriAction.MOOD_FLICK, "sever_cold_scoff");
        return new ChildBeat("shock", "surprise", YukkuriAction.STARTLE, "sever_shock");
    }

    /** Second spoken beat after {@code shock} for a pleading or stunned-freezing child (not used for protest or aloof scoff). */
    public static ChildBeat childFollow(Temperament t, boolean pleads) {
        if (pleads) return new ChildBeat("plead", "teary", null, "sever_plea");
        return new ChildBeat("stare", t.is(Gate.TIMID) ? "teary" : "teary_frown", null, "sever_grievance");
    }

    /** Speech style chosen from the individual's own traits rather than transient capsize fright, so bold/plain children keep their voice. */
    public static String speechStyle(Temperament t) {
        if (t.is(Gate.TIMID)) return "timid";
        if (t.is(Gate.BOLDISH) || t.is(Gate.HARSH)) return "proud";
        return "plain";
    }

    /** Classifies a witnessing sibling's reaction from its own temperament. */
    public static SiblingStance siblingStance(Temperament t) {
        if (t.is(Gate.HARSH) && !t.is(Gate.ATTACHED)) return SiblingStance.COLD;
        if (t.is(Gate.TIMID) && !t.is(Gate.ALOOF)) return SiblingStance.SCARED;
        if (t.is(Gate.BOLDISH) && (t.is(Gate.WARM) || t.is(Gate.ATTACHED))) return SiblingStance.PROTEST;
        if (t.is(Gate.WARM) || t.is(Gate.ATTACHED)) return SiblingStance.SORROW;
        return SiblingStance.UNEASY;
    }
}
