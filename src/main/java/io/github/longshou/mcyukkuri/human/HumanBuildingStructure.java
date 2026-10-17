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
        // A 5 x 4 lattice that includes the exact corners and edges of the rotated footprint. Every height query builds noise columns, so a dense grid
        // (the old 3-block lattice was about 170 columns, 340 queries per candidate chunk) stalled chunk generation and /locate for minutes.
        int nx = 5, nz = 4;
        for(int ix=0;ix<nx;ix++)for(int iz=0;iz<nz;iz++) {
            int x=box.minX()+(box.maxX()-box.minX())*ix/(nx-1),z=box.minZ()+(box.maxZ()-box.minZ())*iz/(nz-1);
            int surface=c.chunkGenerator().getBaseHeight(x,z,Heightmap.Types.WORLD_SURFACE_WG,c.heightAccessor(),c.randomState());
            int floor=c.chunkGenerator().getBaseHeight(x,z,Heightmap.Types.OCEAN_FLOOR_WG,c.heightAccessor(),c.randomState());
            if(surface!=floor)return Optional.empty();
            low=Math.min(low,floor);high=Math.max(high,floor);
            if(high-low>3||floor<box.minY()-1||floor>box.minY()+2)return Optional.empty();
        }
        return Optional.of(new GenerationStub(stub.get().position(),Either.right(pieces)));
    }
    @Override public StructureType<?> type() {return HumanWorld.BUILDING_TYPE.get();}
}
