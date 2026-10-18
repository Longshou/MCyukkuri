package io.github.longshou.mcyukkuri.play;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;

/** Water is saved in the block state. Activity reservations are short, loaded-world leases. */
public final class PlayBlockEntity extends BlockEntity {
    private UUID player;private long leaseUntil;private boolean dismantling,suppressDrop;
    public PlayBlockEntity(BlockPos pos,BlockState state){super(Playgrounds.ENTITY.get(),pos,state);}
    public PlayBlock.Kind kind(){return ((PlayBlock)getBlockState().getBlock()).kind();}
    public Direction facing(){return getBlockState().getValue(PlayBlock.FACING);}
    public int water(){return getBlockState().getValue(PlayBlock.WATER);}
    public void fill(){setWater(3);}
    public boolean spendWater(){if(level==null||level.isClientSide||isRemoved()||water()==0)return false;setWater(water()-1);return true;}
    private void setWater(int value){level.setBlockAndUpdate(worldPosition,getBlockState().setValue(PlayBlock.WATER,value));setChanged();}
    public boolean rinse(YukkuriEntity e){
        if(water()==0||!e.isInWater()||e.life().hygiene()>=99)return false;
        e.life().poolRinse();setWater(water()-1);return true;
    }
    public boolean reserve(YukkuriEntity e){
        if(level==null||isRemoved()||dismantling)return false;
        if(player!=null&&!player.equals(e.getUUID())&&leaseUntil>=level.getGameTime())return false;
        player=e.getUUID();leaseUntil=level.getGameTime()+40;return true;
    }
    public void release(YukkuriEntity e){if(e.getUUID().equals(player)){player=null;leaseUntil=0;}}
    public void suppressDrop(){suppressDrop=true;}
    public void dismantle(){
        if(dismantling||level==null)return;dismantling=true;player=null;
        var kind=kind();var facing=facing();
        if(!suppressDrop)Block.popResource(level,worldPosition,new ItemStack(Playgrounds.ITEMS.get(kind).get()));
        for(var pos:PlayItem.positions(worldPosition,kind,facing))if(level.hasChunkAt(pos)){
            var state=level.getBlockState(pos);
            if(state.getBlock() instanceof PlayBlock b&&b.kind()==kind&&state.getValue(PlayBlock.FACING)==facing&&PlayBlock.root(pos,state).equals(worldPosition)
                    ||pos.equals(worldPosition)&&kind==PlayBlock.Kind.POOL&&state.is(Blocks.WATER))
                level.setBlockAndUpdate(pos,Blocks.AIR.defaultBlockState());
        }
    }
}
