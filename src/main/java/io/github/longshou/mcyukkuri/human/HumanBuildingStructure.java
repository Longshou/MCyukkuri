package io.github.longshou.mcyukkuri.human;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.*;
import net.minecraft.world.level.levelgen.structure.pools.*;
import net.minecraft.world.level.levelgen.structure.pools.alias.PoolAliasLookup;
import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;

/** A single vanilla pool piece, with dry, reasonably level ground under its full footprint. */
public final class HumanBuildingStructure extends Structure {
    public static final MapCodec<HumanBuildingStructure> CODEC=RecordCodecBuilder.mapCodec(i->i.group(
            settingsCodec(i),StructureTemplatePool.CODEC.fieldOf("start_pool").forGetter(s->s.pool)).apply(i,HumanBuildingStructure::new));
    private final Holder<StructureTemplatePool> pool;
    private HumanBuildingStructure(StructureSettings settings,Holder<StructureTemplatePool> pool) {super(settings);this.pool=pool;}
    @Override public Optional<GenerationStub> findGenerationPoint(GenerationContext c) {
        var origin=new BlockPos(c.chunkPos().getMinBlockX(),0,c.chunkPos().getMinBlockZ());
        var stub=JigsawPlacement.addPieces(c,pool,Optional.empty(),1,origin,false,Optional.of(Heightmap.Types.WORLD_SURFACE_WG),80,
                PoolAliasLookup.create(List.of(),origin,c.seed()),DimensionPadding.ZERO,LiquidSettings.APPLY_WATERLOGGING);
        if(stub.isEmpty())return Optional.empty();
        var pieces=stub.get().getPiecesBuilder();var box=pieces.getBoundingBox();
        int low=Integer.MAX_VALUE,high=Integer.MIN_VALUE;
        // A 3 x 3 lattice (corners, edge midpoints, centre of the rotated footprint), one noise column per point. Every column query rebuilds the noise
        // functions, so a dense grid (the old 3-block lattice: about 170 columns, 340 height queries per candidate chunk) stalled chunk generation and
        // /locate for minutes; the column also tells us whether the top block is water, so no second query for the ocean floor is needed.
        for(int ix=0;ix<3;ix++)for(int iz=0;iz<3;iz++) {
            int x=box.minX()+(box.maxX()-box.minX())*ix/2,z=box.minZ()+(box.maxZ()-box.minZ())*iz/2;
            var column=c.chunkGenerator().getBaseColumn(x,z,c.heightAccessor(),c.randomState());
            int floor=Integer.MIN_VALUE;
            for(int y=c.heightAccessor().getMaxBuildHeight()-1;y>=c.heightAccessor().getMinBuildHeight();y--) {
                var state=column.getBlock(y);
                if(state.isAir())continue;
                if(!state.getFluidState().isEmpty())return Optional.empty(); // water or lava on top: not a dry site
                floor=y+1;break;
            }
            if(floor==Integer.MIN_VALUE)return Optional.empty();
            low=Math.min(low,floor);high=Math.max(high,floor);
            if(high-low>3||floor<box.minY()-1||floor>box.minY()+2)return Optional.empty();
        }
        return Optional.of(new GenerationStub(stub.get().position(),Either.right(pieces)));
    }
    @Override public StructureType<?> type() {return HumanWorld.BUILDING_TYPE.get();}
}
