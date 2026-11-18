package io.github.longshou.mcyukkuri.family;

final class FamilyDutyMath {
    private FamilyDutyMath() {}

    static double patrolRadius(int boldness, int attachment) {
        return 2 + .03 * boldness * (1 - .005 * attachment);
    }

    static int stationDwell(int selfControl) { return 60 + (int) Math.round(1.4 * selfControl); }
    static int lookEvery(int intelligence) { return 120 - (int) Math.round(.6 * intelligence); }
    static int insideCheckEvery(int attachment) { return 3 - (int) Math.round(.02 * attachment); }
    static int hungerBreak(int selfControl) { return 50 - (int) Math.round(.2 * selfControl); }
    static double watchStart(int attachment) { return .5 - .003 * attachment; }

    static int searchWaypoints(int attachment, int selfControl) {
        return 2 + (int) Math.round(.04 * (.6 * attachment + .4 * selfControl));
    }

    static double searchSpread(int intelligence) { return 20 + .4 * intelligence; }
    static int beckonPatience(int attachment, int selfControl) { return 200 + 3 * attachment + 2 * selfControl; }

    static double[][] patrolOffsets(double radius, boolean hasMouth) {
        var angles = hasMouth ? new int[]{60, -60, 90, -90} : new int[]{45, 135, 225, 315};
        var offsets = new double[angles.length][2];
        for (int i = 0; i < angles.length; i++) {
            double angle = Math.toRadians(angles[i]);
            offsets[i] = new double[]{radius * Math.cos(angle), radius * Math.sin(angle)};
        }
        return offsets;
    }

    /** Headings and fan angles use the horizontal +X axis as zero. */
    static double[][] searchSweep(double seenX, double seenZ, double fallbackHeading, double radius, int count, double spreadDeg) {
        double distance = Math.hypot(seenX, seenZ);
        double sightScale = distance > radius ? radius / distance : 1;
        double heading = distance < 1 ? fallbackHeading : Math.toDegrees(Math.atan2(seenZ, seenX));
        var offsets = new double[count][];
        offsets[0] = new double[]{seenX * sightScale, seenZ * sightScale};
        for (int i = 1; i < count; i++) {
            int step = (i + 1) / 2;
            int side = i % 2 == 1 ? 1 : -1;
            double angle = Math.toRadians(heading + side * spreadDeg * step / (count / 2));
            double reach = Math.min(radius, distance + 3 * step);
            offsets[i] = new double[]{reach * Math.cos(angle), reach * Math.sin(angle)};
        }
        return offsets;
    }
}
