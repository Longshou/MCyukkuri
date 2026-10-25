package io.github.longshou.mcyukkuri.subspecies;

import io.github.longshou.mcyukkuri.subspecies.crew.CrewTransfers;
import io.github.longshou.mcyukkuri.subspecies.boat.HullHelm.Purpose;
import io.github.longshou.mcyukkuri.subspecies.boat.HullFactory;
import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.family.FamilyLife;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.mind.EmotionEvent;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import io.github.longshou.mcyukkuri.event.WildEvents;
import java.util.ArrayList;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Learning to handle one's hat is separate from leaving the family at subadulthood. */
public final class FirstVoyage {
    /** Project balance within the existing .20-.80 juvenile span, not a Wiki age or size. */
    public static final float MIN_SCALE=.20f;
    private enum Phase { NONE, APPROACH, LAUNCH, BOARD, BALANCE, OUT, RETURN, REBOARD, CELEBRATE }
    private final YukkuriEntity child;
    private Phase phase=Phase.NONE;
    private UUID mentor, pupil, homeHat;
    private BlockPos harbor, turn;
    private int ticks, elapsed, cooldown, loadGrace, hatRecoveryTicks, returnTicks, contactTicks;
    private long nextSearch;
    private boolean completed, reachedTurn, independent, leavingHome;
    private boolean routineLaunch, routineSailing, emergencyRecall;
    /** The worn hat keeps its real cargo and identity while its owner shelters aboard a parent. */
    public FirstVoyage(YukkuriEntity entity){child=entity;}
    private ServerLevel level(){return (ServerLevel)child.level();}
    private YukkuriEntity person(UUID id){return id!=null&&level().getEntity(id) instanceof YukkuriEntity y?y:null;}
    private HatBoatEntity home(){
        return homeHat!=null&&level().getEntity(homeHat) instanceof HatBoatEntity boat&&boat.isAfloat()
                &&mentor!=null&&mentor.equals(boat.ownerYukkuri())?boat:null;
    }
    public boolean completed(){return completed;}
    public boolean independent(){return independent;}
    public boolean active(){return phase!=Phase.NONE||pupil!=null;}
    public boolean readySize(){return child.profile().aquatic()&&child.family().stage()!=FamilyLife.Stage.BABY&&child.family().scale()>=MIN_SCALE&&
            child.childhood().canEatSolid()&&child.childhood().canJump()&&child.childhood().canLearn();}
    /** A temporary refuge is rescue authority only. It never promotes a non-parent into a first-voyage tutor. */
    public boolean needsLesson(){return !child.family().adult()&&!child.family().subadult()&&!completed&&!independent
            &&(child.family().mother()!=null||child.family().father()!=null)&&!parentsKnownDead();}
    /** Missing lineage remains uncertain; only two recorded dead parents close the parent-taught lesson route. */
    private boolean parentsKnownDead() {
        if (!(child.level() instanceof ServerLevel server)) return false;
        var data = FamilyData.get(server);
        var record = data.person(child.getUUID());
        if (record == null) return false;
        boolean parent = false;
        for (var id : new UUID[] {record.mother, record.father}) if (id != null) {
            parent = true;
            var known = data.person(id);
            if (known == null || known.status != FamilyData.Status.DEAD) return false;
        }
        return parent;
    }
    public boolean mayLaunch(){return !(child.getVehicle() instanceof HatBoatEntity hat&&hat.unsteadyMotion())
            &&(child.family().adult()||readySize()&&(phase==Phase.LAUNCH||completed));}
    public boolean launchingFrom(HatBoatEntity boat){return !boat.unsteadyMotion()&&phase==Phase.LAUNCH&&boat.getUUID().equals(homeHat);}
    public boolean keepsBerth(HatBoatEntity boat){return phase!=Phase.NONE&&boat.getUUID().equals(homeHat);}
    public boolean routineSailing(){return routineSailing&&phase==Phase.NONE&&!independent;}
    public boolean returningToParent(){return !leavingHome&&(phase==Phase.RETURN||phase==Phase.REBOARD);}
    /** The token and provisions of this hat's last voyage ride in its custody record while the hat is worn. */
    public UUID retainedHatToken(){return child.life().custody().retainedToken();}
    public int retainedFoodCount() {return child.life().custody().retainedFoodCount();}
    public boolean hasRetainedPaddle(){
        var retainedCargo=child.life().custody().retainedCargo();
        if(retainedCargo==null)return false;
        if(retainedCargo.contains("PaddleItem",Tag.TAG_COMPOUND))
            return ItemStack.parseOptional(child.registryAccess(),retainedCargo.getCompound("PaddleItem")).is(Items.STICK);
        return retainedCargo.getBoolean("Paddle");
    }
    /** True when the hat can take provisions bound to this token: nothing else is held, or it is the same hat. */
    boolean canRetainHat(UUID token){return child.life().custody().canRetain(token);}
    void retainHat(UUID token,CompoundTag cargo){child.life().custody().retain(token,cargo);}
    public void restoreHatCargo(HatBoatEntity boat){
        var retainedCargo=child.life().custody().releaseCargo();
        if(retainedCargo!=null){boat.cargo().load(retainedCargo);boat.syncCargo(boat.cargo().save());}
    }
    /** A worn/storage hat cannot hide provisions from death, landing or an external accessory removal. */
    public void dropStoredCargo(){
        if(child.level().isClientSide)return;
        var stored=child.life().custody().dropCargo();
        if(stored==null)return;
        for(var raw:stored.getList("Food",Tag.TAG_COMPOUND)){
            var stack=ItemStack.parseOptional(child.registryAccess(),(CompoundTag)raw);
            if(!stack.isEmpty())child.spawnAtLocation(stack);
        }
        if(stored.contains("PaddleItem",Tag.TAG_COMPOUND)){
            var paddle=ItemStack.parseOptional(child.registryAccess(),stored.getCompound("PaddleItem"));
            if(paddle.is(Items.STICK))child.spawnAtLocation(paddle.copyWithCount(1));
        }else if(stored.getBoolean("Paddle"))child.spawnAtLocation(new ItemStack(Items.STICK));
    }
    public String status(){return independent?"independent":leavingHome?"leaving":returningToParent()?"returning_family":routineSailing()?"family_sailing"
            :routineLaunch?"family_launch":phase!=Phase.NONE?"practising":completed?"complete":child.family().adult()?"adult":parentsKnownDead()?"orphaned":readySize()?"ready":"growing";}
    public String activity(){
        if(pupil!=null){var y=person(pupil);return y!=null&&y.aquaticLife().firstVoyage().phase==Phase.CELEBRATE?Behaviors.VOYAGE_PRAISE:Behaviors.VOYAGE_WATCH;}
        return switch(phase){
            case APPROACH->Behaviors.VOYAGE_PREPARE;case LAUNCH->Behaviors.VOYAGE_LAUNCH;case BOARD,REBOARD->Behaviors.VOYAGE_BOARD;
            case BALANCE->Behaviors.VOYAGE_BALANCE;case OUT->Behaviors.VOYAGE_OUT;case RETURN->Behaviors.VOYAGE_RETURN;
            case CELEBRATE->Behaviors.VOYAGE_PROUD;default->Behaviors.IDLE;};
    }
    private static boolean healthy(YukkuriEntity y){
        return y!=null&&y.isAlive()&&!y.isNoAi()&&!y.isCommandPlaying()&&!y.operations().active()&&!y.isPanicking()
                &&!y.isInWaterOrBubble()&&!y.isOnFire()&&!y.life().isSleeping()&&y.life().mealTicks()==0
                &&!y.life().hasUrgentNeed()&&y.life().nutrition()>=35&&y.life().energy()>=30&&y.life().bladder()<70&&!y.family().laboring();
    }
    private boolean safeHome(HatBoatEntity boat,YukkuriEntity father){
        return boat!=null&&father!=null&&!boat.unsteadyMotion()&&boat.protects(father)&&boat.flooding()==0;
    }
    /** A short player-induced rock pauses the same trip and reservations, including its timeout. */
    private boolean pauseForMotion(boolean hold){
        var riding=child.getVehicle() instanceof HatBoatEntity hat?hat:null;
        var own=HatBoatEntity.owned(child);
        var home=homeHat!=null&&level().getEntity(homeHat) instanceof HatBoatEntity hat?hat:null;
        var learner=person(pupil);var learnerBoat=learner==null?null:HatBoatEntity.owned(learner);
        boolean moving=riding!=null&&riding.unsteadyMotion()||own!=null&&own.unsteadyMotion()
                ||home!=null&&home.unsteadyMotion()||learnerBoat!=null&&learnerBoat.unsteadyMotion();
        if(!moving)return false;
        if(hold){
            if(riding!=null)riding.helm().hold(child, Purpose.CARE);if(own!=null&&(own.isVehicle()||own.hasIncoming()))own.helm().hold(child, Purpose.CARE);
            if(home!=null)home.helm().hold(child, Purpose.CARE);if(learnerBoat!=null&&(learnerBoat.isVehicle()||learnerBoat.hasIncoming()))learnerBoat.helm().hold(child, Purpose.CARE);
        }
        return true;
    }
    /** A ready pupil already rides a parent; an occupied launch berth or another lesson makes it wait its turn. */
    public boolean tryStart(){
        return start(false);
    }
    /** A competent small juvenile can take its own hat out again while it still depends on its parents. */
    public boolean tryRoutineLaunch(){return completed&&!independent&&start(true);}
    private boolean start(boolean routine){
        if(active())return true;
        if(child.aquaticLife().familyLife().alarm().preventsOuting())return false;
        boolean departure = !routine && completed && !independent && child.childhood().canLiveIndependently();
        if(departure&&child.getVehicle() instanceof HatBoatEntity own&&child.getUUID().equals(own.ownerYukkuri()))
            return departAfloat(own);
        if((!needsLesson()&&!departure&&!routine)||!readySize()||cooldown>0||!healthy(child)||!child.life().hasAccessory()
                ||child.life().custody().custodied()||!child.aquaticLife().hasStick()&&!hasRetainedPaddle()||level().isNight()
                ||!child.life().activity().equals(Behaviors.IDLE)||child.dailyLife().active()
                ||!(child.getVehicle() instanceof HatBoatEntity boat)
                ||!(boat.ownerYukkuri().equals(child.family().father())||boat.ownerYukkuri().equals(child.family().mother()))||boat.hasIncoming())return false;
        long now=level().getGameTime();if(now<nextSearch)return false;nextSearch=now+80;
        var father=person(boat.ownerYukkuri());
        if(!safeHome(boat,father)||!healthy(father)||father.aquaticLife().familyLife().alarm().preventsOuting()||father.aquaticLife().firstVoyage().active()
                ||father.aquaticLife().care().active()||!father.life().activity().equals(Behaviors.IDLE)||father.dailyLife().active()
                ||!father.life().canRecognize(child)||!child.life().canRecognize(father)
                ||!HatBoatRoute.quietSurface(level(),BlockPos.containing(boat.getX(),boat.getY()-.12,boat.getZ()))
                ||boat.getPassengers().stream().anyMatch(p->p instanceof YukkuriEntity y&&!healthy(y))
                ||WildEvents.reserved(child)||WildEvents.reserved(father)
                ||!(!departure&&boat.onBrim(child)?boat.keepVoyageBerth(child):boat.keepDepartureBerth(child)))return false;
        mentor=father.getUUID();homeHat=boat.getUUID();father.aquaticLife().firstVoyage().pupil=child.getUUID();
        child.setPersistenceRequired();father.setPersistenceRequired();elapsed=0;reachedTurn=false;leavingHome=departure;
        routineLaunch=routine;routineSailing=false;emergencyRecall=false;
        boat.helm().hold(child, Purpose.CARE);set(Phase.APPROACH);child.speak("hello");return true;
    }
    /** A child already living in its own hat leaves in that vessel, without first packing it into a parent's hat. */
    private boolean departAfloat(HatBoatEntity boat){
        if(cooldown>0||!readySize()||!healthy(child)||boat.unsteadyMotion()||!boat.protects(child)||boat.flooding()>0
                ||!boat.cargo().paddle()||level().isNight()||!child.life().activity().equals(Behaviors.IDLE)
                ||WildEvents.reserved(child))return false;
        long now=level().getGameTime();if(now<nextSearch)return false;nextSearch=now+80;
        for(var id:new UUID[]{child.family().mother(),child.family().father()}){
            var parent=person(id);var home=parent==null?null:HatBoatEntity.owned(parent);
            if(!healthy(parent)||!safeHome(home,parent)||child.distanceToSqr(parent)>36
                    ||parent.aquaticLife().firstVoyage().active()||parent.aquaticLife().care().active()
                    ||!parent.life().activity().equals(Behaviors.IDLE)||parent.dailyLife().active()
                    ||!parent.life().canRecognize(child)||!child.life().canRecognize(parent)
                    ||WildEvents.reserved(parent)
                    ||!HatBoatRoute.quietSurface(level(),BlockPos.containing(home.getX(),home.getY()-.12,home.getZ()))
                    ||home.getPassengers().stream().anyMatch(p->p instanceof YukkuriEntity y&&!healthy(y)))continue;
            mentor=parent.getUUID();homeHat=home.getUUID();
            harbor=BlockPos.containing(boat.getX(),boat.getY()-.12,boat.getZ());
            leavingHome=true;routineLaunch=false;emergencyRecall=false;turn=findDeparture(boat);
            if(turn==null){mentor=homeHat=null;leavingHome=false;continue;}
            parent.aquaticLife().firstVoyage().pupil=child.getUUID();
            reachedTurn=false;routineSailing=false;ticks=elapsed=0;
            child.setPersistenceRequired();parent.setPersistenceRequired();
            boat.helm().hold(child, Purpose.CARE);home.helm().hold(child, Purpose.CARE);set(Phase.OUT);child.speak("hello");return true;
        }
        return false;
    }
    /** Recall uses the real return route and inter-hat flight; it never awards the lesson or independence. */
    public void suspendMentoring(){pupil=null;}
    /** An audible parental recall can interrupt an unfinished lesson without marking it completed. */
    public boolean recallFromParent(HatBoatEntity parentHat){
        if(independent||parentHat==null||!parentHat.young(child))return false;
        if(returningToParent()&&parentHat.getUUID().equals(homeHat)){emergencyRecall=true;return true;}
        var own=HatBoatEntity.owned(child);
        if(own==null||child.getVehicle()!=own||!own.isAfloat()||!own.protects(child))return false;
        if(phase==Phase.NONE&&completed)return requestReturnToParent(parentHat,true);
        if(homeHat!=null&&!homeHat.equals(parentHat.getUUID()))return false;
        mentor=parentHat.ownerYukkuri();homeHat=parentHat.getUUID();reachedTurn=false;leavingHome=false;
        emergencyRecall=true;routineSailing=false;if(completed)routineLaunch=true;
        elapsed=0;own.helm().drift(child, Purpose.ROUTINE);set(Phase.RETURN);return true;
    }
    public boolean requestReturnToParent(HatBoatEntity parentHat){
        return requestReturnToParent(parentHat,false);
    }
    /** The caller's observed crisis survives the approach; it never grants a remote berth. */
    public boolean requestReturnToParent(HatBoatEntity parentHat,boolean emergency){
        if(independent||!completed||parentHat==null||!parentHat.young(child)||child.family().subadult()&&!emergency)return false;
        if(returningToParent()){
            if(!parentHat.getUUID().equals(homeHat))return false;
            emergencyRecall|=emergency;return true;
        }
        if(phase!=Phase.NONE)return false;
        var own=HatBoatEntity.owned(child);var parent=person(parentHat.ownerYukkuri());
        if(own==null||own.unsteadyMotion()||child.getVehicle()!=own||!own.protects(child)||!safeHome(parentHat,parent))return false;
        boolean room=parentHat.canMakeRoom(child,false);
        if(!room&&!emergency&&!emergencyReturn(own))return false;
        mentor=parent.getUUID();homeHat=parentHat.getUUID();elapsed=0;reachedTurn=false;leavingHome=false;
        emergencyRecall=emergency||emergencyReturn(own);
        routineLaunch=true;routineSailing=false;own.helm().drift(child, Purpose.ROUTINE);set(Phase.RETURN);return true;
    }
    /** Unloading, needs, commands and weather interrupt the milestone, never award a timed success. */
    public void clock(){
        if(cooldown>0)cooldown--;
        if(loadGrace>0){loadGrace--;return;}
        if(pauseForMotion(false))return;
        if(child.aquaticLife().familyLife().alarm().answerPending())return;
        if(pupil!=null){
            var y=person(pupil);
            if(!healthy(child)||y==null||!y.isAlive()||!child.getUUID().equals(y.aquaticLife().firstVoyage().mentor)
                    ||y.aquaticLife().firstVoyage().phase==Phase.NONE)pupil=null;
        }
        if(phase==Phase.NONE)return;
        elapsed++;
        var father=person(mentor);var home=home();
        if(phase!=Phase.RETURN&&phase!=Phase.REBOARD&&phase!=Phase.CELEBRATE){
            String reason=!healthy(child)?"child_unavailable":!healthy(father)?"mentor_unavailable":!safeHome(home,father)?"home_unavailable"
                    :child.distanceToSqr(father)>81?"mentor_too_far"
                    :!child.getUUID().equals(father.aquaticLife().firstVoyage().pupil)?"mentoring_lost":level().isNight()?"night"
                    :!HatBoatRoute.quietSurface(level(),BlockPos.containing(home.getX(),home.getY()-.12,home.getZ()))?"home_water_not_quiet"
                    :elapsed>800?"trip_timeout"
                    :home.getPassengers().stream().anyMatch(p->p instanceof YukkuriEntity y&&!healthy(y))?"home_passenger_unavailable":null;
            if(reason!=null)interrupt(reason);
        }
        if((phase==Phase.RETURN||phase==Phase.REBOARD)&&elapsed>1600)finish("return_timeout");
    }
    private void set(Phase next){set(next, true);}
    private void set(Phase next, boolean log){
        Phase before = phase;
        phase=next;ticks=0;if(next!=Phase.RETURN&&next!=Phase.REBOARD)returnTicks=0;
        if(log && before != next) GameplayLog.limited(child, "voyage", "phase." + next, 200,
                "phase={} -> {} mentor={} homeHat={} elapsed={} completed={} leaving={} routine={} emergency={}",
                before, next, mentor, homeHat, elapsed, completed, leavingHome, routineLaunch, emergencyRecall);
    }
    private static void face(YukkuriEntity who,Vec3 target){
        var d=target.subtract(who.position());float yaw=(float)(Math.toDegrees(Math.atan2(d.z,d.x))-90);
        who.setYRot(yaw);who.yBodyRot=yaw;who.setYHeadRot(yaw);
    }
    private void interrupt(String reason){
        GameplayLog.limited(child, "voyage", "interrupted", 200,
                "trip interrupted reason={} phase={} mentor={} homeHat={} elapsed={} nutrition={} energy={} water={} panic={} leaving={}",
                reason, phase, mentor, homeHat, elapsed, child.life().nutrition(), child.life().energy(),
                child.isInWaterOrBubble(), child.isPanicking(), leavingHome);
        reachedTurn=false;
        // An interrupted departure is once again a dependent child seeking a real refuge berth.
        leavingHome=false;
        if(completed)routineLaunch=true;
        var boat=HatBoatEntity.owned(child);
        if(boat!=null&&(boat.isAfloat()||boat.state()==HatBoatEntity.State.BEACHED)){boat.helm().drift(child, Purpose.ROUTINE);set(Phase.RETURN);}
        else finish("interrupted_without_own_hat");
    }
    /** The placement authored for taking off one's own hat, measured from the retained front berth. */
    private Vec3 dock(HatBoatEntity home){
        if(returningToParent())return home.emergencyApproach(child);
        var berth=home.voyageBerth(child);
        if(berth==null&&completed&&!leavingHome)berth=home.peekSeat(child);
        if(berth==null){
            // A crowded refuge can only be contested after actually reaching its near brim.
            // This is a steering target, not a remote reservation or an eviction.
            return home.emergencyApproach(child);
        }
        var offset=HatVessel.placement(true,HatVessel.Role.SELF).offset().scale(child.growthScale()*child.getScale()/16)
                .yRot((float)Math.toRadians(180-home.getYRot()));
        var at=home.toWorld(berth).add(offset);return new Vec3(at.x,home.getY(),at.z);
    }
    public void tick(){
        if(loadGrace>0)return;
        if(child.aquaticLife().familyLife().alarm().answerPending()){
            child.getNavigation().stop();var own=HatBoatEntity.owned(child);if(own!=null)own.helm().hold(child, Purpose.CARE);return;
        }
        if(pauseForMotion(true))return;
        if(pupil!=null){
            var learner=person(pupil);child.getNavigation().stop();
            if(child.getVehicle() instanceof HatBoatEntity hat){
                var abandoned=learner==null?null:HatBoatEntity.owned(learner);
                if(learner!=null&&learner.aquaticLife().firstVoyage().returningToParent()&&learner.getVehicle()==hat&&abandoned!=null
                        &&child.aquaticLife().familyLife().approachDriftingHat(learner,hat,abandoned))return;
                hat.helm().hold(child, Purpose.CARE);
            }
            if(learner!=null){face(child,learner.position());if(child.tickCount%100==0)child.speak("wait_family");}return;
        }
        if(phase==Phase.NONE)return;
        ticks++;child.getNavigation().stop();
        var father=person(mentor);var home=home();var boat=HatBoatEntity.owned(child);
        if(phase==Phase.APPROACH||phase==Phase.LAUNCH){
            if(!safeHome(home,father)||child.getVehicle()!=home){finish("home_or_parent_unavailable");return;}
            if(home.preparingDepartureBerth(child)){
                home.helm().hold(child, Purpose.CARE);if(!home.advanceDepartureBerth(child))finish("departure_berth_failed");return;
            }
            if(home.changingSeat(child)){home.helm().hold(child, Purpose.CARE);return;}
            if(!home.onVoyageBerth(child)){finish("voyage_berth_lost");return;}
            home.helm().hold(child, Purpose.CARE);var at=dock(home);if(at==null){finish("dock_missing");return;}
            var water=BlockPos.containing(at.x,at.y-.12,at.z);
            if(!HatBoatRoute.quietSurface(level(),water)){finish("launch_water_not_quiet");return;}
            float yaw=home.getYRot();child.setYRot(yaw);child.yBodyRot=yaw;child.setYHeadRot(yaw);
            if(phase==Phase.APPROACH){if(ticks>=20)set(Phase.LAUNCH);return;}
            if(ticks>=36){boat=HullFactory.launchFromFather(child,home,water);if(boat==null){finish("launch_failed");return;}
                harbor=BlockPos.containing(boat.getX(),boat.getY()-.12,boat.getZ());set(Phase.BOARD);}return;
        }
        if(phase==Phase.CELEBRATE){
            if(father!=null)face(child,father.position());if(ticks>=60||!healthy(child))finish("celebration_ended");return;
        }
        if(boat==null||!boat.isAfloat()&&boat.state()!=HatBoatEntity.State.BEACHED){finish("own_hat_unavailable");return;}
        if(child.family().flying()){
            // A source hat with no rider or incoming grip is an actual loose object in the current.
            if(boat.isVehicle()||boat.hasIncoming())boat.helm().hold(child, Purpose.CARE);
            if(home!=null)home.helm().hold(child, Purpose.CARE);return;
        }
        if(phase==Phase.BOARD&&home!=null&&child.getVehicle()==home){
            boat.helm().hold(child, Purpose.CARE);home.helm().hold(child, Purpose.CARE);
            // Approaching the real brim is part of boarding, not a failed launch or a return.
            CrewTransfers.hopOwnHat(child,home,boat);
            if(ticks>180&&!child.family().flying())interrupt("boarding_timeout");
            return;
        }
        if(returningToParent()&&home!=null&&child.getVehicle()==home){
            hatRecoveryTicks++;
            // The parent has brought the hulls together; a few seconds of failed rim contact must not strand the hat.
            if(boat.retrieveAfloat(child,home,hatRecoveryTicks>60)){
                child.showReaction("happy","hat_recovered",40);
                if(reachedTurn&&healthy(father)&&!completed){
                    completed=true;set(Phase.CELEBRATE);child.dailyLife().comfort(25);father.dailyLife().comfort(15);
                    GameplayLog.event(child, "voyage", "lesson completed mentor={} homeHat={} recoveredHat={} elapsed={}",
                            mentor, homeHat, boat.getUUID(), elapsed);
                    var data=YukkuriWorldData.get(level());data.relate(child.getUUID(),father.getUUID(),4);data.relate(father.getUUID(),child.getUUID(),4);
                    child.showReaction("happy",null,60);father.showReaction("happy",null,60);
                    level().sendParticles(ParticleTypes.HAPPY_VILLAGER,child.getX(),child.getY()+child.getBbHeight(),child.getZ(),5,.2,.1,.2,0);
                }else finish("hat_recovered_without_new_lesson");
            }else if(hatRecoveryTicks>180){child.showReaction("teary","hat_out_of_reach",40);finish("hat_recovery_timeout");}return;
        }
        if(child.getVehicle()!=boat){
            // A failed landing is now a real swimmer, not an endlessly restarted
            // RETURN whose prerequisite (riding the own boat) can never hold.
            if(child.isInWaterOrBubble())finish("landing_in_water");else interrupt("own_hat_not_ridden");
            return;
        }
        if(phase==Phase.BOARD){
            boat.helm().hold(child, Purpose.CARE);
            if(routineLaunch){
                if(father!=null&&child.getUUID().equals(father.aquaticLife().firstVoyage().pupil))father.aquaticLife().firstVoyage().pupil=null;
                if(home!=null)home.releaseVoyageBerth(child.getUUID());
                routineLaunch=false;routineSailing=true;emergencyRecall=false;phase=Phase.NONE;ticks=elapsed=0;
                GameplayLog.event(child, "voyage", "routine sailing started boat={} mentor={} completed={}",
                        boat.getUUID(), mentor, completed);
                return;
            }
            set(Phase.BALANCE);return;
        }
        if(phase==Phase.BALANCE){
            boat.helm().hold(child, Purpose.CARE);if(ticks>=40){turn=leavingHome?findDeparture(boat):findTurn(boat);if(turn==null){interrupt("turn_point_unavailable");return;}set(Phase.OUT);}return;
        }
        if(phase==Phase.OUT){
            // Leaving the flotilla starts with an actual exchange while both real hulls are still held together.
            // Independence is still committed only after the child has subsequently paddled clear of this berth.
            if(leavingHome&&ticks<=40){
                if(!safeHome(home,father)){interrupt("farewell_home_unavailable");return;}
                boat.helm().hold(child, Purpose.CARE);home.helm().hold(child, Purpose.CARE);face(child,father.position());face(father,child.position());
                if(ticks==1){
                    child.showReaction("happy","hello",90);child.speak("hello");
                    GameplayLog.event(child,"voyage","afloat farewell started boat={} mentor={} homeHat={}",boat.getUUID(),mentor,homeHat);
                }
                if(ticks==20){
                    father.mind().felt(EmotionEvent.COMFORTED);father.showReactionDelayed("happy","gentle",80,12);
                    GameplayLog.event(child,"voyage","afloat farewell answered boat={} mentor={} homeHat={}",boat.getUUID(),mentor,homeHat);
                }
                return;
            }
            String reason=boat.flooding()>0?"own_hat_flooded":!boat.cargo().paddle()?"paddle_missing"
                    :turn==null?"turn_point_missing":!HatBoatRoute.quietSurface(level(),turn)?"turn_water_not_quiet":!boat.protects(child)?"own_hat_not_protecting":null;
            if(reason!=null){interrupt(reason);return;}
            if(boat.position().distanceToSqr(HatBoatRoute.surface(level(),turn))<.36&&ticks>20){
                reachedTurn=true;
                if (leavingHome && child.independence().completeAfloatDeparture(father, boat)) {
                    independent = true; child.setPersistenceRequired();
                    GameplayLog.event(child, "voyage", "independence established boat={} mentor={} homeHat={} elapsed={}",
                            boat.getUUID(), mentor, homeHat, elapsed);
                    child.showReaction("happy", "hello", 60);
                    if (father != null) father.showReaction("happy", "hello", 60);
                    finish("independence_established");
                    return;
                }
                set(Phase.RETURN);child.speak("hello");return;
            }
            if(ticks>260){interrupt("outbound_timeout");return;}if(ticks%20==1&&!sail(boat,HatBoatRoute.surface(level(),turn)))interrupt("outbound_route_failed");return;
        }
        if(phase==Phase.RETURN||phase==Phase.REBOARD){
            // A missing or capsized father releases this goal to emergency landing/call-for-help behavior.
            if(!safeHome(home,father)){reachedTurn=false;if(ticks>100)finish("return_home_unavailable");return;}
            // First bring both real brims together; FamilyLife then walks to the
            // near edge and selects a reachable climb or a bounded low hop.
            var approach=home.emergencyApproach(child);
            AquaDiag.log(child,"return."+child.getId(),40,"phase="+phase+" boat="+AquaDiag.at(boat.position())+" yaw="+boat.getYRot()+" approach="+AquaDiag.at(approach)
                    +" d="+(approach==null?-1:Math.sqrt(boat.position().distanceToSqr(approach)))+" home="+AquaDiag.at(home.position())+" paddling="+boat.paddling()
                    +" crew="+child.family().crew().describe());
            boolean room=home.voyageBerth(child)!=null||completed&&!leavingHome
                    &&(home.canMakeRoom(child,false)||emergencyReturn(boat));
            if(room&&approach!=null&&boat.protects(child)&&!child.family().flying()){
                double near=boat.position().distanceToSqr(approach);
                // Within a stride of the contact point the paddle is put away and the hull is eased the last bit alongside the
                // parent's (a bounded kinematic drive); the hop that follows only needs the brims to touch, not an exact stop.
                if(near<=.64){
                    home.helm().hold(child, Purpose.CARE);
                    var drive=boat.helm().berth(child, Purpose.BOARDING, approach,boat.getYRot(),.035,4f);
                    contactTicks++;
                    boolean touching=near<=.06||drive.arrived()||contactTicks>40&&near<=.25;
                    if(touching){
                        if(CrewTransfers.hopReturn(child,boat,home,emergencyReturn(boat)).ok()){set(Phase.REBOARD);contactTicks=0;return;}
                        if(boat.changingSeat(child))return;
                        AquaDiag.log(child,"return.refused."+child.getId(),40,"transfer refused: "+child.family().crew().describe());
                    }
                    return;
                }
                contactTicks=0;
            }
            if(ticks%20==1&&!sail(boat,dock(home))){boat.helm().drift(child, Purpose.ROUTINE);if(ticks%100==1)child.speak("wait_family");}
        }
    }
    private boolean emergencyReturn(HatBoatEntity boat){
        return emergencyRecall||child.isPanicking()||child.waterDistress().active()||child.life().nutrition()<30||child.life().energy()<20
                ||boat.flooding()>0;
    }
    private List<Vec3> practicePath(HatBoatEntity boat,Vec3 destination){
        var home=home();if(destination==null||home==null)return List.of();
        var path=HatBoatRoute.find(boat,destination);
        if(path.isEmpty()||path.getLast().distanceToSqr(destination)>1)return List.of();
        return path.stream().allMatch(v->(routineLaunch||leavingHome||v.distanceToSqr(home.position())<=25)
                &&(phase==Phase.RETURN||phase==Phase.REBOARD?HatBoatRoute.calmSurface(level(),BlockPos.containing(v.x,v.y-.12,v.z))
                        :HatBoatRoute.quietSurface(level(),BlockPos.containing(v.x,v.y-.12,v.z))))?path:List.of();
    }
    private BlockPos findTurn(HatBoatEntity boat){
        if(harbor==null)return null;var home=home();if(home==null)return null;
        var choices=new ArrayList<BlockPos>();
        for(var p:BlockPos.betweenClosed(harbor.offset(-3,0,-3),harbor.offset(3,0,3)))
            if(p.distSqr(harbor)>=4&&p.distSqr(harbor)<=9&&HatBoatRoute.quietSurface(level(),p)
                    &&HatBoatRoute.surface(level(),p).distanceToSqr(home.position())>9)choices.add(p.immutable());
        choices.sort(Comparator.comparingDouble(p->p.distSqr(harbor)));
        for(var p:choices)if(!practicePath(boat,HatBoatRoute.surface(level(),p)).isEmpty())return p;return null;
    }
    /** A departure is not the two-block practice turn: it must sail its own hat clear of the parent flotilla by water. */
    private BlockPos findDeparture(HatBoatEntity boat){
        if(harbor==null)return null;var home=home();if(home==null)return null;
        var choices=new ArrayList<BlockPos>();
        for(var p:BlockPos.betweenClosed(harbor.offset(-12,0,-12),harbor.offset(12,0,12)))
            if(p.distSqr(harbor)>=64&&p.distSqr(harbor)<=144&&HatBoatRoute.quietSurface(level(),p)
                    &&HatBoatRoute.surface(level(),p).subtract(home.position()).horizontalDistanceSqr()>=64)choices.add(p.immutable());
        choices.sort(Comparator.comparingDouble(p->p.distSqr(harbor)));
        for(var p:choices)if(!practicePath(boat,HatBoatRoute.surface(level(),p)).isEmpty())return p;return null;
    }
    private boolean sail(HatBoatEntity boat,Vec3 destination){
        if(boat.unsteadyMotion())return false;
        var home=home();
        boolean closeReturn=returningToParent()&&home!=null;
        if(!closeReturn)return boat.helm().route(child, Purpose.ROUTINE, practicePath(boat,destination));
        // A retained berth can become crowded during the trip. Approach outside both
        // actual hulls rather than navigating the child's own hat into that interior seat.
        destination=home.emergencyApproach(child);
        if(destination==null||!returnNodeAllowed(boat,home,destination))return false;
        var path=new ArrayList<>(HatBoatRoute.find(boat,destination,point->returnNodeAllowed(boat,home,point)));
        Vec3 from=path.isEmpty()?boat.position():path.getLast();
        if(!closeReturnLegClear(boat,home,from,destination))return false;
        path.add(destination);
        return boat.helm().route(child, Purpose.ROUTINE, path,.09);
    }
    private boolean returnNodeAllowed(HatBoatEntity boat,HatBoatEntity home,Vec3 point){
        return (routineLaunch||point.distanceToSqr(home.position())<=25)
                &&child.aquaticLife().familyLife().allowsDestination(boat,point)
                &&HatBoatRoute.calmSurface(level(),BlockPos.containing(point.x,point.y-.12,point.z));
    }
    /** Only a short, fully sampled same/adjacent-water-cell finish may bypass a missing BFS centre. */
    private boolean closeReturnLegClear(HatBoatEntity boat,HatBoatEntity home,Vec3 from,Vec3 destination){
        var start=BlockPos.containing(from.x,from.y-.12,from.z);
        var end=BlockPos.containing(destination.x,destination.y-.12,destination.z);
        if(start.distManhattan(end)>1||from.distanceToSqr(destination)>2.25)return false;
        int steps=Math.max(1,(int)Math.ceil(from.distanceTo(destination)/.10));
        for(int i=0;i<=steps;i++){
            var point=from.lerp(destination,(double)i/steps);var cell=BlockPos.containing(point.x,point.y-.12,point.z);
            if(!returnNodeAllowed(boat,home,point)||!level().getWorldBorder().isWithinBounds(cell)
                    ||!level().noCollision(boat,boat.getBoundingBox().move(point.subtract(boat.position()))))return false;
        }
        return true;
    }
    private void finish(String reason){
        if (phase != Phase.NONE) GameplayLog.limited(child, "voyage", "end." + reason, 200,
                "trip ended reason={} phase={} mentor={} homeHat={} elapsed={} completed={} independent={} reachedTurn={} recoveryTicks={}",
                reason, phase, mentor, homeHat, elapsed, completed, independent, reachedTurn, hatRecoveryTicks);
        var father=person(mentor);if(father!=null&&child.getUUID().equals(father.aquaticLife().firstVoyage().pupil))father.aquaticLife().firstVoyage().pupil=null;
        var home=home();if(home!=null)home.releaseVoyageBerth(child.getUUID());
        var boat=HatBoatEntity.owned(child);if(boat!=null)boat.helm().drift(child, Purpose.ROUTINE);
        mentor=homeHat=null;phase=Phase.NONE;cooldown=completed&&!independent?80:600;reachedTurn=false;leavingHome=false;
        hatRecoveryTicks=0;returnTicks=0;contactTicks=0;
        routineLaunch=routineSailing=emergencyRecall=false;
    }
    public CompoundTag save(){
        var tag=new CompoundTag();tag.putBoolean("Completed",completed);tag.putBoolean("Independent",independent);tag.putInt("Cooldown",cooldown);
        tag.putBoolean("RoutineLaunch",routineLaunch);tag.putBoolean("RoutineSailing",routineSailing);
        tag.putBoolean("EmergencyReturn",emergencyRecall);
        tag.putBoolean("InterruptedTrip",phase!=Phase.NONE&&phase!=Phase.CELEBRATE);
        if(mentor!=null)tag.putUUID("Mentor",mentor);if(homeHat!=null)tag.putUUID("HomeHat",homeHat);if(pupil!=null)tag.putUUID("Pupil",pupil);
        if(harbor!=null)tag.putLong("Harbor",harbor.asLong());return tag;
    }
    public void load(CompoundTag tag){
        completed=tag.getBoolean("Completed");independent=tag.getBoolean("Independent");leavingHome=false;cooldown=Math.clamp(tag.getInt("Cooldown"),0,600);
        routineLaunch=tag.getBoolean("RoutineLaunch");routineSailing=tag.getBoolean("RoutineSailing")&&!independent;
        emergencyRecall=tag.getBoolean("InterruptedTrip")&&tag.getBoolean("EmergencyReturn");
        mentor=tag.hasUUID("Mentor")?tag.getUUID("Mentor"):null;homeHat=tag.hasUUID("HomeHat")?tag.getUUID("HomeHat"):null;
        pupil=tag.hasUUID("Pupil")?tag.getUUID("Pupil"):null;phase=Phase.NONE;ticks=elapsed=hatRecoveryTicks=0;reachedTurn=false;loadGrace=40;
        harbor=tag.contains("Harbor")?BlockPos.of(tag.getLong("Harbor")):null;
        if(tag.getBoolean("InterruptedTrip"))set(Phase.RETURN, false);
    }
}
