package io.github.longshou.mcyukkuri.house;

import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyConfig;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.registry.ModBlocks;
import io.github.longshou.mcyukkuri.registry.ModEntities;
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
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.entity.player.PlayerWakeUpEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * The household as a closed loop. People living in a roofed place leave traces: dust and clothing fibre while they walk
 * about and sleep, crumbs when they eat or prepare food. The traces settle as thin piles at the foot of walls and
 * furniture (finite, capped, slowly decaying) and nowhere else: nothing is ever handed to a player. When enough lint
 * and crumbs have gathered next to a hidden corner nobody is looking at, a house yukkuri moves in there and begins to
 * collect them ({@link HouseLife}). Everything is driven by loaded players and villagers; unloaded time is never simulated.
 */
public final class HouseholdEcology {
    public static final int SAMPLE_TICKS=200,DUST_TICKS=600,SETTLEMENT_TICKS=1200;
    public static final int CLEAN_TICKS=2400,SETTLEMENT_COOLDOWN=24000,MAX_PILES=8,MAX_SUPPLIES=32;
    public static final int MAX_CHUNK_PILES=16,MAX_CHUNK_SUPPLIES=64;
    /** Lint and crumbs each fill at most this much of a region, so a pile of unused lint cannot stop the crumbs from landing. */
    public static final int MAX_LINT=14,MAX_CRUMBS=12;
    public static final int VILLAGER_MEAL_TICKS=1200,MAX_ACTIVITY_SOURCES=64;
    public static final int MAX_SOURCE_INSPECTIONS=4096,MAX_LEVELS_PER_SAMPLE=8;
    /** Event traces (a meal, a night's sleep, shearing) per region and kind, refilled one per {@link #TRACE_REFILL} ticks. */
    public static final int TRACE_BUDGET=4,TRACE_REFILL=200;
    /** A newcomer needs at least this much lint and in total this much debris within the room's reachable floor. */
    public static final int ARRIVAL_LINT=2,ARRIVAL_SUPPLY=5;
    /** Humans closer than this to the new home site, or with a clear line of sight within {@link #WATCH_RANGE}, delay a move-in. */
    public static final double WATCH_RANGE=12;
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
    public record Stock(int piles,int supplies,int lint,int crumbs) {}
    public record Snapshot(HouseholdEcologyData.State activity,Stock stock,boolean suppressed,boolean recentlyActive) {}
    /** A hidden corner suitable for a home: the cell itself and the open side from which it is entered. */
    public record Den(BlockPos site,Direction entrance) {}

    // ------------------------------------------------------------------------------------------------ sampling of presence
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
        var sleepers=new HashSet<BlockPos>();
        addPlayers(level,sources,activities,sleepers,sampledPlayers,sources.villagers.isEmpty()?limit:Math.max(0,limit-16),budget);
        int inspect=sources.villagers.size();
        while(inspect-->0&&activities.size()<limit&&budget.take()){
            var id=sources.villagers.removeFirst();
            if(!(level.getEntity(id) instanceof Villager villager)||!villager.isAlive()||villager.isRemoved()){
                sources.loaded.remove(id);continue;
            }
            sources.villagers.addLast(id);
            if(HouseholdProtection.protectedAt(level,villager.blockPosition()))continue;
            activities.add(villager.blockPosition());villagers.add(villager);
            if(villager.isSleeping())sleepers.add(villager.blockPosition());
        }
        addPlayers(level,sources,activities,sleepers,sampledPlayers,limit,budget);
        sample(level,activities,sleepers);villagers(level,villagers);return activities.size();
    }
    private static void addPlayers(ServerLevel level,ActivitySources sources,List<BlockPos> activities,Set<BlockPos> sleepers,Set<UUID> sampled,int limit,ScanBudget budget){
        var players=level.players();if(players.isEmpty())return;
        int inspect=players.size();
        while(inspect-->0&&activities.size()<limit&&budget.take()){
            var player=players.get(Math.floorMod(sources.playerCursor++,players.size()));
            if(player.isAlive()&&!player.isSpectator()&&!(player instanceof FakePlayer)&&!HouseholdProtection.protectedAt(level,player.blockPosition())
                    &&sampled.add(player.getUUID())){
                activities.add(player.blockPosition());
                if(player.isSleeping())sleepers.add(player.blockPosition());
            }
        }
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
    public static void sample(ServerLevel level,Collection<BlockPos> activities){sample(level,activities,Set.of());}
    /** Somebody asleep in a bed sheds fibre twice as fast as somebody walking about. */
    public static void sample(ServerLevel level,Collection<BlockPos> activities,Set<BlockPos> sleepers){
        if(!enabled(level)||activities.isEmpty())return;
        Map<HouseholdEcologyData.Region,Set<BlockPos>> regions=new HashMap<>();Set<HouseholdEcologyData.Region> sleeping=new HashSet<>();
        long now=level.getGameTime(),bucket=Math.floorDiv(now,SAMPLE_TICKS);var data=HouseholdEcologyData.get(level);
        var origins=new HashSet<BlockPos>();var view=new SupplyPileView(level);
        int sampled=0;
        for(var activity:activities){
            if(sampled++>=MAX_ACTIVITY_SOURCES)break;
            if(!origins.add(activity))continue;
            if(HouseholdProtection.protectedAt(level,activity))continue;
            for(var floor:reachable(level,activity,SEARCH_RADIUS))if(!HouseholdProtection.protectedAt(level,floor))
                regions.computeIfAbsent(HouseholdEcologyData.Region.at(floor),key->new LinkedHashSet<>()).add(floor);
            if(sleepers.contains(activity))sleeping.add(HouseholdEcologyData.Region.at(activity));
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
                area.dustTicks=Math.min(DUST_TICKS,area.dustTicks+SAMPLE_TICKS*(sleeping.contains(entry.getKey())?2:1));
                if(area.dustTicks>=DUST_TICKS&&dust(level,floors,view))area.dustTicks=0;
            }
            if(now<area.nextSettlement)continue;
            area.settlementTicks=Math.min(SETTLEMENT_TICKS,area.settlementTicks+SAMPLE_TICKS);
            if(area.settlementTicks>=SETTLEMENT_TICKS&&arrive(level,floors,view)){
                area.settlementTicks=0;area.nextSettlement=now+SETTLEMENT_COOLDOWN;
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ traces of activity
    /** Real meal completion by a player: at most one crumb, only under a roof. */
    public static boolean meal(ServerLevel level,BlockPos origin){return trace(level,origin,SupplyPiles.crumbUnit(),1)==1;}
    public static void finished(LivingEntityUseItemEvent.Finish event){
        if(event.getEntity() instanceof ServerPlayer player&&!(player instanceof FakePlayer)&&player.isAlive()&&!player.isSpectator()
                &&event.getItem().has(DataComponents.FOOD))meal(player.serverLevel(),player.blockPosition());
    }
    /** A night in a bed shakes out fibre from sheets and blankets. */
    public static void woke(PlayerWakeUpEvent event){
        if(event.getEntity() instanceof ServerPlayer player&&!(player instanceof FakePlayer))trace(player.serverLevel(),player.blockPosition(),SupplyPiles.lintUnit(),2);
    }
    /** Tearing out wool, carpet or a bed frees fibre; cutting a ripe crop indoors scatters trimmings. */
    public static void broke(BlockEvent.BreakEvent event){
        if(!(event.getLevel() instanceof ServerLevel level)||!(event.getPlayer() instanceof ServerPlayer player)||player instanceof FakePlayer)return;
        var state=event.getState();
        if(state.is(BlockTags.WOOL)||state.is(BlockTags.WOOL_CARPETS)||state.is(BlockTags.BEDS))trace(level,player.blockPosition(),SupplyPiles.lintUnit(),2);
        else if(state.getBlock() instanceof CropBlock crop&&crop.isMaxAge(state))trace(level,player.blockPosition(),SupplyPiles.crumbUnit(),1);
    }
    /** Shearing a sheep under a roof leaves loose wool in the room. */
    public static void sheared(PlayerInteractEvent.EntityInteract event){
        if(event.getLevel().isClientSide||!(event.getTarget() instanceof Sheep sheep)||!sheep.readyForShearing()
                ||!event.getItemStack().is(Items.SHEARS)||!(event.getLevel() instanceof ServerLevel level))return;
        trace(level,sheep.blockPosition(),SupplyPiles.lintUnit(),2);
    }
    /** Preparing food in the kitchen (crafting or smelting something edible) leaves crumbs. */
    public static void crafted(PlayerEvent.ItemCraftedEvent event){
        if(event.getEntity() instanceof ServerPlayer player&&!(player instanceof FakePlayer)&&event.getCrafting().has(DataComponents.FOOD))
            trace(player.serverLevel(),player.blockPosition(),SupplyPiles.crumbUnit(),1);
    }
    public static void smelted(PlayerEvent.ItemSmeltedEvent event){
        if(event.getEntity() instanceof ServerPlayer player&&!(player instanceof FakePlayer)&&event.getSmelting().has(DataComponents.FOOD))
            trace(player.serverLevel(),player.blockPosition(),SupplyPiles.crumbUnit(),1);
    }
    /**
     * Puts up to {@code units} of lint or crumbs on the floor near {@code origin}, spending the region's event budget. Returns
     * how many landed. Nothing is placed outdoors, in cleaned or sprayed regions, or when the room is already full.
     */
    public static int trace(ServerLevel level,BlockPos origin,ItemStack unit,int units){
        if(units<=0||!SupplyPiles.internal(unit)||!enabled(level)||!covered(level,origin)||suppressed(level,origin))return 0;
        var data=HouseholdEcologyData.get(level);long now=level.getGameTime();
        var area=data.area(HouseholdEcologyData.Region.at(origin),now);area.refill(now);
        boolean lint=SupplyPiles.lint(unit);
        int want=Math.min(units,lint?area.lintBudget:area.crumbBudget);if(want<=0)return 0;
        var spot=new BlockPos[1];
        int placed=residual(level,origin,unit.copyWithCount(want),spot);
        if(placed>0){
            if(lint)area.lintBudget-=placed;else area.crumbBudget-=placed;
            area.lastActive=now;data.setDirty();
            puff(level,spot[0]==null?origin:spot[0],lint);
        }
        return placed;
    }
    /** A short, quiet puff where the debris lands, so the cause of a new pile can be seen. */
    static void puff(ServerLevel level,BlockPos floor,boolean lint){
        var at=Vec3.atBottomCenterOf(floor);
        if(lint)level.sendParticles(ParticleTypes.WHITE_ASH,at.x,at.y+.12,at.z,6,.12,.04,.12,.01);
        else level.sendParticles(new ItemParticleOption(ParticleTypes.ITEM,new ItemStack(Items.BREAD)),at.x,at.y+.1,at.z,4,.08,.03,.08,.02);
    }
    /** Lint and crumbs exist only as world state. Anything that leaks into an inventory or onto the ground as an item is removed. */
    public static void itemJoin(EntityJoinLevelEvent event){
        if(!event.getLevel().isClientSide&&event.getEntity() instanceof ItemEntity item&&SupplyPiles.internal(item.getItem()))event.setCanceled(true);
    }
    public static void purgeInventory(PlayerTickEvent.Post event){
        if(!(event.getEntity() instanceof ServerPlayer player)||player.tickCount%100!=7)return;
        int removed=0;var inventory=player.getInventory();
        for(int slot=0;slot<inventory.getContainerSize();slot++){
            var stack=inventory.getItem(slot);
            if(SupplyPiles.internal(stack)){removed+=stack.getCount();inventory.setItem(slot,ItemStack.EMPTY);}
        }
        if(removed>0)player.displayClientMessage(Component.translatable("house.mcyukkuri.supplies.purged"),true);
    }

    /** Returns the amount accepted, without mutating the input. Callers debit only this real ground transfer. */
    public static int residual(ServerLevel level,BlockPos origin,ItemStack stack){return residual(level,origin,stack,null);}
    private static int residual(ServerLevel level,BlockPos origin,ItemStack stack,BlockPos[] spot){
        if(!enabled(level)||!SupplyPiles.material(stack)||HouseholdProtection.protectedAt(level,origin))return 0;
        int remaining=stack.getCount();var view=new SupplyPileView(level);
        for(var pos:ranked(level,reachable(level,origin,2),stack,origin)){
            if(HouseholdProtection.protectedAt(level,pos))continue;
            int capacity=capacity(level,pos,view,stack);if(capacity==0)continue;
            int added=SupplyPiles.offer(level,pos,stack,Math.min(remaining,capacity));remaining-=added;
            if(added>0){view.changed(pos);if(spot!=null&&spot[0]==null)spot[0]=pos;}
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
        return new Snapshot(HouseholdEcologyData.get(level).state(pos),new Stock(stock.piles(),stock.supplies(),stock.lint(),stock.crumbs()),suppressed(level,pos),recentlyActive(level,pos));
    }
    private static boolean dust(ServerLevel level,Collection<BlockPos> floors,SupplyPileView view){
        var lint=SupplyPiles.lintUnit();var origin=floors.iterator().next();
        for(var pos:ranked(level,floors,lint,origin))
            if(!suppressed(level,pos)&&capacity(level,pos,view,lint)>0&&covered(level,pos)&&SupplyPiles.offer(level,pos,lint,1)==1){view.changed(pos);return true;}
        return false;
    }
    private static int capacity(ServerLevel level,BlockPos pos,SupplyPileView view,ItemStack kind){
        var stock=view.region(pos);var column=view.column(pos);boolean existing=level.getBlockEntity(pos) instanceof SupplyPileBlockEntity;
        if(stock.piles()>MAX_PILES||stock.piles()==MAX_PILES&&!existing||column.piles()>MAX_CHUNK_PILES||column.piles()==MAX_CHUNK_PILES&&!existing)return 0;
        int room=Math.min(MAX_SUPPLIES-stock.supplies(),MAX_CHUNK_SUPPLIES-column.supplies());
        if(SupplyPiles.lint(kind))room=Math.min(room,MAX_LINT-stock.lint());
        else if(SupplyPiles.crumb(kind))room=Math.min(room,MAX_CRUMBS-stock.crumbs());
        return Math.max(0,room);
    }

    // ------------------------------------------------------------------------------------------------ moving in
    /**
     * Enough lint and crumbs have gathered in a room whose hidden corner nobody is watching, and no house yukkuri lives
     * near: one arrives at that corner. It owns no nest yet; it has to carry lint there itself ({@link HouseLife}).
     */
    private static boolean arrive(ServerLevel level,Collection<BlockPos> floors,SupplyPileView view){
        int lint=0,crumb=0;BlockPos supply=null;
        for(var pos:floors)if(level.getBlockEntity(pos) instanceof SupplyPileBlockEntity pile&&pile.count()>0){
            if(pile.lint())lint+=pile.count();else if(pile.crumb())crumb+=pile.count();else continue;
            if(supply==null)supply=pos;
        }
        if(lint<ARRIVAL_LINT||lint+crumb<ARRIVAL_SUPPLY)return false;
        var den=bestDen(level,floors,supply,view);
        if(den==null||watched(level,Vec3.atBottomCenterOf(den.site().relative(den.entrance())),WATCH_RANGE))return false;
        if(!level.getEntitiesOfClass(YukkuriEntity.class,new AABB(den.site()).inflate(20),e->e.isAlive()&&e.getVariant().isHouse()).isEmpty())return false;
        if(FamilyData.get(level).population(level,den.site())>=FamilyConfig.POPULATION_CAP.get())return false;
        return spawnFounder(level,den,level.random.nextBoolean())!=null;
    }
    /** The pioneer appears in front of its hidden corner already knowing it as home. */
    public static YukkuriEntity spawnFounder(ServerLevel level,Den den,boolean reimu){
        var entity=(reimu?ModEntities.HOUSE_REIMU.get():ModEntities.HOUSE_MARISA.get()).create(level);
        if(entity==null)return null;
        var stand=den.site().relative(den.entrance());
        entity.moveTo(Vec3.atBottomCenterOf(stand));entity.setPersistenceRequired();
        if(entity.life().dryStand(stand))entity.setOnGround(true);
        if(!entity.life().dryStand(stand)||!level.noCollision(entity))return null;
        entity.house().foundAt(den);
        if(!level.addFreshEntity(entity))return null;
        entity.showReaction("surprise","house_arrival",100);
        MCYukkuri.LOGGER.info("[house] a {} moved in at {} (entrance {})",reimu?"house reimu":"house marisa",den.site().toShortString(),den.entrance());
        return entity;
    }
    private static Den bestDen(ServerLevel level,Collection<BlockPos> floors,BlockPos near,SupplyPileView view){
        var candidates=new ArrayList<BlockPos>();
        for(var pos:floors)if(level.isEmptyBlock(pos)&&hiddenCorner(level,pos,2)&&!suppressed(level,pos)&&findEntrance(level,pos)!=null)candidates.add(pos);
        var reference=near==null?floors.iterator().next():near;
        candidates.sort(Comparator.comparingInt((BlockPos pos)->-wallScore(level,pos)).thenComparingDouble(pos->pos.distSqr(reference))
                .thenComparingLong(BlockPos::asLong));
        for(var pos:candidates)if(view.nestFree(pos,16))return new Den(pos.immutable(),findEntrance(level,pos));
        return null;
    }
    /** Stray residents choose a hidden corner of their room; nearest first. Nest-free only, caller tests reachability. */
    public static List<Den> dens(ServerLevel level,BlockPos origin,int radius){
        var view=new SupplyPileView(level);var found=new ArrayList<Den>();
        var cells=new ArrayList<BlockPos>();
        for(var pos:BlockPos.betweenClosed(origin.offset(-radius,-1,-radius),origin.offset(radius,1,radius)))cells.add(pos.immutable());
        cells.sort(Comparator.comparingDouble((BlockPos pos)->pos.distSqr(origin)).thenComparingLong(BlockPos::asLong));
        for(int minimum=2;minimum>=1&&found.isEmpty();minimum--)
            for(var pos:cells){
                if(found.size()>=8)break;
                if(!level.hasChunkAt(pos)||!level.isEmptyBlock(pos)||!hiddenCorner(level,pos,minimum)||suppressed(level,pos))continue;
                var entrance=findEntrance(level,pos);
                if(entrance!=null&&view.nestFree(pos,8))found.add(new Den(pos,entrance));
            }
        return found;
    }
    /**
     * Founds the nest of a resident that has brought its first lint to {@code den}. The block appears empty; the caller
     * deposits the lint it carries. Returns null when the corner is no longer free.
     */
    public static HouseNestBlockEntity found(ServerLevel level,Den den,boolean founderReimu){
        var site=den.site();
        if(!level.hasChunkAt(site)||!level.isEmptyBlock(site)||!SupplyPiles.supported(level,site)||suppressed(level,site)
                ||!new SupplyPileView(level).nestFree(site,8))return null;
        if(!level.setBlockAndUpdate(site,ModBlocks.HOUSE_DUST_NEST.get().defaultBlockState()))return null;
        if(!(level.getBlockEntity(site) instanceof HouseNestBlockEntity nest)){level.removeBlock(site,false);return null;}
        nest.setEntrance(den.entrance());nest.markFounder(founderReimu);
        MCYukkuri.LOGGER.info("[house] nest founded at {}",site.toShortString());
        return nest;
    }
    /** Hidden: walkable, roofed, with at least {@code sides} horizontal neighbours that are walls or furniture. */
    public static boolean hiddenCorner(ServerLevel level,BlockPos pos,int sides){
        if(!walkable(level,pos)||!covered(level,pos))return false;
        int blocked=0;
        for(var direction:Direction.Plane.HORIZONTAL){
            var neighbor=pos.relative(direction);if(!level.hasChunkAt(neighbor))return false;
            if(!level.getBlockState(neighbor).getCollisionShape(level,neighbor).isEmpty())blocked++;
        }
        return blocked>=sides&&blocked<=3;
    }
    /** Any person who can see the point (players or villagers within range, with a clear line). */
    public static boolean watched(ServerLevel level,Vec3 point,double range){
        for(var player:level.players()){
            if(player.isSpectator()||player instanceof FakePlayer||player.distanceToSqr(point)>range*range)continue;
            if(sees(level,player.getEyePosition(),point))return true;
        }
        return false;
    }
    private static boolean sees(ServerLevel level,Vec3 eye,Vec3 point){
        return level.clip(new net.minecraft.world.level.ClipContext(eye,point.add(0,.1,0),net.minecraft.world.level.ClipContext.Block.COLLIDER,
                net.minecraft.world.level.ClipContext.Fluid.NONE,net.minecraft.world.phys.shapes.CollisionContext.empty()))
                .getType()==net.minecraft.world.phys.HitResult.Type.MISS;
    }

    // ------------------------------------------------------------------------------------------------ placement helpers
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
    static int wallScore(ServerLevel level,BlockPos pos){
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
    /** Walls and furniture-side floor cells within {@code radius} of {@code origin}, 3 or more cells away, for a resident to patrol. */
    public static List<BlockPos> patrolCells(ServerLevel level,BlockPos origin,int radius){
        var cells=new ArrayList<BlockPos>();
        for(int dx=-radius;dx<=radius;dx++)for(int dz=-radius;dz<=radius;dz++)for(int dy=-1;dy<=1;dy++){
            var pos=origin.offset(dx,dy,dz);
            if(dx*dx+dz*dz<9||dx*dx+dz*dz>radius*radius||!level.hasChunkAt(pos))continue;
            if(walkable(level,pos)&&clear(level,pos.above())&&covered(level,pos)&&wallScore(level,pos)>0)cells.add(pos.immutable());
        }
        java.util.Collections.shuffle(cells,new java.util.Random(level.random.nextLong()));
        return cells;
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

    // ------------------------------------------------------------------------------------------------ operator tools
    /**
     * "People lived here for {@code ticks}": runs the same sources a real stay would, without waiting. Dust, meals and (for
     * {@code sleeping}) bed fibre are laid down through the normal caps; the region counts as active and, when
     * {@code force} is set, the settlement cooldown and sweep suppression are cleared. Returns a one-line summary.
     */
    public static String simulate(ServerLevel level,BlockPos at,int ticks,boolean sleeping,boolean force){
        var floors=reachable(level,at,SEARCH_RADIUS);
        if(floors.isEmpty())return "no walkable floor here";
        if(!covered(level,at))return "not under a roof";
        var data=HouseholdEcologyData.get(level);long now=level.getGameTime();
        var area=data.area(HouseholdEcologyData.Region.at(at),now);
        if(force){area.cleanUntil=0;area.nextSettlement=0;}
        else if(now<area.cleanUntil)return "region was swept; wait or force";
        var view=new SupplyPileView(level);
        int dusts=0,crumbs=0;
        for(int i=0;i<ticks/DUST_TICKS*(sleeping?2:1);i++)if(dust(level,floors,view))dusts++;
        for(int i=0;i<ticks/2400;i++){
            if(residual(level,at,SupplyPiles.crumbUnit())==1)crumbs++;
        }
        area.lastSample=Math.floorDiv(now,SAMPLE_TICKS);area.lastActive=now;
        area.settlementTicks=Math.min(SETTLEMENT_TICKS,area.settlementTicks+ticks);
        boolean arrived=false;
        if(area.settlementTicks>=SETTLEMENT_TICKS&&now>=area.nextSettlement&&enabled(level)&&arrive(level,floors,view)){
            area.settlementTicks=0;area.nextSettlement=now+SETTLEMENT_COOLDOWN;arrived=true;
        }
        data.setDirty();
        return "dust +"+dusts+", crumbs +"+crumbs+(arrived?", a house yukkuri moved in":"");
    }
    /** Lines for /yukkuri household status. */
    public static List<String> report(ServerLevel level,BlockPos at){
        var lines=new ArrayList<String>();var state=HouseholdEcologyData.get(level).state(at);
        var view=new SupplyPileView(level);var stock=view.region(at);
        int lint=0,crumb=0,waste=0;
        for(var pile:view.nearby(at,12,2)){
            if(pile.lint())lint+=pile.count();else if(pile.crumb())crumb+=pile.count();else waste+=pile.count();
        }
        lines.add("region piles="+stock.piles()+" units="+stock.supplies()+"; within 12 blocks lint="+lint+" crumbs="+crumb+" waste="+waste);
        lines.add("activity dust="+state.dustTicks()+"/"+DUST_TICKS+" settle="+state.settlementTicks()+"/"+SETTLEMENT_TICKS
                +" swept="+(level.getGameTime()<state.cleanUntil())+" cooldown="+Math.max(0,state.nextSettlement()-level.getGameTime())
                +" recent="+recentlyActive(level,at)+" roofed="+covered(level,at));
        for(var entity:level.getEntitiesOfClass(YukkuriEntity.class,new AABB(at).inflate(24),e->e.isAlive()&&e.getVariant().isHouse()))
            lines.add("resident "+entity.getVariant().assetId()+" at "+entity.blockPosition().toShortString()+" "+entity.house().describe());
        for(var pos:BlockPos.betweenClosed(at.offset(-16,-3,-16),at.offset(16,3,16)))
            if(level.hasChunkAt(pos)&&level.getBlockEntity(pos) instanceof HouseNestBlockEntity nest)
                lines.add("nest "+pos.toShortString()+" materials="+nest.materials()+"/"+HouseNestBlockEntity.MATERIAL_COST+" food="+nest.food()
                        +" dirt="+nest.dirt()+" eggs="+nest.eggs()+" progress="+Math.round(nest.progress()*100)+"%");
        return lines;
    }
    private HouseholdEcology(){}
}
