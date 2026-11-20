package io.github.longshou.mcyukkuri.headwear;

import io.github.longshou.mcyukkuri.dialogue.ReactionSpeech;
import io.github.longshou.mcyukkuri.social.Temperament.Gate;
import io.github.longshou.mcyukkuri.social.SocialDisposition;
import io.github.longshou.mcyukkuri.settlement.SettlementSociety;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.mind.EmotionCourse;
import io.github.longshou.mcyukkuri.mind.Ousting;
import java.util.Comparator;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;

/**
 * Contempt for the hatless, the root of the hatless individual's misery (user correction 2026-10-18; komicolle 40720
 * No.11518829 "socially erased", No.11518847 "not recognised as one of its kind"). Every yukkuri that sees a living hatless
 * individual it cannot recognise ({@link Recognition}) despises it: its affinity drops, it makes a face and says so. The scorned one answers by its
 * nature ({@code PeerDefense}) and thinks less of the scorner. The harsh ones among them bully it ({@link Sanction}, reason {@code hatless}). Kin are no exception: a mother that
 * cannot see past the missing ribbon scorns her own child exactly as she would a stranger.
 *
 * <p>A hatless body that is also badly hurt ({@link Recognition#grotesque}) is not despised but feared as a monster: the
 * observer is frightened (unease), the timid flee, the harsh gang up to drive it off (reason {@code monster}). It is never
 * taken for food here. Pets and owned individuals are scorned too, but only free ones are bullied or driven off.
 *
 * <p>Ranges, cadence and amounts are project design.
 */
public final class Contempt {
    /** Sight range for noticing a hatless stranger. */
    public static final double RANGE = 6;
    /** Affinity lost per scornful sighting, and the floor contempt alone drives it to. */
    public static final int SCORN = -3, SCORN_FLOOR = -40;
    /** Unease added to the one scorned, per scornful sighting. */
    public static final float SCORNED = .5f;
    /** Ticks until the next look when nothing was found, and after a reaction. */
    static final int IDLE_WAIT = 60, AFTER_SCORN = 600, AFTER_MONSTER = 600;

    private Contempt() {}

    private static boolean perceives(YukkuriEntity e) {
        return e.isAlive() && !e.isNoAi() && !e.isRemoved() && !e.life().isSleeping() && !e.isPanicking() && !e.isCommandPlaying()
                && !e.mind().nonYukkuri() && !e.profile().dangerToYukkuri() && !e.sanction().active() && !e.operations().active()
                && !e.body().dying();
    }
    /** Free to walk away (not eating, not carried, not in the middle of family business). */
    private static boolean mobile(YukkuriEntity e) {
        return !e.isPassenger() && e.life().mealTicks() == 0 && !e.family().active() && !e.life().hasUrgentNeed();
    }
    private static AABB sightBox(YukkuriEntity observer) {
        return observer.getBoundingBox().inflate(RANGE, 3, RANGE);
    }
    static String describe(YukkuriEntity observer) {
        var nearby = observer.level().getEntitiesOfClass(YukkuriEntity.class, sightBox(observer),
                        y -> y != observer && y.isAlive() && !y.life().hasAccessory())
                .stream().sorted(Comparator.comparingDouble(observer::distanceToSqr)).limit(6)
                .map(y -> "#" + y.getId() + "[known=" + Recognition.recognizes(observer, y)
                        + ",sight=" + observer.hasLineOfSight(y) + ",wait=" + observer.headwear().contactWait(y) + "]").toList();
        return "perceives=" + perceives(observer) + " mobile=" + mobile(observer) + " nearby=" + String.join(",", nearby);
    }

    /** One look around by {@code observer} (from Headwear.clock); returns the ticks until the next look. */
    static int observe(YukkuriEntity observer) {
        if (!(observer.level() instanceof ServerLevel level) || !perceives(observer)) return IDLE_WAIT;
        boolean mocks = observer.life().personality().temperament().is(Gate.HARSH);
        var nearby = level.getEntitiesOfClass(YukkuriEntity.class, sightBox(observer),
                        y -> y != observer && y.isAlive() && !y.profile().dangerToYukkuri()
                                && (Recognition.unrecognizedHatless(observer, y) || mocks && Recognition.junkCovered(y)
                                || rejectsDirt(observer, y))
                                && observer.headwear().readyForHatlessContact(y) && observer.hasLineOfSight(y))
                .stream().sorted(Comparator.comparingDouble(observer::distanceToSqr)).toList();
        for (var target : nearby) {
            if (Recognition.unrecognizedHatless(observer, target)) {
                boolean grotesque = Recognition.grotesque(target);
                if (!grotesque && observer.cannibalism().chooseHuntOverScorn(target)) continue;
                return react(level, observer, target, grotesque);
            }
            if (mocks && Recognition.junkCovered(target)) return mock(level, observer, target);
            return snubDirt(observer, target);
        }
        return IDLE_WAIT;
    }

    private static boolean rejectsDirt(YukkuriEntity observer, YukkuriEntity target) {
        if (target.status().marks().dirt() < 2 || target.settlement().society().slave()) return false;
        var relation = observer.life().personality().relation(target.getUUID());
        double patience = SocialDisposition.ramp(observer.life().personality().friendliness(), 25, 80);
        return SocialDisposition.dirtAversion(target, relation) * (1 - .6 * patience) >= .45;
    }

    private static int snubDirt(YukkuriEntity observer, YukkuriEntity target) {
        if (!observer.showReaction("pursed", "", 60)) return IDLE_WAIT;
        observer.getLookControl().setLookAt(target, 30, 30);
        var personality = observer.life().personality();
        int closeness = personality.trust(target.getUUID());
        if (closeness > -10) personality.relationshipChanged(target.getUUID(), -1, 0);
        observer.headwear().coolHatlessContact(target, AFTER_SCORN);
        if (!target.life().isSleeping()) target.showReaction("look_down", "", 60);
        GameplayLog.event(observer, "contempt", "dirt snub target={} dirt={} affinity={}->{}", target.getUUID(),
                target.status().marks().dirt(), closeness, personality.trust(target.getUUID()));
        return AFTER_SCORN;
    }

    private static int mock(ServerLevel level, YukkuriEntity observer, YukkuriEntity target) {
        if (!observer.showReaction("cheek_puff", "puff", 60)) return IDLE_WAIT;
        observer.getLookControl().setLookAt(target, 30, 30);
        var relations = YukkuriWorldData.get(level);
        int before = relations.affinity(observer.getUUID(), target.getUUID());
        if (before > -10) relations.relate(observer.getUUID(), target.getUUID(), -1);
        observer.headwear().coolHatlessContact(target, AFTER_SCORN);
        // The mocked wearer answers by its own disposition on its next beat: a haughty one retorts and shakes it off, a lowly one winces.
        target.headwear().markMocked();
        GameplayLog.event(observer, "contempt", "junk mocked target={} variant={} affinity={} -> {}",
                target.getUUID(), target.life().custody().junk().id(), before, relations.affinity(observer.getUUID(), target.getUUID()));
        return AFTER_SCORN;
    }
    /**
     * {@code observer} faces a hatless individual it cannot recognise: a monster when horribly hurt, otherwise despised.
     * Used when a greeting walks up to one (YukkuriSocial).
     */
    public static void react(ServerLevel level, YukkuriEntity observer, YukkuriEntity target) {
        if (!Recognition.unrecognizedHatless(observer, target) || !observer.headwear().readyForHatlessContact(target)) return;
        boolean grotesque = Recognition.grotesque(target);
        if (!grotesque && observer.cannibalism().chooseHuntOverScorn(target)) return;
        react(level, observer, target, grotesque);
    }

    private static int react(ServerLevel level, YukkuriEntity observer, YukkuriEntity target, boolean grotesque) {
        int wait = grotesque ? monster(level, observer, target) : scorn(level, observer, target);
        observer.headwear().recordContact(target, grotesque);
        observer.headwear().coolHatlessContact(target, wait);
        return wait;
    }

    /** Despise, then keep away, or (harsh) bully. */
    static int scorn(ServerLevel level, YukkuriEntity observer, YukkuriEntity target) {
        var relations = YukkuriWorldData.get(level);
        int affinity = relations.affinity(observer.getUUID(), target.getUUID());
        int before = affinity;
        if (affinity > SCORN_FLOOR) relations.relate(observer.getUUID(), target.getUUID(), Math.max(SCORN, SCORN_FLOOR - affinity));
        affinity = relations.affinity(observer.getUUID(), target.getUUID());
        observer.getLookControl().setLookAt(target, 30, 30);
        boolean bully = observer.family().adult() && observer.life().personality().temperament().is(Gate.HARSH) && Sanction.eligibleTarget(target) && Sanction.enabled()
                && observer.getRandom().nextInt(100) < observer.life().personality().escalationScore();
        if (bully && Sanction.request(target, "hatless", observer)) {
            GameplayLog.event(observer, "contempt", "scorn target={} known=false intelligence={} affinity={} -> {} sanction=request_accepted",
                    target.getUUID(), observer.life().personality().intelligence(), before, affinity);
            return AFTER_SCORN;
        }
        observer.showReaction("sneer", "hatless_scorn", 80);
        // The scorned one answers by its own nature (flee, puff back or ignore), and thinks less of the scorner in turn.
        int regard = relations.affinity(target.getUUID(), observer.getUUID());
        if (regard > SCORN_FLOOR) relations.relate(target.getUUID(), observer.getUUID(), Math.max(SCORN, SCORN_FLOOR - regard));
        if (!SettlementSociety.sparesHatlessBlame(observer, target)) target.groupInteraction().defense().provoke(observer);
        suffer(target, observer, "teary", 80, SCORNED, "scorned", SettlementSociety.sameGroup(observer, target));
        GameplayLog.event(observer, "contempt", "scorn target={} known=false intelligence={} affinity={} -> {} sanction=request_not_accepted attempted={}",
                target.getUUID(), observer.life().personality().intelligence(), before, affinity, bully);
        return AFTER_SCORN + observer.getRandom().nextInt(200);
    }

    /** A hatless, horribly hurt thing: fear; the harsh gang up to drive it away, everyone else flees. */
    static int monster(ServerLevel level, YukkuriEntity observer, YukkuriEntity target) {
        var relations = YukkuriWorldData.get(level);
        int before = relations.affinity(observer.getUUID(), target.getUUID());
        if (relations.affinity(observer.getUUID(), target.getUUID()) > SCORN_FLOOR) relations.relate(observer.getUUID(), target.getUUID(), -5);
        observer.mind().add(6, "monster");
        observer.showReaction("surprise", "hatless_monster", 100);
        suffer(target, observer, "cry", 100, 2, "feared_as_monster", false);
        boolean drive = observer.family().adult() && observer.life().personality().temperament().is(Gate.HARSH) && Sanction.eligibleTarget(target) && Sanction.enabled();
        boolean accepted = drive && Sanction.request(target, "monster", observer);
        boolean frightened = false;
        if (!accepted) {
            if (mobile(observer)) { observer.frightenFrom(target); frightened = true; }
        }
        GameplayLog.event(observer, "contempt", "monster target={} known=false affinity={} -> {} sanctionAccepted={} frightenRequested={}",
                target.getUUID(), before, relations.affinity(observer.getUUID(), target.getUUID()), accepted, frightened);
        return AFTER_MONSTER + observer.getRandom().nextInt(200);
    }

    /**
     * The face and line of the one being scorned or bullied, with exactly {@code unease} added: the line is only spoken, so
     * the reaction event's own unease (MindLife.felt) is not added on top.
     */
    private static void suffer(YukkuriEntity target, YukkuriEntity source, String face, int ticks, float unease, String cause, boolean pitPressure) {
        if (!target.isAlive()) return;
        if (pitPressure) target.mind().addSlavePressure(unease, cause); else target.mind().add(unease, cause);
        if (!target.groupInteraction().defense().active() && !target.isPanicking() && target.showReaction(face, "", ticks)) {
            String line = ReactionSpeech.contempt(pitPressure && target.getRandom().nextInt(3) == 0);
            target.speak(line);
        }
        // An insult weighs less than being driven from a home (.5).
        if (!pitPressure && Ousting.fumes(target.life().personality().temperament()))
            target.mind().feeling().begin(EmotionCourse.Kind.RAGE, .35f, source.getUUID(), target.blockPosition());
    }
}
