package io.github.longshou.mcyukkuri.status;

import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.human.Custody;
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

    static boolean decidesAsPlantParent(YukkuriEntity parent, YukkuriEntity child, LivingEntity actor) {
        return isHuman(actor) && Custody.samePlantStock(parent, child) && ProtectivePuff.parentOf(child, parent);
    }

    static boolean perceives(YukkuriEntity observer, YukkuriEntity target, LivingEntity punisher) {
        int range = LearnedFear.WITNESS_RANGE;
        return observer != target && observer != punisher && observer.isAlive() && !observer.body().dying()
                && !observer.life().isSleeping() && !observer.grieving() && observer.distanceToSqr(target) <= range * range
                && sees(observer, target, punisher);
    }

    public static void human(YukkuriEntity target, LivingEntity punisher, String incident, boolean injured) {
        var mind = target.life().personality();
        double resistance = (2 * mind.boldness() + mind.selfControl()) / 3.0;
        int fearGain = (int) Math.round((injured ? 25 : 12) * (1.25 - .5 * ramp(resistance, 35, 70)));
        respond(target, punisher, incident, injured ? -20 : -6, fearGain, injured);
    }

    public static void peer(YukkuriEntity target, YukkuriEntity punisher, String incident, boolean bitten) {
        // Sanction already recorded its affinity change, and an accepted bite recorded its damage relation.
        respond(target, punisher, incident, 0, bitten ? 0 : 12, false);
    }

    /** A handover has a visible response without adding a punishment memory. */
    public static String handover(YukkuriEntity target, LivingEntity other, String defiantLine) {
        var contact = SocialDisposition.contact(target, other);
        double control = ramp(target.life().personality().selfControl(), 30, 80);
        double unease = ramp(target.mind().unease(), 35, 80);
        double freeze = contact.wariness() * (.35 + .65 * control);
        double plead = (.4 * contact.wariness() + .6 * unease) * (1 - .65 * control);
        double insult = contact.insistence();
        var response = insult > Math.max(freeze, plead) ? Response.DEFIANT : plead > freeze ? Response.PLEAD : Response.FREEZE;
        target.forceReaction(response.action, response.face, response == Response.DEFIANT ? defiantLine : response.line);
        return response.name();
    }

    private static void respond(YukkuriEntity target, LivingEntity punisher, String incident,
                                int closenessChange, int fearGain, boolean humanInjury) {
        if (!target.isAlive() || target.body().dying()) {
            GameplayLog.event(target, "punishment", "victim actor={} incident={} response=collapse",
                    punisher.getUUID(), incident);
            witness(target, punisher, incident, humanInjury);
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
        witness(target, punisher, incident, humanInjury);
    }

    private static void witness(YukkuriEntity target, LivingEntity punisher, String incident, boolean humanInjury) {
        int range = LearnedFear.WITNESS_RANGE;
        for (var observer : target.level().getEntitiesOfClass(YukkuriEntity.class, target.getBoundingBox().inflate(range),
                body -> perceives(body, target, punisher))) {
            // The visible choice is any awake seer's; the remembered fear keeps the witness-learning population.
            if (LearnedFear.eligible(observer) && !observer.operations().active())
                LearnedFear.remember(observer, target, punisher, incident);
            boolean glass = !observer.hasLineOfSight(target) || !observer.hasLineOfSight(punisher);
            if (decidesAsPlantParent(observer, target, punisher)) {
                String choice = observer.kinProtection().witnessed(target, punisher, humanInjury);
                GameplayLog.event(observer, "punishment", "witness victim={} actor={} incident={} response={} glass={}",
                        target.getUUID(), punisher.getUUID(), incident, choice, glass);
                continue;
            }
            boolean parent = ProtectivePuff.parentOf(target, observer);
            var mind = observer.life().personality();
            double nerves = .55 * (1 - ramp(mind.boldness(), 25, 75))
                    + .25 * ramp(mind.fear(punisher.getUUID()), 0, 65) + .20 * ramp(observer.mind().unease(), 40, 85);
            double composure = .30 + .60 * ramp(mind.selfControl(), 30, 80);
            double attachment = ramp(mind.attachment(), 30, 80);
            boolean kin = observer.life().canRecognize(target) && Bonds.careOrMate(observer, target);
            var reaction = nerves > composure ? Response.SHRINK : Response.FREEZE;
            if (kin && attachment > Math.max(nerves, .45)) {
                reaction = parent && GlassSight.sees(target, observer)
                        && nerves < .4 ? Response.PROTECT : Response.TURN_TO_KIN;
            }
            if (parent && humanInjury && ProtectivePuff.humanWitness(observer, target, punisher)) reaction = Response.PROTECT;
            reaction.show(observer, reaction == Response.TURN_TO_KIN ? target : punisher);
            GameplayLog.event(observer, "punishment", "witness victim={} actor={} incident={} response={} glass={}",
                    target.getUUID(), punisher.getUUID(), incident, reaction, glass);
        }
    }

    private PunishmentReaction() {}
}
