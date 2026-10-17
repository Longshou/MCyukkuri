package io.github.longshou.mcyukkuri.house;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/** Bounded, dimension-local activity records; unloaded time never becomes simulated activity. */
public final class HouseholdEcologyData extends SavedData {
    public static final int MAX_REGIONS=256;

    record Region(int x,int y,int z) {
        static Region at(BlockPos pos){return new Region(Math.floorDiv(pos.getX(),16),Math.floorDiv(pos.getY(),8),Math.floorDiv(pos.getZ(),16));}
        boolean contains(BlockPos pos){return equals(at(pos));}
    }
    static final class Area {
        long lastSample=-1,lastVillagerSample=-1,lastActive,cleanUntil,nextSettlement,budgetAt;
        int dustTicks,settlementTicks,villagerMealTicks;
        /** Event traces left by people (a meal, a night in bed, shearing). They refill slowly so busy kitchens cannot flood a room. */
        int lintBudget=HouseholdEcology.TRACE_BUDGET,crumbBudget=HouseholdEcology.TRACE_BUDGET;
        Area(long now){lastActive=now;budgetAt=now;}
        /** One unit per {@link HouseholdEcology#TRACE_REFILL} ticks, up to the cap; the clock only moves while this region is touched. */
        void refill(long now){
            long elapsed=now-budgetAt;if(elapsed<0){budgetAt=now;return;}
            int gained=(int)Math.min(HouseholdEcology.TRACE_BUDGET,elapsed/HouseholdEcology.TRACE_REFILL);
            if(gained>0){
                lintBudget=Math.min(HouseholdEcology.TRACE_BUDGET,lintBudget+gained);
                crumbBudget=Math.min(HouseholdEcology.TRACE_BUDGET,crumbBudget+gained);
                budgetAt+=gained*(long)HouseholdEcology.TRACE_REFILL;
            }
        }
        State state(){return new State(lastSample,lastActive,dustTicks,settlementTicks,cleanUntil,nextSettlement,lastVillagerSample,villagerMealTicks);}
    }
    public record State(long lastSample,long lastActive,int dustTicks,int settlementTicks,long cleanUntil,long nextSettlement,
                        long lastVillagerSample,int villagerMealTicks) {}

    private final Map<Region,Area> areas=new HashMap<>();

    public static HouseholdEcologyData get(ServerLevel level){
        return level.getDataStorage().computeIfAbsent(new Factory<>(HouseholdEcologyData::new,HouseholdEcologyData::load),"mcyukkuri_household_ecology");
    }
    Area find(Region region){return areas.get(region);}
    Area area(Region region,long now){
        var existing=areas.get(region);if(existing!=null)return existing;
        if(areas.size()>=MAX_REGIONS){
            var oldest=areas.entrySet().stream().min(Comparator.comparingLong(entry->entry.getValue().lastActive)).orElseThrow();
            areas.remove(oldest.getKey());
        }
        var created=new Area(now);areas.put(region,created);setDirty();return created;
    }
    /** A read-only view for diagnostics and save/load checks; reading does not refresh a region. */
    public State state(BlockPos pos){
        var area=find(Region.at(pos));return area==null?new State(-1,0,0,0,0,0,-1,0):area.state();
    }
    public int size(){return areas.size();}

    @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider registries){
        tag.putInt("Version",1);var entries=new ListTag();
        areas.forEach((region,area)->{
            var value=new CompoundTag();value.putInt("X",region.x());value.putInt("Y",region.y());value.putInt("Z",region.z());
            value.putLong("LastSample",area.lastSample);value.putLong("LastActive",area.lastActive);
            value.putInt("DustTicks",area.dustTicks);value.putInt("SettlementTicks",area.settlementTicks);
            value.putLong("LastVillagerSample",area.lastVillagerSample);value.putInt("VillagerMealTicks",area.villagerMealTicks);
            value.putLong("CleanUntil",area.cleanUntil);value.putLong("NextSettlement",area.nextSettlement);
            value.putInt("LintBudget",area.lintBudget);value.putInt("CrumbBudget",area.crumbBudget);value.putLong("BudgetAt",area.budgetAt);entries.add(value);
        });
        tag.put("Areas",entries);return tag;
    }
    public static HouseholdEcologyData load(CompoundTag tag,HolderLookup.Provider registries){
        var data=new HouseholdEcologyData();
        for(Tag raw:tag.getList("Areas",Tag.TAG_COMPOUND)){
            var value=(CompoundTag)raw;
            var region=new Region(value.getInt("X"),value.getInt("Y"),value.getInt("Z"));
            var area=new Area(Math.max(0,value.getLong("LastActive")));
            area.lastSample=value.contains("LastSample")?Math.max(-1,value.getLong("LastSample")):-1;
            area.dustTicks=Math.clamp(value.getInt("DustTicks"),0,HouseholdEcology.DUST_TICKS);
            area.settlementTicks=Math.clamp(value.getInt("SettlementTicks"),0,HouseholdEcology.SETTLEMENT_TICKS);
            area.lastVillagerSample=value.contains("LastVillagerSample")?Math.max(-1,value.getLong("LastVillagerSample")):-1;
            area.villagerMealTicks=Math.clamp(value.getInt("VillagerMealTicks"),0,HouseholdEcology.VILLAGER_MEAL_TICKS);
            area.cleanUntil=Math.max(0,value.getLong("CleanUntil"));area.nextSettlement=Math.max(0,value.getLong("NextSettlement"));
            if(value.contains("LintBudget")){
                area.lintBudget=Math.clamp(value.getInt("LintBudget"),0,HouseholdEcology.TRACE_BUDGET);
                area.crumbBudget=Math.clamp(value.getInt("CrumbBudget"),0,HouseholdEcology.TRACE_BUDGET);
                area.budgetAt=Math.max(0,value.getLong("BudgetAt"));
            }
            data.areas.put(region,area);
        }
        while(data.areas.size()>MAX_REGIONS){
            var oldest=data.areas.entrySet().stream().min(Comparator.comparingLong(entry->entry.getValue().lastActive)).orElseThrow();
            data.areas.remove(oldest.getKey());
        }
        return data;
    }
}
