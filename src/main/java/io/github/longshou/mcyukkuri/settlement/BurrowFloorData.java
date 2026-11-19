package io.github.longshou.mcyukkuri.settlement;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

final class BurrowFloorData extends SavedData {
    final BurrowFloors floors=new BurrowFloors();
    private static final Factory<BurrowFloorData> FACTORY=new Factory<>(BurrowFloorData::new,BurrowFloorData::load);

    static BurrowFloorData of(ServerLevel level){return level.getDataStorage().computeIfAbsent(FACTORY,"mcyukkuri_burrow_floors");}
    void watch(BlockPos core,Set<Long> cells){if(floors.watch(core.asLong(),cells))setDirty();}
    void lost(long core,long cell){if(floors.lose(core,cell))setDirty();}

    private static BurrowFloorData load(CompoundTag tag,HolderLookup.Provider registries){
        var data=new BurrowFloorData();
        for(var raw:tag.getList("Homes",Tag.TAG_COMPOUND)){
            var home=(CompoundTag)raw;
            long core=home.getLong("Core");
            data.floors.watch(core,Arrays.stream(home.getLongArray("Floors")).boxed().collect(Collectors.toSet()));
            for(long cell:home.getLongArray("Lost"))data.floors.lose(core,cell);
        }
        return data;
    }
    @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider registries){
        var list=new ListTag();
        floors.homes.forEach((core,watch)->{
            var home=new CompoundTag();
            home.putLong("Core",core);
            home.putLongArray("Floors",watch.floors().stream().mapToLong(Long::longValue).toArray());
            home.putLongArray("Lost",watch.lost().stream().mapToLong(Long::longValue).toArray());
            list.add(home);
        });
        tag.put("Homes",list);
        return tag;
    }
}
