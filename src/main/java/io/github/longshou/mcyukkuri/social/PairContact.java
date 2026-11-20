package io.github.longshou.mcyukkuri.social;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.motor.Motor;

/** A contact owner publishes each admitted frame to both bodies and releases them together. */
public final class PairContact {
    public enum Pose {
        NONE("", ""), LEFT("snuggle_left", Behaviors.NUZZLE), RIGHT("snuggle_right", Behaviors.NUZZLE),
        FAREWELL("nuzzle", Behaviors.INDEPENDENCE_NUZZLE), DATE("nuzzle", Behaviors.IDLE);

        private static final Pose[] VALUES = values();
        private final String clip, behavior;
        Pose(String clip, String behavior) { this.clip = clip; this.behavior = behavior; }
        public static Pose of(int id) { return VALUES[id]; }
        public String clip() { return clip; }
        public String behavior() { return behavior; }
        public Pose opposite() { return this == LEFT ? RIGHT : this == RIGHT ? LEFT : this; }
    }

    private final YukkuriEntity first, second;
    private final Pose pose;
    private final PairContactClock clock;

    public PairContact(YukkuriEntity first, YukkuriEntity second, Pose pose, int length) {
        this.first = first;
        this.second = second;
        this.pose = pose;
        clock = new PairContactClock(length);
    }

    public int ticks() { return clock.ticks(); }
    public boolean complete() { return clock.complete(); }
    public boolean finished() { return clock.finished(first.level().getGameTime()); }

    public void publish(float spacing) {
        long now = first.level().getGameTime();
        clock.publish(now,
                frame -> first.showPairContact(second, pose, now - frame, frame, spacing),
                frame -> second.showPairContact(first, pose.opposite(), now - frame, frame, spacing));
    }

    /** Clears both poses and returns the number of frames that survived their publication tick. */
    public int release() {
        int shown = clock.releasedTicks(first.level().getGameTime());
        first.clearPairContact();
        second.clearPairContact();
        Motor.separateAfterContact(first, second);
        return shown;
    }
}
