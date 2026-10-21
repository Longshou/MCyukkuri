package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.mind.EmotionEvent;
import io.github.longshou.mcyukkuri.status.GameplayLog;

import io.github.longshou.mcyukkuri.world.SearchBudget.Result;
import io.github.longshou.mcyukkuri.world.SearchBudget.Purpose;
import io.github.longshou.mcyukkuri.world.SearchBudget.Lease;
import io.github.longshou.mcyukkuri.human.Custody;
import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.registry.ModItems;
import io.github.longshou.mcyukkuri.season.Season;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.EventHooks;
import io.github.longshou.mcyukkuri.dialogue.SpeechCount;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity;

/** A real resident, never a hidden duplicate: carries one resource, maintains stores and clears waste. */
public final class HabitatLife {
    enum Task { NONE, DIG, ROAM, FOOD, PICKUP, HARVEST, DEPOSIT, CLEAN, SHARE, STAY, SIESTA, NEST_PICKUP, NEST_HARVEST, NEST_BUILD, GRAIN_TAKE, GRAIN_PUT, RATION }
    private final YukkuriEntity entity;
    private GlobalPos home;
    private ItemStack carrying=ItemStack.EMPTY;
    /** The carried food is a migrant's mouthful from the old store: it is put on the new pile even when tired or hungry. */
    private boolean moveLoad;
    /** The second real portion for a personal nest. Unlike household work, its item identity need not match the first. */
    private ItemStack nestMaterial=ItemStack.EMPTY;
    /** The home that supplied reserved nest material. A move or lost bed cancels this small project in the current world. */
    private GlobalPos nestHome;
    private ItemEntity targetItem;
    private BlockPos targetBlock;
    /** The granary pile of a planned site that the current errand walks to. */
    private BlockPos granary;
    /** Package 12: this granary meal is taken without a ration left (theft); the carried food is the household's fetched ration. */
    private boolean stealing,rationLoad;
    private Rations.Fetch rationFetch;
    private Task task=Task.NONE;
    private int wait,ticks,buildTicks;
    private final PlantBedding plants;
    /** Mouth contact with the forage pile, for stocking it and for taking portions out. */
    private final PileContact contact;
    private final HabitatHomes homes;
    private final HabitatSeasonal seasonal;
    /** What this individual's address is. Derived from the world each time; the only stored fact is the shelter it belongs to. */
    public enum Residence { HOUSED, AFLOAT, VAGRANT, DEPENDENT, ELSEWHERE }
    public HabitatLife(YukkuriEntity entity) {
        this.entity=entity;this.plants=new PlantBedding(entity);this.contact=new PileContact(entity);
        this.homes=new HabitatHomes(this);this.seasonal=new HabitatSeasonal(this);
        wait=20+entity.getRandom().nextInt(100); // a freshly spawned colony must not start every search on one tick
    }
    ServerLevel level() { return (ServerLevel)entity.level(); }
    public boolean hasMemory() { return home!=null; }
    public boolean unavailable() { return home!=null&&(!home.dimension().equals(entity.level().dimension())||!entity.level().hasChunkAt(home.pos())); }
    public ShelterBlockEntity shelter() { return home==null||unavailable()?null:ShelterBlock.shelter(entity.level(),home.pos()); }
    public ItemStack carrying() { return carrying; }
    /**
     * Migration (settlement/Migration PREPARE): a mouthful of up to {@code max} real portions of one kind of food leaves
     * the own store and is carried to the new site, where the ordinary deposit puts it on the new pile. Returns the count.
     */
    public int packForMove(ForagePileBlockEntity pile,int max) {
        if(!carrying.isEmpty()||pile==null)return 0;
        var first=pile.take(stack->entity.life().acceptsFood(stack));
        if(first.isEmpty())return 0;
        while(first.getCount()<max){var more=pile.take(stack->ItemStack.isSameItemSameComponents(stack,first));if(more.isEmpty())break;first.grow(more.getCount());}
        carrying=first;moveLoad=true;
        GameplayLog.event(entity,"habitat","pack store={} item={} moved={} stock={}",pile.getBlockPos(),first.getItem(),first.getCount(),pile.food());
        return first.getCount();
    }
    /** A hungry migrant eats one portion of the food it carries. */
    public boolean eatCarried() {
        if(carrying.isEmpty()||!carrying.is(YukkuriEntity.FOOD)||!entity.life().canEat()||!entity.life().acceptsFood(carrying)||entity.life().mealTicks()!=0)return false;
        var bite=carrying.copyWithCount(1);
        if(!entity.life().beginMeal(bite,null,false))return false;
        carrying.shrink(1);if(carrying.isEmpty()){carrying=ItemStack.EMPTY;moveLoad=false;}
        GameplayLog.event(entity,"habitat","carried meal item={} moved=1 carried={}",bite.getItem(),carrying.getCount());
        return true;
    }
    public boolean active() { return task!=Task.NONE; }
    public CompoundTag debug(){var tag=plants.debug();tag.putString("Task",task.name());homes.debug(tag);tag.putString("Residence",residence().name());tag.putInt("Wait",wait);
            tag.putInt("Ticks",ticks);if(targetBlock!=null)tag.putString("Target",targetBlock.toShortString());return tag;}
    public String activity() { return task==Task.CLEAN?Behaviors.SHELTER_CLEAN:task==Task.NEST_BUILD?nestActivity():(task==Task.NEST_HARVEST||task==Task.HARVEST)
            &&plants.harvesting()?Behaviors.NEST_HARVEST:task==Task.NEST_PICKUP||task==Task.NEST_HARVEST?Behaviors.NEST_GATHER:task==Task.DEPOSIT||task==Task.SHARE
            ||task==Task.GRAIN_PUT?Behaviors.SHELTER_CARRY:task==Task.STAY||task==Task.SIESTA?Behaviors.IDLE:task==Task.HARVEST||task==Task.PICKUP?Behaviors.SHELTER_GATHER:Behaviors.SHELTER_STOCK; }
    private String nestActivity(){
        var value=shelter();if(value==null||targetBlock==null||!SurfaceApproach.arrived(entity,Vec3.atBottomCenterOf(targetBlock)))return Behaviors.NEST_CARRY;
        int phase=value.nestWork(entity.getUUID())%60;return phase<20?Behaviors.NEST_PLACE:phase<40?Behaviors.NEST_SPREAD:Behaviors.NEST_PRESS;
    }
    public BlockPos homeStand() { var value=shelter();return value!=null&&value.usable(entity)?value.bed(entity.getUUID()):null; }
    /** Wild individuals live in recognised homes; pets, house yukkuri, predators, hat-boat families and raiding bands have other arrangements. */
    public boolean wildLiver() {
        var human = Custody.view(entity);
        return !entity.profile().houseBody()&&!entity.profile().dangerToYukkuri()&&!entity.profile().aquatic()&&!roaming()
                &&human.wild();
    }
    boolean grownEnough() { return entity.family().adult()||entity.independence().independent()||entity.childhood().canLiveIndependently(); }
    /** HOUSED: belongs to a recognised land home. AFLOAT: owns and rides a real afloat hat. VAGRANT: a grown wild individual with none
     * (it looks for one or digs its own). DEPENDENT: a young one following its parents. ELSEWHERE: not a wild home-dweller. */
    public Residence residence() {
        if (entity.profile().aquatic()) return aquaticResidence();
        if(!wildLiver())return Residence.ELSEWHERE;
        if(home!=null)return Residence.HOUSED;
        return grownEnough()?Residence.VAGRANT:Residence.DEPENDENT;
    }
    /** A water address is only the physical vessel presently carrying its owner or her dependent child; it is never saved as a land HomePos. */
    private Residence aquaticResidence() {
        if (entity.getVehicle() instanceof HatBoatEntity boat && boat.isAfloat() && boat.protects(entity)) {
            if (entity.getUUID().equals(boat.ownerYukkuri())) return Residence.AFLOAT;
            if (boat.young(entity) && !entity.aquaticLife().firstVoyage().independent()
                    && !entity.independence().independent()) return Residence.DEPENDENT;
        }
        return Residence.ELSEWHERE;
    }
    /** The current physical vessel of an aquatic owner or dependent child. This read deliberately does not repair custody or search nearby hulls. */
    public HatBoatEntity waterHome() {
        if (!entity.profile().aquatic() || !(entity.level() instanceof ServerLevel level)) return null;
        // A child aboard a parent hat has the same physical address for food and status, even though it owns no boat token.
        if (entity.getVehicle() instanceof HatBoatEntity ridden && ridden.protects(entity)
                && (entity.getUUID().equals(ridden.ownerYukkuri()) || ridden.young(entity))) return ridden;
        if (entity.life().accessoryCustodyEnded()) return null;
        var id = entity.life().custodyBoat();
        if (id == null) id = entity.subspecies().boatId();
        return id != null && level.getEntity(id) instanceof HatBoatEntity boat && boat.ownsPhysicalHat(entity) ? boat : null;
    }
    /** Only a rider protected by her own afloat hull has an afloat residence. A grounded owner may retain a physical hat without a fake position. */
    public boolean afloatResidence() { return residence() == Residence.AFLOAT; }
    public boolean vagrant() { return residence()==Residence.VAGRANT; }
    /** Share of the hollow in progress that is already dug, 0 when none. */
    public int digProgress() { return homes.digProgress(); }
    /** Members of this individual's household that need a place: itself, its partner and its young. */
    public int householdNeed() {
        int count=1;
        if(entity.family().partner()!=null)count++;
        var data=FamilyData.get(level());
        for(var id:data.children(entity.getUUID())) {
            var person=data.person(id);
            if(person==null||person.status==FamilyData.Status.DEAD||person.status==FamilyData.Status.REMOVED)continue;
            if(!(level().getEntity(id) instanceof YukkuriEntity child)||!child.family().adult())count++;
        }
        return Math.min(count,8);
    }
    public boolean leaking() {
        var value=shelter();
        return value!=null&&value.moisture()>=60&&value.contains(entity.blockPosition());
    }
    public boolean choose(ShelterBlockEntity value) {
        if(roaming()||entity.profile().houseBody()||entity.profile().dangerToYukkuri()||!value.sound()||value.moisture()>=85||value.dirt()>=90
                ||entity.distanceToSqr(Vec3.atBottomCenterOf(value.getBlockPos()))>32*32)return false;
        // The leaver never moves back into the home it said goodbye to.
        if(entity.independence().avoids(value.getBlockPos()))return false;
        if(hasNestProject()&&!sameNestHome(value)) { cancelNestProject();return false; }
        boolean owned=value.owns(entity.getUUID());
        if(!value.claim(entity))return false;
        BlockPos bed=value.bed(entity.getUUID());
        SearchBudget.probe(entity.level());
        if(!entity.life().dryStand(bed)||!homeReachable(bed)){if(!owned)value.release(entity.getUUID());return false;}
        var old=shelter();
        SearchBudget.path(entity);
        if(!entity.life().assignNaturalHome(bed)){if(!owned)value.release(entity.getUUID());return false;}
        if(old!=null&&old!=value)old.release(entity.getUUID());
        home=GlobalPos.of(entity.level().dimension(),value.getBlockPos());entity.setPersistenceRequired();
        if(old!=value||!owned)GameplayLog.event(entity,"habitat","home chosen old={} home={} bed={}",
                old==null?null:old.getBlockPos(),value.getBlockPos(),bed);
        homes.homeChosen();
        entity.independence().settled(value);return true;
    }
    private boolean homeReachable(BlockPos bed) { SearchBudget.path(entity);return entity.life().reachable(bed,false); }
    public boolean inherit(YukkuriEntity parent) { var value=parent.habitat().shelter();return value!=null&&choose(value); }
    public void release() {
        var value=shelter();if(value!=null)value.release(entity.getUUID());
        if(home!=null)GameplayLog.event(entity,"habitat","home released home={} task={} carried={}",home,task,carrying.getCount());
        home=null;cancelNestProject();stop();
    }
    /** Wandering bands never settle: no burrow is claimed or dug for them. */
    boolean roaming() { var group=entity.settlement().group();return group!=null&&group.kind==SettlementData.Kind.BANDITS; }
    public Result findHome() { return homes.findHome(); }
    /** A settlement arrival uses this bounded admission before it releases the old address. */
    public Lease requestHomeSearch() { return homes.requestHomeSearch(); }
    void retrySearch() { wait=20; }
    public void tick() {
        if(entity.level().isClientSide)return;
        if(homes.vagrantPending()&&!ready())homes.cancelVagrantProject();
        if(entity.isNoAi()||entity.profile().houseBody())return;
        if(hasNestProject()&&!nestProjectValid())cancelNestProject();
        if(wait>0)wait--;
        homes.clock();
        seasonal.clock();
        if(entity.tickCount%100!=0)return;
        var value=shelter();boolean inside=value!=null&&value.contains(entity.blockPosition());
        int wet=inside&&value.moisture()>=60?3:0;
        int hygiene=inside&&value.dirt()>=50?2:0;
        entity.life().applyEnvironment(wet,hygiene+(value==null&&vagrant()&&!entity.life().isSleeping()?1:0),0);
        seasonal.quarrelIfNeeded(value);
    }
    private boolean autumn() { return SettlementSeasons.is(entity,Season.AUTUMN); }
    private boolean ready() {
        return entity.isAlive()&&!entity.isNoAi()&&!entity.isPassenger()&&!entity.profile().houseBody()&&!entity.isPanicking()&&!entity.isCommandPlaying()&&!entity.operations().active()
                &&!entity.family().active()&&!entity.family().awaitingLanding()
                &&!entity.life().isSleeping()&&!entity.isInWaterOrBubble()&&!entity.isOnFire()&&entity.life().mealTicks()==0
                &&migrationAllowsWork()&&!entity.wintering().active()
                &&!entity.settlement().duties().active();
    }
    private boolean migrationAllowsWork() {
        boolean underway = Migration.underway(entity);
        if (underway && entity.life().nutrition() < 30)
            GameplayLog.limited(entity, "habitat", "migration.hungry", 1200,
                    "home work refused reason=migration_unsettled group={} nutrition={} carried={} home={} task={}",
                    entity.settlement().groupId(), entity.life().nutrition(), carrying.getCount(), home, task);
        return !underway;
    }
    /** Loose materials worth fetching from the ground: only what the shelter itself needs, never food (food is gathered from plants into the pile). */
    private boolean wantsMaterial(ShelterBlockEntity value,ItemStack stack) { return value.needsMaterial(stack); }
    /** Cut grass from a real plant: first for the lining, then as stock while the forage pile has room and the target is not met. */
    private boolean wantsHarvest(ShelterBlockEntity value) {
        var grass=new ItemStack(Items.SHORT_GRASS);
        if(value.needsMaterial(grass))return true;
        return entity.life().acceptsFood(grass)&&value.food()<AutumnStock.target(level(),value)&&value.foodSpace(grass)>0
                &&(!AutumnStock.autumn(level(),value.getBlockPos())||AutumnStock.fitCollector(entity))
                ||granaryWants(value,grass);
    }
    /** Autumn surplus goes to the community granary of a planned site once the own store has met its target. */
    private boolean granaryWants(ShelterBlockEntity value,ItemStack grass) {
        if(!autumn()||!entity.life().acceptsFood(grass)||value.food()<AutumnStock.target(level(),value)||!AutumnStock.fitCollector(entity))return false;
        var site=SiteLife.of(entity);
        return site!=null&&SiteLife.stock(level(),site)<GRANARY_LIMIT&&SiteLife.store(level(),site,entity.blockPosition(),pile->pile.foodSpace(grass)>0)!=null;
    }
    private static final int GRANARY_LIMIT=160;
    private boolean meal() { return task==Task.FOOD||task==Task.GRAIN_TAKE; }
    /** Own store empty and hungry: walk to the nearest granary pile that holds something this animal eats. */
    private boolean startGranaryTake() {
        var site=SiteLife.of(entity);
        if(site==null)return false;
        // Under the rules of a led community a granary meal comes out of the household's ration; without one it is theft.
        stealing=false;
        if(Rations.governs(entity)&&!Rations.allowance(entity)) {
            if(!Rations.wouldSteal(entity))return false;
            stealing=true;
        }
        if(!SearchBudget.take(level(),entity,Purpose.GRANARY))return false;
        var pile=SiteLife.store(level(),site,entity.blockPosition(),p->!p.peek(stack->entity.life().acceptsFood(stack)).isEmpty());
        if(pile==null||!entity.life().reachable(pile.standCell(),false))return false;
        granary=pile.getBlockPos();task=Task.GRAIN_TAKE;ticks=0;contact.reset();return true;
    }
    /** Package 12: one adult of the household walks to the common store for the household's share (Rations). */
    private boolean startRation(ShelterBlockEntity value) {
        if(!entity.family().adult()||entity.settlement().society().dejected()||!EventHooks.canEntityGrief(level(),entity)
                ||entity.life().nutrition()<40||entity.life().energy()<35)return false;
        Rations.fetchAbort(rationFetch);rationFetch=null;
        var fetch=Rations.fetchStart(entity,value);
        if(fetch==null)return false;
        rationFetch=fetch;var pile=fetch.pile();
        // The path finder only sees about sixteen blocks: a farther store is walked to in legs (approach) and checked on arrival.
        boolean near=entity.distanceToSqr(Vec3.atBottomCenterOf(pile.standCell()))<=12*12;
        if(near&&!entity.life().reachable(pile.standCell(),false)||entity.distanceToSqr(Vec3.atBottomCenterOf(pile.standCell()))>48*48){Rations.fetchAbort(rationFetch);rationFetch=null;return false;}
        granary=pile.getBlockPos();task=Task.RATION;ticks=0;contact.reset();return true;
    }
    private boolean startGranaryPut() {
        var site=SiteLife.of(entity);
        if(site==null||carrying.isEmpty())return false;
        var held=carrying;
        var pile=SiteLife.store(level(),site,entity.blockPosition(),p->p.foodSpace(held)>0);
        if(pile==null||!entity.life().reachable(pile.standCell(),false))return false;
        granary=pile.getBlockPos();task=Task.GRAIN_PUT;ticks=0;contact.reset();return true;
    }
    private ForagePileBlockEntity granaryPile() {
        return granary!=null&&level().hasChunkAt(granary)&&level().getBlockEntity(granary) instanceof ForagePileBlockEntity pile&&!pile.isRemoved()?pile:null;
    }
    /** Walks to the pile's feeding side and keeps the mouth on it; true once the contact has lasted long enough. */
    boolean mouth(ForagePileBlockEntity pile) {
        if(!contact.near(pile)){contact.reset();approach(pile.standCell());return false;}
        return contact.tick(pile);
    }
    private boolean outingMealAvailable() {
        var value=shelter();
        return entity.life().canEat()&&value!=null&&value.usable(entity)&&value.hasFoodFor(entity)
                &&!entity.wintering().usesWinterStores();
    }
    public boolean canUse() {
        if(!ready()){homes.cancelVagrantProject();return false;}
        // A higher-priority goal must reject maintenance before the selector interrupts construction.
        if(entity.independence().householdOuting()&&carrying.isEmpty()
                &&(!outingMealAvailable()||active()&&task!=Task.FOOD)){homes.cancelVagrantProject();return false;}
        if(active())return true;
        // The initiator may already occupy the new shelter; finish moving the original household
        // before ordinary work can start and interrupt its transient relocation cursor.
        if(homes.relocationPending()) {
            if(homes.continueRelocation()==Result.DEFERRED)return false;
        }
        if(wait>0)return false;wait=(int)((autumn()?30:60)/entity.species().traits().chore());
        var value=shelter();boolean housed=value!=null&&value.usable(entity);
        if(!housed) {
            if(hasNestProject())cancelNestProject();
            var project=homes.vagrantProject();
            if(project==Result.DEFERRED)return false;
            if(project==Result.FOUND)return true;
            return seasonal.start(null);
        }
        homes.cancelVagrantProject();
        if(entity.life().canEat()&&value.food()>0&&!entity.wintering().usesWinterStores()){task=Task.FOOD;targetBlock=value.bed(entity.getUUID());ticks=0;return true;}
        if(entity.life().canEat()&&value.food()==0&&entity.life().needsFood()&&!entity.independence().householdOuting()&&startGranaryTake())return true;
        if(carrying.isEmpty()&&!entity.independence().householdOuting()&&startRation(value))return true;
        if(carrying.isEmpty()&&!entity.independence().householdOuting()&&seasonal.start(value))return true;
        if(nestEligible(value)&&startNest(value))return true;
        if(homes.extensionWanted(value)&&homes.startExtension(value))return true;
        if(homes.relocationWanted(value)&&homes.relocate(value)==Result.DEFERRED)return false;
        if(hasNestProject()) {
            if(!nestProjectValid())cancelNestProject();
            return false;
        }
        // Young residents eat and observe the season, but do not maintain the household before their nest gate.
        if(!entity.family().adult()&&!entity.childhood().canLiveIndependently()) {
            if(!carrying.isEmpty()&&entity.life().dryStand(entity.blockPosition()))dropCarrying();
            return false;
        }
        if(seasonal.shareStart(value))return true;
        if(carrying.isEmpty()&&(entity.settlement().society().dejected()||!entity.settlement().duties().carrying().isEmpty()))return false;
        boolean mayWork=EventHooks.canEntityGrief(level(),entity)&&entity.life().nutrition()>=40&&entity.life().energy()>=35;
        if(carrying.isEmpty())moveLoad=false;
        if(!carrying.isEmpty()) {
            if(!mayWork&&!(moveLoad&&carrying.is(YukkuriEntity.FOOD))){if(entity.life().dryStand(entity.blockPosition()))dropCarrying();return false;}
            task=carrying.is(ModItems.WASTE.get())?Task.CLEAN:Task.DEPOSIT;ticks=0;return true;
        }
        if(!mayWork)return false;
        targetItem=value.wasteInside().stream().filter(item->!item.hasPickUpDelay()).findFirst().orElse(null);
        if(targetItem==null)targetItem=level().getEntitiesOfClass(ItemEntity.class,entity.getBoundingBox().inflate(10),item->item.isAlive()&&!item.hasPickUpDelay()
                &&wantsMaterial(value,item.getItem()))
                .stream().sorted(Comparator.<ItemEntity>comparingDouble(entity::distanceToSqr)).limit(3)
                .filter(item->entity.life().reachable(item.blockPosition(),false)).findFirst().orElse(null);
        if(targetItem!=null){task=Task.PICKUP;ticks=0;return true;}
        return planHarvest(value);
    }
    private static boolean nestBedding(ItemStack stack) {
        return stack.is(ItemTags.LEAVES)||stack.is(Items.SHORT_GRASS)||stack.is(Items.WHEAT);
    }
    /** A late child builds only at its actual inherited burrow bed, never by creating or destroying blocks. */
    private boolean nestEligible(ShelterBlockEntity value) {
        return value!=null&&value.kind()==ShelterBlock.Kind.BURROW&&value.owns(entity.getUUID())&&value.usable(entity)
                &&(entity.family().readyForOwnNest()||entity.family().adult()&&value.nestPortions(entity.getUUID())>0)&&!value.privateNest(entity.getUUID())
                &&!entity.life().needsFood()&&!entity.life().hasUrgentNeed()&&!entity.independence().householdOuting()
                &&(!hasNestProject()||sameNestHome(value));
    }
    private boolean startNest(ShelterBlockEntity value) {
        if(!nestEligible(value))return false;
        // A child that grew out of the family nest first takes a cell of its own.
        if(value.sharesBed(entity.getUUID())&&!value.separate(entity))return false;
        normalizeNestMaterials();
        if(!carrying.isEmpty()&&!nestBedding(carrying))return false;
        if(!nestMaterial.isEmpty()&&(!nestBedding(nestMaterial)||carrying.isEmpty()))return false;
        if(!carrying.isEmpty()&&nestHome==null)nestHome=GlobalPos.of(level().dimension(),value.getBlockPos());
        if(!carrying.isEmpty()||!nestMaterial.isEmpty()||value.nestPortions(entity.getUUID())>0) {
            task=Task.NEST_BUILD;targetBlock=value.bed(entity.getUUID());targetItem=null;ticks=buildTicks=0;return true;
        }
        return findNestBedding(value);
    }
    private void normalizeNestMaterials() {
        if(carrying.isEmpty()&&!nestMaterial.isEmpty()){carrying=nestMaterial;nestMaterial=ItemStack.EMPTY;}
        if(nestMaterial.isEmpty()&&carrying.getCount()==2)nestMaterial=carrying.split(1);
    }
    boolean hasNestProject() { return nestHome!=null; }
    private boolean sameNestHome(ShelterBlockEntity value) {
        return nestHome!=null&&nestHome.dimension().equals(entity.level().dimension())&&value!=null&&nestHome.pos().equals(value.getBlockPos());
    }
    private boolean nestProjectValid() {
        var value=shelter();
        return sameNestHome(value)&&value.owns(entity.getUUID())&&!value.privateNest(entity.getUUID());
    }
    /** Invalid projects release every real reserved portion at the current location, retrying after a failed spawn. */
    private void cancelNestProject() {
        if(!hasNestProject())return;
        dropCarrying();
        if(carrying.isEmpty()&&nestMaterial.isEmpty())nestHome=null;
    }
    private boolean nearNest(ShelterBlockEntity value,BlockPos pos) {
        return value.beds().stream().anyMatch(bed->bed.distSqr(pos)<=9);
    }
    /** Select one physical bedding portion close to this home; blocks and paper never count as nest material. */
    private boolean findNestBedding(ShelterBlockEntity value) {
        if(plants.find(value.bed(entity.getUUID()),4,pos->nearNest(value,pos))){task=Task.NEST_HARVEST;targetBlock=null;targetItem=null;ticks=0;return true;}
        targetItem=level().getEntitiesOfClass(ItemEntity.class,value.interior().inflate(3),item->item.isAlive()&&!item.hasPickUpDelay()
                &&nearNest(value,item.blockPosition())&&nestBedding(item.getItem())
                &&entity.life().reachable(item.blockPosition(),false)).stream().min(Comparator.comparingDouble(entity::distanceToSqr)).orElse(null);
        if(targetItem!=null){task=Task.NEST_PICKUP;targetBlock=null;ticks=0;return true;}
        return false;
    }
    private void takeNestMaterial(ShelterBlockEntity value,ItemStack material) {
        if(nestHome==null)nestHome=GlobalPos.of(level().dimension(),value.getBlockPos());
        if(carrying.isEmpty())carrying=material;
        else if(nestMaterial.isEmpty())nestMaterial=material;
    }
    private boolean planHarvest(ShelterBlockEntity value) {
        if(wantsHarvest(value)&&plants.find(entity.blockPosition(),8,pos->true)){
            targetBlock=null;targetItem=null;task=Task.HARVEST;ticks=0;return true;
        }
        return false;
    }
    public boolean canContinue() {
        if(!active()||!ready())return false;
        if(task==Task.DIG)return homes.canDigContinue();
        if(task==Task.ROAM)return homes.canRoamContinue();
        if(task==Task.SIESTA)return seasonal.canSiestaContinue();
        if(task==Task.STAY)return seasonal.canStayContinue();
        if(task==Task.NEST_PICKUP||task==Task.NEST_HARVEST||task==Task.NEST_BUILD)return ticks<600&&nestEligible(shelter())&&(task!=Task.NEST_HARVEST||EventHooks.canEntityGrief(level(),entity));
        return active()&&ready()&&ticks<400&&homeStand()!=null&&(meal()||EventHooks.canEntityGrief(level(),entity))
                &&(!entity.independence().householdOuting()||!carrying.isEmpty()||task==Task.FOOD&&outingMealAvailable())
                &&(meal()||!carrying.isEmpty()||!entity.settlement().society().dejected())
                &&(meal()||entity.life().nutrition()>=40&&entity.life().energy()>=35);
    }
    public void tickGoal() {
        if(!canContinue()){stop();return;}ticks++;
        if(task==Task.DIG){homes.tickDig();return;}
        if(task==Task.ROAM){homes.tickRoam();return;}
        var value=shelter();
        if(task==Task.STAY||task==Task.SIESTA){seasonal.tickRest(value);return;}
        if(task==Task.SHARE){seasonal.tickShare(value);return;}
        if(task==Task.RATION) {
            var pile=granaryPile();
            if(pile==null){stop();return;}
            if(!mouth(pile))return;
            var food=Rations.fetchTake(entity,value,pile,rationFetch);
            if(food.isEmpty()){stop();return;}
            rationFetch=null;carrying=food;rationLoad=true;task=Task.DEPOSIT;ticks=0;contact.reset();return;
        }
        if(task==Task.GRAIN_TAKE) {
            var pile=granaryPile();
            if(pile==null){stop();return;}
            if(!mouth(pile))return;
            var food=pile.take(stack->entity.life().acceptsFood(stack));
            if(!food.isEmpty()) {
                if(!entity.life().beginMeal(food,null,false))pile.returnFood(food);
                else if(stealing){entity.showReaction("happy","narrate_steal",60);CommunityRules.observed(entity,CommunityRules.Offence.THEFT,"granary");}
                else if(Rations.governs(entity))Rations.ateAllowance(entity);
            }
            stealing=false;stop();return;
        }
        if(task==Task.GRAIN_PUT) {
            var pile=granaryPile();
            if(pile==null||carrying.isEmpty()){task=Task.DEPOSIT;ticks=0;return;}
            if(!mouth(pile))return;
            int before=carrying.getCount();
            carrying=pile.returnFood(carrying);
            GameplayLog.event(entity,"habitat","granary deposit store={} moved={} carried={} stock={}",
                    pile.getBlockPos(),before-carrying.getCount(),carrying.getCount(),pile.food());
            if(!carrying.isEmpty()&&entity.life().dryStand(entity.blockPosition()))dropCarrying();
            stop();return;
        }
        if(task==Task.FOOD) {
            var pile=value.pile();
            if(pile==null){stop();return;}
            if(mouth(pile)) {
                // Put down the existing burden before taking a meal, so a rejected meal can
                // return its remainder without overwriting an unrelated carried resource.
                if(!carrying.isEmpty()) {
                    if(!nestMaterial.isEmpty())dropCarrying();
                    // A mouthful of several portions (a ration, a migrant's load) goes onto the pile as a stack.
                    else if(value.deposit(carrying)||carrying.is(YukkuriEntity.FOOD)&&(carrying=value.returnFood(carrying)).isEmpty())carrying=ItemStack.EMPTY;else dropCarrying();
                    if(!carrying.isEmpty()){stop();return;}
                }
                var food=value.takeFood(entity);
                if(!food.isEmpty()&&!entity.life().beginMeal(food,null,false))carrying=value.returnFood(food);
                stop();
            }
        } else if(task==Task.NEST_PICKUP) {
            if(targetItem==null||!targetItem.isAlive()||targetItem.hasPickUpDelay()||!nestEligible(value)
                    ||!nearNest(value,targetItem.blockPosition())||!nestBedding(targetItem.getItem())){stop();return;}
            if(approach(targetItem.blockPosition())&&entity.distanceToSqr(targetItem)<2.25) {
                var remaining=targetItem.getItem().copy();var gathered=remaining.split(1);
                if(remaining.isEmpty())targetItem.discard();else targetItem.setItem(remaining);
                takeNestMaterial(value,gathered);
                targetItem=null;ticks=0;
                task=Task.NEST_BUILD;targetBlock=value.bed(entity.getUUID());buildTicks=0;
            }
        } else if(task==Task.NEST_HARVEST) {
            var portion=plants.tick();
            if(!portion.isEmpty()){takeNestMaterial(value,portion);ticks=0;task=Task.NEST_BUILD;targetBlock=value.bed(entity.getUUID());}
            else if(!plants.active())stop();
        } else if(task==Task.NEST_BUILD) {
            if(targetBlock==null||!targetBlock.equals(value.bed(entity.getUUID()))||!nestEligible(value)){stop();return;}
            if(SurfaceApproach.walk(entity,Vec3.atBottomCenterOf(targetBlock),.65)) {
                int portions=value.nestPortions(entity.getUUID()),work=value.nestWork(entity.getUUID());
                if(work>=portions*60&&portions<2){
                    if(carrying.isEmpty()&&!nestMaterial.isEmpty()){carrying=nestMaterial;nestMaterial=ItemStack.EMPTY;}
                    if(!carrying.isEmpty()&&value.layNestPortion(entity,carrying)){
                        GameplayLog.event(entity,"habitat","nest material home={} item={} count={} portions={}",
                                value.getBlockPos(),carrying.getItem(),carrying.getCount(),value.nestPortions(entity.getUUID()));
                        carrying=ItemStack.EMPTY;
                    }
                    else {if(!findNestBedding(value))stop();return;}
                }
                SurfaceApproach.face(entity,(work/20%4)*90);
                if(value.workOnNest(entity)){
                    GameplayLog.event(entity,"habitat","nest complete home={} bed={} usedPortions={}",value.getBlockPos(),targetBlock,portions);
                    nestHome=null;entity.mind().felt(EmotionEvent.COMFORTED); entity.showReaction("happy","gentle",60);stop();
                }
            }
        } else if(task==Task.PICKUP) {
            if(targetItem==null||!targetItem.isAlive()||targetItem.hasPickUpDelay()
                    ||!(targetItem.getItem().is(ModItems.WASTE.get())&&value.contains(targetItem.blockPosition())
                    ||wantsMaterial(value,targetItem.getItem()))){stop();return;}
            if(approach(targetItem.blockPosition())&&entity.distanceToSqr(targetItem)<2.25) {
                carrying=targetItem.getItem().split(1);cheekFill();if(targetItem.getItem().isEmpty())targetItem.discard();targetItem=null;
                task=carrying.is(ModItems.WASTE.get())?Task.CLEAN:Task.DEPOSIT;ticks=0;
            }
        } else if(task==Task.HARVEST) {
            if(!wantsHarvest(value)){stop();return;}
            var portion=plants.tick();
            if(!portion.isEmpty()){carrying=portion;task=Task.DEPOSIT;ticks=0;}
            else if(!plants.active())stop();
        } else if(task==Task.DEPOSIT) {
            // Food goes onto the forage pile by mouth contact; lining and repair materials are still worked in at the bed.
            if(!value.needsMaterial(carrying)&&carrying.is(YukkuriEntity.FOOD)) {
                var pile=value.ensurePile();
                if(pile==null){if(entity.life().dryStand(entity.blockPosition()))dropCarrying();stop();return;}
                if(!rationLoad&&(value.foodSpace(carrying)==0||autumn()&&value.food()>=AutumnStock.target(level(),value))&&startGranaryPut())return;
                if(!mouth(pile))return;
                depositCarried(value);
            } else if(approach(value.bed(entity.getUUID()))) {
                depositCarried(value);
            }
        } else if(task==Task.CLEAN) {
            BlockPos outside=value.entrance().relative(value.facing(),2);
            if(!entity.life().dryStand(outside)){stop();return;}
            if(approach(outside)){dropCarrying();value.cleaned();entity.species().chore();stop();}
        }
    }
    boolean approach(BlockPos pos) {
        Vec3 goal=Vec3.atBottomCenterOf(pos);
        if(entity.distanceToSqr(goal)<.64){entity.getNavigation().stop();return true;}
        if(ticks%20==1) {
            if(entity.distanceToSqr(goal)>100)pos=BlockPos.containing(entity.position().add(goal.subtract(entity.position()).normalize().scale(7)));
            if(!entity.life().navigate(pos,1,false))stop();
        }
        return false;
    }
    public void stop() { if(entity.level() instanceof ServerLevel level)SearchBudget.cancel(level,entity,Purpose.GRANARY,0);Rations.fetchAbort(rationFetch);rationFetch=null;
            stealing=rationLoad=false;homes.stop(task);plants.stop();contact.reset();seasonal.resetLoad();task=Task.NONE;targetItem=null;targetBlock=null;
            seasonal.clearShareTarget();granary=null;wait=(int)((autumn()?40:80)/entity.species().traits().chore());ticks=buildTicks=0;entity.getNavigation().stop(); }
    /** Cheek pouches (Reimu only): after the first portion, take more of the same food lying within reach, up to the species' capacity. */
    private void cheekFill() {
        if(!carrying.is(YukkuriEntity.FOOD))return;
        while(carrying.getCount()<entity.species().cheekCapacity()) {
            var source=!targetItem.getItem().isEmpty()&&ItemStack.isSameItemSameComponents(targetItem.getItem(),carrying)?targetItem
                    :level().getEntitiesOfClass(ItemEntity.class,entity.getBoundingBox().inflate(2.5),it->it!=targetItem&&it.isAlive()&&!it.hasPickUpDelay()
                            &&ItemStack.isSameItemSameComponents(it.getItem(),carrying)).stream().findFirst().orElse(null);
            if(source==null)break;
            source.getItem().shrink(1);carrying.grow(1);
            if(source.getItem().isEmpty())source.discard();
        }
    }
    /** One portion at a time goes onto the pile (it only takes single portions); a full cheek is emptied by repeated mouth contact. */
    private void depositCarried(ShelterBlockEntity value) {
        if(!value.deposit(carrying.copyWithCount(1))) {
            GameplayLog.limited(entity,"habitat","deposit_refused",200,"deposit refused home={} item={} carried={}",value.getBlockPos(),carrying.getItem(),carrying.getCount());
            if(entity.life().dryStand(entity.blockPosition()))dropCarrying();
            stop();return;
        }
        var item=carrying.getItem();
        carrying.shrink(1);entity.species().chore();
        GameplayLog.event(entity,"habitat","deposit home={} item={} moved=1 carried={} stock={} migrant={} ration={}",
                value.getBlockPos(),item,carrying.getCount(),value.food(),moveLoad,rationLoad);
        if(!carrying.isEmpty()){contact.reset();ticks=0;return;}
        if(entity.getRandom().nextFloat()<.4f)entity.species().count(SpeechCount.STOCK,value.food());
        stop();
    }
    public void dropCarrying() {
        if(entity.level().isClientSide)return;
        if(!carrying.isEmpty())drop(carrying,true);
        if(!nestMaterial.isEmpty())drop(nestMaterial,false);
        if(carrying.isEmpty()&&!nestMaterial.isEmpty()){carrying=nestMaterial;nestMaterial=ItemStack.EMPTY;}
    }
    private void drop(ItemStack stack,boolean primary) {
        var drop=new ItemEntity(level(),entity.getX(),entity.getY(),entity.getZ(),stack.copy());drop.setPickUpDelay(100);
        if(level().addFreshEntity(drop)){
            GameplayLog.event(entity,"habitat","drop item={} count={} task={} drop={}",stack.getItem(),stack.getCount(),task,drop.getUUID());
            if(primary)carrying=ItemStack.EMPTY;else nestMaterial=ItemStack.EMPTY;
        } else GameplayLog.limited(entity,"habitat","drop_refused",200,"drop refused item={} count={} task={}",stack.getItem(),stack.getCount(),task);
    }
    YukkuriEntity entity() { return entity; }
    int ticks() { return ticks; }
    void resetTicks() { ticks=0; }
    void task(Task next) { task=next; }
    Task task() { return task; }
    BlockPos targetBlock() { return targetBlock; }
    void targetBlock(BlockPos next) { targetBlock=next; }
    void carry(ItemStack next) { carrying=next; }
    void resetContact() { contact.reset(); }
    int quarrelCooldown() { return seasonal.quarrelCooldown(); }
    void quarrelCooldown(int ticks) { seasonal.quarrelCooldown(ticks); }

    public CompoundTag save() {
        var tag=new CompoundTag();FamilyData.putPosition(tag,"Home",home);
        FamilyData.putPosition(tag,"NestHome",nestHome);
        if(!carrying.isEmpty())tag.put("Carrying",carrying.save(entity.registryAccess()));
        if(!nestMaterial.isEmpty())tag.put("NestMaterial",nestMaterial.save(entity.registryAccess()));return tag;
    }
    public void load(CompoundTag tag) {
        Rations.fetchAbort(rationFetch);rationFetch=null;
        homes.resetTransient();
        home=FamilyData.position(tag,"Home");nestHome=FamilyData.position(tag,"NestHome");carrying=tag.contains("Carrying")?ItemStack.parseOptional(entity.registryAccess(),
                tag.getCompound("Carrying")):ItemStack.EMPTY;
        nestMaterial=tag.contains("NestMaterial")?ItemStack.parseOptional(entity.registryAccess(),tag.getCompound("NestMaterial")):ItemStack.EMPTY;
        task=Task.NONE;targetItem=null;targetBlock=null;ticks=buildTicks=0;seasonal.resetLoad();
        wait=20+entity.getRandom().nextInt(100);
    }
}
