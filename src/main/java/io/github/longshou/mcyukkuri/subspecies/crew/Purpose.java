package io.github.longshou.mcyukkuri.subspecies.crew;

/** Why a body asks for a seat. Every temporary need is a lease of one of these kinds; nothing else holds a seat. */
public enum Purpose {
    /** An ordinary seat: boarding, a newborn's landing, a returning pupil. */
    BOARD,
    /** The captain's own stand while she climbs aboard (water boarding) before she walks to her stern seat. */
    OWN,
    /** A care, toilet or practice station; the lease keeps the body's own seat as {@code returnTo}. */
    SERVICE,
    /** The first voyage keeps a pupil's departure berth while its own hat is away; it never lapses by time. */
    RETAINED,
    /** A boarded orphan or swimmer sheltered by a captain who is not its parent, until a shore hand-off. */
    REFUGE,
    /** A rescue lands a body now; juveniles and over-size children may only ever take the rescue rim. */
    RESCUE
}
