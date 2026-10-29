package io.github.longshou.mcyukkuri.motor;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * A prop a body carries, sets down and takes back (spec 9.4 {@code PlaceProp} / {@code TakeProp}): the hat, seen through
 * the one interface the motor layer may know. {@code motor/} must not import {@code subspecies/} (design P9), so the
 * a subspecies adapter implements this over the hull, hat custody and accessory transfers.
 *
 * <p>Every method is a read except {@link #setDown} and {@link #takeBack}: those are the irreversible accessory moves the
 * actions call once, at the clip's {@code release} marker. A prop knows its own placement mode (land {@code nest} or
 * floating) and whether it is being placed by its owner ({@code self}) or by a helper, because both decide the clip and
 * the role of the hull that appears.
 */
public interface Prop {
    /** Lower-case with underscores; the {@code prop=} log field. */
    String id();

    /** The prop is on the body (for a hat: {@code HatCustody.worn()}). */
    boolean worn();

    /** The prop is a real entity in the world (for a hat: {@code HatCustody.custodied()}). */
    boolean deployed();

    /** The deployed prop has bodies aboard (a hat's passengers). */
    boolean riders();

    /** The deployed prop has no bodies aboard. */
    boolean empty();

    /** The deployed prop's entity, or null when it is not in the world (the take-back reach test reads it). */
    Entity entity();

    /** Pure: does the world take the prop at this stand and yaw? */
    Feasibility fits(Vec3 at, float yaw);

    /** The irreversible placement; returns the entity it created, or null when the world refused it. */
    Entity setDown(Vec3 at, float yaw);

    /** The irreversible take-back; false when the prop cannot come back (riders, custody, reach). */
    boolean takeBack();

    /** The clip that plays the move: {@code on} true for taking it back, false for setting it down. */
    String clip(boolean on);

    /** Taken back by its brim (a hat afloat): mouth or braid holding the brim reaches it too. */
    default boolean byBrim() { return false; }
}
