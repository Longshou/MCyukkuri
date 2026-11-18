package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.registry.ModBlocks;
import io.github.longshou.mcyukkuri.registry.ModItems;
import io.github.longshou.mcyukkuri.settlement.ForagePileBlockEntity;
import io.github.longshou.mcyukkuri.settlement.ShelterBlock;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.util.ZeroBitStorage;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.event.level.ChunkDataEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/** Recovers stock from old block entities whose blocks resolve to a vanilla alias on load. */
public final class LegacyBlockContents {
    private static final ResourceLocation CRATE = MCYukkuri.id("crate_shelter");
    private static final ResourceLocation BARREL = ResourceLocation.withDefaultNamespace("barrel");
    private static final String SHELTER_ENTITY = "mcyukkuri:wild_shelter";
    private static final String INVENTORIES = "Inventories";
    private static final DeferredRegister<AttachmentType<?>> ATTACHMENTS =
            DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, MCYukkuri.MOD_ID);
    private static final DeferredHolder<AttachmentType<?>, AttachmentType<CompoundTag>> CONTENTS =
            ATTACHMENTS.register("legacy_mat_contents",
                    () -> AttachmentType.builder(LegacyBlockContents::inventoryTag).serialize(CompoundTag.CODEC).build());

    private static CompoundTag inventoryTag() {
        var tag = new CompoundTag();
        tag.put(INVENTORIES, new ListTag());
        return tag;
    }

    public static void register(IEventBus bus) {
        // A barrel item returns the shell's original container without obstructing the existing occupants.
        ModBlocks.BLOCKS.addAlias(CRATE, ResourceLocation.withDefaultNamespace("air"));
        ModItems.ITEMS.addAlias(CRATE, BARREL);
        ATTACHMENTS.register(bus);
    }

    /** The full chunk's post-load processor still holds this list. A protochunk also has a copy of its entries by position. */
    public static void read(ChunkDataEvent.Load event) {
        var chunk = event.getChunk();
        var crates = crateCores(chunk, event.getData());
        var iterator = event.getData().getList("block_entities", Tag.TAG_COMPOUND).iterator();
        while (iterator.hasNext()) {
            var saved = (CompoundTag) iterator.next();
            var pos = BlockEntity.getPosFromTag(saved);
            if (!chunk.getPos().equals(new ChunkPos(pos)) || chunk.isOutsideBuildHeight(pos)) continue;
            if (saved.getString("id").equals(SHELTER_ENTITY) && crates.remove(pos)) {
                saveCrate(chunk, pos, saved);
            } else {
                if (!saved.getString("id").equals("mcyukkuri:care")) continue;
                var block = chunk.getBlockState(pos).getBlock();
                // A care entity on one of the three carpet replacements can only come from an old mat.
                if (block != Blocks.WHITE_CARPET && block != Blocks.BROWN_CARPET && block != Blocks.YELLOW_CARPET) continue;
                if (!saved.getList("Items", Tag.TAG_COMPOUND).isEmpty()) inventories(chunk.getData(CONTENTS)).add(saved);
            }
            iterator.remove();
            if (chunk instanceof ProtoChunk) chunk.removeBlockEntity(pos);
            chunk.setUnsaved(true);
        }
        for (var pos : crates) saveCrate(chunk, pos, new CompoundTag());
    }

    /** The alias already cleared every part. Read core positions from the on-disk palette before the part IDs disappear. */
    private static Set<BlockPos> crateCores(ChunkAccess chunk, CompoundTag data) {
        var cores = new HashSet<BlockPos>();
        boolean changed = false;
        for (var raw : data.getList("sections", Tag.TAG_COMPOUND)) {
            var section = (CompoundTag) raw;
            var states = section.getCompound("block_states");
            var palette = states.getList("palette", Tag.TAG_COMPOUND);
            var coreIndices = new HashSet<Integer>();
            for (int i = 0; i < palette.size(); i++) {
                var entry = palette.getCompound(i);
                if (!entry.getString("Name").equals(CRATE.toString())) continue;
                changed = true;
                if (entry.getCompound("Properties").getString("part").equals(Integer.toString(ShelterBlock.CORE))) coreIndices.add(i);
            }
            if (coreIndices.isEmpty()) continue;
            var origin = SectionPos.of(chunk.getPos(), section.getByte("Y")).origin();
            // Sections pack local x, z, y in that order, with at least four bits per entry in a mixed palette.
            var indices = palette.size() == 1 ? new ZeroBitStorage(4096)
                    : new SimpleBitStorage(Math.max(4, Mth.ceillog2(palette.size())), 4096, states.getLongArray("data"));
            for (int i = 0; i < indices.getSize(); i++) {
                if (coreIndices.contains(indices.get(i))) cores.add(origin.offset(i & 15, i >> 8, (i >> 4) & 15));
            }
        }
        if (changed) {
            var types = chunk.getPersistedStatus().getChunkSaveHeightmaps();
            for (var type : types) chunk.setHeightmap(type, new Heightmap(chunk, type).getRawData());
            Heightmap.primeHeightmaps(chunk, types);
            chunk.setLightCorrect(false);
            chunk.setUnsaved(true);
        }
        return cores;
    }

    private static void saveCrate(ChunkAccess chunk, BlockPos pos, CompoundTag shelter) {
        var recovery = new CompoundTag();
        recovery.putString("id", SHELTER_ENTITY);
        recovery.putInt("x", pos.getX());
        recovery.putInt("y", pos.getY());
        recovery.putInt("z", pos.getZ());
        if (shelter.contains("Pile", Tag.TAG_LONG)) recovery.putLong("Pile", shelter.getLong("Pile"));
        var items = shelter.getList("FoundBedding", Tag.TAG_COMPOUND);
        for (var project : shelter.getList("NestProjects", Tag.TAG_COMPOUND))
            items.addAll(((CompoundTag) project).getList("Materials", Tag.TAG_COMPOUND));
        var shell = new CompoundTag();
        shell.putString("id", BARREL.toString());
        shell.putInt("count", 1);
        items.add(shell);
        recovery.put("Items", items);
        inventories(chunk.getData(CONTENTS)).add(recovery);
    }

    private static ListTag inventories(CompoundTag contents) {
        return contents.getList(INVENTORIES, Tag.TAG_COMPOUND);
    }

    public static void loaded(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getChunk() instanceof LevelChunk chunk)) return;
        release(level, chunk, chunk);
        // A linked food pile can cross a chunk edge. Complete the same recovery whichever chunk loads last.
        var pos = chunk.getPos();
        for (int z = pos.z - 1; z <= pos.z + 1; z++) for (int x = pos.x - 1; x <= pos.x + 1; x++) {
            if (x == pos.x && z == pos.z) continue;
            var neighbor = level.getChunkSource().getChunkNow(x, z);
            if (neighbor != null) release(level, neighbor, chunk);
        }
    }

    private static void release(ServerLevel level, LevelChunk chunk, LevelChunk arrived) {
        var contents = chunk.getExistingDataOrNull(CONTENTS);
        if (contents == null) return;
        var records = inventories(contents);
        records.removeIf(raw -> {
            var saved = (CompoundTag) raw;
            var pos = BlockEntity.getPosFromTag(saved);
            var items = saved.getList("Items", Tag.TAG_COMPOUND);
            boolean crate = saved.getString("id").equals(SHELTER_ENTITY);
            if (chunk == arrived) items.removeIf(item -> {
                var stack = ItemStack.parseOptional(level.registryAccess(), (CompoundTag) item);
                return !stack.isEmpty() && drop(level, pos, stack, crate);
            });
            if (crate && saved.contains("Pile", Tag.TAG_LONG) && releasePile(level, pos, BlockPos.of(saved.getLong("Pile")), arrived))
                saved.remove("Pile");
            return items.isEmpty() && !saved.contains("Pile", Tag.TAG_LONG);
        });
        // Failed spawns stay in the chunk attachment and are tried again when the chunk next loads.
        if (records.isEmpty()) chunk.removeData(CONTENTS);
        else chunk.setUnsaved(true);
    }

    private static boolean releasePile(ServerLevel level, BlockPos core, BlockPos pos, LevelChunk arrived) {
        var at = new ChunkPos(pos);
        var chunk = at.equals(arrived.getPos()) ? arrived : level.getChunkSource().getChunkNow(at.x, at.z);
        if (chunk == null) return false;
        if (!(chunk.getBlockEntity(pos) instanceof ForagePileBlockEntity pile) || !core.equals(pile.owner())) return true;
        for (var stack : pile.drain()) if (!drop(level, pos, stack, true)) pile.returnFood(stack);
        if (pile.food() != 0) return false;
        chunk.setBlockState(pos, Blocks.AIR.defaultBlockState(), false);
        return true;
    }

    private static boolean drop(ServerLevel level, BlockPos pos, ItemStack stack, boolean crate) {
        var item = new ItemEntity(level, pos.getX() + .5, pos.getY() + .15, pos.getZ() + .5, stack);
        item.setDefaultPickUpDelay();
        if (!level.addFreshEntity(item)) return false;
        if (crate) GameplayLog.world(level, "habitat", "legacy crate recovered at={} item={} count={}",
                pos.toShortString(), stack.getItem(), stack.getCount());
        else GameplayLog.world(level, "care", "legacy mat contents recovered at={} item={} count={}",
                pos.toShortString(), stack.getItem(), stack.getCount());
        return true;
    }

    private LegacyBlockContents() {}
}
