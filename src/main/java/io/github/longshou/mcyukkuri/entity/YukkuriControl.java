package io.github.longshou.mcyukkuri.entity;

/** Advances transient gameplay controls once, independently of publishing an animation. */
public final class YukkuriControl {
    public enum Takeover {
        WATER_DISTRESS, PANIC, SHELL, AGITATION, NONE
    }

    /** A tick-local observation. Panic uses the pre-decrement value for its startle/flee clip. */
    public record Frame(Takeover takeover, int panicTicks) {}

    static Frame advance(YukkuriEntity owner) {
        int panic = owner.panicTicksValue();
        Takeover takeover = earlyTakeover(owner, panic);
        if (takeover == Takeover.PANIC && owner.shellRetraction().active()) owner.directNavigation().stop();
        if (panic > 0) owner.setPanicTicks(panic - 1);

        // Physical takeovers retain the command clock. Shell/agitation are sampled after panic advances.
        if (takeover == Takeover.NONE) {
            if (owner.shellRetraction().active()) takeover = Takeover.SHELL;
            else if (owner.agitation().activePresentation()) takeover = Takeover.AGITATION;
            else {
                var requested = owner.requestedActionValue();
                if (requested != null && !requested.loops() && !requested.holdsEndPose()
                        && owner.level().getGameTime() - owner.getAnimationStartTick() >= requested.durationTicks()) {
                    owner.clearRequestedAction();
                }
            }
        }
        return new Frame(takeover, panic);
    }

    private static Takeover earlyTakeover(YukkuriEntity owner, int panic) {
        // Preserve physical precedence and short-circuit reads; this never probes Goal eligibility.
        if ((owner.waterDistress().active() || owner.waterDistress().airbornePlunge())
                && !owner.isCommandPlaying() && !owner.operations().active()) return Takeover.WATER_DISTRESS;
        if (panic > 0 && !owner.isCommandPlaying() && !owner.operations().active()) return Takeover.PANIC;
        return Takeover.NONE;
    }

    private YukkuriControl() {}
}
