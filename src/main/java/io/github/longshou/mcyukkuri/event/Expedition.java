package io.github.longshou.mcyukkuri.event;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.event.WildEventData.Kind;
import io.github.longshou.mcyukkuri.event.WildEventData.Phase;
import io.github.longshou.mcyukkuri.event.WildEventData.Scene;
import io.github.longshou.mcyukkuri.social.YukkuriPersonality.Trait;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** A local scouting trip with an appointed captain, roster and real destination; no scripted danger. */
public final class Expedition {
    public static Scene discover(YukkuriEntity captain,List<YukkuriEntity> peers) {
        if(!eligibleCaptain(captain)||captain.getRandom().nextInt(8)!=0)return null;
        var members=peers.stream().filter(e -> eligibleMember(captain,e)).limit(3).toList();
        if(members.isEmpty())return null;
        for(BlockPos pos:BlockPos.betweenClosed(captain.blockPosition().offset(-10,-1,-10),captain.blockPosition().offset(10,1,10))) {
            double distance=pos.distSqr(captain.blockPosition());
            if(distance>=36&&distance<=100&&captain.level().hasChunkAt(pos)&&captain.level().getBlockState(pos).is(Blocks.SHORT_GRASS)) {
                Scene s=form(captain,members,pos);if(s!=null)return s;
            }
        }
        return null;
    }
    private static boolean eligibleCaptain(YukkuriEntity e) {
        return WildEvents.available(e)&&WildEvents.forest(e)&&e.level().isDay()&&e.family().adult()
                &&e.getVariant().dialogueSpecies().equals("marisa")&&e.life().personality().trait(Trait.BOLDNESS)>=60
                &&e.life().nutrition()>=55&&e.life().energy()>=65;
    }
    private static boolean eligibleMember(YukkuriEntity captain,YukkuriEntity e) {
        return WildEvents.available(e)&&e!=captain&&captain.distanceToSqr(e)<=36&&WildEvents.friendly(captain,e)
                &&e.life().nutrition()>=55&&e.life().energy()>=65&&e.getVariant().dialogueSpecies().equals("marisa")
                &&captain.hasLineOfSight(e)&&captain.life().reachable(e.blockPosition(),false);
    }
    public static Scene form(YukkuriEntity captain,List<YukkuriEntity> members,BlockPos destination) {
        if(!eligibleCaptain(captain)||members.isEmpty()||members.size()>3||members.stream().anyMatch(e -> !eligibleMember(captain,e)))return null;
        var level=(ServerLevel)captain.level();double distance=destination.distSqr(captain.blockPosition());
        if(distance<36||distance>100||!level.hasChunkAt(destination)||!level.getBlockState(destination).is(Blocks.SHORT_GRASS)
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
        var s=WildEventData.get(level).forMember(e.getUUID());
        return s==null||s.kind!=Kind.EXPEDITION?"none":s.members.getFirst().equals(e.getUUID())?"captain":"member";
    }
    public static void advance(ServerLevel level,Scene s,List<YukkuriEntity> roster) {
        if(s.destination==null||!s.destination.dimension().equals(level.dimension())){WildEvents.finish(level,s,Phase.INTERRUPTED,"missing_trip_destination");return;}
        var captain=roster.getFirst();
        if(roster.stream().anyMatch(e -> !captain.hasLineOfSight(e)||captain.distanceToSqr(e)>64)) {
            WildEvents.finish(level,s,Phase.INTERRUPTED,"expedition_roster_separated");return;
        }
        // Stages: departure roll call, outward walk, destination roll call, return walk.
        if(s.sourceCount==0||s.sourceCount==2) {
            if(s.phaseTicks%20==0&&s.responses<roster.size()&&roster.get(s.responses).speak("known_peer"))s.responses++;
            if(s.responses==roster.size()){s.sourceCount++;s.responses=0;WildEventData.get(level).phase(s,Phase.APPROACH);}
        } else {
            BlockPos goal=s.sourceCount==1?s.destination.pos():s.origin.pos();
            if(roster.stream().allMatch(e -> e.distanceToSqr(Vec3.atBottomCenterOf(goal))<6.25)) {
                if(s.sourceCount==3)WildEvents.finish(level,s,Phase.COMPLETE,"real_squad_reported_and_returned");
                else {s.sourceCount=2;WildEventData.get(level).phase(s,Phase.RESPONDING);}
            }
        }
        if(s.ticks>=1200)WildEvents.finish(level,s,Phase.INTERRUPTED,"local_expedition_timed_out");
    }
    public static void move(YukkuriEntity e,Scene s) {
        var level=(ServerLevel)e.level();var captain=level.getEntity(s.members.getFirst());
        if(captain!=null&&captain!=e)e.getLookControl().setLookAt(captain,25,25);
        if(s.sourceCount!=1&&s.sourceCount!=3){e.getNavigation().stop();return;}
        if(s.destination==null)return;
        BlockPos goal=s.sourceCount==1?s.destination.pos():s.origin.pos();
        if(e.distanceToSqr(Vec3.atBottomCenterOf(goal))<4){e.getNavigation().stop();return;}
        if(s.phaseTicks%20==0&&(!e.life().dryStand(goal)||!e.life().navigate(goal,.75,false)))
            WildEvents.finish(level,s,Phase.INTERRUPTED,"expedition_route_unreachable");
    }
    private Expedition() {}
}
