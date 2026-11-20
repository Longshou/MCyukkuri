package io.github.longshou.mcyukkuri.client;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.motor.geom.SeparationStep.Turn;
import io.github.longshou.mcyukkuri.entity.YukkuriView;
import io.github.longshou.mcyukkuri.pose.ContactBounds;
import io.github.longshou.mcyukkuri.pose.WallYield;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import org.joml.Matrix4f;

/** Frame-local skinned bounds for the same neighborhood as the fitted partner contact check. */
final class PoseNeighbors {
    private static final ArrayList<YukkuriEntity> actors = new ArrayList<>();
    private static final IdentityHashMap<YukkuriView, PartnerProjection> projections = new IdentityHashMap<>();
    private static final ArrayList<ContactBounds> drawn = new ArrayList<>(0);
    // Vanilla can supply two partial ticks in one pass when some entities are frozen.
    private static PoseNeighborGrid first = new PoseNeighborGrid(), second = new PoseNeighborGrid();
    private static final Matrix4f inverse = new Matrix4f();
    static final Matrix4f basis = new Matrix4f();
    static long frame;
    static boolean complete, near;

    static void stage(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_SKY) YukkuriModel.expirePaperFits(++frame);
    }

    static void join(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() && event.getEntity() instanceof YukkuriEntity actor) {
            actors.add(actor);
            projections.put(actor, new PartnerProjection());
            drawn.ensureCapacity(actors.size());
            first.reserve(actors.size());
            second.reserve(actors.size());
            invalidate();
        }
    }

    static void leave(EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide() && event.getEntity() instanceof YukkuriEntity actor) {
            actors.removeIf(peer -> peer == actor);
            projections.remove(actor);
            invalidate();
        }
    }

    static void unload(LevelEvent.Unload event) {
        var level = event.getLevel();
        if (level.isClientSide()) {
            actors.removeIf(peer -> peer.level() == level);
            projections.keySet().removeIf(peer -> peer.level() == level);
            drawn.clear();
            invalidate();
        }
    }

    private static void invalidate() {
        first.invalidate();
        second.invalidate();
    }

    static PartnerProjection projection(YukkuriView actor) { return projections.get(actor); }

    private static PoseNeighborGrid grid(float partial) {
        if (first.matches(frame, partial)) return first;
        // Keep the most recently used snapshot first, for the common case of one interpolation clock.
        var previous = first;
        first = second;
        second = previous;
        if (!first.matches(frame, partial)) {
            first.begin(frame, partial);
            for (int i = 0; i < actors.size(); i++) {
                var actor = actors.get(i);
                first.includeRadius(projection(actor).bounds(actor).radius());
                first.add(i, (float)Mth.lerp(partial, actor.xo, actor.getX()),
                        (float)Mth.lerp(partial, actor.yo, actor.getY()), (float)Mth.lerp(partial, actor.zo, actor.getZ()));
            }
        }
        return first;
    }

    static void crowdFaces(YukkuriEntity self, float partial, WallYield.Bounds own, WallClearance clearance, int knownPartner) {
        var at = self.getPosition(partial);
        var grid = grid(partial);
        int count = grid.findLoose((float)at.x, (float)at.y, (float)at.z, own.radius());
        var bed = self.occupiedBed();
        float distance = self.boardingDistance(bed);
        for (int i = 0; i < count; i++) {
            var peer = actors.get(grid.actor(i));
            if (peer == self || peer.level() != self.level() || peer.getId() == knownPartner
                    || !peer.usesCrowdSpacing() || self.bedTurn(bed, distance, peer) == Turn.PASS) continue;
            var delta = peer.getPosition(partial).subtract(at);
            var theirs = projection(peer).bounds(peer);
            if (own.overlaps(theirs, delta.x, delta.y, delta.z))
                clearance.passingFace(self, peer, partial, own, theirs, delta);
        }
    }

    static void collect(YukkuriEntity self, float partial, Matrix4f start, Matrix4f posed) {
        drawn.clear();
        complete = true;
        float x = (float)Mth.lerp(partial, self.xo, self.getX());
        float y = (float)Mth.lerp(partial, self.yo, self.getY());
        float z = (float)Mth.lerp(partial, self.zo, self.getZ());
        var grid = grid(partial);
        int count = grid.find(x, y, z);
        for (int i = 0; i < count; i++) {
            var peer = actors.get(grid.actor(i));
            if (peer == self || peer.level() != self.level()) continue;
            var state = PoseBridge.drawn(peer);
            if (state == null || state.boundsFrame != frame) complete = false;
            else drawn.add(state.bounds);
        }
        near = !complete || !drawn.isEmpty();
        // Match PoseTap's world-mu frame, including the precision of the captured coordinates.
        basis.translation(x * 16, y * 16, z * 16).scale(16)
                .mul(start.invert(inverse)).mul(posed).scale(1/16f);
    }

    static boolean separate(ContactBounds proposed) {
        for (int i = 0; i < drawn.size(); i++) if (!proposed.separate(drawn.get(i))) return false;
        return true;
    }
}
