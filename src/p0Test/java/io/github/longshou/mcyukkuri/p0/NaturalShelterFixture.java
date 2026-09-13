package io.github.longshou.mcyukkuri.p0;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

/** Test-only terrain, made before the encounter; production must never construct this. */
final class NaturalShelterFixture {
    static void cave(ServerLevel level,BlockPos mouth,Direction facing) {
        var side=facing.getClockWise();
        for(int x=-2;x<=2;x++)for(int depth=0;depth<=4;depth++)for(int y=0;y<=2;y++) {
            boolean solid=Math.abs(x)==2||depth==4||y==2||depth==0&&x!=0;
            var pos=mouth.relative(side,x).relative(facing.getOpposite(),depth).above(y);
            level.setBlockAndUpdate(pos,solid?(y==2?Blocks.GRASS_BLOCK:Blocks.DIRT).defaultBlockState():Blocks.AIR.defaultBlockState());
        }
    }
    static void clearCave(ServerLevel level,BlockPos mouth) {
        for(var pos:BlockPos.betweenClosed(mouth.offset(-5,0,-5),mouth.offset(5,3,5)))level.setBlockAndUpdate(pos,Blocks.AIR.defaultBlockState());
    }
    static void pit(ServerLevel level,BlockPos mouth) {
        for(int x=-1;x<=1;x++)for(int depth=0;depth<=2;depth++) {
            var pos=mouth.offset(x,0,-depth);
            level.setBlockAndUpdate(pos.below(),Blocks.DIRT.defaultBlockState());
            level.setBlockAndUpdate(pos,Blocks.AIR.defaultBlockState());
        }
    }
    static void region(ServerLevel level,BlockPos origin) {
        for(int x:new int[]{-12,-4,4,12})for(int z:new int[]{-10,-2,6,14})cave(level,origin.offset(x,0,z),Direction.SOUTH);
    }
    private NaturalShelterFixture() {}
}
