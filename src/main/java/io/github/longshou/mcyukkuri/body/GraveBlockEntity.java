package io.github.longshou.mcyukkuri.body;

import io.github.longshou.mcyukkuri.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Burial retains the identity and source size; breaking a grave does not mint another body. */
public final class GraveBlockEntity extends BlockEntity {
    private CompoundTag remains=new CompoundTag();
    public GraveBlockEntity(BlockPos pos,BlockState state){super(ModBlocks.GRAVE_ENTITY.get(),pos,state);}
    public float sourceScale(){float value=remains.contains("SourceScale")?remains.getFloat("SourceScale"):1;return Float.isFinite(value)?Math.clamp(value,.05f,4f):1;}
    public void bury(CompoundTag data){remains=data.copy();setChanged();if(level!=null)level.sendBlockUpdated(worldPosition,getBlockState(),getBlockState(),3);}
    @Override protected void saveAdditional(CompoundTag tag,HolderLookup.Provider registries){super.saveAdditional(tag,registries);tag.put("Remains",remains.copy());}
    @Override protected void loadAdditional(CompoundTag tag,HolderLookup.Provider registries){super.loadAdditional(tag,registries);remains=tag.getCompound("Remains").copy();}
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider registries){var tag=new CompoundTag();tag.put("Remains",remains.copy());return tag;}
    @Override public ClientboundBlockEntityDataPacket getUpdatePacket(){return ClientboundBlockEntityDataPacket.create(this);}
}
