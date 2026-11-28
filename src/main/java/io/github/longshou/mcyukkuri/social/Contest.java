package io.github.longshou.mcyukkuri.social;

import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.presentation.FaceTier;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.social.ContestOdds.Level;
import io.github.longshou.mcyukkuri.social.ContestOdds.Side;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;

/**
 * The shared contest performance of two bodies after one {@link Prize} (a bed at its lip, a loose food portion, a free play
 * station, an ownerless doll), played inside a {@link GroupInteractionGoal} bout. The odds are drawn once at the start
 * ({@link ContestOdds}): the gap of will and might decides who is ahead, and the pair's heat decides which of the five ways they
 * settle it — a polite bow, a complaining give-way, a friendly sumo, a cheek-puffing standoff, or a short real fight. The prize
 * says only what it is, how badly a body wants it and what the verdict does to each body.
 */
public final class Contest {
    static final int VERDICT_LAG = 10;
    /** A winner's smug beat is seen for this long before it reaches for the prize (the low end of a short face hold), so the prize's own first face cannot paint over it. */
    public static final int GLOAT_TICKS = 30;
    private static final int SHOVE_ENERGY = 2;
    /** The polite bow and the winner's thanks, then the prize is taken. */
    private static final int POLITE_BOW = 30, POLITE_THANKS = 64, POLITE_END = 96;
    /** The loser's complaint and the winner's claim. */
    private static final int COMPLAIN_LINE = 30, COMPLAIN_END = 86;
    /** Three shoves at contact, then the decisive push-out that hands over the prize. */
    private static final int SUMO_START = 6, SUMO_FIRST_SHOVE = 48, SUMO_SHOVE_EVERY = 20, SUMO_SHOVES = 3, SUMO_END = 108;
    /** Both bodies puff in place until the loser gives way; the last puff gets its full action before the verdict. */
    private static final int PUFF_FIRST = 40, PUFF_EVERY = 40, PUFF_ACTION = 56;
    /** The warning, then two or three short charges; a wounded or winded loser breaks off at once. */
    private static final int ATTACK_THREAT = 10, ATTACK_FIRST = 40, ATTACK_EVERY = 30, ATTACK_ROUNDS = 3;

    /** What two bodies contest: the prize owns its own take-over and give-way, the contest owns the performance. */
    public interface Prize {
        /** Log and beat label: {@code bed}, {@code food}, {@code toy}, {@code doll}. */
        String kind();
        /** Where it lies, for the log. */
        String where();
        /** False once it is gone or taken by a third body: the contest ends with no verdict. */
        boolean present(YukkuriEntity probe);
        /** 0..100: how badly {@code body} wants it right now (tiredness for a bed, hunger for food, boredom for a toy). */
        int need(YukkuriEntity body);
        /** 0..100: how much the prize itself matters to {@code body} in its present state (its category's pull plus the real need). */
        int importance(YukkuriEntity body);
        /** The line a winner speaks over its smug face. */
        String winLine();
        /** The verdict: the winner takes it the way an uncontested arrival would, the loser gives way and goes on. */
        void settle(YukkuriEntity body, boolean won, UUID rival);
    }

    private final YukkuriEntity first, second, winner, loser;
    private final Prize prize;
    private final Level level;
    private final double gap, heat;
    private final int puffRounds;
    private int elapsed, rounds, endAt;

    private Contest(YukkuriEntity first, YukkuriEntity second, YukkuriEntity winner, Prize prize, Level level, double gap,
            double heat, int puffRounds) {
        this.first = first; this.second = second; this.winner = winner; this.loser = winner == first ? second : first;
        this.prize = prize; this.level = level; this.gap = gap; this.heat = heat; this.puffRounds = puffRounds;
        endAt = switch (level) {
            case POLITE_YIELD -> POLITE_END;
            case COMPLAIN_YIELD -> COMPLAIN_END;
            case SUMO_DECIDE -> SUMO_END;
            case PUFF_DECIDE -> PUFF_FIRST + PUFF_EVERY * (puffRounds - 1) + PUFF_ACTION + VERDICT_LAG;
            case ATTACK_DECIDE -> ATTACK_FIRST + ATTACK_EVERY * (ATTACK_ROUNDS - 1) + VERDICT_LAG;
        };
    }

    /** Whether this contest is settled by sumo, whose landed ready and push moments then play. */
    boolean sumo() { return level == Level.SUMO_DECIDE; }

    /** True once the sumo has begun to push, which plays the landed push moment. */
    boolean shoving() { return sumo() && elapsed >= SUMO_FIRST_SHOVE; }

    /** True once the pair is actually trading contact: a sumo or a real attack, not a stare or a puff. */
    boolean fighting() { return level == Level.SUMO_DECIDE || level == Level.ATTACK_DECIDE; }

    /** Whether {@code body} steps in this tick to close the distance for contact. */
    boolean approaching(YukkuriEntity body) {
        if (level == Level.SUMO_DECIDE) return body == winner && rounds < SUMO_SHOVES;
        return level == Level.ATTACK_DECIDE && elapsed < endAt;
    }

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

    /** A pair owned by one hand never trades blows; every other pair may. */
    static boolean mayPush(YukkuriEntity a, YukkuriEntity b) {
        return !(a.pet().isPet() && b.pet().isPet() && a.pet().owner() != null && a.pet().ownedBy(b.pet().owner()));
    }

    private static boolean sameOwner(YukkuriEntity a, YukkuriEntity b) {
        return a.pet().isPet() && b.pet().isPet() && a.pet().owner() != null && a.pet().ownedBy(b.pet().owner());
    }

    private static boolean gentle(YukkuriEntity yielder, YukkuriEntity winner, Prize prize, YukkuriWorldData relations) {
        var traits = yielder.life().personality();
        return traits.friendliness() >= 52 || relations.affinity(yielder.getUUID(), winner.getUUID()) >= 10
                || traits.boldness() <= 25 && prize.need(yielder) < 45;
    }

    /** Draws the odds, the heat and the way of settling at {@code prize}; {@code mayPush} is false for a pair that must not fight. */
    static Contest begin(YukkuriEntity first, YukkuriEntity second, Prize prize, boolean mayPush) {
        double gap = ContestOdds.gap(side(first, second, prize), side(second, first, prize));
        var relations = YukkuriWorldData.get((ServerLevel) first.level());
        int importanceFirst = prize.importance(first), importanceSecond = prize.importance(second);
        int pairImportance = ContestOdds.pairImportance(importanceFirst, importanceSecond);
        var a = first.life().personality();
        var b = second.life().personality();
        double avgTemper = (ContestOdds.temper(a.boldness(), a.friendliness(), a.selfControl())
                + ContestOdds.temper(b.boldness(), b.friendliness(), b.selfControl())) / 2;
        double bondBrake = ContestOdds.bondBrake(relations.affinity(first.getUUID(), second.getUUID()),
                relations.affinity(second.getUUID(), first.getUUID()), sameOwner(first, second),
                Bonds.careKin(first, second) || Bonds.careKin(second, first));
        int jitter = ContestOdds.jitter(first.getUUID(), second.getUUID());
        double heat = ContestOdds.heat(pairImportance, avgTemper, bondBrake, jitter);
        boolean fit = ContestOdds.fitToFight(first.getHealth(), first.getMaxHealth(), first.life().energy())
                && ContestOdds.fitToFight(second.getHealth(), second.getMaxHealth(), second.life().energy());
        YukkuriEntity higher = gap > 0 || gap == 0 && first.getUUID().compareTo(second.getUUID()) > 0 ? first : second;
        YukkuriEntity lower = higher == first ? second : first;
        boolean gentle = gentle(lower, higher, prize, relations);
        Level level = ContestOdds.level(gap, heat, mayPush, fit, gentle);
        // A yield goes to the needier body; the sumo's shove pushes with might, so its verdict must follow the same comparison.
        boolean firstWins = switch (level) {
            case POLITE_YIELD, COMPLAIN_YIELD -> higher == first;
            case SUMO_DECIDE -> might(first) >= might(second);
            default -> ContestOdds.firstWins(gap, first.getRandom().nextDouble());
        };
        double chance = ContestOdds.winChance(gap);
        double yieldGap = ContestOdds.yieldGap(heat);
        int puffRounds = ContestOdds.puffRounds(gap, heat);
        var contest = new Contest(first, second, firstWins ? first : second, prize, level, gap, heat, puffRounds);
        GameplayLog.event(first, "social",
                "contest begin prize={} at={} level={} gap={} chance={} heat={} pairImportance={} importance={}/{} jitter={} brake={} yieldGap={} puffs={} winner={} loser={} peer={} boldness={}/{} need={}/{} fit={}",
                prize.kind(), prize.where(), level, Math.round(gap * 10) / 10.0, Math.round(chance * 100) / 100.0,
                Math.round(heat * 10) / 10.0, pairImportance, importanceFirst, importanceSecond, jitter,
                Math.round(bondBrake * 10) / 10.0, Math.round(yieldGap * 10) / 10.0, puffRounds,
                contest.winner.getUUID(), contest.loser.getUUID(), second.getUUID(),
                first.life().personality().boldness(), second.life().personality().boldness(), prize.need(first), prize.need(second), fit);
        return contest;
    }

    /** One tick of the bout clock ({@code t} counts from 1); true when the contest is over. */
    boolean tick(int t) {
        elapsed = t;
        if (!prize.present(first)) { gone(); return true; }
        if (t == 1) faceoff();
        switch (level) {
            case POLITE_YIELD -> polite(t);
            case COMPLAIN_YIELD -> complain(t);
            case SUMO_DECIDE -> sumo(t);
            case PUFF_DECIDE -> puff(t);
            case ATTACK_DECIDE -> attack(t);
        }
        if (t < endAt) return false;
        ending();
        return true;
    }

    private void faceoff() {
        String winnerFace, loserFace;
        switch (level) {
            case POLITE_YIELD -> { winnerFace = "happy"; loserFace = "closed_smile"; }
            case COMPLAIN_YIELD -> { winnerFace = "angry"; loserFace = "pursed"; }
            case SUMO_DECIDE -> { winnerFace = "smug"; loserFace = "smug"; }
            case PUFF_DECIDE -> { winnerFace = "angry"; loserFace = "angry"; }
            default -> { winnerFace = "angry_stare"; loserFace = "surprise"; }
        }
        // The face-off is silent and forced: a greeting or a yield of the approach must not hide the first look of the bout.
        opening(winner, winnerFace);
        opening(loser, loserFace);
    }

    /** The first face of the bout, shown even over a reaction the approach left running. */
    private void opening(YukkuriEntity body, String face) {
        body.getLookControl().setLookAt(body == first ? second : first, 30, 30);
        body.beat("contest_faceoff", face, "");
        body.forceReaction(face, "", FaceTier.Tier.SHORT);
        GameplayLog.event(body, "social", "contest beat={} prize={} role={} face={} line=none accepted=true",
                "contest_faceoff", prize.kind(), body == winner ? "winner" : "loser", face);
    }

    private void polite(int t) {
        if (t == POLITE_BOW) {
            var lp = loser.life().personality();
            boolean timid = lp.boldness() < 50;
            deferredBeat(loser, timid ? YukkuriAction.SHY : YukkuriAction.BOW, "contest_lost",
                    timid ? "shy_peek" : "closed_smile", "contest_yield_polite", FaceTier.Tier.MEDIUM);
        }
        if (t == POLITE_THANKS) {
            deferredBeat(winner, YukkuriAction.JOY_HOP, "contest_thanks", "happy", "contest_thanks", FaceTier.Tier.MEDIUM);
        }
    }

    private void complain(int t) {
        if (t != COMPLAIN_LINE) return;
        var lp = loser.life().personality();
        String face = ContestOdds.contestLoserFace(lp.boldness(), lp.selfControl(), lp.friendliness());
        deferredBeat(loser, ContestOdds.defiant(lp.boldness()) ? YukkuriAction.SULK : YukkuriAction.DROOP,
                "contest_lost", face, "contest_yield_complain", FaceTier.Tier.LONG);
    }

    private void sumo(int t) {
        if (t == SUMO_START) {
            beat(winner, null, "sumo_start", "smug", "sumo_start", FaceTier.Tier.SHORT);
            beat(loser, null, "sumo_start", "smug", "sumo_start", FaceTier.Tier.SHORT);
        }
        if (rounds < SUMO_SHOVES && t == SUMO_FIRST_SHOVE + SUMO_SHOVE_EVERY * rounds) shove(t);
    }

    private void shove(int t) {
        if (!first.life().spendEnergy(SHOVE_ENERGY) || !second.life().spendEnergy(SHOVE_ENERGY)) {
            endAt = Math.min(endAt, t + VERDICT_LAG);
            GameplayLog.event(winner, "social", "contest exhausted prize={} rounds={}/{}", prize.kind(), rounds, SUMO_SHOVES);
            return;
        }
        rounds++;
        var pusher = might(first) >= might(second) ? first : second;
        var pushed = pusher == first ? second : first;
        GroupInteractionGoal.shove(pushed, pusher, rounds == SUMO_SHOVES ? .13 : .07);
        var pp = pushed.life().personality();
        String face = ContestOdds.sumoPushedFace(pp.boldness(), pp.selfControl(), "", pushed.getRandom().nextDouble());
        pushed.forceReaction(face, "", FaceTier.Tier.SHORT);
        pushed.beat("sumo_pushed_back", face, "");
        GameplayLog.event(pushed, "social", "sumo pushed_back face={} round={} pusher={}", face, rounds, pusher.getUUID());
        // The decisive last shove ends the bout at once; its win and push-out beats are the ending's.
        if (rounds == SUMO_SHOVES) endAt = Math.min(endAt, t + VERDICT_LAG);
    }

    private void puff(int t) {
        if (t < PUFF_FIRST || (t - PUFF_FIRST) % PUFF_EVERY != 0) return;
        int round = (t - PUFF_FIRST) / PUFF_EVERY;
        if (round >= puffRounds) return;
        if (!first.life().spendEnergy(SHOVE_ENERGY) || !second.life().spendEnergy(SHOVE_ENERGY)) {
            endAt = Math.min(endAt, t + VERDICT_LAG);
            GameplayLog.event(winner, "social", "contest exhausted prize={} puff={}/{}", prize.kind(), round + 1, puffRounds);
            return;
        }
        var speaker = round % 2 == 0 ? winner : loser;
        // Both stand planted and puff; only the speaker spends its breath on a taunt, so neither ever puffs while backing off.
        for (var body : new YukkuriEntity[]{winner, loser}) {
            if (body == speaker) deferredBeat(body, YukkuriAction.PUFF, "contest_puff", "cheek_puff", "contest_puff", FaceTier.Tier.MEDIUM);
            else beat(body, YukkuriAction.PUFF, "contest_puff", "cheek_puff", "", FaceTier.Tier.MEDIUM);
        }
    }

    private void attack(int t) {
        if (t == ATTACK_THREAT) {
            beat(winner, null, "contest_threat", "angry_stare", "contest_threat", FaceTier.Tier.MEDIUM);
        }
        if (rounds >= ATTACK_ROUNDS || t < ATTACK_FIRST || (t - ATTACK_FIRST) % ATTACK_EVERY != 0) return;
        rounds++;
        beat(winner, YukkuriAction.LUNGE_RAM, "contest_attack", "angry_stare", "", FaceTier.Tier.SHORT);
        beat(loser, YukkuriAction.LUNGE_RAM, "contest_attack", "surprise", "", FaceTier.Tier.SHORT);
        GroupInteractionGoal.shove(loser, winner, .22);
        boolean hit = loser.hurtInBout(((ServerLevel) first.level()).damageSources().mobAttack(winner), 1);
        GameplayLog.event(winner, "social", "contest attack round={} hit={} loserHealth={}/{} loserEnergy={}",
                rounds, hit, loser.getHealth(), loser.getMaxHealth(), loser.life().energy());
        boolean broken = loser.getHealth() <= Math.max(3, loser.getMaxHealth() * ContestOdds.FIGHT_HEALTH)
                || loser.life().energy() < ContestOdds.FIGHT_ENERGY;
        if (broken || rounds == ATTACK_ROUNDS) endAt = Math.min(endAt, t + VERDICT_LAG);
    }

    private void ending() {
        var relations = YukkuriWorldData.get((ServerLevel) first.level());
        String levelName = level.name();
        GameplayLog.event(winner, "social", "contest verdict prize={} at={} level={} winner={} loser={} heat={} gap={} rounds={}",
                prize.kind(), prize.where(), levelName, winner.getUUID(), loser.getUUID(),
                Math.round(heat * 10) / 10.0, Math.round(gap * 10) / 10.0, rounds);
        switch (level) {
            case POLITE_YIELD -> {
                relations.relate(loser.getUUID(), winner.getUUID(), 1);
                relations.relate(winner.getUUID(), loser.getUUID(), 1);
            }
            case COMPLAIN_YIELD -> {
                relations.relate(loser.getUUID(), winner.getUUID(), -2);
                relations.relate(winner.getUUID(), loser.getUUID(), -1);
                claim("smug");
            }
            case SUMO_DECIDE -> {
                relations.relate(loser.getUUID(), winner.getUUID(), -1);
                sumoEnd();
            }
            case PUFF_DECIDE -> {
                relations.relate(loser.getUUID(), winner.getUUID(), -3);
                relations.relate(winner.getUUID(), loser.getUUID(), -2);
                if (prize.need(loser) >= 45) loser.mind().add(4, "contest_puff");
                YukkuriSocial.quarrelled(winner, loser);
                var lp = loser.life().personality();
                String loserFace = ContestOdds.contestLoserFace(lp.boldness(), lp.selfControl(), lp.friendliness());
                deferredBeat(loser, ContestOdds.defiant(lp.boldness()) ? YukkuriAction.SULK : YukkuriAction.DROOP,
                        "contest_lost", loserFace, "contest_yield_complain", FaceTier.Tier.LONG);
                claim("smug");
            }
            default -> {
                relations.relate(loser.getUUID(), winner.getUUID(), -8);
                relations.relate(winner.getUUID(), loser.getUUID(), -5);
                loser.life().personality().relationshipChanged(winner.getUUID(), 0, 15);
                loser.mind().add(10, "contest_attack");
                YukkuriSocial.quarrelled(winner, loser);
                var lp = loser.life().personality();
                String loserFace = ContestOdds.contestLoserFace(lp.boldness(), lp.selfControl(), lp.friendliness());
                loser.fleeFrom(winner);
                deferredBeat(loser, YukkuriAction.FLEE, "contest_lost", loserFace, "contest_lost", FaceTier.Tier.LONG);
                claim("smug");
            }
        }
        prize.settle(winner, true, loser.getUUID());
        prize.settle(loser, false, winner.getUUID());
        if (level == Level.ATTACK_DECIDE) GroupInteractionGoal.witnessedAttack(loser, winner);
    }

    /** The winner's claim over its smug face, for a prize taken at once rather than through the sumo's own win line. */
    private void claim(String face) {
        deferredBeat(winner, YukkuriAction.PROUD, "contest_won", face, prize.winLine(), FaceTier.Tier.MEDIUM);
    }

    /** The sumo's own end, the same win and push-out beats the friendly bout plays. */
    private void sumoEnd() {
        var lp = loser.life().personality();
        String endFace = ContestOdds.sumoPushedFace(lp.boldness(), lp.selfControl(), "", loser.getRandom().nextDouble());
        deferredBeat(winner, YukkuriAction.SUMO_WIN, "sumo_win", "smug", "sumo_win", FaceTier.Tier.MEDIUM);
        deferredBeat(loser, YukkuriAction.SUMO_PUSH_OUT, "sumo_push_out", endFace, "sumo_lose", FaceTier.Tier.MEDIUM);
        GameplayLog.event(winner, "social", "sumo completed prize={} winner={} loser={} winnerHealth={} loserHealth={} loserFace={}",
                prize.kind(), winner.getUUID(), loser.getUUID(), winner.getHealth(), loser.getHealth(), endFace);
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

    /** A beat whose line waits for the first open speech slot inside the action, so a previous cry never swallows it. */
    private void deferredBeat(YukkuriEntity body, YukkuriAction action, String name, String face, String line, FaceTier.Tier tier) {
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
