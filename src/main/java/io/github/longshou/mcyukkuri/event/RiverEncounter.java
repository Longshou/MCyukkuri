package io.github.longshou.mcyukkuri.event;

import io.github.longshou.mcyukkuri.mind.EmotionEvent;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.event.WildEventData.Kind;
import io.github.longshou.mcyukkuri.event.WildEventData.Phase;
import io.github.longshou.mcyukkuri.event.WildEventData.Scene;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.social.BondLevels;
import io.github.longshou.mcyukkuri.social.Bonds;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.phys.Vec3;

/** The actual hat boat owns buoyancy and danger. This module only directs a bound companion on land. */
public final class RiverEncounter {
    public static boolean discover(YukkuriEntity shore) {
        if(!(shore.level() instanceof ServerLevel level)||!WildEvents.available(shore))return false;
        for(var boat:level.getEntitiesOfClass(HatBoatEntity.class,shore.getBoundingBox().inflate(12),HatBoatEntity::isAfloat))
            if(begin(shore,boat)!=null)return true;
        return false;
    }
    public static Scene begin(YukkuriEntity shore,HatBoatEntity boat) {
        if(!(shore.level() instanceof ServerLevel level)||!WildEvents.available(shore)||boat.level()!=level
                ||!boat.isAfloat()||boat.ownerYukkuri()==null||shore.distanceToSqr(boat)>144||!shore.hasLineOfSight(boat)
                ||!(level.getEntity(boat.ownerYukkuri()) instanceof YukkuriEntity rider)||rider.getVehicle()!=boat||!rider.isAlive())return null;
        boolean kin=Bonds.careKin(shore, rider)&&io.github.longshou.mcyukkuri.headwear.Recognition.recognizes(shore,rider);
        boolean group=Bonds.sameGroupId(shore, rider);
        if(!kin&&!group||YukkuriWorldData.get(level).affinity(shore.getUUID(),rider.getUUID())<BondLevels.AFFINITY_NEUTRAL)return null;
        if(shorePoint(shore,boat)==null)return null;
        var data=WildEventData.get(level);
        Scene scene=data.start(Kind.RIVER,level.getBiome(boat.blockPosition()).is(BiomeTags.IS_RIVER)?"river-call":"lake-steady",
                GlobalPos.of(level.dimension(),shore.blockPosition()),List.of(shore.getUUID(),rider.getUUID()),null,level.getGameTime());
        if(scene!=null){scene.target=boat.getUUID();shore.social().stop();shore.setPersistenceRequired();rider.setPersistenceRequired();}
        return scene;
    }
    public static void advance(ServerLevel level,Scene s) {
        var data=WildEventData.get(level);var rawShore=level.getEntity(s.members.getFirst());var rawRider=level.getEntity(s.members.get(1));
        if(!(rawShore instanceof YukkuriEntity shore)||!(rawRider instanceof YukkuriEntity rider)) {
            boolean gone=s.members.stream().anyMatch(id -> YukkuriWorldData.get(level).isCaptured(id)
                    ||FamilyData.get(level).person(id)!=null&&(FamilyData.get(level).person(id).status==FamilyData.Status.DEAD
                    ||FamilyData.get(level).person(id).status==FamilyData.Status.REMOVED||FamilyData.get(level).person(id).status==FamilyData.Status.LOST)
                    ||java.util.stream.StreamSupport.stream(level.getServer().getAllLevels().spliterator(),false).anyMatch(other -> other!=level&&other.getEntity(id)!=null));
            if(gone)WildEvents.finish(level,s,Phase.INTERRUPTED,"bound_member_captured_or_dead");
            return; // Unknown unloaded identities are not a rescue or a death.
        }
        if(!level.isPositionEntityTicking(shore.blockPosition())||!level.isPositionEntityTicking(rider.blockPosition()))return;
        if(!WildEvents.safe(shore)){WildEvents.finish(level,s,Phase.INTERRUPTED,"shore_companion_unavailable");return;}
        if(s.phase==Phase.RESPONDING) {
            if(!WildEvents.safe(rider)){WildEvents.finish(level,s,Phase.INTERRUPTED,"rescued_rider_unavailable");return;}
            s.phaseTicks++;data.setDirty();
            if(s.phaseTicks==1){shore.showReaction("happy","known_peer",70);rider.showReaction("happy","wait_family",70);}
            if(s.phaseTicks>=100)WildEvents.finish(level,s,Phase.COMPLETE,"actual_boat_rescue_observed");
            return;
        }
        var rawBoat=level.getEntity(s.target);
        if(!(rawBoat instanceof HatBoatEntity boat))return;
        if(!rider.getUUID().equals(boat.ownerYukkuri())){WildEvents.finish(level,s,Phase.INTERRUPTED,"boat_identity_changed");return;}
        s.ticks++;s.phaseTicks++;data.setDirty();
        if(boat.state()==HatBoatEntity.State.RESCUED) {data.phase(s,Phase.RESPONDING);return;}
        if(boat.state()==HatBoatEntity.State.CAPSIZED || !rider.isAlive()) {
            shore.showReaction("cry","search_family",100);WildEvents.finish(level,s,Phase.INTERRUPTED,"real_boat_capsized");return;
        }
        if(rider.getVehicle()!=boat){WildEvents.finish(level,s,Phase.INTERRUPTED,"rider_left_boat");return;}
        if(shore.distanceToSqr(boat)>24*24||!shore.hasLineOfSight(boat)){WildEvents.finish(level,s,Phase.INTERRUPTED,"boat_out_of_local_reach");return;}
        if(s.phaseTicks%80==1) {
            shore.showReaction("teary","wait_family",65);rider.mind().felt(EmotionEvent.FRIGHTENED); rider.showReaction("surprise","afraid",65);s.responses++;
        }
        if(s.ticks>=1200)WildEvents.finish(level,s,Phase.INTERRUPTED,"local_observation_ended");
    }
    public static void move(YukkuriEntity shore,Scene s) {
        if(!shore.getUUID().equals(s.members.getFirst()))return;
        var level=(ServerLevel)shore.level();
        var target=s.phase==Phase.RESPONDING?level.getEntity(s.members.get(1)):level.getEntity(s.target);
        if(target==null)return;shore.getLookControl().setLookAt(target,30,30);
        if(s.phaseTicks%20!=0)return;
        BlockPos stand=target instanceof HatBoatEntity boat?shorePoint(shore,boat):target.blockPosition();
        if(stand!=null&&shore.distanceToSqr(Vec3.atBottomCenterOf(stand))<=1){shore.getNavigation().stop();return;}
        if(stand==null||!shore.life().navigate(stand,.9,false))WildEvents.finish(level,s,Phase.INTERRUPTED,"no_reachable_shore");
    }
    private static BlockPos shorePoint(YukkuriEntity shore,HatBoatEntity boat) {
        var choices=new ArrayList<BlockPos>();
        for(BlockPos p:BlockPos.betweenClosed(boat.blockPosition().offset(-5,-1,-5),boat.blockPosition().offset(5,2,5)))
            if(shore.level().hasChunkAt(p)&&shore.life().dryStand(p)&&p.distSqr(shore.blockPosition())<=12*12)choices.add(p.immutable());
        choices.sort(Comparator.comparingDouble(p -> boat.distanceToSqr(Vec3.atBottomCenterOf(p))+.05*p.distSqr(shore.blockPosition())));
        return choices.stream().limit(12).filter(p -> shore.life().reachable(p,false)).findFirst().orElse(null);
    }
    private RiverEncounter() {}
}
