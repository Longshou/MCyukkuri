package io.github.longshou.mcyukkuri.subspecies;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.registry.ModItems;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * A player's recovery of an unattended aquatic hat.  The player does not own a second way to empty the hull: this
 * controller docks the same hull and invokes the same physical landing or cradle hand-off used by the family landing.
 * Token and cargo settlement happen only once every actual rider has left the vessel.
 */
final class HatBoatConfiscation {
    private enum Stage { DOCK, CREW }
    private static final int MAX_TICKS=2600, DOCK_TICKS=320, ACTOR_TICKS=420;

    private final HatBoatEntity boat;
    private final ArrayDeque<UUID> queue=new ArrayDeque<>();
    private final HashMap<UUID,Vec3> spots=new HashMap<>();
    private final ArrayList<AABB> used=new ArrayList<>();
    private boolean active;
    private Stage stage=Stage.DOCK;
    private HatDock.Plan plan;
    private HatBoatEntity.State originalState;
    private UUID playerId,current,currentCaregiver;
    private long started,stageSince,actorSince,airSince;
    private int initialRiders,blocked,refusals,respots;
    private String lastReason="idle";

    HatBoatConfiscation(HatBoatEntity boat){this.boat=boat;}
    boolean active(){return active;}
    String detail(){
        return "active="+active+" stage="+(active?stage:"IDLE")+" current="+current+" caregiver="+currentCaregiver
                +" remaining="+(queue.size()+(current==null?0:1))+" reason="+lastReason;
    }

    boolean begin(Player player){
        if(!(boat.level() instanceof ServerLevel level))return false;
        String reason=startReason(level,player);
        if(reason!=null)return refuse(player,reason);
        var riders=riders(level);
        if(riders==null)return refuse(player,"non_yukkuri_passenger");
        for(var rider:riders)rider.life().wakeInHat();
        if(riders.isEmpty())return finish(level,player);
        if(boat.state()==HatBoatEntity.State.CRADLE)return refuse(player,"ground_hat_crew_requires_owner");
        var guide=landingGuide(level,riders);
        if(guide==null)return refuse(player,"no_landing_guide");
        var found=HatDock.plan(boat,guide,riders.size(),player.blockPosition());
        if(found==null)return refuse(player,"no_dock");
        for(var rider:riders)if(!rider.family().canStepOut()&&!hasCaregiverAt(level,rider,found.center()))
            return refuse(player,"dependent_caregiver_unavailable");

        originalState=boat.state();plan=found;playerId=player.getUUID();current=currentCaregiver=null;initialRiders=riders.size();
        queue.clear();for(var rider:riders)queue.add(rider.getUUID());spots.clear();used.clear();used.add(player.getBoundingBox());
        started=stageSince=actorSince=level.getGameTime();airSince=0;blocked=refusals=respots=0;stage=Stage.DOCK;lastReason="started";
        active=true;
        // A moored hat is held by its ordinary braid controller.  It becomes afloat only while it is eased to this new,
        // reachable bank, and returns to MOORED before any ground caregiver takes a child.
        if(boat.isMoored()){boat.state(HatBoatEntity.State.AFLOAT);boat.setDeltaMovement(Vec3.ZERO);}
        boat.stopPaddling();
        GameplayLog.event(boat,"aquatic","unattended recovery started player={} owner={} token={} riders={} bank={} priorState={}",
                playerId,boat.ownerYukkuri(),boat.launchToken(),queue.size(),plan.bank(),originalState);
        return true;
    }

    void tick(){
        if(!active||!(boat.level() instanceof ServerLevel level))return;
        if(boat.state()==HatBoatEntity.State.CAPSIZED||boat.state()==HatBoatEntity.State.RESCUED||boat.state()==HatBoatEntity.State.REMOVED){
            abort("hull_state_changed",false);return;
        }
        var player=player(level);
        if(player==null||!dry(player)){abort("player_unavailable",true);return;}
        if(ownerControls(level)){abort("owner_control_active",true);return;}
        long now=level.getGameTime();
        if(now-started>MAX_TICKS){abort("timeout",true);return;}
        if(stage==Stage.DOCK)dock(now);else crew(level,now,player);
    }

    void hullInterrupted(String reason){abort(reason,false);}

    private void dock(long now){
        if(!boat.isAfloat()&&boat.state()!=HatBoatEntity.State.BEACHED){abort("dock_hull_unavailable",true);return;}
        var result=boat.driveToward(plan.center(),plan.yaw(),.07,5f);
        double gap=HatDock.rimGap(boat,plan);
        boolean turned=Math.abs(Mth.wrapDegrees(plan.yaw()-boat.getYRot()))<=12;
        if(result==HatBoatEntity.Drive.ARRIVED||gap<=.4&&turned){
            boat.state(HatBoatEntity.State.MOORED);boat.moorAt(boat.position());boat.setDeltaMovement(Vec3.ZERO);boat.stopPaddling();
            stage=Stage.CREW;stageSince=actorSince=now;blocked=0;lastReason="docked";
            GameplayLog.event(boat,"aquatic","unattended recovery docked player={} bank={} rimGap={} riders={}",playerId,plan.bank(),gap,queue.size());
            return;
        }
        blocked=result==HatBoatEntity.Drive.BLOCKED?blocked+1:0;
        if(blocked>=15||now-stageSince>DOCK_TICKS){
            GameplayLog.limited(boat,"aquatic","recovery.dock_blocked",200,
                    "unattended recovery cannot dock player={} bank={} gap={} blocked={} elapsed={}",
                    playerId,plan.bank(),gap,blocked,now-stageSince);
            abort("dock_blocked",true);
        }
    }

    private void crew(ServerLevel level,long now,Player player){
        boat.holdPosition();
        if(current==null){
            current=queue.poll();currentCaregiver=null;actorSince=now;airSince=0;refusals=respots=0;
            if(current==null){
                if(boat.getPassengers().isEmpty())finish(level,player);
                else abort("untracked_passenger_remaining",true);
                return;
            }
        }
        var rider=level.getEntity(current) instanceof YukkuriEntity y&&y.isAlive()?y:null;
        if(rider==null){abort("rider_missing",true);return;}
        if(rider.getVehicle()!=boat){
            if(rider.family().flying()||rider.family().heldByBraid()){airSince=0;return;}
            if(rider.isInWaterOrBubble()){abort("rider_entered_water",true);return;}
            if(rider.onGround()){
                used.add(rider.getBoundingBox());rider.getNavigation().stop();
                GameplayLog.event(rider,"aquatic","confiscation crew landed sourceHat={} player={} method={} at={}",
                        boat.getUUID(),playerId,currentCaregiver==null?"boarding":"cradle",rider.position());
                current=currentCaregiver=null;lastReason="rider_landed";return;
            }
            if(airSince==0)airSince=now;else if(now-airSince>40)abort("rider_left_hull_without_ground",true);
            return;
        }
        airSince=0;
        if(now-actorSince>ACTOR_TICKS){abort("rider_timeout",true);return;}
        if(rider.family().flying()||rider.family().heldByBraid())return;
        var spot=spot(level,rider);
        if(spot==null){abort("no_free_ground",true);return;}
        if(rider.family().canStepOut()){
            if(rider.family().startHatLanding(boat,spot)){lastReason="boarding_started";return;}
            if(++refusals>=24)respot(rider);
            return;
        }
        if(startCradle(level,rider,spot)){lastReason="cradle_started";return;}
    }

    private Vec3 spot(ServerLevel level,YukkuriEntity rider){
        var existing=spots.get(rider.getUUID());if(existing!=null)return existing;
        var occupied=new ArrayList<AABB>(used);
        for(var other:level.getEntitiesOfClass(YukkuriEntity.class,boat.getBoundingBox().inflate(3),candidate->candidate!=rider&&!candidate.isPassenger()))
            occupied.add(other.getBoundingBox());
        var found=HatDock.spotFor(plan,rider,rider.position(),occupied,respots);
        if(found!=null)spots.put(rider.getUUID(),found);
        return found;
    }

    private boolean startCradle(ServerLevel level,YukkuriEntity rider,Vec3 spot){
        var caregivers=caregivers(level,rider,boat.position());
        if(caregivers.isEmpty()){abort("dependent_caregiver_unavailable",true);return false;}
        boolean ready=false;
        for(var caregiver:caregivers){
            var pickup=caregiver.family().cradlePickup();
            if(!pickup.ready())continue;
            ready=true;
            if(pickup.start(boat,rider,spot)){
                currentCaregiver=caregiver.getUUID();
                GameplayLog.event(boat,"aquatic","confiscation dependent handoff started child={} caregiver={} sourceHat={} landing={}",
                        rider.getUUID(),currentCaregiver,boat.getUUID(),spot);
                return true;
            }
        }
        // CradlePickup deliberately cools a refused plan for forty ticks.  Keep the actual hull and child in place during
        // that interval, then try other bank spots before declaring the hand-off impossible.
        if(ready&&++refusals>=2)respot(rider);
        return false;
    }

    private void respot(YukkuriEntity rider){
        spots.remove(rider.getUUID());refusals=0;
        if(++respots>6)abort("no_reachable_ground_spot",true);
    }

    private boolean finish(ServerLevel level,Player player){
        if(!boat.getPassengers().isEmpty())return refuse(player,"passengers_remaining");
        if(!dry(player))return refuse(player,"player_not_on_dry_support");
        int slot=emptyInventorySlot(player);
        if(slot<0)return refuse(player,"player_inventory_full");
        UUID token=boat.launchToken();
        if(token==null)return refuse(player,"no_accessory_token");
        var owner=loadedOwner(level);
        if(owner!=null&&!owner.life().matchesAccessory(boat.ownerYukkuri(),token))return refuse(player,"custody_transfer_rejected");
        if(owner!=null&&!owner.life().relinquishCustody(boat.getUUID(),boat.ownerYukkuri(),token,player.getUUID()))
            return refuse(player,"custody_transfer_rejected");
        var hat=new ItemStack(ModItems.HAT.get());
        CustomData.update(DataComponents.CUSTOM_DATA,hat,tag->{
            tag.putUUID("Owner",boat.ownerYukkuri());tag.putUUID("Token",token);tag.putString("Variant","marisa");
            tag.putString("SourceModel","marisa");tag.putFloat("SourceScale",boat.hullScale());
        });
        // The empty main-inventory slot was checked on this same server tick, so this cannot merge, drop, or lose the only physical hat.
        player.getInventory().setItem(slot,hat);
        if(owner==null)YukkuriWorldData.get(level).accessoryConfiscated(boat.ownerYukkuri(),token,player.getUUID());
        int food=boat.cargo().count();
        boat.clearCrew();boat.cargo().spillAt(boat.position());boat.launchToken(null);boat.state(HatBoatEntity.State.RESCUED);boat.stopPaddling();boat.clearTowTicks();
        int riders=initialRiders;
        active=false;lastReason="completed";clearTransient();
        GameplayLog.event(boat,"aquatic","unattended hat recovered player={} owner={} token={} riders={} foodRemoved={} inventorySlot={}",
                player.getUUID(),boat.ownerYukkuri(),token,riders,food,slot);
        return true;
    }

    private String startReason(ServerLevel level,Player player){
        if(active)return "already_in_progress";
        if(boat.launchToken()==null)return "no_accessory_token";
        if(boat.ownerYukkuri()==null)return "no_owner";
        if(boat.capsizing())return "capsizing";
        if(boat.state()==HatBoatEntity.State.RESCUED)return "already_rescued";
        if(boat.state()==HatBoatEntity.State.REMOVED)return "removed";
        if(boat.hasIncoming())return "incoming_passenger";
        if(boat.landing())return "shore_landing_active";
        if(boat.hasCarryTrip()||boat.hasServiceSeats()||boat.hasReturningSeats()||boat.ownerUsesToiletSeat()||boat.cargo().handling())return "hull_operation_active";
        if(boat.unsteadyMotion()||boat.towed()||boat.flooding()>0)return "hull_unsteady";
        if(!dry(player))return "player_not_on_dry_support";
        for(var passenger:boat.getPassengers()){
            if(!(passenger instanceof YukkuriEntity))return "non_yukkuri_passenger";
            if(passenger.getUUID().equals(boat.ownerYukkuri()))return "owner_aboard";
        }
        return ownerControls(level)?"owner_control_active":null;
    }

    private boolean ownerControls(ServerLevel level){
        var owner=loadedOwner(level);
        return owner!=null&&owner.level()==boat.level()&&owner.distanceToSqr(boat)<=64&&!ownCradleHandoff(owner)
                &&(owner.getVehicle()==boat||owner.family().active()||owner.family().flying()
                ||owner.family().braidRescueActive()||owner.family().hatOnPlaying());
    }

    private boolean ownCradleHandoff(YukkuriEntity owner){
        var pickup=owner.family().cradlePickup();
        return currentCaregiver!=null&&currentCaregiver.equals(owner.getUUID())&&current!=null&&current.equals(pickup.childId())&&pickup.active();
    }

    private List<YukkuriEntity> riders(ServerLevel level){
        var riders=new ArrayList<YukkuriEntity>();
        for(var passenger:boat.getPassengers()){
            if(!(passenger instanceof YukkuriEntity rider))return null;
            if(!rider.getUUID().equals(boat.ownerYukkuri()))riders.add(rider);
        }
        return riders;
    }

    private YukkuriEntity landingGuide(ServerLevel level,List<YukkuriEntity> riders){
        var owner=loadedOwner(level);if(owner!=null&&owner.level()==boat.level()&&owner.isAlive())return owner;
        for(var rider:riders)for(var caregiver:caregivers(level,rider,boat.position()))return caregiver;
        return riders.isEmpty()?null:riders.getFirst();
    }

    private boolean hasCaregiverAt(ServerLevel level,YukkuriEntity rider,Vec3 position){return !caregivers(level,rider,position).isEmpty();}

    private List<YukkuriEntity> caregivers(ServerLevel level,YukkuriEntity rider,Vec3 position){
        var result=new ArrayList<YukkuriEntity>();
        UUID[] parents={rider.family().mother(),rider.family().father()};
        for(var parentId:parents){
            if(parentId==null||!(level.getEntity(parentId) instanceof YukkuriEntity parent)||!parent.isAlive()||parent.getVariant()!=YukkuriVariant.MARISA
                    ||parent.isPassenger()||parent.isNoAi()||parent.isCommandPlaying()||parent.operations().active()||parent.isOnFire()||parent.isPanicking()
                    ||parent.distanceToSqr(position)>16||result.contains(parent))continue;
            result.add(parent);
        }
        return result;
    }

    private Player player(ServerLevel level){
        var player=level.getServer().getPlayerList().getPlayer(playerId);
        return player!=null&&player.level()==boat.level()&&player.isAlive()&&player.distanceToSqr(boat)<=36?player:null;
    }

    private YukkuriEntity loadedOwner(ServerLevel level){
        if(boat.ownerYukkuri()==null)return null;
        for(var dimension:level.getServer().getAllLevels())if(dimension.getEntity(boat.ownerYukkuri()) instanceof YukkuriEntity owner)return owner;
        return null;
    }

    private static boolean dry(Player player){
        var level=player.level();var at=player.blockPosition();
        return !player.isInWaterOrBubble()&&level.getFluidState(at).isEmpty()&&level.getBlockState(at.below()).isFaceSturdy(level,at.below(),Direction.UP);
    }

    private static int emptyInventorySlot(Player player){
        return player.getInventory().getFreeSlot();
    }

    private boolean refuse(Player player,String reason){
        lastReason=reason;
        return boat.recoveryRefused(player,reason);
    }

    private void abort(String reason,boolean restore){
        if(!active)return;
        var caregiver=boat.level() instanceof ServerLevel level&&currentCaregiver!=null&&level.getEntity(currentCaregiver) instanceof YukkuriEntity y?y:null;
        if(caregiver!=null&&caregiver.family().cradlePickup().active())caregiver.family().cradlePickup().cancel();
        if(boat.level() instanceof ServerLevel level&&current!=null&&level.getEntity(current) instanceof YukkuriEntity rider&&rider.family().flying())
            rider.family().cancelBraidRescue();
        lastReason=reason;
        if(restore)restoreOriginalState();
        GameplayLog.limited(boat,"aquatic","recovery.abort."+reason,200,
                "unattended recovery aborted player={} owner={} child={} stage={} reason={} elapsed={} restored={}",
                playerId,boat.ownerYukkuri(),current,stage,reason,boat.level().getGameTime()-started,restore);
        active=false;clearTransient();
    }

    private void restoreOriginalState(){
        if(originalState==null||boat.state()==HatBoatEntity.State.CAPSIZED||boat.state()==HatBoatEntity.State.RESCUED||boat.state()==HatBoatEntity.State.REMOVED)return;
        boat.state(originalState);
        // A stopped hand-off leaves the hull exactly where it has physically reached; pulling a MOORED hull back to an
        // old anchor would cut through the bank or a blocker just crossed during the interrupted hand-off.
        if(originalState==HatBoatEntity.State.MOORED)boat.moorAt(boat.position());
        boat.setDeltaMovement(Vec3.ZERO);boat.stopPaddling();
    }

    private void clearTransient(){
        queue.clear();spots.clear();used.clear();plan=null;current=currentCaregiver=playerId=null;originalState=null;
        initialRiders=blocked=refusals=respots=0;airSince=0;
    }

    CompoundTag save(){
        var tag=new CompoundTag();
        if(!active||originalState==null)return tag;
        tag.putString("OriginalState",originalState.name());
        return tag;
    }

    void load(CompoundTag tag){
        active=false;clearTransient();
        if(!tag.contains("OriginalState"))return;
        try{originalState=HatBoatEntity.State.valueOf(tag.getString("OriginalState"));}catch(IllegalArgumentException ignored){lastReason="reload_invalid_state";return;}
        restoreOriginalState();lastReason="interrupted_by_reload";originalState=null;
        GameplayLog.limited(boat,"aquatic","recovery.reload_interrupted",200,
                "unattended recovery interrupted by reload owner={} restoredState={}",boat.ownerYukkuri(),tag.getString("OriginalState"));
    }
}
