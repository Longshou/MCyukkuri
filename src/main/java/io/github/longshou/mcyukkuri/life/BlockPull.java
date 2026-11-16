package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.body.MouthPlacement;
import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.entity.YukkuriComponents;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.motor.Action;
import io.github.longshou.mcyukkuri.motor.ClipMarkers;
import io.github.longshou.mcyukkuri.motor.Feasibility;
import io.github.longshou.mcyukkuri.motor.MotionPose;
import io.github.longshou.mcyukkuri.motor.Motor;
import io.github.longshou.mcyukkuri.motor.MouthOccupancy;
import io.github.longshou.mcyukkuri.motor.Outcome;
import io.github.longshou.mcyukkuri.motor.Progress;
import io.github.longshou.mcyukkuri.motor.Reason;
import io.github.longshou.mcyukkuri.motor.Running;
import io.github.longshou.mcyukkuri.motor.TravelMode;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Minimal reusable block-item pull for an exposed horizontal face within real mouth reach.
 * First caller: stray newspaper-stack sheet pulling ({@link io.github.longshou.mcyukkuri.settlement.FoundShelter}).
 */
public final class BlockPull {
    public static final String CLIP = YukkuriAction.PAPER_PULL.id();

    public record Stand(BlockPos block, Vec3 feet, Vec3 contact) {}

    public static Stand stand(YukkuriEntity actor, BlockPos pos) {
        var level = actor.level();
        if (!level.hasChunkAt(pos)) return null;
        BlockState state = level.getBlockState(pos);
        VoxelShape shape = state.getShape(level, pos);
        if (shape.isEmpty()) return null;
        AABB bounds = shape.bounds().move(pos);
        double topY = Math.clamp(bounds.maxY - 0.03, bounds.minY + 0.02, bounds.maxY);
        double mouthOffsetY = Math.max(0.05, MouthPlacement.position(actor).y - actor.getY());
        double mouthHoriz = MouthPlacement.position(actor).subtract(actor.position()).horizontalDistance();
        double clearance = Math.max(actor.getBbWidth() * 0.5 + 0.04, mouthHoriz);
        double shortest = Double.POSITIVE_INFINITY;
        Stand best = null;
        for (Direction side : Direction.Plane.HORIZONTAL) {
            Vec3 outward = Vec3.atLowerCornerOf(side.getNormal());
            double depth = side.getAxis() == Direction.Axis.X ? bounds.getXsize() : bounds.getZsize();
            Vec3 face = new Vec3(bounds.getCenter().x + outward.x * depth * 0.5, topY,
                    bounds.getCenter().z + outward.z * depth * 0.5);
            Vec3 probe = new Vec3(face.x + outward.x * clearance, pos.getY(), face.z + outward.z * clearance);
            Vec3 foot = SurfaceApproach.floor(actor, probe);
            if (foot == null) foot = SurfaceApproach.floor(actor, probe.add(0, -1, 0));
            if (foot == null) continue;
            if (topY < foot.y - 0.12 || topY > foot.y + mouthOffsetY + 0.95 * actor.getBbHeight()) continue;
            AABB box = actor.getBoundingBox().move(foot.subtract(actor.position()));
            if (!level.noCollision(actor, box)
                    || !level.getEntitiesOfClass(YukkuriEntity.class, box, y -> y != actor && y.isAlive()).isEmpty()) continue;
            Vec3 mouthAtStand = new Vec3(foot.x, foot.y + mouthOffsetY, foot.z);
            BlockHitResult hit = level.clip(new ClipContext(mouthAtStand, face, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, actor));
            if (hit.getType() != HitResult.Type.MISS && !pos.equals(hit.getBlockPos())) continue;
            if (!SurfaceApproach.canApproach(actor, foot)) continue;
            boolean peerNear = !level.getEntitiesOfClass(YukkuriEntity.class, box.inflate(0.35, 0.1, 0.35),
                    y -> y != actor && y.isAlive()).isEmpty();
            double route = (SurfaceApproach.at(actor, foot) ? 0.0 : SurfaceApproach.routeLength(actor, foot, null))
                    + 0.05 * actor.position().distanceTo(foot) + (peerNear ? 4.0 : 0.0);
            if (route >= shortest) continue;
            shortest = route;
            best = new Stand(pos.immutable(), foot, face);
        }
        return best;
    }

    public static boolean reaches(YukkuriEntity actor, Stand stand) {
        if (!actor.onGround() || actor.position().subtract(stand.feet()).horizontalDistanceSqr() > 0.16) return false;
        Vec3 mouth = MouthPlacement.position(actor);
        BlockHitResult hit = actor.level().clip(new ClipContext(mouth, stand.contact(),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, actor));
        return hit.getType() == HitResult.Type.MISS || stand.block().equals(hit.getBlockPos());
    }

    public static Action action(Stand stand, MouthOccupancy.Purpose purpose,
                                BiPredicate<ServerLevel, BlockPos> valid,
                                BiFunction<ServerLevel, BlockPos, ItemStack> take) {
        return new PullAction(stand, purpose, valid, take);
    }

    private static final class PullAction implements Action {
        private final Stand stand;
        private final MouthOccupancy.Purpose purpose;
        private final BiPredicate<ServerLevel, BlockPos> valid;
        private final BiFunction<ServerLevel, BlockPos, ItemStack> take;
        private final ClipMarkers.Clip marks = ClipMarkers.of(CLIP);
        private final int length = Math.max(1, marks.length());
        private final int tugTick = marks.ticks("tug", Math.max(1, length / 2));
        private long startTick;
        private int ticks;
        private boolean pulled;

        PullAction(Stand stand, MouthOccupancy.Purpose purpose,
                   BiPredicate<ServerLevel, BlockPos> valid,
                   BiFunction<ServerLevel, BlockPos, ItemStack> take) {
            this.stand = stand;
            this.purpose = purpose;
            this.valid = valid;
            this.take = take;
        }

        @Override public String id() { return CLIP; }

        @Override public Feasibility check(YukkuriEntity body) {
            if (!body.onGround() || body.isPassenger() || body.isInWaterOrBubble())
                return Feasibility.of(Reason.NO_SUPPORT, 0, "dry_ground_required");
            if (!(body.level() instanceof ServerLevel level) || !valid.test(level, stand.block()))
                return Feasibility.of(Reason.NO_CONTACT, 0, "source_unavailable");
            if (!mouthFree(body))
                return Feasibility.of(Reason.NO_CONTACT, 0, "mouth_occupied");
            return Feasibility.pass();
        }

        @Override public void start(YukkuriEntity body) {
            body.getNavigation().stop();
            startTick = body.level().getGameTime();
            ticks = 0;
            pulled = false;
        }

        @Override public Progress tick(YukkuriEntity body) {
            if (!body.onGround() || body.isPassenger() || body.isInWaterOrBubble())
                return Outcome.of(Reason.NO_SUPPORT, 0, "dry_ground_required");
            if (!(body.level() instanceof ServerLevel level))
                return Outcome.of(Reason.NO_CONTACT, 0, "client");
            Motor.face(body, SurfaceApproach.yaw(body.position(), stand.contact()));
            Motor.velocity(body, new Vec3(0, body.getDeltaMovement().y, 0), CLIP);
            if (!pulled) {
                if (!valid.test(level, stand.block()))
                    return Outcome.of(Reason.NO_CONTACT, 0, "source_depleted");
                if (!mouthFree(body))
                    return Outcome.of(Reason.NO_CONTACT, 0, "mouth_occupied");
                if (ticks >= tugTick) {
                    ItemStack stack = take.apply(level, stand.block());
                    if (stack.isEmpty()) return Outcome.of(Reason.NO_CONTACT, 0, "source_empty");
                    body.component(YukkuriComponents.MOUTH).set(purpose, stack);
                    pulled = true;
                    GameplayLog.event(body, "habitat", "gathered item={} count={} source={}",
                            stack.getItem(), stack.getCount(), stand.block());
                }
            }
            if (++ticks >= length) return new Outcome.Done("block=" + stand.block());
            return new Running(length - ticks, TravelMode.PASS);
        }

        @Override public MotionPose pose() { return new MotionPose(CLIP, startTick, false); }
        @Override public String startDetail() { return "block=" + stand.block() + " clipTicks=" + length; }

        private static boolean mouthFree(YukkuriEntity body) {
            return body.component(YukkuriComponents.MOUTH).freeForExternalWork()
                    && body.habitat().carrying().isEmpty()
                    && body.forageTrip().load().isEmpty()
                    && body.independence().carrying().isEmpty();
        }
    }

    private BlockPull() {}
}
