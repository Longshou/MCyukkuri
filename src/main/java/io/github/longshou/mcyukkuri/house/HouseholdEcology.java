package io.github.longshou.mcyukkuri.house;

import io.github.longshou.mcyukkuri.family.FamilyConfig;
import io.github.longshou.mcyukkuri.registry.ModBlocks;
import io.github.longshou.mcyukkuri.registry.ModItems;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Natural indoor debris and settlement, driven by nearby player presence and finite real supplies. */
public final class HouseholdEcology {
    public static final int SAMPLE_TICKS=200,DUST_TICKS=600,SETTLEMENT_TICKS=1200;
    public static final int CLEAN_TICKS=2400,SETTLEMENT_COOLDOWN=24000,MAX_PILES=8,MAX_SUPPLIES=24;
    public static final int MAX_CHUNK_PILES=16,MAX_CHUNK_SUPPLIES=64;
    public static final int VILLAGER_MEAL_TICKS=1200,MAX_ACTIVITY_SOURCES=64;
    public static final int MAX_SOURCE_INSPECTIONS=4096,MAX_LEVELS_PER_SAMPLE=8;
    private static final int SEARCH_RADIUS=4;
    private static final int[] HEIGHT_OFFSETS={0,-1,1};
    private static final Map<ServerLevel,ActivitySources> SOURCES=new WeakHashMap<>();
    private static final Map<MinecraftServer,Integer> DIMENSION_CURSORS=new WeakHashMap<>();
    private static final class ScanBudget {
        int remaining=MAX_SOURCE_INSPECTIONS;
        boolean take(){if(remaining<=0)return false;remaining--;return true;}
    }
    private static final class ActivitySources {
        final Set<UUID> loaded=new HashSet<>();
        final ArrayDeque<UUID> villagers=new ArrayDeque<>();
        int playerCursor;
    }
    public record Stock(int piles,int supplies) {}
    public record Snapshot(HouseholdEcologyData.State activity,Stock stock,boolean suppressed,boolean recentlyActive) {}

    /** Register once on the game event bus. No ticker is attached to individual material piles. */
    public static void tick(ServerTickEvent.Post event){
        if(event.getServer().getTickCount()%SAMPLE_TICKS!=0)return;
        var server=event.getServer();var levels=new ArrayList<ServerLevel>();server.getAllLevels().forEach(levels::add);
        if(levels.isEmpty())return;
        int cursor=DIMENSION_CURSORS.getOrDefault(server,0),visited=0,remaining=MAX_ACTIVITY_SOURCES;var budget=new ScanBudget();
        while(visited<Math.min(levels.size(),MAX_LEVELS_PER_SAMPLE)&&remaining>0&&budget.remaining>0){
            var level=levels.get(Math.floorMod(cursor+visited++,levels.size()));
            remaining-=sampleLoaded(level,remaining,budget);
        }
        DIMENSION_CURSORS.put(server,Math.floorMod(cursor+visited,levels.size()));
    }
    public static void joining(EntityJoinLevelEvent event){
        if(event.getLevel() instanceof ServerLevel level&&event.getEntity() instanceof Villager villager){
            var sources=SOURCES.computeIfAbsent(level,key->new ActivitySources());
            if(sources.loaded.add(villager.getUUID()))sources.villagers.addLast(villager.getUUID());
        }
    }
    public static void leaving(EntityLeaveLevelEvent event){
        if(event.getLevel() instanceof ServerLevel level&&event.getEntity() instanceof Villager villager){
            var sources=SOURCES.get(level);if(sources==null)return;
            sources.loaded.remove(villager.getUUID());sources.villagers.remove(villager.getUUID());
        }
    }
    /** Round-robin loaded actors only; leave at least sixteen slots for villagers and reuse any spare budget. */
    public static void sampleLoaded(ServerLevel level){sampleLoaded(level,MAX_ACTIVITY_SOURCES,new ScanBudget());}
    private static int sampleLoaded(ServerLevel level,int limit,ScanBudget budget){
        if(!enabled(level))return 0;
        var sources=SOURCES.computeIfAbsent(level,key->new ActivitySources());
        var activities=new ArrayList<BlockPos>();var villagers=new ArrayList<Villager>();var sampledPlayers=new HashSet<UUID>();
        addPlayers(level,sources,activities,sampledPlayers,sources.villagers.isEmpty()?limit:Math.max(0,limit-16),budget);
        int inspect=sources.villagers.size();
        while(inspect-->0&&activities.size()<limit&&budget.take()){
            var id=sources.villagers.removeFirst();
            if(!(level.getEntity(id) instanceof Villager villager)||!villager.isAlive()||villager.isRemoved()){
                sources.loaded.remove(id);continue;
            }
            sources.villagers.addLast(id);
            if(HouseholdProtection.protectedAt(level,villager.blockPosition()))continue;
            activities.add(villager.blockPosition());villagers.add(villager);
        }
        addPlayers(level,sources,activities,sampledPlayers,limit,budget);
        sample(level,activities);villagers(level,villagers);return activities.size();
    }
    private static void addPlayers(ServerLevel level,ActivitySources sources,List<BlockPos> activities,Set<UUID> sampled,int limit,ScanBudget budget){
        var players=level.players();if(players.isEmpty())return;
        int inspect=players.size();
        while(inspect-->0&&activities.size()<limit&&budget.take()){
            var player=players.get(Math.floorMod(sources.playerCursor++,players.size()));
            if(player.isAlive()&&!player.isSpectator()&&!(player instanceof FakePlayer)&&!HouseholdProtection.protectedAt(level,player.blockPosition())
                    &&sampled.add(player.getUUID()))activities.add(player.blockPosition());
        }
    }
    public static void finished(LivingEntityUseItemEvent.Finish event){
        if(event.getEntity() instanceof ServerPlayer player&&!(player instanceof FakePlayer)&&player.isAlive()&&!player.isSpectator()
                &&event.getItem().has(DataComponents.FOOD))meal(player.serverLevel(),player.blockPosition());
    }
    public static boolean enabled(ServerLevel level){
        return FamilyConfig.HOUSE_ARRIVALS.get()&&level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING)
                &&level.getGameRules().getBoolean(GameRules.RULE_DOMOBSPAWNING);
    }
    /** Any non-leaf roof counts, including high halls and occupied caves; no light or furniture requirement. */
    public static boolean covered(ServerLevel level,BlockPos floor){
        return level.isInWorldBounds(floor)&&level.hasChunkAt(floor)
                &&level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,floor.getX(),floor.getZ())>floor.getY();
    }
    /** Inputs are observed player/villager foot positions. Repeated calls in one bucket cannot accelerate a region. */
    public static void sample(ServerLevel level,Collection<BlockPos> activities){
        if(!enabled(level)||activities.isEmpty())return;
        Map<HouseholdEcologyData.Region,Set<BlockPos>> regions=new HashMap<>();
        long now=level.getGameTime(),bucket=Math.floorDiv(now,SAMPLE_TICKS);var data=HouseholdEcologyData.get(level);
        var origins=new HashSet<BlockPos>();var view=new SupplyPileView(level);
        int sampled=0;
        for(var activity:activities){
            if(sampled++>=MAX_ACTIVITY_SOURCES)break;
            if(!origins.add(activity))continue;
            if(HouseholdProtection.protectedAt(level,activity))continue;
            for(var floor:reachable(level,activity,SEARCH_RADIUS))if(!HouseholdProtection.protectedAt(level,floor))
                regions.computeIfAbsent(HouseholdEcologyData.Region.at(floor),key->new LinkedHashSet<>()).add(floor);
        }
        for(var entry:regions.entrySet()){
            var floors=entry.getValue();
            if(floors.stream().noneMatch(pos->covered(level,pos)))continue;
            var area=data.area(entry.getKey(),now);
            if(area.lastSample>=bucket)continue;
            area.lastSample=bucket;area.lastActive=now;data.setDirty();
            if(now<area.cleanUntil)continue;
            var point=floors.iterator().next();var stock=view.region(point);var column=view.column(point);
            if(stock.supplies()<MAX_SUPPLIES&&stock.piles()<=MAX_PILES&&column.supplies()<MAX_CHUNK_SUPPLIES&&column.piles()<=MAX_CHUNK_PILES){
                area.dustTicks=Math.min(DUST_TICKS,area.dustTicks+SAMPLE_TICKS);
                if(area.dustTicks>=DUST_TICKS&&dust(level,floors,view))area.dustTicks=0;
            }
            if(now<area.nextSettlement)continue;
            area.settlementTicks=Math.min(SETTLEMENT_TICKS,area.settlementTicks+SAMPLE_TICKS);
            if(area.settlementTicks>=SETTLEMENT_TICKS&&settle(level,floors,view)){
                area.settlementTicks=0;area.nextSettlement=now+SETTLEMENT_COOLDOWN;
            }
        }
    }
    /** Real meal completion produces at most one scrap, outdoors too; full or invalid ground produces no loose fallback. */
    public static boolean meal(ServerLevel level,BlockPos origin){
        return residual(level,origin,new ItemStack(ModItems.FOOD_SCRAP.get()))==1;
    }
    /** Returns the amount accepted, without mutating the input. Callers debit only this real ground transfer. */
    public static int residual(ServerLevel level,BlockPos origin,ItemStack stack){return residual(level,origin,stack,false);}
    /** {@code fromDrop} marks stock settled from a loose item entity: residents notice it by day and neglected units rot. */
    public static int residual(ServerLevel level,BlockPos origin,ItemStack stack,boolean fromDrop){
        if(!enabled(level)||!SupplyPiles.material(stack)||HouseholdProtection.protectedAt(level,origin))return 0;
        int remaining=stack.getCount();var view=new SupplyPileView(level);
        for(var pos:ranked(level,reachable(level,origin,2),stack,origin)){
            if(HouseholdProtection.protectedAt(level,pos))continue;
            int capacity=capacity(level,pos,view);if(capacity==0)continue;
            int added=SupplyPiles.offer(level,pos,stack,Math.min(remaining,capacity),fromDrop);remaining-=added;
            if(added>0)view.changed(pos);
            if(remaining==0)break;
        }
        return stack.getCount()-remaining;
    }
    /** At most one real villager food item per active region per 1200 sampled ticks, regardless of population. */
    public static int villagers(ServerLevel level,Collection<Villager> villagers){
        if(!enabled(level)||villagers.isEmpty())return 0;
        Map<HouseholdEcologyData.Region,List<Villager>> regions=new HashMap<>();int sampled=0;
        for(var villager:villagers){
            if(sampled++>=MAX_ACTIVITY_SOURCES)break;
            if(villager.level()!=level||!villager.isAlive()||villager.isRemoved()||suppressed(level,villager.blockPosition()))continue;
            regions.computeIfAbsent(HouseholdEcologyData.Region.at(villager.blockPosition()),key->new ArrayList<>()).add(villager);
        }
        var data=HouseholdEcologyData.get(level);long bucket=Math.floorDiv(level.getGameTime(),SAMPLE_TICKS);int meals=0;
        for(var entry:regions.entrySet()){
            var area=data.find(entry.getKey());
            if(area==null||area.lastSample!=bucket||area.lastVillagerSample>=bucket)continue;
            area.lastVillagerSample=bucket;area.villagerMealTicks=Math.min(VILLAGER_MEAL_TICKS,area.villagerMealTicks+SAMPLE_TICKS);data.setDirty();
            if(area.villagerMealTicks<VILLAGER_MEAL_TICKS)continue;
            for(var villager:entry.getValue()){
                var inventory=villager.getInventory();boolean ate=false;
                for(int slot=0;slot<inventory.getContainerSize();slot++){
                    var food=inventory.getItem(slot);
                    if(food.getCount()<16||!(food.is(Items.BREAD)||food.is(Items.CARROT)||food.is(Items.POTATO)||food.is(Items.BEETROOT)))continue;
                    // The ground write succeeds before debiting the actual inventory; no legal ground means no meal or loss.
                    if(meal(level,villager.blockPosition())){inventory.removeItem(slot,1);area.villagerMealTicks=0;meals++;ate=true;}
                    break;
                }
                if(ate)break;
            }
        }
        return meals;
    }
    /** Cleaning is remembered even if no dust or nest exists yet, and does not erase a longer settlement cooldown. */
    public static void cleaned(ServerLevel level,BlockPos pos){
        var data=HouseholdEcologyData.get(level);long now=level.getGameTime();
        var area=data.area(HouseholdEcologyData.Region.at(pos),now);
        area.cleanUntil=Math.max(area.cleanUntil,now+CLEAN_TICKS);area.nextSettlement=Math.max(area.nextSettlement,area.cleanUntil);
        area.dustTicks=0;area.settlementTicks=0;area.villagerMealTicks=0;area.lastSample=Math.floorDiv(now,SAMPLE_TICKS);
        area.lastVillagerSample=area.lastSample;area.lastActive=now;data.setDirty();
    }
    public static boolean suppressed(ServerLevel level,BlockPos pos){
        return HouseholdProtection.protectedAt(level,pos)||level.getGameTime()<HouseholdEcologyData.get(level).state(pos).cleanUntil();
    }
    public static boolean recentlyActive(ServerLevel level,BlockPos pos){
        var state=HouseholdEcologyData.get(level).state(pos);long age=level.getGameTime()-state.lastActive();
        return state.lastSample()>=0&&age>=0&&age<=SETTLEMENT_TICKS&&!suppressed(level,pos);
    }
    public static Snapshot snapshot(ServerLevel level,BlockPos pos){
        var stock=new SupplyPileView(level).region(pos);
        return new Snapshot(HouseholdEcologyData.get(level).state(pos),new Stock(stock.piles(),stock.supplies()),suppressed(level,pos),recentlyActive(level,pos));
    }
    private static boolean dust(ServerLevel level,Collection<BlockPos> floors,SupplyPileView view){
        var lint=new ItemStack(ModItems.NEST_LINT.get());var origin=floors.iterator().next();
        for(var pos:ranked(level,floors,lint,origin))
            if(!suppressed(level,pos)&&capacity(level,pos,view)>0&&covered(level,pos)&&SupplyPiles.offer(level,pos,lint,1)==1){view.changed(pos);return true;}
        return false;
    }
    private static int capacity(ServerLevel level,BlockPos pos,SupplyPileView view){
        var stock=view.region(pos);var column=view.column(pos);boolean existing=level.getBlockEntity(pos) instanceof SupplyPileBlockEntity;
        if(stock.piles()>MAX_PILES||stock.piles()==MAX_PILES&&!existing||column.piles()>MAX_CHUNK_PILES||column.piles()==MAX_CHUNK_PILES&&!existing)return 0;
        return Math.max(0,Math.min(MAX_SUPPLIES-stock.supplies(),MAX_CHUNK_SUPPLIES-column.supplies()));
    }
    private static boolean settle(ServerLevel level,Collection<BlockPos> floors,SupplyPileView view){
        var source=floors.stream().map(level::getBlockEntity).filter(entity->entity instanceof SupplyPileBlockEntity)
                .map(entity->(SupplyPileBlockEntity)entity).filter(pile->pile.count()>0&&(pile.is(ModItems.NEST_LINT.get())||pile.is(ModItems.FOOD_SCRAP.get())))
                .findFirst().orElse(null);
        if(source==null)return false;
        var candidates=floors.stream().map(pos->new Candidate(pos,0,wallScore(level,pos),pos.distSqr(source.getBlockPos())))
                .sorted(CANDIDATE_ORDER).toList();
        for(var candidate:candidates){
            var pos=candidate.pos();
            var entrance=findEntrance(level,pos);
            if(suppressed(level,pos)||!level.isEmptyBlock(pos)||!shelteredCorner(level,pos)||entrance==null||!view.nestFree(pos,16))continue;
            if(!level.setBlockAndUpdate(pos,ModBlocks.HOUSE_DUST_NEST.get().defaultBlockState()))continue;
            if(!(level.getBlockEntity(pos) instanceof HouseNestBlockEntity nest)){level.removeBlock(pos,false);return false;}
            nest.setEntrance(entrance);
            // The selected pile is still the sole source. Placement never creates a free first unit of bedding or food.
            var material=source.takeOne();
            view.changed(source.getBlockPos());
            if(!material.isEmpty()&&nest.deposit(material)){view.changed(pos);return true;}
            if(!material.isEmpty())SupplyPiles.offer(level,source.getBlockPos(),material,1);
            level.removeBlock(pos,false);view.changed(source.getBlockPos());view.changed(pos);return false;
        }
        return false;
    }
    private record Candidate(BlockPos pos,int existing,int wall,double distance) {}
    private static final Comparator<Candidate> CANDIDATE_ORDER=Comparator.comparingInt(Candidate::existing).reversed()
            .thenComparing(Comparator.comparingInt(Candidate::wall).reversed()).thenComparingDouble(Candidate::distance)
            .thenComparingLong(candidate->candidate.pos().asLong());
    private static List<BlockPos> ranked(ServerLevel level,Collection<BlockPos> floors,ItemStack material,BlockPos origin){
        var ranked=new ArrayList<Candidate>();
        for(var pos:floors){
            var entity=level.getBlockEntity(pos);
            if(level.isEmptyBlock(pos)||entity instanceof SupplyPileBlockEntity pile&&pile.accepts(material)&&pile.count()<SupplyPileBlockEntity.CAPACITY)
                ranked.add(new Candidate(pos,entity instanceof SupplyPileBlockEntity?1:0,wallScore(level,pos),pos.distSqr(origin)));
        }
        ranked.sort(CANDIDATE_ORDER);return ranked.stream().map(Candidate::pos).toList();
    }
    /** Homes need real shelter beside a wall or furniture, never just an empty room centre. */
    public static boolean shelteredCorner(ServerLevel level,BlockPos pos){
        return walkable(level,pos)&&covered(level,pos)&&wallScore(level,pos)>0;
    }
    public static Direction findEntrance(ServerLevel level,BlockPos pos){
        // Prefer the old north-facing layout when it is still accessible.
        for(var direction:new Direction[]{Direction.NORTH,Direction.EAST,Direction.SOUTH,Direction.WEST})
            if(walkable(level,pos.relative(direction))&&covered(level,pos.relative(direction)))return direction;
        return null;
    }
    private static int wallScore(ServerLevel level,BlockPos pos){
        int score=0;
        for(var direction:Direction.Plane.HORIZONTAL){
            var neighbor=pos.relative(direction);if(!level.hasChunkAt(neighbor))continue;
            var state=level.getBlockState(neighbor);
            if(!state.getCollisionShape(level,neighbor).isEmpty())score+=4;
            var entity=level.getBlockEntity(neighbor);
            if(state.getBlock() instanceof BedBlock||entity!=null&&!(entity instanceof SupplyPileBlockEntity)&&!(entity instanceof HouseNestBlockEntity))score++;
        }
        return score;
    }
    /** A small local ground search keeps output on reachable floors rather than through adjacent room walls. */
    private static Set<BlockPos> reachable(ServerLevel level,BlockPos origin,int radius){
        var result=new LinkedHashSet<BlockPos>();var queue=new ArrayDeque<BlockPos>();var visited=new HashSet<BlockPos>();
        for(int dy:HEIGHT_OFFSETS){var start=origin.offset(0,dy,0);if(walkable(level,start)){queue.add(start);visited.add(start);break;}}
        while(!queue.isEmpty()){
            var pos=queue.removeFirst();result.add(pos);
            for(var direction:Direction.Plane.HORIZONTAL)for(int dy:HEIGHT_OFFSETS){
                var next=pos.relative(direction).offset(0,dy,0);
                int dx=next.getX()-origin.getX(),dz=next.getZ()-origin.getZ();
                if(dx*dx+dz*dz>radius*radius||Math.abs(next.getY()-origin.getY())>1||visited.contains(next))continue;
                if(!walkable(level,next)||dy>0&&!clear(level,pos.above())||dy<0&&!clear(level,next.above()))continue;
                visited.add(next);queue.addLast(next);
            }
        }
        return result;
    }
    /** Nearest open, supported floor cell within the radius (lower or higher by a couple of blocks), or null. Reads loaded chunks only. */
    public static BlockPos groundNear(ServerLevel level,BlockPos origin,int radius){
        BlockPos best=null;double bestDistance=Double.MAX_VALUE;
        for(int dx=-radius;dx<=radius;dx++)for(int dz=-radius;dz<=radius;dz++)for(int dy=-2;dy<=1;dy++){
            var pos=origin.offset(dx,dy,dz);
            if(!walkable(level,pos)||!clear(level,pos.above())||HouseholdProtection.protectedAt(level,pos))continue;
            double distance=pos.distSqr(origin);
            if(distance<bestDistance){best=pos.immutable();bestDistance=distance;}
        }
        return best;
    }
    /** True when a loose item in this cell lies on a visible, reachable floor rather than inside or under solid blocks. */
    public static boolean openFloor(ServerLevel level,BlockPos pos){return walkable(level,pos);}
    private static boolean walkable(ServerLevel level,BlockPos pos){return clear(level,pos)&&SupplyPiles.supported(level,pos);}
    private static boolean clear(ServerLevel level,BlockPos pos){
        return level.isInWorldBounds(pos)&&level.hasChunkAt(pos)&&level.getFluidState(pos).isEmpty()
                &&level.getBlockState(pos).getCollisionShape(level,pos).isEmpty();
    }
    private HouseholdEcology(){}
}
