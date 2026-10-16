package io.github.longshou.mcyukkuri.p0;

import io.github.longshou.mcyukkuri.cognition.Cognition;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import io.github.longshou.mcyukkuri.settlement.LeaderSuccession;
import io.github.longshou.mcyukkuri.settlement.SettlementData;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Remote-only. Fixed bodies isolate the office rules while the real server clock supplies every interval. */
@PrefixGameTestTemplate(false)
public final class SuccessionGameTests {
    private static final BlockPos CENTER = new BlockPos(6400, 80, 6400);

    @GameTest(templateNamespace=P0Mod.ID, template="empty", timeoutTicks=20, batch="succession_records")
    public static void migrationAndDeparture(GameTestHelper helper) {
        var level = helper.getLevel(); var lookup = level.registryAccess(); var data = new SettlementData();
        var none = new SettlementData.Group(UUID.randomUUID(), SettlementData.Kind.FOREST, CENTER, null);
        var family = new SettlementData.Group(UUID.randomUUID(), SettlementData.Kind.FAMILY, CENTER, null);
        var old = new SettlementData.Group(UUID.randomUUID(), SettlementData.Kind.FOREST, CENTER, UUID.randomUUID());
        data.add(none); data.add(family); data.add(old);
        data.member(old, old.leader);
        var nbt = data.save(new CompoundTag(), lookup); nbt.putInt("Version", 2);
        for (var raw : nbt.getList("Groups", Tag.TAG_COMPOUND)) {
            var record = (CompoundTag)raw; record.remove("HasLeaderOffice"); record.remove("VacancyConfirmed");
            if (record.getUUID("Id").equals(old.id)) record.remove("Leader");
        }
        var loaded = SettlementData.load(nbt, lookup);
        helper.assertTrue(!loaded.group(none.id).hasLeaderOffice && !loaded.group(family.id).hasLeaderOffice,
                "Old leaderless forests and families do not invent an office");
        var recovered = loaded.group(old.id);
        helper.assertTrue(recovered.hasLeaderOffice && old.leader.equals(recovered.leader) && !recovered.vacancyConfirmed,
                "An explicit old LEADER record recovers the incumbent, not an assumed death");
        loaded.refreshLeader(level, recovered);
        helper.assertTrue(recovered.leader != null, "Unknown or unloaded incumbent is retained");
        UUID departed = recovered.leader;
        loaded.leave(recovered, departed);
        helper.assertTrue(recovered.leader == null && recovered.vacancyConfirmed, "Explicit departure opens the existing office");
        UUID replacement = UUID.randomUUID(); loaded.member(recovered, replacement);
        helper.assertTrue(loaded.appoint(recovered, replacement), "An authorized replacement updates the office");
        loaded.member(recovered, departed); loaded.restore(recovered, departed, 0);
        helper.assertTrue(loaded.role(recovered, departed) == SettlementData.Role.MEMBER
                && loaded.existingMember(recovered, departed).previousRole == SettlementData.Role.MEMBER
                && replacement.equals(recovered.leader), "A returning departed leader cannot reclaim the occupied office");
        var roundTrip = SettlementData.load(loaded.save(new CompoundTag(), lookup), lookup);
        helper.assertTrue(roundTrip.role(roundTrip.group(old.id), replacement) == SettlementData.Role.LEADER
                && roundTrip.existingMember(roundTrip.group(old.id), replacement).previousRole == SettlementData.Role.LEADER,
                "Office, current role and restoration role survive reload together");
        helper.assertTrue(!loaded.appoint(none, replacement) && !loaded.appoint(family, replacement),
                "Neither a leaderless forest nor a family can appoint a leader");

        // Version-1 local migration state has Roles instead of Members. It must merge with the later witnessed-social records.
        UUID localId = UUID.randomUUID(), oldSlave = UUID.randomUUID(), witnessedMember = UUID.randomUUID();
        BlockPos oldCenter = CENTER.offset(12, 0, -7), threatPos = CENTER.east(9), destination = CENTER.west(24);
        CompoundTag localNbt = new CompoundTag();
        localNbt.putInt("Version", 1);
        var localGroups = new net.minecraft.nbt.ListTag();
        CompoundTag localGroup = new CompoundTag();
        localGroup.putUUID("Id", localId); localGroup.putString("Kind", "FOREST"); localGroup.putLong("Center", oldCenter.asLong());
        localGroup.putBoolean("Led", true); localGroup.putInt("Threat", 7); localGroup.putLong("ThreatTime", 321);
        localGroup.putLong("ThreatPos", threatPos.asLong()); localGroup.putLong("MigrateTo", destination.asLong()); localGroup.putLong("MigrateSince", 300);
        var oldRoles = new net.minecraft.nbt.ListTag();
        CompoundTag oldRole = new CompoundTag(); oldRole.putUUID("Id", oldSlave); oldRole.putString("Role", "SLAVE"); oldRoles.add(oldRole);
        localGroup.put("Roles", oldRoles);
        var laterMembers = new net.minecraft.nbt.ListTag();
        CompoundTag laterMember = new CompoundTag(); laterMember.putUUID("Id", witnessedMember); laterMember.putString("Role", "MEMBER");
        laterMember.putString("PreviousRole", "MEMBER"); laterMember.putInt("Rejections", 3); laterMember.putLong("NextObservation", 400);
        laterMember.putLong("RememberedUntil", 500); laterMembers.add(laterMember); localGroup.put("Members", laterMembers);
        localGroups.add(localGroup); localNbt.put("Groups", localGroups);
        var localLoaded = SettlementData.load(localNbt, lookup); var migrated = localLoaded.group(localId);
        helper.assertTrue(migrated.hasLeaderOffice && !migrated.vacancyConfirmed && migrated.threat == 7
                        && threatPos.equals(migrated.threatPos) && destination.equals(migrated.migrateTo)
                        && localLoaded.role(migrated, oldSlave) == SettlementData.Role.UNUN_SLAVE
                        && localLoaded.existingMember(migrated, witnessedMember).rejections == 3,
                "Legacy Led/Roles and threat migration state coexist with witnessed-member records");
        var localRoundTripData = SettlementData.load(localLoaded.save(new CompoundTag(), lookup), lookup);
        var localRoundTrip = localRoundTripData.group(localId);
        helper.assertTrue(oldCenter.equals(localRoundTrip.center) && localRoundTrip.threat == 7
                        && threatPos.equals(localRoundTrip.threatPos) && destination.equals(localRoundTrip.migrateTo)
                        && localRoundTrip.migrateSince == 300 && localRoundTrip.role(oldSlave) == SettlementData.Role.UNUN_SLAVE
                        && localRoundTrip.role(witnessedMember) == SettlementData.Role.MEMBER
                        && localRoundTripData.existingMember(localRoundTrip, witnessedMember).rememberedUntil == 500,
                "Merged legacy migration and social records survive the new saved-data format");
        helper.succeed();
    }

    @GameTest(templateNamespace=P0Mod.ID, template="empty", timeoutTicks=3200, batch="succession_live")
    public static void witnessedSuccession(GameTestHelper helper) {
        var level = helper.getLevel(); var data = SettlementData.get(level);
        var actors = new ArrayList<YukkuriEntity>();
        var blocks = new HashMap<BlockPos, net.minecraft.world.level.block.state.BlockState>();
        var forced = new HashSet<>(level.getForcedChunks());
        var previousObjects = new HashSet<UUID>();
        level.getEntitiesOfClass(Entity.class, new net.minecraft.world.phys.AABB(CENTER).inflate(40))
                .forEach(e -> previousObjects.add(e.getUUID()));
        var groups = new ArrayList<UUID>(); boolean[] cleaned = {false};
        Runnable cleanup = () -> {
            if (cleaned[0]) return; cleaned[0] = true;
            actors.forEach(Entity::discard); groups.forEach(data::remove);
            FamilyData.get(level).rollbackEncounter(actors.stream().map(Entity::getUUID).collect(java.util.stream.Collectors.toSet()));
            level.getEntitiesOfClass(Entity.class, new net.minecraft.world.phys.AABB(CENTER).inflate(40), e -> !previousObjects.contains(e.getUUID())
                    && (e instanceof io.github.longshou.mcyukkuri.entity.RemainsEntity || e instanceof net.minecraft.world.entity.item.ItemEntity))
                    .forEach(Entity::discard);
            blocks.forEach(level::setBlockAndUpdate);
            for (int x=399;x<=401;x++) for (int z=399;z<=401;z++)
                GameTestChunks.setForced(level,x,z,forced.contains(net.minecraft.world.level.ChunkPos.asLong(x,z)));
        };
        helper.testInfo.addListener(new net.minecraft.gametest.framework.GameTestListener() {
            public void testStructureLoaded(net.minecraft.gametest.framework.GameTestInfo info) {}
            public void testPassed(net.minecraft.gametest.framework.GameTestInfo info, net.minecraft.gametest.framework.GameTestRunner runner) { cleanup.run(); }
            public void testFailed(net.minecraft.gametest.framework.GameTestInfo info, net.minecraft.gametest.framework.GameTestRunner runner) { cleanup.run(); }
            public void testAddedForRerun(net.minecraft.gametest.framework.GameTestInfo oldInfo, net.minecraft.gametest.framework.GameTestInfo newInfo, net.minecraft.gametest.framework.GameTestRunner runner) {}
        });
        for (int x=399;x<=401;x++) for (int z=399;z<=401;z++) GameTestChunks.setForced(level,x,z,true);
        for (BlockPos pos : BlockPos.betweenClosed(CENTER.offset(-8,-1,-8), CENTER.offset(8,3,8))) {
            blocks.put(pos.immutable(),level.getBlockState(pos));
            level.setBlockAndUpdate(pos, pos.getY()==CENTER.getY()-1 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
        }
        var leader = actor(helper, actors, 100, 50, 1000, 1);
        var group = new SettlementData.Group(UUID.randomUUID(), SettlementData.Kind.FOREST, CENTER, leader.getUUID());
        data.add(group); groups.add(group.id); leader.settlement().join(group);
        var winner = actor(helper, actors, 300, 80, 2000, .8f, ModEntities.REIMU.get(), new UUID(0, UUID.randomUUID().getLeastSignificantBits()));
        var tied = actor(helper, actors, 300, 80, 2000, 1.2f, ModEntities.REIMU.get(), new UUID(1, UUID.randomUUID().getLeastSignificantBits()));
        var lowerControl = actor(helper, actors, 300, 70, 8000, 1.2f);
        var younger = actor(helper, actors, 300, 80, 1000, 1.2f);
        var lowerCognition = actor(helper, actors, 299, 100, 8000, 1.2f, ModEntities.PATCHOULI.get(), UUID.randomUUID());
        var child = actor(helper, actors, 1000, 100, 2000, .35f);
        var missingHat = actor(helper, actors, 1000, 100, 8000, 1.2f);
        var stranger = actor(helper, actors, 1000, 100, 8000, 1.2f);
        var owned = actor(helper, actors, 1000, 100, 8000, 1.2f);
        var factory = actor(helper, actors, 1000, 100, 8000, 1.2f);
        var house = actor(helper, actors, 1000, 100, 8000, .3f, ModEntities.HOUSE_REIMU.get(), UUID.randomUUID());
        var predator = actor(helper, actors, 1000, 100, 8000, 1.2f, ModEntities.REMILIA.get(), UUID.randomUUID());
        io.github.longshou.mcyukkuri.human.HumanCommerce.assign(owned,UUID.randomUUID(),"companion");
        var custody = new CompoundTag(); custody.putString("FactoryStage","intake");
        io.github.longshou.mcyukkuri.human.HumanCommerce.set(factory,custody);
        var family = child.family().save(); family.putString("Stage","JUVENILE"); child.family().load(family);
        missingHat.life().stowAccessory();
        for (var actor : List.of(winner,tied,lowerControl,younger,lowerCognition,child,missingHat,owned,factory,house,predator)) actor.settlement().join(group);
        winner.setPos(CENTER.getX()+.5,CENTER.getY(),CENTER.getZ()+.5);
        child.setPos(winner.position().add(1,0,0)); missingHat.setPos(winner.position().add(-1,0,0)); stranger.setPos(winner.position().add(0,0,1));
        for (var actor : List.of(owned,factory,house,predator)) actor.setPos(winner.position().add(1,0,1));
        for (var actor : List.of(tied,lowerControl,younger,lowerCognition)) actor.setPos(winner.position().add(0,0,30));
        var teacher = new net.neoforged.neoforge.common.util.FakePlayer(level,
                new com.mojang.authlib.GameProfile(UUID.randomUUID(),"SuccessionTeacher"));
        YukkuriEntity[] waitingWitness = {null};
        BlockPos water = CENTER.east().below();

        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(actors.stream().allMatch(y -> y.tickCount>0), "Loaded fixture entities tick"))
                .thenExecute(() -> {
            var token = leader.life().takeAccessory(null);
            var role = data.member(group,leader.getUUID()); role.role=SettlementData.Role.SHUNNED; role.rejections=2;
            data.refreshLeader(level,group);
            helper.assertTrue(group.leader.equals(leader.getUUID()) && !group.vacancyConfirmed && data.activeLeader(group)==null,
                    "Loss of recognition suspends the incumbent without opening the office");
            helper.assertTrue(leader.life().returnAccessory(leader.getUUID(),token,null) && leader.getUUID().equals(data.activeLeader(group)),
                    "Returning the original accessory restores the original office");
            UUID captured = YukkuriWorldData.get(level).capture(leader);
            helper.assertTrue(captured != null, "Real carrier custody succeeds");
            data.refreshLeader(level,group);
            helper.assertTrue(leader.getUUID().equals(group.leader) && !group.vacancyConfirmed, "Custody is not removal or death");
            var released = YukkuriWorldData.get(level).release(captured,level,net.minecraft.world.phys.Vec3.atBottomCenterOf(CENTER.east(6)),0);
            helper.assertTrue(released != null, "The same captive is released"); actors.add(released);
            var person = FamilyData.get(level).person(released.getUUID());
            var originalPosition = person.position; person.position=GlobalPos.of(Level.NETHER,CENTER);
            data.refreshLeader(level,group); person.position=originalPosition;
            helper.assertTrue(released.getUUID().equals(group.leader), "A living record in another dimension retains office");
            CompoundTag snapshot = new CompoundTag(); released.saveWithoutId(snapshot);
            released.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK); data.refreshLeader(level,group);
            helper.assertTrue(released.getUUID().equals(group.leader), "Actual chunk-unload removal retains office");
            var returned = ModEntities.REIMU.get().create(level); returned.load(snapshot);
            helper.assertTrue(level.addFreshEntity(returned), "Incumbent reloads with its identity"); actors.add(returned);
            returned.discard(); data.refreshLeader(level,group);
            helper.assertTrue(group.leader==null && group.vacancyConfirmed, "Actual discard recorded as REMOVED opens the office");
            helper.assertTrue(data.member(group,returned.getUUID()).role==SettlementData.Role.MEMBER
                    && data.member(group,returned.getUUID()).previousRole==SettlementData.Role.MEMBER, "Former leader has no latent restoration privilege");
            // Test the DEAD path independently; it must also strip the old role while retaining penalties.
            var mortal = actor(helper,actors,100,50,1000,1);
            var deadGroup = new SettlementData.Group(UUID.randomUUID(),SettlementData.Kind.FOREST,CENTER,mortal.getUUID());
            data.add(deadGroup); groups.add(deadGroup.id); mortal.settlement().join(deadGroup);
            mortal.hurt(level.damageSources().generic(),10000); data.refreshLeader(level,deadGroup);
            helper.assertTrue(FamilyData.get(level).person(mortal.getUUID()).status==FamilyData.Status.DEAD
                    && deadGroup.vacancyConfirmed && deadGroup.leader==null, "Actual fatal damage opens a recorded death vacancy");
            long start = level.getGameTime();
            helper.onEachTick(() -> {
                if (cleaned[0]) return;
                long elapsed = level.getGameTime()-start;
                var current = data.group(group.id);
                if (elapsed>=360 && elapsed<400) return; // No simulated member calls: never count this gap retroactively.
                if (elapsed==40) {
                    lowerControl.setPos(winner.position().add(2,0,0)); younger.setPos(winner.position().add(0,0,2));
                    lowerCognition.setPos(winner.position().add(-2,0,0));
                    tied.setPos(winner.position().add(0,0,-2));
                }
                if (elapsed==140 || elapsed==180) {
                    var life = winner.life().save(); life.putFloat("Nutrition",elapsed==140?10:100); winner.life().load(life);
                }
                if (elapsed>980 && elapsed<1020) winner.life().tickGoal();
                if (elapsed==1020) level.setBlockAndUpdate(water,Blocks.STONE.defaultBlockState());
                if (elapsed>1600 && elapsed<1640) winner.social().tickGoal();
                if (elapsed==1640) winner.social().stop();
                if (elapsed>2220 && elapsed<2260) waitingWitness[0].social().tickGoal();
                if (elapsed==2260) waitingWitness[0].social().stop();
                // NoAI is only a fixture isolation switch between explicit calls to the production service.
                for (var y : actors) if (y.isAlive() && !y.isRemoved()) y.setNoAi(false);
                LeaderSuccession.tick(level,data,current);
                int progress = current.successionTicks;
                for (int repeat=0;repeat<8;repeat++) LeaderSuccession.tick(level,data,current);
                helper.assertTrue(current.successionTicks==progress, "Many member calls cannot multiply the group clock");
                for (var y : actors) y.setNoAi(true);
                if (elapsed==39) helper.assertTrue(current.successor==null && current.successionTicks==0,
                        "Children, strangers, missing accessories, human custody, house variants and predators cannot supply a forest witness");
                if (elapsed==179) helper.assertTrue(current.leader==null && !winner.getUUID().equals(current.successor),
                        "Urgent need interrupts this candidate without prematurely assigning the office");
                if (elapsed==300) {
                    int savedProgress=current.successionTicks;
                    var copy=SettlementData.load(data.save(new CompoundTag(),level.registryAccess()),level.registryAccess()).group(current.id);
                    data.add(copy);
                    for (var y : actors) if (y.isAlive() && !y.isRemoved()) y.setNoAi(false);
                    LeaderSuccession.tick(level,data,copy);
                    for (var y : actors) y.setNoAi(true);
                    helper.assertTrue(copy.successionTicks==savedProgress, "Reload keeps preparation but first sample adds no offline time");
                }
                if (elapsed==400) helper.assertTrue(current.successionTicks==0, "A gap in actual group simulation restarts the witnessed interval");
                if (elapsed==980) {
                    helper.assertTrue(current.successionTicks==580 && winner.getUUID().equals(current.successor), "Actual clock reaches 580 ticks before drinking");
                    level.setBlockAndUpdate(water,Blocks.WATER.defaultBlockState());
                    var life=winner.life().save(); life.putFloat("Dryness",40); winner.life().load(life);
                    winner.move(net.minecraft.world.entity.MoverType.SELF,new net.minecraft.world.phys.Vec3(0,-.125,0));
                    helper.assertTrue(winner.onGround() && winner.verticalCollision, "Real floor collision grounds the candidate before drink pathfinding");
                    helper.assertTrue(!winner.life().hasUrgentNeed() && winner.life().plan(), "Non-urgent thirst starts a real life task");
                    winner.life().tickGoal();
                    helper.assertTrue(winner.life().activity().equals("drink"), "Candidate actually drinks at reachable water");
                }
                if (elapsed==1000) helper.assertTrue(current.leader==null && current.successionTicks==0
                        && !winner.getUUID().equals(current.successor) && winner.life().activity().equals("drink"),
                        "Drinking at the last sample prevents appointment, resets preparation and keeps drinking active");
                if (elapsed==1020) helper.assertTrue(winner.life().activity().equals("idle") && current.successionTicks==0
                        && winner.getUUID().equals(current.successor), "Completed drinking starts a fresh interval instead of restoring 580 ticks");
                if (elapsed==1600) {
                    helper.assertTrue(current.successionTicks==580 && winner.getUUID().equals(current.successor), "Fresh actual interval reaches 580 ticks before teaching");
                    startLesson(helper,winner,teacher,false);
                }
                if (elapsed==1620) helper.assertTrue(current.leader==null && current.successionTicks==0
                        && !winner.getUUID().equals(current.successor) && winner.social().activity().equals("teach"),
                        "An ongoing real lesson prevents appointment without stopping the lesson");
                if (elapsed==1640) helper.assertTrue(current.successionTicks==0 && winner.getUUID().equals(current.successor),
                        "After a lesson stops, its former candidate prepares from zero");
                if (elapsed==2220) {
                    helper.assertTrue(current.successionTicks==580 && winner.getUUID().equals(current.successor), "Actual interval reaches 580 ticks before the witness waits");
                    waitingWitness[0]=(YukkuriEntity)level.getEntity(current.successionWitness);
                    helper.assertTrue(waitingWitness[0]!=null, "The preparing witness is physically present");
                    startLesson(helper,waitingWitness[0],teacher,true);
                }
                if (elapsed==2240) helper.assertTrue(current.leader==null && current.successionTicks==0
                        && !waitingWitness[0].getUUID().equals(current.successionWitness) && waitingWitness[0].social().activity().equals("wait"),
                        "A witness following WAIT cannot complete the old interval and keeps following the command");
                if (elapsed==2260) helper.assertTrue(current.successionTicks==0
                        && waitingWitness[0].getUUID().equals(current.successionWitness), "Returning witness begins another fresh interval");
                if (elapsed==2910) {
                    helper.assertTrue(winner.getUUID().equals(current.leader), "Ability, control, adult experience and stable UUID tie-break beat species and larger bodies");
                    helper.assertTrue(data.role(current,winner.getUUID())==SettlementData.Role.LEADER
                            && data.member(current,winner.getUUID()).previousRole==SettlementData.Role.LEADER, "The winner holds both office fields");
                    helper.assertTrue(actors.stream().filter(y -> data.role(current,y.getUUID())==SettlementData.Role.LEADER).count()==1,
                            "Exactly one loaded identity is leader");
                    returned.settlement().join(current); data.restore(current,returned.getUUID(),level.getGameTime());
                    helper.assertTrue(data.role(current,returned.getUUID())==SettlementData.Role.MEMBER && winner.getUUID().equals(current.leader),
                            "A stale former incumbent cannot reclaim the replacement's office");
                    helper.succeed();
                }
            });
        });
    }

    private static void startLesson(GameTestHelper helper, YukkuriEntity learner, net.minecraft.world.entity.player.Player teacher, boolean waiting) {
        teacher.moveTo(learner.getX(),learner.getY(),learner.getZ()+1);
        var personality=learner.life().personality().save(); personality.putInt("Lessons",waiting?3:0);
        var memories=personality.getList("Players",Tag.TAG_COMPOUND);
        var memory=new CompoundTag(); memory.putUUID("Player",teacher.getUUID()); memory.putInt("Trust",20);
        memories.add(memory); personality.put("Players",memories); learner.life().personality().load(personality);
        helper.assertTrue(!learner.life().hasUrgentNeed() && learner.social().teach(teacher)
                && learner.social().activity().equals(waiting?"wait":"teach"), "Real social API starts the requested busy state");
    }

    private static YukkuriEntity actor(GameTestHelper helper, List<YukkuriEntity> actors, int cognition, int control, long experience, float scale) {
        return actor(helper, actors, cognition, control, experience, scale, ModEntities.REIMU.get(), UUID.randomUUID());
    }
    private static YukkuriEntity actor(GameTestHelper helper, List<YukkuriEntity> actors, int cognition, int control, long experience,
                                       float scale, net.minecraft.world.entity.EntityType<YukkuriEntity> type, UUID id) {
        var y=type.create(helper.getLevel()); y.setUUID(id);
        y.setNoAi(true); y.setNoGravity(true); y.setPersistenceRequired();
        var c=new CompoundTag(); c.putInt("Innate",cognition); y.getPersistentData().put(Cognition.KEY,c);
        var family=y.family().save(); family.putBoolean("Initialized",true); family.putString("Stage","ADULT");
        family.putInt("Version",2); family.putLong("Maturity",1000); family.putLong("Lifespan",100000);
        family.putLong("Age",1000+experience); family.putFloat("Scale",scale); family.putFloat("AdultScale",scale); y.family().load(family);
        var life=y.life().save(); life.putFloat("Nutrition",100); life.putFloat("Energy",100); life.putFloat("Bladder",0); y.life().load(life);
        var personality=y.life().personality().save(); personality.putIntArray("DispositionInnate",new int[]{20,20,50,control});
        personality.putIntArray("DispositionExperience",new int[4]); y.life().personality().load(personality);
        y.setPos(CENTER.getX()+5.5,CENTER.getY(),CENTER.getZ()+5.5);
        helper.assertTrue(helper.getLevel().addFreshEntity(y),"Fixture actor added");
        FamilyData.get(helper.getLevel()).register(y); actors.add(y); return y;
    }
    private SuccessionGameTests() {}
}
