package io.github.longshou.mcyukkuri.p0;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.human.HumanCommerce;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import io.github.longshou.mcyukkuri.settlement.NaturalShelter;
import io.github.longshou.mcyukkuri.settlement.SettlementData;
import io.github.longshou.mcyukkuri.settlement.ShelterBlock;
import io.github.longshou.mcyukkuri.settlement.ShelterBlockEntity;
import io.github.longshou.mcyukkuri.settlement.ShelterPlacement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Remote-only: actual entity movement, material transfers, home claims and interrupted visits. */
@PrefixGameTestTemplate(false)
public final class IndependenceGameTests {
    private static final BlockPos ORIGIN = new BlockPos(2600,65,8), DESTINATION = ORIGIN.east(10);
    @GameTest(templateNamespace=P0Mod.ID,template="empty",timeoutTicks=4000,batch="independence")
    public static void buildReloadVisit(GameTestHelper helper) {
        var level = helper.getLevel();
        var forced = new HashSet<>(level.getForcedChunks());
        long time = level.getDayTime();
        boolean daylight = level.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT);
        boolean grief = level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING);
        boolean rain = level.isRaining(), thunder = level.isThundering();
        float rainLevel = level.getRainLevel(1), thunderLevel = level.getThunderLevel(1);
        int hungerRate = io.github.longshou.mcyukkuri.life.YukkuriConfig.HUNGER_INTERVAL.get();
        int energyRate = io.github.longshou.mcyukkuri.life.YukkuriConfig.ENERGY_INTERVAL.get();
        var actors = new ArrayList<YukkuriEntity>();
        var groups = new ArrayList<UUID>();
        YukkuriEntity[] child = {null}; ShelterBlockEntity[] old = {null};
        UUID[] removedAccessory = {null};
        int[] previousFood = {0}, phase = {0}; boolean[] cleaned = {false}, reload = {false};
        var trace = new ArrayList<java.util.Map<String,Object>>();
        Runnable cleanup = () -> {
            if (cleaned[0]) return; cleaned[0] = true;
            for (var pos : List.of(ORIGIN, DESTINATION)) { var home = ShelterBlock.shelter(level,pos); if (home != null) home.rollback(); }
            for (var actor : actors) { if (actor.settlement().groupId() != null) groups.add(actor.settlement().groupId()); actor.discard(); }
            groups.forEach(id -> SettlementData.get(level).remove(id));
            FamilyData.get(level).rollbackEncounter(actors.stream().map(Entity::getUUID).collect(java.util.stream.Collectors.toSet()));
            level.getEntitiesOfClass(ItemEntity.class,new AABB(ORIGIN).inflate(20)).forEach(Entity::discard);
            level.setDayTime(time); level.setWeatherParameters(12000,12000,rain,thunder);
            level.setRainLevel(rainLevel); level.setThunderLevel(thunderLevel);
            io.github.longshou.mcyukkuri.life.YukkuriConfig.HUNGER_INTERVAL.set(hungerRate);
            io.github.longshou.mcyukkuri.life.YukkuriConfig.ENERGY_INTERVAL.set(energyRate);
            level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(daylight,level.getServer());
            level.getGameRules().getRule(GameRules.RULE_MOBGRIEFING).set(grief,level.getServer());
            for (int x=161;x<=164;x++) for(int z=-1;z<=1;z++)
                GameTestChunks.setForced(level,x,z,forced.contains(net.minecraft.world.level.ChunkPos.asLong(x,z)));
            P0Control.write("independence-trace",trace);
        };
        helper.testInfo.addListener(new net.minecraft.gametest.framework.GameTestListener() {
            public void testStructureLoaded(net.minecraft.gametest.framework.GameTestInfo info) {}
            public void testPassed(net.minecraft.gametest.framework.GameTestInfo info,net.minecraft.gametest.framework.GameTestRunner runner) { cleanup.run(); }
            public void testFailed(net.minecraft.gametest.framework.GameTestInfo info,net.minecraft.gametest.framework.GameTestRunner runner) { cleanup.run(); }
            public void testAddedForRerun(net.minecraft.gametest.framework.GameTestInfo oldInfo,net.minecraft.gametest.framework.GameTestInfo newInfo,net.minecraft.gametest.framework.GameTestRunner runner) {}
        });
        for (int x=161;x<=164;x++) for(int z=-1;z<=1;z++) GameTestChunks.setForced(level,x,z,true);
        helper.onEachTick(() -> {
            if (cleaned[0] || child[0] == null) return;
            var y = child[0];
            trace.add(java.util.Map.of("tick",helper.getTick(),"phase",phase[0],"position",y.position().toString(),
                    "behavior",y.getBehaviorId(),"work",y.independence().work(),"carrying",y.independence().carrying().toString(),
                    "oldOwns",old[0].owns(y.getUUID()),"visits",y.independence().visits()));
            if (phase[0] == 3 || phase[0] == 4)
                System.out.println("MCYUKKURI_P0 independence recognition phase=" + phase[0] + " " + visitDiagnostics(y,actors.getFirst()));
            if (phase[0] == 0 && y.independence().work() >= 120 && !reload[0]) {
                helper.assertTrue(old[0].owns(y.getUUID()) && y.habitat().shelter() == old[0],"Old bed remains owned throughout unfinished construction");
                var saved = y.independence().save();
                int materialCount = saved.getList("Materials",Tag.TAG_COMPOUND).size();
                helper.assertTrue(materialCount == 3,"Three actual pieces arrived before labor advanced");
                y.independence().stop(); y.independence().load(saved);
                helper.assertTrue(y.independence().work() == saved.getInt("Work")
                        && y.independence().save().getList("Materials",Tag.TAG_COMPOUND).size() == materialCount,"Interrupted and reloaded work retains references without minting materials");
                reload[0] = true;
            }
        });
        helper.startSequence().thenWaitUntil(() -> GameTestChunks.assertTicking(helper,161,164,-1,1)).thenExecute(() -> {
            ShelterGameTests.daylightFixture(level);
            io.github.longshou.mcyukkuri.life.YukkuriConfig.HUNGER_INTERVAL.set(24000);
            io.github.longshou.mcyukkuri.life.YukkuriConfig.ENERGY_INTERVAL.set(24000);
            for (int x=2584;x<=2620;x++) for(int z=-8;z<=24;z++) {
                level.setBlockAndUpdate(new BlockPos(x,64,z),Blocks.GRASS_BLOCK.defaultBlockState());
                for(int y=65;y<=69;y++) level.setBlockAndUpdate(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState());
            }
            var oldPlan = cavePlan(helper,ORIGIN,2);
            cavePlan(helper,DESTINATION,1);
            var placed = ShelterPlacement.place(level,oldPlan);
            helper.assertTrue(placed != null,"The surveyed original cave accepts its real leaf-bed marker");
            old[0] = placed.shelter();
            old[0].initializeEncounter(2);
            var parent = ModEntities.REIMU.get().create(level); parent.moveTo(Vec3.atBottomCenterOf(ORIGIN)); parent.setNoAi(true); level.addFreshEntity(parent); actors.add(parent);
            var y = ModEntities.MARISA.get().create(level); y.moveTo(Vec3.atBottomCenterOf(ORIGIN.south())); level.addFreshEntity(y); actors.add(y); child[0] = y;
            y.family().generatedChild(parent,null);
            var growth = y.family().save(); growth.putString("Stage","ADULT"); growth.putLong("Age",y.family().maturityAge()+1);
            growth.putLong("Nourishment",y.family().maturityAge()); growth.putFloat("Scale",1); y.family().load(growth);
            FamilyData.get(level).register(parent); FamilyData.get(level).register(y);
            var group = new SettlementData.Group(UUID.randomUUID(),SettlementData.Kind.FAMILY,ORIGIN,null); groups.add(group.id);
            SettlementData.get(level).add(group); parent.settlement().join(group); y.settlement().join(group);
            groundActor(helper,parent,"Original parent"); groundActor(helper,y,"Adult builder");
            boolean parentClaim = parent.habitat().choose(old[0]);
            helper.assertTrue(parentClaim,parentClaim ? "Parent claims a real bed" : "Parent claim failed: " + homeDiagnostics(parent,old[0]));
            boolean childClaim = y.habitat().choose(old[0]);
            helper.assertTrue(childClaim,childClaim ? "Adult child claims a separate real bed" : "Child claim failed: " + homeDiagnostics(y,old[0]));
            var parentBed = old[0].bed(parent.getUUID()); var childBed = old[0].bed(y.getUUID());
            helper.assertTrue(parentBed != null && childBed != null && !parentBed.equals(childBed),"The two residents own distinct surveyed standing cells");
            helper.assertTrue(y.life().reachable(DESTINATION,false) && y.life().reachable(DESTINATION.south(),false),
                    "The adult builder can physically reach the new cave marker and outside entrance");
            var life = y.life().save(); life.putFloat("Nutrition",100); life.putFloat("Energy",100); life.putFloat("Bladder",0); y.life().load(life);
            helper.assertTrue(y.independence().eligible(),"Normal adult natural child can consider independence");
            var human = HumanCommerce.data(y).copy(); HumanCommerce.assign(y,UUID.randomUUID(),"companion");
            helper.assertTrue(!y.independence().eligible(),"A player-adopted child is excluded"); HumanCommerce.set(y,human);
            var role = y.settlement().society().record(); role.rejections = 1;
            helper.assertTrue(!y.independence().eligible(),"An unresolved social penalty cannot be cleared by departure"); role.rejections = 0;
            previousFood[0] = old[0].food();
            var project = project(y,ORIGIN,DESTINATION,ShelterBlock.Kind.BURROW); y.independence().load(project);
            // Existing leaves are actual materials; no material is seeded into the module's counters.
            var leaves = new ItemEntity(level,ORIGIN.getX()+.5,ORIGIN.getY()+.1,ORIGIN.getZ()+1.5,new ItemStack(Items.OAK_LEAVES,3));
            leaves.setDeltaMovement(Vec3.ZERO); leaves.setNoPickUpDelay(); level.addFreshEntity(leaves);
        }).thenWaitUntil(() -> helper.assertTrue(child[0].independence().independent(),"Child must physically carry all three materials and complete the new shelter"))
        .thenExecute(() -> {
            phase[0] = 1; var y = child[0]; var home = y.habitat().shelter();
            helper.assertTrue(reload[0],"The construction interruption checkpoint ran");
            helper.assertTrue(home != null && home.getBlockPos().equals(DESTINATION) && home.usable(y) && home.lining() == 3 && home.food() == 0,
                    "New home has the consumed bedding and no invented food");
            helper.assertTrue(!old[0].owns(y.getUUID()) && old[0].food() == previousFood[0],"Old bed is released, while old inventory is preserved");
            helper.assertTrue(!y.settlement().groupId().equals(groups.getFirst()) && y.settlement().group().center.equals(DESTINATION),"New family return center is the real new home");
            helper.assertTrue(FamilyData.get(level).related(y.getUUID(),actors.getFirst().getUUID()),"Moving retains original parentage");
            helper.assertTrue(level.getEntitiesOfClass(ItemEntity.class,new AABB(ORIGIN).inflate(18),item->item.getItem().is(Items.OAK_LEAVES)).isEmpty(),"Exactly the three real leaf materials were consumed");
            // Distance <=1.5 alone can still put an approaching visitor behind the cave's side wall.
            // Keep the NoAI relative at the real outside entrance for a visible greeting, with its original bed intact.
            var parent = actors.getFirst(); var greetingSpot = Vec3.atBottomCenterOf(old[0].entrance());
            parent.move(MoverType.SELF,greetingSpot.subtract(parent.position())); groundActor(helper,parent,"Visiting relative");
            helper.assertTrue(parent.isNoAi() && parent.distanceToSqr(greetingSpot) < 1.0e-6 && old[0].owns(parent.getUUID())
                    && old[0].interior().inflate(3).contains(parent.position()),"Actual external movement leaves the relative grounded at its own home's outside entrance");
            readyVisit(y);
        }).thenWaitUntil(() -> helper.assertTrue(child[0].independence().active()
                && child[0].independence().activity().equals("independence_visit") && child[0].distanceToSqr(actors.getFirst()) > 2.25,
                "First revisit starts while the child is still walking toward its parent"))
        .thenExecute(() -> removedAccessory[0] = actors.getFirst().life().takeAccessory(null))
        .thenIdle(2).thenExecute(() -> {
            var y = child[0]; var parent = actors.getFirst();
            helper.assertTrue(removedAccessory[0] != null && !y.life().canRecognize(parent),"Ordinary visitor loses recognition when its parent loses the accessory");
            helper.assertTrue(!y.independence().active() && y.independence().visits() == 0 && y.independence().save().getBoolean("Returning"),
                    "Accessory loss cancels the ongoing approach without counting a visit or losing the return obligation");
            helper.assertTrue(parent.life().returnAccessory(parent.getUUID(),removedAccessory[0],null),"The same parent accessory is returned");
        }).thenWaitUntil(() -> assertReturned(helper,child[0]))
        .thenExecute(() -> { phase[0] = 2; readyVisit(child[0]); })
        .thenWaitUntil(() -> helper.assertTrue(child[0].independence().active()
                && independenceTask(child[0]).equals("GREET") && child[0].distanceToSqr(actors.getFirst()) <= 2.25
                && child[0].hasLineOfSight(actors.getFirst()) && actors.getFirst().hasLineOfSight(child[0]),
                "Second revisit must begin the actual visible greeting: " + visitDiagnostics(child[0],actors.getFirst())))
        .thenExecute(() -> {
            var y = child[0]; var parent = actors.getFirst();
            parent.life().setMedal(2); phase[0] = 3;
            helper.assertTrue(parent.life().hasGoldMedal(),"Observer really has the gold qualification before accessory removal");
            helper.assertTrue(y.life().hasAccessory(),"Visitor still has its actual accessory before removal");
            removedAccessory[0] = y.life().takeAccessory(null);
            System.out.println("MCYUKKURI_P0 independence gold setup " + visitDiagnostics(y,parent));
        }).thenIdle(3).thenExecute(() -> {
            var y = child[0]; var parent = actors.getFirst();
            String state = visitDiagnostics(y,parent);
            helper.assertTrue(removedAccessory[0] != null,"Visitor accessory removal returns the real identity token: " + state);
            helper.assertTrue(parent.life().hasGoldMedal(),"Observer retains the actual gold qualification: " + state);
            helper.assertTrue(parent.life().canRecognize(y),"Gold observer recognizes its accessory-less child: " + state);
            helper.assertTrue(y.life().canRecognize(parent),"Visitor still recognizes its adorned parent: " + state);
            helper.assertTrue(y.independence().active(),"Gold exception leaves the actual visit active: " + state);
            helper.assertTrue(independenceTask(y).equals("GREET"),"Gold exception preserves the real greeting phase: " + state);
            helper.assertTrue(y.independence().activity().equals("independence_visit"),"Gold exception retains the public visit activity: " + state);
            helper.assertTrue(y.independence().visits() == 0,"Brief gold greeting cannot prematurely count a completed visit: " + state);
            parent.life().setMedal(0); phase[0] = 4;
        }).thenIdle(2).thenExecute(() -> {
            var y = child[0]; String state = visitDiagnostics(y,actors.getFirst());
            helper.assertTrue(!actors.getFirst().life().canRecognize(y),"Removing gold really removes the observer's recognition exception: " + state);
            helper.assertTrue(!y.independence().active(),"Losing recognition cancels the real greeting: " + state);
            helper.assertTrue(y.independence().visits() == 0,"Cancelled greeting counts no completed visit: " + state);
            helper.assertTrue(y.independence().save().getBoolean("Returning"),"Cancelled greeting retains the physical return obligation: " + state);
            helper.assertTrue(y.life().returnAccessory(y.getUUID(),removedAccessory[0],null),"The same visitor accessory is returned");
            phase[0] = 5;
        }).thenWaitUntil(() -> assertReturned(helper,child[0]))
        .thenExecute(() -> readyVisit(child[0]))
        .thenWaitUntil(() -> helper.assertTrue(child[0].independence().visits() == 1,"Child walks back and spends time with actual relative"))
        .thenExecute(() -> {
            var y = child[0]; y.independence().load(y.independence().save());
            helper.assertTrue(!old[0].owns(y.getUUID()),"Visit and reload never reclaim the family bed");
        }).thenWaitUntil(() -> {
            var stand = child[0].habitat().homeStand();
            helper.assertTrue(stand != null && !child[0].independence().save().getBoolean("Returning")
                    && child[0].distanceToSqr(Vec3.atBottomCenterOf(stand)) < 1,"After visit reload, the same entity walks back to its own home");
        }).thenExecute(() -> weatherAndPaper(helper,old[0],actors))
        .thenSucceed();
    }
    /** Read-only P0 observation: no task, goal or production state is changed to manufacture a greeting. */
    private static String independenceTask(YukkuriEntity actor) {
        try {
            var field = actor.independence().getClass().getDeclaredField("task"); field.setAccessible(true);
            return ((Enum<?>)field.get(actor.independence())).name();
        } catch (ReflectiveOperationException error) { throw new IllegalStateException("Cannot observe the real independence task",error); }
    }
    private static String visitDiagnostics(YukkuriEntity visitor,YukkuriEntity parent) {
        return "tick=" + visitor.tickCount + ", task=" + independenceTask(visitor) + ", active=" + visitor.independence().active()
                + ", activity=" + visitor.independence().activity() + ", behavior=" + visitor.getBehaviorId()
                + ", visits=" + visitor.independence().visits() + ", returning=" + visitor.independence().save().getBoolean("Returning")
                + ", medals=" + parent.life().medal() + "/" + visitor.life().medal()
                + ", accessories=" + parent.life().hasAccessory() + "/" + visitor.life().hasAccessory()
                + ", recognizes=" + parent.life().canRecognize(visitor) + "/" + visitor.life().canRecognize(parent)
                + ", LOS=" + parent.hasLineOfSight(visitor) + "/" + visitor.hasLineOfSight(parent)
                + ", distance2=" + visitor.distanceToSqr(parent) + ", positions=" + parent.position() + "/" + visitor.position()
                + ", needs=" + visitor.life().nutrition() + "/" + visitor.life().energy() + "/" + visitor.life().bladder() + "/" + visitor.life().dryness()
                + ", urgent=" + visitor.life().hasUrgentNeed() + ", life=" + visitor.life().activity()
                + ", panic=" + visitor.isPanicking() + ", command=" + visitor.isCommandPlaying() + ", operations=" + visitor.operations().active()
                + ", badges=" + visitor.badges().active() + ", shell=" + visitor.shellRetraction().active()
                + ", family=" + visitor.family().active() + "/recovery=" + visitor.family().recovery() + ", daily=" + visitor.dailyLife().activity()
                + ", group=" + visitor.groupInteraction().activity() + ", play=" + visitor.playLife().activity()
                + ", habitat=" + visitor.habitat().active() + ":" + visitor.habitat().activity()
                + ", duties=" + visitor.settlement().duties().active() + ":" + visitor.settlement().duties().activity()
                + ", social=" + visitor.social().active() + ", reserved=" + io.github.longshou.mcyukkuri.event.WildEvents.reserved(visitor)
                + ", role=" + visitor.settlement().society().role() + ", parentLife=" + parent.life().activity()
                + ", parentUrgent=" + parent.life().hasUrgentNeed() + ", parentPanic=" + parent.isPanicking()
                + ", parentOperations=" + parent.operations().active() + ", parentFamily=" + parent.family().active();
    }
    private static void groundActor(GameTestHelper helper,YukkuriEntity actor,String label) {
        helper.assertTrue(helper.getLevel().isPositionEntityTicking(actor.blockPosition()),label + " is in an entity-ticking chunk");
        helper.assertTrue(actor.level().noCollision(actor),label + " actual grown body fits before ground contact: " + actor.getBoundingBox());
        // createPath requires onGround for a dry, unmounted mob. Let collision establish it, including for NoAI.
        actor.move(MoverType.SELF,new Vec3(0,-.125,0));
        helper.assertTrue(actor.onGround() && actor.verticalCollision,label + " must touch the real floor before pathfinding: " + actor.position());
    }
    private static String homeDiagnostics(YukkuriEntity actor,ShelterBlockEntity home) {
        var bed = home.bed(actor.getUUID());
        return "position=" + actor.position() + ", ground=" + actor.onGround() + ", body=" + actor.getBoundingBox()
                + ", sound=" + home.sound() + ", owns=" + home.owns(actor.getUUID()) + ", bed=" + bed
                + ", entryDry=" + actor.life().dryStand(home.entrance()) + ", entryPath=" + actor.life().reachable(home.entrance(),false)
                + ", beds=" + home.beds().stream().map(pos -> pos.toShortString() + ":dry=" + actor.life().dryStand(pos)
                        + ":path=" + actor.life().reachable(pos,false)).toList();
    }
    private static ShelterPlacement.Plan cavePlan(GameTestHelper helper,BlockPos anchor,int beds) {
        var level = helper.getLevel();
        helper.assertTrue(level.isPositionEntityTicking(anchor),"Cave anchor is entity-ticking before terrain and placement checks");
        // cave() takes the mouth/leaf-marker cell itself; its outside entrance is one cell south.
        NaturalShelterFixture.cave(level,anchor,Direction.SOUTH);
        var site = NaturalShelter.survey(level,anchor,Direction.SOUTH);
        helper.assertTrue(site != null,"Existing cavity must survey at the actual mouth, not one cell behind it");
        helper.assertTrue(site.entrance().equals(anchor.south()) && site.beds().size() >= beds
                && NaturalShelter.stand(level,site.entrance()) && !NaturalShelter.enclosed(level,site.entrance()),
                "Cave has sufficient real beds and a clear outside entrance");
        var plan = new ShelterPlacement.Plan(anchor,ShelterBlock.Kind.BURROW,Direction.SOUTH);
        helper.assertTrue(ShelterPlacement.canPlace(level,plan),"Surveyed cave satisfies unchanged production placement constraints");
        return plan;
    }
    private static void readyVisit(YukkuriEntity y) {
        var visit = y.independence().save(); visit.putInt("VisitWait",0); visit.putInt("Wait",0); visit.putBoolean("Returning",false);
        y.independence().load(visit);
    }
    private static void assertReturned(GameTestHelper helper,YukkuriEntity y) {
        var stand = y.habitat().homeStand();
        helper.assertTrue(stand != null && !y.independence().save().getBoolean("Returning")
                && y.distanceToSqr(Vec3.atBottomCenterOf(stand)) < 1,"Interrupted visitor walks back to its own new home");
    }
    private static CompoundTag project(YukkuriEntity y,BlockPos old,BlockPos site,ShelterBlock.Kind kind) {
        var tag = new CompoundTag(); FamilyData.putPosition(tag,"Origin",GlobalPos.of(y.level().dimension(),old));
        FamilyData.putPosition(tag,"Site",GlobalPos.of(y.level().dimension(),site)); tag.putUUID("OriginalGroup",y.settlement().groupId());
        tag.putString("Kind",kind.name()); tag.putString("Facing","south"); tag.putBoolean("Farewell",true); return tag;
    }
    private static void weatherAndPaper(GameTestHelper helper,ShelterBlockEntity home,List<YukkuriEntity> actors) {
        var level = helper.getLevel(); var parent = actors.getFirst();
        var y = ModEntities.CHEN.get().create(level); y.moveTo(Vec3.atBottomCenterOf(ORIGIN.south())); level.addFreshEntity(y); actors.add(y);
        y.family().generatedChild(parent,null); var growth = y.family().save(); growth.putString("Stage","ADULT");
        growth.putLong("Age",y.family().maturityAge()+1); growth.putFloat("Scale",1); y.family().load(growth); FamilyData.get(level).register(y);
        y.settlement().join(parent.settlement().group()); groundActor(helper,y,"Adult weather probe");
        boolean claimed = y.habitat().choose(home);
        helper.assertTrue(claimed,claimed ? "Weather probe has a real family bed" : "Weather probe claim failed: " + homeDiagnostics(y,home));
        var site = ORIGIN.south(9); var tag = project(y,ORIGIN,site,ShelterBlock.Kind.CARDBOARD); var list = new ListTag();
        var pieces = new ArrayList<ItemEntity>();
        for(int i=0;i<6;i++) {
            var stack = new ItemStack(i<4?Items.PAPER:Items.OAK_LEAVES); var token = UUID.randomUUID();
            CustomData.update(DataComponents.CUSTOM_DATA,stack,t->t.putUUID("YukkuriConstruction",token));
            var item = new ItemEntity(level,site.getX()+.5,site.getY()+.1,site.getZ()+.5,stack); item.setDeltaMovement(Vec3.ZERO); level.addFreshEntity(item); pieces.add(item);
            var record = new CompoundTag(); record.putUUID("Entity",item.getUUID()); record.putUUID("Token",token); list.add(record);
        }
        tag.put("Materials",list); tag.putInt("Work",600); y.independence().load(tag);
        for(int i=0;i<100;i++)y.independence().clock();
        y.tickCount=200; level.setWeatherParameters(0,1200,true,true); level.setRainLevel(1); level.setThunderLevel(1);
        helper.assertTrue(level.isRainingAt(site),"Weather fixture exposes the real unfinished paper pile");
        y.independence().clock();
        helper.assertTrue(y.independence().rainLosses()==1 && pieces.stream().filter(Entity::isAlive).count()==5 && y.independence().work()==480,
                "Storm removes one actual exposed material and its completed work");
        level.setBlockAndUpdate(site.above(3),Blocks.STONE.defaultBlockState()); y.tickCount=400; y.independence().clock();
        helper.assertTrue(y.independence().rainLosses()==1 && pieces.stream().filter(Entity::isAlive).count()==5,"A real roof protects remaining unfinished materials");
        helper.assertTrue(home.owns(y.getUUID()) && ShelterBlock.shelter(level,site)==null,"Missing paper never creates a completed cardboard shelter or releases the old bed");
        y.independence().abandon();
        helper.assertTrue(pieces.stream().filter(Entity::isAlive).count()==5,"Abandonment refunds no destroyed paper and preserves real surviving materials");
        level.setBlockAndUpdate(site.above(3),Blocks.AIR.defaultBlockState()); level.setWeatherParameters(12000,0,false,false); level.setRainLevel(0); level.setThunderLevel(0);
    }
    private IndependenceGameTests() {}
}
