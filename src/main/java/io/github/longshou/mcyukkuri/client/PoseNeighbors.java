package io.github.longshou.mcyukkuri.client;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.pose.ContactBounds;
import io.github.longshou.mcyukkuri.pose.check.PoseGeometry;
import java.util.ArrayList;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import org.joml.Matrix4f;

/** Frame-local skinned bounds for the same neighborhood as the fitted partner contact check. */
final class PoseNeighbors {
    private static final ArrayList<YukkuriEntity> actors = new ArrayList<>();
    private static final ArrayList<ContactBounds> drawn = new ArrayList<>(0);
    private static final Matrix4f inverse = new Matrix4f();
    static final Matrix4f basis = new Matrix4f();
    static long frame;
    static boolean complete, near;

    static void stage(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_SKY) frame++;
    }

    static void join(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() && event.getEntity() instanceof YukkuriEntity actor) {
            actors.add(actor);
            drawn.ensureCapacity(actors.size());
        }
    }

    static void leave(EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide() && event.getEntity() instanceof YukkuriEntity actor)
            actors.removeIf(peer -> peer == actor);
    }

    static void unload(LevelEvent.Unload event) {
        var level = event.getLevel();
        if (level.isClientSide()) actors.removeIf(peer -> peer.level() == level);
    }

    static void collect(YukkuriEntity self, float partial, Matrix4f start, Matrix4f posed) {
        drawn.clear();
        complete = true;
        float x = (float)Mth.lerp(partial, self.xo, self.getX());
        float y = (float)Mth.lerp(partial, self.yo, self.getY());
        float z = (float)Mth.lerp(partial, self.zo, self.getZ());
        for (int i = 0; i < actors.size(); i++) {
            var peer = actors.get(i);
            if (peer == self || peer.level() != self.level()) continue;
            double dx = (double)(float)Mth.lerp(partial, peer.xo, peer.getX()) - x;
            double dy = (double)(float)Mth.lerp(partial, peer.yo, peer.getY()) - y;
            double dz = (double)(float)Mth.lerp(partial, peer.zo, peer.getZ()) - z;
            if (dx * dx + dy * dy + dz * dz <= PoseGeometry.PARTNER_BLOCKS * PoseGeometry.PARTNER_BLOCKS) {
                var state = PoseBridge.drawn(peer);
                if (state == null || state.boundsFrame != frame) complete = false;
                else drawn.add(state.bounds);
            }
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
