package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.registry.ModBlocks;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/** One cell of a den-mouth screen and the conditions under which its twig comes out. */
public final class DenBarrierBlockEntity extends BlockEntity {
    /** Ticks without a danger within {@link #WATCH_RANGE} before the owner takes the screen down, and the most it stands (project design). */
    static final int CALM_TICKS = 200, DECAY_TICKS = 4800, WATCH_RANGE = 20, CHECK = 20;
    /** How long the danger that drove the builder home still counts as present after it stops acting. */
    static final int THREAT_MEMORY = 1200;
    private UUID owner, threat;
    private List<UUID> absent = List.of();
    private BlockPos den;
    private long laidAt;
    private int calm;
    private boolean released;

    public DenBarrierBlockEntity(BlockPos pos, BlockState state) { super(ModBlocks.DEN_BARRIER_ENTITY.get(), pos, state); }

    void lay(YukkuriEntity builder, ShelterBlockEntity shelter, List<UUID> parents, UUID danger) {
        owner = builder.getUUID(); den = shelter.getBlockPos().immutable(); laidAt = level.getGameTime(); calm = 0;
        absent = parents; threat = danger;
        setChanged();
    }
    public UUID owner() { return owner; }

    boolean calm() { return absent.isEmpty() && calm >= CALM_TICKS; }

    String familyReason(YukkuriEntity resident, ShelterBlockEntity home) {
        if (absent.isEmpty() || resident.family().adult()) return null;
        if (resident.life().nutrition() < 30 && home.food() == 0) return "child_hungry";
        return absent.stream().anyMatch(resident.family()::kinOverdue) ? "parent_overdue" : null;
    }

    void open(YukkuriEntity opener, String reason) { releaseMouth((ServerLevel) level, reason, opener); }

    void tick() {
        if (!(level instanceof ServerLevel server) || released || server.getGameTime() % CHECK != 0) return;
        long age = server.getGameTime() - laidAt;
        var home = home(server);
        if (den != null && server.hasChunkAt(den) && home == null) { release("den_gone", null); return; }
        if (home != null && (home.flooded() || !server.getEntitiesOfClass(YukkuriEntity.class, home.interior(),
                y -> y.isAlive() && home.owns(y.getUUID()) && home.contains(y.blockPosition())
                        && (y.isOnFire() || y.isInWaterOrBubble())).isEmpty())) {
            releaseMouth(server, "danger_inside", nearbyBuilder(server));
            return;
        }
        if (age >= DECAY_TICKS) { release("decayed", nearbyBuilder(server)); return; }
        if (!absent.isEmpty()) return;
        // Remember the specific attacker for a limited time. Nearby predators can still keep the screen up until decay.
        boolean danger = !server.getEntitiesOfClass(YukkuriEntity.class, new AABB(worldPosition).inflate(WATCH_RANGE, 8, WATCH_RANGE),
                p -> p.isAlive() && p.profile().dangerToYukkuri()).isEmpty()
                || age < THREAT_MEMORY && threat != null && server.getEntity(threat) instanceof LivingEntity other && other.isAlive()
                        && other.distanceToSqr(worldPosition.getX() + .5, worldPosition.getY() + .5, worldPosition.getZ() + .5)
                                <= (double) WATCH_RANGE * WATCH_RANGE;
        calm = danger ? 0 : calm + CHECK;
        setChanged();
    }

    void releaseForDeparture(ShelterBlockEntity shelter, String reason, YukkuriEntity resident) {
        if (released || !shelter.getBlockPos().equals(den)) return;
        var server = (ServerLevel) level;
        release(reason, absent.isEmpty() ? nearbyBuilder(server) : resident);
    }


    private ShelterBlockEntity home(ServerLevel server) {
        return den != null && server.hasChunkAt(den) && server.getBlockEntity(den) instanceof ShelterBlockEntity home ? home : null;
    }

    private YukkuriEntity nearbyBuilder(ServerLevel server) {
        return owner != null && server.getEntity(owner) instanceof YukkuriEntity y && y.isAlive()
                && y.distanceToSqr(worldPosition.getX() + .5, worldPosition.getY(), worldPosition.getZ() + .5) <= 144 ? y : null;
    }

    /** Work on the doorway clears every remaining cell of the same screen. */
    private void releaseMouth(ServerLevel server, String reason, YukkuriEntity opener) {
        for (var side : Direction.Plane.HORIZONTAL) {
            var cell = den.relative(side);
            if (server.hasChunkAt(cell) && server.getBlockEntity(cell) instanceof DenBarrierBlockEntity barrier
                    && den.equals(barrier.den) && absent.equals(barrier.absent)) barrier.release(reason, opener);
        }
    }

    private void release(String reason, YukkuriEntity opener) {
        var server = (ServerLevel) level;
        var home = home(server);
        boolean returned = home != null && opener != null && home.returnBranch();
        released = true;
        if (!returned) Block.popResource(server, worldPosition, new ItemStack(Items.STICK));
        GameplayLog.world(server, "home", "den barrier removed pos={} den={} owner={} reason={} age={} twig_returned={} opener={}",
                worldPosition.toShortString(), den == null ? "none" : den.toShortString(), owner, reason,
                server.getGameTime() - laidAt, returned, opener == null ? null : opener.getUUID());
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
        if (threat != null) tag.putUUID("Threat", threat);
        if (den != null) tag.putLong("Den", den.asLong());
        tag.putLong("LaidAt", laidAt);
        tag.putInt("Calm", calm);
        if (!absent.isEmpty()) {
            var parents = new ListTag();
            for (var parent : absent) parents.add(NbtUtils.createUUID(parent));
            tag.put("AbsentParents", parents);
        }
    }
    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        owner = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
        threat = tag.hasUUID("Threat") ? tag.getUUID("Threat") : null;
        den = tag.contains("Den") ? BlockPos.of(tag.getLong("Den")) : null;
        laidAt = tag.getLong("LaidAt");
        calm = Math.max(0, tag.getInt("Calm"));
        absent = tag.getList("AbsentParents", Tag.TAG_INT_ARRAY).stream()
                .filter(parent -> ((IntArrayTag) parent).size() == 4)
                .map(NbtUtils::loadUUID).toList();
    }
}
