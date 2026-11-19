package io.github.longshou.mcyukkuri.social;

record CannibalDrive(double score, double threshold) {
    static CannibalDrive hunt(float nutrition, Temperament traits, int fear, double powerDifference, int scorn) {
        double score = 55 + (12 - nutrition) * 3 + traits.boldness() * .25
                - traits.friendliness() * .45 - traits.selfControl() * .2 - fear * .5 + powerDifference;
        return new CannibalDrive(score, Math.max(45, scorn));
    }

    boolean willing() { return score >= threshold; }
}
