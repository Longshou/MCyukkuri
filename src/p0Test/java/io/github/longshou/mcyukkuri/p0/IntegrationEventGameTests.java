package io.github.longshou.mcyukkuri.p0;

import com.mojang.authlib.GameProfile;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.event.WildCards;
import io.github.longshou.mcyukkuri.event.WildEventData;
import io.github.longshou.mcyukkuri.event.WildEventData.Kind;
import io.github.longshou.mcyukkuri.event.WildEventData.Phase;
import io.github.longshou.mcyukkuri.event.WildEventData.Scene;
import io.github.longshou.mcyukkuri.event.WildEvents;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import io.github.longshou.mcyukkuri.settlement.ShelterBlock;
import io.github.longshou.mcyukkuri.settlement.ShelterPlacement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Missing event boundaries, driven exclusively by normal entity/server ticks after setup. */
@PrefixGameTestTemplate(false)
public final class IntegrationEventGameTests {
    private record Check(String name, boolean pass, String detail) {}
    private record LookSample(long tick, int phaseTick, int member, String target, float headYaw,
            float bodyYaw, float entityYaw, double targetYaw, double error, String position) {}
    private record MoveSample(long tick, int phaseTick, String phase, String first, String second,
            String third, double displacement, double separation, boolean navigating) {}
    private static final int X = 28672, Z = 1024, SLOTS = 4, STRIDE = 128;
    private final List<Check> checks = new ArrayList<>();
    private final List<LookSample> looks = new ArrayList<>();
    private final List<MoveSample> moves = new ArrayList<>();
    private final List<YukkuriEntity> actors = new ArrayList<>();
    private ServerLevel level;
    private FakePlayer threat;
    private Villager resident;
    private BlockPos center, bedHead;
    private YukkuriEntity first, second, third;
    private Scene scene;
    private Vec3 alleyStart;
    private Object songToken;
    private int slot;

    @GameTest(templateNamespace = P0Mod.ID, template = "empty", timeoutTicks = 1800, batch = "integration_event_edges")
    public static void acceptance(GameTestHelper helper) {
        var t = new IntegrationEventGameTests();
        t.level = helper.getLevel();
        t.setup();
        var sequence = helper.startSequence().thenWaitUntil(() -> {
            for (int i = 0; i < SLOTS; i++) helper.assertTrue(t.level.isPositionEntityTicking(
                    new BlockPos(X + i * STRIDE + 8, 65, Z + 8)), "waiting for isolated event edge chunks");
        }).thenExecute(() -> t.run("lookout setup", t::lookoutSetup));
        for (int i = 0; i < 90; i++) sequence.thenIdle(1).thenExecute(t::sampleLook);
        sequence.thenExecute(() -> t.run("lookout result", t::lookoutResult))
                .thenExecute(() -> t.run("rural song setup", () -> t.songSetup(false))).thenIdle(5)
                .thenExecute(() -> t.run("rural song start", t::songStart)).thenIdle(30)
                .thenExecute(() -> t.run("rural song refusal", t::ruralSongResult)).thenIdle(210)
                .thenExecute(() -> t.run("rural song completion", () -> t.songCompletion(false)))
                .thenExecute(() -> t.run("village song setup", () -> t.songSetup(true))).thenIdle(5)
                .thenExecute(() -> t.run("village song start", t::songStart)).thenIdle(30)
                .thenExecute(() -> t.run("village attachment", t::villageSongResult)).thenIdle(210)
                .thenExecute(() -> t.run("village song completion", () -> t.songCompletion(true)))
                .thenExecute(() -> t.run("alley setup", t::alleySetup));
        for (int i = 0; i < 260; i++) sequence.thenIdle(1).thenExecute(t::sampleMove);
        sequence.thenExecute(() -> {
            t.run("alley result", t::alleyResult);
            t.cleanup();
            P0Control.write("integration-event-lookout-samples", t.looks);
            P0Control.write("integration-event-alley-samples", t.moves);
            P0Control.write("integration-event-checks", t.checks);
            var failures = t.checks.stream().filter(c -> !c.pass).toList();
            if (failures.isEmpty()) helper.succeed(); else helper.fail(failures.toString());
        });
    }

    private void setup() {
        for (int i = 0; i < SLOTS; i++) {
            int x = X + i * STRIDE;
            for (int dx = 0; dx < 2; dx++) level.setChunkForced(x / 16 + dx, Z / 16, true);
            for (int dx = 0; dx < 32; dx++) for (int dz = 0; dz < 16; dz++) {
                level.setBlockAndUpdate(new BlockPos(x + dx, 64, Z + dz), Blocks.STONE.defaultBlockState());
                for (int y = 65; y < 70; y++) level.setBlockAndUpdate(new BlockPos(x + dx, y, Z + dz), Blocks.AIR.defaultBlockState());
            }
        }
        threat = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "JointEventThreat"));
        level.addNewPlayer(threat);
    }

    private void next() {
        clearActors();
        center = new BlockPos(X + slot++ * STRIDE + 8, 65, Z + 8);
        threat.moveTo(center.getX() + .5, 65, center.getZ() - 4.5);
        scene = null;
    }

    private YukkuriEntity spawn(boolean marisa, double x, double z) {
        var e = (marisa ? ModEntities.MARISA : ModEntities.REIMU).get().create(level);
        e.moveTo(center.getX() + x, 65, center.getZ() + z, 0, 0);
        e.setOnGround(true);
        e.setPersistenceRequired();
        var family = e.family().save();
        family.putBoolean("Initialized", true); family.putString("Stage", "ADULT");
        family.putLong("Age", 20000); family.putLong("Nourishment", 20000); family.putLong("Lifespan", 72000);
        e.family().load(family);
        var life = e.life().save();
        life.putFloat("Nutrition", 80); life.putFloat("Energy", 90); life.putFloat("Hygiene", 100);
        life.putFloat("Bladder", 0); life.putInt("RestWait", 1200); life.putInt("SocialWait", 1200);
        e.life().load(life);
        var personality = e.life().personality().save();
        PersonalityFixtures.preferences(personality,new int[]{50, 60, 50, 70, 50, 70});
        e.life().personality().load(personality);
        var cognition = new CompoundTag(); cognition.putInt("Innate", 100); cognition.putString("Origin", "gametest_fixture");
        e.getPersistentData().put(io.github.longshou.mcyukkuri.cognition.Cognition.KEY, cognition);
        var cooldown = new CompoundTag(); cooldown.putInt("Cooldown", 1200); e.groupInteraction().load(cooldown);
        level.addFreshEntity(e); FamilyData.get(level).register(e); actors.add(e);
        return e;
    }

    private void friends() {
        YukkuriWorldData.get(level).relate(first.getUUID(), second.getUUID(), 20);
        YukkuriWorldData.get(level).relate(second.getUUID(), first.getUUID(), 20);
    }

    private void lookoutSetup() {
        next(); first = spawn(false, .5, .5); second = spawn(true, 1.5, .5); friends();
        check("lookout refuses an actually unfeared nearby player", WildCards.begin("lookout", first, List.of(second), threat) == null,
                "fear=" + first.life().personality().fear(threat.getUUID()));
        first.life().personality().hurt(threat.getUUID());
        scene = WildCards.begin("lookout", first, List.of(second), null);
        check("lookout binds the actual nearest feared player", scene != null && threat.getUUID().equals(scene.player)
                && level.getNearestPlayer(first, 6) == threat, "scene=" + state() + ", player=" + threat.getUUID());
    }

    private void sampleLook() {
        if (scene == null || !scene.active()) return;
        // Sample away from turn boundaries: goals used the preceding phase tick this engine tick.
        int withinTurn = scene.phaseTicks % 30;
        if (withinTurn < 12 || withinTurn > 25) return;
        for (int index = 0; index < 2; index++) {
            YukkuriEntity e = index == 0 ? first : second;
            boolean watching = index == (scene.phaseTicks / 30) % 2;
            Entity target = watching ? threat : index == 0 ? second : first;
            double angle = Math.toDegrees(Math.atan2(target.getZ() - e.getZ(), target.getX() - e.getX())) - 90;
            double error = Math.abs(Mth.wrapDegrees(e.yHeadRot - (float) angle));
            looks.add(new LookSample(level.getGameTime(), scene.phaseTicks, index, watching ? "feared_player" : "peer",
                    e.yHeadRot, e.yBodyRot, e.getYRot(), angle, error, e.position().toString()));
        }
    }

    private void lookoutResult() {
        for (int index = 0; index < 2; index++) {
            final int member = index;
            for (String target : List.of("feared_player", "peer")) {
                var samples = looks.stream().filter(s -> s.member == member && s.target.equals(target)).toList();
                check("lookout member " + member + " actually turns toward " + target,
                        samples.stream().filter(s -> s.error <= 12).count() >= 3, samples);
            }
        }
        check("lookout finishes through actual server ticks", scene != null && scene.phase == Phase.COMPLETE, state());
    }

    private void songSetup(boolean village) {
        next(); first = spawn(false, .5, .5); second = spawn(true, 1.5, .5); friends();
        // Keep the five settling ticks away from autonomous event discovery; it can reserve a park race.
        for(var singer:List.of(first,second))singer.tickCount=120-Math.floorMod(singer.getId(),100);
        if (village) {
            bedHead = center.offset(7, 0, 2);
            var bed = Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.EAST);
            level.setBlockAndUpdate(bedHead.west(), bed.setValue(BedBlock.PART, BedPart.FOOT));
            level.setBlockAndUpdate(bedHead, bed.setValue(BedBlock.PART, BedPart.HEAD));
            var home = level.registryAccess().registryOrThrow(Registries.POINT_OF_INTEREST_TYPE).getHolderOrThrow(PoiTypes.HOME);
            level.getPoiManager().add(bedHead, home);
            var claimed = level.getPoiManager().take(p -> p.is(PoiTypes.HOME), (p, at) -> at.equals(bedHead), bedHead, 1);
            resident = EntityType.VILLAGER.create(level);
            resident.moveTo(bedHead.getX() + .5, 65, bedHead.getZ() + 2.5);
            resident.setNoAi(true); resident.setPersistenceRequired();
            resident.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(level.dimension(), bedHead));
            level.addFreshEntity(resident);
            check("village fixture has real bed, resident and occupied HOME POI", claimed.filter(bedHead::equals).isPresent()
                    && level.getBlockState(bedHead).is(Blocks.RED_BED)
                    && level.getPoiManager().getCountInRange(p -> p.is(PoiTypes.HOME), bedHead, 1, PoiManager.Occupancy.IS_OCCUPIED) == 1,
                    "bed=" + bedHead + ",resident=" + resident.getUUID() + ",home=" + resident.getBrain().getMemory(MemoryModuleType.HOME));
        }
        check((village ? "village" : "rural") + " song uses actual nonforest location",
                !WildEvents.forest(first) && level.isCloseToVillage(first.blockPosition(), 2) == village,
                "pos=" + first.blockPosition() + ",forest=" + WildEvents.forest(first) + ",villageDistance=" + level.sectionsToVillage(SectionPos.of(first.blockPosition())));
    }

    private void songStart() {
        check("actual DailyLife song session starts at " + center, first.dailyLife().singTo(second)
                && first.dailyLife().active() && second.dailyLife().active(), first.dailyLife().activity());
        songToken = first.dailyLife().songSessionToken();
    }

    private Scene songScene() {
        return WildEventData.get(level).scenes().stream().filter(s -> s.kind == Kind.CONCERT
                && s.members.contains(first.getUUID())).findFirst().orElse(null);
    }

    private void ruralSongResult() {
        check("rural real song remains active but receives no street event", first.dailyLife().active()
                && first.dailyLife().songSessionToken() == songToken && songScene() == null,
                "pos=" + first.blockPosition() + ",village=" + level.isCloseToVillage(first.blockPosition(), 2) + ",activity=" + first.dailyLife().activity());
    }

    private void villageSongResult() {
        scene = songScene();
        check("village event naturally attaches to the original real DailyLife session", scene != null
                && scene.card.equals("street-performance") && scene.concertToken == songToken
                && first.dailyLife().songSessionToken() == songToken && level.isCloseToVillage(scene.origin.pos(), 2),
                "state=" + state() + ",origin=" + (scene == null ? null : scene.origin) + ",bed=" + bedHead);
    }

    private void songCompletion(boolean village) {
        scene = songScene();
        check((village ? "street" : "rural") + " song completes exactly once through real goals",
                first.dailyLife().songs() == 1 && second.dailyLife().songs() == 1
                        && (village ? scene != null && scene.phase == Phase.COMPLETE && scene.responses >= 3 : scene == null),
                "state=" + state() + ",songs=" + first.dailyLife().songs() + "/" + second.dailyLife().songs());
    }

    private void alleySetup() {
        next(); first = spawn(false, .95, .95); second = spawn(true, .4, .4); third = spawn(false, 1.7, .7);
        var peers = List.of(second, third);
        level.setBlockAndUpdate(center.west(), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(center.north(), Blocks.STONE.defaultBlockState());
        check("two real adjacent walls without residence reject alley corner", WildCards.begin("alley-corner", first, peers, null) == null,
                "inhabited=" + WildEvents.inhabited(first));
        var placed = ShelterPlacement.place(level, new ShelterPlacement.Plan(center.offset(7, 0, -4), ShelterBlock.Kind.CARDBOARD, Direction.SOUTH));
        if (placed == null || !first.habitat().choose(placed.shelter())) throw new IllegalStateException("actual cardboard home refused");
        first.habitat().stop(); first.family().stop(); first.social().stop();
        level.setBlockAndUpdate(center.north(), Blocks.AIR.defaultBlockState());
        check("actual residence with only one wall rejects alley corner", WildCards.begin("alley-corner", first, peers, null) == null, first.habitat().shelter());
        level.setBlockAndUpdate(center.north(), Blocks.STONE.defaultBlockState());
        check("alley corner rejects fewer than three actual members", WildCards.begin("alley-corner", first, List.of(second), null) == null, "two actors");
        scene = WildCards.begin("alley-corner", first, peers, null);
        alleyStart = first.position();
        check("three physical members and real home/walls start alley corner", scene != null && scene.members.size() == 3
                && first.habitat().shelter() == placed.shelter(), "state=" + state() + ",home=" + placed.shelter().getBlockPos() + ",walls=" + center.west() + "/" + center.north());
    }

    private void sampleMove() {
        if (scene == null || alleyStart == null || !scene.active()) return;
        moves.add(new MoveSample(level.getGameTime(), scene.phaseTicks, scene.phase.name(), first.position().toString(), second.position().toString(),
                third.position().toString(), first.position().distanceTo(alleyStart), first.distanceTo(second), !first.getNavigation().isDone()));
    }

    private void alleyResult() {
        check("alley yielding uses a real navigation path", moves.stream().anyMatch(MoveSample::navigating), moves);
        check("first alley member physically yields at least half a block", moves.stream().anyMatch(s -> s.displacement >= .5),
                "start=" + alleyStart + ",end=" + first.position() + ",max=" + moves.stream().mapToDouble(MoveSample::displacement).max().orElse(0));
        check("alley reaches real member separation and completes", scene != null && scene.phase == Phase.COMPLETE
                && moves.stream().anyMatch(s -> s.separation >= 1.5), state());
    }

    private String state() { return scene == null ? "none" : scene.card + ":" + scene.phase + ":" + scene.phaseTicks + ":" + scene.result; }
    private void check(String name, boolean pass, Object detail) { checks.add(new Check(name, pass, String.valueOf(detail))); }
    private void run(String name, Runnable action) {
        try { action.run(); } catch (Throwable error) { check(name + " unexpected exception", false, error); }
        P0Control.write("integration-event-checks", checks);
    }
    private void clearActors() {
        for (var actor : actors) {
            var active = WildEventData.get(level).forMember(actor.getUUID());
            if (active != null && active.active()) WildEvents.finish(level, active, Phase.INTERRUPTED, "integration_fixture_cleanup");
            actor.discard();
        }
        actors.clear();
    }
    private void cleanup() {
        clearActors(); threat.discard();
        if (resident != null) resident.discard();
        if (bedHead != null) level.getPoiManager().remove(bedHead);
        for (int i = 0; i < SLOTS; i++) for (int dx = 0; dx < 2; dx++) level.setChunkForced((X + i * STRIDE) / 16 + dx, Z / 16, false);
    }
}
