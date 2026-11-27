package io.github.longshou.mcyukkuri.social;

import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.presentation.FaceTier;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.social.ContestOdds.Process;
import io.github.longshou.mcyukkuri.social.ContestOdds.Side;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;

/**
 * The shared contest performance of two bodies after one {@link Prize} (a bed at its lip, a loose food portion, a free play
 * station), played inside a {@link GroupInteractionGoal} bout. The odds are drawn once at the start ({@link ContestOdds}); the bout
 * then plays the face-off, a push and pull when the pair is close, and the verdict, so what the player sees follows from the two
 * bodies' numbers. The prize says only what it is, how badly a body wants it and what the verdict does to each body.
 */
public final class Contest {
    static final int FACEOFF = 40, PUSH_EVERY = 20, VOICE_GAP = 60, VERDICT_LAG = 10;
    /** A winner's smug beat is seen for this long before it reaches for the prize (the low end of a short face hold), so the prize's own first face cannot paint over it. */
    public static final int GLOAT_TICKS = 30;
    private static final int SHOVE_ENERGY = 2;

    /** What two bodies contest: the prize owns its own take-over and give-way, the contest owns the performance. */
    public interface Prize {
        /** Log and beat label: {@code bed}, {@code food}, {@code toy}. */
        String kind();
        /** Where it lies, for the log. */
        String where();
        /** False once it is gone or taken by a third body: the contest ends with no verdict. */
        boolean present(YukkuriEntity probe);
        /** 0..100: how badly {@code body} wants it right now (tiredness for a bed, hunger for food, boredom for a toy). */
        int need(YukkuriEntity body);
        /** The line a winner speaks over its smug face. */
        String winLine();
        /** The verdict: the winner takes it the way an uncontested arrival would, the loser gives way and goes on. */
        void settle(YukkuriEntity body, boolean won, UUID rival);
    }

    private final YukkuriEntity first, second, winner, loser;
    private final Process process;
    private final Prize prize;
    private final double gap, chance;
    private final int pushes;
    private int elapsed, pushed, verdictAt, lastVoice = -VOICE_GAP;

    private Contest(YukkuriEntity first, YukkuriEntity second, YukkuriEntity winner, Process process, Prize prize, double gap, double chance, int pushes) {
        this.first = first; this.second = second; this.winner = winner; this.loser = winner == first ? second : first;
        this.process = process; this.prize = prize; this.gap = gap; this.chance = chance; this.pushes = pushes;
        verdictAt = FACEOFF + (process == Process.TUSSLE ? PUSH_EVERY * pushes : 0) + VERDICT_LAG;
    }

    /** True once a close bout has moved from the staring face-off into its push-and-pull phase. */
    boolean tussling() { return process == Process.TUSSLE && elapsed > FACEOFF; }

    /** Only the winner steps in to close any gap opened by shoves, keeping the tussle face-to-face instead of shoving across air. */
    boolean closesGap(YukkuriEntity body) { return body == winner; }

    /** The one body strength: a contest, a sumo bout and a self-defence all read it. */
    static double might(YukkuriEntity body) {
        return ContestOdds.might(body.getBbWidth(), body.species().traits().might(), body.body().healthRatio(), body.life().energy());
    }

    private static Side side(YukkuriEntity own, YukkuriEntity other, Prize prize) {
        var traits = own.life().personality();
        return new Side(might(own), traits.boldness(), traits.friendliness(), traits.fear(other.getUUID()), prize.need(own));
    }

    /** Whether {@code a} would win {@code prize} over {@code b} when no performance is staged: the same gap, ties broken by identity. */
    public static boolean beats(YukkuriEntity a, YukkuriEntity b, Prize prize) {
        double gap = ContestOdds.gap(side(a, b, prize), side(b, a, prize));
        return gap > 0 || gap == 0 && a.getUUID().compareTo(b.getUUID()) > 0;
    }

    /** Draws the odds of the two bodies at {@code prize}; {@code mayPush} is false for a pair that must not fight. */
    static Contest begin(YukkuriEntity first, YukkuriEntity second, Prize prize, boolean mayPush) {
        double gap = ContestOdds.gap(side(first, second, prize), side(second, first, prize));
        var process = ContestOdds.process(gap, mayPush);
        boolean firstWins = ContestOdds.firstWins(gap, first.getRandom().nextDouble());
        double chance = ContestOdds.winChance(gap);
        var contest = new Contest(first, second, firstWins ? first : second, process, prize, gap, chance, ContestOdds.pushes(gap));
        GameplayLog.event(first, "social", "contest begin prize={} at={} process={} gap={} chance={} pushes={} winner={} loser={} peer={} boldness={}/{} need={}/{}",
                prize.kind(), prize.where(), process, Math.round(gap * 10) / 10.0, Math.round(chance * 100) / 100.0, process == Process.TUSSLE ? contest.pushes : 0,
                contest.winner.getUUID(), contest.loser.getUUID(), second.getUUID(), first.life().personality().boldness(), second.life().personality().boldness(),
                prize.need(first), prize.need(second));
        return contest;
    }

    /** One tick of the bout clock ({@code t} counts from 1); true when the contest is over. */
    boolean tick(int t) {
        elapsed = t;
        if (!prize.present(first)) { gone(); return true; }
        if (t == 1) { faceoff(first); faceoff(second); }
        if (process == Process.TUSSLE && pushed < pushes && t > FACEOFF && (t - FACEOFF) % PUSH_EVERY == 0) push(t);
        if (t < verdictAt) return false;
        verdict();
        return true;
    }

    private void faceoff(YukkuriEntity body) {
        var traits = body.life().personality();
        boolean defiant = ContestOdds.defiant(traits.boldness());
        String face = defiant ? (traits.boldness() >= 75 && traits.friendliness() < 40 ? "angry_stare" : "angry") : "surprise";
        // In a lopsided bout the timid loser flinches silently so its speech clock stays open for its yielding line at the verdict.
        boolean speaks = process == Process.TUSSLE || body == winner || defiant;
        beat(body, null, "contest_faceoff", face, speaks ? "contest_faceoff" : "", FaceTier.Tier.MEDIUM);
    }

    private void push(int t) {
        if (!first.life().spendEnergy(SHOVE_ENERGY) || !second.life().spendEnergy(SHOVE_ENERGY)) {
            // Out of breath: the tussle ends where it stands.
            verdictAt = t + VERDICT_LAG;
            GameplayLog.event(winner, "social", "contest exhausted prize={} pushes={}/{}", prize.kind(), pushed, pushes);
            return;
        }
        pushed++;
        double reach = (first.getBbWidth() + second.getBbWidth()) * .5 + .65;
        if (first.distanceToSqr(second) <= reach * reach) {
            if (pushed < pushes) {
                boolean winnerPushes = pushed % 2 == 1;
                GroupInteractionGoal.shove(winnerPushes ? loser : winner, winnerPushes ? winner : loser, winnerPushes ? .07 : .06);
            } else {
                GroupInteractionGoal.shove(loser, winner, .12);
            }
        }
        boolean allowVoice = t - lastVoice >= VOICE_GAP && t + 60 <= verdictAt;
        var speaker = pushed % 2 == 1 ? winner : loser;
        boolean spoke = false;
        for (var body : new YukkuriEntity[]{winner, loser}) {
            boolean defiant = ContestOdds.defiant(body.life().personality().boldness());
            boolean speaks = allowVoice && body == speaker && body.quiet();
            if (speaks) spoke = true;
            beat(body, YukkuriAction.PUSH, "contest_tussle", defiant ? "angry" : "teary", speaks ? "contest_tussle" : "", FaceTier.Tier.MEDIUM);
        }
        if (spoke) lastVoice = t;
    }

    private void verdict() {
        var relations = YukkuriWorldData.get((ServerLevel) first.level());
        relations.relate(loser.getUUID(), winner.getUUID(), -2);
        relations.relate(winner.getUUID(), loser.getUUID(), -1);
        GameplayLog.event(winner, "social", "contest verdict prize={} at={} process={} winner={} loser={} chance={} pushes={}",
                prize.kind(), prize.where(), process, winner.getUUID(), loser.getUUID(), Math.round(chance * 100) / 100.0, pushed);
        boolean quietWin = process == Process.LOPSIDED && !ContestOdds.defiant(winner.life().personality().boldness());
        verdictBeat(winner, quietWin ? YukkuriAction.JOY_HOP : YukkuriAction.PROUD, "contest_won",
                quietWin ? "happy" : "smug", prize.winLine(), FaceTier.Tier.MEDIUM);
        var lp = loser.life().personality();
        String loserFace = ContestOdds.contestLoserFace(lp.boldness(), lp.selfControl(), lp.friendliness());
        YukkuriAction loserAction = ContestOdds.defiant(lp.boldness()) ? YukkuriAction.SULK : YukkuriAction.DROOP;
        verdictBeat(loser, loserAction, "contest_lost", loserFace, "contest_lost", FaceTier.Tier.LONG);
        prize.settle(winner, true, loser.getUUID());
        prize.settle(loser, false, winner.getUUID());
    }

    /** The prize went away mid-contest: nobody won it, nobody lost anything. */
    private void gone() {
        GameplayLog.event(first, "social", "contest prize_gone prize={} at={} peer={}", prize.kind(), prize.where(), second.getUUID());
        beat(first, null, "contest_gone", "confused", "", FaceTier.Tier.SHORT);
        beat(second, null, "contest_gone", "confused", "", FaceTier.Tier.SHORT);
    }

    /** One beat: the declared face and line are noted for the arbiter, which logs the face really shown beside them. */
    private void beat(YukkuriEntity body, YukkuriAction action, String name, String face, String line, FaceTier.Tier tier) {
        body.getLookControl().setLookAt(body == first ? second : first, 30, 30);
        body.beat(name, face, line);
        boolean shown = action == null ? body.showReaction(face, line, tier) : body.showReaction(action, face, line, action.durationTicks(), tier);
        GameplayLog.event(body, "social", "contest beat={} prize={} role={} face={} line={} accepted={}",
                name, prize.kind(), body == winner ? "winner" : "loser", face, line.isEmpty() ? "none" : line, shown);
    }

    /** A verdict beat defers its line to the first open speech slot inside the face hold so face-off or tussle speech never swallows it. */
    private void verdictBeat(YukkuriEntity body, YukkuriAction action, String name, String face, String line, FaceTier.Tier tier) {
        String role = body == winner ? "winner" : "loser";
        body.getLookControl().setLookAt(body == first ? second : first, 30, 30);
        body.beat(name, face, line);
        boolean shown = body.showWithDeferredLine(action, action.durationTicks(), face, line, tier, 2,
                res -> GameplayLog.event(body, "social", "contest speech prize={} role={} event={} spoken={}",
                        prize.kind(), role, line, res.spoken()));
        GameplayLog.event(body, "social", "contest beat={} prize={} role={} face={} line={} accepted={}",
                name, prize.kind(), role, face, line.isEmpty() ? "none" : line, shown);
    }
}
