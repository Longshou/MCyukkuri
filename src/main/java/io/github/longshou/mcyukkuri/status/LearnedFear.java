package io.github.longshou.mcyukkuri.status;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.social.SocialDisposition;
import io.github.longshou.mcyukkuri.social.YukkuriPersonality.FearBand;
import java.util.EnumSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Witnesses write to the normal directed relation. Retreat is a short use of ordinary ground movement. */
public final class LearnedFear {
    private static final int WITNESS_RANGE = 8, WITNESS_INTERVAL = 200;
    private static final int FIRE_RANGE = 3;
    private static final int WARNING_TICKS = 20;
    private static final int RETREAT_BUDGET = 120;
    private static final float HUNGRY = 20;

    private LearnedFear() {}

    public static boolean eligible(YukkuriEntity body) {
        if(!Reactions.prey(body)||body.body().dying()||body.life().isSleeping()
                ||body.isInWaterOrBubble()||body.vehicleHat()!=null||body.isVehicle())return false;
        var identity = body.status().identity();
        return (identity.free() || identity.companion()) && !body.settlement().society().slave();
    }

    public static void witnessed(YukkuriEntity victim, LivingEntity holder, String incident) {
        if (victim.level().isClientSide || holder==victim) return;
        long now = victim.level().getGameTime();
        for (var witness : victim.level().getEntitiesOfClass(YukkuriEntity.class, victim.getBoundingBox().inflate(WITNESS_RANGE),
                body -> body!=victim && body!=holder && eligible(body) && !body.operations().active()
                        && body.distanceToSqr(victim)<=WITNESS_RANGE*WITNESS_RANGE
                        && body.hasLineOfSight(victim) && body.hasLineOfSight(holder))) {
            var personality = witness.life().personality();
            var before = personality.relation(holder.getUUID());
            // A recent directed contact already anchors this memory, including another sighting of the same holder.
            if (before.fear()>0 && now-before.lastInteraction()<WITNESS_INTERVAL) continue;
            int gain = 6+Math.round(9*(100-personality.boldness())/100f);
            if (!witness.family().adult()) gain = (gain+1)/2;
            personality.relationshipChanged(holder.getUUID(),0,gain);
            int after = personality.fear(holder.getUUID());
            if (after==before.fear()) continue;
            witness.showReaction("surprise","afraid",60);
            GameplayLog.event(witness,"threat","fear learned holder={} victim={} incident={} fear={} -> {}",
                    holder.getUUID(),victim.getUUID(),incident,before.fear(),after);
        }
    }

    public static boolean waryOf(YukkuriEntity body, Entity holder) {
        return body.life().personality().fearBand(holder.getUUID())!=FearBand.NORMAL;
    }

    public static boolean hungerOverrides(YukkuriEntity body) {
        return body.life().nutrition()<HUNGRY;
    }

    public static final class RetreatGoal extends Goal {
        private final YukkuriEntity body;
        private LivingEntity holder;
        private BlockPos fire;
        private Vec3 stand;
        private int ticks;
        private long nextScan;
        private BlockPos lastBody, lastDanger;

        public RetreatGoal(YukkuriEntity body) {
            this.body=body;
            setFlags(EnumSet.of(Flag.MOVE,Flag.LOOK));
        }

        private boolean available() {
            return eligible(body) && Reactions.free(body) && !body.isLeashed() && !body.isPanicking()
                    && !body.life().hotFloor().active() && !hungerOverrides(body);
        }

        @Override public boolean canUse() {
            long now=body.level().getGameTime();
            if (now<nextScan || !available()) return false;
            nextScan=now+WARNING_TICKS;
            double best=Double.POSITIVE_INFINITY;
            for (var source : body.level().getEntitiesOfClass(LivingEntity.class,body.getBoundingBox().inflate(3))) {
                if(source==body || !source.isAlive() || source instanceof Player player && (player.isSpectator()||player.isInvisible())
                        || !body.hasLineOfSight(source)) continue;
                double distance=body.distanceToSqr(source);
                double radius=SocialDisposition.learnedRetreatRadius(body.life().personality().relation(source.getUUID()));
                if(radius>0 && distance<=radius*radius && distance<best&&changed(source.blockPosition())){holder=source;best=distance;}
            }
            if(holder==null && body.body().remembersBurn())fire=nearbyFire();
            if(holder==null && fire==null)return false;
            return true;
        }

        private BlockPos nearbyFire() {
            var origin=body.blockPosition();
            BlockPos closest=null;
            double best=FIRE_RANGE*FIRE_RANGE;
            for(var pos : BlockPos.betweenClosed(origin.offset(-FIRE_RANGE,-1,-FIRE_RANGE),origin.offset(FIRE_RANGE,1,FIRE_RANGE))){
                double distance=body.distanceToSqr(Vec3.atCenterOf(pos));
                if(distance>best||!changed(pos)||!burning(pos))continue;
                var hit=body.level().clip(new ClipContext(body.getEyePosition(),Vec3.atCenterOf(pos),
                        ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,body));
                if(hit.getType()!=HitResult.Type.MISS&&!hit.getBlockPos().equals(pos))continue;
                best=distance;closest=pos.immutable();
            }
            return closest;
        }

        private boolean burning(BlockPos pos) {
            if(!body.level().hasChunkAt(pos))return false;
            var state=body.level().getBlockState(pos);
            return state.is(BlockTags.FIRE)||state.getFluidState().is(FluidTags.LAVA)||CampfireBlock.isLitCampfire(state);
        }

        private boolean changed(BlockPos danger) {
            return !body.blockPosition().equals(lastBody)||!danger.equals(lastDanger);
        }

        @Override public void start() {
            ticks=0;
            lastBody=body.blockPosition();
            lastDanger=holder==null?fire:holder.blockPosition();
            body.showReaction("surprise","afraid",60);
            GameplayLog.limited(body,"threat","learned_cue",200,"learned retreat cue holder={} fire={}",
                    holder==null?null:holder.getUUID(),fire);
        }

        @Override public boolean canContinueToUse() {
            return ticks<RETREAT_BUDGET && available()
                    && (holder!=null ? holder.isAlive()&&holder.level()==body.level()&&body.distanceToSqr(holder)<64&&waryOf(body,holder)
                                     : fire!=null&&body.body().remembersBurn()&&burning(fire));
        }

        @Override public boolean requiresUpdateEveryTick() { return true; }

        @Override public void tick() {
            var danger=holder==null?Vec3.atCenterOf(fire):holder.position();
            if(++ticks<=WARNING_TICKS){
                body.getLookControl().setLookAt(danger.x,danger.y,danger.z);
                if(ticks<WARNING_TICKS)return;
                if(body.distanceToSqr(danger)>9){ticks=RETREAT_BUDGET;return;}
                stand=Reactions.escapeStand(body,danger,holder,5,Set.of());
                if(stand==null){finish("no_route");return;}
            }
            var result=SurfaceApproach.walkResult(body,stand,holder,1.2);
            if(result==SurfaceApproach.Result.ARRIVED)finish("away");
            else if(result==SurfaceApproach.Result.BLOCKED)finish("blocked");
        }

        private void finish(String outcome) {
            var danger=holder==null?Vec3.atCenterOf(fire):holder.position();
            GameplayLog.limited(body,"threat","learned_"+outcome,200,"learned retreat end result={} holder={} fire={} stand={} distance={}",
                    outcome,holder==null?null:holder.getUUID(),fire,stand,body.position().distanceTo(danger));
            ticks=RETREAT_BUDGET;
        }

        @Override public void stop() {
            SurfaceApproach.halt(body);
            holder=null;fire=null;stand=null;
            nextScan=body.level().getGameTime()+WITNESS_INTERVAL;
        }
    }
}
