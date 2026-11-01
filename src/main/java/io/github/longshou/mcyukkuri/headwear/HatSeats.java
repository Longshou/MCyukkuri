package io.github.longshou.mcyukkuri.headwear;

import io.github.longshou.mcyukkuri.motor.geom.BodyFootprint;
import io.github.longshou.mcyukkuri.motor.geom.Vec;
import java.util.List;

/** Stateless admission into actual rider coordinates. Bounds are measured at captain growth one. */
public final class HatSeats {
    public record Body(String asset, double size, double width, double height) {}
    public record Occupant(Body body, Vec at, boolean captain) {}
    private static final double INFANT = HullBounds.DATA.number("seating.infant_max_growth");
    private static final double YOUNG = HullBounds.DATA.number("seating.young_max_growth");
    private static final Vec CAPTAIN = new Vec(HullBounds.DATA.number("captain_seat.x"),
            HullBounds.DATA.number("captain_seat.y"), HullBounds.DATA.number("captain_seat.z"));
    private static final Vec YOUNG_SEAT = youngSeat();
    private static Vec youngSeat() {
        var point = HullBounds.DATA.field("seating.young").getAsJsonArray();
        return new Vec(point.get(0).getAsDouble(), point.get(1).getAsDouble(), point.get(2).getAsDouble());
    }
    private static final List<Vec> INFANTS = HullBounds.DATA.field("seating.infants").getAsJsonArray().asList().stream()
            .map(v -> v.getAsJsonArray()).map(v -> new Vec(v.get(0).getAsDouble(), v.get(1).getAsDouble(), v.get(2).getAsDouble())).toList();

    private HatSeats() {}

    /** Existing riders retain their coordinates; only an arriving body gets a target. Null means no seat. */
    public static Vec admit(double scale, Body body, boolean captain, List<Occupant> riders) {
        if (captain) {
            var at = scaled(CAPTAIN, scale);
            return riders.stream().anyMatch(Occupant::captain) || !clear(body, at, riders) ? null : at;
        }
        var young = riders.stream().filter(r -> !r.captain()).toList();
        if ((float) body.size() <= (float) (INFANT * scale) && young.stream().allMatch(r -> (float) r.body().size() <= (float) (INFANT * scale))) {
            for (var point : INFANTS) {
                var at = scaled(point, scale);
                if (young.stream().noneMatch(r -> distance(r.at(), at) < scale * INFANT)
                        && clear(body, at, riders)) return at;
            }
            return null;
        }
        if (!young.isEmpty() || (float) body.size() > (float) (YOUNG * scale)) return null;
        var at = scaled(YOUNG_SEAT, scale);
        return clear(body, at, riders) ? at : null;
    }

    /** Growth changes eligibility, never the saved local coordinates. */
    public static boolean fits(double scale, Occupant rider, List<Occupant> others) {
        if (rider.captain()) return true;
        double limit = INFANTS.stream().anyMatch(p -> distance(scaled(p, scale), rider.at()) < scale * INFANT / 2) ? INFANT : YOUNG;
        return (float) rider.body().size() <= (float) (limit * scale) && clear(rider.body(), rider.at(), others);
    }

    private static boolean clear(Body body, Vec at, List<Occupant> riders) {
        var shape = BodyFootprint.of(body.asset(), body.width(), body.height(), 0);
        for (var rider : riders) {
            var other = rider.body(); var p = rider.at();
            if (shape.overlaps(at.x(), at.y(), at.z(), BodyFootprint.of(other.asset(), other.width(), other.height(), 0),
                    p.x(), p.y(), p.z(), 0)) return false;
        }
        return true;
    }
    private static Vec scaled(Vec at, double scale) { return new Vec(at.x() * scale, at.y() * scale, at.z() * scale); }
    private static double distance(Vec a, Vec b) { return Math.hypot(a.x() - b.x(), a.z() - b.z()); }
}
