package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.status.Reactions;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Last-step steering is permitted only on a continuous, clear, supported surface. */
public final class SurfaceApproach {
    private SurfaceApproach() {}

    /** Resolve the real sole height on a low nest or adjacent floor, retaining its collision shell. */
    public static Vec3 floor(YukkuriEntity actor, Vec3 near) {
        return floor(actor,near,false);
    }
    /** A planned rest target ignores temporary occupants; walking still checks real collisions. */
    public static Vec3 restingFloor(YukkuriEntity actor, Vec3 near) {
        return floor(actor,near,true);
    }
    /** The highest collision top under the body's square footprint within half a block of {@code near}, unchecked. */
    public static Vec3 surface(YukkuriEntity actor, Vec3 near) { return top(actor,near); }
    private static Vec3 top(YukkuriEntity actor, Vec3 near) {
        if(!actor.level().hasChunkAt(BlockPos.containing(near)))return null;
        double half=actor.getBbWidth()*.5-.005, top=Double.NEGATIVE_INFINITY;
        var probe=new AABB(near.x-half,near.y-.5,near.z-half,near.x+half,near.y+.5,near.z+half);
        for(var shape:actor.level().getBlockCollisions(actor,probe))for(var box:shape.toAabbs())
            if(box.maxX>probe.minX&&box.minX<probe.maxX&&box.maxZ>probe.minZ&&box.minZ<probe.maxZ
                    &&box.maxY>=probe.minY&&box.maxY<=probe.maxY)top=Math.max(top,box.maxY);
        return Double.isFinite(top)?new Vec3(near.x,top,near.z):null;
    }
    private static Vec3 floor(YukkuriEntity actor, Vec3 near, boolean planning) {
        var at=top(actor,near);
        return at!=null&&supported(actor,at,planning)?at:null;
    }

    public static boolean supported(YukkuriEntity actor, Vec3 feet) {
        return supported(actor,feet,false);
    }
    private static boolean supported(YukkuriEntity actor, Vec3 feet, boolean planning) {
        var level = actor.level();
        if (!planning && !Reactions.Weather.allowsStep(actor,BlockPos.containing(feet))) return false;
        if (!level.hasChunkAt(BlockPos.containing(feet)) || !level.getFluidState(BlockPos.containing(feet)).isEmpty()) return false;
        AABB body = actor.getBoundingBox().move(feet.subtract(actor.position())).deflate(.005);
        if (planning ? level.getBlockCollisions(actor,body).iterator().hasNext() : !level.noCollision(actor, body)) return false;
        double radius = actor.getBbWidth() * .42;
        for (int x : new int[]{-1, 1}) for (int z : new int[]{-1, 1}) {
            double px = feet.x + x * radius, pz = feet.z + z * radius;
            var foot = new AABB(px-.015, feet.y-.08, pz-.015, px+.015, feet.y+.005, pz+.015);
            if (!level.getBlockCollisions(actor, foot).iterator().hasNext()) return false;
        }
        return true;
    }

    public static boolean clearLastStep(YukkuriEntity actor, Vec3 destination) {
        Vec3 start = actor.position();
        if (!actor.onGround() || Math.abs(start.y-destination.y) > .25 || start.distanceToSqr(destination) > 2.25) return false;
        if (!supported(actor, destination)) return false;
        int samples = Math.max(1, (int)Math.ceil(start.distanceTo(destination)/Math.min(.05, actor.getBbWidth()*.25)));
        double previous=start.y;
        for (int i=0; i<=samples; i++) {
            var sample=start.lerp(destination,(double)i/samples);
            if(supportedStep(actor,sample)){previous=sample.y;continue;}
            // Partly over an edge (a nest rim or step it is stepping down from): the top under part of the sole is enough
            // for this sample; requiring all four feet here stranded wide bodies on a rim above the floor they walk to.
            var surface=top(actor,sample);
            if(surface==null||Math.abs(surface.y-previous)>.25||!supportedStep(actor,surface))return false;
            previous=surface.y;
        }
        return true;
    }

    /** A step may begin with the body partly over the edge it just climbed onto. */
    private static boolean supportedStep(YukkuriEntity actor, Vec3 feet) {
        var level = actor.level();
        if (!Reactions.Weather.allowsStep(actor,BlockPos.containing(feet))) return false;
        if (!level.hasChunkAt(BlockPos.containing(feet)) || !level.getFluidState(BlockPos.containing(feet)).isEmpty()) return false;
        AABB body = actor.getBoundingBox().move(feet.subtract(actor.position())).deflate(.005);
        if (!level.noCollision(actor, body)) return false;
        // Match the physical feet: vanilla can land while only part of the bounding box touches
        // the bank. Requiring all four inner corners here prevents walking further onto that bank.
        // Every sample still needs actual support, and the final standing place needs all corners.
        var sole = new AABB(body.minX, feet.y-.08, body.minZ, body.maxX, feet.y+.005, body.maxZ);
        return level.getBlockCollisions(actor, sole).iterator().hasNext();
    }

    public static boolean arrived(YukkuriEntity actor, Vec3 destination) {
        // Two actors may stop on opposite edges of this tolerance. Keep their combined error
        // inside the shared mouth envelope, rather than accepting two individually "near" bodies.
        return Math.abs(actor.getY()-destination.y)<.08 && actor.position().subtract(destination).horizontalDistanceSqr()<.0004;
    }

    /** Never substitute a straight line for a route around a step or obstruction. */
    public static boolean walk(YukkuriEntity actor, Vec3 destination, double speed) {
        if (TravelRange.keepBabyHome(actor) && !TravelRange.babyDestination(actor,BlockPos.containing(destination))) { halt(actor); return false; }
        if (!Reactions.Weather.allowsStep(actor, BlockPos.containing(destination))) { halt(actor); return false; }
        if (arrived(actor, destination)) { halt(actor); return true; }
        if (clearLastStep(actor, destination)) {
            actor.getNavigation().stop();
            double remaining = actor.position().distanceTo(destination);
            // Mob.setSpeed also sets its forward input; ground travel multiplies that input by
            // speed again. Linear modifiers made the last few centimetres quadratically slow.
            double movement = actor.getAttributeValue(Attributes.MOVEMENT_SPEED);
            double modifier = Math.sqrt(Math.min(.02, remaining*.20))/Math.max(.0001, movement);
            actor.getMoveControl().setWantedPosition(destination.x, destination.y, destination.z,
                    Math.min(speed, modifier));
        } else if (actor.getNavigation().isDone() || actor.tickCount%10==0) {
            actor.life().navigate(BlockPos.containing(destination), speed, false);
        }
        return false;
    }

    public static void halt(YukkuriEntity actor) {
        actor.getNavigation().stop();
        actor.getMoveControl().setWantedPosition(actor.getX(), actor.getY(), actor.getZ(), 0);
        actor.setSpeed(0);
        if (actor.onGround()) {
            Vec3 velocity = actor.getDeltaMovement();
            actor.setDeltaMovement(0, velocity.y, 0);
        }
    }

    public static void face(YukkuriEntity actor, float yaw) {
        float turn = Mth.approachDegrees(actor.getYRot(), yaw, 12);
        actor.setYRot(turn); actor.yBodyRot = turn; actor.setYHeadRot(turn);
    }

    public static float yaw(Vec3 from, Vec3 to) {
        Vec3 delta = to.subtract(from);
        return (float)(Math.toDegrees(Math.atan2(delta.z, delta.x))-90);
    }
}
