package io.github.longshou.mcyukkuri.event;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.event.WildEventData.Kind;
import io.github.longshou.mcyukkuri.event.WildEventData.Phase;
import io.github.longshou.mcyukkuri.event.WildEventData.Scene;
import io.github.longshou.mcyukkuri.life.TravelRange;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.social.BondLevels;
import io.github.longshou.mcyukkuri.social.Bonds;
import io.github.longshou.mcyukkuri.social.DailyLife;
import io.github.longshou.mcyukkuri.social.Temperament.Gate;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.status.StatusEvents;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import io.github.longshou.mcyukkuri.cognition.Cognition;
import java.util.Comparator;
import java.util.function.Predicate;
import net.minecraft.nbt.CompoundTag;

/** Small conditional scenes around existing life, relationships, food and weather. */
public final class WildCards {
    /** No surviving card is an optional play or social scene; care, conflict, food and weather keep their own priority. */
    private static final Set<String> LEISURE=Set.of();
    static boolean leisure(String card) { return LEISURE.contains(card); }
    static boolean ambient(Scene scene) { return scene.kind == Kind.CARD && leisure(scene.card); }
    static boolean canYield(Scene scene) {
        return scene.active() && ambient(scene) && scene.phase == Phase.APPROACH && scene.responses == 0
                && scene.target == null && scene.fed.isEmpty();
    }
    private static final List<String> AMBIENT=List.of("reconcile","group-turn","street-scraps","forest-find");
    public static void discover(YukkuriEntity first,List<YukkuriEntity> peers) {
        int offset=Math.floorMod(first.getUUID().hashCode()+first.tickCount/100,AMBIENT.size());
        for(int i=0;i<AMBIENT.size();i++) {
            String card=AMBIENT.get((offset+i)%AMBIENT.size());
            for(var peer:peers) if(begin(card,first,List.of(peer),null)!=null)return;
        }
    }
    private static boolean close(YukkuriEntity a,YukkuriEntity b,double radius){return a.distanceToSqr(b)<=radius*radius&&a.hasLineOfSight(b);}
    private static ItemEntity target(YukkuriEntity e,Predicate<ItemEntity> predicate) {
        return e.level().getEntitiesOfClass(ItemEntity.class,e.getBoundingBox().inflate(4),item -> item.isAlive()
                && !item.hasPickUpDelay() && e.hasLineOfSight(item) && predicate.test(item)
                && e.life().reachable(item.blockPosition(),false)).stream()
                .min(Comparator.comparingDouble(e::distanceToSqr)).orElse(null);
    }
    private static boolean conflict(YukkuriEntity a,YukkuriEntity b) {
        var memory=a.getPersistentData().getCompound("WildConflict");
        return memory.hasUUID("Peer")&&memory.getUUID("Peer").equals(b.getUUID())
                && memory.getLong("Until")>a.level().getGameTime();
    }
    public static void competing(YukkuriEntity a,YukkuriEntity b) {
        for(var pair:List.of(List.of(a,b),List.of(b,a))) {
            var memory=new CompoundTag();memory.putUUID("Peer",pair.get(1).getUUID());memory.putLong("Until",a.level().getGameTime()+2400);
            pair.getFirst().getPersistentData().put("WildConflict",memory);
        }
    }
    public static Scene begin(String card,YukkuriEntity first,List<YukkuriEntity> peers,Player player) {
        if(!(first.level() instanceof ServerLevel level)||peers.isEmpty()||!WildEvents.available(first))return null;
        var second=peers.getFirst();List<YukkuriEntity> all=new ArrayList<>();all.add(first);all.addAll(peers);
        if(all.stream().distinct().count()!=all.size()||all.size()>6||all.stream().anyMatch(p -> p.life().usingFoundCover())
                ||peers.stream().anyMatch(p -> p.level()!=level
                ||!WildEvents.available(p)||!close(first,p,6)||!first.life().reachable(p.blockPosition(),false)))return null;
        // Everyone in a card must be well and free (the daily-life gate), and cards happen near home, like songs.
        if(!all.stream().allMatch(p -> DailyLife.wellAndFree(p,false)))return null;
        if(!all.stream().allMatch(TravelRange::nearHomeForLeisure))return null;
        var data=WildEventData.get(level);
        if (!data.canStart(GlobalPos.of(level.dimension(),first.blockPosition()), all.stream().map(Entity::getUUID).toList(), level.getGameTime())) return null;
        var relations=YukkuriWorldData.get(level);
        int affinity=relations.affinity(first.getUUID(),second.getUUID());
        ItemEntity resource=null;
        boolean qualified=switch(card) {
            case "reconcile" -> conflict(first,second) && affinity>BondLevels.AFFINITY_RECONCILABLE
                    && first.life().personality().temperament().is(Gate.WARM) && WildEvents.recognizes(first,second);
            case "group-turn" -> Bonds.sameGroupId(first, second)
                    && first.life().canEat() && second.life().canEat()
                    && first.life().nutrition()<65 && second.life().nutrition()<65
                    && WildEvents.friendly(first,second)
                    && (resource=target(first,item -> first.life().acceptsFood(item.getItem())))!=null;
            case "street-scraps" -> WildEvents.inhabited(first) && first.life().nutrition()<65
                    && (resource=target(first,item -> first.life().acceptsFood(item.getItem())))!=null;
            case "forest-find" -> WildEvents.forest(first) && first.life().nutrition()<65 && WildEvents.friendly(first,second)
                    && (resource=target(first,item -> first.life().acceptsFood(item.getItem())))!=null;
            default -> false;
        };
        if(!qualified)return null;
        Scene scene=data.start(Kind.CARD,card,GlobalPos.of(level.dimension(),first.blockPosition()),
                all.stream().map(Entity::getUUID).toList(),player==null?null:player.getUUID(),level.getGameTime());
        if(scene!=null){if(resource!=null)scene.target=resource.getUUID();
            if(card.equals("forest-find"))scene.sourceCount=Cognition.forestFindSucceeds(first,first.getRandom().nextDouble())?1:0;
            for(var e:all){e.social().stop();e.setPersistenceRequired();}}
        return scene;
    }
    public static void advance(ServerLevel level,Scene s,List<YukkuriEntity> all) {
        if(GroupCards.handles(s.card)){GroupCards.advance(level,s,all);return;}
        var first=all.getFirst();var second=all.size()>1?all.get(1):first;
        if(all.stream().anyMatch(e -> !first.hasLineOfSight(e)||first.distanceToSqr(e)>64)) {
            WildEvents.finish(level,s,Phase.INTERRUPTED,"participants_separated");return;
        }
        if(s.phaseTicks==1) {
            for(var e:all)e.showReaction("neutral","wait_turn",60);
        }
        if(foodCard(s)) {
            if(s.card.equals("forest-find")&&s.sourceCount==0) {
                if(s.phaseTicks>=80){first.showReaction("neutral","heard",50);WildEvents.finish(level,s,Phase.REFUSED,"food_not_recognized");}return;
            }
            int expected=s.card.equals("group-turn")?s.members.size():1;
            if(s.fed.size()>=expected){WildEvents.finish(level,s,Phase.COMPLETE,"real_food_taken_and_swallowed");return;}
            Entity item=level.getEntity(s.target);
            boolean outstanding=false;for(int i=0;i<all.size();i++)if((s.responses&(1<<i))!=0&&!s.fed.contains(all.get(i).getUUID()))outstanding=true;
            if(!(item instanceof ItemEntity)||!item.isAlive()) {
                if(!outstanding)WildEvents.finish(level,s,Phase.INTERRUPTED,"finite_food_exhausted");
            }
            if(s.phaseTicks>=600)WildEvents.finish(level,s,Phase.INTERRUPTED,"food_turn_timed_out");return;
        }
        if(s.card.equals("reconcile")) {
            if(s.phaseTicks>=80&&s.responses==0&&second.speak("known_peer"))s.responses=1;
            if(s.responses==1&&level.getGameTime()>=second.speechEnd()) {
                var relations=YukkuriWorldData.get(level);boolean accepted=relations.affinity(second.getUUID(),first.getUUID())>=BondLevels.AFFINITY_DISLIKED;
                if(accepted){relations.relate(first.getUUID(),second.getUUID(),2);relations.relate(second.getUUID(),first.getUUID(),1);
                    first.getPersistentData().remove("WildConflict");second.getPersistentData().remove("WildConflict");}
                WildEvents.finish(level,s,accepted?Phase.COMPLETE:Phase.REFUSED,accepted?"actual_reconciliation_response":"peer_kept_distance");return;
            }
            if(s.phaseTicks>=320)WildEvents.finish(level,s,Phase.INTERRUPTED,"reconciliation_response_interrupted");return;
        }
        if(s.card.equals("voice-peace")||s.card.equals("scold-after")) {
            if(s.phaseTicks>=70 && first.life().mealTicks()==0 && !first.social().active()) {
                first.showReaction(s.card.equals("scold-after")?"surprise":"happy",s.card.equals("scold-after")?"heard":"known_peer",50);
                WildEvents.finish(level,s,Phase.COMPLETE,"real_action_response_finished");return;
            }
        } else if(s.phaseTicks>=80) {
            for(var e:all)e.showReaction("neutral","known_peer",35);
            WildEvents.finish(level,s,Phase.COMPLETE,"observed_"+s.card);return;
        }
        if(s.phaseTicks>=240)WildEvents.finish(level,s,Phase.INTERRUPTED,"scene_timed_out");
    }
    public static void move(YukkuriEntity e,Scene s) {
        if(GroupCards.handles(s.card)){GroupCards.move(e,s);return;}
        var level=(ServerLevel)e.level();Entity peer=level.getEntity(s.members.getFirst());
        if(peer==e && s.members.size()>1)peer=level.getEntity(s.members.get(1));if(peer==null)return;
        e.getLookControl().setLookAt(peer,25,25);
        e.getNavigation().stop();
        if (StatusEvents.trace()) GameplayLog.event(e, "wild-event", "stationary wait scene={} phase={} elapsed={}",
                s.id, s.phase, s.phaseTicks);
    }
    private static boolean foodCard(Scene s){return s!=null&&s.kind==Kind.CARD&&(s.card.equals("group-turn")||s.card.equals("forest-find")||s.card.equals("street-scraps"));}
    /** Gates only the claimed physical item. Hunger and every unrelated food retain their normal rules. */
    public static boolean mayTakeFood(YukkuriEntity e,ItemEntity food) {
        if(!(e.level() instanceof ServerLevel level))return true;
        Scene s=WildEventData.get(level).forMember(e.getUUID(),scene -> foodCard(scene) && food.getUUID().equals(scene.target));
        if(!foodCard(s)||s.target==null||!s.target.equals(food.getUUID()))return true;
        if(s.card.equals("forest-find")&&s.sourceCount==0)return false;
        int index=s.members.indexOf(e.getUUID());
        if(index<0||s.fed.contains(e.getUUID())||(s.responses&(1<<index))!=0)return false;
        return s.card.equals("group-turn")?index==s.fed.size():index==0;
    }
    public static void foodTaken(YukkuriEntity e,ItemEntity food) {
        if(!(e.level() instanceof ServerLevel level))return;
        Scene s=WildEventData.get(level).forMember(e.getUUID(),scene -> foodCard(scene) && food.getUUID().equals(scene.target));
        if(foodCard(s)&&food.getUUID().equals(s.target)){int index=s.members.indexOf(e.getUUID());if(index>=0){s.responses|=1<<index;WildEventData.get(level).setDirty();}}
    }
    public static void swallowed(YukkuriEntity e) {
        if(!(e.level() instanceof ServerLevel level))return;
        Scene s=WildEventData.get(level).forMember(e.getUUID(),scene -> foodCard(scene) && (scene.responses&(1<<scene.members.indexOf(e.getUUID())))!=0 && !scene.fed.contains(e.getUUID()));
        if(foodCard(s)){int index=s.members.indexOf(e.getUUID());if(index>=0&&(s.responses&(1<<index))!=0){s.fed.add(e.getUUID());WildEventData.get(level).setDirty();}}
    }
    public static void mealInterrupted(YukkuriEntity e) {
        if(!(e.level() instanceof ServerLevel level))return;
        Scene s=WildEventData.get(level).forMember(e.getUUID(),scene -> foodCard(scene) && (scene.responses&(1<<scene.members.indexOf(e.getUUID())))!=0 && !scene.fed.contains(e.getUUID()));
        if(foodCard(s)&&(s.responses&(1<<s.members.indexOf(e.getUUID())))!=0&&!s.fed.contains(e.getUUID()))
            WildEvents.finish(level,s,Phase.INTERRUPTED,"claimed_food_meal_interrupted");
    }
    /** Called after talk() really accepted the request; only a witnessed recent conflict qualifies. */
    public static void talked(YukkuriEntity e,UUID player,boolean gentle) {
        if(!(e.level() instanceof ServerLevel level))return;
        var memory=e.getPersistentData().getCompound("WildConflict");if(!memory.hasUUID("Peer"))return;
        Entity raw=level.getEntity(memory.getUUID("Peer"));if(!(raw instanceof YukkuriEntity peer)||!conflict(e,peer))return;
        after(gentle?"voice-peace":"scold-after",e,player,peer);
    }
    private static void after(String card,YukkuriEntity e,UUID player,YukkuriEntity requestedPeer) {
        if(!(e.level() instanceof ServerLevel level)||!WildEvents.safe(e))return;
        Player observer=level.getPlayerByUUID(player);if(!WildEvents.playerNear(e,observer))return;
        if(!WildEvents.available(requestedPeer)||!close(e,requestedPeer,4))return;
        Scene s=WildEventData.get(level).start(Kind.CARD,card,GlobalPos.of(level.dimension(),e.blockPosition()),List.of(e.getUUID(),requestedPeer.getUUID()),player,level.getGameTime());
        if(s!=null){s.phase=Phase.RESPONDING;s.responses=1;requestedPeer.social().stop();}
    }
    private WildCards() {}
}
