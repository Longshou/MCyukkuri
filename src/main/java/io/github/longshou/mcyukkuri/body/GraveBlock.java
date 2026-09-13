package io.github.longshou.mcyukkuri.body;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class GraveBlock extends Block implements EntityBlock {
    public GraveBlock(Properties properties){super(properties);}
    @Override public BlockEntity newBlockEntity(BlockPos pos,BlockState state){return new GraveBlockEntity(pos,state);}
    @Override protected VoxelShape getShape(BlockState state,BlockGetter level,BlockPos pos,CollisionContext context){
        float scale=level.getBlockEntity(pos) instanceof GraveBlockEntity grave?grave.sourceScale():1;
        return box(8-6.8*scale,0,8-5.032*scale,8+6.8*scale,2.5*scale,8+5.032*scale);
    }
}
