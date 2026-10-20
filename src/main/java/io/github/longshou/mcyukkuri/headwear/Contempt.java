package io.github.longshou.mcyukkuri.headwear;

import io.github.longshou.mcyukkuri.social.Temperament;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import java.util.Comparator;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;

/**
 * Contempt for the hatless, the root of the hatless individual's misery (user correction 2026-10-18; komicolle 40720
 * No.11518829 "socially erased", No.11518847 "not recognised as one of its kind"). Every yukkuri that sees a living hatless
 * individual it cannot recognise ({@link Recognition}) despises it: its affinity drops, it makes a face and says so, and it
 * keeps away. The harsh ones among them bully it ({@link Sanction}, reason {@code hatless}). Kin are no exception: a mother that
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
    /** Harsh enough to bully (the same disposition the sanction asks of its executors). */
    static boolean harsh(YukkuriEntity e) {
        var p = e.life().personality();
        return Temperament.harshForContempt(p);
    }
    static String describe(YukkuriEntity observer) {
        var nearby = observer.level().getEntitiesOfClass(YukkuriEntity.class, observer.getBoundingBox().inflate(RANGE, 3, RANGE),
                        y -> y != observer && y.isAlive() && !y.life().hasAccessory())
                .stream().sorted(Comparator.comparingDouble(observer::distanceToSqr)).limit(6)
                .map(y -> "#" + y.getId() + "[known=" + Recognition.recognizes(observer, y)
                        + ",sight=" + observer.hasLineOfSight(y) + ",wait=" + observer.headwear().contactWait(y) + "]").toList();
        return "perceives=" + perceives(observer) + " mobile=" + mobile(observer) + " nearby=" + String.join(",", nearby);
    }

    /** One look around by {@code observer} (from Headwear.clock); returns the ticks until the next look. */
    static int observe(YukkuriEntity observer) {
        if (!(observer.level() instanceof ServerLevel level) || !perceives(observer)) return IDLE_WAIT;
        var target = level.getEntitiesOfClass(YukkuriEntity.class, observer.getBoundingBox().inflate(RANGE, 3, RANGE),
                        y -> y != observer && !y.isRemoved() && !y.profile().dangerToYukkuri() && Recognition.unrecognizedHatless(observer, y)
                                && observer.headwear().readyForHatlessContact(y) && observer.hasLineOfSight(y))
                .stream().min(Comparator.comparingDouble(observer::distanceToSqr)).orElse(null);
        if (target == null) return IDLE_WAIT;
        return react(level, observer, target);
    }
    /**
     * {@code observer} faces a hatless individual it cannot recognise: a monster when horribly hurt, otherwise despised.
     * Also used when a greeting walks up to one (YukkuriSocial). Returns the ticks until the observer looks again.
     */
    public static int react(ServerLevel level, YukkuriEntity observer, YukkuriEntity target) {
        if (!Recognition.unrecognizedHatless(observer, target) || !observer.headwear().readyForHatlessContact(target)) return IDLE_WAIT;
        boolean grotesque = Recognition.grotesque(target);
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
        boolean bully = observer.family().adult() && harsh(observer) && Sanction.eligibleTarget(target) && Sanction.enabled()
                && observer.getRandom().nextInt(100) < observer.life().personality().escalationScore();
        if (bully && Sanction.request(target, "hatless", observer)) {
            GameplayLog.event(observer, "contempt", "scorn target={} known=false intelligence={} affinity={} -> {} sanction=request_accepted",
                    target.getUUID(), observer.life().personality().intelligence(), before, affinity);
            return AFTER_SCORN;
        }
        observer.showReaction("angry", "hatless_scorn", 80);
        if (mobile(observer)) keepAway(observer, target, 5);
        suffer(target, "teary", target.getRandom().nextInt(3) == 0 ? "settlement_hurt" : "", 80, SCORNED, "scorned");
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
        suffer(target, "cry", "settlement_hurt", 100, 2, "feared_as_monster");
        boolean drive = observer.family().adult() && harsh(observer) && Sanction.eligibleTarget(target) && Sanction.enabled();
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
    static void suffer(YukkuriEntity target, String face, String line, int ticks, float unease, String cause) {
        if (!target.isAlive()) return;
        target.mind().add(unease, cause);
        target.showReaction(face, "", ticks);
        if (!line.isEmpty()) target.speak(line);
    }

    private static void keepAway(YukkuriEntity observer, YukkuriEntity target, int distance) {
        var away = DefaultRandomPos.getPosAway(observer, distance, 2, target.position());
        if (away != null) observer.life().navigate(BlockPos.containing(away), 1.0, false);
    }
}
