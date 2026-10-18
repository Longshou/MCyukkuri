package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;

/**
 * The docking point for "sanction" (制裁): komicolle 40720 No.11518829, yukkuri "sanction" — that is, kill — whatever
 * cannot take it easy; the coordinator's ruling C7 makes this the real outcome. The physical act (who attacks, how the
 * victim is chased and killed, what happens to its accessory and remains) belongs to the accessory/"second life"
 * package, which installs a {@link Handler} at mod setup:
 *
 * <pre>{@code Sanction.install((offender, enforcer, severity, offence) -> MySanctions.start(offender, enforcer, severity, offence));}</pre>
 *
 * <p>The community rules ({@link CommunityRules}) call {@link #punish} for the gravest judgements (severity
 * {@link #KILL}). While no handler is installed, or when the handler declines (returns false), the rules fall back to
 * driving the offender out of the community and record {@code sanction_deferred} in the community log.
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

    /** @return true when an installed handler accepted the case */
    public static boolean punish(YukkuriEntity offender, YukkuriEntity enforcer, int severity, String offence) {
        var current = handler;
        if (current == null || offender == null || offender.level().isClientSide || !offender.isAlive()) return false;
        try { return current.punish(offender, enforcer, severity, offence); }
        catch (RuntimeException failure) {
            io.github.longshou.mcyukkuri.MCYukkuri.LOGGER.warn("Sanction handler failed for {}: {}", offender.getUUID(), failure.toString());
            return false;
        }
    }

    private Sanction() {}
}
