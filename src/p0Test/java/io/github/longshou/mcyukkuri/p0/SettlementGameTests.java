package io.github.longshou.mcyukkuri.p0;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.life.YukkuriConfig;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import io.github.longshou.mcyukkuri.settlement.ForageGoal;
import io.github.longshou.mcyukkuri.settlement.SettlementData;
import io.github.longshou.mcyukkuri.settlement.SettlementComposition;
import net.minecraft.util.RandomSource;
import io.github.longshou.mcyukkuri.settlement.SettlementSpawner;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@PrefixGameTestTemplate(false)
public final class SettlementGameTests {
    private record Check(String name, boolean pass) {}
    private final List<Check> checks = new ArrayList<>();
    private static final BlockPos ORIGIN = new BlockPos(656, 65, 8);
    private static final AABB AREA = new AABB(623, 62, -33, 689, 73, 49);
    private void check(String name, boolean pass) { checks.add(new Check(name, pass)); }
    @GameTest(templateNamespace=P0Mod.ID, template="empty", timeoutTicks=600, batch="settlements")
    public static void settlementAcceptance(GameTestHelper helper) {
        var test = new SettlementGameTests(); var level = helper.getLevel();
        ShelterGameTests.daylightFixture(level);
        for (int x = 39; x <= 42; x++) for(int z=-2;z<=2;z++) GameTestChunks.setForced(level,x, z, true);
        for (int x = 624; x < 688; x++) for (int z = -32; z < 48; z++) {
            level.setBlockAndUpdate(new BlockPos(x,64,z), Blocks.GRASS_BLOCK.defaultBlockState());
            for (int y = 65; y <= 71; y++) level.setBlockAndUpdate(new BlockPos(x,y,z), Blocks.AIR.defaultBlockState());

        }
        helper.startSequence().thenWaitUntil(() -> {
            for (int x=39; x<=42; x++) for(int z=-2;z<=2;z++) helper.assertTrue(level.isPositionEntityTicking(new BlockPos(x*16+8,65,z*16+8)), "Waiting for settlement fixture chunks");
        }).thenExecute(() -> {
            try { clear(level); NaturalShelterFixture.region(level,ORIGIN); test.run(level); }
            catch (Throwable error) { test.check("Unexpected exception: " + error, false); }
            finally {
                clear(level);
                for (int x = 39; x <= 42; x++) for(int z=-2;z<=2;z++) GameTestChunks.setForced(level,x,z,false);
            }
            P0Control.write("settlement-checks", test.checks);
            var failed = test.checks.stream().filter(c -> !c.pass).toList();
            if (failed.isEmpty()) helper.succeed(); else helper.fail(failed.toString());
        });
    }
    private static void clear(ServerLevel level) {
        clearArea(level,AREA);
    }
    private static void clearArea(ServerLevel level,AABB area) {
        for(var pos:BlockPos.betweenClosed(BlockPos.containing(area.minX,65,area.minZ),BlockPos.containing(area.maxX,67,area.maxZ))) {
            if(level.getBlockEntity(pos) instanceof io.github.longshou.mcyukkuri.settlement.ShelterBlockEntity home)home.rollback();
            else if(level.getBlockState(pos).is(io.github.longshou.mcyukkuri.registry.ModBlocks.WILD_LATRINE.get()))level.setBlockAndUpdate(pos,Blocks.AIR.defaultBlockState());
        }
        level.getEntitiesOfClass(Entity.class,area,e->e instanceof YukkuriEntity||e instanceof ItemEntity).forEach(Entity::discard);
    }
    private void run(ServerLevel level) {
        var sizes = new java.util.HashSet<Integer>();
        boolean single = false, childless = false, multiple = false;
        for (int seed = 0; seed < 128; seed++) {
            var plan = SettlementComposition.sample(SettlementSpawner.Preset.FAMILY, RandomSource.create(seed));
            var h = plan.households().getFirst(); sizes.add(h.size());
            single |= h.parents() == 1; childless |= h.children() == 0; multiple |= h.children() > 1;
        }
        check("family sampling includes variable sizes, single parents, childless couples and multiple children", sizes.size() >= 4 && single && childless && multiple);
        for (var preset : SettlementSpawner.Preset.values()) {
            var members = SettlementSpawner.spawn(level, ORIGIN, preset);
            boolean forest = preset == SettlementSpawner.Preset.FOREST || preset == SettlementSpawner.Preset.FOREST_LED;
            check(preset + " variable population", preset == SettlementSpawner.Preset.WANDERER ? members.size() == 1
                    : preset == SettlementSpawner.Preset.BANDITS ? members.size() >= 3 && members.size() <= 4
                    : forest ? members.size() >= 12 && members.size() <= 85 : members.size() >= 2 && members.size() <= 7);
            if (members.isEmpty()) { clear(level); continue; }
            members.forEach(e -> e.setNoAi(true));
            if (preset == SettlementSpawner.Preset.WANDERER) {
                check("wanderer has no artificial group or ancestry", members.getFirst().settlement().groupId() == null && members.getFirst().family().mother() == null);
                clear(level); continue;
            }
            var group = members.getFirst().settlement().group();
            check(preset + " shared durable group", group != null && members.stream().allMatch(e -> group.id.equals(e.settlement().groupId())));
            if (preset == SettlementSpawner.Preset.BANDITS) {
                var data = SettlementData.get(level);
                check("BANDITS remain adult roaming members without homes or family records", group.kind == SettlementData.Kind.BANDITS && group.leader == null
                        && members.stream().allMatch(e -> {
                            var member = data.existingMember(group,e.getUUID());
                            return e.family().adult() && e.life().home() == null && !e.life().naturalHome()
                                    && e.family().mother() == null && e.family().father() == null && e.family().partner() == null
                                    && FamilyData.get(level).person(e.getUUID()) == null && member != null && member.role == SettlementData.Role.MEMBER;
                        }));
                CompoundTag saved = new CompoundTag(); members.getFirst().saveWithoutId(saved);
                var copy = ModEntities.forVariant(members.getFirst().getVariant()).create(level); copy.load(saved);
                check("BANDITS entity NBT keeps group without a home", group.id.equals(copy.settlement().groupId()) && copy.life().home() == null);
                var restored = SettlementData.load(data.save(new CompoundTag(), level.registryAccess()), level.registryAccess()).group(group.id);
                check("world NBT keeps group kind center and leader", restored != null && restored.center.equals(group.center) && restored.kind == group.kind
                        && java.util.Objects.equals(restored.leader,group.leader));
                clear(level); continue;
            }
            check(preset + " real covered reachable homes", members.stream().allMatch(e -> e.life().homeStand() != null && e.life().naturalHome()));
            var householdIds = new java.util.HashSet<String>();
            for (var adult : members.stream().filter(e -> e.family().adult() && !e.getUUID().equals(group.leader)).toList()) {
                String id = adult.getUUID().toString(), partner = adult.family().partner() == null ? id : adult.family().partner().toString();
                householdIds.add(id.compareTo(partner) < 0 ? id : partner);
            }
            check(preset + " distinct household count", forest ? householdIds.size() >= 6 && householdIds.size() <= 12 : householdIds.size() == 1);
            for (var child : members.stream().filter(e -> !e.family().adult()).toList()) {
                var mother = (YukkuriEntity)level.getEntity(child.family().mother());
                var father = child.family().father() == null ? null : (YukkuriEntity)level.getEntity(child.family().father());
                check("generated child keeps actual single or dual parents and species inheritance", mother != null
                        && mother.family().caresFor(child)
                        && (father == null ? child.family().father() == null && mother.family().partner() == null
                            && child.getVariant() == mother.getVariant()
                            : father.family().caresFor(child) && father.getUUID().equals(mother.family().partner())
                            && (child.getVariant() == mother.getVariant() || child.getVariant() == father.getVariant()))
                        && FamilyData.get(level).related(child.getUUID(), mother.getUUID()));
                CompoundTag childTag = new CompoundTag(); child.saveWithoutId(childTag);
                var loadedChild = ModEntities.forVariant(child.getVariant()).create(level); loadedChild.load(childTag);
                check("child ancestry survives NBT including absent second parent", java.util.Objects.equals(loadedChild.family().father(), child.family().father())
                        && child.family().mother().equals(loadedChild.family().mother()));
            }
            check(preset + " optional leader", preset == SettlementSpawner.Preset.FOREST_LED
                    ? group.leader != null && level.getEntity(group.leader) instanceof YukkuriEntity leader && leader.getVariant() == YukkuriVariant.PATCHOULI
                    : group.leader == null);
            CompoundTag saved = new CompoundTag(); members.getFirst().saveWithoutId(saved);
            var copy = ModEntities.forVariant(members.getFirst().getVariant()).create(level); copy.load(saved);
            check("entity NBT keeps group and home", group.id.equals(copy.settlement().groupId()) && copy.life().home().equals(members.getFirst().life().home()));
            var restored = SettlementData.load(SettlementData.get(level).save(new CompoundTag(), level.registryAccess()), level.registryAccess()).group(group.id);
            check("world NBT keeps group kind center and leader", restored != null && restored.center.equals(group.center) && restored.kind == group.kind
                    && java.util.Objects.equals(restored.leader,group.leader));
            if (preset == SettlementSpawner.Preset.FOREST) {
                var mother = members.stream().filter(e -> e.family().adult() && e.family().partner() != null).min(java.util.Comparator.comparingInt(e->e.habitat().shelter().occupants())).orElseThrow();
                var father = (YukkuriEntity)level.getEntity(mother.family().partner());
                father.moveTo(Vec3.atBottomCenterOf(father.habitat().shelter().bed(father.getUUID()))); father.setOnGround(true);
                var relations = io.github.longshou.mcyukkuri.life.YukkuriWorldData.get(level);
                relations.relate(mother.getUUID(),father.getUUID(),30); relations.relate(father.getUUID(),mother.getUUID(),30);
                for (var parent : List.of(mother,father)) {
                    CompoundTag life = parent.life().save(); life.putFloat("Nutrition",100); life.putFloat("Energy",100); life.putFloat("Hygiene",100); parent.life().load(life);
                }
                var families = FamilyData.get(level);
                int oldBirthCap = YukkuriConfig.COMMUNITY_POPULATION_CAP.get();
                try {
                    YukkuriConfig.COMMUNITY_POPULATION_CAP.set(members.size());
                    check("forest birth budget refuses unreserved children at cap", families.conceive(mother,father,FamilyData.BirthKind.STEM,
                            net.minecraft.core.GlobalPos.of(level.dimension(),mother.blockPosition())) == null);
                    YukkuriConfig.COMMUNITY_POPULATION_CAP.set(oldBirthCap);
                    var brood = families.conceive(mother,father,FamilyData.BirthKind.STEM,net.minecraft.core.GlobalPos.of(level.dimension(),mother.blockPosition()));
                    check("large forest retains reproduction headroom above ordinary family cap", members.size() > 16 && brood != null);
                    if (brood != null) {
                        check("unborn forest children consume shared population budget", families.population(level,group.center) == members.size() + brood.unborn());
                        var home=mother.habitat().shelter();
                        boolean moved=families.relocate(brood,level,home.entrance().relative(home.facing(),5));
                        check("mother outings retain unborn sleeping-place reservations",moved&&!home.suitableForYoung(home.capacity()-home.occupants()));
                        var familyCopy=FamilyData.load(families.save(new CompoundTag(),level.registryAccess()),level.registryAccess());
                        check("unborn sleeping-place reservations survive NBT",familyCopy.reservedForResidents(home::owns)==brood.unborn());
                        families.cancel(brood);
                    }
                } finally { YukkuriConfig.COMMUNITY_POPULATION_CAP.set(oldBirthCap); }
            }
            if (group.leader != null) {
                var leader = (YukkuriEntity)level.getEntity(group.leader);
                var member = members.getFirst();
                long daytime = level.getDayTime();
                CompoundTag leaderLife = leader.life().save();
                try {
                    level.setDayTime(11500);
                    var entry=leader.habitat().shelter().entrance();
                    leader.moveTo(Vec3.atBottomCenterOf(entry));leader.setOnGround(true);
                    for(var candidate:BlockPos.betweenClosed(entry.offset(-5,0,-5),entry.offset(5,0,5))) {
                        if(candidate.distSqr(entry)<4||!member.life().dryStand(candidate))continue;
                        member.moveTo(Vec3.atBottomCenterOf(candidate));member.setOnGround(true);
                        if(member.hasLineOfSight(leader)&&member.life().reachable(entry,false))break;
                    }
                    member.settlement().load(member.settlement().save());
                    check("recognized visible leader can gather members at dusk", member.settlement().canUse());
                    Vec3 nearby = member.position(); member.moveTo(nearby.add(60,0,0));
                    check("transport beyond local area interrupts a pending return", !member.settlement().canContinue());
                    member.moveTo(nearby); member.settlement().stop();
                    member.settlement().load(member.settlement().save());
                    CompoundTag lost = leader.life().save(); lost.putBoolean("Accessory", false); leader.life().load(lost);
                    check("missing accessory prevents automatic leader recognition", !member.settlement().canUse());
                    leader.life().load(leaderLife); member.settlement().load(member.settlement().save());
                    CompoundTag hungry = member.life().save(); hungry.putFloat("Nutrition", 10); member.life().load(hungry);
                    check("urgent hunger takes precedence over leader gathering", !member.settlement().canUse());
                } finally { level.setDayTime(daytime); leader.life().load(leaderLife); }
                FamilyData.get(level).register(leader);
                UUID identity = group.leader;
                leader.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
                SettlementData.get(level).refreshLeader(level, group);
                check("unloaded leader is not declared dead", identity.equals(group.leader));
                FamilyData.get(level).died(leader);
                SettlementData.get(level).refreshLeader(level, group);
                check("known leader death leaves forest community leaderless", group.leader == null && group.kind == SettlementData.Kind.FOREST);
            }
            clear(level);
        }
        // Cap refusal must not produce a partial household.
        int oldCap = YukkuriConfig.COMMUNITY_SPAWN_CAP.get();
        try {
            YukkuriConfig.COMMUNITY_SPAWN_CAP.set(1);
            check("population cap refuses entire forest", SettlementSpawner.spawn(level, ORIGIN, SettlementSpawner.Preset.FOREST).isEmpty()
                    && level.getEntitiesOfClass(YukkuriEntity.class, AREA).isEmpty());
        } finally { YukkuriConfig.COMMUNITY_SPAWN_CAP.set(oldCap); }
        var data = new SettlementData();
        check("negative coordinates use stable region cells", data.discover(new BlockPos(-1,0,-1)) && !data.discover(new BlockPos(-64,0,-64))
                && data.discover(BlockPos.ZERO));
        var roundtrip = SettlementData.load(data.save(new CompoundTag(), level.registryAccess()), level.registryAccess());
        check("reload never replenishes discovered area", !roundtrip.discover(new BlockPos(-32,0,-32)));
        level.setBlockAndUpdate(ORIGIN,Blocks.STONE.defaultBlockState());
        check("occupied site refuses entire shelter-dependent community", SettlementSpawner.spawn(level, ORIGIN, SettlementSpawner.Preset.FOREST).isEmpty());
        level.setBlockAndUpdate(ORIGIN,Blocks.AIR.defaultBlockState());

        SettlementSocietyChecks.run(level, ORIGIN, this::check);

        var grazer = ModEntities.REIMU.get().create(level); grazer.moveTo(Vec3.atBottomCenterOf(ORIGIN)); grazer.setOnGround(true); grazer.setNoAi(true); level.addFreshEntity(grazer);
        CompoundTag life = grazer.life().save(); life.putFloat("Nutrition", 50); grazer.life().load(life);
        level.setBlockAndUpdate(ORIGIN, Blocks.SHORT_GRASS.defaultBlockState());
        boolean grief = level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING);
        try {
            level.getGameRules().getRule(GameRules.RULE_MOBGRIEFING).set(false, level.getServer());
            check("mobGriefing false preserves wild food", !new ForageGoal(grazer).canUse() && level.getBlockState(ORIGIN).is(Blocks.SHORT_GRASS));
            level.getGameRules().getRule(GameRules.RULE_MOBGRIEFING).set(true, level.getServer());
            ForageGoal forage = new ForageGoal(grazer);
            check("wild grass is reachable food", forage.canUse()); forage.tick();
            check("grazing transfers one real plant to mouth before nutrition", level.getBlockState(ORIGIN).isAir()
                    && grazer.life().mouth().is(Items.SHORT_GRASS) && grazer.life().mouth().getCount() == 1 && grazer.life().nutrition() == 50);
            check("same plant cannot feed twice", !new ForageGoal(grazer).canUse());
        } finally { level.getGameRules().getRule(GameRules.RULE_MOBGRIEFING).set(grief, level.getServer()); }
        grazer.finalizeSpawn(level, level.getCurrentDifficultyAt(ORIGIN), MobSpawnType.SPAWN_EGG, null);
        check("spawn egg does not trigger a natural community", !grazer.settlement().save().getBoolean("PendingNatural"));
        grazer.finalizeSpawn(level, level.getCurrentDifficultyAt(ORIGIN), MobSpawnType.NATURAL, null);
        check("natural spawn queues safe server-tick discovery", grazer.settlement().save().getBoolean("PendingNatural"));
        boolean communities = YukkuriConfig.COMMUNITIES.get();
        try {
            YukkuriConfig.COMMUNITIES.set(false); grazer.setNoAi(false); grazer.settlement().tick();
            check("disabled discovery clears pending seed without generating companions", !grazer.settlement().save().getBoolean("PendingNatural")
                    && grazer.settlement().groupId() == null && level.getEntitiesOfClass(YukkuriEntity.class, AREA).size() == 1);
        } finally { YukkuriConfig.COMMUNITIES.set(communities); }
    }

    @GameTest(templateNamespace=P0Mod.ID, template="empty", timeoutTicks=700, batch="settlement_movement")
    public static void settlementReturn(GameTestHelper helper) {
        ServerLevel level = helper.getLevel(); BlockPos origin = new BlockPos(816,65,8);
        ShelterGameTests.daylightFixture(level);
        AABB area = new AABB(767,62,-49,865,73,65);
        for (int x=48; x<=53; x++) for(int z=-3;z<=3;z++) GameTestChunks.setForced(level,x,z,true);
        for (int x=768; x<864; x++) for (int z=-48; z<64; z++) {
            level.setBlockAndUpdate(new BlockPos(x,64,z), Blocks.GRASS_BLOCK.defaultBlockState());
            for (int y=65; y<=71; y++) level.setBlockAndUpdate(new BlockPos(x,y,z), Blocks.AIR.defaultBlockState());

        }
        YukkuriEntity[] subject = new YukkuriEntity[1];
        double[] initial = new double[1];
        helper.startSequence().thenWaitUntil(() -> {
            for (int x=48; x<=53; x++) for(int z=-3;z<=3;z++) helper.assertTrue(
                    level.isPositionEntityTicking(new BlockPos(x*16+8,65,z*16+8)),
                    "Waiting for movement chunk " + x + "," + z + "; forced="
                            + level.getForcedChunks().contains(net.minecraft.world.level.ChunkPos.asLong(x,z)));
        })
                .thenExecute(() -> {
                    clearArea(level,area);
                    NaturalShelterFixture.region(level,origin);
                    var members = SettlementSpawner.spawn(level, origin, SettlementSpawner.Preset.FOREST_LED);
                    helper.assertTrue(members.size() >= 13, "Complete forest fixture, population=" + FamilyData.get(level).population(level,origin)
                            + ", loaded=" + level.getEntitiesOfClass(YukkuriEntity.class,area).size());
                    members.forEach(e -> e.setNoAi(true));
                    subject[0] = (YukkuriEntity)level.getEntity(members.getFirst().settlement().group().leader);
                    subject[0].setNoAi(false);
                    BlockPos home = subject[0].life().homeStand();
                    CompoundTag life = subject[0].life().save(); life.putFloat("Nutrition",100); life.putFloat("Energy",100); subject[0].life().load(life);
                    boolean started=false;
                    for(var candidate:BlockPos.betweenClosed(origin.offset(-30,0,-30),origin.offset(30,0,30))) {
                        if(candidate.distSqr(home)<=196||candidate.distSqr(home)>16*16||candidate.distSqr(origin)<=196||!subject[0].life().dryStand(candidate))continue;
                        subject[0].moveTo(Vec3.atBottomCenterOf(candidate));subject[0].setOnGround(true);
                        if(!subject[0].life().reachable(home,false))continue;
                        subject[0].settlement().load(subject[0].settlement().save());
                        if(subject[0].settlement().canUse()){started=true;break;}
                    }
                    initial[0] = subject[0].distanceToSqr(Vec3.atBottomCenterOf(subject[0].life().homeStand()));
                    helper.assertTrue(started, "Member outside community starts a real path home");
                }).thenWaitUntil(() -> helper.assertTrue(subject[0] != null
                        && subject[0].distanceToSqr(Vec3.atBottomCenterOf(subject[0].life().homeStand())) < 9, "Waiting for actual navigation back to community"))
                .thenExecute(() -> {
                    P0Control.write("settlement-movement-checks", List.of(new Check("loaded server AI returns distant member without teleport", true),
                            new Check("return traverses more than fourteen blocks", initial[0] > 196)));
                    clearArea(level,area);
                    for (int x=48; x<=53; x++) for(int z=-3;z<=3;z++) GameTestChunks.setForced(level,x,z,false);
                }).thenSucceed();
    }
}
