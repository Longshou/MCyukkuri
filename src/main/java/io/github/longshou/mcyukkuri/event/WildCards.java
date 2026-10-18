package io.github.longshou.mcyukkuri.event;

import io.github.longshou.mcyukkuri.mind.EmotionEvent;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.event.WildEventData.Kind;
import io.github.longshou.mcyukkuri.event.WildEventData.Phase;
import io.github.longshou.mcyukkuri.event.WildEventData.Scene;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.item.AccessoryItem;
import io.github.longshou.mcyukkuri.life.Facilities;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.social.BondLevels;
import io.github.longshou.mcyukkuri.social.Bonds;
import io.github.longshou.mcyukkuri.social.Temperament;
import io.github.longshou.mcyukkuri.social.YukkuriPersonality.Trait;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.phys.Vec3;
import io.github.longshou.mcyukkuri.body.FillingResource;
import io.github.longshou.mcyukkuri.body.GraveBlockEntity;
import io.github.longshou.mcyukkuri.cognition.Cognition;
import io.github.longshou.mcyukkuri.registry.ModItems;
import io.github.longshou.mcyukkuri.subspecies.SubspeciesLife;
import java.util.Comparator;
import java.util.function.Predicate;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;

/** Small conditional scenes around existing life, relationships, toys and facilities. */
public final class WildCards {
    private static final List<String> AMBIENT=List.of("weak-escort","little-gap","rivalry","crowd","territory","plea","lookout",
            "sibling-game","mate-touch","teach","imitate","ornament","deficient-wait","young-call","reconcile","group-turn","tank-corner","tank-doll",
            "grave-quiet","street-scraps","alley-sniff","alley-corner","forest-find","forest-family","park-family","park-race","rain-close","heat-rest");
    public static void discover(YukkuriEntity first,List<YukkuriEntity> peers) {
        int offset=Math.floorMod(first.getUUID().hashCode()+first.tickCount/100,AMBIENT.size());
        for(int i=0;i<AMBIENT.size();i++) {
            String card=AMBIENT.get((offset+i)%AMBIENT.size());
            for(var peer:peers) {
                List<YukkuriEntity> others=new ArrayList<>();others.add(peer);
                if(card.equals("crowd")||card.equals("alley-corner")||card.equals("forest-family")) {
                    var third=peers.stream().filter(p -> p!=peer && first.distanceToSqr(p)<9
                            && (!card.equals("forest-family")||Bonds.careKin(first, p))).findFirst().orElse(null);
                    if(third==null)continue;others.add(third);
                }
                if(begin(card,first,others,null)!=null)return;
            }
        }
    }
    private static boolean close(YukkuriEntity a,YukkuriEntity b,double radius){return a.distanceToSqr(b)<=radius*radius&&a.hasLineOfSight(b);}
    private static boolean caring(YukkuriEntity a,YukkuriEntity b) {
        return WildEvents.friendly(a,b) && (a.life().personality().selfishness()<=100-Temperament.CARE_AT_60
                || Bonds.careKin(a, b)
                || YukkuriWorldData.get((ServerLevel)a.level()).affinity(a.getUUID(),b.getUUID())>=BondLevels.AFFINITY_CLOSE);
    }
    private static boolean familiar(YukkuriEntity a,YukkuriEntity b) {
        return WildEvents.friendly(a,b) && (Bonds.careKin(a, b)
                || YukkuriWorldData.get((ServerLevel)a.level()).affinity(a.getUUID(),b.getUUID())>=BondLevels.AFFINITY_CLOSE);
    }
    private static ItemEntity target(YukkuriEntity e,Predicate<ItemEntity> predicate) {
        return e.level().getEntitiesOfClass(ItemEntity.class,e.getBoundingBox().inflate(4),item -> item.isAlive()
                && !item.hasPickUpDelay() && e.hasLineOfSight(item) && predicate.test(item)
                && e.life().reachable(item.blockPosition(),false)).stream()
                .min(Comparator.comparingDouble(e::distanceToSqr)).orElse(null);
    }
    private static boolean grave(YukkuriEntity e) {
        for(BlockPos p:BlockPos.betweenClosed(e.blockPosition().offset(-3,-1,-3),e.blockPosition().offset(3,1,3)))
            if(e.level().getBlockEntity(p) instanceof GraveBlockEntity)return true;
        return false;
    }
    private static boolean narrowCorner(YukkuriEntity e) {
        int walls=0;for(var direction:Direction.Plane.HORIZONTAL) {
            var pos=e.blockPosition().relative(direction);
            if(!e.level().getBlockState(pos).getCollisionShape(e.level(),pos).isEmpty())walls++;
        }
        return walls>=2&&WildEvents.inhabited(e);
    }
    private static boolean sharedHomeEntrance(YukkuriEntity a,YukkuriEntity b) {
        var shelter=a.habitat().shelter();
        if(shelter!=null&&shelter==b.habitat().shelter()&&shelter.owns(a.getUUID())&&shelter.owns(b.getUUID()))
            return a.blockPosition().distSqr(shelter.getBlockPos())<16;
        return a.life().home()!=null&&a.life().home().equals(b.life().home())&&a.blockPosition().distSqr(a.life().home().pos())<16;
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
        if(all.stream().distinct().count()!=all.size()||all.size()>6||peers.stream().anyMatch(p -> p.level()!=level
                ||!WildEvents.available(p)||!close(first,p,6)||!first.life().reachable(p.blockPosition(),false)))return null;
        var relations=YukkuriWorldData.get(level);var family=FamilyData.get(level);
        int affinity=relations.affinity(first.getUUID(),second.getUUID());
        ItemEntity resource=null;
        boolean qualified=switch(card) {
            case "weak-escort" -> second.life().energy()<60 && first.life().energy()>=70 && caring(first,second);
            case "little-gap" -> first.getBbWidth()>=second.getBbWidth()*1.5 && close(first,second,2) && caring(first,second);
            case "rivalry" -> affinity<=BondLevels.AFFINITY_DISLIKED && relations.affinity(second.getUUID(),first.getUUID())<BondLevels.AFFINITY_NEUTRAL && WildEvents.recognizes(first,second);
            case "crowd" -> all.size()==3 && all.stream().allMatch(p -> close(first,p,1.6));
            case "territory" -> first.life().personality().escalationScore()>=65 && first.life().personality().selfishness()>=Temperament.SELFISHNESS_AT_65
                    && affinity<=BondLevels.AFFINITY_NEUTRAL && !Bonds.careKin(first, second) && close(first,second,2);
            case "plea" -> first.life().nutrition()<40 && second.life().nutrition()>60 && WildEvents.recognizes(first,second);
            case "lookout" -> familiar(first,second) && level.getNearestPlayer(first,6)!=null
                    && first.life().personality().fear(level.getNearestPlayer(first,6).getUUID())>=BondLevels.FEAR_AT_15;
            case "sibling-game" -> WildEvents.siblings(first,second) && familiar(first,second)
                    && all.stream().allMatch(p -> p.childhood().canExercise()&&p.life().energy()>=65&&p.life().nutrition()>=50&&space(p));
            case "mate-touch" -> second.getUUID().equals(first.family().partner()) && first.getUUID().equals(second.family().partner())
                    && WildEvents.friendly(first,second);
            case "imitate" -> !first.getVariant().dialogueSpecies().equals(second.getVariant().dialogueSpecies())
                    && first.childhood().canLearn() && first.life().personality().trait(Trait.CURIOSITY)>=Temperament.CURIOSITY_AT_60 && WildEvents.friendly(first,second);
            case "teach" -> Cognition.canTeach(first,second) && space(first) && space(second);
            case "ornament" -> !second.life().hasAccessory() && caring(first,second)
                    && (resource=target(second,item -> AccessoryItem.matches(second,item.getItem())))!=null;
            case "deficient-wait" -> second.subspecies().congenital()!=SubspeciesLife.Congenital.NORMAL
                    && caring(first,second) && first.distanceToSqr(second)>4;
            case "young-call" -> WildEvents.childOf(second,first) && first.distanceToSqr(second)>9 && WildEvents.friendly(first,second);
            case "reconcile" -> conflict(first,second) && affinity>BondLevels.AFFINITY_RECONCILABLE
                    && first.life().personality().selfishness()<=100-Temperament.CARE_AT_60 && WildEvents.recognizes(first,second);
            case "group-turn" -> Bonds.sameGroupId(first, second)
                    && first.life().canEat() && second.life().canEat()
                    && first.life().nutrition()<65 && second.life().nutrition()<65
                    && WildEvents.friendly(first,second)
                    && (resource=target(first,item -> first.life().acceptsFood(item.getItem())))!=null;
            case "tank-corner" -> sharedHomeEntrance(first,second) && close(first,second,2) && WildEvents.friendly(first,second);
            case "tank-doll" -> Math.abs(first.life().personality().trait(Trait.CURIOSITY)-second.life().personality().trait(Trait.CURIOSITY))>=20
                    && WildEvents.friendly(first,second) && (resource=target(first,item -> Facilities.toy(item.getItem())
                    && (item.getItem().is(Facilities.ITEMS.get("reimu_doll").get())
                    ||item.getItem().is(Facilities.ITEMS.get("marisa_doll").get()))))!=null;
            case "grave-quiet" -> grave(first) && WildEvents.friendly(first,second);
            case "street-scraps" -> WildEvents.inhabited(first) && first.life().nutrition()<65
                    && (resource=target(first,item -> first.life().acceptsFood(item.getItem())))!=null;
            case "forest-find" -> WildEvents.forest(first) && first.life().nutrition()<65 && WildEvents.friendly(first,second)
                    && (resource=target(first,item -> first.life().acceptsFood(item.getItem())))!=null;
            case "alley-sniff" -> narrowCorner(first) && (first.life().hygiene()<50 || first.life().nutrition()<50)
                    && (resource=target(first,item -> item.getItem().is(ModItems.WASTE.get())
                    ||FillingResource.isFilling(item.getItem())))!=null;
            case "alley-corner" -> all.size()==3&&narrowCorner(first)&&all.stream().allMatch(p -> close(first,p,1.6));
            case "forest-family" -> all.size()>=3 && WildEvents.forest(first)
                    && peers.stream().allMatch(p -> Bonds.careKin(first, p)&&WildEvents.friendly(first,p));
            case "park-family" -> Bonds.careKin(first, second) && WildEvents.friendly(first,second)
                    && level.isDay() && first.life().dryStand(first.blockPosition()) && !first.life().covered(first.blockPosition())
                    && level.getNearestPlayer(first,6)!=null;
            case "park-race" -> familiar(first,second) && level.isDay() && !first.life().covered(first.blockPosition())
                    && all.stream().allMatch(p -> p.life().energy()>=65&&p.life().nutrition()>=50&&space(p));
            case "rain-close" -> level.isRainingAt(first.blockPosition())&&level.isRainingAt(second.blockPosition())&&familiar(first,second);
            case "heat-rest" -> level.isDay() && level.getBiome(first.blockPosition()).value().getBaseTemperature()>=1.0f
                    && !first.life().covered(first.blockPosition()) && second.life().energy()<50 && caring(first,second);
            default -> false;
        };
        if(!qualified)return null;
        Scene scene=WildEventData.get(level).start(Kind.CARD,card,GlobalPos.of(level.dimension(),first.blockPosition()),
                all.stream().map(Entity::getUUID).toList(),player==null?null:player.getUUID(),level.getGameTime(),
                () -> !card.equals("teach") || second.subspecies().acceptsLesson(first.getUUID()));
        if(scene!=null){if(resource!=null)scene.target=resource.getUUID();
            if(card.equals("forest-find"))scene.sourceCount=Cognition.forestFindSucceeds(first,level.random.nextDouble())?1:0;
            if(card.equals("lookout")||card.equals("park-family")){var observer=level.getNearestPlayer(first,6);if(observer!=null)scene.player=observer.getUUID();}
            for(var e:all){e.social().stop();e.setPersistenceRequired();}}
        return scene;
    }
    private static boolean space(YukkuriEntity e) {
        for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)if(!e.life().dryStand(e.blockPosition().offset(x,0,z)))return false;
        return e.level().noCollision(e,e.getBoundingBox().expandTowards(0,.6,0));
    }
    public static void advance(ServerLevel level,Scene s,List<YukkuriEntity> all) {
        if(GroupCards.handles(s.card)){GroupCards.advance(level,s,all);return;}
        var first=all.getFirst();var second=all.size()>1?all.get(1):first;
        if(all.stream().anyMatch(e -> !first.hasLineOfSight(e)||first.distanceToSqr(e)>64)) {
            WildEvents.finish(level,s,Phase.INTERRUPTED,"participants_separated");return;
        }
        if(s.result.equals("voice-peace")||s.result.equals("scold-after")) {
            if(s.phaseTicks>=70&&!first.social().active()) {
                first.showReaction(s.result.equals("voice-peace")?"happy":"surprise","heard",50);
                first.getPersistentData().remove("WildConflict");second.getPersistentData().remove("WildConflict");
                WildEvents.finish(level,s,Phase.COMPLETE,"actual_"+s.result);
            }return;
        }
        if(s.phaseTicks==1) {
            String face=s.card.equals("rivalry")||s.card.equals("territory")?"angry":s.card.equals("plea")?"teary":"neutral";
            for(var e:all)e.showReaction(face,s.card.equals("plea")?"hungry":s.card.equals("ornament")?"search_family":"wait_turn",60);
            if(s.card.equals("rivalry")||s.card.equals("territory"))competing(first,second);
        }
        if(s.card.equals("imitate")) {
            if(s.phaseTicks>=80 && s.responses==0 && second.speak("hello"))s.responses=1;
            if(s.responses==1 && first.imitateSpeech(second))s.responses=2;
            if(s.responses==2 && level.getGameTime()>=first.speechEnd()) {
                WildEvents.finish(level,s,Phase.COMPLETE,"heard_and_repeated_actual_peer_line");return;
            }
            if(s.phaseTicks>=320)WildEvents.finish(level,s,Phase.INTERRUPTED,"imitation_interrupted");return;
        }
        if(s.card.equals("teach")) {
            if(s.phaseTicks==20&&first.onGround()&&space(first)&&first.life().spendEnergy(1))first.getJumpControl().jump();
            if(s.phaseTicks>=20&&s.phaseTicks<55&&first.getDeltaMovement().y>.03)s.responses|=1;
            if(s.phaseTicks==70&&(s.responses&1)!=0&&second.onGround()&&space(second)&&second.life().spendEnergy(1))second.getJumpControl().jump();
            if(s.phaseTicks>=70&&s.phaseTicks<105&&second.getDeltaMovement().y>.03)s.responses|=2;
            if(s.phaseTicks>=110) {
                if(s.responses!=3){WildEvents.finish(level,s,Phase.INTERRUPTED,"demonstration_or_imitation_interrupted");return;}
                if(!Cognition.learningSucceeds(second,level.random.nextDouble())) {
                    second.showReaction("neutral","heard",60);WildEvents.finish(level,s,Phase.REFUSED,"actual_imitation_not_learned");return;
                }
                s.sourceCount=1;
                var result=Cognition.commitTeaching(first,second,s.id);
                WildEvents.finish(level,s,result==Cognition.TeachingResult.INELIGIBLE?Phase.INTERRUPTED:Phase.COMPLETE,
                        result==Cognition.TeachingResult.INELIGIBLE?"teaching_no_longer_eligible":"actual_demonstration_learned");
            }return;
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
        if(s.card.equals("forest-family")) {
            if(s.phaseTicks>=80&&s.phaseTicks%20==0&&s.responses<all.size()&&all.get(s.responses).speak("known_peer"))s.responses++;
            if(s.responses==all.size()){WildEvents.finish(level,s,Phase.COMPLETE,"every_present_relative_responded");return;}
            if(s.phaseTicks>=320)WildEvents.finish(level,s,Phase.INTERRUPTED,"family_response_interrupted");return;
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
        if(s.card.equals("sibling-game")||s.card.equals("park-race")) {
            for(int i=0;i<all.size();i++) {
                var e=all.get(i);int turn=s.phaseTicks<60?0:1;
                if((s.phaseTicks==20+i*4||s.phaseTicks==70+i*4)&&e.onGround()&&space(e)&&e.life().spendEnergy(1))e.getJumpControl().jump();
                if((s.phaseTicks>=20&&s.phaseTicks<55||s.phaseTicks>=70&&s.phaseTicks<105)&&e.getDeltaMovement().y>.03)
                    s.responses|=1<<(i+turn*2);
            }
            if(s.phaseTicks>=110){WildEvents.finish(level,s,s.responses==15?Phase.COMPLETE:Phase.INTERRUPTED,s.responses==15?"two_real_jumps_each":"jump_interrupted");}return;
        }
        if(s.card.equals("ornament")) {
            Entity raw=level.getEntity(s.target);
            if(!(raw instanceof ItemEntity item)||!AccessoryItem.matches(second,item.getItem())) {
                WildEvents.finish(level,s,second.life().hasAccessory()?Phase.COMPLETE:Phase.INTERRUPTED,second.life().hasAccessory()?"accessory_recovered":"accessory_unavailable");return;
            }
            if(second.getBoundingBox().inflate(.2).intersects(item.getBoundingBox())&&!item.hasPickUpDelay()) {
                var tag=item.getItem().getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag();
                if(second.life().returnAccessory(tag.getUUID("Owner"),tag.getUUID("Token"),null)) {
                    var remaining=item.getItem().copy();remaining.shrink(1);if(remaining.isEmpty())item.discard();else item.setItem(remaining);
                    WildEvents.finish(level,s,Phase.COMPLETE,"original_accessory_returned");return;
                }
            }
        } else if(s.card.equals("tank-doll")) {
            Entity raw=level.getEntity(s.target);
            if(!(raw instanceof ItemEntity item)||!item.isAlive()||!Facilities.toy(item.getItem())) {WildEvents.finish(level,s,Phase.INTERRUPTED,"doll_removed");return;}
            int index=s.responses%2;var actor=all.get(index);
            if(s.phaseTicks%30==0&&actor.distanceToSqr(item)<1&&actor.hasLineOfSight(item)&&actor.life().spendEnergy(1)) {
                Vec3 direction=item.position().subtract(actor.position()).multiply(1,0,1).normalize();
                if(direction.lengthSqr()<.01)direction=new Vec3(.1,0,0);
                if(actor.life().dryStand(BlockPos.containing(item.position().add(direction.scale(.2))))) {
                    item.setDeltaMovement(direction.scale(.035).add(0,.035,0));item.hurtMarked=true;s.responses++;
                    actor.mind().felt(EmotionEvent.PLAYED); actor.showReaction("happy","toy",40);
                }
            }
            if(s.responses>=2){WildEvents.finish(level,s,Phase.COMPLETE,"each_touched_real_doll");return;}
        } else if(s.card.equals("fed-after")||s.card.equals("voice-peace")||s.card.equals("scold-after")) {
            if(s.phaseTicks>=70 && first.life().mealTicks()==0 && !first.social().active()) {
                first.showReaction(s.card.equals("scold-after")?"surprise":"happy",s.card.equals("scold-after")?"heard":"known_peer",50);
                WildEvents.finish(level,s,Phase.COMPLETE,"real_action_response_finished");return;
            }
        } else if(s.phaseTicks>=80) {
            boolean reached=switch(s.card) {
                case "little-gap","crowd","alley-corner","tank-corner" -> first.distanceToSqr(second)>=2.25;
                case "weak-escort","deficient-wait","young-call","rain-close","mate-touch" -> first.distanceToSqr(second)<=4;
                default -> true;
            };
            if(reached) {
                for(var e:all)e.showReaction(s.card.equals("rivalry")||s.card.equals("territory")?"angry":"neutral","known_peer",35);
                WildEvents.finish(level,s,Phase.COMPLETE,"observed_"+s.card);return;
            }
        }
        if(s.phaseTicks>=240)WildEvents.finish(level,s,Phase.INTERRUPTED,"scene_timed_out");
    }
    public static void move(YukkuriEntity e,Scene s) {
        if(GroupCards.handles(s.card)){GroupCards.move(e,s);return;}
        var level=(ServerLevel)e.level();Entity peer=level.getEntity(s.members.getFirst());
        if(peer==e && s.members.size()>1)peer=level.getEntity(s.members.get(1));if(peer==null)return;
        e.getLookControl().setLookAt(peer,25,25);
        if(s.card.equals("lookout")&&s.player!=null) {
            var threat=level.getPlayerByUUID(s.player);
            if(threat!=null&&e.hasLineOfSight(threat)&&s.members.indexOf(e.getUUID())==(s.phaseTicks/30)%2)e.getLookControl().setLookAt(threat,30,30);
        }
        if(s.card.equals("ornament")||s.card.equals("tank-doll")) {
            Entity item=level.getEntity(s.target);if(item==null)return;
            boolean actor=s.card.equals("ornament")?e.getUUID().equals(s.members.get(1)):e.getUUID().equals(s.members.get(s.responses%2));
            if(actor){navigate(e,item.position(),s,.5);return;}
        }
        if(s.card.equals("little-gap")||s.card.equals("crowd")||s.card.equals("alley-corner")||s.card.equals("tank-corner")) {
            if(e.getUUID().equals(s.members.getFirst())&&e.distanceToSqr(peer)<3) {
                Vec3 away=e.position().subtract(peer.position()).multiply(1,0,1).normalize();
                if(away.lengthSqr()<.1)away=new Vec3(1,0,0);navigate(e,e.position().add(away.scale(2)),s,0);return;
            }
        } else if(s.card.equals("weak-escort")||s.card.equals("deficient-wait")||s.card.equals("young-call")||s.card.equals("rain-close")||s.card.equals("mate-touch")) {
            navigate(e,peer.position(),s,1.5);return;
        }
        e.getNavigation().stop();
    }
    private static void navigate(YukkuriEntity e,Vec3 point,Scene s,double distance) {
        if(e.distanceToSqr(point)<=distance*distance){e.getNavigation().stop();return;}
        if(s.phaseTicks%20!=0)return;
        BlockPos goal=BlockPos.containing(point);
        if(!e.life().dryStand(goal)||!e.life().navigate(goal,.65,false))WildEvents.finish((ServerLevel)e.level(),s,Phase.INTERRUPTED,"unreachable_scene_target");
    }
    public static void fed(YukkuriEntity e,UUID player) { after("fed-after",e,player,null); }
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
        Scene current=WildEventData.get(level).forMember(e.getUUID(),scene -> scene.kind==Kind.CARD
                && (scene.card.equals("rivalry")||scene.card.equals("territory")) && scene.members.contains(peer.getUUID()));
        if(current!=null && current.kind==Kind.CARD && (current.card.equals("rivalry")||current.card.equals("territory"))) {
            // Keep the existing participant lease and progress rather than minting a competing session.
            WildEventData.get(level).phase(current,Phase.RESPONDING);current.player=player;current.result=gentle?"voice-peace":"scold-after";
        } else after(gentle?"voice-peace":"scold-after",e,player,peer);
    }
    private static void after(String card,YukkuriEntity e,UUID player,YukkuriEntity requestedPeer) {
        if(!(e.level() instanceof ServerLevel level)||!WildEvents.safe(e))return;
        Player observer=level.getPlayerByUUID(player);if(!WildEvents.playerNear(e,observer))return;
        YukkuriEntity peer=requestedPeer;
        if(peer==null)peer=level.getEntitiesOfClass(YukkuriEntity.class,e.getBoundingBox().inflate(4),p -> p!=e&&WildEvents.available(p)&&e.hasLineOfSight(p)).stream().findFirst().orElse(null);
        if(peer==null||!WildEvents.available(peer)||!close(e,peer,4))return;
        Scene s=WildEventData.get(level).start(Kind.CARD,card,GlobalPos.of(level.dimension(),e.blockPosition()),List.of(e.getUUID(),peer.getUUID()),player,level.getGameTime());
        if(s!=null){s.phase=Phase.RESPONDING;s.responses=1;peer.social().stop();}
    }
    private WildCards() {}
}
