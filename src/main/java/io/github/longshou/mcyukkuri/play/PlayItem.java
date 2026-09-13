package io.github.longshou.mcyukkuri.play;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;

/** Atomic placement, one physical root, one returned facility on dismantling. */
public final class PlayItem extends Item {
    private final PlayBlock.Kind kind;
    public PlayItem(PlayBlock.Kind kind,Properties properties){super(properties);this.kind=kind;}
    public static List<BlockPos> positions(BlockPos root,PlayBlock.Kind kind,Direction facing){
        var result=new ArrayList<BlockPos>();
        for(int y=0;y<2;y++)for(int z=-kind.depth;z<=kind.depth;z++)for(int x=-1;x<=1;x++)result.add(BlockPos.containing(PlayGeometry.world(root,facing,x,y,z)));
        return result;
    }
    public static boolean canPlace(ServerLevel level,BlockPos root,PlayBlock.Kind kind,Direction facing){
        var positions=positions(root,kind,facing);
        for(var pos:positions){
            if(level.isOutsideBuildHeight(pos)||!level.hasChunkAt(pos)||!level.getFluidState(pos).isEmpty()||level.getBlockEntity(pos)!=null)return false;
            var state=level.getBlockState(pos);
            if(!state.isAir()&&!state.is(Blocks.SHORT_GRASS)&&!state.is(Blocks.FERN)&&!state.is(Blocks.SNOW))return false;
            if(pos.getY()==root.getY()&&!level.getBlockState(pos.below()).isFaceSturdy(level,pos.below(),Direction.UP))return false;
        }
        for(var e:level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,new AABB(root).inflate(3,2,3),e->e.isAlive()&&!e.isSpectator())){
            for(var pos:positions){var shape=PlayGeometry.shape(kind,facing,PlayBlock.part(pos.subtract(root))).move(pos.getX(),pos.getY(),pos.getZ());
                if(Shapes.joinIsNotEmpty(shape,Shapes.create(e.getBoundingBox()),BooleanOp.AND))return false;}
        }
        return true;
    }
    public static PlayBlockEntity place(ServerLevel level,BlockPos root,PlayBlock.Kind kind,Direction facing){
        if(!canPlace(level,root,kind,facing))return null;
        var before=new LinkedHashMap<BlockPos,BlockState>();var block=Playgrounds.BLOCKS.get(kind).get();
        for(var pos:positions(root,kind,facing))before.put(pos,level.getBlockState(pos));
        for(var pos:before.keySet())if(!level.setBlock(pos,block.defaultBlockState().setValue(PlayBlock.PART,PlayBlock.part(pos.subtract(root))).setValue(PlayBlock.FACING,facing),3)){
            var core=PlayBlock.facility(level,root);if(core!=null)core.suppressDrop();before.forEach((at,state)->level.setBlock(at,state,3));return null;
        }
        return PlayBlock.facility(level,root);
    }
    @Override public InteractionResult useOn(UseOnContext context){
        if(context.getLevel().isClientSide)return InteractionResult.SUCCESS;
        var level=(ServerLevel)context.getLevel();var player=context.getPlayer();var root=context.getClickedPos().relative(context.getClickedFace());var facing=context.getHorizontalDirection();
        if(player!=null)for(var pos:positions(root,kind,facing))if(!level.mayInteract(player,pos)||!player.mayUseItemAt(pos,context.getClickedFace(),context.getItemInHand()))return InteractionResult.FAIL;
        if(place(level,root,kind,facing)==null){if(player!=null)player.displayClientMessage(Component.translatable("play.mcyukkuri.no_space",3,kind.depth*2+1,2),true);return InteractionResult.FAIL;}
        if(player==null||!player.getAbilities().instabuild)context.getItemInHand().shrink(1);return InteractionResult.CONSUME;
    }
}
