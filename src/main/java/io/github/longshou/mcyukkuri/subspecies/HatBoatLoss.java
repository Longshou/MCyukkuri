package io.github.longshou.mcyukkuri.subspecies;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.damagesource.DamageSource;

/** Positive physical destruction of one deployed hat. Unloading and ordinary cleanup are not loss events. */
final class HatBoatLoss {
    private static final float BURN_DAMAGE = 6;
    private static final int FLOOD_TICKS = 600, SUBMERGED_TICKS = 200;
    private final HatBoatEntity boat;
    private float burned;
    private int submerged, flooded;

    HatBoatLoss(HatBoatEntity boat) { this.boat = boat; }

    boolean fireHit(DamageSource source, float amount) {
        if (!source.is(DamageTypeTags.IS_FIRE)) return false;
        burned = Math.min(BURN_DAMAGE, burned + amount);
        if (burned >= BURN_DAMAGE) destroy("burned");
        return true;
    }

    void tick() {
        if (boat.isRemoved() || boat.state() == HatBoatEntity.State.RESCUED || boat.state() == HatBoatEntity.State.REMOVED) return;
        var waterline=BlockPos.containing(boat.getX(),boat.getY()-.12,boat.getZ());
        if(boat.state()==HatBoatEntity.State.CAPSIZED&&boat.level().getFluidState(waterline).is(FluidTags.WATER))
            flooded=Math.min(FLOOD_TICKS,flooded+1);
        else flooded=0;
        var above = BlockPos.containing(boat.getX(), boat.getBoundingBox().maxY + .1, boat.getZ());
        if (!boat.level().hasChunkAt(above)) { submerged = 0; return; }
        if (boat.state() == HatBoatEntity.State.CAPSIZED && boat.level().getFluidState(above).is(FluidTags.WATER)) {
            if (++submerged >= SUBMERGED_TICKS) destroy("sunk");
        } else submerged = 0;
    }

    /** A capsized cloth hull has thirty seconds to be righted before waterlogging overcomes its buoyancy. */
    boolean sinking() { return boat.state()==HatBoatEntity.State.CAPSIZED&&flooded>=FLOOD_TICKS; }

    void destroy(String cause) {
        if (!(boat.level() instanceof ServerLevel server) || boat.state() == HatBoatEntity.State.RESCUED
                || boat.state() == HatBoatEntity.State.REMOVED) return;
        var ownerId = boat.ownerYukkuri();
        var token = boat.launchToken();
        // Invalidate the vessel before callbacks can try to reclaim its hat or release its inventory a second time.
        boat.state(HatBoatEntity.State.REMOVED);
        boat.launchToken(null);
        boat.stopPaddling();
        boat.cargo().spillAt(boat.position());
        for (var passenger : List.copyOf(boat.getPassengers())) {
            var at = passenger.position();
            passenger.stopRiding();
            passenger.setPos(at);
            if (passenger instanceof YukkuriEntity y && y.isInWaterOrBubble()) y.waterDistress().plunge(boat.getUUID(), null);
        }
        if (ownerId != null && token != null) {
            YukkuriEntity owner = null;
            for (var dimension : server.getServer().getAllLevels())
                if (dimension.getEntity(ownerId) instanceof YukkuriEntity candidate) { owner = candidate; break; }
            if (owner != null) owner.headwear().deployedDestroyed(ownerId, token, cause);
            else YukkuriWorldData.get(server).accessoryDestroyed(ownerId, token, cause);
        }
        boat.discard();
    }

    void save(CompoundTag tag) {
        tag.putFloat("HullBurnDamage", burned);
        tag.putInt("HullSubmergedTicks", submerged);
        tag.putInt("HullFloodedTicks", flooded);
    }

    void load(CompoundTag tag) {
        burned = Math.clamp(tag.getFloat("HullBurnDamage"), 0, BURN_DAMAGE);
        submerged = Math.clamp(tag.getInt("HullSubmergedTicks"), 0, SUBMERGED_TICKS);
        flooded = Math.clamp(tag.getInt("HullFloodedTicks"), 0, FLOOD_TICKS);
    }
}
