package io.github.longshou.mcyukkuri.command;

import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.registry.ModBlocks;
import io.github.longshou.mcyukkuri.settlement.SettlementData;
import io.github.longshou.mcyukkuri.settlement.SettlementOrders;
import io.github.longshou.mcyukkuri.settlement.SettlementSpawner;
import io.github.longshou.mcyukkuri.settlement.ShelterBlockEntity;
import io.github.longshou.mcyukkuri.settlement.ShelterPlacement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/** One persistent, owned outdoor exhibit. All movement, orders and inventory use ordinary gameplay. */
@EventBusSubscriber(modid = MCYukkuri.MOD_ID)
public final class MuseumSettlementSetup {
    private static final String OWNER = "MuseumSettlementRun";
    public record Result(boolean success, String message, int count) {}
    /** Exhibit buttons run from command blocks with output off, so every outcome (and failure reason) is also said to the players. */
    private static Result announce(ServerLevel level, Result result) {
        var line = Component.literal("[聚落] " + result.message()).withStyle(result.success() ? ChatFormatting.GREEN : ChatFormatting.RED);
        level.getServer().getPlayerList().broadcastSystemMessage(line, false);
        return result;
    }
    public static Result start(ServerLevel level, BlockPos center) { return announce(level, startRun(level, center)); }
    public static Result order(ServerLevel level, SettlementOrders.Kind kind) { return announce(level, orderRun(level, kind)); }
    public static Result threat(ServerLevel level, int amount) { return announce(level, threatRun(level, amount)); }
    public static Result migrate(ServerLevel level) { return announce(level, migrateRun(level)); }
    public static Result status(ServerLevel level) { return announce(level, statusRun(level)); }
    public static Result reset(ServerLevel level) { return announce(level, resetRun(level)); }
    private static Result fail(String reason) { return new Result(false, reason, 0); }
    private static Result ok(String message, int count) { return new Result(true, message, count); }
    private record Fixture(BlockState before, boolean shelter) {}

    /** Saved in the overworld so a transported actor cannot evade the singleton run guard. */
    public static final class State extends SavedData {
        private UUID run, group;
        private GlobalPos origin;
        private final Set<UUID> members = new LinkedHashSet<>(), retired = new HashSet<>();
        private final Map<UUID, GlobalPos> groups = new LinkedHashMap<>();
        private final Map<GlobalPos, Fixture> fixtures = new LinkedHashMap<>();
        // Only lives across one actor tick: proves that a newly acquired shelter was created
        // during this actor's own tick, rather than a pre-existing/player-built home it joined.
        private final Map<UUID, Set<BlockPos>> beforeHomes = new HashMap<>();
        public State() {}
        private boolean active() { return run != null; }
        private static State get(ServerLevel level) {
            return level.getServer().overworld().getDataStorage().computeIfAbsent(
                    new Factory<>(State::new, State::load), "mcyukkuri_museum_settlement");
        }
        @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            if (run != null) tag.putUUID("Run", run);
            if (group != null) tag.putUUID("Group", group);
            FamilyData.putPosition(tag, "Origin", origin);
            tag.put("Members", ids(members)); tag.put("Retired", ids(retired));
            var groupTags = new ListTag();
            groups.forEach((id, pos) -> { var value = new CompoundTag(); value.putUUID("Id", id); FamilyData.putPosition(value, "At", pos); groupTags.add(value); });
            tag.put("Groups", groupTags);
            var blocks = new ListTag();
            fixtures.forEach((pos, fixture) -> {
                var value = new CompoundTag(); FamilyData.putPosition(value, "At", pos);
                value.put("Before", NbtUtils.writeBlockState(fixture.before())); value.putBoolean("Shelter", fixture.shelter()); blocks.add(value);
            });
            tag.put("Fixtures", blocks); return tag;
        }
        private static State load(CompoundTag tag, HolderLookup.Provider registries) {
            var state = new State();
            state.run = tag.hasUUID("Run") ? tag.getUUID("Run") : null;
            state.group = tag.hasUUID("Group") ? tag.getUUID("Group") : null;
            state.origin = FamilyData.position(tag, "Origin");
            readIds(tag, "Members", state.members); readIds(tag, "Retired", state.retired);
            for (Tag raw : tag.getList("Groups", Tag.TAG_COMPOUND)) {
                var value = (CompoundTag)raw; var pos = FamilyData.position(value, "At");
                if (pos != null && value.hasUUID("Id")) state.groups.put(value.getUUID("Id"), pos);
            }
            for (Tag raw : tag.getList("Fixtures", Tag.TAG_COMPOUND)) {
                var value = (CompoundTag)raw; var pos = FamilyData.position(value, "At");
                if (pos != null) state.fixtures.put(pos, new Fixture(NbtUtils.readBlockState(
                        registries.lookupOrThrow(Registries.BLOCK), value.getCompound("Before")), value.getBoolean("Shelter")));
            }
            return state;
        }
        private static ListTag ids(Set<UUID> ids) {
            var list = new ListTag();
            for (UUID id : ids) { var tag = new CompoundTag(); tag.putUUID("Id", id); list.add(tag); }
            return list;
        }
        private static void readIds(CompoundTag tag, String key, Set<UUID> ids) {
            for (Tag raw : tag.getList(key, Tag.TAG_COMPOUND)) {
                var item = (CompoundTag)raw; if (item.hasUUID("Id")) ids.add(item.getUUID("Id"));
            }
        }
    }

    private static Result startRun(ServerLevel level, BlockPos center) {
        var state = State.get(level);
        if (state.active()) return fail("已有本轮聚落；先成功重置，不能覆盖登记或复制成员。");
        // The spawner searches 24 blocks out; two extra cells include its latrine placement.
        var before = new HashMap<BlockPos, BlockState>();
        for (var at : BlockPos.betweenClosed(center.offset(-27, -3, -27), center.offset(27, 3, 27))) {
            if (!level.hasChunkAt(at)) return fail("聚落选址区域尚未加载；未创建任何成员。");
            before.put(at.immutable(), level.getBlockState(at));
        }
        var actors = SettlementSpawner.spawn(level, center, SettlementSpawner.Preset.FOREST_LED);
        if (actors.isEmpty()) return fail("真实森林聚落生成失败：检查至少 13 个可达天然洞穴、人口上限和通路。");
        state.run = UUID.randomUUID(); state.origin = GlobalPos.of(level.dimension(), center);
        state.group = actors.getFirst().settlement().groupId();
        state.groups.put(state.group, state.origin);
        for (var actor : actors) {
            state.members.add(actor.getUUID()); actor.getPersistentData().putUUID(OWNER, state.run);
            actor.addTag("pm_settlement"); actor.setPersistenceRequired();
            var home = actor.habitat().shelter();
            if (home != null) recordFixture(level, state, home.getBlockPos(), before.getOrDefault(home.getBlockPos(), Blocks.AIR.defaultBlockState()), true);
        }
        for (var entry : before.entrySet()) {
            var at = entry.getKey();
            if (!entry.getValue().is(ModBlocks.WILD_LATRINE.get()) && level.getBlockState(at).is(ModBlocks.WILD_LATRINE.get()))
                recordFixture(level, state, at, entry.getValue(), false);
        }
        // No food is dropped on the ground and nothing is credited: each household's forage pile (储粮堆, placed
        // beside its entrance by the spawner) holds the starting grass, members stagger their first searches on
        // their own, and the meadow's real grass is the only source. Reset removes unused piles with their shelters.
        state.setDirty();
        return ok("真实聚落已建立；成员自行生活，粮食只存在各户门口的储粮堆，采集与存取看实际状态。", actors.size());
    }

    private static Result orderRun(ServerLevel level, SettlementOrders.Kind kind) {
        var state = State.get(level); var group = group(level, state);
        if (group == null || group.leader == null || !(level.getEntity(group.leader) instanceof YukkuriEntity leader)) return fail("本轮族长未在当前维度加载。");
        for (UUID id : state.members) if (level.getEntity(id) instanceof YukkuriEntity worker
                && leader.settlement().orders().issue(worker, kind))
            return ok("已接受真实 " + kind + " 命令；尚未完成。居民=" + id + "，当前本人粮库="
                    + (worker.habitat().shelter() == null ? 0 : worker.habitat().shelter().food()), 1);
        return fail("没有居民通过实际接单条件：白天、族长 12 格内互认/可见、成年空闲、无急需/冷却，采集还需可用床位。");
    }

    private static Result threatRun(ServerLevel level, int amount) {
        var state = State.get(level); var group = group(level, state);
        if (group == null || amount < 1 || amount > 20) return fail("本轮聚落不可用或威胁数值不在 1–20。");
        SettlementData.get(level).reportThreat(group, group.center.west(8), level.getGameTime(), amount);
        return ok("已记录真实威胁；未授予迁徙完成。", group.threat);
    }

    private static Result migrateRun(ServerLevel level) {
        var state = State.get(level); var group = group(level, state);
        if (group == null || group.leader == null || !(level.getEntity(group.leader) instanceof YukkuriEntity leader))
            return fail("本轮族长未加载。");
        if (group.migrateTo != null) return fail("本轮已在迁徙途中。");
        // The production entry point selects nearest. At this exact unique center it can
        // only select our registered group; a coincident foreign community blocks the call.
        if (SettlementData.get(level).groups().stream().anyMatch(other -> !other.id.equals(group.id) && other.center.equals(group.center)))
            return fail("另一个聚落与本轮中心重合，拒绝使用最近聚落命令。");
        var source = leader.createCommandSourceStack().withPermission(2).withSuppressedOutput().withPosition(Vec3.atBottomCenterOf(group.center));
        level.getServer().getCommands().performPrefixedCommand(source, "yukkuri settlement migrate");
        return group.migrateTo == null ? fail("真实选址未通过；需 32–48 格外已加载且可达的天然洞穴区。")
                : ok("已开始真实迁徙，目标=" + group.migrateTo.toShortString() + "；尚未到达。", 1);
    }

    private static Result statusRun(ServerLevel level) {
        var state = State.get(level); var group = group(level, state);
        if (!state.active()) return fail("没有已登记聚落轮次。");
        int loaded = 0, orders = 0, food = 0;
        for (UUID id : state.members) if (find(level, id) instanceof YukkuriEntity actor) { loaded++; if (actor.settlement().orders().active()) orders++; }
        for (var at : state.fixtures.keySet()) {
            var dimension = level.getServer().getLevel(at.dimension());
            if (dimension != null && dimension.hasChunkAt(at.pos()) && dimension.getBlockEntity(at.pos()) instanceof ShelterBlockEntity home
                    && owned(home.getPersistentData(), state.run)) food += home.food();
        }
        return ok("登记成员=" + state.members.size() + "，已加载=" + loaded + "，执行命令=" + orders + "，登记住所实际粮食=" + food
                + "，迁徙目标=" + (group == null || group.migrateTo == null ? "无" : group.migrateTo.toShortString()), loaded);
    }

    /** All refusal checks run before stopping any goal, releasing a bed, or removing a group. */
    private static Result resetRun(ServerLevel level) {
        var state = State.get(level);
        if (!state.active()) return ok("没有需要重置的轮次。", 0);
        var family = FamilyData.get(level);
        String relatives = discoverDescendants(level, state);
        if (relatives != null) return fail(relatives);
        var actors = new ArrayList<YukkuriEntity>();
        for (UUID id : state.members) {
            if (YukkuriWorldData.get(level).isCaptured(id)) return fail("成员已被收容，整轮保留；先释放同一身份：" + id);
            var entity = find(level, id);
            if (entity instanceof YukkuriEntity actor) {
                if (io.github.longshou.mcyukkuri.human.HumanCommerce.data(actor).hasUUID("Owner"))
                    return fail("成员已被领养，拒绝删除玩家持有的个体；整轮保留：" + id);
                if (actor.family().partner() != null && !state.members.contains(actor.family().partner()))
                    return fail("成员已与外部居民结伴，拒绝删除外部家庭的一员；整轮保留：" + id);
                actors.add(actor); continue;
            }
            var person = family.person(id);
            if (person == null || person.status == FamilyData.Status.ALIVE)
                return fail("成员未加载或身份状态未知，整轮保留：" + id);
            if (person.status == FamilyData.Status.UNBORN) {
                var brood = family.brood(person.brood);
                if (brood == null || brood.detachedHolder != null) return fail("未出生成员的持有关系尚未安全归档，整轮保留：" + id);
            }
        }
        for (var at : state.fixtures.keySet()) {
            var dimension = level.getServer().getLevel(at.dimension());
            if (dimension == null || !dimension.hasChunkAt(at.pos())) return fail("登记住所/设施尚未加载，整轮保留：" + at.pos().toShortString());
            if (dimension.getBlockEntity(at.pos()) instanceof ShelterBlockEntity home && owned(home.getPersistentData(), state.run))
                for (var part : ShelterPlacement.positions(home.getBlockPos(), home.kind()))
                    if (!dimension.hasChunkAt(part)) return fail("住所部分区块尚未加载，整轮保留。");
        }
        for (var at : state.groups.values()) if (level.getServer().getLevel(at.dimension()) == null)
            return fail("登记聚落维度不可用，整轮保留。");

        int count = state.members.size();
        // Cancel only broods whose complete ancestry belongs to this run. Keep tombstones;
        // removing ancestry would allow stale entity NBT to re-enter as a fresh resident.
        for (UUID id : state.members) {
            var person = family.person(id);
            if (person != null && person.brood != null) family.cancel(family.brood(person.brood));
        }
        for (var actor : actors) {
            actor.setNoAi(true); actor.getNavigation().stop();
            actor.settlement().orders().stop(); actor.settlement().duties().cancelAssignment();
            actor.independence().abandon(); actor.habitat().dropCarrying(); actor.habitat().release();
            var dimension = (ServerLevel)actor.level();
            SettlementData.get(dimension).leave(actor.settlement().group(), actor.getUUID(), dimension.getGameTime());
            actor.stopRiding(); actor.discard();
        }
        for (var entry : state.fixtures.entrySet()) {
            var at = entry.getKey(); var dimension = level.getServer().getLevel(at.dimension());
            var blockEntity = dimension.getBlockEntity(at.pos());
            if (blockEntity == null || !owned(blockEntity.getPersistentData(), state.run)) continue;
            if (entry.getValue().shelter()) {
                if (!(blockEntity instanceof ShelterBlockEntity home)) continue;
                for (UUID id : List.copyOf(home.residentIds())) if (state.members.contains(id)) home.release(id);
                if (!home.residentIds().isEmpty()) continue;
                home.rollback();
            } else {
                // A facility shared with a surviving outside resident remains in place.
                if (outsideResidentNear(dimension, state, at.pos())
                        || blockEntity instanceof net.minecraft.world.Container container && !container.isEmpty()) continue;
                dimension.setBlock(at.pos(), Blocks.AIR.defaultBlockState(), 3);
            }
            if (dimension.getBlockState(at.pos()).isAir()) dimension.setBlock(at.pos(), entry.getValue().before(), 3);
        }
        removeEmptyGroups(level, state);
        state.retired.addAll(state.members); state.members.clear(); state.groups.clear(); state.fixtures.clear(); state.beforeHomes.clear();
        state.run = state.group = null; state.origin = null; state.setDirty();
        return ok("本轮成员与专属空住所已重置；外部居民、混居住所及其库存保留。", count);
    }

    private static boolean outsideResidentNear(ServerLevel level, State state, BlockPos pos) {
        int cx = pos.getX() >> 4, cz = pos.getZ() >> 4;
        for (int x = cx - 1; x <= cx + 1; x++) for (int z = cz - 1; z <= cz + 1; z++) {
            var chunk = level.getChunkSource().getChunkNow(x, z);
            if (chunk == null) return true; // Unknown neighbouring residents retain shared facilities.
            for (var block : chunk.getBlockEntities().values()) if (block instanceof ShelterBlockEntity home
                    && home.getBlockPos().distSqr(pos) <= 64
                    && home.residentIds().stream().anyMatch(id -> !state.members.contains(id))) return true;
        }
        return false;
    }
    private static String discoverDescendants(ServerLevel level, State state) {
        var family = FamilyData.get(level); boolean changed;
        do {
            changed = false;
            for (UUID parent : List.copyOf(state.members)) for (UUID id : family.children(parent)) {
                if (state.members.contains(id)) continue;
                var child = family.person(id);
                if (child == null || child.mother != null && !state.members.contains(child.mother)
                        || child.father != null && !state.members.contains(child.father))
                    return "出现与外部居民共同生育的后代，拒绝自动删除家庭；整轮保留。";
                state.members.add(id); changed = true; state.setDirty();
            }
        } while (changed);
        return null;
    }
    private static void removeEmptyGroups(ServerLevel level, State state) {
        for (var entry : state.groups.entrySet()) {
            var dimension = level.getServer().getLevel(entry.getValue().dimension());
            var data = SettlementData.get(dimension);
            var group = data.group(entry.getKey());
            // Dead and otherwise terminal members may have no loaded entity to release them.
            for (UUID id : state.members) data.leave(group, id, dimension.getGameTime());
            for (Tag raw : data.save(new CompoundTag(), dimension.registryAccess()).getList("Groups", Tag.TAG_COMPOUND)) {
                var value = (CompoundTag)raw;
                if (value.hasUUID("Id") && entry.getKey().equals(value.getUUID("Id")) && value.getList("Members", Tag.TAG_COMPOUND).isEmpty()) data.remove(entry.getKey());
            }
        }
    }
    private static Entity find(ServerLevel level, UUID id) {
        for (ServerLevel dimension : level.getServer().getAllLevels()) { var found = dimension.getEntity(id); if (found != null) return found; }
        return null;
    }
    private static SettlementData.Group group(ServerLevel level, State state) {
        return !state.active() || state.origin == null || !state.origin.dimension().equals(level.dimension())
                ? null : SettlementData.get(level).group(state.group);
    }
    private static boolean owned(CompoundTag tag, UUID run) { return run != null && tag.hasUUID(OWNER) && run.equals(tag.getUUID(OWNER)); }
    private static void recordFixture(ServerLevel level, State state, BlockPos pos, BlockState before, boolean shelter) {
        var blockEntity = level.getBlockEntity(pos); if (blockEntity == null) return;
        var at = GlobalPos.of(level.dimension(), pos);
        if (state.fixtures.containsKey(at) && owned(blockEntity.getPersistentData(), state.run)) return;
        blockEntity.getPersistentData().putUUID(OWNER, state.run); blockEntity.setChanged();
        state.fixtures.putIfAbsent(at, new Fixture(before, shelter)); state.setDirty();
    }

    @SubscribeEvent public static void joining(EntityJoinLevelEvent event) {
        if (event.getEntity() instanceof YukkuriEntity actor && event.getLevel() instanceof ServerLevel level
                && State.get(level).retired.contains(actor.getUUID())) event.setCanceled(true);
    }
    @SubscribeEvent public static void beforeTick(EntityTickEvent.Pre event) {
        if (!(event.getEntity() instanceof YukkuriEntity actor) || !(actor.level() instanceof ServerLevel level)) return;
        var state = State.get(level); if (!state.active()) return;
        if (!state.members.contains(actor.getUUID())) {
            var mother = actor.family().mother(); var father = actor.family().father();
            if (mother == null || !state.members.contains(mother) || father != null && !state.members.contains(father)) return;
            state.members.add(actor.getUUID()); actor.getPersistentData().putUUID(OWNER, state.run); state.setDirty();
        }
        if (actor.habitat().shelter() != null && !actor.settlement().migrating() && !actor.independence().building()) return;
        var existing = new HashSet<BlockPos>(); int cx = actor.blockPosition().getX() >> 4, cz = actor.blockPosition().getZ() >> 4;
        for (int x = cx - 1; x <= cx + 1; x++) for (int z = cz - 1; z <= cz + 1; z++) {
            var chunk = level.getChunkSource().getChunkNow(x, z);
            if (chunk != null) for (var block : chunk.getBlockEntities().values())
                if (block instanceof ShelterBlockEntity) existing.add(block.getBlockPos());
        }
        state.beforeHomes.put(actor.getUUID(), existing);
    }
    @SubscribeEvent public static void afterTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof YukkuriEntity actor) || !(actor.level() instanceof ServerLevel level)) return;
        var state = State.get(level); if (!state.active() || !state.members.contains(actor.getUUID())) return;
        var before = state.beforeHomes.remove(actor.getUUID()); var home = actor.habitat().shelter();
        boolean created = before != null && home != null && !before.contains(home.getBlockPos())
                && Math.abs(home.getBlockPos().getX() - actor.blockPosition().getX()) <= 12
                && Math.abs(home.getBlockPos().getZ() - actor.blockPosition().getZ()) <= 12
                && home.residentIds().stream().allMatch(state.members::contains);
        if (created) recordFixture(level, state, home.getBlockPos(), Blocks.AIR.defaultBlockState(), true);
        if (created && owned(home.getPersistentData(), state.run) && actor.settlement().group() != null
                && actor.settlement().group().center.equals(home.getBlockPos())
                && !state.groups.containsKey(actor.settlement().groupId())) {
            state.groups.put(actor.settlement().groupId(), GlobalPos.of(level.dimension(), home.getBlockPos())); state.setDirty();
        }
    }
    private MuseumSettlementSetup() {}
}
