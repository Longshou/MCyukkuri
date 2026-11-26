package io.github.longshou.mcyukkuri.social;

import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.presentation.FaceTier;
import io.github.longshou.mcyukkuri.life.NestDoorway;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.social.ContestOdds.Process;
import io.github.longshou.mcyukkuri.social.ContestOdds.Side;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * The shared contest performance of two bodies after one prize (today a bed at its lip), played inside a
 * {@link GroupInteractionGoal} bout. The odds are drawn once at the start ({@link ContestOdds}); the bout then plays the
 * face-off, a push and pull when the pair is close, and the verdict, so what the player sees follows from the two bodies' numbers.
 */
public final class Contest {
    static final int FACEOFF = 40, PUSH_EVERY = 20, VOICE_GAP = 60, VERDICT_LAG = 10;
    private static final int SHOVE_ENERGY = 2;

    private final YukkuriEntity first, second, winner, loser;
    private final Process process;
    private final BlockPos prize;
    private final double gap, chance;
    private final int pushes;
    private int pushed, verdictAt, lastVoice = -VOICE_GAP;

    private Contest(YukkuriEntity first, YukkuriEntity second, YukkuriEntity winner, Process process, BlockPos prize, double gap, double chance, int pushes) {
        this.first = first; this.second = second; this.winner = winner; this.loser = winner == first ? second : first;
        this.process = process; this.prize = prize; this.gap = gap; this.chance = chance; this.pushes = pushes;
        verdictAt = FACEOFF + (process == Process.TUSSLE ? PUSH_EVERY * pushes : 0) + VERDICT_LAG;
    }

    /** The one body strength: a contest, a sumo bout and a self-defence all read it. */
    static double might(YukkuriEntity body) {
        return ContestOdds.might(body.getBbWidth(), body.species().traits().might(), body.body().healthRatio(), body.life().energy());
    }

    /** A bed is wanted as much as its claimant is tired. */
    private static Side bedSide(YukkuriEntity own, YukkuriEntity other) {
        var traits = own.life().personality();
        return new Side(might(own), traits.boldness(), traits.friendliness(), traits.fear(other.getUUID()), 100 - Math.round(own.life().energy()));
    }

    /** Whether {@code a} would win the bed over {@code b} when no performance is staged: the same gap, ties broken by identity. */
    public static boolean beats(YukkuriEntity a, YukkuriEntity b) {
        double gap = ContestOdds.gap(bedSide(a, b), bedSide(b, a));
        return gap > 0 || gap == 0 && a.getUUID().compareTo(b.getUUID()) > 0;
    }

    /** Draws the odds of the two bodies at {@code prize}'s lip; {@code mayPush} is false for a pair that must not fight. */
    static Contest begin(YukkuriEntity first, YukkuriEntity second, BlockPos prize, boolean mayPush) {
        double gap = ContestOdds.gap(bedSide(first, second), bedSide(second, first));
        var process = ContestOdds.process(gap, mayPush);
        boolean firstWins = ContestOdds.firstWins(gap, first.getRandom().nextDouble());
        double chance = ContestOdds.winChance(gap);
        var contest = new Contest(first, second, firstWins ? first : second, process, prize, gap, chance, ContestOdds.pushes(gap));
        GameplayLog.event(first, "social", "contest begin prize=bed nest={} process={} gap={} chance={} pushes={} winner={} loser={} peer={} boldness={}/{} need={}/{}",
                prize, process, Math.round(gap * 10) / 10.0, Math.round(chance * 100) / 100.0, process == Process.TUSSLE ? contest.pushes : 0,
                contest.winner.getUUID(), contest.loser.getUUID(), second.getUUID(), first.life().personality().boldness(), second.life().personality().boldness(),
                bedSide(first, second).need(), bedSide(second, first).need());
        return contest;
    }

    /** One tick of the bout clock ({@code t} counts from 1); true when the contest is over. */
    boolean tick(int t) {
        if (NestDoorway.bed(first, prize) == null) { gone(); return true; }
        if (t == 1) { faceoff(first); faceoff(second); }
        if (process == Process.TUSSLE && pushed < pushes && t > FACEOFF && (t - FACEOFF) % PUSH_EVERY == 0) push(t);
        if (t < verdictAt) return false;
        verdict();
        return true;
    }

    private void faceoff(YukkuriEntity body) {
        boolean defiant = ContestOdds.defiant(body.life().personality().boldness());
        beat(body, null, "contest_faceoff", defiant ? "angry" : "surprise", defiant ? "mine" : "shy", FaceTier.Tier.MEDIUM);
    }

    private void push(int t) {
        if (!first.life().spendEnergy(SHOVE_ENERGY) || !second.life().spendEnergy(SHOVE_ENERGY)) {
            // Out of breath: the tussle ends where it stands.
            verdictAt = t + VERDICT_LAG;
            GameplayLog.event(winner, "social", "contest exhausted prize=bed pushes={}/{}", pushed, pushes);
            return;
        }
        pushed++;
        boolean voiced = t - lastVoice >= VOICE_GAP;
        if (voiced) lastVoice = t;
        for (var body : new YukkuriEntity[]{winner, loser}) {
            boolean defiant = ContestOdds.defiant(body.life().personality().boldness());
            // A grunt at most every three seconds, from one body at a time; the pushes between are the bodies leaning in.
            boolean grunts = voiced && defiant && body == (pushed % 2 == 1 ? winner : loser);
            beat(body, YukkuriAction.PUSH, "contest_tussle", defiant ? "angry" : "teary", grunts ? "puff" : "", FaceTier.Tier.MEDIUM);
        }
        GroupInteractionGoal.shove(loser, winner);
    }

    private void verdict() {
        var relations = YukkuriWorldData.get((ServerLevel) first.level());
        relations.relate(loser.getUUID(), winner.getUUID(), -2);
        relations.relate(winner.getUUID(), loser.getUUID(), -1);
        GameplayLog.event(winner, "social", "contest verdict prize=bed nest={} process={} winner={} loser={} chance={} pushes={}",
                prize, process, winner.getUUID(), loser.getUUID(), Math.round(chance * 100) / 100.0, pushed);
        beat(winner, null, "contest_won", "smug", "home_declare", FaceTier.Tier.MEDIUM);
        boolean defiant = ContestOdds.defiant(loser.life().personality().boldness());
        beat(loser, null, "contest_lost", defiant ? "cheek_puff" : "teary", defiant ? "puff" : "wait_turn", FaceTier.Tier.LONG);
        winner.life().bedVerdict(prize, true, loser.getUUID());
        loser.life().bedVerdict(prize, false, winner.getUUID());
    }

    /** The bed went away mid-contest: nobody won it, nobody lost anything. */
    private void gone() {
        GameplayLog.event(first, "social", "contest prize_gone prize=bed nest={} peer={}", prize, second.getUUID());
        beat(first, null, "contest_gone", "confused", "", FaceTier.Tier.SHORT);
        beat(second, null, "contest_gone", "confused", "", FaceTier.Tier.SHORT);
    }

    /** One beat: the declared face and line are noted for the arbiter, which logs the face really shown beside them. */
    private void beat(YukkuriEntity body, YukkuriAction action, String name, String face, String line, FaceTier.Tier tier) {
        body.getLookControl().setLookAt(body == first ? second : first, 30, 30);
        body.beat(name, face, line);
        boolean shown = action == null ? body.showReaction(face, line, tier) : body.showReaction(action, face, line, action.durationTicks(), tier);
        GameplayLog.event(body, "social", "contest beat={} prize=bed role={} face={} line={} accepted={}",
                name, body == winner ? "winner" : "loser", face, line.isEmpty() ? "none" : line, shown);
    }
}
