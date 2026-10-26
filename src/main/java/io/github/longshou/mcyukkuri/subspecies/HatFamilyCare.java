package io.github.longshou.mcyukkuri.subspecies;

import io.github.longshou.mcyukkuri.subspecies.boat.HullHelm.Purpose;
import io.github.longshou.mcyukkuri.status.GameplayLog;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.CareDecisionLedger;
import io.github.longshou.mcyukkuri.family.ChildhoodLife;
import io.github.longshou.mcyukkuri.family.FamilyFeedingContact;
import io.github.longshou.mcyukkuri.life.InfantFood;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.life.ToiletContact;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.particles.ParticleTypes;

/** A parent stops rowing to tend real children. Interrupted servings return to cargo or the world. */
public final class HatFamilyCare {
    private enum Kind { NONE, FEED, FIRST_TOILET, COMFORT, NUZZLE, LULLABY }
    private final YukkuriEntity parent;
    private Kind kind = Kind.NONE;
    private UUID child;
    private ItemStack serving = ItemStack.EMPTY;
    private int ticks, deliveryTicks, mealWait, affectionWait, songWait, meals, comforts, songs;
    private int finishTicks, toiletTicks;
    /** A physical failure is route-local; the caregiver's decision remains in the parent ledger. */
    private final Map<UUID,Long> retryUntil=new HashMap<>();
    private CareDecisionLedger.Need decisionNeed;
    private UUID decisionChild;
    private boolean bedtimeSung;
    private UUID grateful;
    private long gratefulUntil;

    public HatFamilyCare(YukkuriEntity parent) { this.parent = parent; }
    public ItemStack serving() { return serving; }
    public int meals() { return meals; }
    public int comforts() { return comforts; }
    public int songs() { return songs; }
    public boolean active() { return kind != Kind.NONE; }
    /** A rescued child is nuzzled once the parent can attend; still mouth contact only, no cleaning or healing. */
    public void expectNuzzle(YukkuriEntity young) { grateful = young.getUUID(); gratefulUntil = level().getGameTime() + 200; }
    private ServerLevel level() { return (ServerLevel) parent.level(); }
    public int childId() { var y = child(); return y == null ? -1 : y.getId(); }
    /** Client-sync source: uninterrupted real contact in the active feed or first-toilet care. */
    public int contactProgress() { return kind == Kind.FEED || kind == Kind.FIRST_TOILET ? deliveryTicks : 0; }
    private YukkuriEntity child() {
        return child != null && level().getEntity(child) instanceof YukkuriEntity y ? y : null;
    }
    public String activity() {
        if(parent.getVehicle() instanceof HatBoatEntity boat&&boat.carryingInHat(parent))return Behaviors.BOAT_CARRY_YOUNG;
        if(parent.getVehicle() instanceof HatBoatEntity boat&&boat.walkingInHat(parent))return Behaviors.HAT_WALK;
        if(finishTicks>0)return Behaviors.CARE_FINISH;
        return switch (kind) { case FEED -> {
            var young=child();yield young!=null&&young.family().needsMash()&&ticks<40?Behaviors.FAMILY_CHEW:Behaviors.BOAT_FEED_YOUNG;
        } case FIRST_TOILET -> deliveryTicks >= ChildhoodLife.FIRST_TOILET_CONTACT_TICKS ? Behaviors.CARE_FINISH : deliveryTicks > 0 ? Behaviors.FIRST_TOILET_ASSIST : Behaviors.FIRST_TOILET_SEEK;
            case COMFORT, NUZZLE -> Behaviors.BOAT_NUZZLE;
            case LULLABY -> Behaviors.BOAT_LULLABY; default -> Behaviors.IDLE; };
    }
    public String response(YukkuriEntity young) {
        if(young.getVehicle() instanceof HatBoatEntity boat&&boat.carryingInHat(young))return Behaviors.BOAT_BE_CARRIED;
        if(young.getVehicle() instanceof HatBoatEntity boat&&boat.walkingInHat(young)&&!boat.practisingMove(young))return Behaviors.HAT_WALK;
        if (kind == Kind.LULLABY && parent.getVehicle() == young.getVehicle()) return Behaviors.BOAT_LISTEN;
        if (!young.getUUID().equals(child)) return Behaviors.IDLE;
        if (finishTicks > 0) return kind == Kind.FEED && young.life().mealTicks() > 0 ? Behaviors.BOAT_EAT : Behaviors.BOAT_RECEIVE_CARE;
        return switch (kind) {
            case FEED -> Behaviors.BOAT_BEG;
            case FIRST_TOILET -> toiletTicks > 0 ? Behaviors.BOAT_TOILET : deliveryTicks > 0 &&
                    deliveryTicks < ChildhoodLife.FIRST_TOILET_CONTACT_TICKS ? Behaviors.FIRST_TOILET_STIMULATED : Behaviors.FIRST_TOILET_WAIT;
            case COMFORT, NUZZLE -> Behaviors.BOAT_RECEIVE_CARE;
            default -> Behaviors.IDLE;
        };
    }
    public void clock() {
        if (mealWait > 0) mealWait--;
        if (affectionWait > 0) affectionWait--;
        if (songWait > 0) songWait--;
        if (!level().isNight()) bedtimeSung = false;
        retryUntil.entrySet().removeIf(entry -> entry.getValue() <= level().getGameTime());
        if (active() && (!(parent.getVehicle() instanceof HatBoatEntity boat) || !availableForContinuation(boat))) cancel();
        // A saved serving is recovered once; a partially completed interaction never grants its rewards on load.
        if (!active() && !serving.isEmpty()) refund();
    }
    private List<YukkuriEntity> children(HatBoatEntity boat) {
        return boat.getPassengers().stream().filter(p -> p instanceof YukkuriEntity).map(p -> (YukkuriEntity)p)
                .filter(y -> boat.careRecipient(y) && y.isAlive() && !y.isNoAi() && !y.isCommandPlaying()
                        && !y.operations().active() && !y.isPanicking() && boat.protects(y)
                        && parent.life().canRecognize(y) && y.life().canRecognize(parent)
                        && parent.hasLineOfSight(y)).toList();
    }
    private boolean availableContext(HatBoatEntity boat) {
        return parent.isAlive() && !parent.isNoAi() && !parent.isCommandPlaying() && !parent.operations().active()
                && parent.getVehicle() == boat && parent.getUUID().equals(boat.ownerYukkuri()) && boat.protects(parent)
                && boat.isAfloat() && boat.flooding() == 0 && !boat.hasIncoming()
                && !parent.isPanicking() && !parent.family().laboring() && !parent.life().isSleeping()
                && parent.life().mealTicks() == 0;
    }
    private boolean availableForStart(HatBoatEntity boat) {
        return availableContext(boat)&&!parent.life().hasUrgentNeed()&&parent.life().nutrition()>=40&&parent.life().energy()>=35;
    }
    private boolean availableForContinuation(HatBoatEntity boat) {
        var life=parent.life();
        return availableContext(boat)&&life.nutrition()>=15&&life.energy()>=15
                &&(life.bladder()<90||life.constipated())&&life.wetness()<=40;
    }
    private boolean retryBlocked(YukkuriEntity young) {
        return retryUntil.getOrDefault(young.getUUID(),0L)>level().getGameTime();
    }
    /** A family-care need reuses the parent's recorded result; this route never rolls by itself. */
    private boolean acceptsCare(YukkuriEntity young,CareDecisionLedger.Need need,boolean urgent) {
        return parent.family().careDecisions().decide(young,need,urgent).accepted();
    }
    /** Feeding has priority over affection. Bedtime singing begins only after reaching quiet water. */
    public boolean tend(HatBoatEntity boat, boolean bedtime) {
        if ((!active()&&!availableForStart(boat))||(active()&&!availableForContinuation(boat))) { cancel(); return false; }
        if (boat.transportingInHat(parent)) return active();
        var children = children(boat);
        if (!active()) {
            var hungry = children.stream().filter(y -> y.life().nutrition() < 60 && y.life().canReceiveCareFood() && !y.life().isSleeping()&&
                    !retryBlocked(y)&&!parent.family().careDecisions().refused(y,CareDecisionLedger.Need.FEED))
                    .min(Comparator.comparingDouble(y -> y.life().nutrition())).orElse(null);
            var toilet = children.stream().filter(y -> boat.young(y) && y.childhood().needsAssistedFirstToilet()&&y.life().nutrition()>=30
                    &&!retryBlocked(y)&&!parent.family().careDecisions().refused(y,CareDecisionLedger.Need.FIRST_TOILET)).max(Comparator.comparingDouble(y->y.life().bladder())).orElse(null);
            if (toilet != null && (hungry==null||hungry.life().nutrition()>=30) && acceptsCare(toilet,CareDecisionLedger.Need.FIRST_TOILET,toilet.life().bladder()>=70)) begin(Kind.FIRST_TOILET, toilet, CareDecisionLedger.Need.FIRST_TOILET);
            if (!active() && hungry != null && (mealWait == 0 || hungry.life().nutrition() < 25)) {
                serving = boat.cargo().takeCareMeal(parent,hungry);
                if(!serving.isEmpty())GameplayLog.event(parent,"aquatic","hat care portion reserved hat={} child={} item={} cargoRemaining={}",
                        boat.getUUID(),hungry.getUUID(),serving,boat.cargo().count());
                if (!serving.isEmpty()&&acceptsCare(hungry,CareDecisionLedger.Need.FEED,hungry.life().nutrition()<30)) begin(Kind.FEED, hungry, CareDecisionLedger.Need.FEED);
                else if(!serving.isEmpty()) refund();
            }
            if (!active()) {
                var hurt = children.stream().filter(boat::young).filter(y -> parent.woundComfort().whyNotAfloat(y, boat) == null)
                        .min(Comparator.comparingDouble(y -> y.body().healthRatio())).orElse(null);
                if (hurt != null && parent.woundComfort().beginAfloat(hurt, boat)) begin(Kind.COMFORT, hurt);
            }
            if (grateful != null && level().getGameTime() > gratefulUntil) grateful = null;
            if (!active() && grateful != null) {
                var rescued = children.stream().filter(boat::young).filter(y -> y.getUUID().equals(grateful) && y.life().mealTicks() == 0 && !y.life().isSleeping()).findFirst().orElse(null);
                if (rescued != null) { grateful = null; if (acceptsCare(rescued,CareDecisionLedger.Need.NUZZLE,true)) begin(Kind.NUZZLE, rescued, CareDecisionLedger.Need.NUZZLE); }
            }
            var familyChildren=children.stream().filter(boat::young).toList();
            if (!active() && bedtime && !bedtimeSung && songWait == 0 && !familyChildren.isEmpty()
                    && familyChildren.stream().anyMatch(y -> !y.life().isSleeping())
                    && familyChildren.stream().noneMatch(y -> y.life().nutrition() < 30 || y.life().mealTicks() > 0)) {
                var listener = familyChildren.stream().filter(y -> !y.life().isSleeping()).findFirst().orElse(null);
                if (listener != null && acceptsCare(listener,CareDecisionLedger.Need.LULLABY,false)) begin(Kind.LULLABY, null, listener, CareDecisionLedger.Need.LULLABY);
                else songWait = 1200;
            }
            if (!active() && !bedtime && !level().isNight() && affectionWait == 0 && parent.life().nutrition() >= 35 && parent.life().energy() >= 25) {
                var lonely = children.stream().filter(boat::young).filter(y -> y.life().mealTicks() == 0 && !y.life().isSleeping()
                        && y.life().nutrition() >= 35 && y.dailyLife().affectionNeed() >= 35 && parent.distanceToSqr(y) <= 4&&!retryBlocked(y))
                        .max(Comparator.comparingInt(y -> y.dailyLife().affectionNeed())).orElse(null);
                if (lonely != null) {
                    affectionWait = 600;
                    if (parent.life().personality().fear(lonely.getUUID()) < 60 && lonely.life().personality().fear(parent.getUUID()) < 60
                            && parent.getRandom().nextFloat() < parent.life().personality().companionshipProbability()
                            && lonely.getRandom().nextFloat() < lonely.life().personality().companionshipProbability()) begin(Kind.NUZZLE, lonely);
                }
            }
            if (!active()) return false;
        }
        var young = child();
        if (kind != Kind.LULLABY && (young == null || !children.contains(young) || parent.distanceToSqr(young) > 4
                || young.life().isSleeping() || kind == Kind.NUZZLE && young.life().mealTicks() > 0)) {
            cancel(); return false;
        }
        if (kind == Kind.LULLABY && (children.isEmpty() || children.stream().anyMatch(y -> y.life().nutrition() < 15))) {
            cancel(); return false;
        }
        boat.helm().drift(parent, Purpose.CARE); ticks++;
        if(kind!=Kind.FEED&&children.stream().anyMatch(y->y.life().nutrition()<15&&y.life().canReceiveCareFood())){cancel();return false;}
        if (finishTicks > 0) {
            finishTicks++;
            // A feeding pair stays in contact for the child's actual swallowing, including slower meals.
            if (kind == Kind.FEED && young != null && young.life().mealTicks() > 0) {
                if (finishTicks >= 160) fail("swallow_timeout");
                return true;
            }
            if (finishTicks >= 24) cancel();
            return true;
        }
        if (young != null) {
            parent.getLookControl().setLookAt(young, 20, 20);
            // Rear care and the subsequent rim release set the child's orientation deliberately:
            // a generic look-at here would turn its back away from the parent or its rear back over the hat.
            if (kind != Kind.FIRST_TOILET) young.getLookControl().setLookAt(parent, 20, 20);
        }
        if (kind == Kind.FEED) {
            if (young.family().needsMash() && !InfantFood.isMash(serving)) {
                if (ticks < 40) return true;
                serving=InfantFood.mash(serving,parent.getUUID());
            }
            if (!boat.prepareCareSeat(young, parent)) {
                fail("feeding_seat_refused"); return false;
            }
            boolean prepared=!young.family().needsMash()||InfantFood.isMash(serving);
            if (prepared && boat.careSeatReady(young)) FamilyFeedingContact.faceEachOther(parent, young);
            if (prepared && boat.careSeatReady(young) && FamilyFeedingContact.mouthsMeet(parent,young)) deliveryTicks++; else deliveryTicks=0;
            if (deliveryTicks >= FamilyFeedingContact.REQUIRED_CONTACT_TICKS) {
                boolean served=young.family().needsMash() ? young.life().beginBraidMeal(parent,serving) : young.life().beginMeal(serving,parent.getUUID(),true);
                if (!served) {
                    GameplayLog.limited(parent,"aquatic","care.meal_refused",200,"hat care meal refused child={} item={}",young.getUUID(),serving);
                    cancel(); return false;
                }
                GameplayLog.event(parent,"aquatic","hat care handed to meal child={} item={} mealTicks={} nutrition={}",
                        young.getUUID(),serving,young.life().mealTicks(),young.life().nutrition());
                completeDecision();
                serving = ItemStack.EMPTY; meals++; mealWait = 80;
                parent.speak("share");
                finishTicks=1; return true;
            }
        }
        if (kind == Kind.FIRST_TOILET) {
            if (!boat.prepareRearCareSeat(young, parent)) {
                fail("rear_care_seat_refused"); return false;
            }
            if (deliveryTicks < ChildhoodLife.FIRST_TOILET_CONTACT_TICKS) {
                if (boat.careSeatReady(young)) faceRearCare(parent, young);
                if (boat.careSeatReady(young) && ChildhoodLife.toiletContact(parent, young)) deliveryTicks++; else deliveryTicks=0;
                if (ticks >= 360) { fail("rear_contact_timeout"); return false; }
                return true;
            }
            if (!boat.prepareToiletSeat(young, parent)) { fail("toilet_seat_refused"); return false; }
            if (!boat.toiletSeatReady(young)) { toiletTicks = 0; if (ticks >= 640) fail("toilet_seat_timeout"); return active(); }
            boat.faceToiletOutward(young);
            parent.getLookControl().setLookAt(young,20,20);
            if (ToiletContact.hatOutlet(young, boat) == null) { toiletTicks = 0; if (ticks >= 640) fail("toilet_outlet_timeout"); return active(); }
            if (++toiletTicks < ToiletContact.RELEASE_TICKS) return true;
            if (!young.childhood().completeAssistedFirstToilet(parent)) { cancel(); return false; }
            completeDecision();
            GameplayLog.event(parent,"aquatic","hat care first toilet complete child={} bladder={}",young.getUUID(),young.life().bladder());
            parent.speak("caregiver");
            finishTicks=1; return true;
        }
        if (kind == Kind.COMFORT) {
            boolean working = parent.woundComfort().tickAfloat(boat);
            deliveryTicks = parent.woundComfort().contactProgress() > 0 ? Math.round(parent.woundComfort().contactProgress() * 20) : 0;
            if (!working) {
                if (young.woundComfort().soothed()) {
                    comforts++; affectionWait = 600;
                    GameplayLog.event(parent,"aquatic","hat care wound comfort complete child={} comforts={}",young.getUUID(),comforts);
                }
                finishTicks = 1;
            }
            return true;
        }
        if(kind==Kind.NUZZLE){
            if(!boat.prepareCareSeat(young,parent)){
                fail("nuzzle_seat_refused");return false;
            }
            if(boat.careSeatReady(young))FamilyFeedingContact.faceEachOther(parent,young);
            if(boat.careSeatReady(young)&&FamilyFeedingContact.mouthsMeet(parent,young))deliveryTicks++;else deliveryTicks=0;
        }
        if (kind == Kind.NUZZLE && deliveryTicks == 48) {
            parent.dailyLife().comfort(15); young.dailyLife().comfort(35); comforts++; affectionWait = 600;
            young.life().personality().relationshipChanged(parent.getUUID(), 3, -2);
            parent.life().personality().relationshipChanged(young.getUUID(), 3, -2);
            completeDecision();
            finishTicks=1;
            GameplayLog.event(parent,"aquatic","hat care nuzzle complete child={} comforts={}",young.getUUID(),comforts);
        }
        if (kind == Kind.LULLABY) {
            if (ticks % 20 == 1) level().sendParticles(ParticleTypes.NOTE,
                    parent.getX(), parent.getEyeY()+.2, parent.getZ(), 1, .1, .03, .1, 0);
            if (ticks == 80) {
                for (var listener : children) { listener.dailyLife().comfort(10); listener.dailyLife().entertained(20); }
                parent.dailyLife().entertained(15); songs++; songWait = 1200; bedtimeSung = true;
                completeDecision();
                GameplayLog.event(parent,"aquatic","hat care lullaby complete listeners={} songs={}",children.size(),songs);
            }
        }
        if (ticks >= (kind == Kind.LULLABY ? 80 : kind == Kind.FIRST_TOILET ? 640 : 480)) {
            if (kind == Kind.LULLABY) cancel(); else fail("care_contact_timeout");
        }
        return true;
    }
    /** Proof of this live care transaction; a prepared berth alone never authorizes a first bowel release. */
    public boolean firstToiletReleaseReady(YukkuriEntity young) {
        return kind == Kind.FIRST_TOILET && young.getUUID().equals(child)
                && deliveryTicks >= ChildhoodLife.FIRST_TOILET_CONTACT_TICKS
                && toiletTicks >= ToiletContact.RELEASE_TICKS;
    }

    private void begin(Kind next, YukkuriEntity young) { begin(next, young, null, null); }
    private void begin(Kind next, YukkuriEntity young, CareDecisionLedger.Need need) { begin(next, young, young, need); }
    private void begin(Kind next, YukkuriEntity young, YukkuriEntity decidingFor, CareDecisionLedger.Need need) {
        kind = next; child = young == null ? null : young.getUUID(); ticks = deliveryTicks = finishTicks = toiletTicks = 0;
        decisionChild = decidingFor == null ? null : decidingFor.getUUID(); decisionNeed = need;
        parent.life().wakeInHat();
        if (young != null) young.life().wakeInHat();
        if (next == Kind.LULLABY && parent.getVehicle() instanceof HatBoatEntity boat)
            for (var listener : children(boat)) if (!listener.life().isSleeping()) listener.life().wakeInHat();
        parent.setPersistenceRequired();
        parent.speak(next == Kind.LULLABY ? "song" : next == Kind.NUZZLE || next == Kind.COMFORT ? "nuzzle" : "caregiver");
        GameplayLog.limited(parent,"aquatic","care.begin."+next,200,"hat care started kind={} child={} item={}",kind,child,serving);
    }
    private void completeDecision() {
        if (decisionNeed != null && decisionChild != null) parent.family().careDecisions().complete(decisionChild, decisionNeed);
        decisionChild = null; decisionNeed = null;
    }
    public void cancel() {
        if(active())GameplayLog.limited(parent,"aquatic","care.stop."+kind,200,
                "hat care stopped kind={} child={} ticks={} contactTicks={} finishTicks={} pendingItem={}",
                kind,child,ticks,deliveryTicks,finishTicks,serving);
        if (kind == Kind.COMFORT && parent.woundComfort().licking()) parent.woundComfort().stop();
        if (child != null && parent.getVehicle() instanceof HatBoatEntity boat) boat.releaseCareSeat(child);
        kind = Kind.NONE; child = decisionChild = null; decisionNeed = null; ticks = deliveryTicks = finishTicks = toiletTicks = 0; refund();
    }
    private void fail(String reason){
        GameplayLog.limited(parent,"aquatic","care.failed."+reason,200,
                "hat care failed kind={} child={} reason={} ticks={} contactTicks={} finishTicks={} toiletTicks={} retryTicks=100",
                kind,child,reason,ticks,deliveryTicks,finishTicks,toiletTicks);
        if(child!=null)retryUntil.put(child,level().getGameTime()+100);cancel();
    }
    private void refund() {
        if (serving.isEmpty()) return;
        var returned = serving; serving = ItemStack.EMPTY;
        int reserved=returned.getCount();
        if (parent.getVehicle() instanceof HatBoatEntity boat && boat.protects(parent)) boat.cargo().store(returned, returned.getCount(), false);
        int remaining=returned.getCount();
        var drop=!returned.isEmpty()?parent.spawnAtLocation(returned):null;
        GameplayLog.event(parent,"aquatic","hat care portion refund result cargoReturned={} dropAttemptCount={} drop={}",
                reserved-remaining,remaining,drop==null?null:drop.getUUID());
    }
    public CompoundTag save() {
        var tag = new CompoundTag();
        tag.putInt("MealWait", mealWait); tag.putInt("AffectionWait", affectionWait); tag.putInt("SongWait", songWait);
        tag.putInt("Meals", meals); tag.putInt("Comforts", comforts); tag.putInt("Songs", songs); tag.putBoolean("BedtimeSung", bedtimeSung);
        if (!serving.isEmpty()) tag.put("Serving", serving.save(parent.registryAccess()));
        return tag;
    }
    public void load(CompoundTag tag) {
        if (child != null && parent.getVehicle() instanceof HatBoatEntity boat) boat.releaseCareSeat(child);
        retryUntil.clear();
        grateful = null;
        kind = Kind.NONE; child = decisionChild = null; decisionNeed = null; ticks = deliveryTicks = finishTicks = toiletTicks = 0;
        mealWait = Math.clamp(tag.getInt("MealWait"), 0, 80); affectionWait = Math.clamp(tag.getInt("AffectionWait"), 0, 600);
        songWait = Math.clamp(tag.getInt("SongWait"), 0, 1200); bedtimeSung = tag.getBoolean("BedtimeSung");
        meals = Math.max(0, tag.getInt("Meals")); comforts = Math.max(0, tag.getInt("Comforts")); songs = Math.max(0, tag.getInt("Songs"));
        serving = ItemStack.parseOptional(parent.registryAccess(), tag.getCompound("Serving"));
        if (!serving.isEmpty()) serving.setCount(1);
    }

    /** Child faces away from the captain so the parent is physically behind it for first-toilet care. */
    private static void faceRearCare(YukkuriEntity parent, YukkuriEntity child) {
        var delta = child.position().subtract(parent.position());
        float yaw = (float)(Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90);
        SurfaceApproach.face(parent,yaw);
        SurfaceApproach.face(child,yaw);
    }
}
