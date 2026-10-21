package io.github.longshou.mcyukkuri.social;

import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.cognition.Cognition;
import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.event.WildCards;
import io.github.longshou.mcyukkuri.event.WildEvents;
import io.github.longshou.mcyukkuri.headwear.Contempt;
import io.github.longshou.mcyukkuri.headwear.Recognition;
import io.github.longshou.mcyukkuri.item.AccessoryItem;
import io.github.longshou.mcyukkuri.life.Facilities;
import io.github.longshou.mcyukkuri.life.EcologyMovement;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.mind.EmotionEvent;
import io.github.longshou.mcyukkuri.registry.ModItems;
import io.github.longshou.mcyukkuri.settlement.SettlementSociety;
import io.github.longshou.mcyukkuri.pet.PetSkill;
import io.github.longshou.mcyukkuri.human.HumanNpc;
import io.github.longshou.mcyukkuri.social.YukkuriPersonality.Trait;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.phys.Vec3;

/** Optional social goals yield to physiology, commands and danger. */
public final class YukkuriSocial {
    private enum Task { NONE, PLAYER, PEER, TOY, TEACH, WAIT, ACCESSORY }
    private final YukkuriEntity entity;
    private Task task = Task.NONE;
    private Entity target;
    private UUID protectedContact;
    private int elapsed, duration, teachingWait;
    private boolean greeted;
    private boolean retreat;
    private boolean cautious;
    private double observedDistance;
    private int lessonSegments, lessonLimit;
    private record ContactWait(int until, long lastInteraction) {}
    private final Map<UUID, ContactWait> contactWaits = new HashMap<>();
    private int toyContact, toyTouches;
    public YukkuriSocial(YukkuriEntity entity) { this.entity = entity; }
    private YukkuriPersonality personality() { return entity.life().personality(); }
    public String activity() { return switch(task) { case NONE -> Behaviors.NONE; case PLAYER -> Behaviors.PLAYER; case PEER -> Behaviors.PEER;
            case TOY -> Behaviors.TOY; case TEACH -> Behaviors.TEACH; case WAIT -> Behaviors.WAIT; case ACCESSORY -> Behaviors.ACCESSORY; }; }
    public boolean holdingStill() { return task == Task.TEACH || task == Task.WAIT; }
    public boolean active() { return task != Task.NONE; }
    public int toyTouches() { return toyTouches; }
    public boolean beginPlay(ItemEntity toy) {
        if(!entity.childhood().canExercise()||entity.level().isClientSide||EcologyMovement.boat(entity)!=null||toy.level()!=entity.level()||!toy.isAlive()||!Facilities.toy(toy.getItem())
                ||entity.distanceToSqr(toy)>36||!entity.hasLineOfSight(toy)||entity.isPanicking()||entity.life().hasUrgentNeed()
                ||entity.isCommandPlaying()||entity.dailyLife().active()||entity.operations().active()||(entity.groupInteraction().active()||
                        entity.playLife().active())||entity.life().mealTicks()>0)return false;
        var lease=toy.getPersistentData();long now=entity.level().getGameTime();
        if(lease.hasUUID("YukkuriPlayOwner")&&!lease.getUUID("YukkuriPlayOwner").equals(entity.getUUID())&&lease.getLong("YukkuriPlayUntil")>now)return false;
        stop();task=Task.TOY;target=toy;duration=180+personality().selfControl();elapsed=toyContact=toyTouches=0;
        lease.putUUID("YukkuriPlayOwner",entity.getUUID());lease.putLong("YukkuriPlayUntil",now+duration+40);
        GameplayLog.limited(entity,"social","toy.started",1200,"toy session started toy={} item={} duration={}",toy.getUUID(),toy.getItem().getItem(),duration);
        return true;
    }
    public void tick() {
        if (teachingWait > 0) teachingWait--;
        if (active() && (entity.isPanicking() || urgent() || entity.isCommandPlaying())) stop();
    }
    public boolean talk(Player player, boolean gentle) {
        if (entity.isPanicking() || entity.dailyLife().active() || entity.life().isSleeping() || entity.life().hasUrgentNeed() || entity.life().mealTicks() > 0) {
            entity.showReaction("surprise", "busy", 60); return false;
        }
        if (!personality().talk(player.getUUID(), gentle)) {
            entity.showReaction("neutral", "heard", 40); return false;
        }
        if(gentle)entity.dailyLife().comfort(5);
        stop(); task = Task.PLAYER; target = player; elapsed = 0; duration = 60;
        chooseApproach(player);
        if (!gentle) retreat = true;
        entity.getLookControl().setLookAt(player, 30, 30);
        entity.mind().felt(gentle ? EmotionEvent.COMFORTED : EmotionEvent.COLD_TREATMENT); entity.showReaction(gentle ? "happy" : "surprise", gentle ? "gentle" : "cold", 100);
        WildCards.talked(entity,player.getUUID(),gentle);
        entity.pet().contact(player.getUUID(), gentle);
        return true;
    }
    public boolean teach(Player player) {
        String reason=!entity.childhood().canLearn()?"cannot_learn":player.level()!=entity.level()?"different_level":!player.isAlive()?"teacher_unavailable"
                :entity.distanceToSqr(player)>16?"teacher_too_far":!entity.hasLineOfSight(player)?"no_sight"
                :teachingWait>0?"cooldown":entity.isPanicking()?"panic":entity.dailyLife().active()?"leisure_active":entity.life().isSleeping()?"sleeping"
                :entity.life().hasUrgentNeed()?"urgent_need":entity.life().mealTicks()>0?"eating"
                :entity.isCommandPlaying()?"command_active":!Bonds.trustedForLesson(entity,player.getUUID())?"teacher_not_trusted":null;
        if (reason!=null) {
            lessonRefused(player,reason);
            entity.showReaction("surprise", "lesson_refused", 80); return false;
        }
        if (!entity.subspecies().acceptsLesson(player.getUUID())) {
            lessonRefused(player,"subspecies_declined");
            entity.showReaction("angry", "lesson_refused", 80); return false;
        }
        stop(); target = player; protectContact(player); elapsed = 0; retreat = false; teachingWait = 600;
        if (personality().knowsWait()) {
            float roll=entity.getRandom().nextFloat();var probability=personality().ruleProbability();
            GameplayLog.event(entity,"lesson","wait command decision player={} probability={} roll={} accepted={}",
                    player.getUUID(),probability,roll,roll<probability);
            if (roll >= probability) { stop(); entity.showReaction("neutral", "lesson_refused", 80); return false; }
            task = Task.WAIT; duration = personality().waitingTicks(); entity.showReaction("happy", "wait_learned", 80); }
        else { task = Task.TEACH; duration = 100; lessonSegments = 0; lessonLimit = personality().workSegments(); personality().decided("attend_lesson");entity.showReaction("neutral", "lesson_start", 80); }
        GameplayLog.event(entity,"lesson","social lesson started player={} task={} duration={} knownWait={}",
                player.getUUID(),task,duration,personality().knowsWait());
        return true;
    }
    private void lessonRefused(Player player,String reason){
        GameplayLog.limited(entity,"lesson","social.refused."+reason,200,
                "social lesson refused player={} reason={} cooldown={}",player.getUUID(),reason,teachingWait);
    }
    public boolean canUse() {
        if (task != Task.NONE) return canContinue();
        if (!entity.childhood().canLearn()) return false;
        if(task==Task.NONE && WildEvents.reserved(entity))return false;
        if (entity.isPanicking() || entity.dailyLife().active() || entity.life().isSleeping() || entity.life().hasUrgentNeed() ||
                entity.life().mealTicks() > 0 || entity.isCommandPlaying()||entity.operations().active()||(entity.groupInteraction().active()||entity.playLife().active())) return false;
        if (entity.life().nutrition() < 40 || entity.life().energy() < 35 || entity.family().active()
                || entity.habitat().active() || entity.settlement().active() || entity.settlement().duties().active()
                || entity.forageTrip().outing() || entity.independence().active()) return false;
        if (entity.life().socialWait() > 0) return false;
        entity.life().deferSocial(240 - personality().attachment());
        var level = entity.level();
        // A hat passenger has no safe deck route to a dropped item or toy.  Conversation may
        // still use the actual hull's movement/contact path below.
        if(EcologyMovement.boat(entity)==null&&!entity.life().hasAccessory()&&!entity.life().accessoryCustodied()){
            target=level.getEntitiesOfClass(ItemEntity.class,entity.getBoundingBox().inflate(8),item->item.isAlive()
                    &&AccessoryItem.matches(entity,item.getItem())&&entity.hasLineOfSight(item))
                    .stream().min(Comparator.comparingDouble(entity::distanceToSqr)).orElse(null);
            if(target!=null){task=Task.ACCESSORY;duration=240;elapsed=0;return true;}
        }
        if (EcologyMovement.boat(entity)==null&&entity.getRandom().nextFloat() < .5f) {
            target = level.getEntitiesOfClass(ItemEntity.class, entity.getBoundingBox().inflate(6), item -> item.isAlive() && Facilities.toy(item.getItem()))
                    .stream().min(Comparator.comparingDouble(entity::distanceToSqr)).orElse(null);
            if (target instanceof ItemEntity toy && beginPlay(toy)) return true;
        }
        if (entity.getRandom().nextFloat() < personality().companionshipProbability()) {
            target = level.getEntitiesOfClass(YukkuriEntity.class, entity.getBoundingBox().inflate(5), peer -> peer != entity && peer.isAlive()
                    && !peer.isNoAi() && !peer.isPanicking() && !peer.life().isSleeping() && !peer.life().hasUrgentNeed()
                    && !peer.dailyLife().active() && !peer.social().active() && !WildEvents.reserved(peer)
                    && contactReady(peer) && personality().trust(peer.getUUID()) >= 0
                    && !SettlementSociety.avoids(entity,peer) && entity.hasLineOfSight(peer)
                    && (!Recognition.unrecognizedHatless(entity, peer) || entity.headwear().readyForHatlessContact(peer)))
                    .stream().sorted(Comparator.comparingInt((YukkuriEntity peer) -> personality().trust(peer.getUUID())).reversed()
                            .thenComparingDouble(entity::distanceToSqr)).findFirst().orElse(null);
            if (target != null) { task = Task.PEER; duration = 200; elapsed = 0; greeted = false; chooseApproach(target); return true; }
        }
        target = level.getEntitiesOfClass(LivingEntity.class, entity.getBoundingBox().inflate(6),
                person -> (person instanceof Player player && !player.isSpectator() || person instanceof HumanNpc)
                        && person.isAlive() && contactReady(person) && personality().trust(person.getUUID()) >= 0
                        && entity.hasLineOfSight(person)).stream()
                .sorted(Comparator.comparingInt((LivingEntity person) -> personality().trust(person.getUUID())).reversed()
                        .thenComparingDouble(entity::distanceToSqr)).findFirst().orElse(null);
        if (target != null) {
            task = Task.PLAYER; duration = 200; elapsed = 0; greeted = false;
            chooseApproach(target);
            return true;
        }
        return false;
    }
    public boolean canContinue() {
        return task != Task.NONE && target != null && target.isAlive() && target.level() == entity.level()
                && entity.hasLineOfSight(target) && !entity.dailyLife().active() && !entity.life().isSleeping()
                && entity.distanceToSqr(target) < 64 && !entity.isPanicking() && !urgent()
                && entity.life().mealTicks() == 0 && !entity.isCommandPlaying() && elapsed < duration;
    }
    public void tickGoal() {
        if (!canContinue()) { stop(); return; }
        elapsed++;
        entity.getLookControl().setLookAt(target, 25, 25);
        if(task==Task.TOY&&target instanceof ItemEntity toy) {
            var lease=toy.getPersistentData();
            if(!Facilities.toy(toy.getItem())||!lease.hasUUID("YukkuriPlayOwner")||!lease.getUUID("YukkuriPlayOwner").equals(entity.getUUID())
                    ||!entity.hasLineOfSight(toy)||!entity.life().dryStand(toy.blockPosition())||entity.operations().active()||
                            (entity.groupInteraction().active()||entity.playLife().active())){stop();return;}
            double reach=(entity.getBbWidth()+toy.getBbWidth())*.5+.2;
            if(entity.distanceToSqr(toy)>reach*reach) {
                toyContact=0;
                // A toy within the same step needs direct contact movement. Creating a path first can fail
                // while the freshly spawned entity is settling onto the floor and cancel the whole play session.
                if(entity.distanceToSqr(toy)<1){entity.getNavigation().stop();entity.getMoveControl().setWantedPosition(toy.getX(),entity.getY(),toy.getZ(),.8);}
                else if(elapsed%10==1&&!entity.life().navigate(toy.blockPosition(),1,false))stop();
            } else {
                entity.getNavigation().stop();toyContact++;
                if(toyContact>=12&&toy.getDeltaMovement().horizontalDistanceSqr()<.005) {
                    if(!entity.life().spendEnergy(1)){stop();return;}
                    var direction=toy.position().subtract(entity.position()).multiply(1,0,1).normalize();if(direction.lengthSqr()<.01)direction=new Vec3(1,0,0);
                    if(!entity.life().dryStand(BlockPos.containing(toy.position().add(direction.scale(.8))))){stop();return;}
                    boolean rolls=Facilities.rollingToy(toy.getItem());
                    toy.setDeltaMovement(direction.scale(rolls?.14:.025).add(0,rolls?.06:0,0));toy.hurtMarked=true;toyContact=0;toyTouches++;
                    if(toyTouches==1){entity.dailyLife().entertained(15);}
                    entity.mind().felt(EmotionEvent.PLAYED); entity.showReaction("happy","toy",60);
                }
            }
            return;
        }
        if(task==Task.ACCESSORY&&target instanceof ItemEntity item){
            if(!AccessoryItem.matches(entity,item.getItem())){stop();return;}
            var flat=target.position().subtract(entity.position()).multiply(1,0,1);
            if(entity.getBoundingBox().inflate(.2).intersects(item.getBoundingBox())&&!item.hasPickUpDelay()){
                var data=item.getItem().getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag();
                if(entity.life().returnAccessory(data.getUUID("Owner"),data.getUUID("Token"),null)){
                    item.getItem().shrink(1);if(item.getItem().isEmpty())item.discard();else item.setItem(item.getItem().copy());
                }
                stop();return;
            }
            if(flat.lengthSqr()<1&&entity.life().dryStand(BlockPos.containing(item.getX(),entity.getY(),item.getZ()))){
                entity.getNavigation().stop();entity.getMoveControl().setWantedPosition(item.getX(),entity.getY(),item.getZ(),.8);
                if(entity.onGround()&&elapsed%20==0&&item.getY()>entity.getY()+entity.getBbHeight())entity.getJumpControl().jump();
            }else if(elapsed%10==1&&!entity.life().navigate(BlockPos.containing(item.getX(),entity.getY(),item.getZ()),1,false))stop();
            return;
        }
        if (task == Task.TEACH || task == Task.WAIT) {
            EcologyMovement.halt(entity);
            if (entity.distanceToSqr(target) > 16 || personality().fear(target.getUUID()) >= BondLevels.SOCIAL_FEAR_LIMIT) { stop(); return; }
            if (elapsed == duration && task == Task.TEACH) {
                if (entity.pet().lessons().complete(PetSkill.WAIT)) {
                    personality().relationshipChanged(target.getUUID(), 3, -1);
                    entity.showReaction("happy", personality().knowsWait() ? "lesson_learned" : "lesson_progress", 100);
                } else entity.showReaction("surprise", "lesson_progress", 60);
                lessonSegments++;
                if (!personality().knowsWait() && lessonSegments < lessonLimit && !entity.life().hasUrgentNeed()) elapsed = 0;
            }
            return;
        }
        if ((task == Task.PLAYER || task == Task.PEER) && retreat) {
            if (!greeted) { greeted = true;
                    if (personality().fear(target.getUUID()) > BondLevels.FEAR_AT_15) entity.mind().felt(EmotionEvent.FRIGHTENED);
                    entity.showReaction("surprise", personality().fear(target.getUUID()) > BondLevels.FEAR_AT_15 ? "afraid" : "shy", 80); }
            if (elapsed % 10 == 1) {
                Vec3 away = DefaultRandomPos.getPosAway(entity, 5, 2, target.position());
                if (away != null && EcologyMovement.boat(entity)==null) entity.life().navigate(BlockPos.containing(away), 1.1, false);
            }
            return;
        }
        if (cautious) {
            double distance = entity.distanceTo(target);
            int watchTicks = 20 + 100 - personality().boldness();
            if (distance + 1 < observedDistance && elapsed < watchTicks || personality().fear(target.getUUID()) >= 60 && distance < 1.5) {
                retreat = true; cautious = false; return;
            }
            if (elapsed < watchTicks) { EcologyMovement.halt(entity); return; }
        }
        if (entity.distanceToSqr(target) > 2.25) {
            if (elapsed % 10 == 1 && !EcologyMovement.approach(entity, target, 1.0)
                    && EcologyMovement.boat(entity)==null) stop();
            return;
        }
        EcologyMovement.halt(entity);
        if (!greeted) {
            greeted = true;
            if (task == Task.PLAYER) entity.showReaction("happy", personality().trust(target.getUUID()) >= BondLevels.TRUST_AT_20 ? "caregiver" : "hello", 80);
            else if (task == Task.PEER && target instanceof YukkuriEntity peer) {
                if (entity.life().canRecognize(peer)) {
                    YukkuriWorldData relations = YukkuriWorldData.get((ServerLevel)entity.level());
                    if (relations.affinity(entity.getUUID(),peer.getUUID()) < BondLevels.AFFINITY_NEUTRAL) {
                        entity.mind().felt(EmotionEvent.REJECTED); entity.showReaction("angry","unrecognized",60);stop();return;
                    }
                    if (!entity.pet().reactTo(peer)) {
                        entity.showReaction("happy", relations.affinity(entity.getUUID(), peer.getUUID()) >= BondLevels.AFFINITY_CLOSE ? "known_peer" : "peer", 80);
                        relations.relate(entity.getUUID(), peer.getUUID(), 2);
                        // A greeting is met by the other party: otherwise bonds only grow one way and mutual affinity for nuzzling stalls.
                        if (peer.life().canRecognize(entity) && relations.affinity(peer.getUUID(), entity.getUUID()) >= BondLevels.AFFINITY_NEUTRAL)
                            relations.relate(peer.getUUID(), entity.getUUID(), 1);
                    }

                } else if (Recognition.unrecognizedHatless(entity, peer)) {
                    // Walked up to greet and found no accessory: not taken for a yukkuri, and despised for it (headwear.Contempt).
                    Contempt.react((ServerLevel) entity.level(), entity, peer);
                    stop(); return;
                } else { entity.mind().felt(EmotionEvent.REJECTED); entity.showReaction("surprise", "unrecognized", 80); }
            } else if (task == Task.TOY) { entity.mind().felt(EmotionEvent.PLAYED); entity.showReaction("happy", "toy", 100); }
        }
    }
    private boolean urgent() {
        return entity.life().nutrition() < 15 || entity.life().energy() < 15 || entity.isInWaterOrBubble()
                || entity.isOnFire() || entity.mind().nonYukkuri();
    }
    private boolean contactReady(Entity other) {
        var wait = contactWaits.get(other.getUUID());
        return wait == null || entity.tickCount >= wait.until()
                || personality().lastInteraction(other.getUUID()) != wait.lastInteraction();
    }
    private void protectContact(Entity other) {
        if (protectedContact != null) personality().releaseRelation(protectedContact);
        protectedContact = other.getUUID(); personality().protectRelation(protectedContact);
    }
    private void chooseApproach(Entity other) {
        protectContact(other);
        var fear = personality().fearBand(other.getUUID());
        cautious = personality().trust(other.getUUID()) > 0 && fear != YukkuriPersonality.FearBand.NORMAL;
        retreat = personality().trust(other.getUUID()) <= 0 && fear == YukkuriPersonality.FearBand.AFRAID;
        observedDistance = entity.distanceTo(other);
    }
    public void stop() {
        if(task==Task.TEACH||task==Task.WAIT)GameplayLog.event(entity,"lesson",
                "social lesson stopped task={} teacher={} elapsed={} duration={} segments={} knownWait={}",
                task,target==null?null:target.getUUID(),elapsed,duration,task==Task.TEACH?lessonSegments:0,personality().knowsWait());
        if(task==Task.TOY)GameplayLog.limited(entity,"social","toy.stopped",1200,
                "toy session stopped toy={} elapsed={} contactTicks={} touches={}",
                target==null?null:target.getUUID(),elapsed,toyContact,toyTouches);
        if(task==Task.TOY&&target instanceof ItemEntity toy){var lease=toy.getPersistentData();if(lease.hasUUID("YukkuriPlayOwner")&&
                lease.getUUID("YukkuriPlayOwner").equals(entity.getUUID())){lease.remove("YukkuriPlayOwner");lease.remove("YukkuriPlayUntil");}}
        if (task != Task.NONE) entity.life().deferSocial(600);
        if (target != null && (task == Task.PLAYER || task == Task.PEER)) {
            contactWaits.entrySet().removeIf(e -> e.getValue().until() <= entity.tickCount);
            if (contactWaits.size() >= 64) contactWaits.remove(contactWaits.keySet().iterator().next());
            contactWaits.put(target.getUUID(), new ContactWait(entity.tickCount + 600, personality().lastInteraction(target.getUUID())));
        }
        if (protectedContact != null) { personality().releaseRelation(protectedContact); protectedContact = null; }
        task = Task.NONE; target = null; elapsed = 0; greeted = false; retreat = false; cautious = false;
        entity.getNavigation().stop();
    }
}
