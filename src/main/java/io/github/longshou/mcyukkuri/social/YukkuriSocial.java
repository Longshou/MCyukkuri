package io.github.longshou.mcyukkuri.social;

import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.status.Reactions;
import io.github.longshou.mcyukkuri.subspecies.SubspeciesLife;
import io.github.longshou.mcyukkuri.shop.ShopMenu;
import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriComponents;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.event.WildCards;
import io.github.longshou.mcyukkuri.event.WildEvents;
import io.github.longshou.mcyukkuri.headwear.Contempt;
import io.github.longshou.mcyukkuri.headwear.Recognition;
import io.github.longshou.mcyukkuri.item.AccessoryItem;
import io.github.longshou.mcyukkuri.life.Facilities;
import io.github.longshou.mcyukkuri.life.EcologyMovement;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.mind.EmotionEvent;
import io.github.longshou.mcyukkuri.motor.ClipMarkers;
import io.github.longshou.mcyukkuri.motor.GreetingStance;
import io.github.longshou.mcyukkuri.motor.ContactGeometry;
import io.github.longshou.mcyukkuri.motor.MotorOwner;
import io.github.longshou.mcyukkuri.motor.Outcome;
import io.github.longshou.mcyukkuri.motor.action.Face;
import io.github.longshou.mcyukkuri.motor.action.StepIn;
import io.github.longshou.mcyukkuri.settlement.SettlementSociety;
import io.github.longshou.mcyukkuri.pet.PetSkill;
import io.github.longshou.mcyukkuri.pet.PetStudy;
import io.github.longshou.mcyukkuri.human.HumanNpc;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/** Optional social goals yield to physiology, commands and danger. */
public final class YukkuriSocial implements MotorOwner {
    private static final double HUMAN_CONTACT = 1.5, FOOD_HANDOFF = 2.5;
    private static final float ASK_NUTRITION_MIN = 30, ASK_NUTRITION_MAX = 50, FED_NUTRITION = 55;
    private enum Task { NONE, PLAYER, PEER, TOY, ACCESSORY, ANSWER, STUDY }
    private final YukkuriEntity entity;
    private Task task = Task.NONE;
    private Entity target;
    private UUID protectedContact;
    private int elapsed, duration;
    private boolean greeted;
    private boolean retreat;
    private boolean cautious;
    private double observedDistance;
    private float lastFoodRequest = -1;
    private boolean humanMayAsk;
    private double humanRetreatRoll;
    private int humanWatchUntil;
    private long replyUntil;
    /** A loose-accessory retrieval: one budget for the whole trip (the walk to a bank stand, the step in and the swim), since a
     *  route round a bank corner is longer than the social tasks' short beat. */
    private static final int ACCESSORY = 1200;
    /** The bank stands this retrieval already stepped in from (the swim did not reach the item): not chosen again. */
    private final Set<BlockPos> triedStands = new HashSet<>();
    private record ContactWait(int until, long lastInteraction) {}
    private final Map<UUID, ContactWait> contactWaits = new HashMap<>();
    private int toyContact, toyTouches;
    private PetStudy.Exercise exercise;
    private int greetClicks;
    private long greetUntil;
    private String greetClip = "greet";
    /** A stopped retrieval is not restarted on the next goal evaluation. */
    private long accessoryRetry;
    public int greetClicks() { return greetClicks; }
    public boolean greeting() { return entity.level().getGameTime() < greetUntil; }
    public long greetStart() { return greetUntil - ClipMarkers.of(greetClip).length(); }
    public String greetingClip() { return greetClip; }
    /** Presentation request only: no command goal and no interruption of the current leisure session. */
    private void greetingBody(String clip) {
        greetClip = clip;
        greetUntil = entity.level().getGameTime() + ClipMarkers.of(clip).length();
    }

    /** The existing empty-hand greeting, counted once at its actual player entry. */
    public void clickedTalk(Player player) {
        if (ShopMenu.clickStock(player,entity)) return;
        boolean feedback = entity.pet().lessons().praise(player)
                || (entity.life().isSleeping() && entity.pet().care().praise(player));
        boolean accepted = feedback || talk(player, true);
        greetClicks++;
        if (accepted && !feedback) {
            greetingBody("greet");
            entity.pet().care().praise(player);
        }
        GameplayLog.event(entity, "interaction", "greet click player={} accepted={} clicks={}", player.getUUID(), accepted, greetClicks);
    }
    /** Greeting memory (runtime only): last contact per peer, the pending morning greeting and the answer a greeted peer owes. Project design. */
    private static final int REUNION_GAP = 6000, ANSWER_BASE = 20, ANSWER_SPREAD = 11, ANSWER_HOLD = 40, ANSWER_EXPIRY = 200;
    private final Map<UUID, Long> lastContact = new HashMap<>();
    private boolean wasSleeping, morningPending;
    private long morningDay = -1, answerUntil;
    private String greetWhy, answerWhy;
    private boolean answerScorn;
    private int answerDelay;
    public YukkuriSocial(YukkuriEntity entity) { this.entity = entity; }
    private YukkuriPersonality personality() { return entity.life().personality(); }
    public String activity() { return switch(task) { case NONE -> Behaviors.NONE; case PLAYER -> Behaviors.PLAYER; case PEER -> Behaviors.PEER;
            case TOY -> Behaviors.TOY; case ACCESSORY -> Behaviors.ACCESSORY; case ANSWER -> Behaviors.PEER; case STUDY -> Behaviors.TEACH; }; }
    public boolean active() { return task != Task.NONE; }
    public boolean humanContact() { return task == Task.PLAYER; }
    /** The actual completed contact in the current social goal, without probing eligibility. */
    public UUID observedGreeting() {
        return (task == Task.PLAYER || task == Task.PEER) && greeted && !retreat ? target.getUUID() : null;
    }
    @Override public String ownerId() { return "social:accessory"; }
    public boolean ownsBodyInWater() { return task == Task.ACCESSORY && entity.profile().aquatic(); }
    public int toyTouches() { return toyTouches; }
    public boolean beginPlay(ItemEntity toy) {
        if(!entity.childhood().canExercise()||entity.level().isClientSide||toy.level()!=entity.level()||!toy.isAlive()||!Facilities.toy(toy.getItem())
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
        if (active() && (entity.isPanicking() || urgent() || entity.isCommandPlaying())) stop();
    }
    public void save(CompoundTag tag) {
        if (lastFoodRequest >= 0) tag.putFloat("LastFoodRequestNutrition", lastFoodRequest);
    }
    public void load(CompoundTag tag) {
        lastFoodRequest = tag.contains("LastFoodRequestNutrition") ? tag.getFloat("LastFoodRequestNutrition") : -1;
    }
    public void ate(float nutrition) {
        if (nutrition >= FED_NUTRITION) lastFoodRequest = -1;
    }
    public void mealStarted() {
        if (humanContact()) stop();
    }
    public boolean busyForTalk() {
        return entity.isPanicking() || entity.dailyLife().active() || entity.life().isSleeping()
                || entity.life().hasUrgentNeed() || entity.life().mealTicks() > 0;
    }
    public boolean talk(Player player, boolean gentle) {
        if (busyForTalk()) {
            entity.showReaction("surprise", "busy", 60); return false;
        }
        if (!personality().talk(player.getUUID(), gentle)) {
            entity.showReaction("neutral", "heard", 40); return false;
        }
        if(gentle)entity.dailyLife().comfort(5);
        stop();
        if (!entity.isPassenger()) {
            startHuman(player, false);
            duration = 60;
            retreat = !gentle;
        }
        entity.getLookControl().setLookAt(player, 30, 30);
        entity.mind().felt(gentle ? EmotionEvent.COMFORTED : EmotionEvent.COLD_TREATMENT); entity.showReaction(gentle ? "happy" : "surprise", gentle ? "gentle" : "cold", 100);
        WildCards.talked(entity,player.getUUID(),gentle);
        entity.pet().contact(player.getUUID(), gentle);
        return true;
    }
    public boolean canUse() {
        if (task == Task.ANSWER && entity.level().getGameTime() > answerUntil) { stop(); return false; }
        if (task != Task.NONE) return canContinue();
        if (entity.life().isSleeping()) wasSleeping = true;
        else if (wasSleeping) {
            wasSleeping = false;
            long day = entity.level().getDayTime() / 24000L;
            if (entity.level().isDay() && day != morningDay) { morningPending = true; morningDay = day; }
        }
        if (!entity.childhood().canLearn() || !availableForSocial()) return false;
        boolean regular = entity.life().nutrition() >= 40;
        boolean canAsk = canRequestFood();
        if (!regular && !canAsk) return false;
        var reunion = entity.moldExile().reunionPeer();
        if (regular && reunion != null && contactReady(reunion)) {
            target = reunion;
            approachPeer("reunion");
            return true;
        }
        if (entity.pet().lessons().practicing(PetSkill.GREETING)) {
            var guest = PetStudy.visitor(entity);
            if (guest != null && contactReady(guest)) {
                startHuman(guest, false);
                return true;
            }
        }
        if (entity.life().socialWait() > 0) return false;
        entity.life().deferSocial(240 - personality().attachment());
        exercise = entity.pet().lessons().studyExample(null);
        if (exercise != null) {
            task = Task.STUDY;
            duration = ClipMarkers.of("think").length();
            elapsed = 0;
            return true;
        }
        var level = entity.level();
        if (canAsk && findHuman(true)) return true;
        if (!regular) return false;
        if (morningPending) {
            morningPending = false;
            target = level.getEntitiesOfClass(YukkuriEntity.class, entity.getBoundingBox().inflate(6), peer -> peer != entity && peer.isAlive()
                    && !peer.isNoAi() && !peer.isPanicking() && !peer.life().isSleeping() && !peer.social().active() && Bonds.careKin(entity, peer)
                    && !attachedTo(peer) && entity.hasLineOfSight(peer) && !SettlementSociety.avoids(entity, peer))
                    .stream().min(Comparator.comparingDouble(entity::distanceToSqr)).orElse(null);
            if (target != null) { approachPeer("morning"); return true; }
        }
        if (entity.getRandom().nextFloat() < .5f) {
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
            if (target != null) { approachPeer(null); return true; }
        }
        return !canAsk && findHuman(false);
    }

    private record HumanCandidate(LivingEntity person, YukkuriWorldData.Relation relation) {}

    private boolean findHuman(boolean ask) {
        var candidates = entity.level().getEntitiesOfClass(LivingEntity.class, entity.getBoundingBox().inflate(6),
                        person -> (person instanceof Player player && !player.isSpectator() || person instanceof HumanNpc)
                                && person.isAlive() && contactReady(person) && entity.hasLineOfSight(person)
                                && entity.distanceToSqr(person) <= 36).stream()
                .map(person -> new HumanCandidate(person, personality().relation(person.getUUID())))
                .filter(candidate -> candidate.relation().closeness() >= 0)
                .sorted(Comparator.comparingInt((HumanCandidate candidate) -> candidate.relation().closeness()).reversed()
                        .thenComparingDouble(candidate -> entity.distanceToSqr(candidate.person())))
                .toList();
        for (var candidate : candidates) {
            if (tryHuman(candidate.person(), candidate.relation(), ask)) return true;
        }
        return false;
    }

    private boolean tryHuman(LivingEntity person, YukkuriWorldData.Relation relation, boolean ask) {
        double wariness = SocialDisposition.humanWariness(entity, person, relation);
        // Watching or backing away needs no route toward the person.
        if (wariness < .50 && !atHumanContact(person) && humanStand(person) == null) {
            GameplayLog.limited(entity, "social", "human_unreachable", 200,
                    "human contact blocked target={} reason=no_dry_stand", person.getUUID());
            return false;
        }
        startHuman(person, ask);
        return true;
    }

    private void startHuman(LivingEntity person, boolean ask) {
        task = Task.PLAYER;
        target = person;
        elapsed = 0;
        duration = 200;
        humanMayAsk = ask;
        humanRetreatRoll = entity.getRandom().nextDouble();
        protectContact(person);
    }

    /** The food approach already has this player in view. Release its movement only at a real social contact. */
    public boolean takeFoodApproach(Player player) {
        if (humanContact() && target == player) return true;
        if (active() || entity.distanceToSqr(player) > FOOD_HANDOFF * FOOD_HANDOFF || entity.life().socialWait() > 0
                || !availableForSocial() || !canRequestFood() || !contactReady(player) || !entity.hasLineOfSight(player)) return false;
        var relation = personality().relation(player.getUUID());
        return relation.closeness() >= 0 && tryHuman(player, relation, true);
    }

    private boolean canRequestFood() {
        float nutrition = entity.life().nutrition();
        return lastFoodRequest < 0 && nutrition >= ASK_NUTRITION_MIN && nutrition < ASK_NUTRITION_MAX
                && entity.status().identity().free() && !entity.profile().dangerToYukkuri() && !entity.settlement().society().slave()
                && entity.childhood().canLiveIndependently() && entity.life().canEat()
                && entity.component(YukkuriComponents.MOUTH).purpose() == null;
    }

    private boolean atHumanContact(Entity person) {
        return entity.distanceToSqr(person) <= HUMAN_CONTACT * HUMAN_CONTACT && EcologyMovement.supported(entity)
                && SurfaceApproach.supportedGround(entity, entity.position());
    }

    private Vec3 humanStand(Entity person) {
        return SurfaceApproach.beside(entity, person, 1.25, HUMAN_CONTACT, HUMAN_CONTACT);
    }
    /** Nothing of its own under way (needs, leisure, errands, events): it may take up a social goal now. */
    private boolean freeForSocial() {
        return entity.life().nutrition() >= 40 && availableForSocial();
    }
    private boolean availableForSocial() {
        return !WildEvents.reserved(entity) && !entity.isPanicking() && !entity.dailyLife().active() && !entity.life().isSleeping()
                && !entity.life().hasUrgentNeed() && !entity.waterDistress().active() && entity.life().mealTicks() == 0
                && !entity.isCommandPlaying() && !entity.operations().active() && !entity.groupInteraction().active() && !entity.playLife().active()
                && entity.life().energy() >= 35 && !entity.family().active()
                && !entity.habitat().active() && !entity.settlement().active() && !entity.settlement().duties().active()
                && !entity.forageTrip().outing() && !entity.independence().active();
    }
    /** Retrieving the real accessory has headwear priority, independent of optional greetings and their cooldown. */
    public boolean retrievingAccessory() { return task == Task.ACCESSORY; }

    public boolean startAccessory() {
        if (task != Task.NONE || entity.tickCount < accessoryRetry || entity.life().hasAccessory() || entity.life().accessoryCustodied()
                || !entity.childhood().canLearn() || entity.isPanicking() || entity.life().isSleeping()
                || entity.life().hasUrgentNeed() || entity.waterDistress().active() || entity.life().mealTicks() > 0
                || entity.isCommandPlaying() || entity.operations().active()) return false;
        target=entity.level().getEntitiesOfClass(ItemEntity.class,entity.getBoundingBox().inflate(8),item->{
            if(!item.isAlive()||!AccessoryItem.matches(entity,item.getItem()))return false;
            boolean reachable=ContactGeometry.inMouthReach(entity,item)||SurfaceApproach.approach(entity,item)!=null;
            if(!reachable)GameplayLog.limited(entity,"headwear","accessory_unreachable",200,
                    "loose accessory unreachable item={} at={} sight={} in_water={}",item.getUUID(),GameplayLog.at(item.position()),entity.hasLineOfSight(item),item.isInWater());
            return reachable;
        }).stream().min(Comparator.comparingDouble(entity::distanceToSqr)).orElse(null);
        if(target==null)return false;
        task=Task.ACCESSORY;duration=ACCESSORY;elapsed=0;triedStands.clear();
        GameplayLog.event(entity,"headwear","loose accessory retrieval started item={} at={}",target.getUUID(),GameplayLog.at(target.position()));
        return true;
    }
    public boolean canContinue() {
        if (task == Task.STUDY) return elapsed < duration && freeForSocial() && !entity.pet().busy();
        return task != Task.NONE && target != null && target.isAlive() && target.level() == entity.level()
                && !attachedTo(target)
                && (task == Task.ACCESSORY || entity.hasLineOfSight(target)) && !entity.dailyLife().active() && !entity.life().isSleeping()
                && entity.distanceToSqr(target) < 64 && !entity.isPanicking() && !urgent()
                && entity.life().mealTicks() == 0 && !entity.isCommandPlaying()
                && (replyUntil > 0 ? entity.level().getGameTime() < replyUntil : elapsed < duration || holdingPeerGreeting())
                && (!humanContact() || !entity.isPassenger() && !entity.life().hasUrgentNeed() && !entity.operations().active()
                        && !entity.waterDistress().active() && !(target instanceof Player player && player.isSpectator()))
                && (task != Task.ANSWER || answerDelay > 0 || approachedBy(target));
    }
    public void tickGoal() {
        if (!canContinue()) { stop(); return; }
        elapsed++;
        if (task == Task.STUDY) {
            EcologyMovement.halt(entity);
            if (elapsed == 1) entity.showReaction("confused", "", duration);
            if (elapsed >= duration) {
                var actual = PetStudy.find(entity, null, exercise.skill());
                if (actual != null && exercise.sameExample(actual)) PetStudy.answer(entity, actual);
            }
            return;
        }
        entity.getLookControl().setLookAt(target, 25, 25);
        if (task == Task.ANSWER) {
            EcologyMovement.halt(entity);
            if (target instanceof YukkuriEntity peer) turnToFace(peer);
            if (elapsed == answerDelay && target instanceof YukkuriEntity other) {
                if (answerScorn) Contempt.react((ServerLevel) entity.level(), entity, other);
                else if (entity.isPassenger() || GreetingStance.clear(entity, entity.position(), other))
                    answerGreeting(other, "greet", answerWhy);
                else stop();
            }
            return;
        }
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
            if(ContactGeometry.inMouthReach(entity,item)){
                if(item.hasPickUpDelay())return;
                if(AccessoryItem.receive(entity,item.getItem(),null)){
                    item.getItem().shrink(1);if(item.getItem().isEmpty())item.discard();else item.setItem(item.getItem().copy());
                }
                stop();return;
            }
            var motor=entity.motor();
            if(motor.owner()==this){
                if(motor.current()!=null)return;
                if(!(motor.last() instanceof Outcome.Done)){stop();return;}
            }
            var move=SurfaceApproach.approach(entity,item,triedStands);
            if(move instanceof StepIn)triedStands.add(entity.blockPosition().immutable());
            if(move==null||!motor.run(this,move)){
                GameplayLog.limited(entity,"headwear","accessory_no_way",200,"loose accessory retrieval ended item={} stands={} reason={}",
                        item.getUUID(),triedStands.size(),move==null?"no_way":"refused");
                stop();return;
            }
            return;
        }
        if (humanContact()) {
            tickHumanContact();
            return;
        }
        if (task == Task.PEER && retreat) {
            if (!greeted) {
                greeted = true;
                boolean afraid = personality().fear(target.getUUID()) > BondLevels.FEAR_AT_15;
                if (afraid) entity.mind().felt(EmotionEvent.FRIGHTENED);
                entity.showReaction("surprise", afraid ? "afraid" : "shy", 80);
            }
            if (elapsed % 10 == 1) {
                Vec3 away = DefaultRandomPos.getPosAway(entity, 5, 2, target.position());
                if (away != null) entity.life().navigate(BlockPos.containing(away), 1.1, false);
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
        if (holdingPeerGreeting()) {
            EcologyMovement.halt(entity);
            return;
        }
        if (!greeted && task == Task.PEER && target instanceof YukkuriEntity peer && !entity.isPassenger()) {
            var arrival = GreetingStance.approach(entity, peer, 1.0, 1.5);
            if (arrival == SurfaceApproach.Result.BLOCKED) {
                entity.showReaction("surprise", "", 40);
                stop();
            }
            if (arrival != SurfaceApproach.Result.ARRIVED) return;
            if (!turnToFace(peer)) return;
        } else if (entity.distanceToSqr(target) > 2.25) {
            // approach reports arrival, not path acceptance. A walking partner must keep its goal until contact,
            // the existing duration limit or a real interruption, rather than abort on the first moving tick.
            // Every tick: the shared approach's last step is a one-tick MoveControl input, so a throttled call crawls.
            if (EcologyMovement.approachResult(entity, target, 1.0) == SurfaceApproach.Result.BLOCKED) {
                entity.showReaction("surprise", "", 40);
                stop();
            }
            return;
        }
        EcologyMovement.halt(entity);
        if (!greeted && task == Task.PEER && target instanceof YukkuriEntity peer
                && entity.dailyLife().nuzzleOnArrival(peer)) return;
        if (!greeted) {
            greeted = true;
            if (task == Task.PEER && target instanceof YukkuriEntity peer) {
                if (entity.life().canRecognize(peer)) {
                    YukkuriWorldData relations = YukkuriWorldData.get((ServerLevel)entity.level());
                    if (relations.affinity(entity.getUUID(),peer.getUUID()) < BondLevels.AFFINITY_NEUTRAL) {
                        entity.mind().felt(EmotionEvent.REJECTED); entity.showReaction("angry","unrecognized",60);stop();return;
                    }
                    String why = greetReason(peer, relations);
                    if (why != null && Recognition.unrecognizedHatless(peer, entity)) peer.social().receiveGreeting(entity, true, why);
                    else if (why != null) {
                        startGreeting(peer, why);
                        peer.social().receiveGreeting(entity, false, why);
                        relations.relate(entity.getUUID(), peer.getUUID(), 2);
                        if (peer.life().canRecognize(entity) && relations.affinity(peer.getUUID(), entity.getUUID()) >= BondLevels.AFFINITY_NEUTRAL)
                            relations.relate(peer.getUUID(), entity.getUUID(), 1);
                    }
                    else if (!entity.pet().reactTo(peer)) {
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
    private boolean holdingPeerGreeting() {
        if (task != Task.PEER || !greeted || !(target instanceof YukkuriEntity peer)) return false;
        var answer = peer.social();
        return greeting() || answer.task == Task.ANSWER && answer.target == entity && answer.answerDelay > 0;
    }

    private void tickHumanContact() {
        var relation = personality().relation(target.getUUID());
        double wariness = SocialDisposition.humanWariness(entity, target, relation);
        if (!retreat && (humanRetreatRoll < SocialDisposition.ramp(wariness, .62, .80)
                || elapsed == 1 && visitor(target) && !entity.pet().lessons().attempt(PetSkill.GREETING))) {
            retreat = true;
            greeted = false;
            replyUntil = 0;
            duration = elapsed + 80;
        }
        if (retreat) {
            retreatFromHuman(wariness);
            return;
        }
        if (greeted) {
            if (!atHumanContact(target)) stop();
            return;
        }
        if (wariness >= .18 && humanWatchUntil == 0) {
            int watch = 10 + (int) (30 * SocialDisposition.ramp(wariness, .18, .50));
            humanWatchUntil = elapsed + watch;
            entity.showReaction("surprise", "", watch);
            GameplayLog.limited(entity, "social", "human_watch", 200, "human watch target={} wariness={} ticks={}",
                    target.getUUID(), wariness, watch);
        }
        if (elapsed < humanWatchUntil) {
            EcologyMovement.halt(entity);
            return;
        }
        if (wariness >= .50) {
            stop();
            return;
        }
        if (!atHumanContact(target)) {
            Vec3 stand = humanStand(target);
            if (stand == null || SurfaceApproach.walkResult(entity, stand, target, 1) == SurfaceApproach.Result.BLOCKED) {
                GameplayLog.limited(entity, "social", "human_route_ended", 200,
                        "human contact blocked target={} reason={}", target.getUUID(), stand == null ? "no_dry_stand" : "route_blocked");
                stop();
            }
            return;
        }
        EcologyMovement.halt(entity);
        if (!faceContact(target)) return;
        greeted = true;
        if (humanMayAsk && canRequestFood()) {
            askForFood(wariness, relation);
            return;
        }
        boolean keeper = entity.status().identity().companion() && entity.pet().ownedBy(target.getUUID());
        if (keeper && target instanceof Player owner && entity.pet().lessons().studyWith(owner)) return;
        String line = keeper || relation.closeness() >= BondLevels.TRUST_AT_20 ? "caregiver" : "hello";
        boolean visitor = visitor(target);
        greetingBody(visitor ? "bow" : "greet");
        entity.showReaction(wariness >= .18 ? "surprise" : "happy", line, 80);
        if (visitor) entity.pet().lessons().performed(PetSkill.GREETING, true);
        GameplayLog.limited(entity, "social", "human_greeting", 200, "human greeting identity={} player={} line={} wariness={}",
                entity.status().identity().id(), target.getUUID(), line, wariness);
    }

    private boolean faceContact(Entity person) {
        float yaw = SurfaceApproach.yaw(entity.position(), person.position());
        if (Math.abs(Mth.wrapDegrees(yaw - entity.yBodyRot)) <= Face.FACE_ARRIVED) return true;
        if (!(entity.motor().current() instanceof Face)) entity.motor().run(this, new Face(yaw));
        return false;
    }

    private void askForFood(double wariness, YukkuriWorldData.Relation relation) {
        double esteem = SocialDisposition.selfEsteem(entity);
        double chance = SocialDisposition.demandProbability(personality(), esteem, wariness, relation);
        boolean demand = entity.getRandom().nextDouble() < chance;
        int sequence = entity.speechSequence();
        String line = demand ? "human_food_demand" : "human_food_ask";
        entity.showReaction(demand ? "cheek_puff" : "teary", line, 80);
        if (sequence == entity.speechSequence() || entity.subspecies().congenital() != SubspeciesLife.Congenital.NORMAL) {
            GameplayLog.limited(entity, "social", "food_request_unsaid", 200, "human food request unvoiced target={}", target.getUUID());
            stop();
            return;
        }
        lastFoodRequest = entity.life().nutrition();
        // Allow three to six seconds to respond after the actual line, with more patience at higher self-control.
        replyUntil = entity.speechEnd() + 60 + Math.round(.60 * personality().selfControl());
        GameplayLog.event(entity, "social", "human food request target={} nutrition={} esteem={} wariness={} chance={} line={} replyUntil={}",
                target.getUUID(), lastFoodRequest, esteem, wariness, chance, line, replyUntil);
    }

    public boolean canDismissFoodRequest(Player player) {
        return humanContact() && target == player && replyUntil > entity.level().getGameTime()
                && canContinue() && atHumanContact(player);
    }

    public boolean dismissFoodRequest(Player player) {
        if (!canDismissFoodRequest(player)) return false;
        var relations = YukkuriWorldData.get((ServerLevel) entity.level());
        var before = personality().relation(player.getUUID());
        var after = relations.interact(entity.getUUID(), player.getUUID(), -4, 8);
        replyUntil = 0;
        retreat = true;
        greeted = false;
        duration = elapsed + 80;
        GameplayLog.event(entity, "social", "human food dismissed target={} closeness={}->{} fear={}->{}",
                player.getUUID(), before.closeness(), after.closeness(), before.fear(), after.fear());
        return true;
    }

    private void retreatFromHuman(double wariness) {
        if (greeted) {
            if (entity.getNavigation().isDone()) stop();
            return;
        }
        if (!EcologyMovement.supported(entity)) return;
        greeted = true;
        boolean afraid = wariness >= .62;
        if (afraid) entity.mind().felt(EmotionEvent.FRIGHTENED);
        entity.showReaction(afraid ? "surprise" : "angry", afraid ? "afraid" : "", 80);
        if (visitor(target)) entity.pet().lessons().performed(PetSkill.GREETING, false);
        Vec3 away = Reactions.awayFrom(entity, 5, target.position());
        boolean moving = away != null && EcologyMovement.navigate(entity, away, 1.1);
        GameplayLog.event(entity, "social", "human retreat identity={} player={} wariness={} moving={}",
                entity.status().identity().id(), target.getUUID(), wariness, moving);
        if (!moving) stop();
    }
    /** Why this arrival is a greeting, or null: a first meeting, a return after a real separation, or the first morning contact of a kin. */
    private String greetReason(YukkuriEntity peer, YukkuriWorldData relations) {
        String reason = greetingDue(peer, relations);
        noteContact(peer);
        greetWhy = null;
        return reason;
    }
    private String greetingDue(YukkuriEntity peer, YukkuriWorldData relations) {
        long now = entity.level().getGameTime();
        Long last = lastContact.get(peer.getUUID());
        if ("morning".equals(greetWhy)) return last == null || last / 24000L != now / 24000L ? greetWhy : null;
        // Family is never a stranger: kin met with no contact remembered greet as a reunion.
        if (last == null) return Bonds.careKin(entity, peer) || relations.affinity(entity.getUUID(), peer.getUUID()) >= BondLevels.AFFINITY_ACQUAINTED
                ? "reunion" : "first_meet";
        return now - last >= REUNION_GAP ? "reunion" : null;
    }
    /** A real contact with this peer (a nuzzle, a mating): meeting it again soon after is no reunion. */
    public void noteContact(YukkuriEntity peer) {
        if (lastContact.size() >= 64 && !lastContact.containsKey(peer.getUUID())) lastContact.remove(lastContact.keySet().iterator().next());
        lastContact.put(peer.getUUID(), entity.level().getGameTime());
    }
    /** Read why a nuzzle should open with a greeting. The session records contact after reaching its stand. */
    public String greetingDue(YukkuriEntity peer) {
        return greetingDue(peer, YukkuriWorldData.get((ServerLevel) entity.level()));
    }
    public void startGreeting(YukkuriEntity peer, String why) {
        greetingBody("greet");
        entity.showReaction("happy", "hello", 80);
        logGreet(peer, why);
    }
    public void answerGreeting(YukkuriEntity peer, String clip, String why) {
        greetingBody(clip);
        entity.getLookControl().setLookAt(peer, 30, 30);
        entity.showReaction("happy", "hello", 80);
        noteContact(peer);
        GameplayLog.limited(entity, "social", "answer." + peer.getUUID(), 200,
                "greet answer from={} to={} why={} answer={}", entity.getUUID(), peer.getUUID(), why, clip);
        entity.settlement().society().welcome(peer);
    }
    private void logGreet(YukkuriEntity other, String why) {
        double gap = entity.position().subtract(other.position()).horizontalDistance();
        GameplayLog.limited(entity, "social", "greet." + other.getUUID(), 200,
                "greet from={} to={} why={} spacing={} minimum={}", entity.getUUID(), other.getUUID(), why,
                Math.round(gap * 1000) / 1000.0, Math.round(GreetingStance.visualDistance(entity, other) * 1000) / 1000.0);
    }
    private void approachPeer(String why) {
        task = Task.PEER; duration = 200; elapsed = 0; greeted = false; greetWhy = why;
        choosePeerApproach(target);
        if (!retreat && target instanceof YukkuriEntity peer) peer.social().noticeApproach(entity);
    }
    /**
     * One motor turn of the whole body toward the peer it answers: the look control only tilts the head, so a reply from a body
     * still facing away is delivered with its back to the greeter. Requested once per missing facing, at the ability's turn rate.
     */
    private boolean turnToFace(YukkuriEntity peer) {
        float yaw = SurfaceApproach.yaw(entity.position(), peer.position());
        if (Math.abs(Mth.wrapDegrees(yaw - entity.yBodyRot)) <= Face.FACE_ARRIVED) return true;
        if (entity.motor().current() instanceof Face) return false;
        GameplayLog.limited(entity, "social", "answer_face." + peer.getUUID(), ANSWER_EXPIRY,
                "answer face turn to={} yaw={} bodyYaw={}", peer.getUUID(), Math.round(yaw * 10) / 10.0,
                Math.round(entity.yBodyRot * 10) / 10.0);
        entity.motor().run(this, new Face(yaw));
        return false;
    }
    /** The greeter is still walking up to this one, not yet arrived. */
    private boolean approachedBy(Entity other) {
        return other instanceof YukkuriEntity greeter && greeter.social().task == Task.PEER && greeter.social().target == entity
                && !greeter.social().greeted && !greeter.social().retreat;
    }
    private boolean freeToAnswer(YukkuriEntity from) {
        return !entity.level().isClientSide && entity.isAlive() && !entity.isNoAi() && !entity.life().isSleeping() && !entity.isPanicking()
                && !entity.life().hasUrgentNeed() && entity.life().mealTicks() == 0 && !entity.isCommandPlaying() && !entity.operations().active()
                && !entity.groupInteraction().active() && !entity.playLife().active() && !entity.family().laboring()
                && !attachedTo(from) && entity.hasLineOfSight(from);
    }
    /**
     * A free peer that sees another walk up to it stops where it is and watches it come, instead of wandering off before
     * the greeting (project design). Its own leisure and errands win: only an idle one with no session notices. The watch is
     * the answer task before its greeting, and ends as soon as the walker arrives, turns away or gives up.
     */
    private void noticeApproach(YukkuriEntity from) {
        if (task != Task.NONE || !entity.childhood().canLearn() || !freeForSocial() || !entity.life().activity().equals(Behaviors.IDLE) || !freeToAnswer(from)) return;
        task = Task.ANSWER; target = from; elapsed = 0; greeted = false; answerScorn = false; answerWhy = null; answerDelay = 0;
        duration = ANSWER_EXPIRY; answerUntil = entity.level().getGameTime() + ANSWER_EXPIRY;
        GameplayLog.limited(entity, "social", "noticed." + from.getUUID(), 200, "approach noticed from={} distance={}",
                from.getUUID(), Math.round(entity.distanceTo(from) * 10) / 10.0);
    }
    /** A greeted peer turns to the greeter and answers about a second later, from its own social goal; hatless greeters get scorn instead. */
    public boolean receiveGreeting(YukkuriEntity from, boolean scorn, String why) {
        if (task != Task.NONE && !(task == Task.ANSWER && target == from) || !freeToAnswer(from)) return false;
        if (entity.dailyLife().active()) {
            if (scorn) Contempt.react((ServerLevel) entity.level(), entity, from);
            else answerGreeting(from, "nod", why);
            return true;
        }
        task = Task.ANSWER; target = from; elapsed = 0; greeted = true; answerScorn = scorn; answerWhy = why;
        answerDelay = ANSWER_BASE + entity.getRandom().nextInt(ANSWER_SPREAD); duration = answerDelay + ANSWER_HOLD;
        answerUntil = entity.level().getGameTime() + ANSWER_EXPIRY;
        return true;
    }
    private boolean urgent() {
        return entity.life().nutrition() < 15 || entity.life().energy() < 15 || entity.isInWaterOrBubble()&&!ownsBodyInWater()
                || entity.isOnFire() || entity.mind().nonYukkuri();
    }
    private boolean contactReady(Entity other) {
        var wait = contactWaits.get(other.getUUID());
        return !attachedTo(other) && (wait == null || entity.tickCount >= wait.until()
                || personality().lastInteraction(other.getUUID()) != wait.lastInteraction());
    }
    /** Turning toward one's rider or carrier turns the contact target along with the body. */
    private boolean attachedTo(Entity other) {
        return other.getVehicle() == entity || entity.getVehicle() == other;
    }
    private void protectContact(Entity other) {
        if (protectedContact != null) personality().releaseRelation(protectedContact);
        protectedContact = other.getUUID(); personality().protectRelation(protectedContact);
    }
    private void choosePeerApproach(Entity other) {
        protectContact(other);
        var fear = personality().fearBand(other.getUUID());
        int trust = personality().trust(other.getUUID());
        cautious = trust > 0 && fear != YukkuriPersonality.FearBand.NORMAL;
        retreat = trust <= 0 && fear == YukkuriPersonality.FearBand.AFRAID;
        observedDistance = entity.distanceTo(other);
    }
    private boolean visitor(Entity other) {
        return (other instanceof Player || other instanceof HumanNpc) && entity.pet().isPet() && !entity.pet().ownedBy(other.getUUID());
    }
    public void stop() {
        entity.motor().cancel(this,"social_stop");
        if(task==Task.PLAYER||task==Task.PEER)GameplayLog.limited(entity,"social","contact.ended",200,
                "social contact ended target={} greeted={} retreat={} elapsed={}/{}",
                target==null?null:target.getUUID(),greeted,retreat,elapsed,duration);
        if(task==Task.TOY)GameplayLog.limited(entity,"social","toy.stopped",1200,
                "toy session stopped toy={} elapsed={} contactTicks={} touches={}",
                target==null?null:target.getUUID(),elapsed,toyContact,toyTouches);
        if(task==Task.TOY&&target instanceof ItemEntity toy){var lease=toy.getPersistentData();if(lease.hasUUID("YukkuriPlayOwner")&&
                lease.getUUID("YukkuriPlayOwner").equals(entity.getUUID())){lease.remove("YukkuriPlayOwner");lease.remove("YukkuriPlayUntil");}}
        if (task == Task.ACCESSORY) accessoryRetry = entity.tickCount + 200;
        if (task != Task.NONE) entity.life().deferSocial(600);
        if (target != null && (task == Task.PLAYER || task == Task.PEER)) {
            contactWaits.entrySet().removeIf(e -> e.getValue().until() <= entity.tickCount);
            if (contactWaits.size() >= 64) contactWaits.remove(contactWaits.keySet().iterator().next());
            contactWaits.put(target.getUUID(), new ContactWait(entity.tickCount + 600, personality().lastInteraction(target.getUUID())));
        }
        if (protectedContact != null) { personality().releaseRelation(protectedContact); protectedContact = null; }
        task = Task.NONE; target = null; exercise = null; elapsed = 0; greeted = false; retreat = false; cautious = false; greetWhy = null;
        humanWatchUntil = 0; replyUntil = 0;
        entity.getNavigation().stop();
    }
}
