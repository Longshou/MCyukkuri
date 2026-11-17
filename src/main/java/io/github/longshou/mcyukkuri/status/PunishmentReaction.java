package io.github.longshou.mcyukkuri.status;

import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.human.HumanNpc;
import io.github.longshou.mcyukkuri.life.GlassSight;
import io.github.longshou.mcyukkuri.social.Bonds;
import io.github.longshou.mcyukkuri.social.ProtectivePuff;
import io.github.longshou.mcyukkuri.social.SocialDisposition;
import java.util.Set;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import static io.github.longshou.mcyukkuri.social.SocialDisposition.ramp;

/** One response to a committed event. The relation remembers the actor; existing goals own any flight. */
public final class PunishmentReaction {
    private enum Response {
        DEFIANT(YukkuriAction.PUFF, "cheek_puff", "puff"),
        FREEZE(YukkuriAction.BRACE, "horrified", ""),
        ENDURE(YukkuriAction.BRACE, "endure", ""),
        PLEAD(YukkuriAction.BEG, "teary", "plea_sad"),
        ESCAPE(YukkuriAction.TREMBLE, "afraid", "afraid"),
        SHRINK(YukkuriAction.DROOP, "teary_frown", ""),
        TURN_TO_KIN(YukkuriAction.BRACE, "teary", ""),
        PROTECT(YukkuriAction.PUFF, "cheek_puff", "protect_taunt");

        final YukkuriAction action;
        final String face, line;
        Response(YukkuriAction action, String face, String line) {
            this.action = action;
            this.face = face;
            this.line = line;
        }

        void show(YukkuriEntity body, LivingEntity focus) {
            body.getLookControl().setLookAt(focus, 30, 30);
            body.forceReaction(action, face, line);
        }
    }

    public static boolean isHuman(Entity actor) { return actor instanceof Player || actor instanceof HumanNpc; }

    public static boolean sees(YukkuriEntity witness, YukkuriEntity target, LivingEntity punisher) {
        return GlassSight.sees(witness, target) && GlassSight.sees(witness, punisher);
    }

    public static void human(YukkuriEntity target, LivingEntity punisher, String incident, boolean injured) {
        var mind = target.life().personality();
        double resistance = (2 * mind.boldness() + mind.selfControl()) / 3.0;
        int fearGain = (int) Math.round((injured ? 25 : 12) * (1.25 - .5 * ramp(resistance, 35, 70)));
        respond(target, punisher, incident, injured ? -20 : -6, fearGain);
    }

    public static void peer(YukkuriEntity target, YukkuriEntity punisher, String incident, boolean bitten) {
        // Sanction already recorded its affinity change, and an accepted bite recorded its damage relation.
        respond(target, punisher, incident, 0, bitten ? 0 : 12);
    }

    private static void respond(YukkuriEntity target, LivingEntity punisher, String incident,
                                int closenessChange, int fearGain) {
        if (!target.isAlive() || target.body().dying()) {
            GameplayLog.event(target, "punishment", "victim actor={} incident={} response=collapse",
                    punisher.getUUID(), incident);
            witness(target, punisher, incident);
            return;
        }
        boolean human = isHuman(punisher);
        var personality = target.life().personality();
        int before = personality.fear(punisher.getUUID());
        personality.relationshipChanged(punisher.getUUID(), closenessChange, fearGain);
        int fear = personality.fear(punisher.getUUID());
        double wary = ramp(fear, 15, 65);
        double esteem = ramp(SocialDisposition.selfEsteem(target), 40, 65);
        double bold = ramp(personality.boldness(), 30, 80);
        double control = ramp(personality.selfControl(), 30, 80);
        double unease = ramp(target.mind().unease(), 40, 85);
        int wounds = target.body().condition().painSeverity();
        double sound = ramp(target.body().healthRatio(), .45, .95) * (1 - .3 * ramp(wounds, 0, 5));
        double energy = ramp(target.life().energy(), 25, 60);
        boolean exit = (human || target.isPanicking()) && energy > 0 && Reactions.free(target)
                && target.onGround() && !target.isLeashed() && !target.hurtCrying() && !target.body().weak()
                && !target.shellRetraction().available() && !target.grief().protecting()
                && Reactions.escapeStand(target, punisher, 5, Set.of()) != null;
        var response = Response.DEFIANT;
        double best = (.45 * esteem + .32 * bold + .23 * sound) * (1 - wary) * (1 - .45 * unease);
        double freeze = (.28 + .38 * (1 - bold) + .34 * unease) * (.55 + .45 * wary) * (exit ? .65 : 1);
        if (freeze > best) { best = freeze; response = Response.FREEZE; }
        double endure = control * (.28 + .37 * sound + .35 * (1 - unease)) * (1 - .5 * wary);
        if (endure > best) { best = endure; response = Response.ENDURE; }
        double plead = (.43 * wary + .37 * unease + .20 * (1 - bold)) * (1 - .62 * control)
                * (1 - .35 * esteem) * (.9 + .25 * (1 - sound));
        if (plead > best && GlassSight.sees(target, punisher)) { best = plead; response = Response.PLEAD; }
        double escape = (.52 * wary + .28 * unease + .20 * energy) * (.55 + .45 * sound);
        if (exit && escape > best) response = Response.ESCAPE;

        if (response == Response.ESCAPE && !target.isPanicking()) {
            target.fleeFrom(punisher);
            target.mind().feeling().frightened(.6f);
        }
        response.show(target, punisher);
        GameplayLog.event(target, "punishment", "victim actor={} incident={} response={} fear={} -> {} health={} wounds={} unease={} exit={}",
                punisher.getUUID(), incident, response, before, fear, target.getHealth(), wounds, target.mind().unease(), exit);
        witness(target, punisher, incident);
    }

    private static void witness(YukkuriEntity target, LivingEntity punisher, String incident) {
        int range = LearnedFear.WITNESS_RANGE;
        for (var observer : target.level().getEntitiesOfClass(YukkuriEntity.class, target.getBoundingBox().inflate(range),
                body -> body != target && body != punisher && body.isAlive() && !body.body().dying()
                        && !body.life().isSleeping() && !body.grieving() && body.distanceToSqr(target) <= range * range
                        && sees(body, target, punisher))) {
            LearnedFear.remember(observer, target, punisher, incident);
            var mind = observer.life().personality();
            double nerves = .55 * (1 - ramp(mind.boldness(), 25, 75))
                    + .25 * ramp(mind.fear(punisher.getUUID()), 0, 65) + .20 * ramp(observer.mind().unease(), 40, 85);
            double composure = .30 + .60 * ramp(mind.selfControl(), 30, 80);
            double attachment = ramp(mind.attachment(), 30, 80);
            boolean kin = observer.life().canRecognize(target) && Bonds.careOrMate(observer, target);
            var reaction = nerves > composure ? Response.SHRINK : Response.FREEZE;
            if (kin && attachment > Math.max(nerves, .45)) {
                reaction = ProtectivePuff.parentOf(target, observer) && GlassSight.sees(target, observer)
                        && nerves < .4 ? Response.PROTECT : Response.TURN_TO_KIN;
            }
            reaction.show(observer, reaction == Response.TURN_TO_KIN ? target : punisher);
            boolean glass = !observer.hasLineOfSight(target) || !observer.hasLineOfSight(punisher);
            GameplayLog.event(observer, "punishment", "witness victim={} actor={} incident={} response={} glass={}",
                    target.getUUID(), punisher.getUUID(), incident, reaction, glass);
        }
    }

    private PunishmentReaction() {}
}
