package io.github.longshou.mcyukkuri.subspecies;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.world.phys.AABB;

/** One cargo-space policy for storage, seats, routes and temporary brim storage. No inventory mutations. */
final class HatInteriorLayout {
    private final HatBoatEntity boat;

    HatInteriorLayout(HatBoatEntity boat) { this.boat = boat; }

    List<HatVessel.ProvisionZone> zones(int inner, int aside) {
        var result = new ArrayList<>(HatCargoLayout.zones(boat.mode(), HatBoatCargo.tierFor(inner), false));
        result.addAll(HatCargoLayout.zones(boat.mode(), HatBoatCargo.tierFor(aside), true));
        return result;
    }

    List<double[]> taken(UUID excluded, int inner, int aside) {
        var result = boat.occupantsExcept(excluded);
        for (var zone : zones(inner, aside)) {
            var p = zone.position();
            result.add(new double[]{p.x, p.y, p.z, zone.radius(), zone.height(), 2});
        }
        return result;
    }

    /** Check actual bodies AND future destinations. A moving/held body also keeps its original reservation. */
    boolean cargoFits(int inner, int aside) {
        if (inner < 0 || aside < 0 || inner + aside > HatBoatCargo.CAPACITY) return false;
        if (HatBoatCargo.tierFor(inner) >= 3 && boat.hasInnerCargoReservations()) return false;
        var occupants = boat.cargoOccupants();
        for (var zone : zones(inner, aside)) for (var body : occupants) if (overlaps(zone, body)) return false;
        return true;
    }

    int storageRoom() {
        var cargo = boat.cargo();
        if (cargo.asideCount() > 0 || cargo.handling() || boat.cargoLayoutBusy() || cargo.discardCooling()) return 0;
        int room = 0;
        for (int count = cargo.count() + 1; count <= boat.cargoCapacity(); count++) {
            if (!cargoFits(count, 0)) break;
            room++;
        }
        return room;
    }

    static boolean overlaps(HatVessel.ProvisionZone zone, double[] body) {
        double half = body[3] / HatVessel.radius(1) * .5;
        double height = body.length > 4 ? body[4] : body[3] * 2;
        if (overlaps(zone, new AABB(body[0]-half, body[1], body[2]-half, body[0]+half, body[1]+height, body[2]+half))) return true;
        return body.length >= 10 && body[6] > 0 && overlaps(zone,
                new AABB(body[0]-body[6], body[1]+body[8], body[2]-body[7],
                        body[0]+body[6], body[1]+body[8]+body[9], body[2]+body[7]));
    }

    private static boolean overlaps(HatVessel.ProvisionZone zone, AABB box) {
        var p = zone.position();
        if (box.minY >= p.y + zone.height() - .025 || box.maxY <= p.y + .025) return false;
        double dx = Math.max(0, Math.max(box.minX-p.x, p.x-box.maxX));
        double dz = Math.max(0, Math.max(box.minZ-p.z, p.z-box.maxZ));
        return dx*dx + dz*dz < Math.pow(zone.radius() + .025, 2);
    }
}
