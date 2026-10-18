package io.github.longshou.mcyukkuri.headwear;

import io.github.longshou.mcyukkuri.social.Temperament;
import io.github.longshou.mcyukkuri.MCYukkuri;
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
    /** Affinity at or below which a harsh observer will bully at once; otherwise one chance in {@link #BULLY_ODDS}. */
    public static final int BULLY_AT = -15, BULLY_ODDS = 4;
    /** Ticks until the next look when nothing was found, and after a reaction. */
    static final int IDLE_WAIT = 60, AFTER_SCORN = 400, AFTER_MONSTER = 600;

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

    /** One look around by {@code observer} (from Headwear.clock); returns the ticks until the next look. */
    static int observe(YukkuriEntity observer) {
        if (!(observer.level() instanceof ServerLevel level) || !perceives(observer)) return IDLE_WAIT;
        var target = level.getEntitiesOfClass(YukkuriEntity.class, observer.getBoundingBox().inflate(RANGE, 3, RANGE),
                        y -> y != observer && !y.isRemoved() && !y.profile().dangerToYukkuri() && Recognition.unrecognizedHatless(observer, y)
                                && observer.hasLineOfSight(y))
                .stream().min(Comparator.comparingDouble(observer::distanceToSqr)).orElse(null);
        if (target == null) return IDLE_WAIT;
        return react(level, observer, target);
    }
    /**
     * {@code observer} faces a hatless individual it cannot recognise: a monster when horribly hurt, otherwise despised.
     * Also used when a greeting walks up to one (YukkuriSocial). Returns the ticks until the observer looks again.
     */
    public static int react(ServerLevel level, YukkuriEntity observer, YukkuriEntity target) {
        return Recognition.grotesque(target) ? monster(level, observer, target) : scorn(level, observer, target);
    }

    /** Despise, then keep away, or (harsh) bully. */
    static int scorn(ServerLevel level, YukkuriEntity observer, YukkuriEntity target) {
        var relations = YukkuriWorldData.get(level);
        int affinity = relations.affinity(observer.getUUID(), target.getUUID());
        if (affinity > SCORN_FLOOR) relations.relate(observer.getUUID(), target.getUUID(), Math.max(SCORN, SCORN_FLOOR - affinity));
        affinity = relations.affinity(observer.getUUID(), target.getUUID());
        observer.getLookControl().setLookAt(target, 30, 30);
        boolean bully = observer.family().adult() && harsh(observer) && Sanction.eligibleTarget(target) && Sanction.enabled()
                && (affinity <= BULLY_AT || observer.getRandom().nextInt(BULLY_ODDS) == 0);
        if (bully && Sanction.request(target, "hatless", observer)) return AFTER_SCORN;
        observer.showReaction("angry", "hatless_scorn", 80);
        if (mobile(observer)) keepAway(observer, target, 5);
        suffer(target, "teary", target.getRandom().nextInt(3) == 0 ? "settlement_hurt" : "", 80, SCORNED, "scorned");
        MCYukkuri.LOGGER.debug("[contempt] {} #{} scorns hatless {} #{} (affinity {})", observer.getVariant().assetId(), observer.getId(),
                target.getVariant().assetId(), target.getId(), affinity);
        return AFTER_SCORN + observer.getRandom().nextInt(200);
    }

    /** A hatless, horribly hurt thing: fear; the harsh gang up to drive it away, everyone else flees. */
    static int monster(ServerLevel level, YukkuriEntity observer, YukkuriEntity target) {
        var relations = YukkuriWorldData.get(level);
        if (relations.affinity(observer.getUUID(), target.getUUID()) > SCORN_FLOOR) relations.relate(observer.getUUID(), target.getUUID(), -5);
        observer.mind().add(6, "monster");
        observer.showReaction("surprise", "hatless_monster", 100);
        suffer(target, "cry", "settlement_hurt", 100, 2, "feared_as_monster");
        boolean drive = observer.family().adult() && harsh(observer) && Sanction.eligibleTarget(target) && Sanction.enabled();
        if (!(drive && Sanction.request(target, "monster", observer))) {
            if (mobile(observer)) observer.frightenFrom(target);
        }
        MCYukkuri.LOGGER.info("[contempt] {} #{} takes hatless {} #{} for a monster ({})", observer.getVariant().assetId(), observer.getId(),
                target.getVariant().assetId(), target.getId(), observer.sanction().target() == target ? "drives it off" : "flees");
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
