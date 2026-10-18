package io.github.longshou.mcyukkuri.event;

import io.github.longshou.mcyukkuri.mind.EmotionEvent;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.event.HomeClaimData.Claim;
import io.github.longshou.mcyukkuri.event.HomeClaimData.Stage;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.life.YukkuriConfig;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.social.Bonds;
import io.github.longshou.mcyukkuri.world.SearchBudget;
import io.github.longshou.mcyukkuri.world.SearchBudget.Cost;
import io.github.longshou.mcyukkuri.world.SearchBudget.Purpose;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.IdentityHashMap;
import java.util.WeakHashMap;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * Home declaration (L16/L21 pattern): a wild family claims a player's house or crop field, may get walled in,
 * and ends by being driven off, adopted, settling, collapsing from hunger, or being left alone. No food or kin is created.
 */
public final class HomeClaim {
    private static final int TICK = 10, WITNESS = 24;
    // Local weak identity -> scalar generation only; the neutral budget never retains a Claim.
    private static final Map<MinecraftServer,Map<Claim,Long>> SETTLE_GENERATIONS=new IdentityHashMap<>();
    private static Map<Claim,Long> generations(MinecraftServer server) {
        return SETTLE_GENERATIONS.computeIfAbsent(server,ignored -> new WeakHashMap<>());
    }
    private static void cancelSettle(ServerLevel level,Claim claim) {
        cancelSettle(level.getServer(),claim);
    }
    private static void cancelSettle(MinecraftServer server,Claim claim) {
        var values=SETTLE_GENERATIONS.get(server);
        var generation=values==null?null:values.remove(claim);
        if(generation!=null)SearchBudget.cancel(server,claim.site.dimension(),claim.id,Purpose.CLAIM_SETTLE,generation);
    }
    public static void stopped(ServerStoppedEvent event) { SETTLE_GENERATIONS.remove(event.getServer()); }
    private static final int MEMBER_COOLDOWN = 6000; // [original]
    private record Enclosure(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, boolean food) {}

    // --- membership -------------------------------------------------------------------------------------------
    public static Claim of(YukkuriEntity e) {
        if (!(e.level() instanceof ServerLevel level)) return null;
        Claim c=HomeClaimData.get(level).forMember(e.getUUID(),claim -> claim.site.dimension().equals(level.dimension()));
        return c!=null && c.active() && c.site.dimension().equals(level.dimension()) ? c : null;
    }
    public static boolean holds(YukkuriEntity e) { return of(e)!=null; }

    // --- discovery --------------------------------------------------------------------------------------------
    private static boolean eligible(YukkuriEntity e) {
        return e.isAlive() && !e.profile().houseBody() && !e.profile().dangerToYukkuri() && WildEvents.unowned(e) && WildEvents.available(e);
    }
    private static List<YukkuriEntity> family(YukkuriEntity e) {
        var level=(ServerLevel)e.level(); var kin=FamilyData.get(level);
        List<YukkuriEntity> found=level.getEntitiesOfClass(YukkuriEntity.class,e.getBoundingBox().inflate(8),p -> p!=e && eligible(p)
                && (Bonds.careKin(e, p) || p.getUUID().equals(e.family().partner())) && WildEvents.recognizes(e,p)).stream()
                .sorted(Comparator.comparingDouble(e::distanceToSqr)).limit(5).toList();
        List<YukkuriEntity> all=new ArrayList<>(); all.add(e); all.addAll(found); return all;
    }
    private static boolean crop(ServerLevel level, BlockPos pos, BlockState state) {
        return state.getBlock() instanceof CropBlock && level.getBlockState(pos.below()).is(Blocks.FARMLAND);
    }
    private static boolean house(BlockState s) {
        return s.is(BlockTags.BEDS) || s.is(BlockTags.DOORS) || s.is(Blocks.CRAFTING_TABLE) || s.is(Blocks.CHEST) || s.is(Blocks.FURNACE);
    }
    /** A reachable standing spot beside a human house block (covered) or crop, outside villages; null when none. */
    private static BlockPos locate(YukkuriEntity e, BlockPos center) {
        var level=(ServerLevel)e.level(); List<BlockPos> anchors=new ArrayList<>();
        for (BlockPos p:BlockPos.betweenClosed(center.offset(-10,-4,-10),center.offset(10,4,10))) {
            if (!level.hasChunkAt(p)) continue;
            var state=level.getBlockState(p);
            if (crop(level,p,state) || house(state)) anchors.add(p.immutable());
        }
        anchors.sort(Comparator.comparingDouble(p -> p.distSqr(center)));
        int paths=0;
        for (BlockPos anchor:anchors.stream().limit(24).toList()) {
            if (level.isVillage(anchor)) continue;
            boolean farm=crop(level,anchor,level.getBlockState(anchor));
            List<BlockPos> stands=new ArrayList<>();
            for (BlockPos stand:BlockPos.betweenClosed(anchor.offset(-2,-1,-2),anchor.offset(2,1,2)))
                if (e.life().dryStand(stand) && (farm || e.life().covered(stand))) stands.add(stand.immutable());
            stands.sort(Comparator.comparingDouble(p -> p.distSqr(anchor)));
            for (BlockPos stand:stands) {
                if (paths++>=8) return null;
                if (e.life().reachable(stand,false)) return stand;
            }
        }
        return null;
    }
    /** Called from {@link WildEvents#discover} for an available wild adult; true when a claim started. */
    public static boolean discover(YukkuriEntity e) {
        if (!(e.level() instanceof ServerLevel level) || !YukkuriConfig.HOME_CLAIMS.get() || !e.family().adult() || !eligible(e)) return false;
        if (level.random.nextInt(YukkuriConfig.HOME_CLAIM_CHANCE.get())!=0) return false;
        Player player=level.getNearestPlayer(e,WITNESS);
        if (player==null || !player.isAlive() || player.isSpectator()) return false;
        if (HomeClaimData.get(level).cooling(e.getUUID(),level.getGameTime())) return false;
        List<YukkuriEntity> members=family(e);
        if (members.size()<2) return false;
        BlockPos stand=locate(e,e.blockPosition());
        return stand!=null && begin(level,members,stand,player,false)!=null;
    }
    /** Admin entry: nearest eligible wild family to {@code origin} claims a site near {@code origin}, skipping chance and cooldowns. */
    public static Claim startAt(ServerLevel level, BlockPos origin, Player player) {
        var near=level.getEntitiesOfClass(YukkuriEntity.class,new AABB(origin).inflate(32),p -> eligible(p)).stream()
                .sorted(Comparator.comparingDouble(p -> p.blockPosition().distSqr(origin))).toList();
        for (YukkuriEntity e:near) {
            List<YukkuriEntity> members=family(e);
            if (members.size()<2) continue;
            BlockPos stand=locate(e,origin);
            if (stand==null) continue;
            Claim c=begin(level,members,stand,player,true);
            if (c!=null) return c;
        }
        return null;
    }
    private static Claim begin(ServerLevel level, List<YukkuriEntity> members, BlockPos stand, Player player, boolean force) {
        var data=HomeClaimData.get(level); long now=level.getGameTime();
        GlobalPos site=GlobalPos.of(level.dimension(),stand);
        if (!force && (data.regionBlocked(site,now) || members.stream().anyMatch(m -> data.cooling(m.getUUID(),now)))) return null;
        List<UUID> ids=members.stream().map(YukkuriEntity::getUUID).toList();
        Claim c=data.start(ids,site,player==null?null:player.getUUID());
        if (c==null) return null;
        for (var m:members) { m.setPersistenceRequired(); m.social().stop(); m.dailyLife().stop(); }
        return c;
    }

    // --- progress ---------------------------------------------------------------------------------------------
    public static void tick(ServerTickEvent.Post event) {
        var server=event.getServer();
        if (server.overworld().getGameTime()%TICK!=0) return;
        var data=HomeClaimData.get(server.overworld());
        var active=data.active();
        for(var c:new ArrayList<>(generations(server).keySet())) {
            if(!active.contains(c)||c.stage!=Stage.HOLD) {
                cancelSettle(server,c);
            }
        }
        for (Claim c:active) {
            ServerLevel level=server.getLevel(c.site.dimension());
            if (level!=null) advance(level,data,c);
            else { cancelSettle(server,c); supervise(server,null,data,c,List.of(),EventWatchdog.Availability.PARTICIPANT_UNAVAILABLE); }
        }
    }
    private static boolean supervise(MinecraftServer server, ServerLevel level, HomeClaimData data, Claim c,
                                     List<YukkuriEntity> alive, EventWatchdog.Availability availability) {
        boolean expired=c.supervision.observe(server.overworld().getGameTime(),availability,level);
        data.setDirty();
        if(!expired)return true;
        cancelSettle(server,c);
        long region=(c.result.equals("driven_off")?3L:1L)*YukkuriConfig.HOME_CLAIM_REGION_COOLDOWN.get();
        data.end(c,c.supervision.timeoutReason(),c.supervision.originTime(),MEMBER_COOLDOWN,region);
        for(var y:alive)y.getNavigation().stop();
        return false;
    }
    private static void advance(ServerLevel level, HomeClaimData data, Claim c) {
        if (!c.active()) {cancelSettle(level,c);return;}
        long now=level.getGameTime(); var family=FamilyData.get(level);
        List<YukkuriEntity> alive=new ArrayList<>();
        for (UUID id:c.members) {
            if (level.getEntity(id) instanceof YukkuriEntity y && y.isAlive()) { alive.add(y); continue; }
            var person=family.person(id);
            if (person!=null && (person.status==FamilyData.Status.DEAD || person.status==FamilyData.Status.REMOVED || person.status==FamilyData.Status.LOST)) continue;
            cancelSettle(level,c);
            supervise(level.getServer(),level,data,c,alive,EventWatchdog.Availability.PARTICIPANT_UNAVAILABLE);
            return; // Unloaded, captured or in another dimension: pause, never count it as a death.
        }
        if (alive.isEmpty()) { cancelSettle(level,c);data.end(c,"perished",now,MEMBER_COOLDOWN,YukkuriConfig.HOME_CLAIM_REGION_COOLDOWN.get()); return; }
        if (alive.stream().anyMatch(y -> !level.isPositionEntityTicking(y.blockPosition()))) {
            cancelSettle(level,c);
            supervise(level.getServer(),level,data,c,alive,EventWatchdog.Availability.PARTICIPANT_UNAVAILABLE); return;
        }
        if(!supervise(level.getServer(),level,data,c,alive,EventWatchdog.Availability.AVAILABLE))return;
        c.ticks+=TICK; c.stageTicks+=TICK; data.setDirty();
        switch (c.stage) {
            case GATHER -> {
                if (c.ticks>1200) { finish(level,data,c,alive,"never_arrived",null); return; }
                var spot=Vec3.atBottomCenterOf(c.site.pos());
                var first=alive.stream().filter(y -> y.family().adult() && y.distanceToSqr(spot)<=25).findFirst().orElse(null);
                if (first!=null) declare(level,c,first,alive);
            }
            case HOLD, ENCLOSED, COLLAPSING -> hold(level,data,c,alive,now);
            case RETREAT -> {
                var spot=Vec3.atBottomCenterOf(c.site.pos());
                if (c.stageTicks>=600 || alive.stream().allMatch(y -> y.distanceToSqr(spot)>=256))
                    finish(level,data,c,alive,"",null);
            }
            default -> { }
        }
    }
    private static void declare(ServerLevel level, Claim c, YukkuriEntity first, List<YukkuriEntity> alive) {
        c.declared=true; c.stage(Stage.HOLD);
        first.showReaction("angry","mine",100);
        for (var y:alive) if (y!=first) y.showReaction("happy","known_peer",80);
        notify(level,c,"home_claim.mcyukkuri.declare",first.getName());
    }
    private static void hold(ServerLevel level, HomeClaimData data, Claim c, List<YukkuriEntity> alive, long now) {
        c.holdTicks+=TICK;
        if (c.holdTicks%200==0) {
            Enclosure walls=YukkuriConfig.HOME_CLAIM_ENCLOSURE.get() ? enclosure(alive.getFirst(),c.site.pos()) : null;
            if (walls!=null) {
                c.hasBox=true; c.minX=walls.minX; c.minY=walls.minY; c.minZ=walls.minZ; c.maxX=walls.maxX; c.maxY=walls.maxY; c.maxZ=walls.maxZ;
                c.foodInside=walls.food;
                if (c.stage==Stage.HOLD) { c.stage(Stage.ENCLOSED); for (var y:alive) y.showReaction("happy","mine",60); }
                else if (c.stage==Stage.COLLAPSING && alive.stream().noneMatch(y -> y.life().nutrition()<30)) c.stage(Stage.ENCLOSED);
                if (c.stage==Stage.ENCLOSED && !c.foodInside && alive.stream().anyMatch(y -> y.life().nutrition()<30)) c.stage(Stage.COLLAPSING);
            } else if (c.stage!=Stage.HOLD) { // The pen was opened (or never closed again): back to plain holding.
                c.hasBox=false; c.foodInside=true; c.quarrels=0; c.stage(Stage.HOLD);
            }
        }
        if(c.stage!=Stage.HOLD)cancelSettle(level,c);
        if (c.stage==Stage.COLLAPSING && c.stageTicks%400==200) {
            quarrel(level,alive); c.quarrels++;
            if (c.quarrels>=5) { finish(level,data,c,alive,"family_collapsed","home_claim.mcyukkuri.collapsed"); return; }
        }
        if (c.stage==Stage.HOLD && c.holdTicks>=YukkuriConfig.HOME_CLAIM_SETTLE_TICKS.get()) {
            Cost upper=new Cost(0,0,0);
            for(int i=0;i<alive.size();i++) {
                // Earlier members may create a core before this member enumerates Homes.
                // Reserve all seven potential placements/member, including conservative rollback slack.
                upper=upper.plus(alive.get(i).habitat().homeSearchCost(SearchBudget.multiply(i,7)));
            }
            long generation=generations(level.getServer()).computeIfAbsent(c,ignored->SearchBudget.generation());
            var lease=SearchBudget.request(level,c.id,Purpose.CLAIM_SETTLE,generation,upper);
            if(lease==null)return; // No member has searched; HOLD retries the whole original ordered batch.
            try(lease) {
                for(var y:alive)y.habitat().findHome(lease);
                finish(level,data,c,alive,"settled","home_claim.mcyukkuri.settled");
            }
        }
    }
    private static void quarrel(ServerLevel level, List<YukkuriEntity> alive) {
        var relations=YukkuriWorldData.get(level);
        for (var y:alive) if (!y.family().adult()) y.showReaction("teary","hungry",60);
        var adults=alive.stream().filter(y -> y.family().adult()).toList();
        if (adults.isEmpty()) return;
        var a=adults.get(level.random.nextInt(adults.size()));
        a.showReaction("angry","mine",60);
        var others=alive.stream().filter(y -> y!=a).toList();
        if (others.isEmpty()) return;
        var b=others.get(level.random.nextInt(others.size()));
        if (b.family().adult()) b.showReaction("angry","mine",60);
        relations.relate(a.getUUID(),b.getUUID(),-6); relations.relate(b.getUUID(),a.getUUID(),-6); // [original]
    }
    private static void finish(ServerLevel level, HomeClaimData data, Claim c, List<YukkuriEntity> alive, String result, String message) {
        cancelSettle(level,c);
        long region=result.equals("driven_off") || c.result.equals("driven_off") ? 3L*YukkuriConfig.HOME_CLAIM_REGION_COOLDOWN.get() : YukkuriConfig.HOME_CLAIM_REGION_COOLDOWN.get();
        data.end(c,result,level.getGameTime(),MEMBER_COOLDOWN,region);
        for (var y:alive) y.getNavigation().stop();
        if (message!=null) notify(level,c,message);
    }
    private static void notify(ServerLevel level, Claim c, String key, Object... args) {
        var spot=Vec3.atCenterOf(c.site.pos());
        for (var p:level.players()) if (!p.isSpectator() && p.distanceToSqr(spot)<=WITNESS*WITNESS) p.sendSystemMessage(Component.translatable(key,args));
    }

    // --- enclosure (L16) --------------------------------------------------------------------------------------
    private static boolean walkable(YukkuriEntity probe, BlockPos pos) {
        if (probe.life().dryStand(pos)) return true;
        var level=probe.level(); // Tilled soil is not a sturdy face but can be walked over.
        return level.hasChunkAt(pos) && level.getBlockState(pos.below()).is(Blocks.FARMLAND) && level.getFluidState(pos).isEmpty()
                && level.getBlockState(pos).getCollisionShape(level,pos).isEmpty() && level.getBlockState(pos.above()).getCollisionShape(level,pos.above()).isEmpty();
    }
    /** Bounded BFS over standable cells (step <= 1): enclosed when it exhausts within 512 cells and radius 15. */
    private static Enclosure enclosure(YukkuriEntity probe, BlockPos start) {
        if (!walkable(probe,start)) return null;
        var level=probe.level(); Deque<BlockPos> queue=new ArrayDeque<>(); Set<Long> seen=new HashSet<>();
        queue.add(start); seen.add(start.asLong());
        int minX=start.getX(), minY=start.getY(), minZ=start.getZ(), maxX=minX, maxY=minY, maxZ=minZ; boolean food=false;
        while (!queue.isEmpty()) {
            BlockPos p=queue.poll();
            minX=Math.min(minX,p.getX()); minY=Math.min(minY,p.getY()); minZ=Math.min(minZ,p.getZ());
            maxX=Math.max(maxX,p.getX()); maxY=Math.max(maxY,p.getY()); maxZ=Math.max(maxZ,p.getZ());
            if (!food && !food(probe,level.getBlockState(p)).isEmpty()) food=true;
            for (Direction direction:Direction.Plane.HORIZONTAL) for (int dy=-1;dy<=1;dy++) {
                BlockPos n=p.relative(direction).offset(0,dy,0);
                if (seen.contains(n.asLong()) || !walkable(probe,n)) continue;
                if (Math.abs(n.getX()-start.getX())>15 || Math.abs(n.getZ()-start.getZ())>15) return null;
                seen.add(n.asLong());
                if (seen.size()>512) return null;
                queue.add(n);
            }
        }
        return new Enclosure(minX,minY,minZ,maxX,maxY,maxZ,food);
    }

    // --- food -------------------------------------------------------------------------------------------------
    private static ItemStack food(YukkuriEntity y, BlockState state) {
        ItemStack stack=ItemStack.EMPTY; var block=state.getBlock();
        if (block==Blocks.SHORT_GRASS) stack=new ItemStack(Items.SHORT_GRASS);
        else if (block==Blocks.WHEAT) stack=new ItemStack(Items.WHEAT);
        else if (block==Blocks.CARROTS) stack=new ItemStack(Items.CARROT);
        else if (block==Blocks.POTATOES) stack=new ItemStack(Items.POTATO);
        else if (block==Blocks.BEETROOTS) stack=new ItemStack(Items.BEETROOT);
        return !stack.isEmpty() && y.life().acceptsFood(stack) ? stack : ItemStack.EMPTY;
    }
    /** A hungry member's nearest edible crop of the claimed field (inside the pen when walled in), or null. */
    public static BlockPos findCrop(YukkuriEntity y, Claim c) {
        if (!y.life().canEat() || !EventHooks.canEntityGrief((ServerLevel)y.level(),y)) return null;
        var level=(ServerLevel)y.level(); BlockPos site=c.site.pos(); BlockPos best=null; double score=Double.MAX_VALUE;
        for (BlockPos p:BlockPos.betweenClosed(site.offset(-8,-3,-8),site.offset(8,3,8))) {
            if (!level.hasChunkAt(p)) continue;
            if (c.hasBox && (p.getX()<c.minX||p.getX()>c.maxX||p.getY()<c.minY-1||p.getY()>c.maxY+1||p.getZ()<c.minZ||p.getZ()>c.maxZ)) continue;
            var state=level.getBlockState(p);
            if (!(state.getBlock() instanceof CropBlock crop) || food(y,state).isEmpty() || !io.github.longshou.mcyukkuri.settlement.Grazing.ripe(state)) continue;
            double d=y.distanceToSqr(Vec3.atBottomCenterOf(p))+(crop.isMaxAge(state)?0:100);
            if (d<score) { best=p.immutable(); score=d; }
        }
        return best!=null && y.life().reachable(best,false) ? best : null;
    }
    public static boolean cropEdible(YukkuriEntity y, BlockPos pos) {
        return y.level().hasChunkAt(pos) && !food(y,y.level().getBlockState(pos)).isEmpty() && y.level().getBlockState(pos).getBlock() instanceof CropBlock
                && io.github.longshou.mcyukkuri.settlement.Grazing.ripe(y.level().getBlockState(pos));
    }
    /** Eats one real crop block: it disappears and its item becomes the meal (griefing rules apply). */
    public static boolean eat(YukkuriEntity y, BlockPos pos) {
        var level=(ServerLevel)y.level(); var state=level.getBlockState(pos); ItemStack food=food(y,state);
        if (food.isEmpty() || !EventHooks.canEntityGrief(level,y) || !io.github.longshou.mcyukkuri.settlement.Grazing.ripe(state)) return false;
        // The crop steps back half its growth range instead of vanishing; vanilla growth ripens it again.
        if (!y.life().beginMeal(food,null,false)) return false;
        io.github.longshou.mcyukkuri.settlement.Grazing.graze(level,pos,state);
        return true;
    }

    // --- movement (used by HomeClaimGoal) ---------------------------------------------------------------------
    public static boolean mayAct(YukkuriEntity y) {
        if (!y.isAlive() || y.isNoAi() || y.isPanicking() || y.isCommandPlaying() || y.operations().active() || y.life().isSleeping()
                || y.life().mealTicks()!=0 || y.isInWaterOrBubble() || y.isPassenger()) return false;
        var life=y.life(); // Only hunger may be answered here; other urgent needs go back to their own goals.
        return !life.hasUrgentNeed() || life.nutrition()<30 && life.energy()>=25 && life.wetness()<=40 && !y.isOnFire();
    }
    public static boolean away(YukkuriEntity y, Claim c, boolean homing) {
        double d=y.distanceToSqr(Vec3.atBottomCenterOf(c.site.pos()));
        if (c.stage==Stage.RETREAT) return c.retreat!=null && d<256;
        return d>(homing?16:c.stage==Stage.GATHER?9:36);
    }
    public static void step(YukkuriEntity y, Claim c) {
        if (!y.getNavigation().isDone() && y.tickCount%40!=0) return;
        if (c.stage==Stage.RETREAT) { if (c.retreat!=null) retreatStep(y,c); return; }
        y.life().navigate(c.site.pos(),1.0,false);
    }
    /** The pathfinder only plans short hops, so flee toward the far anchor ten blocks at a time. */
    private static void retreatStep(YukkuriEntity y, Claim c) {
        BlockPos anchor=c.retreat.pos();
        double dx=anchor.getX()+.5-y.getX(), dz=anchor.getZ()+.5-y.getZ(), len=Math.sqrt(dx*dx+dz*dz);
        if (len<1.5) return;
        double base=Math.atan2(dz,dx), step=Math.min(10,len);
        for (int i=0;i<5;i++) {
            double angle=base+(i==0?0:(i%2==1?1:-1)*((i+1)/2)*Math.PI/4);
            int x=(int)Math.floor(y.getX()+Math.cos(angle)*step), z=(int)Math.floor(y.getZ()+Math.sin(angle)*step);
            for (int dy=2;dy>=-3;dy--) {
                BlockPos p=new BlockPos(x,y.getBlockY()+dy,z);
                if (y.life().dryStand(p) && y.life().navigate(p,1.2,false)) return;
            }
        }
    }

    // --- player responses -------------------------------------------------------------------------------------
    private static boolean responsive(Claim c) { return c!=null && c.active() && c.stage!=Stage.RETREAT; }
    private static Claim responsiveClaim(YukkuriEntity e) {
        if (!(e.level() instanceof ServerLevel level)) return null;
        return HomeClaimData.get(level).forMember(e.getUUID(),claim -> claim.site.dimension().equals(level.dimension()) && responsive(claim));
    }
    /** A player hit a claimant (called from {@code YukkuriEntity.hurt}). */
    public static void attacked(YukkuriEntity e, Player player) {
        Claim c=responsiveClaim(e);
        if (responsive(c)) driveOff((ServerLevel)e.level(),c,player,e);
    }
    /** The existing "decline event" operation on a claimant: the player asks them to leave. */
    public static boolean declined(YukkuriEntity e, Player player) {
        Claim c=responsiveClaim(e);
        return responsive(c) && driveOff((ServerLevel)e.level(),c,player,null);
    }
    private static boolean driveOff(ServerLevel level, Claim c, Player player, YukkuriEntity hit) {
        cancelSettle(level,c);
        var data=HomeClaimData.get(level); c.result="driven_off"; c.player=player.getUUID(); c.stage(Stage.RETREAT); data.setDirty();
        double dx=c.site.pos().getX()+.5-player.getX(), dz=c.site.pos().getZ()+.5-player.getZ(), len=Math.sqrt(dx*dx+dz*dz);
        if (len<1e-3) { dx=1; dz=0; len=1; }
        c.retreat=GlobalPos.of(level.dimension(),c.site.pos().offset((int)Math.round(dx/len*20),0,(int)Math.round(dz/len*20)));
        for (UUID id:c.members) if (level.getEntity(id) instanceof YukkuriEntity y && y.isAlive()) {
            if (y!=hit) y.life().personality().confiscated(player.getUUID()); // The hit member already learned fear from the blow.
            y.mind().felt(EmotionEvent.FRIGHTENED); y.showReaction("surprise","afraid",80);
        }
        notify(level,c,"home_claim.mcyukkuri.driven_off");
        return true;
    }
    /** A claimant was adopted by {@code player}: the rest of the family follows when they also qualify. */
    public static void adopted(YukkuriEntity e, Player player) {
        Claim c=responsiveClaim(e);
        if (!responsive(c)) return;
        var level=(ServerLevel)e.level(); List<YukkuriEntity> others=new ArrayList<>();
        for (UUID id:c.members) if (!id.equals(e.getUUID()) && level.getEntity(id) instanceof YukkuriEntity y && y.isAlive()) others.add(y);
        finish(level,HomeClaimData.get(level),c,others,"adopted","home_claim.mcyukkuri.adopted");
        for (var y:others) WildEvents.adopt(y,player);
    }

    // --- observation and admin --------------------------------------------------------------------------------
    public static Component page(YukkuriEntity e) {
        Claim c=of(e);
        if (c==null) return null;
        return Component.translatable("notebook.mcyukkuri.home_claim",Component.translatable("home_claim.mcyukkuri.stage."+c.stage.name().toLowerCase(java.util.Locale.ROOT)),
                c.site.pos().toShortString(),c.members.size(),c.holdTicks/20);
    }
    public static List<Component> status(ServerLevel level) {
        List<Component> lines=new ArrayList<>();
        for (Claim c:HomeClaimData.get(level).active())
            lines.add(Component.translatable("commands.mcyukkuri.homeclaim.entry",c.site.dimension().location().toString(),c.site.pos().toShortString(),
                    Component.translatable("home_claim.mcyukkuri.stage."+c.stage.name().toLowerCase(java.util.Locale.ROOT)),c.members.size(),c.holdTicks/20));
        return lines;
    }
    private HomeClaim() {}
}
