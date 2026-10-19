package io.github.longshou.mcyukkuri.headwear;

import io.github.longshou.mcyukkuri.body.BodySigns;
import io.github.longshou.mcyukkuri.cognition.Cognition;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.pet.Ability;
import io.github.longshou.mcyukkuri.status.YukkuriStatus;
import net.minecraft.nbt.CompoundTag;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity;

/**
 * The one recognition test (docs/HEADWEAR.md). A yukkuri knows another by its accessory (komicolle 40720 No.11518847).
 * Without it, only a clever observer still sees who it is: whether the observer can is decided by its intellect alone,
 * never by its badge, its kinship or its friendship (user correction 2026-10-18). A parent that cannot see past the missing
 * hat treats its own child as a hatless stranger: no feeding, no defending, the same contempt.
 *
 * <p>Intellect = cognitive ability ({@link Cognition}: innate + learned, wild draw 50-200) + {@value #WITS_WEIGHT} x the
 * trained wits (知力, {@code pet.Ability.WITS}, 0-100; a wild individual has 0). Threshold {@value #THRESHOLD}. With the
 * existing distributions (project design, see the doc for the numbers): about 7% of wild-drawn individuals pass, Myon
 * (cognition x0.6) never does on its own, a silver badge (wits 50 or more) passes when its cognition is about 90 or more,
 * a gold badge (wits 70 or more) always passes, bronze (wits not examined, usually below 40) normally does not.
 */
public final class Recognition {
    /** Intellect needed to recognise a yukkuri that has lost its accessory (project number). */
    public static final int THRESHOLD = 190;
    /** Weight of the trained wits (0-100) against the cognitive ability (0-1000 scale). */
    public static final int WITS_WEIGHT = 2;
    /** Share of health at or below which a hatless body is a "monster" to those who cannot recognise it. */
    public static final float MONSTER_HEALTH = .35f;

    private Recognition() {}

    public static int intellect(YukkuriEntity e) {
        return Cognition.ability(e) + WITS_WEIGHT * e.pet().ability(Ability.WITS);
    }
    /** Clever enough to know a hatless yukkuri for what (and who) it is. */
    public static boolean seesPastAccessory(YukkuriEntity observer) { return intellect(observer) >= THRESHOLD; }

    /** The owner sitting in its own working hat boat still has its hat. */
    private static boolean afloatInOwnHat(YukkuriEntity target) {
        return target.getVehicle() instanceof HatBoatEntity boat
                && target.getUUID().equals(boat.ownerYukkuri()) && boat.protects(target);
    }
    /** {@code observer} knows {@code target} as a yukkuri (and as itself, so kin as kin). */
    public static boolean recognizes(YukkuriEntity observer, YukkuriEntity target) {
        if (observer == target || target.life().hasAccessory() || afloatInOwnHat(target)) return true;
        return seesPastAccessory(observer);
    }
    /** The observer cannot tell that this living hatless individual is a yukkuri; kin are no exception. */
    public static boolean unrecognizedHatless(YukkuriEntity observer, YukkuriEntity target) {
        return observer != target && target.isAlive() && !recognizes(observer, target);
    }
    /** Unrecognised and nobody's property, not a predator: may be bullied, driven off or hunted (pets and owned are safe, C11). */
    public static boolean fairGame(YukkuriEntity observer, YukkuriEntity target) {
        return unrecognizedHatless(observer, target) && YukkuriStatus.identity(target).free() && !target.profile().dangerToYukkuri();
    }
    /** Remains are known as a yukkuri when the hat was on at death, or by an observer clever enough. */
    public static boolean remainsRecognized(YukkuriEntity observer, CompoundTag remains) {
        return !remains.contains("Accessory") || remains.getBoolean("Accessory") || seesPastAccessory(observer);
    }

    /** A living hatless body that is badly hurt: dying, a deep wound, mould spread, or little health left. */
    public static boolean grotesque(YukkuriEntity target) {
        if (!target.isAlive() || target.life().hasAccessory()) return false;
        var body = target.body();
        return body.dying() || body.healthRatio() <= MONSTER_HEALTH || BodySigns.moldTier(body.mold()) >= 2 || !body.deepParts().isEmpty();
    }
    /** Not even taken for a yukkuri, and horribly hurt: a monster to this observer (feared and driven off, not eaten). */
    public static boolean seenAsMonster(YukkuriEntity observer, YukkuriEntity target) {
        return unrecognizedHatless(observer, target) && grotesque(target);
    }
}
