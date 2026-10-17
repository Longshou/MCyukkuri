package io.github.longshou.mcyukkuri.subspecies;

import io.github.longshou.mcyukkuri.family.LiveBirthGeometry;
import java.util.List;

/** Diagnostic: why a hat boat reports no room for a newborn (family/FamilyLife logs this while a water birth is held up). */
public final class HatRoomProbe {
    private HatRoomProbe() {}

    public static String explain(HatBoatEntity boat) {
        double newborn = HatVessel.radius(.8 * LiveBirthGeometry.BABY_SCALE) / boat.unit();
        var body = HatVessel.newbornSpace(boat.mode(), boat.hullScale(), boat.unit());
        var out = new StringBuilder("afloat=").append(boat.isAfloat()).append(" state=").append(boat.state()).append(" catches=").append(boat.catches())
                .append(" young=").append(boat.youngAboard()).append(" incoming=").append(boat.hasIncoming())
                .append(" r=").append(String.format("%.4f", newborn)).append(" body=").append(body).append(" room=").append(boat.hasRoomForYoung());
        for (String region : new String[]{"day", "night", "catch"}) {
            var free = HatVessel.place(boat.mode(), newborn, region, null, List.of(), null, Double.NaN, body);
            var loose = HatVessel.place(boat.mode(), newborn, region, null, List.of(), null, Double.NaN, null);
            out.append(" | ").append(region).append(" free=").append(free).append(" nobody=").append(loose);
        }
        try {
            var method = HatBoatEntity.class.getDeclaredMethod("layoutTaken", java.util.UUID.class);
            method.setAccessible(true);
            @SuppressWarnings("unchecked") var taken = (List<double[]>) method.invoke(boat, (Object) null);
            out.append(" || taken=");
            for (var t : taken) out.append(java.util.Arrays.toString(t));
            var region = boat.getClass().getDeclaredMethod("region");
            region.setAccessible(true);
            String name = (String) region.invoke(boat);
            out.append(" region=").append(name)
                    .append(" withTaken+body=").append(HatVessel.place(boat.mode(), newborn, name, null, taken, null, Double.NaN, body))
                    .append(" withTaken-nobody=").append(HatVessel.place(boat.mode(), newborn, name, null, taken, null, Double.NaN, null))
                    .append(" smaller=").append(HatVessel.place(boat.mode(), newborn * .5, name, null, taken, null, Double.NaN, null));
        } catch (ReflectiveOperationException error) { out.append(" reflect=").append(error); }
        return out.toString();
    }
}
