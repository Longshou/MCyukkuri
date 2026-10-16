package io.github.longshou.mcyukkuri.p0;

import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.entity.*;
import io.github.longshou.mcyukkuri.family.*;
import io.github.longshou.mcyukkuri.human.*;
import io.github.longshou.mcyukkuri.item.CarrierItem;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.registry.ModItems;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@PrefixGameTestTemplate(false)
public final class HumanExpansionGameTests {
    private record Check(String name,boolean pass){}
    private final List<Check> checks=new ArrayList<>();
    private UUID pipelineChild,pipelineWorker;
    private final Set<String> seenStages=new HashSet<>();
    private boolean carried;
    private static final AABB AREA=new AABB(1190,62,-2,1390,84,60);
    private void check(String name,boolean pass){checks.add(new Check(name,pass));}
    @GameTest(templateNamespace=P0Mod.ID,template="empty",timeoutTicks=3400,batch="human_expansion")
    public static void productionAndBadges(GameTestHelper h){
        var t=new HumanExpansionGameTests();var level=h.getLevel();
        for(int x=74;x<=86;x++)for(int z=-1;z<=3;z++)GameTestChunks.setForced(level,x,z,true);
        h.onEachTick(()->{
            if(t.pipelineChild!=null&&level.getEntity(t.pipelineChild) instanceof YukkuriEntity y){t.seenStages.add(FactoryPipeline.stage(y));t.carried|=y.getVehicle() instanceof FactoryTray;
                if(level.getGameTime()%200==0){P0Control.write("factory-pipeline-progress",Map.of("child",y.position().toString(),"stage",FactoryPipeline.stage(y),"passenger",y.isPassenger(),"tag",HumanCommerce.data(y).toString(),"worker",level.getEntity(t.pipelineWorker).position().toString(),"trays",level.getEntitiesOfClass(FactoryTray.class,AREA).stream().map(c->Map.of("pos",c.position().toString(),"blocked",c.blocked(),"path",c.getNavigation().isDone())).toList()));
                }
            }
        });
        h.startSequence().thenWaitUntil(()->{
            for(int x=74;x<=86;x++)for(int z=-1;z<=3;z++)h.assertTrue(level.isPositionEntityTicking(new BlockPos(x*16+8,67,z*16+8)),"Waiting for factory chunks");
        }).thenExecute(()->{
            level.setDayTime(6000);level.getEntitiesOfClass(Entity.class,AREA,e->!(e instanceof net.minecraft.world.entity.player.Player)).forEach(Entity::discard);
            String[] kinds={"labor","manju","bag"};
            for(int i=0;i<3;i++){
                var template=level.getStructureManager().get(MCYukkuri.id("yukkuri_"+kinds[i]+"_factory")).orElseThrow();var pos=new BlockPos(1200+60*i,64,0);
                // Reusing a test world must not retain nest residents from discarded entities.
                for(var old:BlockPos.betweenClosed(pos,pos.offset(40,14,34)))if(level.getBlockEntity(old)!=null)
                    level.setBlock(old,Blocks.AIR.defaultBlockState(),2);
                t.check(kinds[i]+" has authored footprint",template.getSize().equals(new BlockPos(41,15,35)));
                t.check(kinds[i]+" places with residents",template.placeInWorld(level,pos,pos,new StructurePlaceSettings().setIgnoreEntities(false).setFinalizeEntities(true),level.random,2));
            }
            level.getEntitiesOfClass(HumanNpc.class,AREA).forEach(n->{n.aiStep();n.setNoAi(true);});level.getEntitiesOfClass(YukkuriEntity.class,AREA).forEach(n->{n.setOnGround(true);n.setNoAi(true);});
        }).thenWaitUntil(()->h.assertTrue(level.getEntitiesOfClass(YukkuriEntity.class,AREA).stream().noneMatch(y->HumanCommerce.data(y).contains("PendingRole")),"Waiting for custody"))
        .thenExecute(()->{
            try{t.run(level);t.beginPipeline(level);}catch(Throwable e){t.check("Unexpected exception: "+e,false);}
        }).thenIdle(2800).thenExecute(()->{
            if(t.pipelineChild!=null){
                var n=level.getEntity(t.pipelineWorker);var product=n instanceof HumanNpc npc?npc.production().product():ItemStack.EMPTY;
                var token=CarrierItem.token(product);var tag=token==null?new CompoundTag():YukkuriWorldData.get(level).captiveSnapshot(token);
                t.check("Physical tray carries a nursery child and packages that UUID",tag.hasUUID("UUID")&&tag.getUUID("UUID").equals(t.pipelineChild));
                t.check("Live production visits receiving, nursery, grading, washing and packing",t.carried&&t.seenStages.containsAll(Set.of("receive","nursery_transfer","grade","wash","pack")));
            }
            level.getEntitiesOfClass(Entity.class,AREA,e->!(e instanceof net.minecraft.world.entity.player.Player)).forEach(Entity::discard);
            for(int x=74;x<=86;x++)for(int z=-1;z<=3;z++)GameTestChunks.setForced(level,x,z,false);
            P0Control.write("human-expansion-checks",t.checks);var failed=t.checks.stream().filter(c->!c.pass).toList();if(failed.isEmpty())h.succeed();else h.fail(failed.toString());
        });
    }
    private HumanNpc worker(ServerLevel l,String kind){return l.getEntitiesOfClass(HumanNpc.class,AREA,n->n.siteRole().equals(kind+"_worker")).getFirst();}
    private void beginPipeline(ServerLevel l){
        var n=worker(l,"bag");for(var other:n.animals("input")){HumanCommerce.assign(other,n.getUUID(),"reserve");other.moveTo(1347.5,67,15.5);}
        var parents=n.animals("breeder").stream().filter(y->HumanCommerce.data(y).getInt("FactoryGroup")==0).toList();var a=parents.get(0);var b=parents.get(1);
        a.moveTo(1326.5,67,15.5);b.moveTo(1328.5,67,15.5);
        for(var y:parents){var life=y.life().save();life.putFloat("Nutrition",95);life.putFloat("Energy",95);life.putFloat("Hygiene",100);life.putFloat("Bladder",0);y.life().load(life);y.setHealth(y.getMaxHealth());
            var age=y.family().save();age.putBoolean("Initialized",true);age.putLong("Age",24001);age.putLong("Lifespan",72000);age.putLong("Nourishment",24000);age.putString("Stage","ADULT");age.putInt("Recovery",0);y.family().load(age);}
        var data=FamilyData.get(l);var stocked=n.production().save();var depleted=stocked.copy();depleted.putInt("Material",0);n.production().load(depleted);
        check("Authoritative family entry refuses a factory with no materials",data.conceive(a,b,FamilyData.BirthKind.STEM,GlobalPos.of(l.dimension(),a.life().homeStand()))==null&&a.family().conceptions()==0);
        n.production().load(stocked);
        check("Nursery reservation cannot exceed its workshop capacity",!FactoryProduction.reproductionAllowed(a,b,9));
        var brood=data.conceive(a,b,FamilyData.BirthKind.STEM,GlobalPos.of(l.dimension(),a.life().homeStand()));
        check("Workshop parent pair uses the authoritative conception path",brood!=null);if(brood==null){P0Control.write("human-expansion-parent-debug",Map.of("a",a.life().save().toString(),"b",b.life().save().toString(),"aReady",a.family().ready(),"bReady",b.family().ready(),"pair",a.family().canReproduceWith(b),"budget",FactoryProduction.reproductionAllowed(a,b,5)));return;}
        check("Conception charges both parent service counts and starts recovery",parents.stream().allMatch(y->HumanCommerce.data(y).getInt("FactoryCycles")==1&&HumanCommerce.data(y).getLong("FactoryRestUntil")>l.getGameTime()));
        check("Different parent groups cannot be crossed",!n.production().breeding().allows(a,n.animals("breeder").stream().filter(y->HumanCommerce.data(y).getInt("FactoryGroup")==1).findFirst().orElseThrow(),1));
        brood.elapsed=brood.duration;
        var child=data.birth(brood,0,l,new Vec3(1328.5,67,22.5));
        check("Actual brood creates a child with parent identities",child!=null&&a.getUUID().equals(child.family().mother())&&b.getUUID().equals(child.family().father()));if(child==null)return;
        check("Successful birth immediately inherits factory custody",HumanCommerce.ownedBy(child,n.getUUID())&&HumanCommerce.data(child).getString("Use").equals("nursery"));
        child.setNoAi(true);HumanCommerce.assign(child,n.getUUID(),"nursery");
        check("Stem newborn is not immediately ready for processing",!FactoryProduction.nurseryReady(child));
        var grown=child.family().save();grown.putLong("Age",child.family().maturityAge()/6+1);grown.putLong("Nourishment",child.family().maturityAge()/6+1);grown.putBoolean("AwaitingLanding",false);child.family().load(grown);
        var healthy=child.life().save();healthy.putFloat("Nutrition",95);healthy.putFloat("Energy",95);child.life().load(healthy);
        check("Nursery maturity requires real age and nourishment",FactoryProduction.nurseryReady(child));
        child.setOnGround(true);
        for(var parent:n.animals("breeder")){var state=parent.family().save();state.putInt("Recovery",10000);parent.family().load(state);}
        pipelineChild=child.getUUID();pipelineWorker=n.getUUID();child.setNoAi(false);n.setNoAi(false);n.production().pipeline().tray().setNoAi(false);
    }
    private void run(ServerLevel l){
        var labor=worker(l,"labor");var manju=worker(l,"manju");var bag=worker(l,"bag");
        for(var n:List.of(manju,bag))for(var y:n.animals("breeder")){y.setOnGround(true);HumanCommerce.prepareBreederHome(y);}
        check("Distinct persistent factory roles",labor.production().kind().equals("labor")&&manju.production().kind().equals("manju")&&bag.production().kind().equals("bag"));
        check("Labor workforce has actual individuals",labor.animals("labor").size()==4&&labor.animals("breeder").isEmpty());
        check("Both processing works have two dedicated parent pairs",manju.animals("breeder").size()==4&&bag.animals("breeder").size()==4);
        check("Parents receive actual nest homes",manju.animals("breeder").stream().allMatch(y->y.life().homeStand()!=null));
        var captive=bag.animals("input").getFirst();captive.moveTo(1338.5,67,13.5);var id=captive.getUUID();int material=bag.production().material();
        captive.pet().issue(io.github.longshou.mcyukkuri.pet.BadgeGrade.GOLD,1,null);var family=captive.family().save();
        check("Input cannot bypass physical staged processing",!bag.production().process(captive)&&bag.production().outputCount()==0);
        check("Completed live packaging produces one finite sealed occupant",finishFixture(bag,captive)&&captive.isRemoved()&&bag.production().outputCount()==1&&bag.production().material()==material-1);
        var sealed=bag.production().product();var token=CarrierItem.token(sealed);var ledger=YukkuriWorldData.get(l);
        var snapshot=ledger.captiveSnapshot(token);
        check("Package preserves UUID, family and gold qualification",snapshot.hasUUID("UUID")&&snapshot.getUUID("UUID").equals(id)&&snapshot.getCompound("YukkuriFamily").equals(family)&&snapshot.getCompound("YukkuriPet").getInt("Grade")==3);
        check("Processing state is saved on the live occupant",snapshot.getCompound("NeoForgeData").getCompound("HumanCommerce").getBoolean("ProcessedLive"));
        var saved=new CompoundTag();bag.save(saved);var loaded=HumanWorld.BROTHER.get().create(l);loaded.load(saved);
        check("Factory reload keeps queued token and debited materials",CarrierItem.token(loaded.production().product()).equals(token)&&loaded.production().material()==material-1);
        var p=FakePlayerFactory.get(l,new com.mojang.authlib.GameProfile(UUID.fromString("d14e9845-5438-44a8-9ad0-4a103a5e3727"),"FactoryAcceptance"));
        p.getInventory().clearContent();p.getInventory().add(new ItemStack(Items.EMERALD,32));p.moveTo(bag.position().add(0,0,-1));
        var menu=new HumanMenu(30,p.getInventory(),bag.getId());p.containerMenu=menu;
        check("Factory sale debits stock and gives its actual package",menu.clickMenuButton(p,14)&&bag.production().outputCount()==0&&p.getInventory().items.stream().anyMatch(s->token.equals(CarrierItem.token(s))));
        check("Empty product queue cannot sell again",!menu.clickMenuButton(p,14));
        var actual=p.getInventory().items.stream().filter(s->s.is(HumanWorld.LIVE_BAG.get())).findFirst().orElseThrow().copy();p.getInventory().clearContent();p.setItemInHand(InteractionHand.MAIN_HAND,actual);
        var floor=new BlockPos(1380,66,10);
        for(var cell:BlockPos.betweenClosed(floor.offset(-3,0,-3),floor.offset(3,4,3)))l.setBlockAndUpdate(cell,cell.getY()==66?Blocks.STONE.defaultBlockState():Blocks.AIR.defaultBlockState());
        l.setBlockAndUpdate(floor.above(),Blocks.STONE.defaultBlockState());p.moveTo(1378,67,10);
        var hit=new BlockHitResult(Vec3.atCenterOf(floor).add(0,.5,0),Direction.UP,floor,false);
        var context=new UseOnContext(p,InteractionHand.MAIN_HAND,hit);
        check("Blocked opening leaves the package and token intact",!HumanWorld.LIVE_BAG.get().useOn(context).consumesAction()&&ledger.hasToken(token)&&actual.getCount()==1);
        l.setBlockAndUpdate(floor.above(),Blocks.AIR.defaultBlockState());l.setBlockAndUpdate(floor.above(2),Blocks.AIR.defaultBlockState());
        check("Opening releases the same living UUID once",HumanWorld.LIVE_BAG.get().useOn(context).consumesAction()&&l.getEntity(id) instanceof YukkuriEntity&&actual.isEmpty()&&!ledger.hasToken(token));
        check("Copied package cannot duplicate an occupant",ledger.release(token,l,new Vec3(1382,67,10),0)==null);
        check("Released live product belongs to the opener",HumanCommerce.ownedBy((YukkuriEntity)l.getEntity(id),p.getUUID()));
        var input=manju.animals("input").getFirst();int filling=input.body().filling();material=manju.production().material();
        check("Completed manju processing consumes real filling and wheat",finishFixture(manju,input)&&input.body().filling()==filling-1&&manju.production().material()==material-1&&manju.production().product().is(HumanWorld.MANJU.get()));
        check("Manju retains source identity",io.github.longshou.mcyukkuri.body.FillingResource.data(manju.production().product()).getUUID("Source").equals(input.getUUID()));
        check("Production cooldown rejects a duplicate immediate operation",!manju.production().process(input));
        var empty=manju.production().save();empty.putInt("Material",0);empty.putInt("Delay",0);manju.production().load(empty);filling=input.body().filling();
        check("Exhausted wheat stops processing without injury or debit",!manju.production().process(input)&&input.body().filling()==filling);
        var wrong=new ItemStack(Items.PAPER,8);var wheat=new ItemStack(Items.WHEAT,8);
        check("Replenishment consumes only matching actual material",!manju.production().replenish(wrong)&&wrong.getCount()==8&&manju.production().replenish(wheat)&&wheat.isEmpty()&&manju.production().material()==8);
        var laborer=labor.animals("labor").getFirst();laborer.moveTo(1218.5,67,13.5);labor.tickCount=100;
        int count=labor.production().outputCount();float food=laborer.life().nutrition();labor.production().tick();
        check("Labor produces paper at a bench and spends worker nutrition",labor.production().outputCount()==count+1&&labor.production().product().is(Items.PAPER)&&laborer.life().nutrition()<food);
        var poor=laborer.life().save();poor.putFloat("Nutrition",0);laborer.life().load(poor);
        var next=labor.animals("labor").get(1);next.moveTo(1218.5,67,13.5);var reset=labor.production().save();reset.putInt("Delay",0);labor.production().load(reset);
        count=labor.production().outputCount();labor.production().tick();check("One hungry laborer does not stop the healthy workforce",labor.production().outputCount()==count+1);
        check("Live package resale cannot profit from the three-emerald input payment",bag.production().productPrice()>3);
        // The consumer uses the same five-page server catalog; no rare placeholders are purchasable.
        p.moveTo(bag.position().add(0,0,-1));p.containerMenu=menu;Set<Item> goods=new HashSet<>();
        for(int page=0;page<5;page++){for(int i=0;i<4;i++)goods.add(menu.supply(i).getItem());check("Supply page "+page+" changes on the server",menu.clickMenuButton(p,13));}
        check("Catalog contains 20 distinct real supplies",goods.size()==20&&goods.contains(ModItems.NEST.get())&&goods.contains(ModItems.WHISTLE.get())&&goods.contains(ModItems.PATCHOULI_BONNET.get()));
        check("Invalid page/action IDs cannot transact",!menu.clickMenuButton(p,999)&&!menu.clickMenuButton(p,-1));
        p.containerMenu=p.inventoryMenu;p.getInventory().clearContent();
        safeguards(bag,bag.animals("input").getFirst());
        replacement(manju);
    }
    private void replacement(HumanNpc n){
        var old=n.animals("breeder").stream().filter(y->HumanCommerce.data(y).getInt("FactoryGroup")==0).toList();
        for(var y:old){var tag=HumanCommerce.data(y).copy();tag.putInt("FactoryCycles",6);HumanCommerce.set(y,tag);}
        var pair=new ArrayList<YukkuriEntity>();
        for(int i=0;i<2;i++){var y=(YukkuriEntity)old.get(i).getType().create(n.level());y.moveTo(1286.5+i*2,67,17.5);y.setOnGround(true);y.setNoAi(true);n.level().addFreshEntity(y);HumanCommerce.assign(y,n.getUUID(),"reserve_parent");pair.add(y);}
        n.production().breeding().tick();
        check("Exhausted parent pair retires and releases its nests",old.stream().allMatch(y->HumanCommerce.data(y).getBoolean("RetiredParent")&&HumanCommerce.data(y).getString("Use").equals("input")&&y.life().homeStand()==null));
        check("Real reserve pair enters physical parent transfer before breeding",pair.stream().allMatch(y->HumanCommerce.data(y).getString("Use").equals("breeder")&&FactoryPipeline.stage(y).equals("parent_transfer"))&&!n.production().breeding().allows(pair.get(0),pair.get(1),1));
        for(var y:pair){var flow=n.production().pipeline();var tray=flow.tray();tray.moveTo(Vec3.atBottomCenterOf(flow.destination(y)));y.moveTo(tray.position().add(0,.25,0));y.startRiding(tray,true);flow.arrived(y,tray);}
        check("Replacement delivery claims real vacant nests",pair.stream().allMatch(y->!y.isPassenger()&&FactoryPipeline.stage(y).isEmpty()&&y.life().homeStand()!=null));
    }
    private void safeguards(HumanNpc n,YukkuriEntity y){
        var flow=n.production().pipeline();var saved=n.production().save();var original=HumanCommerce.data(y).copy();float health=y.getHealth();
        flow.enroll(y);var tag=HumanCommerce.data(y).copy();int batch=tag.getInt("BatchNumber");
        flow.enroll(y);check("Re-enrolling never changes an individual's batch",batch==HumanCommerce.data(y).getInt("BatchNumber"));
        var dock=flow.destination(y);var t=flow.tray();t.moveTo(Vec3.atBottomCenterOf(dock));y.moveTo(t.position().add(0,.25,0));y.startRiding(t,true);n.moveTo(t.position().add(1.6,0,0));
        tag.putLong("LastFactoryWork",n.level().getGameTime()-20);HumanCommerce.set(y,tag);flow.work(y);
        check("One contact advances only part of the inspection",FactoryPipeline.stage(y).equals("receive")&&HumanCommerce.data(y).getInt("StageWork")==20);
        flow.work(y);check("Repeated same-tick contact cannot accelerate a stage",HumanCommerce.data(y).getInt("StageWork")==20);
        var part=n.production().save();var frozen=part.copy();frozen.putInt("Material",0);n.production().load(frozen);
        check("Missing material freezes work without debiting the individual",!flow.work(y)&&HumanCommerce.data(y).getInt("StageWork")==20&&y.getHealth()==health);
        var full=part.copy();var products=new net.minecraft.nbt.ListTag();for(int i=0;i<16;i++)products.add(new ItemStack(Items.PAPER).save(n.registryAccess()));full.put("Output",products);n.production().load(full);
        flow.cycleSales();check("Full storage stops production even when the selected use has no stock",n.production().outputCount()==0&&!flow.operating());n.production().load(part);
        var clone=HumanWorld.BROTHER.get().create(n.level());var all=new CompoundTag();n.save(all);clone.load(all);
        check("Partial line reload keeps the same tray, batches and stage work",clone.production().pipeline().save().equals(flow.save())&&HumanCommerce.data(y).getInt("StageWork")==20);
        tag=HumanCommerce.data(y).copy();tag.putLong("LastFactoryWork",n.level().getGameTime()-20);HumanCommerce.set(y,tag);n.moveTo(t.position().add(8,0,0));
        check("Absent staff cannot complete station work",!flow.work(y)&&HumanCommerce.data(y).getInt("StageWork")==20);n.moveTo(t.position().add(1.6,0,0));
        tag=HumanCommerce.data(y).copy();tag.putInt("StageWork",80);HumanCommerce.set(y,tag);y.setHealth(y.getMaxHealth()*.4f);flow.work(y);
        check("Failed birth inspection routes to observation, never to packing",FactoryPipeline.stage(y).equals("observe_transfer")&&flow.held()>0&&n.production().totalOutputCount()==0);
        var lifeBefore=y.life().save();tag=HumanCommerce.data(y).copy();tag.putString("FactoryStage","observe");HumanCommerce.set(y,tag);
        var hungry=lifeBefore.copy();hungry.putFloat("Nutrition",10);y.life().load(hungry);
        var nurse=n.level().getEntitiesOfClass(HumanNpc.class,AREA,h->h.siteRole().equals("bag_nursery_attendant")).getFirst();var previousNurse=nurse.position();nurse.moveTo(y.position().add(1.6,0,0));
        check("Observation accepts real nursing even for an input individual",nurse.attend(y));nurse.moveTo(previousNurse);y.life().load(lifeBefore);
        t.unload();y.setHealth(health);tag=HumanCommerce.data(y).copy();tag.putString("FactoryStage","observe");HumanCommerce.set(y,tag);flow.tick();
        check("Recovered observations re-enter grading",FactoryPipeline.stage(y).equals("grade"));
        n.production().load(saved);HumanCommerce.set(y,original);
        var trayCount=n.level().getEntitiesOfClass(FactoryTray.class,AREA).size();flow.tick();flow.tick();
        check("Line ticks reuse their persistent tray instead of spawning duplicates",n.level().getEntitiesOfClass(FactoryTray.class,AREA).size()==trayCount);
        flow.trayDestroyed(flow.tray().getUUID());var iron=new ItemStack(Items.IRON_INGOT,4);
        check("A destroyed tray needs exactly four repair ingots",flow.status()==3&&flow.repair(iron)&&iron.isEmpty());n.production().load(saved);
        var station=new BlockPos(1340,67,19);var block=n.level().getBlockState(station);n.level().setBlockAndUpdate(station,Blocks.AIR.defaultBlockState());
        check("A missing grading station pauses the whole production line",flow.status()==4&&!flow.operating());n.level().setBlockAndUpdate(station,block);
    }
    /** Prepare the last operation for inventory/identity tests; the separate AI run covers every preceding stage. */
    private boolean finishFixture(HumanNpc n,YukkuriEntity y){
        var flow=n.production().pipeline();flow.enroll(y);var tag=HumanCommerce.data(y).copy();tag.putString("FactoryStage","pack");tag.putInt("StageWork",80);
        tag.putLong("LastFactoryWork",n.level().getGameTime()-20);tag.putInt("Purpose",1);HumanCommerce.set(y,tag);
        var pos=flow.destination(y);var previous=flow.tray();if(previous!=null)previous.discard();
        var tray=HumanWorld.TRAY.get().create(n.level());tray.assign(n.getUUID());tray.moveTo(Vec3.atBottomCenterOf(pos));tray.setNoAi(true);n.level().addFreshEntity(tray);
        var state=flow.save();state.putUUID("Tray",tray.getUUID());flow.load(state);y.moveTo(tray.position().add(0,.25,0));y.startRiding(tray,true);
        n.moveTo(tray.position().add(1.6,0,0));boolean result=n.production().process(y);
        if(!result)P0Control.write("factory-fixture-debug",Map.of("status",flow.status(),"equipped",flow.equipped(),"loaded",flow.fullyLoaded(),"needs",flow.needsService(y),"health",FactoryPipeline.healthy(y),"sight",n.hasLineOfSight(y),"tag",HumanCommerce.data(y).toString(),"distance",n.distanceToSqr(y),"worker",n.position().toString(),"tray",tray.position().toString()));
        return result;
    }
}
