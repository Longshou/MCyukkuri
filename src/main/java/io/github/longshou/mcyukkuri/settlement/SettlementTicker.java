package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Current-tick participation, not another membership ledger. Registered explicitly by MCYukkuri. */
public final class SettlementTicker {
    private record GroupKey(ServerLevel level, SettlementData.Group group) {}
    private record Submission(YukkuriEntity entity, boolean sampled, boolean migration) {}
    private static final class Batch {
        final GroupKey key;
        final List<Submission> members = new ArrayList<>();
        final Set<YukkuriEntity> identities = Collections.newSetFromMap(new IdentityHashMap<>());
        Batch(GroupKey key) { this.key = key; }
    }
    private static final class Frame {
        final int tick;
        final Map<GroupKey, Batch> groups = new LinkedHashMap<>();
        boolean drained;
        Frame(int tick) { this.tick = tick; }
    }
    private static final Map<MinecraftServer, Frame> FRAMES = new IdentityHashMap<>();

    static void submit(YukkuriEntity entity, SettlementData.Group group) {
        if (!(entity.level() instanceof ServerLevel level)) return;
        var key = new GroupKey(level, group);
        if (!current(key, entity)) return;
        var server = level.getServer();
        var frame = FRAMES.get(server);
        if (frame == null || frame.tick != server.getTickCount()) {
            frame = new Frame(server.getTickCount());
            FRAMES.put(server, frame);
        }
        if (frame.drained) return;
        var batch = frame.groups.computeIfAbsent(key, Batch::new);
        if (batch.identities.add(entity)) batch.members.add(new Submission(entity, entity.tickCount % 100 == 0,
                !entity.wintering().active() && !entity.independence().householdOuting()));
    }

    private static boolean current(GroupKey key, YukkuriEntity entity) {
        var level = key.level();
        return level.getServer().getLevel(level.dimension()) == level && entity.level() == level
                && entity.isAlive() && !entity.isRemoved() && !entity.isNoAi()
                && level.getEntity(entity.getUUID()) == entity && entity.settlement().group() == key.group();
    }

    /** Use the roster only to filter identities; its map order never becomes scheduling order. */
    private static List<Submission> members(Batch batch) {
        var submitted = batch.members.stream().filter(s -> current(batch.key, s.entity())).toList();
        if (submitted.isEmpty()) return List.of();
        AABB box = submitted.getFirst().entity().getBoundingBox();
        for (var s : submitted) box = box.minmax(s.entity().getBoundingBox());
        Set<YukkuriEntity> visible = Collections.newSetFromMap(new IdentityHashMap<>());
        visible.addAll(LoadedGroupMembers.query(batch.key.level(), batch.key.group().id, box, batch.identities::contains));
        return submitted.stream().filter(s -> visible.contains(s.entity())).toList();
    }

    private static boolean migrationSample(GroupKey key, Submission s) {
        return s.sampled() && s.migration() && current(key, s.entity())
                && !s.entity().wintering().active() && !s.entity().independence().householdOuting();
    }

    private static boolean leaderSample(GroupKey key, SettlementData data, Submission s) {
        return s != null && s.sampled() && current(key, s.entity()) && CommunityRules.ruled(key.group())
                && s.entity().getUUID().equals(data.activeLeader(key.group()));
    }

    public static void tick(ServerTickEvent.Post event) {
        var server = event.getServer();
        var frame = FRAMES.get(server);
        if (frame == null || frame.drained) return;
        if (frame.tick != server.getTickCount()) { FRAMES.remove(server); return; }
        frame.drained = true;
        try {
            for (var batch : List.copyOf(frame.groups.values())) tickGroup(batch);
        } finally {
            frame.groups.clear();
        }
    }

    private static void tickGroup(Batch batch) {
        var members = members(batch);
        if (members.isEmpty()) return;
        var key = batch.key;
        var level = key.level();
        var group = key.group();
        var data = SettlementData.get(level);
        long now = level.getGameTime(), previous = group.lastSuccessionSample;
        boolean succession = LeaderSuccession.due(group, now);
        if (succession || members.stream().anyMatch(Submission::sampled)) data.refreshLeader(level, group);

        var migrants = members.stream().filter(s -> migrationSample(key, s)).toList();
        if (!migrants.isEmpty()) {
            if (!Migration.moving(group)) SettlementLife.seasonal(level, group);
            if (group.kind != SettlementData.Kind.BANDITS) {
                Migration.decay(data, group, now);
                for (var s : migrants) if (migrationSample(key, s)) Migration.observe(data, s.entity(), group, now);
                var first = migrants.stream().filter(s -> migrationSample(key, s)).findFirst().orElse(null);
                var leader = migrants.stream().filter(s -> migrationSample(key, s)
                        && s.entity().getUUID().equals(group.leader)).findFirst().orElse(null);
                // A timeout belongs to the first real caller; otherwise only the real leader can review/arrive.
                var caller = Migration.moving(group) && Migration.expired(group, now) ? first : leader;
                if (caller != null) Migration.advance(level, data, caller.entity(), group, now);
            }
        }

        var leader = members.stream().filter(s -> leaderSample(key, data, s)).findFirst().orElse(null);
        if (leaderSample(key, data, leader)) CommunityRules.tickLeader(leader.entity(), group);
        if (leaderSample(key, data, leader)) Rations.tickLeader(leader.entity(), group);
        if (leaderSample(key, data, leader)) CommonHunt.tickLeader(leader.entity(), group);

        // Succession is eligible every tick with a real participant, independently of the 100-tick sample.
        if (succession && members.stream().anyMatch(s -> current(key, s.entity()))) {
            group.lastSuccessionSample = now;
            LeaderSuccession.sample(level, data, group, now, previous);
        }
    }

    public static void unloaded(LevelEvent.Unload event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        var frame = FRAMES.get(level.getServer());
        if (frame != null) frame.groups.keySet().removeIf(key -> key.level() == level);
    }

    public static void stopped(ServerStoppedEvent event) { FRAMES.remove(event.getServer()); }
    private SettlementTicker() {}
}
