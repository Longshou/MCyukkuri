package io.github.longshou.mcyukkuri.headwear;

import io.github.longshou.mcyukkuri.body.BodyPart;
import io.github.longshou.mcyukkuri.body.WoundSurface;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.status.YukkuriStatus;
import net.minecraft.nbt.CompoundTag;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity;

/**
 * The one recognition test (docs/HEADWEAR.md). A yukkuri knows another by its accessory (komicolle 40720 No.11518847).
 * Without it, a clever observer still sees who it is; aquatic Marisa is the one innate exception. Neither route depends on
 * the observer's badge, kinship or friendship (user correction 2026-10-18). A parent that cannot see past the missing hat
 * treats its own child as a hatless stranger: no feeding, no defending, the same contempt.
 *
 * <p>Recognition reads the innate-and-experienced intelligence axis directly. At {@value #THRESHOLD} or above an observer
 * can recognise a hatless yukkuri; below it, the accessory remains necessary. This is independent of badge grade,
 * lineage or a separate cognition score.
 */
public final class Recognition {
    /** Intellect needed to recognise a yukkuri that has lost its accessory (project number). */
    public static final int THRESHOLD = 80;
    /** Project threshold: visibly damaged skin on at least two different body regions. */
    public static final int MONSTER_REGIONS = 2;

    private Recognition() {}

    public static int intellect(YukkuriEntity e) {
        return e.life().personality().intelligence();
    }
    /** Aquatic Marisa knows the hatless as yukkuri by nature; other observers still need sufficient intellect. */
    public static boolean seesPastAccessory(YukkuriEntity observer) {
        return observer.profile().aquatic() || intellect(observer) >= THRESHOLD;
    }

    /** Only a nearby, loaded physical hat is visible to other yukkuri; unresolved custody only calms its owner. */
    private static boolean custodiedInOwnHat(YukkuriEntity target) {
        if (target.getVehicle() instanceof HatBoatEntity riding && target.getUUID().equals(riding.ownerYukkuri())
                && riding.protects(target)) return true;
        var boat = HatBoatEntity.owned(target);
        return boat != null && target.distanceToSqr(boat) <= 64;
    }
    /** {@code observer} knows {@code target} as a yukkuri (and as itself, so kin as kin). */
    public static boolean recognizes(YukkuriEntity observer, YukkuriEntity target) {
        if (observer == target || target.life().hasAccessory() || custodiedInOwnHat(target)) return true;
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

    /** Count visibly damaged regions, rather than treating internal illness or low health as a torn appearance. */
    public static long damagedRegions(YukkuriEntity target) {
        var mesh = WoundSurface.get(target.subspecies().assetId());
        return target.body().wounds().stream().filter(w -> w.severity() >= 2 && mesh.valid(w))
                .map(w -> BodyPart.of(mesh, w)).distinct().count();
    }
    /** A living hatless body with multiple visibly damaged regions. */
    public static boolean grotesque(YukkuriEntity target) {
        if (!target.isAlive() || target.life().hasAccessory()) return false;
        return damagedRegions(target) >= MONSTER_REGIONS;
    }
    /** Not even taken for a yukkuri, and horribly hurt: a monster to this observer (feared and driven off, not eaten). */
    public static boolean seenAsMonster(YukkuriEntity observer, YukkuriEntity target) {
        return unrecognizedHatless(observer, target) && grotesque(target);
    }
}
