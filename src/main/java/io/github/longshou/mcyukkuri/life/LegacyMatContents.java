package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.function.BiPredicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.event.level.ChunkDataEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/** Recovers the hopper-accessible inventory of a retired mat when its block alias loads as carpet. */
public final class LegacyMatContents {
    private static final String INVENTORIES = "Inventories";
    private static final DeferredRegister<AttachmentType<?>> ATTACHMENTS =
            DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, MCYukkuri.MOD_ID);
    private static final DeferredHolder<AttachmentType<?>, AttachmentType<CompoundTag>> CONTENTS =
            ATTACHMENTS.register("legacy_mat_contents",
                    () -> AttachmentType.builder(LegacyMatContents::inventoryTag).serialize(CompoundTag.CODEC).build());

    private static CompoundTag inventoryTag() {
        var tag = new CompoundTag();
        tag.put(INVENTORIES, new ListTag());
        return tag;
    }

    public static void register(IEventBus bus) {
        ATTACHMENTS.register(bus);
    }

    /** The full chunk's post-load processor still holds this list. A protochunk also has a copy of its entries by position. */
    public static void read(ChunkDataEvent.Load event) {
        var chunk = event.getChunk();
        var iterator = event.getData().getList("block_entities", Tag.TAG_COMPOUND).iterator();
        while (iterator.hasNext()) {
            var care = (CompoundTag) iterator.next();
            if (!care.getString("id").equals("mcyukkuri:care")) continue;
            var pos = BlockEntity.getPosFromTag(care);
            if (!chunk.getPos().equals(new ChunkPos(pos)) || chunk.isOutsideBuildHeight(pos)) continue;
            var block = chunk.getBlockState(pos).getBlock();
            // A care entity on one of the three carpet replacements can only come from an old mat.
            if (block != Blocks.WHITE_CARPET && block != Blocks.BROWN_CARPET && block != Blocks.YELLOW_CARPET) continue;
            if (!care.getList("Items", Tag.TAG_COMPOUND).isEmpty()) {
                chunk.getData(CONTENTS).getList(INVENTORIES, Tag.TAG_COMPOUND).add(care);
            }
            iterator.remove();
            if (chunk instanceof ProtoChunk) chunk.removeBlockEntity(pos);
            chunk.setUnsaved(true);
        }
    }

    public static void loaded(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        release(event.getChunk(), level.registryAccess(), (pos, stack) -> {
            var item = new ItemEntity(level, pos.getX() + .5, pos.getY() + .15, pos.getZ() + .5, stack);
            item.setDefaultPickUpDelay();
            if (!level.addFreshEntity(item)) return false;
            GameplayLog.world(level, "care", "legacy mat contents recovered at={} item={} count={}",
                    pos.toShortString(), stack.getItem(), stack.getCount());
            return true;
        });
    }

    static void release(ChunkAccess chunk, HolderLookup.Provider registries, BiPredicate<BlockPos, ItemStack> drop) {
        var contents = chunk.getExistingDataOrNull(CONTENTS);
        if (contents == null) return;
        var inventories = contents.getList(INVENTORIES, Tag.TAG_COMPOUND);
        inventories.removeIf(raw -> {
            var care = (CompoundTag) raw;
            var pos = BlockEntity.getPosFromTag(care);
            var items = care.getList("Items", Tag.TAG_COMPOUND);
            items.removeIf(item -> {
                var stack = ItemStack.parseOptional(registries, (CompoundTag) item);
                return !stack.isEmpty() && drop.test(pos, stack);
            });
            return items.isEmpty();
        });
        // Failed spawns stay in the chunk attachment and are tried again when the chunk next loads.
        if (inventories.isEmpty()) chunk.removeData(CONTENTS);
        else chunk.setUnsaved(true);
    }

    private LegacyMatContents() {}
}
