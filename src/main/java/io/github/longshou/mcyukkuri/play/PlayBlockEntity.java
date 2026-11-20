package io.github.longshou.mcyukkuri.play;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** The tub's orange juice (0-4) is saved here and synced for drawing. Activity reservations are short, loaded-world leases. */
public final class PlayBlockEntity extends BlockEntity {
    private UUID player;private long leaseUntil;private boolean dismantling,suppressDrop;
    private long bedImpactTick;
    private float bedImpactStrength;
    private int juice;
    public PlayBlockEntity(BlockPos pos,BlockState state){super(Playgrounds.ENTITY.get(),pos,state);}
    public PlayBlock.Kind kind(){return ((PlayBlock)getBlockState().getBlock()).kind();}
    public Direction facing(){return getBlockState().getValue(PlayBlock.FACING);}
    public int juice(){return juice;}

    void pressBed(float strength) {
        bedImpactTick = level.getGameTime();
        bedImpactStrength = strength;
    }

    public float bedDepression(float partial) {
        // Return in four ticks. The skirt hides at most two model units of bed travel.
        float spring = Math.max(0, 1 - (level.getGameTime() - bedImpactTick + partial) / 4);
        return (2 / 16f) * bedImpactStrength * spring * spring;
    }
    /** A town's park tub starts full. */
    public void fill(){setJuice(JuiceTub.CAPACITY);}
    /** One poured item is one level. */
    public boolean pour(){if(JuiceTub.full(juice))return false;setJuice(JuiceTub.filled(juice));return true;}
    /** A completed soak uses up one level. */
    public void soaked(){if(juice>0)setJuice(JuiceTub.drained(juice));}
    private void setJuice(int value){juice=value;setChanged();if(level!=null&&!level.isClientSide)level.sendBlockUpdated(worldPosition,getBlockState(),getBlockState(),3);}
    @Override protected void saveAdditional(CompoundTag tag,HolderLookup.Provider registries){super.saveAdditional(tag,registries);tag.putInt("Juice",juice);}
    @Override protected void loadAdditional(CompoundTag tag,HolderLookup.Provider registries){
        super.loadAdditional(tag,registries);juice=Math.clamp(tag.getInt("Juice"),0,JuiceTub.CAPACITY);
    }
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider registries){var tag=new CompoundTag();tag.putInt("Juice",juice);return tag;}
    @Override public ClientboundBlockEntityDataPacket getUpdatePacket(){return ClientboundBlockEntityDataPacket.create(this);}
    @Override public void onDataPacket(Connection connection,ClientboundBlockEntityDataPacket packet,HolderLookup.Provider registries){
        handleUpdateTag(packet.getTag(),registries);
    }
    public boolean occupiedByOther(YukkuriEntity e){
        return player!=null&&!player.equals(e.getUUID())&&leaseUntil>=level.getGameTime();
    }
    public boolean reserve(YukkuriEntity e){
        if(level==null||isRemoved()||dismantling)return false;
        if(occupiedByOther(e))return false;
        player=e.getUUID();leaseUntil=level.getGameTime()+40;return true;
    }
    public void release(YukkuriEntity e){if(e.getUUID().equals(player)){player=null;leaseUntil=0;}}
    public void suppressDrop(){suppressDrop=true;}
    public void dismantle(){
        if(dismantling||level==null)return;dismantling=true;player=null;
        var kind=kind();var facing=facing();
        if(!suppressDrop)Block.popResource(level,worldPosition,new ItemStack(Playgrounds.ITEMS.get(kind).get()));
        // The juice has no item form without its bottle, and the bottle went back to the player when it was poured: it spills.
        if(juice>0&&level instanceof ServerLevel server){
            server.sendParticles(ParticleTypes.FALLING_HONEY,worldPosition.getX()+.5,worldPosition.getY()+.3,worldPosition.getZ()+.5,6*juice,.5,.1,.5,0);
            server.playSound(null,worldPosition,SoundEvents.BOTTLE_EMPTY,SoundSource.BLOCKS,1,.8f);
            GameplayLog.world(server,"play","tub spilled at={} juice={}",worldPosition,juice);
        }
        for(var pos:PlayItem.positions(worldPosition,kind,facing))if(level.hasChunkAt(pos)){
            var state=level.getBlockState(pos);
            if(state.getBlock() instanceof PlayBlock b&&b.kind()==kind&&state.getValue(PlayBlock.FACING)==facing&&PlayBlock.root(pos,state).equals(worldPosition))
                level.setBlockAndUpdate(pos,Blocks.AIR.defaultBlockState());
        }
    }
}
