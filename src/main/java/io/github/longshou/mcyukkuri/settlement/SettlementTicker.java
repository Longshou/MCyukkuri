package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
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
    private record Submission(YukkuriEntity entity, boolean sampled, boolean migration) {}
    private static final class Batch {
        final ServerLevel level;
        final SettlementData.Group group;
        final List<Submission> members = new ArrayList<>();
        final Set<YukkuriEntity> identities = Collections.newSetFromMap(new IdentityHashMap<>());
        Batch(ServerLevel level, SettlementData.Group group) { this.level = level; this.group = group; }
    }
    private static final class Frame {
        final int tick;
        final List<Batch> groups = new ArrayList<>();
        final Map<ServerLevel, Map<SettlementData.Group, Batch>> byLevel = new IdentityHashMap<>();
        boolean drained;
        Frame(int tick) { this.tick = tick; }
        Batch batch(ServerLevel level, SettlementData.Group group) {
            var batches = byLevel.computeIfAbsent(level, unused -> new IdentityHashMap<>());
            var batch = batches.get(group);
            if (batch != null) return batch;
            batch = new Batch(level, group);
            batches.put(group, batch);
            groups.add(batch);
            return batch;
        }
        void remove(ServerLevel level) {
            if (byLevel.remove(level) != null) groups.removeIf(batch -> batch.level == level);
        }
        void clear() { groups.clear(); byLevel.clear(); }
    }
    private static final Map<MinecraftServer, Frame> FRAMES = new IdentityHashMap<>();

    static void submit(YukkuriEntity entity, SettlementData.Group group) {
        if (!(entity.level() instanceof ServerLevel level)) return;
        if (!current(level, group, entity)) return;
        var server = level.getServer();
        var frame = FRAMES.get(server);
        if (frame == null || frame.tick != server.getTickCount()) {
            frame = new Frame(server.getTickCount());
            FRAMES.put(server, frame);
        }
        if (frame.drained) return;
        var batch = frame.batch(level, group);
        if (batch.identities.add(entity)) batch.members.add(new Submission(entity, entity.tickCount % 100 == 0,
                !entity.wintering().active() && !entity.independence().householdOuting()));
    }

    private static boolean current(ServerLevel level, SettlementData.Group group, YukkuriEntity entity) {
        return level.getServer().getLevel(level.dimension()) == level && entity.level() == level
                && entity.isAlive() && !entity.isRemoved() && !entity.isNoAi()
                && level.getEntity(entity.getUUID()) == entity && entity.settlement().group() == group;
    }

    /** Use the roster only to filter identities; its map order never becomes scheduling order. */
    private static List<Submission> members(Batch batch) {
        var submitted = new ArrayList<Submission>(batch.members.size());
        AABB box = null;
        for (var submission : batch.members) {
            if (!current(batch.level, batch.group, submission.entity())) continue;
            submitted.add(submission);
            box = box == null ? submission.entity().getBoundingBox() : box.minmax(submission.entity().getBoundingBox());
        }
        if (submitted.isEmpty()) return List.of();
        Set<YukkuriEntity> visible = Collections.newSetFromMap(new IdentityHashMap<>());
        visible.addAll(LoadedGroupMembers.query(batch.level, batch.group.id, box, batch.identities::contains));
        int kept = 0;
        for (var submission : submitted) if (visible.contains(submission.entity())) submitted.set(kept++, submission);
        if (kept == 0) return List.of();
        submitted.subList(kept, submitted.size()).clear();
        return submitted;
    }

    private static boolean migrationSample(Batch batch, Submission s) {
        return s.sampled() && s.migration() && current(batch.level, batch.group, s.entity())
                && !s.entity().wintering().active() && !s.entity().independence().householdOuting();
    }

    private static boolean leaderSample(Batch batch, SettlementData data, Submission s) {
        return s != null && s.sampled() && current(batch.level, batch.group, s.entity()) && CommunityRules.ruled(batch.group)
                && s.entity().getUUID().equals(data.activeLeader(batch.group));
    }

    public static void tick(ServerTickEvent.Post event) {
        var server = event.getServer();
        var frame = FRAMES.get(server);
        if (frame == null || frame.drained) return;
        if (frame.tick != server.getTickCount()) { FRAMES.remove(server); return; }
        frame.drained = true;
        try {
            for (var batch : List.copyOf(frame.groups)) tickGroup(batch);
        } finally {
            frame.clear();
        }
    }

    private static void tickGroup(Batch batch) {
        var members = members(batch);
        if (members.isEmpty()) return;
        var level = batch.level;
        var group = batch.group;
        var data = SettlementData.get(level);
        long now = level.getGameTime(), previous = group.lastSuccessionSample;
        boolean succession = LeaderSuccession.due(group, now);
        boolean sampled = false;
        for (var member : members) if (member.sampled()) { sampled = true; break; }
        if (succession || sampled) data.refreshLeader(level, group);

        List<Submission> migrants = null;
        for (var member : members) if (migrationSample(batch, member)) {
            if (migrants == null) migrants = new ArrayList<>();
            migrants.add(member);
        }
        if (migrants != null) {
            if (!Migration.moving(group)) SettlementLife.seasonal(level, group);
            if (group.kind != SettlementData.Kind.BANDITS) {
                Migration.decay(data, group, now);
                for (var member : migrants) if (migrationSample(batch, member))
                    Migration.observe(data, member.entity(), group, now);
                Submission first = null, leader = null;
                for (var member : migrants) if (migrationSample(batch, member)) {
                    if (first == null) first = member;
                    if (leader == null && member.entity().getUUID().equals(group.leader)) leader = member;
                }
                // A timeout belongs to the first real caller; otherwise only the real leader can review/arrive.
                var caller = Migration.moving(group) && Migration.expired(group, now) ? first : leader;
                if (caller != null) Migration.advance(level, data, caller.entity(), group, now);
            }
        }

        Submission leader = null;
        for (var member : members) if (leaderSample(batch, data, member)) { leader = member; break; }
        if (leaderSample(batch, data, leader)) CommunityRules.tickLeader(leader.entity(), group);
        if (leaderSample(batch, data, leader)) Rations.tickLeader(leader.entity(), group);
        if (leaderSample(batch, data, leader)) CommonHunt.tickLeader(leader.entity(), group);

        // Succession is eligible every tick with a real participant, independently of the 100-tick sample.
        boolean current = false;
        for (var member : members) if (current(level, group, member.entity())) { current = true; break; }
        if (succession && current) {
            group.lastSuccessionSample = now;
            LeaderSuccession.sample(level, data, group, now, previous);
        }
    }

    public static void unloaded(LevelEvent.Unload event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        var frame = FRAMES.get(level.getServer());
        if (frame != null) frame.remove(level);
    }

    public static void stopped(ServerStoppedEvent event) { FRAMES.remove(event.getServer()); }
    private SettlementTicker() {}
}
