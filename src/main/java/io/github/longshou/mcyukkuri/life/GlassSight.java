package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.motor.action.Walk;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.common.Tags;

/** A glass wall one block thick lets bodies see each other across it, including along seams between tiles. */
public final class GlassSight {
    /** The first glass tile hit by a ray through one layer, or null when clear or occluded. */
    public static BlockHitResult glassBetween(Entity observer, Vec3 eye, Entity target) {
        var level = observer.level(); var end = target.getEyePosition();
        var hit = level.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, observer));
        if (!glass(level, hit)) return null;
        return beyond(observer, eye, end, ClipContext.Block.COLLIDER, hit, true).getType() == HitResult.Type.MISS ? hit : null;
    }

    public static BlockHitResult outlineThroughGlass(Entity observer, Vec3 eye, Vec3 end) {
        var hit = observer.level().clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, observer));
        return glass(observer.level(), hit) ? beyond(observer, eye, end, ClipContext.Block.OUTLINE, hit, false) : hit;
    }

    private static boolean glass(BlockGetter level, BlockHitResult hit) {
        if (hit.getType() != HitResult.Type.BLOCK) return false;
        return glass(level.getBlockState(hit.getBlockPos()));
    }

    private static boolean glass(BlockState state) { return state.is(Tags.Blocks.GLASS_BLOCKS) || state.is(Tags.Blocks.GLASS_PANES); }

    private static BlockHitResult beyond(Entity observer, Vec3 eye, Vec3 end, ClipContext.Block shape, BlockHitResult hit, boolean layer) {
        var across = hit.getDirection().getAxis();
        int plane = hit.getBlockPos().get(across);
        return observer.level().clip(new ClipContext(eye, end, shape, ClipContext.Fluid.NONE, observer) {
            @Override public VoxelShape getBlockShape(BlockState block, BlockGetter world, BlockPos pos) {
                return pos.equals(hit.getBlockPos()) || layer && pos.get(across) == plane && glass(block)
                        ? Shapes.empty() : super.getBlockShape(block, world, pos);
            }
        });
    }

    /** In plain sight, or behind one glass wall. */
    public static boolean sees(LivingEntity observer, Entity target) {
        return observer.hasLineOfSight(target) || glassBetween(observer, observer.getEyePosition(), target) != null;
    }

    /** The nearest supported floor point on the body's side of {@code glass}, reachable by the body, from which {@code other}
     *  is still seen through that same glass cell; null when there is none. */
    public static Vec3 stand(YukkuriEntity body, Entity other, BlockPos glass) {
        var centre = Vec3.atBottomCenterOf(glass);
        var bounds = body.level().getBlockState(glass).getCollisionShape(body.level(), glass).bounds();
        Vec3 closest = null;
        double best = Double.POSITIVE_INFINITY;
        for (var normal : Direction.Plane.HORIZONTAL) {
            var out = Vec3.atLowerCornerOf(normal.getNormal());
            if (other.position().subtract(centre).dot(out) >= 0) continue;
            double width = normal.getAxis() == Direction.Axis.X ? bounds.getXsize() : bounds.getZsize();
            var stand = SurfaceApproach.restingFloor(body, centre.add(out.scale((width + body.getBbWidth()) / 2 + Walk.STAND_ARRIVED)));
            if (stand == null || body.distanceToSqr(stand) >= best) continue;
            var hit = glassBetween(body, stand.add(0, body.getEyeY() - body.getY(), 0), other);
            if (hit != null && glass.equals(hit.getBlockPos()) && SurfaceApproach.canApproach(body, stand)) {
                closest = stand; best = body.distanceToSqr(stand);
            }
        }
        return closest;
    }

    private GlassSight() {}
}
