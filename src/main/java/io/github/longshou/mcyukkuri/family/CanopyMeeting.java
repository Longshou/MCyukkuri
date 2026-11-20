package io.github.longshou.mcyukkuri.family;

import java.util.List;
import java.util.stream.DoubleStream;

/** Coordinates along the entrance and across it, measured from the centre of the one-block pad. */
record CanopyMeeting(double parent, double child, double side) {
    static final int APPROACH_TICKS = 600;
    static double spacing(double parentWidth, double childWidth, double routeClearance) {
        // Leave the navigation sweep its clearance, including at the endpoint.
        return (parentWidth+childWidth)*.5 + routeClearance + .001;
    }
    static boolean withinReach(double distance, double spacing, double arrival, double parentDot, double childDot) {
        double facing=Math.cos(Math.toRadians(2));
        return distance<=spacing+arrival && parentDot>=facing && childDot>=facing;
    }
    static List<CanopyMeeting> candidates(double parentWidth, double parentSole, double childSole,
            double mouthSpacing, double arrival, double parentSide, double childSide) {
        double parent = .5 + parentWidth / 2 + .01;
        double child = Math.min(parent - mouthSpacing, .5 - childSole);
        double margin = .5 - Math.max(parentSole, childSole) - arrival;
        if (margin < 0 || child < -.5 + childSole) return List.of();
        return DoubleStream.of(parentSide, childSide, 0, -margin, margin)
                .map(side -> Math.clamp(side, -margin, margin)).distinct()
                .mapToObj(side -> new CanopyMeeting(parent, child, side)).toList();
    }
}
