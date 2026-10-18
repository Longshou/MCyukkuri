package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.life.YukkuriConfig;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.registry.ModItems;
import io.github.longshou.mcyukkuri.season.AutumnStock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.EventHooks;

/** A real resident, never a hidden duplicate: carries one resource, maintains stores and clears waste. */
public final class HabitatLife {
    private enum Task { NONE, DIG, ROAM, FOOD, PICKUP, HARVEST, DEPOSIT, CLEAN, SHARE, STAY, SIESTA, NEST_PICKUP, NEST_HARVEST, NEST_BUILD, GRAIN_TAKE, GRAIN_PUT, RATION }
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
    private Task task=Task.NONE;
    private int wait,ticks,buildTicks;
    private final PlantBedding plants;
    /** Mouth contact with the forage pile, for stocking it and for taking portions out. */
    private final PileContact contact;
    private boolean shareLoaded;
    // Seasonal state is transient: it restarts with the entity and never changes the save format.
    private BlockPos shareTo;
    private int shareCooldown,quarrelCooldown;
    private long siestaDay=-1;
    private boolean arrived;
    /** The hollow being scraped (a new home or an extension of the own one), the next cell and the work done on it. Transient: the world keeps the dug cells. */
    private Burrowing.Plan dig;
    private int digIndex,digWork,extendWait,roamWait,searchWait,moveWait;
    /** The own cave has no soft wall left to dig out; a crowded household then looks for a bigger home. Transient. */
    private boolean noExtension;
    /** What the last attempt to move house found; for the check output. */
    private String moveNote="";
    private boolean extending;
    private BlockPos roamTarget;
    /** Where an interrupted hollow was started: the next attempt carries on there instead of opening a second one. */
    private BlockPos resumeMouth;
    private Direction resumeDirection;
    /** What this individual's address is. Derived from the world each time; the only stored fact is the shelter it belongs to. */
    public enum Residence { HOUSED, VAGRANT, DEPENDENT, ELSEWHERE }
    public HabitatLife(YukkuriEntity entity) {
        this.entity=entity;this.plants=new PlantBedding(entity);this.contact=new PileContact(entity);
        wait=20+entity.getRandom().nextInt(100); // a freshly spawned colony must not start every search on one tick
    }
    private ServerLevel level() { return (ServerLevel)entity.level(); }
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
        carrying=first;moveLoad=true;return first.getCount();
    }
    /** A hungry migrant eats one portion of the food it carries. */
    public boolean eatCarried() {
        if(carrying.isEmpty()||!carrying.is(YukkuriEntity.FOOD)||!entity.life().canEat()||!entity.life().acceptsFood(carrying)||entity.life().mealTicks()!=0)return false;
        var bite=carrying.copyWithCount(1);
        if(!entity.life().beginMeal(bite,null,false))return false;
        carrying.shrink(1);if(carrying.isEmpty()){carrying=ItemStack.EMPTY;moveLoad=false;}
        return true;
    }
    public boolean active() { return task!=Task.NONE; }
    public CompoundTag debug(){var tag=plants.debug();tag.putString("Task",task.name());tag.putBoolean("NoExtension",noExtension);tag.putInt("ExtendWait",extendWait);tag.putInt("MoveWait",moveWait);tag.putString("MoveNote",moveNote);tag.putString("Residence",residence().name());tag.putInt("Wait",wait);tag.putInt("Ticks",ticks);if(targetBlock!=null)tag.putString("Target",targetBlock.toShortString());return tag;}
    public String activity() { return task==Task.CLEAN?"shelter_clean":task==Task.NEST_BUILD?nestActivity():(task==Task.NEST_HARVEST||task==Task.HARVEST)&&plants.harvesting()?"nest_harvest":task==Task.NEST_PICKUP||task==Task.NEST_HARVEST?"nest_gather":task==Task.DEPOSIT||task==Task.SHARE||task==Task.GRAIN_PUT?"shelter_carry":task==Task.STAY||task==Task.SIESTA?"idle":task==Task.HARVEST||task==Task.PICKUP?"shelter_gather":"shelter_stock"; }
    private String nestActivity(){
        var value=shelter();if(value==null||targetBlock==null||!io.github.longshou.mcyukkuri.life.SurfaceApproach.arrived(entity,Vec3.atBottomCenterOf(targetBlock)))return "nest_carry";
        int phase=value.nestWork(entity.getUUID())%60;return phase<20?"nest_place":phase<40?"nest_spread":"nest_press";
    }
    public BlockPos homeStand() { var value=shelter();return value!=null&&value.usable(entity)?value.bed(entity.getUUID()):null; }
    /** Wild individuals live in recognised homes; pets, house yukkuri, predators, hat-boat families and raiding bands have other arrangements. */
    public boolean wildLiver() {
        var human=io.github.longshou.mcyukkuri.human.HumanCommerce.data(entity);
        return !entity.getVariant().isHouse()&&!entity.getVariant().isPredator()&&!io.github.longshou.mcyukkuri.subspecies.HatVessel.aquatic(entity)&&!roaming()
                &&!human.hasUUID("Owner")&&!human.contains("PendingRole")&&!human.contains("FactoryStage");
    }
    private boolean grownEnough() { return entity.family().adult()||entity.independence().independent()||entity.childhood().canLiveIndependently(); }
    /** HOUSED: belongs to a recognised home. VAGRANT: a grown wild individual with none (it looks for one or digs its own). DEPENDENT: a young one that follows its parents. ELSEWHERE: not a wild home-dweller. */
    public Residence residence() {
        if(!wildLiver())return Residence.ELSEWHERE;
        if(home!=null)return Residence.HOUSED;
        return grownEnough()?Residence.VAGRANT:Residence.DEPENDENT;
    }
    public boolean vagrant() { return residence()==Residence.VAGRANT; }
    /** Share of the hollow in progress that is already dug, 0 when none. */
    public int digProgress() { return dig==null||dig.cells().isEmpty()?0:digIndex*100/dig.cells().size(); }
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
        if(roaming()||entity.getVariant().isHouse()||entity.getVariant().isPredator()||!value.sound()||value.moisture()>=85||value.dirt()>=90||entity.distanceToSqr(Vec3.atBottomCenterOf(value.getBlockPos()))>32*32)return false;
        // The leaver never moves back into the home it said goodbye to.
        if(entity.independence().avoids(value.getBlockPos()))return false;
        if(hasNestProject()&&!sameNestHome(value)) { cancelNestProject();return false; }
        boolean owned=value.owns(entity.getUUID());
        if(!value.claim(entity))return false;
        BlockPos bed=value.bed(entity.getUUID());
        if(!entity.life().dryStand(bed)||!entity.life().reachable(bed,false)){if(!owned)value.release(entity.getUUID());return false;}
        var old=shelter();
        if(!entity.life().assignNaturalHome(bed)){if(!owned)value.release(entity.getUUID());return false;}
        if(old!=null&&old!=value)old.release(entity.getUUID());
        home=GlobalPos.of(entity.level().dimension(),value.getBlockPos());entity.setPersistenceRequired();
        entity.independence().settled(value);return true;
    }
    public boolean inherit(YukkuriEntity parent) { var value=parent.habitat().shelter();return value!=null&&choose(value); }
    public void release() { var value=shelter();if(value!=null)value.release(entity.getUUID());home=null;cancelNestProject();stop(); }
    /** Wandering bands never settle: no burrow is claimed or dug for them. */
    private boolean roaming() { var group=entity.settlement().group();return group!=null&&group.kind==SettlementData.Kind.BANDITS; }
    /** Lower is better: enough free cells for the household first, then a size close to what the household needs plus room to grow, then near. */
    private static double fit(int cells,int free,int need,double distanceSqr) {
        return Math.max(0,need+1-free)*40+Math.abs(cells-(need+3))*2+Math.sqrt(distanceSqr)/4;
    }
    /** Recognise a home: a registered one nearby (a roomy-enough one first), else an unregistered cave or hollow that qualifies (see {@link NaturalShelter#survey}). */
    public boolean findHome() {
        if(roaming()||entity.getVariant().isHouse()||entity.getVariant().isPredator()||unavailable())return false;
        // A community on a planned site moves into that site's own burrows first (spare ones included).
        var site=SiteLife.of(entity);
        if(site!=null&&SiteLife.settle(entity,this,site))return true;
        int need=householdNeed();
        var cores=new ArrayList<ShelterBlockEntity>();
        for(BlockPos pos:BlockPos.betweenClosed(entity.blockPosition().offset(-12,-2,-12),entity.blockPosition().offset(12,2,12)))
            if(level().hasChunkAt(pos)&&level().getBlockEntity(pos) instanceof ShelterBlockEntity value)cores.add(value);
        cores.sort(Comparator.comparingDouble(core->fit(core.capacity(),core.capacity()-core.usedSlots(),need,entity.distanceToSqr(Vec3.atBottomCenterOf(core.getBlockPos())))));
        for(var core:cores)if(!CommunityRules.barred(entity,core.getBlockPos())&&choose(core))return true;
        // A dependent child follows its parents' home; only a grown individual founds a home of its own.
        if(!grownEnough()||!EventHooks.canEntityGrief(level(),entity))return false;
        // Banished from this territory: it must not found a home inside it (package 12).
        if(CommunityRules.barred(entity,entity.blockPosition()))return false;
        record Candidate(ShelterPlacement.Plan plan,double score) {}
        var candidates=new ArrayList<Candidate>();
        for(BlockPos pos:BlockPos.betweenClosed(entity.blockPosition().offset(-8,-2,-8),entity.blockPosition().offset(8,2,8))) {
            if(!NaturalShelter.enclosed(level(),pos))continue;
            for(var direction:Direction.Plane.HORIZONTAL) {
                var plan=new ShelterPlacement.Plan(pos.immutable(),ShelterBlock.Kind.BURROW,direction);
                if(!ShelterPlacement.canPlace(level(),plan))continue;
                int cells=plan.beds(level()).size();
                candidates.add(new Candidate(plan,fit(cells,cells,need,entity.distanceToSqr(Vec3.atBottomCenterOf(pos)))));
            }
        }
        candidates.sort(Comparator.comparingDouble(Candidate::score));
        int tried=0;
        for(var candidate:candidates) {
            if(++tried>4)break;
            if(!entity.life().reachable(candidate.plan().center(),false))continue;
            var placed=ShelterPlacement.place(level(),candidate.plan());
            if(placed!=null){if(choose(placed.shelter()))return true;placed.rollback(level());}
        }
        return false;
    }
    public void tick() {
        if(entity.level().isClientSide||entity.isNoAi()||entity.getVariant().isHouse())return;
        if(hasNestProject()&&!nestProjectValid())cancelNestProject();
        if(wait>0)wait--;
        if(roamWait>0)roamWait--;
        if(searchWait>0)searchWait--;
        if(extendWait>0)extendWait--;
        if(moveWait>0)moveWait--;
        if(shareCooldown>0)shareCooldown--;
        if(quarrelCooldown>0)quarrelCooldown--;
        if(entity.tickCount%100!=0)return;
        var value=shelter();boolean inside=value!=null&&value.contains(entity.blockPosition());
        int wet=inside&&value.moisture()>=60?3:0;
        int hygiene=inside&&value.dirt()>=50?2:0;
        entity.life().applyEnvironment(wet,hygiene+(value==null&&vagrant()&&!entity.life().isSleeping()?1:0),0);
        if(value!=null&&quarrelCooldown==0&&Seasons.is(entity,Season.WINTER)&&value.food()==0&&entity.life().nutrition()<30)quarrel(value);
    }
    /** Winter famine: two starving housemates over empty stores snap at each other. Sacrifice for the family is not modelled yet (plan 2.4). */
    private void quarrel(ShelterBlockEntity value) {
        for(var id:value.residentIds()) {
            if(id.equals(entity.getUUID())||entity.getUUID().compareTo(id)>0)continue;
            if(!(level().getEntity(id) instanceof YukkuriEntity other)||!other.isAlive()||other.life().nutrition()>=30
                    ||other.habitat().quarrelCooldown>0||entity.distanceToSqr(other)>256)continue;
            var relations=YukkuriWorldData.get(level());
            int loss=-YukkuriConfig.WINTER_QUARREL_PENALTY.get();
            relations.relate(entity.getUUID(),other.getUUID(),loss);relations.relate(other.getUUID(),entity.getUUID(),loss);
            boolean weaker=entity.life().nutrition()<=other.life().nutrition();
            entity.showReaction(weaker?"teary":"angry","mine",80);other.showReaction(weaker?"angry":"teary","mine",80);
            quarrelCooldown=2400;other.habitat().quarrelCooldown=2400;return;
        }
    }
    private boolean autumn() { return Seasons.is(entity,Season.AUTUMN); }
    private boolean ready() {
        return entity.isAlive()&&!entity.isNoAi()&&!entity.isPassenger()&&!entity.getVariant().isHouse()&&!entity.isPanicking()&&!entity.isCommandPlaying()&&!entity.operations().active()
                &&!entity.family().active()&&!entity.family().awaitingLanding()
                &&!entity.life().isSleeping()&&!entity.isInWaterOrBubble()&&!entity.isOnFire()&&entity.life().mealTicks()==0
                &&!Migration.underway(entity)&&!entity.wintering().active()
                &&!entity.settlement().duties().active();
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
        if(!SearchBudget.take(level()))return false;
        var pile=SiteLife.store(level(),site,entity.blockPosition(),p->!p.peek(stack->entity.life().acceptsFood(stack)).isEmpty());
        if(pile==null||!entity.life().reachable(pile.standCell(),false))return false;
        granary=pile.getBlockPos();task=Task.GRAIN_TAKE;ticks=0;contact.reset();return true;
    }
    /** Package 12: one adult of the household walks to the common store for the household's share (Rations). */
    private boolean startRation(ShelterBlockEntity value) {
        if(!entity.family().adult()||entity.settlement().society().dejected()||!EventHooks.canEntityGrief(level(),entity)
                ||entity.life().nutrition()<40||entity.life().energy()<35)return false;
        var pile=Rations.fetchStart(entity,value);
        if(pile==null)return false;
        if(!entity.life().reachable(pile.standCell(),false)){Rations.fetchAbort(entity,value);return false;}
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
    private boolean mouth(ForagePileBlockEntity pile) {
        if(!contact.near(pile)){contact.reset();approach(pile.standCell());return false;}
        return contact.tick(pile);
    }
    private boolean outingMealAvailable() {
        var value=shelter();
        return entity.life().canEat()&&value!=null&&value.usable(entity)&&value.hasFoodFor(entity)
                &&!entity.wintering().usesWinterStores();
    }
    public boolean canUse() {
        if(!ready())return false;
        // A higher-priority goal must reject maintenance before the selector interrupts construction.
        if(entity.independence().householdOuting()&&carrying.isEmpty()
                &&(!outingMealAvailable()||active()&&task!=Task.FOOD))return false;
        if(active())return true;
        if(wait>0)return false;wait=(int)((autumn()?30:60)/entity.species().traits().chore());
        var value=shelter();boolean housed=value!=null&&value.usable(entity);
        if(!housed) {
            if(hasNestProject())cancelNestProject();
            if(vagrantProject())return true;
            return seasonal(null);
        }
        if(entity.life().canEat()&&value.food()>0&&!entity.wintering().usesWinterStores()){task=Task.FOOD;targetBlock=value.bed(entity.getUUID());ticks=0;return true;}
        if(entity.life().canEat()&&value.food()==0&&entity.life().needsFood()&&!entity.independence().householdOuting()&&startGranaryTake())return true;
        if(carrying.isEmpty()&&!entity.independence().householdOuting()&&startRation(value))return true;
        if(carrying.isEmpty()&&!entity.independence().householdOuting()&&seasonal(value))return true;
        if(nestEligible(value)&&startNest(value))return true;
        if(extensionWanted(value)&&startExtension(value))return true;
        if(relocationWanted(value))relocate(value);
        if(hasNestProject()) {
            if(!nestProjectValid())cancelNestProject();
            return false;
        }
        // Young residents eat and observe the season, but do not maintain the household before their nest gate.
        if(!entity.family().adult()&&!entity.childhood().canLiveIndependently()) {
            if(!carrying.isEmpty()&&entity.life().dryStand(entity.blockPosition()))dropCarrying();
            return false;
        }
        if(shareStart(value))return true;
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
    private boolean hasNestProject() { return nestHome!=null; }
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
    /** Winter closed nest and summer noon rest. Both only hold the animal in place; eating still comes first. */
    private boolean seasonal(ShelterBlockEntity value) {
        if(!Seasons.enabled()||entity.getVariant().isPredator()||entity.family().active()
                ||entity.independence().householdOuting()||entity.life().hasUrgentNeed())return false;
        if(value!=null&&!entity.wintering().usesWinterStores()&&Seasons.confined(entity)){task=Task.STAY;targetBlock=value.bed(entity.getUUID());ticks=0;arrived=false;return true;}
        long day=level().getDayTime()/24000;
        if(siestaDay==day||!Seasons.is(entity,Season.SUMMER)||!Seasons.noon(level())||level().isRaining()
                ||entity.life().nutrition()<30||entity.life().covered(entity.blockPosition()))return false;
        siestaDay=day;
        BlockPos shade=null;double best=Double.MAX_VALUE;
        for(var pos:BlockPos.betweenClosed(entity.blockPosition().offset(-6,-2,-6),entity.blockPosition().offset(6,2,6))) {
            double distance=pos.distSqr(entity.blockPosition());
            if(distance<best&&level().hasChunkAt(pos)&&entity.life().covered(pos)&&ShelterBlock.shelter(level(),pos)==null
                    &&entity.life().dryStand(pos)&&entity.life().reachable(pos,false)){shade=pos.immutable();best=distance;}
        }
        if(shade==null&&value!=null){BlockPos bed=value.bed(entity.getUUID());if(bed!=null&&entity.distanceToSqr(Vec3.atBottomCenterOf(bed))<=24*24)shade=bed;}
        if(shade==null)return false;
        task=Task.SIESTA;targetBlock=shade;ticks=0;arrived=false;return true;
    }
    /** At a gathering, a household with plenty hands one real portion to the poorest other household of the group. */
    private boolean shareStart(ShelterBlockEntity value) {
        var group=entity.settlement().group();
        if(group==null||shareCooldown>0||!carrying.isEmpty()||entity.independence().householdOuting()
                ||entity.settlement().society().dejected()||!entity.settlement().duties().carrying().isEmpty()
                ||!EventHooks.canEntityGrief(level(),entity)||entity.life().energy()<35||entity.life().nutrition()<40||Migration.moving(group)||!Seasons.assembling(level(),group)
                ||value.food()<=value.occupants()*YukkuriConfig.SHARE_SURPLUS_PER_RESIDENT.get())return false;
        if(value.pile()==null)return false;
        ShelterBlockEntity best=null;
        var members=level().getEntitiesOfClass(YukkuriEntity.class,new AABB(group.center).inflate(40),
                other->other!=entity&&other.isAlive()&&group.id.equals(other.settlement().groupId()));
        for(var other:members) {
            var shelter=other.habitat().shelter();
            if(shelter==null||shelter.getBlockPos().equals(value.getBlockPos())||shelter.food()>=shelter.occupants()*YukkuriConfig.SHARE_NEED_PER_RESIDENT.get()
                    ||shelter.food()>=shelter.foodCapacity())continue;
            if(best==null||shelter.food()<best.food())best=shelter;
        }
        var bestPile=best==null?null:best.pile();
        if(bestPile==null||!value.hasFoodFor(entity)||!entity.life().reachable(bestPile.standCell(),false))return false;
        // Nothing is taken yet: the portion leaves the own pile by real mouth contact once the errand is under way.
        shareTo=best.getBlockPos();shareLoaded=false;contact.reset();task=Task.SHARE;ticks=0;shareCooldown=YukkuriConfig.SHARE_COOLDOWN.get();return true;
    }
    public boolean canContinue() {
        if(!active()||!ready())return false;
        if(task==Task.DIG)return dig!=null&&ticks<900&&EventHooks.canEntityGrief(level(),entity)&&!entity.life().hasUrgentNeed()&&entity.life().nutrition()>=25;
        if(task==Task.ROAM)return roamTarget!=null&&ticks<700&&!entity.life().hasUrgentNeed();
        if(task==Task.SIESTA)return ticks<1200&&targetBlock!=null&&Seasons.is(entity,Season.SUMMER)&&Seasons.noon(level())&&!entity.life().hasUrgentNeed();
        if(task==Task.STAY)return homeStand()!=null&&Seasons.confined(entity)&&!entity.wintering().usesWinterStores()&&!entity.life().hasUrgentNeed();
        if(task==Task.NEST_PICKUP||task==Task.NEST_HARVEST||task==Task.NEST_BUILD)return ticks<600&&nestEligible(shelter())&&(task!=Task.NEST_HARVEST||EventHooks.canEntityGrief(level(),entity));
        return active()&&ready()&&ticks<400&&homeStand()!=null&&(meal()||EventHooks.canEntityGrief(level(),entity))
                &&(!entity.independence().householdOuting()||!carrying.isEmpty()||task==Task.FOOD&&outingMealAvailable())
                &&(meal()||!carrying.isEmpty()||!entity.settlement().society().dejected())
                &&(meal()||entity.life().nutrition()>=40&&entity.life().energy()>=35);
    }
    public void tickGoal() {
        if(!canContinue()){stop();return;}ticks++;
        if(task==Task.DIG){tickDig();return;}
        if(task==Task.ROAM){tickRoam();return;}
        var value=shelter();
        if(task==Task.STAY||task==Task.SIESTA) {
            if(task==Task.STAY&&value!=null&&entity.life().canEat()&&value.food()>0){task=Task.FOOD;targetBlock=value.bed(entity.getUUID());ticks=0;return;}
            if(approach(targetBlock)) {
                entity.getNavigation().stop();
                if(!arrived){arrived=true;if(task==Task.SIESTA)entity.showReaction("tired","puff",80);}
            }
            return;
        }
        if(task==Task.SHARE) {
            if(!shareLoaded) {
                var own=value==null?null:value.pile();
                if(own==null||value.food()<=value.occupants()*YukkuriConfig.SHARE_SURPLUS_PER_RESIDENT.get()){stop();return;}
                if(!mouth(own))return;
                var food=value.takeFood(entity);
                if(food.isEmpty()){stop();return;}
                carrying=food;shareLoaded=true;contact.reset();ticks=0;return;
            }
            var other=shareTo==null?null:ShelterBlock.shelter(level(),shareTo);
            var pile=other==null?null:other.ensurePile();
            if(pile==null){task=Task.DEPOSIT;ticks=0;return;}
            if(!mouth(pile))return;
            if(other.deposit(carrying)){carrying=ItemStack.EMPTY;entity.showReaction("happy","share",80);stop();}
            else{task=Task.DEPOSIT;ticks=0;}
            return;
        }
        if(task==Task.RATION) {
            var pile=granaryPile();
            if(pile==null){stop();return;}
            if(!mouth(pile))return;
            var food=Rations.fetchTake(entity,value,pile);
            if(food.isEmpty()){stop();return;}
            carrying=food;rationLoad=true;task=Task.DEPOSIT;ticks=0;contact.reset();return;
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
            carrying=pile.returnFood(carrying);
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
            if(io.github.longshou.mcyukkuri.life.SurfaceApproach.walk(entity,Vec3.atBottomCenterOf(targetBlock),.65)) {
                int portions=value.nestPortions(entity.getUUID()),work=value.nestWork(entity.getUUID());
                if(work>=portions*60&&portions<2){
                    if(carrying.isEmpty()&&!nestMaterial.isEmpty()){carrying=nestMaterial;nestMaterial=ItemStack.EMPTY;}
                    if(!carrying.isEmpty()&&value.layNestPortion(entity,carrying)){carrying=ItemStack.EMPTY;}
                    else {if(!findNestBedding(value))stop();return;}
                }
                io.github.longshou.mcyukkuri.life.SurfaceApproach.face(entity,(work/20%4)*90);
                if(value.workOnNest(entity)){nestHome=null;entity.showReaction("happy","gentle",60);stop();}
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
    // ---- vagrants: look for a home, scrape one into soft ground, or move on
    /** A grown individual without a home: it recognises one in reach, otherwise digs a hollow into a soil face nearby, otherwise wanders on. */
    private boolean vagrantProject() {
        if(!vagrant()||!EventHooks.canEntityGrief(level(),entity))return false;
        if(entity.life().nutrition()<35||entity.life().energy()<30||level().isNight()||searchWait>0)return false;
        searchWait=100;
        // Banished from this territory: move on instead of settling in it (package 12).
        if(CommunityRules.barred(entity,entity.blockPosition()))return roamWait<=0&&startRoam();
        if(findHome())return false;
        if(startDig())return true;
        return roamWait<=0&&startRoam();
    }
    /** Cells the household wants in a new hollow: a nest and a store for the first, two more for everyone else. */
    private int wantedCells() { return Math.min(NaturalShelter.MAX_CELLS-2,4+2*householdNeed()); }
    private boolean startDig() {
        if(!SearchBudget.take(level()))return false;
        int want=wantedCells();
        Burrowing.Plan best=null;double bestScore=-1;
        if(resumeMouth!=null) {
            var again=Burrowing.plan(level(),resumeMouth,resumeDirection,want);
            if(again!=null&&entity.life().reachable(resumeMouth,false)){dig=again;extending=false;digIndex=0;digWork=0;task=Task.DIG;ticks=0;return true;}
            resumeMouth=null;resumeDirection=null;
        }
        var faces=new ArrayList<>(Burrowing.faces(level(),entity.blockPosition(),9));
        faces.sort(Comparator.comparingDouble(face->face.distSqr(entity.blockPosition())));
        for(var face:faces.subList(0,Math.min(faces.size(),40))) {
            if(!entity.life().reachable(face,false))continue;
            for(var direction:Direction.Plane.HORIZONTAL) {
                if(!Burrowing.soft(level(),face.relative(direction)))continue;
                var plan=Burrowing.plan(level(),face,direction,want);
                if(plan==null)continue;
                double score=plan.cells().size()*6-Math.sqrt(face.distSqr(entity.blockPosition()));
                if(score>bestScore){best=plan;bestScore=score;}
            }
        }
        if(best==null)return false;
        resumeMouth=best.mouth();resumeDirection=best.dig();
        dig=best;extending=false;digIndex=0;digWork=0;task=Task.DIG;ticks=0;return true;
    }
    private boolean extensionWanted(ShelterBlockEntity value) {
        if(extendWait>0)return false;
        return entity.family().adult()&&value.owns(entity.getUUID())&&value.usable(entity)&&value.crowded()&&!level().isNight()
                &&EventHooks.canEntityGrief(level(),entity)&&entity.life().nutrition()>=50&&entity.life().energy()>=40&&!entity.life().needsFood()
                &&!entity.independence().householdOuting()&&carrying.isEmpty()&&!hasNestProject();
    }
    /** The family has outgrown the cave: scrape one more cell out of the wall, away from the doorway. */
    private boolean startExtension(ShelterBlockEntity value) {
        extendWait=2400;
        var plan=Burrowing.extension(level(),value);
        noExtension=plan==null;
        if(plan==null)return false;
        dig=plan;extending=true;digIndex=0;digWork=0;task=Task.DIG;ticks=0;return true;
    }
    // ---- moving the household to a bigger home
    private boolean relocationWanted(ShelterBlockEntity value) {
        if(moveWait>0)return false;
        return noExtension&&entity.family().adult()&&value.owns(entity.getUUID())&&value.crowded()&&!level().isNight()
                &&EventHooks.canEntityGrief(level(),entity)&&carrying.isEmpty()&&!hasNestProject();
    }
    /** A recognised, empty, roomier home within reach: a registered one, or a cave that qualifies and is registered now. */
    private ShelterBlockEntity biggerHome(int minSleeping,ShelterBlockEntity old) {
        ShelterBlockEntity best=null;
        for(BlockPos pos:BlockPos.betweenClosed(entity.blockPosition().offset(-24,-2,-24),entity.blockPosition().offset(24,2,24))) {
            if(!level().hasChunkAt(pos)||!(level().getBlockEntity(pos) instanceof ShelterBlockEntity core)||core==old||!core.residentIds().isEmpty()
                    ||core.kind()!=ShelterBlock.Kind.BURROW||!core.sound()||core.capacity()<minSleeping)continue;
            if(best==null||core.capacity()<best.capacity())best=core;
        }
        if(best!=null)return best;
        if(!SearchBudget.take(level()))return null;
        ShelterPlacement.Plan plan=null;int cells=Integer.MAX_VALUE;int seen=0,fitting=0;
        for(BlockPos pos:BlockPos.betweenClosed(entity.blockPosition().offset(-30,-2,-30),entity.blockPosition().offset(30,2,30))) {
            if(!NaturalShelter.enclosed(level(),pos))continue;
            for(var direction:Direction.Plane.HORIZONTAL) {
                var candidate=new ShelterPlacement.Plan(pos.immutable(),ShelterBlock.Kind.BURROW,direction);
                if(!ShelterPlacement.canPlace(level(),candidate))continue;
                int size=candidate.beds(level()).size();seen++;
                if(size>=minSleeping)fitting++;
                if(size>=minSleeping&&size<cells&&entity.life().reachable(pos,false)){plan=candidate;cells=size;}
            }
        }
        moveNote="caves="+seen+" big_enough="+fitting+" min="+minSleeping+(plan==null?" none_reachable":" chosen");
        if(plan==null)return null;
        var placed=ShelterPlacement.place(level(),plan);
        return placed==null?null:placed.shelter();
    }
    /** The whole household (everyone of the old home who is near) walks into the bigger home: adults first, the young into a parent's cell. */
    private void relocate(ShelterBlockEntity old) {
        moveWait=6000;
        int need=householdNeed();
        var target=biggerHome(Math.max(old.capacity()+2,need+3),old);
        if(target==null){moveWait=1200;return;}
        var movers=new ArrayList<YukkuriEntity>();
        for(var id:old.residentIds())
            if(level().getEntity(id) instanceof YukkuriEntity y&&y.isAlive()&&y.distanceToSqr(entity)<=48*48)movers.add(y);
        movers.sort(Comparator.comparingInt(y->y.family().adult()?0:1));
        int moved=0;
        for(var mover:movers) {
            mover.life().trustRoutes(true);
            try{if(mover.habitat().choose(target))moved++;}finally{mover.life().trustRoutes(false);}
        }
        moveNote+=" movers="+movers.size()+" moved="+moved;
        if(moved==0&&target.residentIds().isEmpty())target.dismantle();
    }
    private void tickDig() {
        while(digIndex<dig.cells().size()&&Burrowing.open(level(),dig.cells().get(digIndex)))digIndex++;
        if(digIndex>=dig.cells().size()){finishDig();return;}
        var cell=dig.cells().get(digIndex);
        if(!Burrowing.soft(level(),cell)){stop();return;}
        var from=dig.reach(cell);
        if(!approach(from))return;
        var state=level().getBlockState(cell);
        io.github.longshou.mcyukkuri.life.SurfaceApproach.face(entity,io.github.longshou.mcyukkuri.life.SurfaceApproach.yaw(entity.position(),Vec3.atCenterOf(cell)));
        digWork++;
        if(digWork%12==0) {
            level().sendParticles(new net.minecraft.core.particles.BlockParticleOption(net.minecraft.core.particles.ParticleTypes.BLOCK,state),
                    cell.getX()+.5,cell.getY()+.3,cell.getZ()+.5,6,.25,.15,.25,.02);
            level().playSound(null,cell,state.getSoundType().getHitSound(),net.minecraft.sounds.SoundSource.BLOCKS,.6f,.8f);
        }
        if(digWork<Burrowing.digTicks(state))return;
        level().destroyBlock(cell,false,entity);
        entity.life().spendEnergy(1.2f);entity.life().spendNutrition(.35f);
        digIndex++;digWork=0;ticks=0;
    }
    /** All planned cells are open: the hollow is recognised as a home (or the cave's survey is refreshed after an extension). */
    private void finishDig() {
        var plan=dig;boolean wasExtending=extending;
        dig=null;resumeMouth=null;resumeDirection=null;
        // The hollow may have been recognised as a home while the last cells were still being dug: bring its layout up to date.
        if(!wasExtending&&level().getBlockEntity(plan.doorway()) instanceof ShelterBlockEntity early&&!early.isRemoved()) {
            early.refreshSite(NaturalShelter.survey(level(),early.getBlockPos(),early.facing(),early.pileCell()));
            if(!early.owns(entity.getUUID()))choose(early);
            entity.showReaction("happy","gentle",80);stop();return;
        }
        if(wasExtending) {
            var value=shelter();
            if(value!=null)value.refreshSite(NaturalShelter.survey(level(),value.getBlockPos(),value.facing(),value.pileCell()));
            entity.showReaction("happy","gentle",60);stop();return;
        }
        var placement=new ShelterPlacement.Plan(plan.doorway(),ShelterBlock.Kind.BURROW,plan.facing());
        if(ShelterPlacement.canPlace(level(),placement)) {
            var placed=ShelterPlacement.place(level(),placement);
            if(placed!=null) {
                if(choose(placed.shelter()))entity.showReaction("happy","gentle",80);else placed.rollback(level());
            }
        }
        stop();
    }
    /** No soil face to dig into here: walk on a good way and look again. */
    private boolean startRoam() {
        roamWait=300;
        for(int attempt=0;attempt<8;attempt++) {
            int dx=entity.getRandom().nextInt(41)-20,dz=entity.getRandom().nextInt(41)-20;
            if(dx*dx+dz*dz<100)continue;
            var ground=level().getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,entity.blockPosition().offset(dx,0,dz));
            if(!level().hasChunkAt(ground)||!entity.life().dryStand(ground)||!entity.life().reachable(ground,false))continue;
            roamTarget=ground;task=Task.ROAM;ticks=0;return true;
        }
        return false;
    }
    private void tickRoam() {
        if(roamTarget==null){stop();return;}
        if(approach(roamTarget))stop();
    }
    private boolean approach(BlockPos pos) {
        Vec3 goal=Vec3.atBottomCenterOf(pos);
        if(entity.distanceToSqr(goal)<.64){entity.getNavigation().stop();return true;}
        if(ticks%20==1) {
            if(entity.distanceToSqr(goal)>100)pos=BlockPos.containing(entity.position().add(goal.subtract(entity.position()).normalize().scale(7)));
            if(!entity.life().navigate(pos,1,false))stop();
        }
        return false;
    }
    public void stop() { if(task==Task.RATION)Rations.fetchAbort(entity,shelter());stealing=rationLoad=false;if(task==Task.DIG){dig=null;digWork=0;}roamTarget=null;plants.stop();contact.reset();shareLoaded=false;task=Task.NONE;targetItem=null;targetBlock=null;shareTo=null;granary=null;wait=(int)((autumn()?40:80)/entity.species().traits().chore());ticks=buildTicks=0;entity.getNavigation().stop(); }
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
            if(entity.life().dryStand(entity.blockPosition()))dropCarrying();
            stop();return;
        }
        carrying.shrink(1);entity.species().chore();
        if(!carrying.isEmpty()){contact.reset();ticks=0;return;}
        if(entity.getRandom().nextFloat()<.4f)entity.species().count(io.github.longshou.mcyukkuri.dialogue.SpeechCount.STOCK,value.food());
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
        if(level().addFreshEntity(drop)){if(primary)carrying=ItemStack.EMPTY;else nestMaterial=ItemStack.EMPTY;}
    }
    public CompoundTag save() {
        var tag=new CompoundTag();FamilyData.putPosition(tag,"Home",home);
        FamilyData.putPosition(tag,"NestHome",nestHome);
        if(!carrying.isEmpty())tag.put("Carrying",carrying.save(entity.registryAccess()));
        if(!nestMaterial.isEmpty())tag.put("NestMaterial",nestMaterial.save(entity.registryAccess()));return tag;
    }
    public void load(CompoundTag tag) {
        home=FamilyData.position(tag,"Home");nestHome=FamilyData.position(tag,"NestHome");carrying=tag.contains("Carrying")?ItemStack.parseOptional(entity.registryAccess(),tag.getCompound("Carrying")):ItemStack.EMPTY;
        nestMaterial=tag.contains("NestMaterial")?ItemStack.parseOptional(entity.registryAccess(),tag.getCompound("NestMaterial")):ItemStack.EMPTY;
        task=Task.NONE;targetItem=null;targetBlock=null;ticks=buildTicks=0;shareLoaded=false;
        wait=20+entity.getRandom().nextInt(100);
    }
}
