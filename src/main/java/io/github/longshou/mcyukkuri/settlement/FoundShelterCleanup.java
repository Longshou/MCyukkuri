package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/** Idle villagers remove only long-empty cardboard within sight, never a target or an occupied shelter. */
public final class FoundShelterCleanup {
    private static final int ABANDONED = 12000, SIGHT = 8, QUIET = 6;
    private static final SavedData.Factory<History> HISTORY = new SavedData.Factory<>(History::new, History::load);
    private FoundShelterCleanup() {}

    private static final class History extends SavedData {
        private final Set<ChunkPos> areas = new LinkedHashSet<>();
        private long day = -1;

        boolean cleaned(ChunkPos area, long today) {
            return day == today && areas.stream().anyMatch(other -> Math.abs(other.x-area.x) <= 1 && Math.abs(other.z-area.z) <= 1);
        }
        void record(ChunkPos area, long today) {
            if (day != today) { day = today; areas.clear(); }
            areas.add(area); setDirty();
        }
        static History load(CompoundTag tag, HolderLookup.Provider registries) {
            var history = new History();
            history.day = tag.getLong("Day");
            for (long area : tag.getLongArray("Areas")) history.areas.add(new ChunkPos(area));
            return history;
        }
        @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            tag.putLong("Day", day);
            tag.putLongArray("Areas", areas.stream().mapToLong(ChunkPos::toLong).toArray());
            return tag;
        }
    }

    public static void tick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Villager villager) || !(villager.level() instanceof ServerLevel level)
                || villager.tickCount % 100 != 0 || !villager.getBrain().isActive(Activity.IDLE)
                || !villager.getNavigation().isDone() || !EventHooks.canEntityGrief(level, villager)) return;
        var here = villager.blockPosition();
        long day = level.getDayTime() / 24000;
        for (var home : LoadedShelters.within(level, here.offset(-SIGHT,-2,-SIGHT), here.offset(SIGHT,2,SIGHT))) {
            if (home.kind() != ShelterBlock.Kind.CARDBOARD || home.emptyTicks() < ABANDONED || home.occupants() != 0) continue;
            var pos = home.getBlockPos();
            var area = new ChunkPos(pos);
            var done = level.getDataStorage().computeIfAbsent(HISTORY, "mcyukkuri_found_shelter_cleanup");
            if (done.cleaned(area, day)) continue;
            if (!level.getEntitiesOfClass(YukkuriEntity.class, new AABB(pos).inflate(12), y -> y.isAlive()
                    && (y.distanceToSqr(Vec3.atCenterOf(pos)) <= QUIET * QUIET || y.life().targetingFoundShelter(pos))).isEmpty()) continue;
            var hit = level.clip(new ClipContext(villager.getEyePosition(), Vec3.atCenterOf(pos), ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE, villager));
            if (hit.getType() != HitResult.Type.MISS && ShelterBlock.shelter(level, hit.getBlockPos()) != home) continue;
            done.record(area, day);
            villager.swing(InteractionHand.MAIN_HAND);
            GameplayLog.event(villager, "habitat", "found cleared shelter={} empty_ticks={}", pos, home.emptyTicks());
            home.dismantle("villager_cleanup");
            return;
        }
    }
}
