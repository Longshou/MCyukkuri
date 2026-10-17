package io.github.longshou.mcyukkuri.house;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

/** One operation's view of loaded chunks. Each chunk BE map is traversed once; writes update the view in place. */
public final class SupplyPileView {
    public record Stock(int piles,int supplies) {
        private Stock plus(int cells,int units){return new Stock(piles+cells,supplies+units);}
    }
    private static final Stock EMPTY=new Stock(0,0);
    private static final class ChunkStock {
        final boolean loaded;
        final Map<BlockPos,SupplyPileBlockEntity> piles=new HashMap<>();
        final Map<BlockPos,Integer> counts=new HashMap<>();
        final Map<Integer,Stock> bands=new HashMap<>();
        final Map<BlockPos,HouseNestBlockEntity> nests=new HashMap<>();
        Stock total=EMPTY;
        ChunkStock(boolean loaded){this.loaded=loaded;}
        void update(BlockPos pos,SupplyPileBlockEntity pile){
            int before=counts.getOrDefault(pos,0),after=pile==null?0:pile.count();
            if(after==0){counts.remove(pos);piles.remove(pos);}else{counts.put(pos,after);piles.put(pos,pile);}
            int cells=(after>0?1:0)-(before>0?1:0),units=after-before,band=Math.floorDiv(pos.getY(),8);
            total=total.plus(cells,units);bands.put(band,bands.getOrDefault(band,EMPTY).plus(cells,units));
        }
    }
    private final ServerLevel level;
    private final Map<Long,ChunkStock> chunks=new HashMap<>();
    private int scannedChunks;
    public SupplyPileView(ServerLevel level){this.level=level;}
    private ChunkStock chunk(int x,int z){
        long key=ChunkPos.asLong(x,z);var cached=chunks.get(key);if(cached!=null)return cached;
        var loaded=level.getChunkSource().getChunkNow(x,z);var result=new ChunkStock(loaded!=null);chunks.put(key,result);
        if(loaded!=null){
            scannedChunks++;
            for(var entity:loaded.getBlockEntities().values()){
                if(entity.isRemoved())continue;
                if(entity instanceof SupplyPileBlockEntity pile&&pile.count()>0)result.update(pile.getBlockPos(),pile);
                else if(entity instanceof HouseNestBlockEntity nest)result.nests.put(nest.getBlockPos(),nest);
            }
        }
        return result;
    }
    public Stock region(BlockPos pos){return chunk(pos.getX()>>4,pos.getZ()>>4).bands.getOrDefault(Math.floorDiv(pos.getY(),8),EMPTY);}
    public Stock column(BlockPos pos){return chunk(pos.getX()>>4,pos.getZ()>>4).total;}
    /** Call after this operation adds, withdraws or removes stock. No second full chunk scan is needed. */
    public void changed(BlockPos pos){
        var cached=chunks.get(ChunkPos.asLong(pos.getX()>>4,pos.getZ()>>4));if(cached==null||!cached.loaded)return;
        var entity=level.getBlockEntity(pos);
        cached.update(pos,entity instanceof SupplyPileBlockEntity pile&&!pile.isRemoved()?pile:null);
        if(entity instanceof HouseNestBlockEntity nest&&!nest.isRemoved())cached.nests.put(pos,nest);else cached.nests.remove(pos);
    }
    public List<SupplyPileBlockEntity> nearby(BlockPos center,int radius,int vertical){
        var result=new ArrayList<SupplyPileBlockEntity>();
        for(int x=(center.getX()-radius)>>4;x<=((center.getX()+radius)>>4);x++)
            for(int z=(center.getZ()-radius)>>4;z<=((center.getZ()+radius)>>4);z++)
                for(var pile:chunk(x,z).piles.values()){
                    var pos=pile.getBlockPos();
                    if(Math.abs(pos.getX()-center.getX())<=radius&&Math.abs(pos.getZ()-center.getZ())<=radius
                            &&Math.abs(pos.getY()-center.getY())<=vertical&&!pile.isRemoved()&&pile.count()>0)result.add(pile);
                }
        return result;
    }
    public List<HouseNestBlockEntity> nearbyNests(BlockPos center,int radius){
        var result=new ArrayList<HouseNestBlockEntity>();
        for(int x=(center.getX()-radius)>>4;x<=((center.getX()+radius)>>4);x++)
            for(int z=(center.getZ()-radius)>>4;z<=((center.getZ()+radius)>>4);z++){
                var stock=chunk(x,z);if(!stock.loaded)continue;
                for(var nest:stock.nests.values())if(!nest.isRemoved()&&nest.getBlockPos().distSqr(center)<=radius*radius)result.add(nest);
            }
        return result;
    }
    public boolean nestFree(BlockPos pos,int radius){
        for(int x=(pos.getX()-radius)>>4;x<=((pos.getX()+radius)>>4);x++)
            for(int z=(pos.getZ()-radius)>>4;z<=((pos.getZ()+radius)>>4);z++){
                var stock=chunk(x,z);if(!stock.loaded)return false;
                for(var nest:stock.nests.values())if(!nest.isRemoved()&&nest.getBlockPos().distSqr(pos)<=radius*radius)return false;
            }
        return true;
    }
    /** Diagnostic count for budget checks; unrelated queries and local mutations do not rescan a cached chunk. */
    public int scannedChunks(){return scannedChunks;}
}
