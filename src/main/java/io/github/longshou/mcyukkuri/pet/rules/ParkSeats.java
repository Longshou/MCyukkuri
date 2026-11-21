package io.github.longshou.mcyukkuri.pet.rules;

import java.util.ArrayList;
import java.util.List;

/**
 * Where an owner sits to watch the pets at a park facility: benches a few blocks from the facility, the best few kept so two
 * owners spread over them, and the long side of the bench that looks at the facility.
 */
public final class ParkSeats {
    /** Horizontal gap from the facility centre to a seat centre. A bench pair beside a 3x3 facility sits 3 away; near enough to see the
     *  pets' faces, far enough that the sitter is not in the run. */
    public static final double NEAR = 2, FAR = 9;
    /** Seats kept after sorting nearest first; one owner's pick is stable, two owners usually differ. */
    public static final int KEEP = 3;

    private ParkSeats() {}

    public static boolean watchable(double gap) { return gap >= NEAR && gap <= FAR; }

    /** The best {@link #KEEP} seats, the one chosen by the owner's hash first and the others after it (a fallback if its route fails). */
    public static <Seat> List<Seat> order(List<Seat> nearestFirst, int ownerHash) {
        var best = nearestFirst.subList(0, Math.min(KEEP, nearestFirst.size()));
        var ordered = new ArrayList<Seat>(best.size());
        for (int i = 0; i < best.size(); i++) ordered.add(best.get((Math.floorMod(ownerHash, best.size()) + i) % best.size()));
        return ordered;
    }

    /** A bench has two long sides; {@code along} is the facility's offset from the seat along the bench's first normal. */
    public static boolean facesFirstNormal(double along) { return along >= 0; }
}
