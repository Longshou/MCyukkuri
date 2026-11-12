package io.github.longshou.mcyukkuri.motor;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriComponents;
import io.github.longshou.mcyukkuri.entity.YukkuriView;
import io.github.longshou.mcyukkuri.family.ParentCarry;
import io.github.longshou.mcyukkuri.motor.action.PlaceProp;
import io.github.longshou.mcyukkuri.motor.action.TakeProp;

/**
 * The one authority on what a Marisa's braid holds this tick (project design, user 2026-11-06: braid occupancy has one authority):
 * a young ({@link ParentCarry#braidGripping}), her hat on its way on or off (the hat clips, which keep the stick in the tuft), her
 * held stick (her kit in {@link Held.Place#GRIP}), or nothing. Read on both sides from the hold record, the motor action and the
 * held record; never stored.
 */
public final class Braid {
    public enum Use { NONE, FREE, STICK, YOUNG, HAT }

    private Braid() {}

    /** What her braid does now; NONE for a body without one. */
    public static Use use(YukkuriEntity body) {
        if (!ContactGeometry.braided(body)) return Use.NONE;
        if (gripping(body)) return Use.YOUNG;
        String action = body.motorAction();
        if (action.equals(TakeProp.ID) || action.equals(PlaceProp.ID)) return Use.HAT;
        return !body.hatKit().isEmpty() && body.life().custody().kitPlace() == Held.Place.GRIP ? Use.STICK : Use.FREE;
    }

    /** Her braid grips a young now (at its tip, on its way onto or off the brim or head, or closing on it). */
    public static boolean gripping(YukkuriView body) { return ContactGeometry.braided(body) && ParentCarry.braidGripping(body); }

    /** Her mouth holds nothing: no item, no bite of food, no carried young. */
    public static boolean mouthFree(YukkuriEntity body) {
        return body.component(YukkuriComponents.MOUTH).freeForKit();
    }
}
