package io.github.longshou.mcyukkuri.subspecies;

import io.github.longshou.mcyukkuri.subspecies.boat.HullHelm.Purpose;
import io.github.longshou.mcyukkuri.status.GameplayLog;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyFeedingContact;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.EventHooks;
import net.minecraft.tags.BlockTags;

/** A parent obtains one finite twig, then physically hands it to a ready pupil aboard its hat. */
public final class HatFamilyPaddle {
    private static final int CONTACT_TICKS=36;
    private final YukkuriEntity owner;
    private UUID recipient;
    /** The parent's own dropped stick wins over unrelated floating sticks while it still exists. */
    private UUID ownDroppedPaddle;
    private ItemEntity floating;
    private BlockPos branch,water;
    private BlockState branchState;
    private Vec3 berth;
    private HatBoatEntity steering;
    private ItemStack held=ItemStack.EMPTY;
    private boolean reserved;
    private int elapsed,contact,retry;
    private long lastContact=Long.MIN_VALUE;

    public HatFamilyPaddle(YukkuriEntity owner){this.owner=owner;}
    public boolean active(){return recipient!=null||!held.isEmpty();}
    public boolean handingOver(){return !held.isEmpty();}
    /** The one real handoff stick is already part of the parent's boat load until this transfer completes. */
    public boolean handingOverTo(YukkuriEntity child){return handingOver()&&child!=null&&child.getUUID().equals(recipient);}
    /** Identity guard for the one held ItemStack; generic stick insertion never uses this exception. */
    boolean handingOverTo(YukkuriEntity child,ItemStack offered){return handingOverTo(child)&&offered==held&&held.is(Items.STICK);}
    public ItemStack carried(){return held.copy();}
    public ItemStack heldStick(){return carried();}
    public void rememberOwnDroppedPaddle(UUID itemId){if(itemId!=null)ownDroppedPaddle=itemId;}
    public String response(YukkuriEntity child){
        return child!=null&&child.getUUID().equals(recipient)&&handingOver()?Behaviors.BOAT_RECEIVE_PADDLE:Behaviors.IDLE;
    }
    public String activity(){return !held.isEmpty()?Behaviors.BOAT_GIVE_PADDLE:contact>0?Behaviors.BOAT_GATHER:Behaviors.BOAT_PADDLE;}
    public void stop(){stop("external_stop");}
    private void stop(String reason){clear(true,reason);}
    /** A higher-priority family route may already have been installed by the caller. */
    public void yieldToOtherActivity(){clear(false,"yield_to_activity");}
    private void clear(boolean stopRoute,String reason){
        if(active())GameplayLog.limited(owner,"aquatic","paddle.end."+reason,200,
                "family paddle ended child={} hat={} reason={} held={} elapsed={}",recipient,
                steering==null?null:steering.getUUID(),reason,held,elapsed);
        if(steering!=null&&active()){
            if(stopRoute)steering.helm().drift(owner, Purpose.CARE);
            if(reserved&&recipient!=null)steering.releaseCareSeat(recipient);
        }
        var dropped=held;held=ItemStack.EMPTY;
        if(!dropped.isEmpty()&&!owner.level().isClientSide){
            var item=owner.spawnAtLocation(dropped);
            if(item!=null){
                rememberOwnDroppedPaddle(item.getUUID());
                GameplayLog.event(owner,"aquatic","family paddle held item dropped child={} drop={} item={}",recipient,item.getUUID(),item.getItem());
            }
        }
        recipient=null;floating=null;branch=water=null;branchState=null;berth=null;steering=null;
        reserved=false;elapsed=contact=0;lastContact=Long.MIN_VALUE;retry=40;
    }
    private boolean pupil(YukkuriEntity child,HatBoatEntity boat){
        return child!=null&&child.isAlive()&&boat.young(child)&&boat.protects(child)
                &&!child.isPanicking()&&!child.isOnFire()&&!child.isNoAi()&&!child.isCommandPlaying()
                &&!child.operations().active()&&!child.life().isSleeping()&&child.life().mealTicks()==0
                &&!child.life().hasUrgentNeed()&&child.life().nutrition()>=35&&child.life().energy()>=30
                &&child.aquaticLife().firstVoyage().readySize()&&!child.aquaticLife().firstVoyage().independent()
                &&!child.aquaticLife().firstVoyage().active()&&child.subspecies().boatId()==null
                &&!child.aquaticLife().hasStick()&&!child.aquaticLife().firstVoyage().hasRetainedPaddle();
    }
    private boolean available(HatBoatEntity boat){
        if(owner.level().isClientSide||!owner.isAlive()||!owner.profile().aquatic()||owner.getVehicle()!=boat
                ||!owner.getUUID().equals(boat.ownerYukkuri())||!boat.isAfloat()||!boat.protects(owner)
                ||boat.flooding()>0||!boat.cargo().paddle()||owner.isPanicking()||owner.isOnFire()
                ||owner.isNoAi()||owner.isCommandPlaying()||owner.operations().active()||owner.family().laboring()
                ||owner.life().isSleeping()||owner.life().mealTicks()>0||owner.life().hasUrgentNeed()
                ||owner.life().nutrition()<60||owner.life().energy()<40||owner.aquaticLife().care().active()
                ||owner.aquaticLife().firstVoyage().active()||owner.level().isNight()
                ||owner.level().isRainingAt(boat.blockPosition()))return false;
        var attacker=owner.getLastHurtByMob();
        if(attacker!=null&&attacker.isAlive()&&owner.distanceToSqr(attacker)<=64)return false;
        if(owner.level().getEntitiesOfClass(LivingEntity.class,owner.getBoundingBox().inflate(7,3,7),
                y->y!=owner&&y.isAlive()&&(y instanceof Enemy||y instanceof YukkuriEntity other&&other.profile().dangerToYukkuri())
                        &&owner.hasLineOfSight(y)).size()>0)return false;
        return boat.getPassengers().stream().noneMatch(p->p instanceof YukkuriEntity y
                &&(y.isPanicking()||y.isOnFire()||y.life().hasUrgentNeed()));
    }
    /** Recover the exact lost cargo paddle while it is still in reach of its own righted boat. */
    public boolean recoverOwnPaddle(HatBoatEntity boat){
        if(!recoveryAvailable(boat)||boat.cargo().paddle()||ownDroppedPaddle==null||boat.remainingCapacity()<boat.paddleLoad())return false;
        var entity=((ServerLevel)owner.level()).getEntity(ownDroppedPaddle);
        if(!(entity instanceof ItemEntity item)||!usableFloating(boat,item,true)){ownDroppedPaddle=null;return false;}
        if(!onOwnDeck(boat,item)&&boat.distanceToSqr(item)>2.25)return false;
        if(!boat.cargo().givePaddle(item.getItem(),false))return false;
        if(item.getItem().isEmpty())item.discard();
        GameplayLog.event(owner,"aquatic","own paddle recovered hat={} drop={} paddle={}",boat.getUUID(),item.getUUID(),boat.cargo().paddle());
        ownDroppedPaddle=null;owner.speak("take_food");return true;
    }
    private boolean recoveryAvailable(HatBoatEntity boat){
        return !owner.level().isClientSide&&owner.isAlive()&&owner.profile().aquatic()&&owner.getVehicle()==boat
                &&owner.getUUID().equals(boat.ownerYukkuri())&&boat.isAfloat()&&boat.protects(owner)
                &&!boat.unsteadyMotion()&&boat.flooding()==0&&!owner.isPanicking()&&!owner.isOnFire()
                &&!owner.isNoAi()&&!owner.isCommandPlaying()&&!owner.operations().active()&&!owner.family().laboring()
                &&!owner.life().isSleeping()&&!owner.life().hasUrgentNeed();
    }
    /** Invoke after immediate care/physiology. An active handoff owns its temporary care berth until stop(). */
    public boolean tick(HatBoatEntity boat){
        if(!available(boat)){stop("parent_or_hat_unavailable");return false;}
        if(steering!=null&&steering!=boat){stop("hat_changed");return false;}
        steering=boat;
        var level=(ServerLevel)owner.level();
        var child=recipient!=null&&level.getEntity(recipient) instanceof YukkuriEntity y?y:null;
        if(!pupil(child,boat)){
            if(active()){stop("child_unavailable");return false;}
            if(retry>0){retry--;return false;}
            child=boat.getPassengers().stream().filter(YukkuriEntity.class::isInstance).map(YukkuriEntity.class::cast)
                    .filter(y->pupil(y,boat)).findFirst().orElse(null);
            if(child==null)return false;
            recipient=child.getUUID();elapsed=contact=0;
            GameplayLog.limited(owner,"aquatic","paddle.start",200,"family paddle started child={} hat={}",recipient,boat.getUUID());
        }
        if(++elapsed>600){stop("timeout");return false;}
        if(!held.isEmpty())return handOver(boat,child);
        if(boat.remainingCapacity()<boat.paddleLoad())return false;
        if(berth==null&&!find(boat)){stop("no_source_or_route");return false;}
        if(branch!=null&&(!level.hasChunkAt(branch)||!level.getBlockState(branch).equals(branchState))) {stop("branch_changed");return false;}
        if(floating!=null&&(!floating.isAlive()||floating.hasPickUpDelay()||!floating.isInWater()
                ||!floating.getItem().is(Items.STICK))){stop("floating_source_changed");return false;}
        if(floating!=null&&contact==0&&elapsed%20==1){
            var next=floatingBerth(boat,floating);
            if(next==null){stop("floating_berth_unavailable");return false;}
            berth=next;water=BlockPos.containing(next.x,next.y-.12,next.z);
        }
        if(!HatBoatRoute.calmSurface(level,water)||!owner.aquaticLife().familyLife().allowsDestination(boat,berth)){stop("destination_unsafe");return false;}
        if(boat.position().subtract(berth).horizontalDistanceSqr()>.0025){
            resetContact();
            if(elapsed%20==1&&!sail(boat,water,berth)){stop("route_failed");return false;}
            return true;
        }
        Vec3 source=branch!=null?Vec3.atCenterOf(branch):floating.position().add(0,.05,0);
        float stern=SurfaceApproach.yaw(boat.position(),source)+180;
        // Holding the spot and turning the stern toward the source is one helm order: a berth at the present position.
        boat.helm().berth(owner, Purpose.CARE, boat.position(), stern, 0, 4f);
        SurfaceApproach.face(owner,SurfaceApproach.yaw(owner.position(),source));
        Vec3 mouth=mouth(),touch=branch!=null?branchPoint(mouth):source;
        if(Math.abs(Mth.wrapDegrees(stern-boat.getYRot()))>5||mouth.distanceToSqr(touch)>.20*.20||!visible(touch)){
            resetContact();return true;
        }
        if(!contactFor(branch!=null?CONTACT_TICKS:8))return true;
        if(branch!=null){
            // The final valid contact consumes exactly one real source block, with no block loot duplication.
            if(!level.setBlock(branch,Blocks.AIR.defaultBlockState(),3)){stop("branch_pickup_refused");return false;}
            held=new ItemStack(Items.STICK);
        }else{
            var rest=floating.getItem().copy();held=rest.split(1);
            if(rest.isEmpty())floating.discard();else floating.setItem(rest);
        }
        if(floating!=null&&floating.getUUID().equals(ownDroppedPaddle))ownDroppedPaddle=null;
        GameplayLog.event(owner,"aquatic","family paddle reserved child={} hat={} branch={} drop={} held={}",
                recipient,boat.getUUID(),branch,floating==null?null:floating.getUUID(),held);
        floating=null;branch=water=null;branchState=null;berth=null;resetContact();elapsed=0;
        return true;
    }
    private boolean handOver(HatBoatEntity boat,YukkuriEntity child){
        boat.helm().hold(owner, Purpose.CARE);
        if(!reserved){if(!boat.prepareCareSeat(child,owner))return true;reserved=true;}
        if(!boat.careSeatReady(child)){resetContact();return true;}
        FamilyFeedingContact.faceEachOther(owner,child);
        if(!FamilyFeedingContact.mouthsMeet(owner,child)){resetContact();return true;}
        if(contactFor(12)&&child.aquaticLife().receiveFamilyPaddle(this,held)){
            owner.speak("take_food");child.speak("hello");stop("delivered");
        }
        return true;
    }
    private boolean contactFor(int needed){
        long now=owner.level().getGameTime();
        if(now!=lastContact){contact=lastContact!=Long.MIN_VALUE&&now==lastContact+1?contact+1:1;lastContact=now;}
        return contact>=needed;
    }
    private void resetContact(){contact=0;lastContact=Long.MIN_VALUE;}
    private boolean find(HatBoatEntity boat){
        var level=owner.level();
        if(ownDroppedPaddle!=null){
            var entity=((ServerLevel)level).getEntity(ownDroppedPaddle);
            if(entity instanceof ItemEntity item&&usableFloating(boat,item,true)){
                if(item.isInWater()&&reserveFloating(boat,item))return true;
            }else ownDroppedPaddle=null;
        }
        var items=level.getEntitiesOfClass(ItemEntity.class,boat.getBoundingBox().inflate(10,2,10),
                i->usableFloating(boat,i,false)&&!i.getUUID().equals(ownDroppedPaddle));
        items.sort(Comparator.comparingDouble(boat::distanceToSqr));
        for(var item:items)if(reserveFloating(boat,item))return true;
        if(!EventHooks.canEntityGrief(owner.level(),owner))return false;
        var branches=new ArrayList<BlockPos>();
        for(var at:BlockPos.betweenClosed(boat.blockPosition().offset(-10,-1,-10),boat.blockPosition().offset(10,1,10)))
            if(level.hasChunkAt(at)&&twigSource(level.getBlockState(at)))branches.add(at.immutable());
        branches.sort(Comparator.comparingDouble(p->p.distSqr(boat.blockPosition())));
        for(var at:branches)for(var side:Direction.Plane.HORIZONTAL){
            var waterAt=at.relative(side).below();
            if(!HatBoatRoute.calmSurface(level,waterAt))continue;
            var surface=HatBoatRoute.surface(level,waterAt);
            var toward=Vec3.atCenterOf(at).subtract(surface).multiply(1,0,1).normalize();
            var docking=surface.add(toward.scale(.5-boat.getBbWidth()*.5-.03));
            if(reachable(boat,waterAt,docking)){
                branch=at;branchState=level.getBlockState(at);water=waterAt;berth=docking;return true;
            }
        }
        return false;
    }
    private boolean usableFloating(HatBoatEntity boat,ItemEntity item,boolean own){
        return item.isAlive()&&!item.hasPickUpDelay()&&item.getItem().is(Items.STICK)
                &&(item.isInWater()||own&&onOwnDeck(boat,item));
    }
    private boolean onOwnDeck(HatBoatEntity boat,ItemEntity item){
        var local=boat.toLocal(item.position());
        var brim=HatVessel.brim(boat.mode());
        return Math.abs(local.x)<=brim[0]+.15&&Math.abs(local.z)<=brim[1]+.15
                &&local.y>=HatVessel.top(boat.mode())-.4&&local.y<=HatVessel.top(boat.mode())+.6;
    }
    private boolean reserveFloating(HatBoatEntity boat,ItemEntity item){
        var at=floatingBerth(boat,item);if(at==null)return false;
        var waterAt=BlockPos.containing(at.x,at.y-.12,at.z);
        if(!reachable(boat,waterAt,at))return false;
        floating=item;water=waterAt;berth=at;return true;
    }
    private Vec3 floatingBerth(HatBoatEntity boat,ItemEntity item){
        var away=boat.position().subtract(item.position()).multiply(1,0,1).normalize();
        if(away.lengthSqr()<.01)away=new Vec3(0,0,1);
        double reach=Math.abs(boat.ownerSeat(owner).z)*boat.unit()+owner.getBbWidth()*.5+.06;
        var at=item.position().add(away.scale(reach));
        var waterAt=BlockPos.containing(at.x,boat.getY()-.12,at.z);
        return HatBoatRoute.calmSurface(owner.level(),waterAt)?new Vec3(at.x,HatBoatRoute.surface(owner.level(),waterAt).y,at.z):null;
    }
    private boolean twigSource(BlockState state){return state.is(BlockTags.LEAVES)||state.is(Blocks.DEAD_BUSH);}
    private boolean reachable(HatBoatEntity boat,BlockPos waterAt,Vec3 at){
        if(!owner.aquaticLife().familyLife().allowsDestination(boat,at)
                ||!owner.level().noCollision(boat,boat.getBoundingBox().move(at.subtract(boat.position()))))return false;
        var path=HatBoatRoute.find(boat,at,p->owner.aquaticLife().familyLife().allowsDestination(boat,p));
        return path.stream().allMatch(p->owner.aquaticLife().familyLife().allowsDestination(boat,p))
                &&(!path.isEmpty()||finalLegClear(boat,waterAt,at));
    }
    private boolean sail(HatBoatEntity boat,BlockPos waterAt,Vec3 at){
        if(!reachable(boat,waterAt,at))return false;
        var path=new ArrayList<>(HatBoatRoute.find(boat,at,p->owner.aquaticLife().familyLife().allowsDestination(boat,p)));path.add(at);
        return boat.helm().route(owner, Purpose.CARE, path,.0025);
    }
    private boolean finalLegClear(HatBoatEntity boat,BlockPos waterAt,Vec3 at){
        var current=BlockPos.containing(boat.getX(),boat.getY()-.12,boat.getZ());
        if(current.distManhattan(waterAt)>1||boat.position().subtract(at).horizontalDistanceSqr()>2.25)return false;
        int count=Math.max(1,(int)Math.ceil(boat.position().distanceTo(at)/.1));
        for(int i=1;i<=count;i++){
            var p=boat.position().lerp(at,(double)i/count);
            if(!HatBoatRoute.calmSurface(owner.level(),BlockPos.containing(p.x,p.y-.12,p.z))
                    ||!owner.aquaticLife().familyLife().allowsDestination(boat,p)
                    ||!owner.level().noCollision(boat,boat.getBoundingBox().move(p.subtract(boat.position()))))return false;
        }
        return true;
    }
    private Vec3 mouth(){return owner.position().add(Vec3.directionFromRotation(0,owner.getYHeadRot()).scale(owner.getBbWidth()*.5)).add(0,owner.getBbHeight()*.5,0);}
    private Vec3 branchPoint(Vec3 mouth){return new Vec3(Mth.clamp(mouth.x,branch.getX()+.1,branch.getX()+.9),Mth.clamp(mouth.y,branch.getY()+.1,branch.getY()+
            .9),Mth.clamp(mouth.z,branch.getZ()+.1,branch.getZ()+.9));}
    private boolean visible(Vec3 point){
        var hit=owner.level().clip(new ClipContext(owner.position().add(0,owner.getBbHeight()*.5,0),point,
                ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,owner));
        return hit.getType()==HitResult.Type.MISS||branch!=null&&hit.getBlockPos().equals(branch);
    }
    public CompoundTag save(){
        var tag=new CompoundTag();
        if(recipient!=null)tag.putUUID("Recipient",recipient);
        if(ownDroppedPaddle!=null)tag.putUUID("OwnDroppedPaddle",ownDroppedPaddle);
        if(!held.isEmpty())tag.put("Held",held.save(owner.registryAccess()));
        return tag;
    }
    public void load(CompoundTag tag){
        recipient=tag.hasUUID("Recipient")?tag.getUUID("Recipient"):null;
        ownDroppedPaddle=tag.hasUUID("OwnDroppedPaddle")?tag.getUUID("OwnDroppedPaddle"):null;
        held=tag.contains("Held")?ItemStack.parseOptional(owner.registryAccess(),tag.getCompound("Held")):ItemStack.EMPTY;
        floating=null;branch=water=null;branchState=null;berth=null;steering=null;reserved=false;
        elapsed=contact=retry=0;lastContact=Long.MIN_VALUE;
    }
}
