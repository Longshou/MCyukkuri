package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.registry.ModBlocks;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.status.Reactions;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/** Who laid one cell of a den-mouth screen, which den it closes, and how long it has stood without danger near. */
public final class DenBarrierBlockEntity extends BlockEntity {
    /** Ticks without a predator within {@link #WATCH_RANGE} before the owner takes the screen down, and the most it stands (project design). */
    static final int CALM_TICKS = 200, DECAY_TICKS = 4800, WATCH_RANGE = 20, CHECK = 20, PUSH_TICKS = 60;
    private UUID owner;
    private BlockPos den;
    private long laidAt;
    private int calm, push;
    private boolean released;

    public DenBarrierBlockEntity(BlockPos pos, BlockState state) { super(ModBlocks.DEN_BARRIER_ENTITY.get(), pos, state); }

    void lay(YukkuriEntity builder, ShelterBlockEntity shelter) {
        owner = builder.getUUID(); den = shelter.getBlockPos().immutable(); laidAt = level.getGameTime(); calm = 0;
        setChanged();
    }
    public UUID owner() { return owner; }

    void tick() {
        if (!(level instanceof ServerLevel server) || released || server.getGameTime() % CHECK != 0) return;
        long age = server.getGameTime() - laidAt;
        var home = den == null || !server.hasChunkAt(den) ? null : ShelterBlock.shelter(server, den);
        if (den != null && server.hasChunkAt(den) && home == null) { release("den_gone", false); return; }
        boolean danger = !server.getEntitiesOfClass(YukkuriEntity.class, new AABB(worldPosition).inflate(WATCH_RANGE, 8, WATCH_RANGE),
                p -> p.isAlive() && p.profile().dangerToYukkuri()).isEmpty();
        calm = danger ? 0 : calm + CHECK;
        // A resident that stands calm against the screen from inside pushes it aside (it is only loose twigs), danger or not.
        boolean pushing = home != null && !server.getEntitiesOfClass(YukkuriEntity.class, new AABB(worldPosition).inflate(1.6, 1, 1.6),
                y -> y.isAlive() && home.owns(y.getUUID()) && !y.life().isSleeping() && !y.isPanicking()
                        && y.status().reactions().threat().stage() == Reactions.Threat.Stage.NONE).isEmpty();
        push = pushing ? push + CHECK : 0;
        if (push >= PUSH_TICKS) release("pushed_through", true);
        else if (calm >= CALM_TICKS) release("danger_passed", true);
        else if (age >= DECAY_TICKS) release("decayed", true);
    }

    /** The owner takes the screen apart; its twig goes back to the doorway stock only if the owner is still there to carry it. */
    private void release(String reason, boolean refund) {
        if (!(level instanceof ServerLevel server) || released) return;
        released = true;
        var home = den == null || !server.hasChunkAt(den) ? null : ShelterBlock.shelter(server, den);
        var builder = owner != null && server.getEntity(owner) instanceof YukkuriEntity y && y.isAlive() ? y : null;
        boolean returned = false;
        String lost = "none";
        if (refund && home != null && builder != null && builder.distanceToSqr(worldPosition.getX() + .5, worldPosition.getY(), worldPosition.getZ() + .5) <= 144) {
            home.returnBranch(); returned = true;
        } else lost = home == null ? "den_gone" : builder == null ? "owner_absent" : refund ? "owner_far" : "not_returned";
        GameplayLog.world(server, "home", "den barrier removed pos={} den={} owner={} reason={} age={} twig_returned={} twig_lost_because={}",
                worldPosition.toShortString(), den == null ? "none" : den.toShortString(), owner, reason, server.getGameTime() - laidAt, returned, lost);
        server.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.OAK_LEAVES.defaultBlockState()),
                worldPosition.getX() + .5, worldPosition.getY() + .5, worldPosition.getZ() + .5, 8, .3, .3, .3, .05);
        server.removeBlock(worldPosition, false);
    }

    /** A player's hand pulled it apart: the twigs are gone with the block's drop, nothing returns to the stock. */
    void broken(Player player) {
        if (!(level instanceof ServerLevel server) || released) return;
        released = true;
        GameplayLog.world(server, "home", "den barrier broken pos={} den={} owner={} player={} age={} twig_returned=false",
                worldPosition.toShortString(), den == null ? "none" : den.toShortString(), owner, player.getUUID(), server.getGameTime() - laidAt);
    }

    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (owner != null) tag.putUUID("Owner", owner);
        if (den != null) tag.putLong("Den", den.asLong());
        tag.putLong("LaidAt", laidAt);
        tag.putInt("Calm", calm);
    }
    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        owner = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
        den = tag.contains("Den") ? BlockPos.of(tag.getLong("Den")) : null;
        laidAt = tag.getLong("LaidAt");
        calm = Math.max(0, tag.getInt("Calm"));
    }
}
