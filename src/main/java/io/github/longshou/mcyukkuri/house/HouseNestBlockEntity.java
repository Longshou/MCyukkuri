package io.github.longshou.mcyukkuri.house;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyConfig;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.registry.ModBlocks;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import io.github.longshou.mcyukkuri.registry.ModItems;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.Comparator;
import java.util.Objects;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.HitResult;

/** A physical home: finite stores, contact care, and four stable egg places backed by the family ledger. */
public final class HouseNestBlockEntity extends BlockEntity {
    public static final int MATERIAL_COST=6,STOCK_CAP=24,EGG_CAP=4;
    private UUID identity=UUID.randomUUID(),broodId,carer;
    private int materials,food,dirt,pendingDebris,ticks,arrivalTicks,arrivals,careTicks,disturbedTicks,maintenanceWait,hatchWait,incubationFoodTicks;
    private boolean destroyed,founderReimu=true;
    private Direction entranceDirection=Direction.NORTH;
    private final HouseEggs.Ref[] eggSlots=new HouseEggs.Ref[EGG_CAP];
    private final float[] eggProgress=new float[EGG_CAP];
    private final boolean[] eggPresent=new boolean[EGG_CAP];
    private final int[] hatchAges={-1,-1,-1,-1};
    /** The source owns the full-clutch itinerary; the target only retains a slot reservation for it. */
    private HouseEggRelocation eggRelocation;
    private HouseEggRelocation.Incoming incomingEggRelocation;
    private long clientSyncAt;
    private List<YukkuriEntity> nearby=List.of();

    public HouseNestBlockEntity(BlockPos pos,BlockState state){super(ModBlocks.HOUSE_NEST_ENTITY.get(),pos,state);}
    public UUID identity(){return identity;}
    public int materials(){return materials;}
    public int food(){return food;}
    public int dirt(){return dirt;}
    public int eggs(){int count=0;for(boolean present:eggPresent)if(present)count++;return count;}
    public float progress(){float sum=0;for(int i=0;i<EGG_CAP;i++)if(eggPresent[i])sum+=eggProgress[i];return eggs()==0?0:sum/eggs();}
    public boolean built(){return materials>=MATERIAL_COST;}
    public int careTicks(){return careTicks;}
    public int disturbedTicks(){return disturbedTicks;}
    public boolean eggPresent(int slot){return slot>=0&&slot<EGG_CAP&&eggPresent[slot];}
    public float eggProgress(int slot){return slot>=0&&slot<EGG_CAP?eggProgress[slot]:0;}
    public int hatchAgeTicks(int slot){
        if(slot<0||slot>=EGG_CAP||hatchAges[slot]<0)return -1;
        int elapsed=level!=null&&level.isClientSide?(int)Math.clamp(level.getGameTime()-clientSyncAt,0,120):0;
        int age=hatchAges[slot]+elapsed;return age<120?age:-1;
    }
    public Vec3 eggLocalPosition(int slot){return HouseEggGeometry.slot(slot);}
    public Vec3 eggPosition(int slot){var local=eggLocalPosition(slot);return localPosition(local.x,local.y,local.z);}
    public Direction entranceDirection(){return entranceDirection;}
    public void setEntrance(Direction direction){
        if(direction!=null&&direction.getAxis().isHorizontal()){entranceDirection=direction;changed();}
    }
    /** Coordinates are authored facing north and rotated with the accessible entrance. */
    public Vec3 localPosition(double x,double y,double z){
        double dx=x-.5,dz=z-.5;
        var rotated=switch(entranceDirection){
            case EAST -> new Vec3(-dz,y,dx);
            case SOUTH -> new Vec3(-dx,y,-dz);
            case WEST -> new Vec3(dz,y,-dx);
            default -> new Vec3(dx,y,dz);
        };
        return Vec3.atBottomCenterOf(worldPosition).add(rotated);
    }
    public Vec3 toLocal(Vec3 point){
        var delta=point.subtract(Vec3.atBottomCenterOf(worldPosition));
        return switch(entranceDirection){
            case EAST -> new Vec3(delta.z+.5,delta.y,-delta.x+.5);
            case SOUTH -> new Vec3(-delta.x+.5,delta.y,-delta.z+.5);
            case WEST -> new Vec3(-delta.z+.5,delta.y,delta.x+.5);
            default -> delta.add(.5,0,.5);
        };
    }
    public FamilyData.Brood brood(){return level instanceof ServerLevel server?FamilyData.get(server).brood(broodId):null;}
    public static boolean isMaterial(ItemStack item){return SupplyPiles.lint(item);}
    public boolean deposit(ItemStack item){
        if(destroyed||item.getCount()!=1)return false;
        if(SupplyPiles.crumb(item)&&food<STOCK_CAP)food++;
        else if(isMaterial(item)&&materials<MATERIAL_COST)materials++;
        else return false;
        changed();return true;
    }
    public ItemStack takeFood(){if(food==0||destroyed)return ItemStack.EMPTY;food--;changed();return SupplyPiles.crumbUnit();}
    public boolean usable(YukkuriEntity entity){
        return entity.profile().houseBody()&&!destroyed&&entity.life().dryStand(entrance())&&entity.life().covered(entrance());
    }
    public BlockPos entrance(){return worldPosition.relative(entranceDirection);}
    /** Residents keep this much crumb stock in hand: enough for a clutch and its incubation. */
    public static final int FOOD_GOAL=12;
    public boolean destroyed(){return destroyed;}
    /** The parents can clear one unit of dirt only once per maintenance wait; tending sooner would achieve nothing. */
    public boolean canClean(){return maintenanceWait==0;}
    /** A founder made the nest; its partner is the other kind of house yukkuri and arrives later. */
    public void markFounder(boolean reimu){founderReimu=reimu;arrivals=Math.max(arrivals,1);changed();}
    /** Too dirty, being disturbed, or covered by a repellent: parents would move the clutch if they could. */
    public boolean unfit(){
        return destroyed||dirt>=6||disturbedTicks>0||level instanceof ServerLevel server&&HouseholdProtection.protectedAt(server,worldPosition);
    }
    public boolean hasEggRoom(){
        return eggRoom()>0;
    }
    public int eggRoom(){
        return Math.max(0,rawEggRoom()-(incomingEggRelocation==null?0:incomingEggRelocation.remaining()));
    }
    private int rawEggRoom(){
        if(destroyed||!built())return 0;
        int room=0;
        for(int slot=0;slot<EGG_CAP;slot++)if(eggSlots[slot]==null&&hatchAges[slot]<0)room++;
        return room;
    }

    // ------------------------------------------------------------------------------------------------ moving a full clutch
    HouseEggRelocation relocation(){return eggRelocation;}
    /** Read-only progress for scenario logs: source owns remaining/in-flight/arrived counts until final household handoff. */
    public String eggRelocationStatus(){return eggRelocation==null?"-":eggRelocation.status();}
    boolean relocationTargets(HouseNestBlockEntity target){return eggRelocation!=null&&eggRelocation.targets(target);}
    private int incomingReservationFor(HouseNestBlockEntity target){return relocationTargets(target)?eggRelocation.incomingReservation():-1;}
    private void reconcileEggRelocation(ServerLevel server){
        if(eggRelocation==null)return;
        boolean changed=eggRelocation.reconcile(server,this);
        var target=relocationTarget(server);
        // The source alone releases the reservation, after both ticket and parent obligations have closed.
        if(eggRelocation.readyToClose()&&(target!=null||relocationTargetGone(server))){finishEggRelocation(target);return;}
        if(changed)changed();
        if(eggRelocation.handoff()){
            if(target!=null&&target.unfit()){
                eggRelocation.startRollback();
                restoreRollbackCarrier(target);
                GameplayLog.world(server,"house","egg relocation rollback source={} destination={} reason=destination_unfit_handoff",worldPosition,target.worldPosition);
                changed();
            }else if(target!=null)advanceEggRelocationHandoff(target);
            else if(relocationTargetGone(server)){
                eggRelocation.startRollback();
                restoreRollbackCarrier(null);
                GameplayLog.world(server,"house","egg relocation rollback source={} destination={} reason=destination_destroyed_handoff",worldPosition,eggRelocation.target().pos());
                changed();
            }
        }
        // A target parent may load after the rollback began; keep restoring one runner until the real return legs start.
        if(eggRelocation!=null&&eggRelocation.rollback()&&eggRelocation.movedCount()>0&&!eggRelocation.carrying())restoreRollbackCarrier(target);
        if(eggRelocation!=null&&eggRelocation.rollbackHandoff()&&(target!=null||relocationTargetGone(server)))advanceEggRelocationRollbackHandoff(target);
        else if(eggRelocation!=null&&eggRelocation.rollbackComplete()&&relocationTargetGone(server))finishEggRelocation(null);
    }
    private HouseNestBlockEntity relocationTarget(ServerLevel server){
        if(eggRelocation==null||!eggRelocation.target().dimension().equals(server.dimension())||!server.hasChunkAt(eggRelocation.target().pos()))return null;
        return server.getBlockEntity(eggRelocation.target().pos()) instanceof HouseNestBlockEntity found&&eggRelocation.targets(found)?found:null;
    }
    /** Only a loaded replacement or destroyed block is final; an unloaded target keeps the durable itinerary waiting. */
    private boolean relocationTargetGone(ServerLevel server){
        if(eggRelocation==null||!eggRelocation.target().dimension().equals(server.dimension())||!server.hasChunkAt(eggRelocation.target().pos()))return false;
        return !(server.getBlockEntity(eggRelocation.target().pos()) instanceof HouseNestBlockEntity target)
                ||!eggRelocation.targets(target)||target.destroyed();
    }
    /** Source-tick owner for the final family handoff: move each available recorded parent and retain unavailable ones for a later tick. */
    boolean advanceEggRelocationHandoff(HouseNestBlockEntity target){
        if(!(level instanceof ServerLevel server)||eggRelocation==null||!eggRelocation.handoff()||target==null||!relocationTargets(target)||target.unfit())return false;
        boolean changed=false;
        var family=FamilyData.get(server);
        for(var id:eggRelocation.parents()){
            var person=family.person(id);
            if(person!=null&&(person.status==FamilyData.Status.DEAD||person.status==FamilyData.Status.REMOVED||person.status==FamilyData.Status.LOST)){
                if(eggRelocation.handoffParent(id)){
                    GameplayLog.world(server,"house","egg relocation parent resolved source={} destination={} parent={} reason=terminal",worldPosition,target.worldPosition,id);
                    changed=true;
                }
                continue;
            }
            if(!(server.getEntity(id) instanceof YukkuriEntity parent)||!parent.isAlive())continue;
            if(parent.house().claims(target)){
                if(eggRelocation.arrivedParent(id)){
                    GameplayLog.event(parent,"house","egg relocation parent resolved source={} destination={} reason=already_arrived",worldPosition,target.worldPosition);
                    changed=true;
                }
                continue;
            }
            // The source is loaded while this tick runs, so a non-source claim is a real change even if that new home chunk is absent.
            if(!parent.house().claims(this)){
                if(eggRelocation.handoffParent(id)){
                    GameplayLog.event(parent,"house","egg relocation parent resolved source={} destination={} reason=home_changed",worldPosition,target.worldPosition);
                    changed=true;
                }
                continue;
            }
            if(target.usable(parent)&&parent.life().reachable(target.entrance(),false)&&parent.house().choose(target)&&eggRelocation.arrivedParent(id)){
                GameplayLog.event(parent,"house","egg relocation parent handoff source={} destination={}",worldPosition,target.worldPosition);
                changed=true;
            }
        }
        if(eggRelocation.parents().isEmpty()){
            eggRelocation.completeHandoff();
            var destination=target.worldPosition;
            finishEggRelocation(target);
            GameplayLog.world(server,"house","egg relocation parent handoff complete source={} destination={}",worldPosition,destination);
            return true;
        }
        if(changed)changed();
        return false;
    }
    /** Keep one loaded target parent as the source-side runner before egg rollback begins; it remains on the durable return list. */
    private void restoreRollbackCarrier(HouseNestBlockEntity target){
        if(!(level instanceof ServerLevel server)||eggRelocation==null||!eggRelocation.rollback())return;
        for(var id:eggRelocation.arrivedParents()){
            if(!(server.getEntity(id) instanceof YukkuriEntity parent)||!parent.isAlive()||parent.house().claims(this))continue;
            boolean stillTarget=parent.house().claims(eggRelocation.target(),eggRelocation.targetIdentity());
            boolean lostDestroyedHome=target==null&&!parent.house().hasHomeClaim();
            if((stillTarget||lostDestroyedHome)&&usable(parent)&&parent.life().reachable(entrance(),false)&&parent.house().choose(this)){
                GameplayLog.event(parent,"house","egg relocation rollback carrier restored source={} destination={}",worldPosition,eggRelocation.target().pos());
                changed();
                return;
            }
        }
    }
    /** Once every egg has physically returned, send only the parents that already reached the failed target back to this source. */
    private boolean advanceEggRelocationRollbackHandoff(HouseNestBlockEntity target){
        if(!(level instanceof ServerLevel server)||eggRelocation==null||!eggRelocation.rollbackHandoff())return false;
        boolean changed=false;
        var family=FamilyData.get(server);
        for(var id:eggRelocation.arrivedParents()){
            var person=family.person(id);
            if(person!=null&&(person.status==FamilyData.Status.DEAD||person.status==FamilyData.Status.REMOVED||person.status==FamilyData.Status.LOST)){
                if(eggRelocation.returnedParent(id)){
                    GameplayLog.world(server,"house","egg relocation rollback parent resolved source={} destination={} parent={} reason=terminal",worldPosition,eggRelocation.target().pos(),id);
                    changed=true;
                }
                continue;
            }
            if(!(server.getEntity(id) instanceof YukkuriEntity parent)||!parent.isAlive())continue;
            if(parent.house().claims(this)){
                if(eggRelocation.returnedParent(id)){
                    GameplayLog.event(parent,"house","egg relocation rollback parent resolved source={} destination={} reason=returned",worldPosition,eggRelocation.target().pos());
                    changed=true;
                }
                continue;
            }
            if(!parent.house().claims(eggRelocation.target(),eggRelocation.targetIdentity())
                    && !(target==null&&!parent.house().hasHomeClaim())){
                if(eggRelocation.returnedParent(id)){
                    GameplayLog.event(parent,"house","egg relocation rollback parent resolved source={} destination={} reason=home_changed",worldPosition,eggRelocation.target().pos());
                    changed=true;
                }
                continue;
            }
            if(!usable(parent)){
                if(eggRelocation.returnedParent(id)){
                    GameplayLog.event(parent,"house","egg relocation rollback parent interrupted source={} destination={} reason=source_unusable",worldPosition,eggRelocation.target().pos());
                    changed=true;
                }
                continue;
            }
            if(parent.life().reachable(entrance(),false)&&parent.house().choose(this)&&eggRelocation.returnedParent(id)){
                GameplayLog.event(parent,"house","egg relocation rollback parent source={} destination={}",worldPosition,eggRelocation.target().pos());
                changed=true;
            }
        }
        if(eggRelocation.readyToClose()){
            var destination=eggRelocation.target().pos();
            finishEggRelocation(target);
            GameplayLog.world(server,"house","egg relocation rollback parent handoff complete source={} destination={}",worldPosition,destination);
            return true;
        }
        if(changed)changed();
        return false;
    }
    boolean beginEggRelocation(HouseNestBlockEntity target){
        if(!(level instanceof ServerLevel server)||target==null||target==this||target.level!=server||target.unfit()
                ||eggRelocation!=null||incomingEggRelocation!=null)return false;
        var refs=relocationEggs(server);
        if(refs.isEmpty()||!target.reserveIncoming(this,refs.size()))return false;
        eggRelocation=new HouseEggRelocation(GlobalPos.of(server.dimension(),target.getBlockPos()),target.identity(),refs,relocationParents(server,refs));
        changed();return true;
    }
    private List<HouseEggs.Ref> relocationEggs(ServerLevel server){
        syncEggs(server);
        var refs=new ArrayList<HouseEggs.Ref>();
        for(var ref:eggSlots)if(ref!=null&&HouseEggs.belongs(HouseEggs.resolve(server,ref),this))refs.add(ref);
        return refs;
    }
    /** The brood ledger fixes the two parents that must complete the final home handoff; nearby residents are supplementary only. */
    private List<UUID> relocationParents(ServerLevel server,List<HouseEggs.Ref> refs){
        var parents=new ArrayList<UUID>();
        for(var ref:refs){
            var entry=HouseEggs.resolve(server,ref);var brood=entry==null?null:entry.brood();
            if(brood==null)continue;
            if(brood.mother!=null&&!parents.contains(brood.mother))parents.add(brood.mother);
            if(brood.father!=null&&!parents.contains(brood.father))parents.add(brood.father);
        }
        return parents;
    }
    private boolean reserveIncoming(HouseNestBlockEntity source,int count){
        if(!(level instanceof ServerLevel server)||source==null||source.level!=server||count<=0||incomingEggRelocation!=null||eggRelocation!=null
                ||unfit()||rawEggRoom()<count)return false;
        incomingEggRelocation=new HouseEggRelocation.Incoming(GlobalPos.of(server.dimension(),source.getBlockPos()),source.identity(),count);
        changed();return true;
    }
    private boolean acceptsIncoming(HouseNestBlockEntity source){return incomingEggRelocation!=null&&incomingEggRelocation.from(source);}
    private void consumeIncoming(HouseNestBlockEntity source){
        if(!acceptsIncoming(source))return;
        incomingEggRelocation=incomingEggRelocation.consumed();changed();
    }
    private void releaseIncoming(HouseNestBlockEntity source){
        if(acceptsIncoming(source)){incomingEggRelocation=null;changed();}
    }
    /** Loaded source state is authoritative; an unloaded source may resume, but a removed or replaced one cannot keep a slot forever. */
    private void reconcileIncoming(ServerLevel server){
        if(incomingEggRelocation==null)return;
        var sourcePos=incomingEggRelocation.source();
        if(!sourcePos.dimension().equals(server.dimension())){incomingEggRelocation=null;changed();return;}
        if(!server.hasChunkAt(sourcePos.pos()))return;
        if(!(server.getBlockEntity(sourcePos.pos()) instanceof HouseNestBlockEntity source)
                ||!incomingEggRelocation.sourceIdentity().equals(source.identity())){incomingEggRelocation=null;changed();return;}
        source.reconcileEggRelocation(server);
        int remaining=source.incomingReservationFor(this);
        if(remaining<0){incomingEggRelocation=null;changed();return;}
        if(incomingEggRelocation.remaining()!=remaining){incomingEggRelocation=incomingEggRelocation.remaining(remaining);changed();}
    }
    /** Lift one source-owned egg only after its source itinerary has named this carrier. */
    ItemStack liftRelocationForward(YukkuriEntity entity){
        if(!(level instanceof ServerLevel server)||eggRelocation==null||!eggRelocation.forward()||!eggRelocation.readyFor(entity.getUUID())
                ||entity.house().nest()!=this||!atEggs(entity))return ItemStack.EMPTY;
        var ref=eggRelocation.nextForward();
        if(ref==null)return ItemStack.EMPTY;
        var egg=extractEgg(ref);
        if(egg.isEmpty())return ItemStack.EMPTY;
        if(eggRelocation.beginCarry(entity.getUUID(),ref,false)){changed();return egg;}
        insertEgg(egg);return ItemStack.EMPTY;
    }
    /** The same carrier physically takes only this source's transferred egg back from its named target. */
    ItemStack liftRelocationRollback(YukkuriEntity entity,HouseNestBlockEntity target){
        if(!(level instanceof ServerLevel)||eggRelocation==null||!eggRelocation.rollback()||!eggRelocation.readyFor(entity.getUUID())
                ||!relocationTargets(target))return ItemStack.EMPTY;
        var ref=eggRelocation.nextRollback();
        if(ref==null)return ItemStack.EMPTY;
        var egg=target.extractIncomingEgg(entity,this,ref);
        if(egg.isEmpty())return ItemStack.EMPTY;
        if(eggRelocation.beginCarry(entity.getUUID(),ref,true)){changed();return egg;}
        target.insertEgg(egg);return ItemStack.EMPTY;
    }
    private ItemStack extractIncomingEgg(YukkuriEntity entity,HouseNestBlockEntity source,HouseEggs.Ref ref){
        if(!(level instanceof ServerLevel)||destroyed||!acceptsIncoming(source)||!atEggs(entity))return ItemStack.EMPTY;
        return extractEgg(ref);
    }
    private ItemStack extractEgg(HouseEggs.Ref wanted){
        if(!(level instanceof ServerLevel server)||destroyed||wanted==null)return ItemStack.EMPTY;
        for(int slot=0;slot<EGG_CAP;slot++)if(wanted.equals(eggSlots[slot])){
            var stack=HouseEggs.extract(server,wanted,this);
            if(!stack.isEmpty()){eggSlots[slot]=null;syncEggs(server);changed();return stack;}
        }
        return ItemStack.EMPTY;
    }
    enum RelocationTransfer { REJECTED, MOVED, HANDOFF, ROLLED_BACK }
    RelocationTransfer depositRelocation(YukkuriEntity entity,HouseNestBlockEntity target,ItemStack stack){
        if(!(level instanceof ServerLevel server)||eggRelocation==null||target!=null&&!relocationTargets(target))return RelocationTransfer.REJECTED;
        var ref=eggRelocation.carryingRef();
        if(!eggRelocation.ownedBy(entity.getUUID())||!HouseEggs.ticket(server,stack,ref))return RelocationTransfer.REJECTED;
        if(eggRelocation.rollback()){
            if(!insertEgg(stack))return RelocationTransfer.REJECTED;
            boolean complete=eggRelocation.carryingBack()?eggRelocation.depositedRollback(entity.getUUID(),ref):returnForwardCarry();
            if(target==null){eggRelocation=null;changed();return RelocationTransfer.ROLLED_BACK;}
            if(complete&&eggRelocation.readyToClose())finishEggRelocation(target);
            else changed();
            return complete?RelocationTransfer.ROLLED_BACK:RelocationTransfer.MOVED;
        }
        if(target==null||!eggRelocation.forward()||!target.acceptsIncoming(this)||!target.insertIncomingEgg(stack,this))return RelocationTransfer.REJECTED;
        if(!eggRelocation.depositedForward(entity.getUUID(),ref))return RelocationTransfer.REJECTED;
        target.consumeIncoming(this);changed();
        return eggRelocation.handoff()?RelocationTransfer.HANDOFF:RelocationTransfer.MOVED;
    }
    private boolean returnForwardCarry(){eggRelocation.returnedToOrigin();return eggRelocation.rollbackComplete();}
    /** The ticket remains as one floor item when neither recorded nest can accept it; clear only the stale itinerary. */
    boolean abandonRelocationCarry(YukkuriEntity entity,HouseNestBlockEntity target,ItemStack stack){
        if(!(level instanceof ServerLevel server)||eggRelocation==null)return false;
        var ref=eggRelocation.carryingRef();
        if(!eggRelocation.ownedBy(entity.getUUID())||!HouseEggs.ticket(server,stack,ref))return false;
        eggRelocation.returnedToOrigin();
        abandonEggRelocation(target);return true;
    }
    boolean beginEggRelocationRollback(HouseNestBlockEntity target){
        if(eggRelocation==null||target==null||!relocationTargets(target))return false;
        eggRelocation.startRollback();
        if(eggRelocation.readyToClose())finishEggRelocation(target);
        else changed();
        return true;
    }
    /** The target was confirmed replaced or destroyed.  Keep a carried real egg until it reaches this source, then clear the stale route. */
    boolean beginEggRelocationRollback(){
        if(eggRelocation==null)return false;
        eggRelocation.startRollback();changed();return true;
    }
    boolean finishEggRelocation(HouseNestBlockEntity target){
        if(eggRelocation==null||target!=null&&!relocationTargets(target))return false;
        if(target!=null)target.releaseIncoming(this);
        eggRelocation=null;changed();return true;
    }
    void abandonEggRelocation(HouseNestBlockEntity target){
        if(eggRelocation==null)return;
        if(target!=null&&relocationTargets(target))target.releaseIncoming(this);
        eggRelocation=null;changed();
    }
    private void abandonEggRelocation(){
        HouseNestBlockEntity target=null;
        if(level!=null&&eggRelocation!=null&&eggRelocation.target().dimension().equals(level.dimension())
                &&level.hasChunkAt(eggRelocation.target().pos())
                &&level.getBlockEntity(eggRelocation.target().pos()) instanceof HouseNestBlockEntity found
                && eggRelocation.targets(found))target=found;
        abandonEggRelocation(target);
    }
    /**
     * Starts an ordinary house-egg brood and installs every child in this nest in one transaction.
     * Callers still establish any relationship or scenario preconditions themselves; this method never
     * manufactures tickets or writes child identity outside {@link FamilyData#conceive}.
     */
    public boolean createClutch(YukkuriEntity mother,YukkuriEntity father){
        if(!(level instanceof ServerLevel server)||mother==null||father==null||mother==father||mother.level()!=server||father.level()!=server
                ||mother.house().nest()!=this||father.house().nest()!=this||!readyForNewClutch())return false;
        var data=FamilyData.get(server);
        var brood=data.conceive(mother,father,FamilyData.BirthKind.EGG,GlobalPos.of(server.dimension(),worldPosition));
        if(brood==null)return false;
        if(installNewClutch(server,brood))return true;
        // All installation gates were checked before conceiving, so this is defensive only.  Do not leave an
        // unborn ledger entry if a future change adds an installation failure after conception.
        data.cancel(brood);
        GameplayLog.world(server,"family","brood cancelled brood={} mother={} nest={} reason=clutch_install_failed",brood.id,brood.mother,worldPosition);
        return false;
    }
    private boolean readyForNewClutch(){
        return eggRelocation==null&&incomingEggRelocation==null&&!unfit()&&built()&&food>=4&&eggs()==0&&Arrays.stream(eggSlots).allMatch(Objects::isNull)
                &&Arrays.stream(hatchAges).allMatch(age->age<0);
    }
    /** Atomically claims all members of a freshly recorded EGG brood; callers never see a partial clutch. */
    private boolean installNewClutch(ServerLevel server,FamilyData.Brood brood){
        if(brood==null||brood.cancelled||brood.kind!=FamilyData.BirthKind.EGG||FamilyData.get(server).brood(brood.id)!=brood
                ||!readyForNewClutch()||brood.children.isEmpty()||brood.children.size()>EGG_CAP)return false;
        for(var child:brood.children)
            if(child==null||child.born||child.eggLost||child.eggManaged||child.eggNest!=null||child.eggNestId!=null||child.eggToken!=null)return false;
        broodId=brood.id;
        for(int slot=0;slot<brood.children.size();slot++)eggSlots[slot]=HouseEggs.attachNew(brood,brood.children.get(slot),this);
        food-=4;syncEggs(server);changed();FamilyData.get(server).setDirty();return true;
    }
    private boolean sheltered(){
        // Match living-entity shelter checks: actual roof blocks, not delayed skylight propagation.
        return level!=null&&level.hasChunkAt(entrance())
                &&level.getHeight(Heightmap.Types.MOTION_BLOCKING,
                entrance().getX(),entrance().getZ())>entrance().getY();
    }
    private boolean atEggs(YukkuriEntity entity){
        if(!entity.onGround()||!entity.blockPosition().equals(worldPosition)||entity.getY()>worldPosition.getY()+.3)return false;
        return level.clip(new ClipContext(entity.getEyePosition(),eggPosition(0).add(0,.06,0),
                ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,entity))
                .getType()==HitResult.Type.MISS;
    }
    private List<YukkuriEntity> residents(){
        return nearby.stream().filter(e->e.isAlive()&&worldPosition.equals(e.house().nestPosition())&&e.house().nest()==this).toList();
    }
    /** Called at the actual contact beat, not while a parent is merely walking towards the nest. */
    public boolean tend(YukkuriEntity entity){
        if(!(level instanceof ServerLevel server)||HouseholdProtection.protectedAt(server,worldPosition)||!built()||destroyed||disturbedTicks>0||!healthyCarer(entity)
                ||entity.house().nest()!=this||entity.position().distanceToSqr(eggPosition(0))>1.21
                ||!atEggs(entity))return false;
        carer=entity.getUUID();careTicks=240;
        if(dirt>0&&maintenanceWait==0){
            // One attempt per wait: when the floor outside cannot take more waste the nest simply stays dirty.
            if(HouseholdEcology.residual(server,entrance().relative(entranceDirection),new ItemStack(ModItems.WASTE.get()))==1)dirt--;
            maintenanceWait=240;
        }
        changed();return true;
    }
    /** A parent standing at the eggs takes one in its mouth to carry it elsewhere. */
    public ItemStack lift(YukkuriEntity entity){
        if(destroyed||!(level instanceof ServerLevel)||entity.house().nest()!=this||entity.position().distanceToSqr(eggPosition(0))>1.21||!atEggs(entity))return ItemStack.EMPTY;
        return extractEgg();
    }
    public ItemStack extractEgg(){
        if(!(level instanceof ServerLevel server)||destroyed)return ItemStack.EMPTY;
        for(int slot=0;slot<EGG_CAP;slot++){
            var stack=HouseEggs.extract(server,eggSlots[slot],this);
            if(!stack.isEmpty()){eggSlots[slot]=null;syncEggs(server);changed();return stack;}
        }
        return ItemStack.EMPTY;
    }
    public boolean insertEgg(ItemStack stack){return insertEgg(stack,null);}
    private boolean insertIncomingEgg(ItemStack stack,HouseNestBlockEntity source){
        return acceptsIncoming(source)&&insertEgg(stack,source);
    }
    private boolean insertEgg(ItemStack stack,HouseNestBlockEntity incomingSource){
        if(!(level instanceof ServerLevel server)||destroyed||!built()||!sheltered()
                ||!level.getFluidState(entrance()).isEmpty()||!level.getFluidState(worldPosition).isEmpty())return false;
        syncEggs(server);
        if(incomingSource==null&&eggRoom()==0)return false;
        for(int slot=0;slot<EGG_CAP;slot++)if(eggSlots[slot]==null&&hatchAges[slot]<0){
            var ref=HouseEggs.insert(server,stack,this);if(ref==null)return false;
            eggSlots[slot]=ref;syncEggs(server);changed();return true;
        }
        return false;
    }
    private boolean syncEggs(ServerLevel server){
        boolean changed=false;
        for(int slot=0;slot<EGG_CAP;slot++){
            var entry=HouseEggs.resolve(server,eggSlots[slot]);
            boolean present=HouseEggs.belongs(entry,this);float next=present?entry.progress():0;
            if(!present)eggSlots[slot]=null;
            changed|=eggPresent[slot]!=present||eggProgress[slot]!=next;
            eggPresent[slot]=present;eggProgress[slot]=next;
        }
        return changed;
    }
    public void tick(){
        if(!(level instanceof ServerLevel server)||destroyed)return;
        reconcileEggRelocation(server);reconcileIncoming(server);
        boolean protectedHome=HouseholdProtection.protectedAt(server,worldPosition);
        ticks++;
        if(ticks%20==1)nearby=level.getEntitiesOfClass(YukkuriEntity.class,new AABB(worldPosition).inflate(8),
                e->e.isAlive()&&e.profile().houseBody());
        var residents=residents();
        if(careTicks>0&&--careTicks==0){carer=null;changed();}
        if(disturbedTicks>0&&--disturbedTicks==0)changed();
        if(maintenanceWait>0)maintenanceWait--;
        if(hatchWait>0)hatchWait--;
        for(int slot=0;slot<EGG_CAP;slot++)if(hatchAges[slot]>=0&&++hatchAges[slot]>=120){
            hatchAges[slot]=-1;
            // A shell becomes one unit of nest debris; full nests eject the real excess.
            if(dirt<8)dirt++;
            else if(HouseholdEcology.residual(server,entrance().relative(entranceDirection),new ItemStack(ModItems.WASTE.get()))==0)pendingDebris++;
            changed();
        }
        if(pendingDebris>0&&dirt<8){pendingDebris--;dirt++;changed();}
        // Existing crumbs OR nesting fibers are enough for opportunistic settlers, in daylight too.
        if(HouseholdEcology.enabled(server)
                &&arrivals<2&&(food>0||materials>0)&&disturbedTicks==0&&sheltered()&&residents.size()<2
                &&HouseholdEcology.recentlyActive(server,worldPosition)&&!HouseholdEcology.suppressed(server,worldPosition)
                &&HouseholdEcology.shelteredCorner(server,worldPosition)){
            if(++arrivalTicks>=FamilyConfig.HOUSE_ARRIVAL_TIME.get()){
                arrivalTicks=0;
                if(FamilyData.get(server).population(server,worldPosition)<FamilyConfig.POPULATION_CAP.get()){
                    // The partner of the founder is the other kind of house yukkuri.
                    var entity=(arrivals==0==founderReimu?ModEntities.HOUSE_REIMU.get():ModEntities.HOUSE_MARISA.get()).create(server);
                    if(entity!=null){
                        entity.moveTo(Vec3.atBottomCenterOf(entrance()));entity.setPersistenceRequired();
                        if(entity.life().dryStand(entrance()))entity.setOnGround(true);
                        if(entity.life().dryStand(entrance())&&server.noCollision(entity)&&entity.house().choose(this)&&server.addFreshEntity(entity)){
                            arrivals++;entity.showReaction("surprise","house_arrival",100);changed();
                        }
                    }
                }
            }
        }
        if(ticks%600==0&&!residents.isEmpty()){dirt=Math.min(8,dirt+1);changed();}
        syncEggs(server);
        boolean cared=!protectedHome&&careTicks>0&&carer!=null&&server.getEntity(carer) instanceof YukkuriEntity parent
                &&healthyCarer(parent)&&parent.house().nest()==this&&atEggs(parent)&&parent.position().distanceToSqr(eggPosition(0))<1.5625;
        if(!cared&&careTicks>0){careTicks=0;carer=null;changed();}
        if(built()&&dirt<6&&food>0&&disturbedTicks==0&&cared&&eggs()>0){
            for(int slot=0;slot<EGG_CAP;slot++){
                var entry=HouseEggs.resolve(server,eggSlots[slot]);if(!HouseEggs.belongs(entry,this))continue;
                entry.child().eggElapsed=Math.min(entry.brood().duration,entry.child().eggElapsed+1);
                // The clutch summary remains useful to old notebook callers; custody is per egg.
                entry.brood().elapsed=Math.max(entry.brood().elapsed,entry.child().eggElapsed);
                if(hatchWait==0&&entry.progress()>=1){
                    var baby=FamilyData.get(server).birth(entry.brood(),entry.index(),server,eggPosition(slot).add(0,HouseEggGeometry.HATCH_FOOT_LIFT,0));
                    if(baby!=null){
                        baby.house().choose(this);baby.showReaction("happy","hatched",80);
                        eggSlots[slot]=null;hatchAges[slot]=0;hatchWait=48;
                        if(server.getEntity(carer) instanceof YukkuriEntity witness)witness.showReaction("happy","house_hatched",80);
                        syncEggs(server);
                        changed();
                    }
                }
            }
            if(++incubationFoodTicks>=600){incubationFoodTicks=0;food--;changed();}
            FamilyData.get(server).setDirty();
        }
        // Founding a clutch needs both parents physically at home. A transferred clutch occupies real slots.
        if(!protectedHome&&ticks%100==0&&readyForNewClutch()){
            var candidates=residents.stream().filter(this::healthyCarer)
                    .filter(e->e.position().distanceToSqr(eggPosition(0))<2.25)
                    .sorted(Comparator.comparing(YukkuriEntity::getUUID)).toList();
            outer:for(int i=0;i<candidates.size();i++)for(int j=i+1;j<candidates.size();j++){
                var a=candidates.get(i);var b=candidates.get(j);
                if((a.family().partner()==null||a.family().partner().equals(b.getUUID()))&&(b.family().partner()==null||b.family().partner().equals(a.getUUID()))){
                    if(createClutch(a,b)){a.showReaction("happy","laid_eggs",100);break outer;}
                }
            }
        }
        if(syncEggs(server)&&ticks%10==0)changed();
        if(ticks%20==0&&(careTicks>0||disturbedTicks>0||Arrays.stream(hatchAges).anyMatch(age->age>=0)))changed();
        if(ticks%200==0)setChanged();
    }
    private boolean healthyCarer(YukkuriEntity entity){
        return entity.isAlive()&&!entity.isNoAi()&&entity.family().adult()&&entity.life().nutrition()>=40
                &&entity.getHealth()>=entity.getMaxHealth()*.5&&entity.body().condition().readyForHouseCare()
                &&!entity.isOnFire()&&!entity.isInWaterOrBubble()&&!entity.isPanicking()&&usable(entity);
    }
    private void disturb(boolean dismantle){
        disturbedTicks=dismantle?240:120;careTicks=0;carer=null;
        if(level!=null)for(var resident:level.getEntitiesOfClass(YukkuriEntity.class,new AABB(worldPosition).inflate(8),
                e->e.isAlive()&&e.profile().houseBody()&&worldPosition.equals(e.house().nestPosition())))resident.house().disturb(dismantle);
    }
    /** Gentle cleaning removes loose dirt and food. Dismantling only loses eggs still physically here. */
    public int clean(boolean dismantle){
        int removed=dirt+pendingDebris+food+(int)Arrays.stream(hatchAges).filter(age->age>=0).count();dirt=food=pendingDebris=0;
        Arrays.fill(hatchAges,-1);
        if(level instanceof ServerLevel server){
            if(dismantle){
                abandonEggRelocation();
                incomingEggRelocation=null;
                removed+=materials+eggs();materials=0;
                for(var ref:eggSlots)HouseEggs.lose(server,ref,this);
                Arrays.fill(eggSlots,null);Arrays.fill(hatchAges,-1);syncEggs(server);broodId=null;
            }
        }
        if(removed>0||dismantle)disturb(dismantle);
        changed();return removed;
    }
    public void destroyNest(){
        if(destroyed)return;
        int debris=materials+dirt+pendingDebris+(int)Arrays.stream(hatchAges).filter(age->age>=0).count();
        if(level instanceof ServerLevel server){
            abandonEggRelocation();
            incomingEggRelocation=null;
            syncEggs(server);debris+=eggs();
            for(var ref:eggSlots)HouseEggs.lose(server,ref,this);
        }
        // Residents must drop an in-flight ticket instead of writing it back into a nest that is being removed.
        destroyed=true;disturb(true);
        if(debris>0)Block.popResource(level,worldPosition,new ItemStack(ModItems.WASTE.get(),debris));
        food=materials=dirt=pendingDebris=0;Arrays.fill(eggSlots,null);Arrays.fill(eggPresent,false);Arrays.fill(eggProgress,0);broodId=null;changed();
    }
    private void changed(){setChanged();if(level!=null&&!level.isClientSide)level.sendBlockUpdated(worldPosition,getBlockState(),getBlockState(),3);}
    @Override protected void saveAdditional(CompoundTag tag,HolderLookup.Provider registries){
        super.saveAdditional(tag,registries);tag.putInt("Version",3);tag.putUUID("Identity",identity);if(broodId!=null)tag.putUUID("Brood",broodId);
        tag.putString("Entrance",entranceDirection.getName());
        tag.putInt("Materials",materials);tag.putInt("Food",food);tag.putInt("Dirt",dirt);tag.putInt("Ticks",ticks);tag.putInt("ArrivalTicks",arrivalTicks);tag.putInt("Arrivals",arrivals);
        tag.putInt("PendingDebris",pendingDebris);tag.putBoolean("FounderReimu",founderReimu);
        tag.putInt("CareTicks",careTicks);tag.putInt("DisturbedTicks",disturbedTicks);tag.putInt("MaintenanceWait",maintenanceWait);
        tag.putInt("HatchWait",hatchWait);tag.putInt("IncubationFoodTicks",incubationFoodTicks);
        var slots=new ListTag();
        for(int slot=0;slot<EGG_CAP;slot++){
            var data=eggSlots[slot]==null?new CompoundTag():eggSlots[slot].save();
            data.putInt("Slot",slot);data.putBoolean("Present",eggPresent[slot]);data.putFloat("Progress",eggProgress[slot]);data.putInt("HatchAge",hatchAges[slot]);slots.add(data);
        }
        tag.put("EggSlots",slots);
        if(eggRelocation!=null)tag.put("EggRelocation",eggRelocation.save());
        if(incomingEggRelocation!=null)tag.put("IncomingEggRelocation",incomingEggRelocation.save());
    }
    @Override protected void loadAdditional(CompoundTag tag,HolderLookup.Provider registries){
        super.loadAdditional(tag,registries);if(tag.hasUUID("Identity"))identity=tag.getUUID("Identity");broodId=tag.hasUUID("Brood")?tag.getUUID("Brood"):null;
        var direction=Direction.byName(tag.getString("Entrance"));
        entranceDirection=direction!=null&&direction.getAxis().isHorizontal()?direction:Direction.NORTH;
        materials=Math.clamp(tag.getInt("Materials"),0,MATERIAL_COST);food=Math.clamp(tag.getInt("Food"),0,STOCK_CAP);dirt=Math.clamp(tag.getInt("Dirt"),0,8);
        pendingDebris=Math.clamp(tag.getInt("PendingDebris"),0,EGG_CAP);founderReimu=!tag.contains("FounderReimu")||tag.getBoolean("FounderReimu");
        ticks=Math.max(0,tag.getInt("Ticks"));arrivalTicks=Math.max(0,tag.getInt("ArrivalTicks"));arrivals=Math.clamp(tag.getInt("Arrivals"),0,2);
        careTicks=Math.clamp(tag.getInt("CareTicks"),0,240);carer=null;
        disturbedTicks=Math.clamp(tag.getInt("DisturbedTicks"),0,240);maintenanceWait=Math.clamp(tag.getInt("MaintenanceWait"),0,240);
        hatchWait=Math.clamp(tag.getInt("HatchWait"),0,48);incubationFoodTicks=Math.clamp(tag.getInt("IncubationFoodTicks"),0,599);
        Arrays.fill(eggSlots,null);Arrays.fill(eggPresent,false);Arrays.fill(eggProgress,0);Arrays.fill(hatchAges,-1);
        for(Tag entry:tag.getList("EggSlots",Tag.TAG_COMPOUND)){
            var data=(CompoundTag)entry;int slot=data.getInt("Slot");if(slot<0||slot>=EGG_CAP)continue;
            eggSlots[slot]=HouseEggs.Ref.load(data);eggPresent[slot]=data.getBoolean("Present");eggProgress[slot]=Math.clamp(data.getFloat("Progress"),0,1);
            hatchAges[slot]=Math.clamp(data.getInt("HatchAge"),-1,119);
        }
        eggRelocation=tag.contains("EggRelocation",Tag.TAG_COMPOUND)?HouseEggRelocation.load(tag.getCompound("EggRelocation")):null;
        incomingEggRelocation=tag.contains("IncomingEggRelocation",Tag.TAG_COMPOUND)
                ?HouseEggRelocation.Incoming.load(tag.getCompound("IncomingEggRelocation")):null;
        clientSyncAt=level==null?0:level.getGameTime();
    }
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider registries){return saveWithoutMetadata(registries);}
    @Override public ClientboundBlockEntityDataPacket getUpdatePacket(){return ClientboundBlockEntityDataPacket.create(this);}
}
