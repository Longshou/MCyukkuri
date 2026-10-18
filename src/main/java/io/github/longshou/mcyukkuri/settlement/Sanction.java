package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;

/**
 * The rules' side of "sanction" (制裁): komicolle 40720 No.11518829, yukkuri "sanction" — that is, kill — whatever
 * cannot take it easy (ruling C7). The physical act (executors, the chase, the bites, the remains) is the headwear
 * package's {@link io.github.longshou.mcyukkuri.headwear.Sanction#request}; this class only hands judgements over.
 *
 * <p>The community rules ({@link CommunityRules}) call {@link #punish} for the gravest judgements (severity
 * {@link #KILL}: killing kin and cannibalism at once; theft, defiance and returning from exile after the lighter
 * penalties). When the request is refused (sanctions disabled, a protected target, or no harsh adult in sight) the rules
 * fall back to driving the offender out and record {@code sanction_deferred}. A different {@link Handler} may be
 * installed for tests or later packages; by default the headwear sanction is used.
 */
public final class Sanction {
    /** Severity scale shared with {@link CommunityRules.Punishment}: 1 rebuke, 2 ration cut, 3 expulsion, 4 kill. */
    public static final int REBUKE = 1, RATION_CUT = 2, EXPEL = 3, KILL = 4;

    @FunctionalInterface
    public interface Handler {
        /**
         * Carry out a sanction. Server thread only; {@code enforcer} may be null (the handler picks a fitting member).
         *
         * @param offence the {@link CommunityRules.Offence} id, e.g. {@code kin_kill}, {@code returned_exile}, {@code hatless}
         * @return true when the handler took the case (it will act through real behaviour); false to let the rules fall back
         */
        boolean punish(YukkuriEntity offender, YukkuriEntity enforcer, int severity, String offence);
    }

    private static volatile Handler handler;

    /** Installs (or, with null, removes) the sanction implementation. The last call wins. */
    public static void install(Handler value) { handler = value; }
    public static boolean installed() { return handler != null; }

    /** Short form for callers without an enforcer or offence name. */
    public static boolean punish(YukkuriEntity offender, int severity) { return punish(offender, null, severity, "unspecified"); }

    /** Reason names as the headwear sanction logs them. */
    static String reason(String offence) {
        return switch (offence) {
            case "kin_kill" -> "killed_kin";
            case "defiance" -> "defied_leader";
            default -> offence;
        };
    }

    /** @return true when the sanction was taken up (executors are on it) */
    public static boolean punish(YukkuriEntity offender, YukkuriEntity enforcer, int severity, String offence) {
        if (offender == null || offender.level().isClientSide || !offender.isAlive() || severity < KILL) return false;
        var current = handler;
        if (current == null) return io.github.longshou.mcyukkuri.headwear.Sanction.request(offender, reason(offence), enforcer);
        try { return current.punish(offender, enforcer, severity, offence); }
        catch (RuntimeException failure) {
            io.github.longshou.mcyukkuri.MCYukkuri.LOGGER.warn("Sanction handler failed for {}: {}", offender.getUUID(), failure.toString());
            return false;
        }
    }

    private Sanction() {}
}
