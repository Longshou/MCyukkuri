package io.github.longshou.mcyukkuri.event;

import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.event.WildEventData.Kind;
import io.github.longshou.mcyukkuri.event.WildEventData.Phase;
import io.github.longshou.mcyukkuri.event.WildEventData.Scene;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.human.HumanCommerce;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.social.BondLevels;
import io.github.longshou.mcyukkuri.social.Bonds;
import io.github.longshou.mcyukkuri.social.YukkuriPersonality.Trait;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Loaded-world encounters bind real identities. No event creates food, relatives or ownership. */
@EventBusSubscriber(modid=MCYukkuri.MOD_ID)
public final class WildEvents {
    public static boolean reserved(YukkuriEntity e) {
        if (!(e.level() instanceof ServerLevel level)) return false;
        Scene s=WildEventData.get(level).forMember(e.getUUID());
        // The existing DailyLife session owns the singer and listener during the concert.
        return s!=null && s.active() && s.kind!=Kind.CONCERT;
    }
    public static String activity(YukkuriEntity e) {
        if (!(e.level() instanceof ServerLevel level)) return Behaviors.IDLE;
        Scene s=WildEventData.get(level).forMember(e.getUUID());
        if(s==null || !s.active())return Behaviors.IDLE;
        return s.kind==Kind.CARD ? Behaviors.event(s.card.replace('-','_'))
                : s.kind==Kind.BADGE_ARC ? Behaviors.eventBadge(s.card)
                : s.phase==Phase.SEPARATED ? Behaviors.EVENT_SEPARATED : Behaviors.event(s.kind.name().toLowerCase(java.util.Locale.ROOT));
    }
    public static boolean safe(YukkuriEntity e) {
        return e.isAlive() && !e.isRemoved() && !e.isNoAi() && !e.isPanicking() && !e.isCommandPlaying()
                && e.family().brood()==null && !e.family().awaitingLanding()
                && !e.operations().active() && !e.pet().busy() && !e.social().holdingStill() && !e.isInWaterOrBubble() && !e.isOnFire() && !e.isPassenger()
                && !e.life().isSleeping() && e.life().energy()>=15 && e.life().nutrition()>=10
                && e.body().condition().readyForWildEvent() && e.life().wetness()<50;
    }
    public static boolean available(YukkuriEntity e) {
        return e.level() instanceof ServerLevel level && safe(e) && WildEventData.get(level).forMember(e.getUUID())==null && e.onGround() && !e.profile().houseBody() && !e.profile().dangerToYukkuri() && !HomeClaim.holds(e)
                && !e.dailyLife().active() && !e.social().active() && !e.groupInteraction().active() && !e.playLife().active()
                && !e.family().active() && !e.house().active() && !e.habitat().active() && e.life().mealTicks()==0;
    }
    public static boolean recognizes(YukkuriEntity a, YukkuriEntity b) {
        return a.life().canRecognize(b) && b.life().canRecognize(a);
    }
    public static boolean friendly(YukkuriEntity a, YukkuriEntity b) {
        if(!recognizes(a,b))return false;
        var relations=YukkuriWorldData.get((ServerLevel)a.level());
        return relations.affinity(a.getUUID(),b.getUUID())>=BondLevels.AFFINITY_NEUTRAL && relations.affinity(b.getUUID(),a.getUUID())>=BondLevels.AFFINITY_NEUTRAL;
    }
    public static boolean siblings(YukkuriEntity a, YukkuriEntity b) {
        if(a==b||a.family().adult()||b.family().adult())return false;
        var data=FamilyData.get((ServerLevel)a.level());var first=data.person(a.getUUID());var second=data.person(b.getUUID());
        return first!=null && second!=null && (first.mother!=null && first.mother.equals(second.mother)
                || first.father!=null && first.father.equals(second.father));
    }
    public static boolean childOf(YukkuriEntity parent, YukkuriEntity child) {
        var person=FamilyData.get((ServerLevel)parent.level()).person(child.getUUID());
        return parent.family().adult() && !child.family().adult() && person!=null
                && (parent.getUUID().equals(person.mother)||parent.getUUID().equals(person.father));
    }
    public static boolean singleParent(YukkuriEntity parent, YukkuriEntity child) {
        if(!childOf(parent,child))return false;
        var data=FamilyData.get((ServerLevel)parent.level());var childRecord=data.person(child.getUUID());
        UUID other=parent.getUUID().equals(childRecord.mother)?childRecord.father:childRecord.mother;
        // Unknown and explicitly dead are distinct valid cases. Unloaded/captured is never dead.
        if(other!=null && (data.person(other)==null || data.person(other).status!=FamilyData.Status.DEAD))return false;
        UUID mate=parent.family().partner();
        return mate==null || data.person(mate)!=null && data.person(mate).status==FamilyData.Status.DEAD;
    }
    public static boolean forest(YukkuriEntity e) {
        var group=e.settlement().group();
        return e.level().getBiome(e.blockPosition()).is(BiomeTags.IS_FOREST)
                || group!=null && group.kind==io.github.longshou.mcyukkuri.settlement.SettlementData.Kind.FOREST;
    }
    public static boolean inhabited(YukkuriEntity e) {
        return ((ServerLevel)e.level()).isCloseToVillage(e.blockPosition(),2) || e.habitat().shelter()!=null;
    }
    public static boolean unowned(YukkuriEntity e) {
        var data=HumanCommerce.data(e);
        return !data.hasUUID("Owner") && !data.contains("PendingRole");
    }
    public static List<YukkuriEntity> nearby(YukkuriEntity e) {
        return e.level().getEntitiesOfClass(YukkuriEntity.class,e.getBoundingBox().inflate(6),p -> p!=e && available(p)
                && e.hasLineOfSight(p) && Math.abs(p.getY()-e.getY())<2).stream()
                .sorted(Comparator.comparingDouble(e::distanceToSqr)).limit(5).toList();
    }
    public static boolean playerNear(YukkuriEntity e, Player player) {
        return player!=null && player.isAlive() && !player.isSpectator() && player.level()==e.level()
                && e.distanceToSqr(player)<=64 && e.hasLineOfSight(player);
    }
    public static Scene begin(Kind kind, YukkuriEntity first, List<YukkuriEntity> others, Player player) {
        if(!(first.level() instanceof ServerLevel level)||!available(first))return null;
        List<YukkuriEntity> all=new ArrayList<>();all.add(first);all.addAll(others);
        if(all.stream().distinct().count()!=all.size() || all.size()>6 || all.stream().anyMatch(e -> !available(e)
                || e.level()!=level || first.distanceToSqr(e)>64 || !first.hasLineOfSight(e)
                || e!=first && !first.life().reachable(e.blockPosition(),false)))return null;
        boolean valid=switch(kind) {
            case SISTERS -> all.size()==2 && siblings(first,others.getFirst()) && friendly(first,others.getFirst())
                    && all.stream().allMatch(e -> unowned(e)&&e.life().nutrition()<40) && all.stream().anyMatch(WildEvents::inhabited) && playerNear(first,player);
            case SINGLE_PARENT -> all.size()>=2 && others.stream().allMatch(e -> singleParent(first,e)&&friendly(first,e))
                    && all.stream().allMatch(WildEvents::unowned) && others.stream().anyMatch(e -> e.life().nutrition()<40)
                    && inhabited(first) && playerNear(first,player);
            case ADOPTION -> first.family().adult() && all.size()>=2 && others.stream().allMatch(e -> Bonds.careKin(first, e)&&friendly(first,e))
                    && all.stream().allMatch(WildEvents::unowned) && playerNear(first,player)
                    && all.stream().allMatch(e -> e.life().nutrition()>=40)
                    && first.life().personality().approachScore(player.getUUID())>=45 && first.life().personality().fear(player.getUUID())<BondLevels.AFFECTION_FEAR_LIMIT
                    && !first.life().hasUrgentNeed() && first.life().dryStand(first.blockPosition());
            case CONCERT -> all.size()==2 && forest(first) && friendly(first,others.getFirst())
                    && first.life().nutrition()>=40 && first.life().energy()>=35 && level.isDay();
            default -> false;
        };
        if(!valid)return null;
        var data=WildEventData.get(level);var origin=GlobalPos.of(level.dimension(),first.blockPosition());
        List<UUID> ids=all.stream().map(Entity::getUUID).toList();
        if(!data.canStart(origin,ids,level.getGameTime()))return null;
        // Start the actual existing song before claiming the event; no synthetic song reward.
        if(kind==Kind.CONCERT && !first.dailyLife().singTo(others.getFirst()))return null;
        Scene scene=data.start(kind,kind==Kind.CONCERT?"concert-response":kind==Kind.SISTERS?"sisters-wait":kind.name().toLowerCase(java.util.Locale.ROOT),
                origin,ids,player==null?null:player.getUUID(),level.getGameTime());
        if(scene!=null){scene.sourceCount=first.dailyLife().songs();if(kind==Kind.CONCERT)scene.concertToken=first.dailyLife().songSessionToken();for(var e:all){e.setPersistenceRequired();if(kind!=Kind.CONCERT)e.social().stop();}}
        return scene;
    }
    @SubscribeEvent public static void discover(EntityTickEvent.Post event) {
        if(!(event.getEntity() instanceof YukkuriEntity e)||!(e.level() instanceof ServerLevel level))return;
        // Observe the single existing leisure scheduler; do not add another random song trigger.
        if(e.tickCount%10==0 && e.dailyLife().songListener()!=null)attachConcert(e,e.dailyLife().songListener());
        if(Math.floorMod(e.tickCount+e.getId(),100)!=0 || !available(e))return;
        var data=WildEventData.get(level);
        if(!data.canStart(GlobalPos.of(level.dimension(),e.blockPosition()),List.of(e.getUUID()),level.getGameTime()))return;
        if(RiverEncounter.discover(e))return;
        if(HomeClaim.discover(e))return;
        List<YukkuriEntity> peers=nearby(e);if(peers.isEmpty())return;
        Player player=level.getNearestPlayer(e,8);
        for(var peer:peers) {
            if(begin(Kind.SISTERS,e,List.of(peer),player)!=null)return;
            var children=peers.stream().filter(p -> singleParent(e,p)&&friendly(e,p)).toList();
            if(!children.isEmpty()&&begin(Kind.SINGLE_PARENT,e,children,player)!=null)return;
        }
        if(playerNear(e,player)) {
            var kin=peers.stream().filter(p -> Bonds.careKin(e, p)&&friendly(e,p)).limit(3).toList();
            if(!kin.isEmpty()&&begin(Kind.ADOPTION,e,kin,player)!=null)return;
        }
        if(GroupCards.discover(e,peers))return;
        if(Expedition.discover(e,peers)!=null)return;
        WildCards.discover(e,peers);
    }
    private static void attachConcert(YukkuriEntity singer,YukkuriEntity listener) {
        var level=(ServerLevel)singer.level();
        if(!safe(singer)||!safe(listener)||!singer.hasLineOfSight(listener)||singer.distanceToSqr(listener)>36
                ||!forest(singer)&&!level.isCloseToVillage(singer.blockPosition(),2))return;
        var data=WildEventData.get(level);var s=data.start(Kind.CONCERT,forest(singer)?"concert-response":"street-performance",
                GlobalPos.of(level.dimension(),singer.blockPosition()),List.of(singer.getUUID(),listener.getUUID()),null,level.getGameTime());
        if(s!=null){s.sourceCount=singer.dailyLife().songs();s.concertToken=singer.dailyLife().songSessionToken();singer.setPersistenceRequired();listener.setPersistenceRequired();}
    }
    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        var data=WildEventData.get(event.getServer().overworld());
        for(Scene s:data.scenes()) {
            if(!s.active())continue;
            ServerLevel level=event.getServer().getLevel(s.origin.dimension());if(level==null)continue;
            advance(level,s);
        }
    }
    public static void advance(ServerLevel level, Scene s) {
        if(!s.active())return;
        if(s.kind==Kind.RIVER){RiverEncounter.advance(level,s);return;}
        var data=WildEventData.get(level);var family=FamilyData.get(level);var custody=YukkuriWorldData.get(level);
        List<YukkuriEntity> loaded=new ArrayList<>(); boolean separated=false;
        for(UUID id:s.members) {
            Entity raw=level.getEntity(id);
            if(raw instanceof YukkuriEntity e && e.isAlive())loaded.add(e);
            else {
                var person=family.person(id);
                if(custody.isCaptured(id) || person!=null && person.status==FamilyData.Status.DEAD)separated=true;
                else if(person!=null && (person.status==FamilyData.Status.REMOVED || person.status==FamilyData.Status.LOST)) {
                    finish(level,s,Phase.INTERRUPTED,"participant_removed");return;
                } else if(java.util.stream.StreamSupport.stream(level.getServer().getAllLevels().spliterator(),false)
                        .anyMatch(other -> other!=level && other.getEntity(id)!=null)) {
                    finish(level,s,Phase.INTERRUPTED,"participant_changed_dimension");return;
                } else return; // Chunk unload or absent metadata: pause, never respawn or settle.
            }
        }
        if(loaded.isEmpty() || loaded.stream().anyMatch(e -> !level.isPositionEntityTicking(e.blockPosition())))return;
        if(separated && s.phase!=Phase.SEPARATED) {
            data.phase(s,Phase.SEPARATED);s.result="member_captured_or_dead";
            for(var e:loaded)e.showReaction("cry","search_family",80);
        }
        // Badge arcs keep their own (action-tolerant) safety checks: they play actions that the generic test would read as danger.
        if(s.kind==Kind.BADGE_ARC && s.phase!=Phase.SEPARATED){io.github.longshou.mcyukkuri.pet.social.BadgeArcs.advance(level,s,loaded);return;}
        if(loaded.stream().anyMatch(e -> !safe(e))) { finish(level,s,Phase.INTERRUPTED,"danger_or_unavailable");return; }
        if(loaded.stream().anyMatch(e -> e.blockPosition().distSqr(s.origin.pos())>(Expedition.far(s)?72*72:32*32))) {finish(level,s,Phase.INTERRUPTED,"left_region");return;}
        s.ticks++;s.phaseTicks++;data.setDirty();
        if(s.phase==Phase.SEPARATED) {
            if(s.phaseTicks%60==1)for(var e:loaded)e.showReaction("teary","wait_family",60);
            if(s.phaseTicks>=180)finish(level,s,Phase.INTERRUPTED,"separation_observed");
            return;
        }
        if(s.kind==Kind.EXPEDITION){Expedition.advance(level,s,loaded);return;}
        if(s.kind==Kind.CARD){WildCards.advance(level,s,loaded);return;}
        if(s.kind==Kind.CONCERT) {
            Object token=loaded.getFirst().dailyLife().songSessionToken();
            if(s.concertToken==null||token!=null&&token!=s.concertToken){finish(level,s,Phase.INTERRUPTED,"original_song_session_lost");return;}
            boolean singing=token==s.concertToken;
            if(singing && s.phaseTicks%40==1) {
                var audience=loaded.get(1);audience.showReaction("happy","known_peer",35);
                level.sendParticles(ParticleTypes.NOTE,audience.getX(),audience.getEyeY()+.1,audience.getZ(),1,.05,.05,.05,0);s.responses++;
            }
            if(!singing){boolean done=loaded.getFirst().dailyLife().songs()>s.sourceCount;
                finish(level,s,done?Phase.COMPLETE:Phase.INTERRUPTED,done?"song_and_audience_finished":"song_interrupted");}
            return;
        }
        if(s.kind==Kind.ABANDONMENT) {
            var e=loaded.getFirst();boolean attached=e.life().personality().trust(s.player)>=BondLevels.TRUST_FAMILIAR;
            if(s.phaseTicks==1)e.showReaction(attached?"surprise":"happy",attached?"search_family":"hello",70);
            if(s.phaseTicks==80)e.showReaction(attached?"cry":"neutral",attached?"search_family":"shy",90);
            if(s.phaseTicks>=200)finish(level,s,Phase.COMPLETE,attached?"former_owner_lament":"left_former_owner");
            return;
        }
        Player player=level.getPlayerByUUID(s.player);
        if(!playerNear(loaded.getFirst(),player)) {finish(level,s,Phase.REFUSED,"player_left");return;}
        if(s.phase==Phase.APPROACH) {
            if(s.phaseTicks==1)for(var e:loaded)e.showReaction("teary",s.kind==Kind.ADOPTION?"caregiver":"hungry",80);
            if(loaded.getFirst().distanceToSqr(player)<=16)data.phase(s,Phase.WAITING);
        } else if(s.phase==Phase.WAITING && s.phaseTicks%120==1) {
            for(var e:loaded)e.showReaction("teary",s.kind==Kind.ADOPTION?"wait_family":"hungry",60);
        } else if(s.phase==Phase.RESPONDING && s.phaseTicks>=80) {
            if(s.kind==Kind.ADOPTION || s.fed.containsAll(s.members))finish(level,s,Phase.COMPLETE,s.kind==Kind.ADOPTION?"individual_adopted":"all_members_actually_fed");
            else data.phase(s,Phase.WAITING);
        }
        if(s.ticks>=1200)finish(level,s,Phase.REFUSED,"request_timed_out");
    }
    public static void finish(ServerLevel level, Scene s, Phase phase, String result) {
        boolean arc=s.kind==Kind.BADGE_ARC;
        if(arc && s.active())io.github.longshou.mcyukkuri.pet.social.BadgeArcs.settle(level,s,phase,result);
        WildEventData.get(level).finish(s,phase,result,level.getGameTime(),arc?io.github.longshou.mcyukkuri.pet.social.BadgeArcs.cooldown(s):1200);
        for(UUID id:s.members)if(level.getEntity(id) instanceof YukkuriEntity e)e.getNavigation().stop();
    }
    /** Called only at the existing successful food swallow, before its feeder UUID is cleared. */
    public static void fed(YukkuriEntity e, UUID player) {
        WildCards.swallowed(e);
        if(player==null || !(e.level() instanceof ServerLevel level))return;
        Scene s=WildEventData.get(level).forMember(e.getUUID());
        if(s!=null && (s.kind==Kind.SISTERS||s.kind==Kind.SINGLE_PARENT) && s.active()) {
            if(s.fed.add(e.getUUID())) {
                WildEventData.get(level).phase(s,Phase.RESPONDING);
                e.showReaction("happy","share",80);
                for(UUID id:s.members)if(!id.equals(e.getUUID()) && level.getEntity(id) instanceof YukkuriEntity peer)
                    peer.showReaction(s.fed.contains(id)?"happy":"teary",s.fed.contains(id)?"known_peer":"hungry",60);
            }
        } else if(s!=null && s.kind==Kind.BADGE_ARC && s.active())io.github.longshou.mcyukkuri.pet.social.BadgeArcs.fed(level,s,e,player);
        else if(s==null)WildCards.fed(e,player);
    }
    /** The standing part of the adopt rule (no distance or safety): shared with the simple exam's wild-animal eligibility. */
    public static boolean canAdopt(YukkuriEntity e, Player player) {
        return !e.profile().dangerToYukkuri()&&!e.profile().houseBody()&&HumanCommerce.mayCapture(player,e)
                &&unowned(e)&&Bonds.trustedPlayer(e, player.getUUID());
    }
    public static boolean adopt(YukkuriEntity e, Player player) {
        if(!(e.level() instanceof ServerLevel level)||!safe(e)||!playerNear(e,player)||e.distanceToSqr(player)>16||!canAdopt(e,player))return false;
        HumanCommerce.assign(e,player.getUUID(),"companion");
        Scene s=WildEventData.get(level).forMember(e.getUUID());
        if(s!=null && s.kind==Kind.ADOPTION){s.player=player.getUUID();WildEventData.get(level).phase(s,Phase.RESPONDING);}
        else if(s!=null && s.kind==Kind.BADGE_ARC && s.active())io.github.longshou.mcyukkuri.pet.social.BadgeArcs.adopted(level,s,e,player);
        e.showReaction("happy","caregiver",80);HomeClaim.adopted(e,player);return true;
    }
    public static boolean abandon(YukkuriEntity e, Player player) {
        if(!(e.level() instanceof ServerLevel level)||!safe(e)||!playerNear(e,player)||e.distanceToSqr(player)>16
                ||!HumanCommerce.ownedBy(e,player.getUUID())||!HumanCommerce.data(e).getString("Use").equals("companion"))return false;
        var data=WildEventData.get(level);Scene old=data.forMember(e.getUUID());
        if(old!=null)return false;
        Scene scene=data.start(Kind.ABANDONMENT,"abandonment",GlobalPos.of(level.dimension(),e.blockPosition()),List.of(e.getUUID()),player.getUUID(),level.getGameTime());
        if(scene==null)return false;
        e.pet().abandonedBy(player.getUUID());
        var owner=HumanCommerce.data(e).copy();owner.remove("Owner");owner.remove("Use");HumanCommerce.set(e,owner);
        e.life().releaseHome();e.social().stop();e.dailyLife().stop();e.setPersistenceRequired();
        data.phase(scene,Phase.WAITING);return true;
    }
    public static boolean decline(YukkuriEntity e, Player player) {
        if(!(e.level() instanceof ServerLevel level)||!playerNear(e,player)||e.distanceToSqr(player)>16)return false;
        if(HomeClaim.declined(e,player))return true;
        Scene s=WildEventData.get(level).forMember(e.getUUID());
        if(s==null||!player.getUUID().equals(s.player)||s.kind==Kind.ABANDONMENT)return false;
        finish(level,s,Phase.REFUSED,"player_declined");return true;
    }
    private WildEvents() {}
}
