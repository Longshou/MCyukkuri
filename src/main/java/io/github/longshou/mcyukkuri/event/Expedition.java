package io.github.longshou.mcyukkuri.event;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.event.WildEventData.Kind;
import io.github.longshou.mcyukkuri.event.WildEventData.Phase;
import io.github.longshou.mcyukkuri.event.WildEventData.Scene;
import io.github.longshou.mcyukkuri.life.YukkuriConfig;
import io.github.longshou.mcyukkuri.social.Temperament;
import io.github.longshou.mcyukkuri.social.YukkuriPersonality.Trait;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import java.util.Comparator;

/** A local scouting trip with an appointed captain, roster and real destination; no scripted danger. */
public final class Expedition {
    public static Scene discover(YukkuriEntity captain,List<YukkuriEntity> peers) {
        if(!eligibleCaptain(captain)||captain.getRandom().nextInt(8)!=0)return null;
        var members=peers.stream().filter(e -> eligibleMember(captain,e)).limit(3).toList();
        if(members.isEmpty())return null;
        if(captain.getRandom().nextInt(YukkuriConfig.BIOME_EXPEDITION_ONE_IN.get())==0){var distant=discoverFar(captain,members);if(distant!=null)return distant;}
        for(BlockPos pos:BlockPos.betweenClosed(captain.blockPosition().offset(-20,-1,-20),captain.blockPosition().offset(20,1,20))) {
            double distance=pos.distSqr(captain.blockPosition());
            if(distance>=144&&distance<=400&&captain.level().hasChunkAt(pos)&&captain.level().getBlockState(pos).is(Blocks.SHORT_GRASS)) {
                Scene s=form(captain,members,pos);if(s!=null)return s;
            }
        }
        return null;
    }
    /** Cross-biome trip (E02): a destination 24-40 blocks away in a different biome; reached by bounded path segments. */
    private static Scene discoverFar(YukkuriEntity captain,List<YukkuriEntity> members) {
        var level=(ServerLevel)captain.level();
        var here=level.getBiome(captain.blockPosition()).unwrapKey().orElse(null);
        if(here==null)return null;
        for(int i=0;i<12;i++) {
            double angle=captain.getRandom().nextDouble()*Math.PI*2,distance=48+captain.getRandom().nextInt(33);
            int x=captain.blockPosition().getX()+(int)Math.round(Math.cos(angle)*distance),z=captain.blockPosition().getZ()+(int)Math.round(Math.sin(angle)*distance);
            if(!level.hasChunkAt(new BlockPos(x,captain.blockPosition().getY(),z)))continue;
            BlockPos destination=new BlockPos(x,level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z),z);
            if(level.getBiome(destination).unwrapKey().map(key -> key.equals(here)).orElse(true))continue;
            Scene s=formFar(captain,members,destination);if(s!=null)return s;
        }
        return null;
    }
    private static Scene formFar(YukkuriEntity captain,List<YukkuriEntity> members,BlockPos destination) {
        if(!eligibleCaptain(captain)||members.isEmpty()||members.size()>3||members.stream().anyMatch(e -> !eligibleMember(captain,e)))return null;
        var level=(ServerLevel)captain.level();double distance=destination.distSqr(captain.blockPosition());
        if(distance<48*48||distance>84*84||!level.hasChunkAt(destination)||!captain.life().dryStand(destination))return null;
        var roster=new ArrayList<YukkuriEntity>();roster.add(captain);roster.addAll(members);
        var s=WildEventData.get(level).start(Kind.EXPEDITION,"expedition-biome",GlobalPos.of(level.dimension(),captain.blockPosition()),
                roster.stream().map(YukkuriEntity::getUUID).toList(),null,level.getGameTime());
        if(s!=null){s.destination=GlobalPos.of(level.dimension(),destination.immutable());for(var e:roster)e.setPersistenceRequired();}
        return s;
    }
    public static boolean far(Scene s) { return s.kind==Kind.EXPEDITION&&s.card.equals("expedition-biome"); }
    /** Vanilla pathing reaches about 16 blocks; walk bounded segments toward the goal (same approach as settlement returns). */
    private static boolean stepToward(YukkuriEntity e,BlockPos destination) {
        Vec3 goal=Vec3.atBottomCenterOf(destination);
        if(e.distanceToSqr(goal)<=16*16&&e.life().navigate(destination,.75,false))return true;
        Vec3 delta=goal.subtract(e.position());
        BlockPos waypoint=BlockPos.containing(e.position().add(delta.normalize().scale(Math.min(8,delta.length()))));
        var candidates=new ArrayList<BlockPos>();
        for(BlockPos pos:BlockPos.betweenClosed(waypoint.offset(-2,-2,-2),waypoint.offset(2,2,2)))
            if(e.life().dryStand(pos)&&pos.distSqr(destination)<e.blockPosition().distSqr(destination))candidates.add(pos.immutable());
        candidates.sort(Comparator.comparingDouble(pos -> pos.distSqr(destination)));
        for(BlockPos pos:candidates.stream().limit(12).toList())if(e.life().navigate(pos,.75,false))return true;
        return false;
    }
    private static void report(ServerLevel level,Scene s) {
        var key=level.getBiome(s.destination.pos()).unwrapKey().orElse(null);if(key==null)return;
        var name=Component.translatable("biome."+key.location().getNamespace()+"."+key.location().getPath());
        for(var player:level.players())if(player.distanceToSqr(Vec3.atCenterOf(s.origin.pos()))<=24*24)
            player.displayClientMessage(Component.translatable("event.mcyukkuri.biome_expedition",name),false);
    }
    private static boolean eligibleCaptain(YukkuriEntity e) {
        return WildEvents.available(e)&&WildEvents.forest(e)&&e.level().isDay()&&e.family().adult()
                &&e.getVariant().dialogueSpecies().equals("marisa")&&e.life().personality().trait(Trait.BOLDNESS)>=Temperament.BOLDNESS_AT_60
                &&e.life().nutrition()>=55&&e.life().energy()>=65;
    }
    private static boolean eligibleMember(YukkuriEntity captain,YukkuriEntity e) {
        return WildEvents.available(e)&&e.childhood().canLearn()&&e!=captain&&captain.distanceToSqr(e)<=36&&WildEvents.friendly(captain,e)
                &&e.life().nutrition()>=55&&e.life().energy()>=65&&e.getVariant().dialogueSpecies().equals("marisa")
                &&captain.hasLineOfSight(e)&&captain.life().reachable(e.blockPosition(),false);
    }
    public static Scene form(YukkuriEntity captain,List<YukkuriEntity> members,BlockPos destination) {
        if(!eligibleCaptain(captain)||members.isEmpty()||members.size()>3||members.stream().anyMatch(e -> !eligibleMember(captain,e)))return null;
        var level=(ServerLevel)captain.level();double distance=destination.distSqr(captain.blockPosition());
        if(distance<144||distance>400||!level.hasChunkAt(destination)||!level.getBlockState(destination).is(Blocks.SHORT_GRASS)
                ||!captain.life().dryStand(destination)||!captain.life().reachable(destination,false)
                ||members.stream().anyMatch(e -> !e.life().reachable(destination,false)))return null;
        var roster=new ArrayList<YukkuriEntity>();roster.add(captain);roster.addAll(members);
        var s=WildEventData.get(level).start(Kind.EXPEDITION,"expedition-count",GlobalPos.of(level.dimension(),captain.blockPosition()),
                roster.stream().map(YukkuriEntity::getUUID).toList(),null,level.getGameTime());
        if(s!=null){s.destination=GlobalPos.of(level.dimension(),destination.immutable());for(var e:roster)e.setPersistenceRequired();}
        return s;
    }
    public static String role(YukkuriEntity e) {
        if(!(e.level() instanceof ServerLevel level))return "none";
        var s=WildEventData.get(level).forMember(e.getUUID(),scene -> scene.kind==Kind.EXPEDITION);
        return s==null||s.kind!=Kind.EXPEDITION?"none":s.members.getFirst().equals(e.getUUID())?"captain":"member";
    }
    public static void advance(ServerLevel level,Scene s,List<YukkuriEntity> roster) {
        if(s.destination==null||!s.destination.dimension().equals(level.dimension())){WildEvents.finish(level,s,Phase.INTERRUPTED,"missing_trip_destination");return;}
        var captain=roster.getFirst();
        boolean distant=far(s);
        if(roster.stream().anyMatch(e -> distant?captain.distanceToSqr(e)>196:(!captain.hasLineOfSight(e)||captain.distanceToSqr(e)>64))) {
            WildEvents.finish(level,s,Phase.INTERRUPTED,"expedition_roster_separated");return;
        }
        // Stages: departure roll call, outward walk, destination roll call, return walk.
        if(s.sourceCount==0||s.sourceCount==2) {
            if(s.phaseTicks%20==0&&s.responses<roster.size()&&roster.get(s.responses).speak("known_peer"))s.responses++;
            if(s.responses==roster.size()){s.sourceCount++;s.responses=0;WildEventData.get(level).phase(s,Phase.APPROACH);}
        } else {
            BlockPos goal=s.sourceCount==1?s.destination.pos():s.origin.pos();
            if(roster.stream().allMatch(e -> e.distanceToSqr(Vec3.atBottomCenterOf(goal))<(distant?16:6.25))) {
                if(s.sourceCount==3){
                    if(distant){report(level,s);captain.showReaction("happy","hello",60);}
                    WildEvents.finish(level,s,Phase.COMPLETE,distant?"crossed_biome_and_returned":"real_squad_reported_and_returned");
                } else {if(distant)captain.showReaction("surprise","food_found",70);s.sourceCount=2;s.responses=0;WildEventData.get(level).phase(s,Phase.RESPONDING);}
            }
        }
        if(distant?s.ticks>=4600:s.ticks>=2400)WildEvents.finish(level,s,Phase.INTERRUPTED,distant?"biome_expedition_timed_out":"local_expedition_timed_out");
    }
    public static void move(YukkuriEntity e,Scene s) {
        var level=(ServerLevel)e.level();var captain=level.getEntity(s.members.getFirst());
        if(captain!=null&&captain!=e)e.getLookControl().setLookAt(captain,25,25);
        if(s.sourceCount!=1&&s.sourceCount!=3){e.getNavigation().stop();return;}
        if(s.destination==null)return;
        BlockPos goal=s.sourceCount==1?s.destination.pos():s.origin.pos();
        if(e.distanceToSqr(Vec3.atBottomCenterOf(goal))<4){e.getNavigation().stop();return;}
        if(far(s)) {
            // Segmented walk: a single failed segment is retried; the trip is only given up after repeated failures.
            if(s.phaseTicks%20==0&&!stepToward(e,goal)&&++s.responses>=5)WildEvents.finish(level,s,Phase.INTERRUPTED,"expedition_route_unreachable");
            return;
        }
        if(s.phaseTicks%20==0&&(!e.life().dryStand(goal)||!e.life().navigate(goal,.75,false)))
            WildEvents.finish(level,s,Phase.INTERRUPTED,"expedition_route_unreachable");
    }
    private Expedition() {}
}
