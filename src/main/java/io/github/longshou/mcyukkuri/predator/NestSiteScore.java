package io.github.longshou.mcyukkuri.predator;

final class NestSiteScore {
    private NestSiteScore() {}

    static double preferredHeight(double timid) { return 6 + 4 * timid; }

    static double minimumPathDistance(double timid) { return 8 + 16 * timid; }

    static double score(int height, double cover, double pathDistance, double distance, double timid) {
        double heightFit = 1 - Math.abs(height - preferredHeight(timid)) / 6;
        double pathFit = Math.clamp((pathDistance - minimumPathDistance(timid)) / 8, 0, 1);
        return .3 * heightFit + (.3 + .2 * timid) * cover + .4 * pathFit - .02 * distance;
    }

    /** A nearer site wins a score tie. Equal scores and distances keep the deterministic scan order. */
    static int compare(double score, double distance, double otherScore, double otherDistance) {
        int rank = Double.compare(score, otherScore);
        return rank != 0 ? rank : Double.compare(otherDistance, distance);
    }
}
