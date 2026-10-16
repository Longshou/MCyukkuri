package io.github.longshou.mcyukkuri.house;

import io.github.longshou.mcyukkuri.registry.ModBlocks;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;

/** Durable household fixtures, indexed by chunk; querying never loads their chunks. */
public final class HouseholdProtection extends SavedData {
    public static final int RADIUS=16,HEIGHT=8;
    private final Map<Long,Set<Long>> fixtures=new HashMap<>();
    public static HouseholdProtection get(ServerLevel level){
        return level.getDataStorage().computeIfAbsent(new Factory<>(HouseholdProtection::new,HouseholdProtection::load),"mcyukkuri_household_protection");
    }
    private static long chunk(BlockPos pos){return ChunkPos.asLong(pos.getX()>>4,pos.getZ()>>4);}
    public void add(BlockPos pos){if(fixtures.computeIfAbsent(chunk(pos),key->new HashSet<>()).add(pos.asLong()))setDirty();}
    public void remove(BlockPos pos){
        var entries=fixtures.get(chunk(pos));if(entries==null||!entries.remove(pos.asLong()))return;
        if(entries.isEmpty())fixtures.remove(chunk(pos));setDirty();
    }
    public static boolean protectedAt(ServerLevel level,BlockPos target){
        var data=get(level);int cx=target.getX()>>4,cz=target.getZ()>>4;
        for(int x=cx-1;x<=cx+1;x++)for(int z=cz-1;z<=cz+1;z++){
            var entries=data.fixtures.get(ChunkPos.asLong(x,z));if(entries==null)continue;
            var iterator=entries.iterator();
            while(iterator.hasNext()){
                var pos=BlockPos.of(iterator.next());
                // A saved fixture in an unloaded neighbor still protects across the chunk border.
                if(level.hasChunkAt(pos)&&!level.getBlockState(pos).is(ModBlocks.HOUSEHOLD_GUARD.get())){
                    iterator.remove();data.setDirty();continue;
                }
                if(Math.abs(pos.getX()-target.getX())<=RADIUS&&Math.abs(pos.getZ()-target.getZ())<=RADIUS
                        &&Math.abs(pos.getY()-target.getY())<=HEIGHT)return true;
            }
        }
        return false;
    }
    @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider registries){
        tag.putInt("Version",1);tag.putLongArray("Fixtures",fixtures.values().stream().flatMap(Set::stream).mapToLong(Long::longValue).toArray());return tag;
    }
    public static HouseholdProtection load(CompoundTag tag,HolderLookup.Provider registries){
        var data=new HouseholdProtection();for(long position:tag.getLongArray("Fixtures")){
            var pos=BlockPos.of(position);data.fixtures.computeIfAbsent(chunk(pos),key->new HashSet<>()).add(position);
        }return data;
    }
}
