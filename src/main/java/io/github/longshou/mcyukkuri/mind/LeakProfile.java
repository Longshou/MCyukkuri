package io.github.longshou.mcyukkuri.mind;

/** Stage and effective control set the strong/moderate bands and the length of the visible flow. */
enum LeakProfile {
    BABY(.50f, .80f, .20f, .40f, 40),
    JUVENILE(.25f, .45f, .05f, .15f, 30),
    ADULT(.05f, .15f, 0, 0, 20);

    private final float strongMin, strongMax, moderateMin, moderateMax;
    private final int minFlow;

    LeakProfile(float strongMin, float strongMax, float moderateMin, float moderateMax, int minFlow) {
        this.strongMin = strongMin;
        this.strongMax = strongMax;
        this.moderateMin = moderateMin;
        this.moderateMax = moderateMax;
        this.minFlow = minFlow;
    }

    float chance(boolean strong, int control, float urine) {
        float impulse = 1 - control / 100f;
        float band = strong ? strongMin + (strongMax - strongMin) * impulse
                : moderateMin + (moderateMax - moderateMin) * impulse;
        return band * Math.min(1, urine / 65);
    }

    int flow(int control) { return minFlow + Math.round(20 * (1 - control / 100f)); }

    // Half a pressure point per tick of the established flow; small loads can run out first.
    float portion(int control, float urine) { return Math.min(urine, flow(control) * .5f); }

    record Draw(float roll, float chance) {
        boolean wins() { return roll < chance; }
    }

    float width(int control, float bodyWidth, float released) {
        float full = Math.max(.12f, bodyWidth * (.5f + .2f * (1 - control / 100f)));
        return full * (float) Math.sqrt(released / (flow(control) * .5f));
    }

    record Beat(long start, long flowAt, long puddleAt, long endAt, float width, float amount, boolean joy) {
        boolean cooling(long now) { return now < start + 1200; }
        String face(long now) {
            if (now < flowAt) return "leak_startle";
            if (now < puddleAt) return "leak_flow";
            if (now < endAt) return joy ? "satisfied" : "side_teary";
            return "";
        }
    }
}
