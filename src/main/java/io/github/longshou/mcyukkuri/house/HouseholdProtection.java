package io.github.longshou.mcyukkuri.house;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;

/** Timed spray anchors. All indexes are bounded, dimension-local and independent of loaded chunks. */
public final class HouseholdProtection extends SavedData {
    public static final int RADIUS=8,HEIGHT=4,DURATION=72_000,MAX_ANCHORS=256;
    private final Map<Long,Anchor> anchors=new HashMap<>();
    private final Map<Long,Set<Long>> chunks=new HashMap<>();
    private final TreeSet<Anchor> expiry=new TreeSet<>(Comparator.comparingLong(Anchor::until).thenComparingLong(Anchor::position));
    private record Anchor(long position,long until) {}

    public static HouseholdProtection get(ServerLevel level){
        return level.getDataStorage().computeIfAbsent(new Factory<>(HouseholdProtection::new,HouseholdProtection::load),"mcyukkuri_household_protection");
    }
    private static long chunk(BlockPos pos){return ChunkPos.asLong(pos.getX()>>4,pos.getZ()>>4);}
    /** Refresh the same anchor; at capacity, preserve every other still-active treatment. */
    public boolean spray(BlockPos pos,long now){
        prune(now);
        if(!anchors.containsKey(pos.asLong())&&anchors.size()>=MAX_ANCHORS)return false;
        remove(pos);insert(new Anchor(pos.asLong(),now+DURATION));
        setDirty();return true;
    }
    private void insert(Anchor anchor){
        anchors.put(anchor.position(),anchor);expiry.add(anchor);
        chunks.computeIfAbsent(chunk(BlockPos.of(anchor.position())),key->new HashSet<>()).add(anchor.position());
    }
    public void remove(BlockPos pos){
        var previous=anchors.remove(pos.asLong());if(previous==null)return;
        expiry.remove(previous);
        var entries=chunks.get(chunk(pos));
        if(entries!=null){entries.remove(pos.asLong());if(entries.isEmpty())chunks.remove(chunk(pos));}
        setDirty();
    }
    private void prune(long now){
        while(!expiry.isEmpty()&&expiry.first().until()<=now)remove(BlockPos.of(expiry.first().position()));
    }
    public int size(long now){prune(now);return anchors.size();}
    /** Remaining time at a point, using the longest covering treatment when areas overlap. */
    public long remainingTicks(BlockPos target,long now){
        prune(now);long remaining=0;
        int minX=(target.getX()-RADIUS)>>4,maxX=(target.getX()+RADIUS)>>4;
        int minZ=(target.getZ()-RADIUS)>>4,maxZ=(target.getZ()+RADIUS)>>4;
        for(int x=minX;x<=maxX;x++)for(int z=minZ;z<=maxZ;z++){
            var positions=chunks.get(ChunkPos.asLong(x,z));if(positions==null)continue;
            for(long position:positions){
                var pos=BlockPos.of(position);
                if(Math.abs(pos.getX()-target.getX())<=RADIUS&&Math.abs(pos.getZ()-target.getZ())<=RADIUS
                        &&Math.abs(pos.getY()-target.getY())<=HEIGHT)
                    remaining=Math.max(remaining,anchors.get(position).until()-now);
            }
        }
        return remaining;
    }
    public static boolean protectedAt(ServerLevel level,BlockPos target){
        return get(level).remainingTicks(target,level.getGameTime())>0;
    }
    @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider registries){
        tag.putInt("Version",2);var list=new ListTag();
        for(var anchor:expiry){var entry=new CompoundTag();entry.putLong("Pos",anchor.position());entry.putLong("Until",anchor.until());list.add(entry);}
        tag.put("Sprays",list);return tag;
    }
    public static HouseholdProtection load(CompoundTag tag,HolderLookup.Provider registries){
        var data=new HouseholdProtection();
        // Version 1 held permanent block fixtures. They can be recovered as finite spray bottles,
        // but neither those coordinates nor a missing expiry may grant permanent protection.
        if(tag.getInt("Version")!=2)return data;
        var list=tag.getList("Sprays",Tag.TAG_COMPOUND);
        for(int i=0;i<Math.min(list.size(),MAX_ANCHORS);i++){
            var entry=list.getCompound(i);
            if(!entry.contains("Pos",Tag.TAG_LONG)||!entry.contains("Until",Tag.TAG_LONG)||entry.getLong("Until")<=0)continue;
            var anchor=new Anchor(entry.getLong("Pos"),entry.getLong("Until"));
            var previous=data.anchors.get(anchor.position());
            if(previous!=null){if(previous.until()>=anchor.until())continue;data.remove(BlockPos.of(anchor.position()));}
            data.insert(anchor);
        }
        return data;
    }
}
