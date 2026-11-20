package io.github.longshou.mcyukkuri.life;

final class BedClaimDrive {
    private BedClaimDrive() {}

    static boolean prefersBed(float energy, int boldness, boolean socialReady, boolean wantsShelter, double distanceSquared) {
        if (!socialReady || wantsShelter) return true;
        // In fair daylight, ten points of fatigue add a block of interest. Boldness adds up to one more.
        double range = (100 - energy) / 10.0 + boldness / 100.0;
        return distanceSquared <= range * range;
    }

    static long nextSearchAfter(long now, boolean deferred) {
        return deferred ? now : now + 200; // Same search interval as maintaining an address.
    }
}
