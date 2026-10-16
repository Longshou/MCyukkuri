package io.github.longshou.mcyukkuri.subspecies;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyLife;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/** Learning to handle one's hat is separate from leaving the family at subadulthood. */
public final class FirstVoyage {
    /** Project balance within the existing .20-.80 juvenile span, not a Wiki age or size. */
    public static final float MIN_SCALE=.20f;
    private enum Phase { NONE, APPROACH, LAUNCH, BOARD, BALANCE, OUT, RETURN, REBOARD, CELEBRATE }
    private final YukkuriEntity child;
    private Phase phase=Phase.NONE;
    private UUID mentor, pupil, homeHat;
    private BlockPos harbor, turn;
    private int ticks, elapsed, cooldown, loadGrace;
    private long nextSearch;
    private boolean completed, reachedTurn, independent, leavingHome;
    private boolean routineLaunch, routineSailing, emergencyRecall;
    /** The worn hat keeps its real cargo and identity while its owner shelters aboard a parent. */
    private UUID retainedHatToken;
    private CompoundTag retainedCargo;
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
    public boolean readySize(){return HatVessel.aquatic(child)&&child.family().stage()!=FamilyLife.Stage.BABY&&child.family().scale()>=MIN_SCALE&&child.childhood().canEatSolid()&&child.childhood().canJump()&&child.childhood().canLearn();}
    public boolean needsLesson(){return !child.family().adult()&&!completed&&!independent&&(child.family().mother()!=null||child.family().father()!=null);}
    public boolean mayLaunch(){return !(child.getVehicle() instanceof HatBoatEntity hat&&hat.unsteadyMotion())
            &&(child.family().adult()||readySize()&&(phase==Phase.LAUNCH||completed));}
    public boolean launchingFrom(HatBoatEntity boat){return !boat.unsteadyMotion()&&phase==Phase.LAUNCH&&boat.getUUID().equals(homeHat);}
    public boolean keepsBerth(HatBoatEntity boat){return phase!=Phase.NONE&&boat.getUUID().equals(homeHat);}
    public boolean routineSailing(){return routineSailing&&phase==Phase.NONE&&!independent;}
    public boolean returningToParent(){return !leavingHome&&(phase==Phase.RETURN||phase==Phase.REBOARD);}
    public UUID retainedHatToken(){return retainedHatToken;}
    public boolean hasRetainedPaddle(){return retainedCargo!=null&&retainedCargo.getBoolean("Paddle");}
    void retainHat(UUID token,CompoundTag cargo){retainedHatToken=token;retainedCargo=cargo.copy();}
    void restoreHatCargo(HatBoatEntity boat){
        if(retainedCargo!=null){boat.cargo().load(retainedCargo);boat.syncCargo(boat.cargo().save());retainedCargo=null;}
        retainedHatToken=null;
    }
    /** A worn/storage hat cannot hide provisions from death, landing or an external accessory removal. */
    public void dropStoredCargo(){
        if(child.level().isClientSide)return;
        var stored=retainedCargo;retainedCargo=null;retainedHatToken=null;
        if(stored==null)return;
        for(var raw:stored.getList("Food",net.minecraft.nbt.Tag.TAG_COMPOUND)){
            var stack=net.minecraft.world.item.ItemStack.parseOptional(child.registryAccess(),(CompoundTag)raw);
            if(!stack.isEmpty())child.spawnAtLocation(stack);
        }
        if(stored.getBoolean("Paddle"))child.spawnAtLocation(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STICK));
    }
    public String status(){return independent?"independent":leavingHome?"leaving":returningToParent()?"returning_family":routineSailing()?"family_sailing":routineLaunch?"family_launch":phase!=Phase.NONE?"practising":completed?"complete":child.family().adult()?"adult":readySize()?"ready":"growing";}
    public String activity(){
        if(pupil!=null){var y=person(pupil);return y!=null&&y.aquaticLife().firstVoyage().phase==Phase.CELEBRATE?"voyage_praise":"voyage_watch";}
        return switch(phase){
            case APPROACH->"voyage_prepare";case LAUNCH->"voyage_launch";case BOARD,REBOARD->"voyage_board";
            case BALANCE->"voyage_balance";case OUT->"voyage_out";case RETURN->"voyage_return";
            case CELEBRATE->"voyage_proud";default->"idle";};
    }
    private static boolean healthy(YukkuriEntity y){
        return y!=null&&y.isAlive()&&!y.isNoAi()&&!y.isCommandPlaying()&&!y.operations().active()&&!y.isPanicking()
                &&!y.isInWaterOrBubble()&&!y.isOnFire()&&!y.life().isSleeping()&&y.life().mealTicks()==0
                &&!y.life().hasUrgentNeed()&&y.life().nutrition()>=35&&y.life().energy()>=30&&y.life().bladder()<70&&!y.family().laboring();
    }
    private boolean safeHome(HatBoatEntity boat,YukkuriEntity father){
        return boat!=null&&father!=null&&!boat.unsteadyMotion()&&boat.protects(father)&&boat.flooding()==0&&!boat.towed();
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
            if(riding!=null)riding.holdPosition();if(own!=null)own.holdPosition();
            if(home!=null)home.holdPosition();if(learnerBoat!=null)learnerBoat.holdPosition();
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
        boolean departure = !routine && completed && !independent && child.childhood().canLiveIndependently();
        if(departure&&child.getVehicle() instanceof HatBoatEntity own&&child.getUUID().equals(own.ownerYukkuri()))
            return departAfloat(own);
        if((!needsLesson()&&!departure&&!routine)||!readySize()||cooldown>0||!healthy(child)||!child.life().hasAccessory()
                ||child.subspecies().boatId()!=null||!child.aquaticLife().hasStick()&&!hasRetainedPaddle()||level().isNight()
                ||!child.life().activity().equals("idle")||child.dailyLife().active()
                ||!(child.getVehicle() instanceof HatBoatEntity boat)
                ||!(boat.ownerYukkuri().equals(child.family().father())||boat.ownerYukkuri().equals(child.family().mother()))||boat.hasIncoming())return false;
        long now=level().getGameTime();if(now<nextSearch)return false;nextSearch=now+80;
        var father=person(boat.ownerYukkuri());
        if(!safeHome(boat,father)||!healthy(father)||father.aquaticLife().firstVoyage().active()
                ||father.aquaticLife().care().active()||!father.life().activity().equals("idle")||father.dailyLife().active()
                ||!father.life().canRecognize(child)||!child.life().canRecognize(father)
                ||!HatBoatRoute.quietSurface(level(),BlockPos.containing(boat.getX(),boat.getY()-.12,boat.getZ()))
                ||boat.getPassengers().stream().anyMatch(p->p instanceof YukkuriEntity y&&!healthy(y))
                ||io.github.longshou.mcyukkuri.event.WildEvents.reserved(child)||io.github.longshou.mcyukkuri.event.WildEvents.reserved(father)
                ||!(!departure&&boat.onBrim(child)?boat.keepVoyageBerth(child):boat.keepDepartureBerth(child)))return false;
        mentor=father.getUUID();homeHat=boat.getUUID();father.aquaticLife().firstVoyage().pupil=child.getUUID();
        child.setPersistenceRequired();father.setPersistenceRequired();elapsed=0;reachedTurn=false;leavingHome=departure;
        routineLaunch=routine;routineSailing=false;emergencyRecall=false;
        boat.holdPosition();set(Phase.APPROACH);child.speak("hello");return true;
    }
    /** A child already living in its own hat leaves in that vessel, without first packing it into a parent's hat. */
    private boolean departAfloat(HatBoatEntity boat){
        if(cooldown>0||!readySize()||!healthy(child)||boat.unsteadyMotion()||!boat.protects(child)||boat.flooding()>0||boat.towed()
                ||!boat.cargo().paddle()||level().isNight()||!child.life().activity().equals("idle")
                ||io.github.longshou.mcyukkuri.event.WildEvents.reserved(child))return false;
        long now=level().getGameTime();if(now<nextSearch)return false;nextSearch=now+80;
        for(var id:new UUID[]{child.family().mother(),child.family().father()}){
            var parent=person(id);var home=parent==null?null:HatBoatEntity.owned(parent);
            if(!healthy(parent)||!safeHome(home,parent)||child.distanceToSqr(parent)>36
                    ||parent.aquaticLife().firstVoyage().active()||parent.aquaticLife().care().active()
                    ||!parent.life().activity().equals("idle")||parent.dailyLife().active()
                    ||!parent.life().canRecognize(child)||!child.life().canRecognize(parent)
                    ||io.github.longshou.mcyukkuri.event.WildEvents.reserved(parent)
                    ||!HatBoatRoute.quietSurface(level(),BlockPos.containing(home.getX(),home.getY()-.12,home.getZ()))
                    ||home.getPassengers().stream().anyMatch(p->p instanceof YukkuriEntity y&&!healthy(y)))continue;
            mentor=parent.getUUID();homeHat=home.getUUID();
            harbor=BlockPos.containing(boat.getX(),boat.getY()-.12,boat.getZ());
            leavingHome=true;routineLaunch=false;emergencyRecall=false;turn=findTurn(boat);
            if(turn==null){mentor=homeHat=null;leavingHome=false;continue;}
            parent.aquaticLife().firstVoyage().pupil=child.getUUID();
            reachedTurn=false;routineSailing=false;ticks=elapsed=0;
            child.setPersistenceRequired();parent.setPersistenceRequired();
            boat.holdPosition();home.holdPosition();set(Phase.OUT);child.speak("hello");return true;
        }
        return false;
    }
    /** Recall uses the real return route and inter-hat flight; it never awards the lesson or independence. */
    public boolean requestReturnToParent(HatBoatEntity parentHat){
        return requestReturnToParent(parentHat,false);
    }
    /** The caller's observed crisis survives the approach; it never grants a remote berth. */
    public boolean requestReturnToParent(HatBoatEntity parentHat,boolean emergency){
        if(independent||!completed||parentHat==null||!parentHat.young(child))return false;
        if(returningToParent()){
            if(!parentHat.getUUID().equals(homeHat))return false;
            emergencyRecall|=emergency;return true;
        }
        if(phase!=Phase.NONE)return false;
        var own=HatBoatEntity.owned(child);var parent=person(parentHat.ownerYukkuri());
        if(own==null||own.unsteadyMotion()||child.getVehicle()!=own||!own.protects(child)||!safeHome(parentHat,parent)
                ||!parentHat.hasSafeRoomFor(child)&&!emergency&&!emergencyReturn(own))return false;
        mentor=parent.getUUID();homeHat=parentHat.getUUID();elapsed=0;reachedTurn=false;leavingHome=false;
        emergencyRecall=emergency||emergencyReturn(own);
        routineLaunch=true;routineSailing=false;own.stopPaddling();set(Phase.RETURN);return true;
    }
    /** Unloading, needs, commands and weather interrupt the milestone, never award a timed success. */
    public void clock(){
        if(cooldown>0)cooldown--;
        if(loadGrace>0){loadGrace--;return;}
        if(pauseForMotion(false))return;
        if(pupil!=null){
            var y=person(pupil);
            if(!healthy(child)||y==null||!y.isAlive()||!child.getUUID().equals(y.aquaticLife().firstVoyage().mentor)
                    ||y.aquaticLife().firstVoyage().phase==Phase.NONE)pupil=null;
        }
        if(phase==Phase.NONE)return;
        elapsed++;
        var father=person(mentor);var home=home();
        if(phase!=Phase.RETURN&&phase!=Phase.REBOARD&&phase!=Phase.CELEBRATE
                &&(!healthy(child)||!healthy(father)||!safeHome(home,father)||child.distanceToSqr(father)>81
                ||!child.getUUID().equals(father.aquaticLife().firstVoyage().pupil)||level().isNight()
                ||!HatBoatRoute.quietSurface(level(),BlockPos.containing(home.getX(),home.getY()-.12,home.getZ()))||elapsed>800
                ||home.getPassengers().stream().anyMatch(p->p instanceof YukkuriEntity y&&!healthy(y))))interrupt();
        if((phase==Phase.RETURN||phase==Phase.REBOARD)&&elapsed>1600)finish();
    }
    private void set(Phase next){phase=next;ticks=0;}
    private static void face(YukkuriEntity who,Vec3 target){
        var d=target.subtract(who.position());float yaw=(float)(Math.toDegrees(Math.atan2(d.z,d.x))-90);
        who.setYRot(yaw);who.yBodyRot=yaw;who.setYHeadRot(yaw);
    }
    private void interrupt(){
        reachedTurn=false;
        // An interrupted departure is once again a dependent child seeking a real refuge berth.
        leavingHome=false;
        if(completed)routineLaunch=true;
        var boat=HatBoatEntity.owned(child);
        if(boat!=null&&(boat.isAfloat()||boat.state()==HatBoatEntity.State.BEACHED)){boat.stopPaddling();set(Phase.RETURN);}
        else finish();
    }
    /** The placement authored for taking off one's own hat, measured from the retained front berth. */
    private Vec3 dock(HatBoatEntity home){
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
        if(pauseForMotion(true))return;
        if(pupil!=null){
            var learner=person(pupil);child.getNavigation().stop();
            if(child.getVehicle() instanceof HatBoatEntity hat)hat.holdPosition();
            if(learner!=null){face(child,learner.position());if(child.tickCount%100==0)child.speak("wait_family");}return;
        }
        if(phase==Phase.NONE)return;
        ticks++;child.getNavigation().stop();
        var father=person(mentor);var home=home();var boat=HatBoatEntity.owned(child);
        if(phase==Phase.APPROACH||phase==Phase.LAUNCH){
            if(!safeHome(home,father)||child.getVehicle()!=home){finish();return;}
            if(home.changingSeat(child)){home.holdPosition();return;}
            if(!home.onVoyageBerth(child)){finish();return;}
            home.holdPosition();var at=dock(home);if(at==null){finish();return;}
            var water=BlockPos.containing(at.x,at.y-.12,at.z);
            if(!HatBoatRoute.quietSurface(level(),water)){finish();return;}
            float yaw=home.getYRot();child.setYRot(yaw);child.yBodyRot=yaw;child.setYHeadRot(yaw);
            if(phase==Phase.APPROACH){if(ticks>=20)set(Phase.LAUNCH);return;}
            if(ticks>=36){boat=HatBoatEntity.launchFromFather(child,home,water);if(boat==null){finish();return;}
                harbor=BlockPos.containing(boat.getX(),boat.getY()-.12,boat.getZ());set(Phase.BOARD);}return;
        }
        if(phase==Phase.CELEBRATE){
            if(father!=null)face(child,father.position());if(ticks>=60||!healthy(child))finish();return;
        }
        if(boat==null||!boat.isAfloat()&&boat.state()!=HatBoatEntity.State.BEACHED){finish();return;}
        if(child.family().flying()){boat.holdPosition();if(home!=null)home.holdPosition();return;}
        if(home!=null&&child.getVehicle()==home){
            if(boat.retrieveAfloat(child,home)){
                if(reachedTurn&&healthy(father)&&!completed){
                    completed=true;set(Phase.CELEBRATE);child.dailyLife().comfort(25);father.dailyLife().comfort(15);
                    var data=YukkuriWorldData.get(level());data.relate(child.getUUID(),father.getUUID(),4);data.relate(father.getUUID(),child.getUUID(),4);
                    child.showReaction("happy","hello",60);father.showReaction("happy","hello",60);
                    level().sendParticles(net.minecraft.core.particles.ParticleTypes.HAPPY_VILLAGER,child.getX(),child.getY()+child.getBbHeight(),child.getZ(),5,.2,.1,.2,0);
                }else finish();
            }else if(ticks>100)finish();return;
        }
        if(child.getVehicle()!=boat){
            // A failed landing is now a real swimmer, not an endlessly restarted
            // RETURN whose prerequisite (riding the own boat) can never hold.
            if(child.isInWaterOrBubble())finish();else interrupt();
            return;
        }
        if(phase==Phase.BOARD){
            boat.holdPosition();
            if(routineLaunch){
                if(father!=null&&child.getUUID().equals(father.aquaticLife().firstVoyage().pupil))father.aquaticLife().firstVoyage().pupil=null;
                if(home!=null)home.releaseVoyageBerth(child.getUUID());
                routineLaunch=false;routineSailing=true;emergencyRecall=false;phase=Phase.NONE;ticks=elapsed=0;
                return;
            }
            set(Phase.BALANCE);return;
        }
        if(phase==Phase.BALANCE){
            boat.holdPosition();if(ticks>=40){turn=findTurn(boat);if(turn==null){interrupt();return;}set(Phase.OUT);}return;
        }
        if(phase==Phase.OUT){
            if(boat.flooding()>0||boat.towed()||!boat.cargo().paddle()||turn==null||!HatBoatRoute.quietSurface(level(),turn)||!boat.protects(child)){interrupt();return;}
            if(boat.position().distanceToSqr(HatBoatRoute.surface(level(),turn))<.36&&ticks>20){
                reachedTurn=true;
                if (leavingHome && child.childhood().canLiveIndependently() && boat.protects(child)) {
                    independent = true; child.setPersistenceRequired();
                    child.showReaction("happy", "hello", 60);
                    if (father != null) father.showReaction("happy", "hello", 60);
                    finish();
                    return;
                }
                set(Phase.RETURN);child.speak("hello");return;
            }
            if(ticks>260){interrupt();return;}if(ticks%20==1&&!sail(boat,HatBoatRoute.surface(level(),turn)))interrupt();return;
        }
        if(phase==Phase.RETURN||phase==Phase.REBOARD){
            // A missing or capsized father releases this goal to emergency landing/call-for-help behavior.
            if(!safeHome(home,father)){reachedTurn=false;if(ticks>100)finish();return;}
            if((home.voyageBerth(child)!=null||completed&&!leavingHome&&(home.hasSafeRoomFor(child)||emergencyReturn(boat)))
                    &&(boat.distanceToSqr(home)<=6.25||emergencyReturn(boat)&&!home.hasSafeRoomFor(child))
                    &&boat.protects(child)&&!boat.towed()){
                if(child.family().transferHat(boat,home,emergencyReturn(boat))){set(Phase.REBOARD);return;}
            }
            if(ticks%20==1&&!sail(boat,dock(home))){boat.stopPaddling();if(ticks%100==1)child.speak("wait_family");}
        }
    }
    private boolean emergencyReturn(HatBoatEntity boat){
        return emergencyRecall||child.isPanicking()||child.waterDistress().active()||child.life().nutrition()<30||child.life().energy()<20
                ||boat.flooding()>0||boat.towed();
    }
    private List<Vec3> practicePath(HatBoatEntity boat,Vec3 destination){
        var home=home();if(destination==null||home==null)return List.of();
        var path=HatBoatRoute.find(boat,destination);
        if(path.isEmpty()||path.getLast().distanceToSqr(destination)>1)return List.of();
        return path.stream().allMatch(v->(routineLaunch||v.distanceToSqr(home.position())<=25)
                &&(phase==Phase.RETURN||phase==Phase.REBOARD?HatBoatRoute.calmSurface(level(),BlockPos.containing(v.x,v.y-.12,v.z))
                        :HatBoatRoute.quietSurface(level(),BlockPos.containing(v.x,v.y-.12,v.z))))?path:List.of();
    }
    private BlockPos findTurn(HatBoatEntity boat){
        if(harbor==null)return null;var home=home();if(home==null)return null;
        var choices=new java.util.ArrayList<BlockPos>();
        for(var p:BlockPos.betweenClosed(harbor.offset(-3,0,-3),harbor.offset(3,0,3)))
            if(p.distSqr(harbor)>=4&&p.distSqr(harbor)<=9&&HatBoatRoute.quietSurface(level(),p)
                    &&HatBoatRoute.surface(level(),p).distanceToSqr(home.position())>9)choices.add(p.immutable());
        choices.sort(Comparator.comparingDouble(p->p.distSqr(harbor)));
        for(var p:choices)if(!practicePath(boat,HatBoatRoute.surface(level(),p)).isEmpty())return p;return null;
    }
    private boolean sail(HatBoatEntity boat,Vec3 destination){
        if(boat.unsteadyMotion())return false;
        var home=home();
        boolean closeEmergency=returningToParent()&&home!=null&&emergencyReturn(boat)&&!home.hasSafeRoomFor(child);
        if(!closeEmergency)return boat.paddleAlong(practicePath(boat,destination));
        // A retained berth can become crowded during the trip. Approach outside both
        // actual hulls rather than navigating the child's own hat into that interior seat.
        destination=home.emergencyApproach(child);
        if(destination==null||!returnNodeAllowed(boat,home,destination))return false;
        var path=new java.util.ArrayList<>(HatBoatRoute.find(boat,destination,point->returnNodeAllowed(boat,home,point)));
        Vec3 from=path.isEmpty()?boat.position():path.getLast();
        if(!closeReturnLegClear(boat,home,from,destination))return false;
        path.add(destination);
        return boat.paddleAlong(path,.0025);
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
    private void finish(){
        var father=person(mentor);if(father!=null&&child.getUUID().equals(father.aquaticLife().firstVoyage().pupil))father.aquaticLife().firstVoyage().pupil=null;
        var home=home();if(home!=null)home.releaseVoyageBerth(child.getUUID());
        var boat=HatBoatEntity.owned(child);if(boat!=null)boat.stopPaddling();
        mentor=homeHat=null;phase=Phase.NONE;cooldown=completed&&!independent?80:600;reachedTurn=false;leavingHome=false;
        routineLaunch=routineSailing=emergencyRecall=false;
    }
    public CompoundTag save(){
        var tag=new CompoundTag();tag.putBoolean("Completed",completed);tag.putBoolean("Independent",independent);tag.putInt("Cooldown",cooldown);
        tag.putBoolean("RoutineLaunch",routineLaunch);tag.putBoolean("RoutineSailing",routineSailing);
        tag.putBoolean("EmergencyReturn",emergencyRecall);
        if(retainedHatToken!=null)tag.putUUID("RetainedHatToken",retainedHatToken);
        if(retainedCargo!=null)tag.put("RetainedCargo",retainedCargo.copy());
        tag.putBoolean("InterruptedTrip",phase!=Phase.NONE&&phase!=Phase.CELEBRATE);
        if(mentor!=null)tag.putUUID("Mentor",mentor);if(homeHat!=null)tag.putUUID("HomeHat",homeHat);if(pupil!=null)tag.putUUID("Pupil",pupil);
        if(harbor!=null)tag.putLong("Harbor",harbor.asLong());return tag;
    }
    public void load(CompoundTag tag){
        completed=tag.getBoolean("Completed");independent=tag.getBoolean("Independent");leavingHome=false;cooldown=Math.clamp(tag.getInt("Cooldown"),0,600);
        routineLaunch=tag.getBoolean("RoutineLaunch");routineSailing=tag.getBoolean("RoutineSailing")&&!independent;
        emergencyRecall=tag.getBoolean("InterruptedTrip")&&tag.getBoolean("EmergencyReturn");
        retainedHatToken=tag.hasUUID("RetainedHatToken")?tag.getUUID("RetainedHatToken"):null;
        retainedCargo=tag.contains("RetainedCargo")?tag.getCompound("RetainedCargo").copy():null;
        mentor=tag.hasUUID("Mentor")?tag.getUUID("Mentor"):null;homeHat=tag.hasUUID("HomeHat")?tag.getUUID("HomeHat"):null;
        pupil=tag.hasUUID("Pupil")?tag.getUUID("Pupil"):null;phase=Phase.NONE;ticks=elapsed=0;reachedTurn=false;loadGrace=40;
        harbor=tag.contains("Harbor")?BlockPos.of(tag.getLong("Harbor")):null;
        if(tag.getBoolean("InterruptedTrip"))set(Phase.RETURN);
    }
}
