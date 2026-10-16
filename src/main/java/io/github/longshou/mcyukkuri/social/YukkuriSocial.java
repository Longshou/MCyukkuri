package io.github.longshou.mcyukkuri.social;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.registry.ModItems;
import io.github.longshou.mcyukkuri.social.YukkuriPersonality.Trait;
import java.util.Comparator;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/** Optional social goals yield to physiology, commands and danger. */
public final class YukkuriSocial {
    private enum Task { NONE, PLAYER, PEER, TOY, TEACH, WAIT, ACCESSORY }
    private final YukkuriEntity entity;
    private Task task = Task.NONE;
    private Entity target;
    private int elapsed, duration, teachingWait;
    private boolean greeted;
    private boolean retreat;
    private int toyContact, toyTouches;
    public YukkuriSocial(YukkuriEntity entity) { this.entity = entity; }
    private YukkuriPersonality personality() { return entity.life().personality(); }
    public String activity() { return task.name().toLowerCase(java.util.Locale.ROOT); }
    public boolean holdingStill() { return task == Task.TEACH || task == Task.WAIT; }
    public boolean active() { return task != Task.NONE; }
    public int toyTouches() { return toyTouches; }
    public boolean beginPlay(ItemEntity toy) {
        if(!entity.childhood().canExercise()||entity.level().isClientSide||toy.level()!=entity.level()||!toy.isAlive()||!io.github.longshou.mcyukkuri.life.Facilities.toy(toy.getItem())
                ||entity.distanceToSqr(toy)>36||!entity.hasLineOfSight(toy)||entity.isPanicking()||entity.life().hasUrgentNeed()
                ||entity.isCommandPlaying()||entity.dailyLife().active()||entity.operations().active()||(entity.groupInteraction().active()||entity.playLife().active())||entity.life().mealTicks()>0)return false;
        var lease=toy.getPersistentData();long now=entity.level().getGameTime();
        if(lease.hasUUID("YukkuriPlayOwner")&&!lease.getUUID("YukkuriPlayOwner").equals(entity.getUUID())&&lease.getLong("YukkuriPlayUntil")>now)return false;
        stop();task=Task.TOY;target=toy;duration=180+personality().trait(Trait.PATIENCE);elapsed=toyContact=toyTouches=0;
        lease.putUUID("YukkuriPlayOwner",entity.getUUID());lease.putLong("YukkuriPlayUntil",now+duration+40);return true;
    }
    public void tick() {
        if (teachingWait > 0) teachingWait--;
        if (active() && (entity.isPanicking() || entity.life().hasUrgentNeed() || entity.isCommandPlaying())) stop();
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
        retreat = !gentle || personality().approachScore(player.getUUID()) < 30;
        entity.getLookControl().setLookAt(player, 30, 30);
        entity.showReaction(gentle ? "happy" : "surprise", gentle ? "gentle" : "cold", 100);
        io.github.longshou.mcyukkuri.event.WildCards.talked(entity,player.getUUID(),gentle);
        return true;
    }
    public boolean teach(Player player) {
        if (!entity.childhood().canLearn() || player.level()!=entity.level() || !player.isAlive() || entity.distanceToSqr(player)>16 || !entity.hasLineOfSight(player)
                || teachingWait > 0 || entity.isPanicking() || entity.dailyLife().active() || entity.life().isSleeping() || entity.life().hasUrgentNeed() || entity.life().mealTicks() > 0
                || entity.isCommandPlaying() || personality().trust(player.getUUID()) < 10 || personality().fear(player.getUUID()) >= 30) {
            entity.showReaction("surprise", "lesson_refused", 80); return false;
        }
        if (!entity.subspecies().acceptsLesson(player.getUUID())) {
            entity.showReaction("angry", "lesson_refused", 80); return false;
        }
        stop(); target = player; elapsed = 0; retreat = false; teachingWait = 240;
        if (personality().knowsWait()) { task = Task.WAIT; duration = 100; entity.showReaction("happy", "wait_learned", 80); }
        else { task = Task.TEACH; duration = personality().lessonDuration(); personality().decided("attend_lesson");entity.showReaction("neutral", "lesson_start", 80); }
        return true;
    }
    public boolean canUse() {
        if (!entity.childhood().canLearn()) return task == Task.PLAYER && target != null && entity.distanceToSqr(target) <= 4 && canContinue();
        if(task==Task.NONE && io.github.longshou.mcyukkuri.event.WildEvents.reserved(entity))return false;
        if (entity.isPanicking() || entity.dailyLife().active() || entity.life().isSleeping() || entity.life().hasUrgentNeed() || entity.life().mealTicks() > 0 || entity.isCommandPlaying()||entity.operations().active()||(entity.groupInteraction().active()||entity.playLife().active())) return false;
        if (task != Task.NONE) return true;
        if (entity.life().socialWait() > 0) return false;
        entity.life().deferSocial(240 - personality().trait(Trait.SOCIABILITY));
        var level = entity.level();
        if(!entity.life().hasAccessory()){
            target=level.getEntitiesOfClass(ItemEntity.class,entity.getBoundingBox().inflate(8),item->item.isAlive()
                    &&io.github.longshou.mcyukkuri.item.AccessoryItem.matches(entity,item.getItem())&&entity.hasLineOfSight(item))
                    .stream().min(Comparator.comparingDouble(entity::distanceToSqr)).orElse(null);
            if(target!=null){task=Task.ACCESSORY;duration=240;elapsed=0;return true;}
        }
        if (entity.getRandom().nextInt(100) < personality().trait(Trait.CURIOSITY)) {
            target = level.getEntitiesOfClass(ItemEntity.class, entity.getBoundingBox().inflate(6), item -> item.isAlive() && io.github.longshou.mcyukkuri.life.Facilities.toy(item.getItem()))
                    .stream().min(Comparator.comparingDouble(entity::distanceToSqr)).orElse(null);
            if (target instanceof ItemEntity toy && beginPlay(toy)) return true;
        }
        if (entity.getRandom().nextInt(100) < personality().trait(Trait.SOCIABILITY)) {
            target = level.getEntitiesOfClass(YukkuriEntity.class, entity.getBoundingBox().inflate(5), peer -> peer != entity && peer.isAlive()
                    && !peer.isNoAi() && !peer.isPanicking() && !peer.life().isSleeping() && !peer.life().hasUrgentNeed()
                    && !peer.dailyLife().active() && !io.github.longshou.mcyukkuri.event.WildEvents.reserved(peer)
                    && !io.github.longshou.mcyukkuri.settlement.SettlementSociety.avoids(entity,peer) && entity.hasLineOfSight(peer)).stream().min(Comparator.comparingDouble(entity::distanceToSqr)).orElse(null);
            if (target != null) { task = Task.PEER; duration = 80; elapsed = 0; greeted = false; return true; }
        }
        target = level.getEntitiesOfClass(Player.class, entity.getBoundingBox().inflate(6), player -> player.isAlive() && !player.isSpectator())
                .stream().max(Comparator.comparingDouble(player -> personality().approachScore(player.getUUID()) - entity.distanceToSqr(player))).orElse(null);
        if (target != null) {
            task = Task.PLAYER; duration = 80; elapsed = 0; greeted = false;
            retreat = personality().approachScore(target.getUUID()) < 40;
            return true;
        }
        return false;
    }
    public boolean canContinue() {
        return task != Task.NONE && target != null && target.isAlive() && target.level() == entity.level()
                && (entity.childhood().canLearn() || task == Task.PLAYER && entity.distanceToSqr(target) <= 4)
                && entity.hasLineOfSight(target) && !entity.dailyLife().active() && !entity.life().isSleeping()
                && entity.distanceToSqr(target) < 64 && !entity.isPanicking() && !entity.life().hasUrgentNeed()
                && entity.life().mealTicks() == 0 && !entity.isCommandPlaying() && elapsed < duration;
    }
    public void tickGoal() {
        if (!canContinue()) { stop(); return; }
        elapsed++;
        entity.getLookControl().setLookAt(target, 25, 25);
        if (!entity.childhood().canLearn()) { entity.getNavigation().stop(); return; }
        if(task==Task.TOY&&target instanceof ItemEntity toy) {
            var lease=toy.getPersistentData();
            if(!io.github.longshou.mcyukkuri.life.Facilities.toy(toy.getItem())||!lease.hasUUID("YukkuriPlayOwner")||!lease.getUUID("YukkuriPlayOwner").equals(entity.getUUID())
                    ||!entity.hasLineOfSight(toy)||!entity.life().dryStand(toy.blockPosition())||entity.operations().active()||(entity.groupInteraction().active()||entity.playLife().active())){stop();return;}
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
                    boolean rolls=io.github.longshou.mcyukkuri.life.Facilities.rollingToy(toy.getItem());
                    toy.setDeltaMovement(direction.scale(rolls?.14:.025).add(0,rolls?.06:0,0));toy.hurtMarked=true;toyContact=0;toyTouches++;
                    if(toyTouches==1){personality().experienced(Trait.CURIOSITY,1);entity.dailyLife().entertained(15);}
                    entity.showReaction("happy","toy",60);
                }
            }
            return;
        }
        if(task==Task.ACCESSORY&&target instanceof ItemEntity item){
            if(!io.github.longshou.mcyukkuri.item.AccessoryItem.matches(entity,item.getItem())){stop();return;}
            var flat=target.position().subtract(entity.position()).multiply(1,0,1);
            if(entity.getBoundingBox().inflate(.2).intersects(item.getBoundingBox())&&!item.hasPickUpDelay()){
                var data=item.getItem().getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA,net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
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
            entity.getNavigation().stop();
            if (entity.distanceToSqr(target) > 16 || personality().fear(target.getUUID()) >= 30) { stop(); return; }
            if(task==Task.TEACH&&elapsed==duration/2&&entity.dailyLife().boredom()>70&&personality().selfControl()<30) {
                personality().decided("lesson_distracted");stop();return;
            }
            if (elapsed == duration && task == Task.TEACH) {
                io.github.longshou.mcyukkuri.cognition.Cognition.get(entity);
                personality().lessonCompleted(target.getUUID());
                if (target instanceof Player teacher) io.github.longshou.mcyukkuri.cognition.Cognition.playerLessonCompleted(entity, teacher);
                entity.showReaction("happy", personality().knowsWait() ? "lesson_learned" : "lesson_progress", 100);
            }
            return;
        }
        if (task == Task.PLAYER && retreat) {
            if (!greeted) { greeted = true; entity.showReaction("surprise", personality().fear(target.getUUID()) > 15 ? "afraid" : "shy", 80); }
            if (elapsed % 10 == 1) {
                Vec3 away = DefaultRandomPos.getPosAway(entity, 5, 2, target.position());
                if (away != null) entity.life().navigate(BlockPos.containing(away), 1.1, false);
            }
            return;
        }
        if (entity.distanceToSqr(target) > 2.25) {
            if (elapsed % 10 == 1 && !entity.life().navigate(target.blockPosition(), 1.0, false)) stop();
            return;
        }
        entity.getNavigation().stop();
        if (!greeted) {
            greeted = true;
            if (task == Task.PLAYER) entity.showReaction("happy", personality().trust(target.getUUID()) >= 20 ? "caregiver" : "hello", 80);
            else if (task == Task.PEER && target instanceof YukkuriEntity peer) {
                if (!peer.life().mouth().isEmpty()) entity.body().tellSource(peer, peer.life().mouth());
                if (!entity.life().mouth().isEmpty()) peer.body().tellSource(entity, entity.life().mouth());
                if (entity.life().canRecognize(peer)) {
                    YukkuriWorldData relations = YukkuriWorldData.get((ServerLevel)entity.level());
                    if (relations.affinity(entity.getUUID(),peer.getUUID()) < 0) {
                        entity.showReaction("angry","unrecognized",60);stop();return;
                    }
                    if (!entity.badges().reactTo(peer)) {
                        entity.showReaction("happy", relations.affinity(entity.getUUID(), peer.getUUID()) >= 10 ? "known_peer" : "peer", 80);
                        relations.relate(entity.getUUID(), peer.getUUID(), 2);
                    }
                    personality().experienced(Trait.SOCIABILITY, 1);
                } else entity.showReaction("surprise", "unrecognized", 80);
            } else if (task == Task.TOY) entity.showReaction("happy", "toy", 100);
        }
    }
    public void stop() {
        if(task==Task.TOY&&target instanceof ItemEntity toy){var lease=toy.getPersistentData();if(lease.hasUUID("YukkuriPlayOwner")&&lease.getUUID("YukkuriPlayOwner").equals(entity.getUUID())){lease.remove("YukkuriPlayOwner");lease.remove("YukkuriPlayUntil");}}
        if (task != Task.NONE) entity.life().deferSocial(240 + (100 - personality().trait(Trait.CURIOSITY)) * 2);
        task = Task.NONE; target = null; elapsed = 0; greeted = false; retreat = false;
        entity.getNavigation().stop();
    }
}
