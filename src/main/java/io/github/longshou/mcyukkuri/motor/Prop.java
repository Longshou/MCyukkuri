package io.github.longshou.mcyukkuri.motor;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/** A prop a body carries, sets down and takes back: the hat, through the one interface the motor layer may know. Every method reads except {@link #setDown} and {@link #takeBack}. */
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
