package io.github.longshou.mcyukkuri.subspecies;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyLife;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/** A juvenile's first use of its own real hat. A parent watches from the bank; only a completed return counts.
 *  Interrupted or reloaded trips return without rewards. No water immunity, spare hats or free paddles. */
public final class FirstVoyage {
    /** Project balance: half the standard adult's linear size, within the existing .20-.80 juvenile span. */
    public static final float MIN_SCALE=.50f;
    private enum Phase { NONE, APPROACH, LAUNCH, BOARD, BALANCE, OUT, RETURN, CELEBRATE }
    private final YukkuriEntity child;
    private Phase phase=Phase.NONE;
    private UUID mentor, pupil;
    private BlockPos bank, harbor, turn;
    private int ticks, elapsed, cooldown;
    private long nextSearch;
    private boolean completed, reachedTurn;
    public FirstVoyage(YukkuriEntity entity){child=entity;}
    private ServerLevel level(){return (ServerLevel)child.level();}
    private YukkuriEntity person(UUID id){return id!=null&&level().getEntity(id) instanceof YukkuriEntity y?y:null;}
    public boolean completed(){return completed;}
    public boolean active(){return phase!=Phase.NONE||pupil!=null;}
    public boolean readySize(){return HatVessel.aquatic(child)&&child.family().stage()==FamilyLife.Stage.JUVENILE&&child.family().scale()>=MIN_SCALE;}
    public boolean needsLesson(){return !child.family().adult()&&!completed;}
    public boolean mayLaunch(){return child.family().adult()||readySize()&&(completed||phase==Phase.LAUNCH);}
    public String status(){return completed?"complete":phase!=Phase.NONE?"practising":child.family().adult()?"adult":readySize()?"ready":"growing";}
    public String activity(){
        if(pupil!=null){var y=person(pupil);return y!=null&&y.aquaticLife().firstVoyage().phase==Phase.CELEBRATE?"voyage_praise":"voyage_watch";}
        return switch(phase){
            case APPROACH->"voyage_prepare";case LAUNCH->"voyage_launch";case BOARD->"voyage_board";
            case BALANCE->"voyage_balance";case OUT->"voyage_out";case RETURN->"voyage_return";
            case CELEBRATE->"voyage_proud";default->"idle";};
    }
    private static boolean healthy(YukkuriEntity y){
        return y!=null&&y.isAlive()&&!y.isNoAi()&&!y.isCommandPlaying()&&!y.operations().active()&&!y.isPanicking()
                &&!y.isInWaterOrBubble()&&!y.isOnFire()&&!y.life().isSleeping()&&y.life().mealTicks()==0
                &&!y.life().hasUrgentNeed()&&y.life().nutrition()>=35&&y.life().energy()>=30&&y.life().bladder()<70&&!y.family().laboring();
    }
    private boolean parent(YukkuriEntity y){return y.getUUID().equals(child.family().mother())||y.getUUID().equals(child.family().father());}
    private static boolean familyFree(YukkuriEntity y){
        return !y.family().active()||y.family().activity().equals("follow_parent")||y.family().activity().equals("wait_child");
    }
    private boolean freeParent(YukkuriEntity y){
        return parent(y)&&y.family().adult()&&healthy(y)&&!y.isPassenger()&&y.onGround()&&familyFree(y)
                &&!y.dailyLife().active()&&!y.aquaticLife().firstVoyage().active()&&!y.aquaticLife().active()
                &&!io.github.longshou.mcyukkuri.event.WildEvents.reserved(y)&&y.life().canRecognize(child)&&child.life().canRecognize(y);
    }
    /** Called by the aquatic goal, or by giving the ready juvenile a stick while sneaking. */
    public boolean tryStart(){
        if(active())return true;
        if(!needsLesson()||!readySize()||cooldown>0||!healthy(child)||child.isPassenger()||!child.onGround()
                ||!child.life().hasAccessory()||child.subspecies().boatId()!=null||!familyFree(child)
                ||!child.aquaticLife().hasStick()||child.life().hasUrgentNeed()||level().isNight()
                ||io.github.longshou.mcyukkuri.event.WildEvents.reserved(child))return false;
        long now=level().getGameTime();if(now<nextSearch)return false;nextSearch=now+100;
        var parents=level().getEntitiesOfClass(YukkuriEntity.class,child.getBoundingBox().inflate(8,3,8),this::freeParent)
                .stream().sorted(Comparator.comparingDouble(child::distanceToSqr)).toList();
        for(var adult:parents){
            for(var water:BlockPos.betweenClosed(child.blockPosition().offset(-5,-2,-5),child.blockPosition().offset(5,1,5))){
                if(!HatBoatRoute.quietSurface(level(),water))continue;
                for(var side:Direction.Plane.HORIZONTAL){
                    var stand=water.relative(side).above();var at=Vec3.atBottomCenterOf(stand);
                    if(adult.distanceToSqr(at)>12.25||!child.life().dryStand(stand)||!child.life().reachable(stand,false)
                            ||!adult.hasLineOfSight(child)||!adult.life().dryStand(adult.blockPosition()))continue;
                    bank=stand.immutable();harbor=water.immutable();mentor=adult.getUUID();
                    adult.aquaticLife().firstVoyage().pupil=child.getUUID();
                    child.setPersistenceRequired();adult.setPersistenceRequired();reachedTurn=false;elapsed=0;
                    set(Phase.APPROACH);child.speak("hello");return true;
                }
            }
        }
        return false;
    }
    /** Independent watchdog: losing a goal, an unloaded parent, weather or a player command cannot finish a lesson. */
    public void clock(){
        if(cooldown>0)cooldown--;
        if(pupil!=null){
            var y=person(pupil);
            if(!healthy(child)||child.isPassenger()||!child.life().dryStand(child.blockPosition())
                    ||y==null||!y.isAlive()||!child.getUUID().equals(y.aquaticLife().firstVoyage().mentor)
                    ||y.aquaticLife().firstVoyage().phase==Phase.NONE)pupil=null;
        }
        if(phase==Phase.NONE)return;
        elapsed++;
        var adult=person(mentor);
        if(phase!=Phase.CELEBRATE&&(!healthy(child)||!healthy(adult)
                ||!child.getUUID().equals(adult.aquaticLife().firstVoyage().pupil)||child.distanceToSqr(adult)>81
                ||level().isNight()||harbor==null||!HatBoatRoute.quietSurface(level(),harbor)||elapsed>700)) {
            reachedTurn=false;if(phase!=Phase.RETURN)interrupt();
        }
        if(phase==Phase.RETURN&&elapsed>1500){
            // Let the ordinary boat-return/rescue logic take over; never award a timed-out trip.
            finish();
        }
    }
    private void set(Phase next){phase=next;ticks=0;}
    private void face(YukkuriEntity who,Vec3 target){
        var d=target.subtract(who.position());float yaw=(float)(Math.toDegrees(Math.atan2(d.z,d.x))-90);
        who.setYRot(yaw);who.yBodyRot=yaw;who.setYHeadRot(yaw);
    }
    private void interrupt(){
        reachedTurn=false;
        var boat=HatBoatEntity.find(child);
        if(boat!=null&&child.getUUID().equals(boat.ownerYukkuri())&&(boat.isAfloat()||boat.state()==HatBoatEntity.State.BEACHED)){
            boat.stopPaddling();set(Phase.RETURN);
        }else finish();
    }
    public void tick(){
        if(pupil!=null){
            var learner=person(pupil);child.getNavigation().stop();
            if(learner!=null){face(child,learner.position());if(child.tickCount%100==0)child.speak("wait_family");}
            return;
        }
        if(phase==Phase.NONE)return;
        ticks++;
        var adult=person(mentor);var boat=HatBoatEntity.find(child);
        if(phase==Phase.APPROACH){
            if(bank==null||!child.life().dryStand(bank)){finish();return;}
            if(child.distanceToSqr(Vec3.atBottomCenterOf(bank))>.16){
                if(ticks%20==1&&!child.life().navigate(bank,.75,false))finish();return;
            }
            child.getNavigation().stop();
            // A half-size child's braid reaches less far. Step toward the edge on dry ground, so the small
            // hull clears the bank while the hat-off animation still meets its physical placement exactly.
            var center=Vec3.atBottomCenterOf(bank);var toward=HatBoatRoute.surface(level(),harbor).subtract(center).multiply(1,0,1).normalize();
            double reach=-HatVessel.placement(true,HatVessel.Role.SELF).offset().z*child.growthScale()*child.getScale()/16;
            double step=Math.max(0,.60+.525*child.growthScale()-reach);
            var edge=center.add(toward.scale(step));
            if(child.distanceToSqr(edge)>.0004){child.getMoveControl().setWantedPosition(edge.x,edge.y,edge.z,.55);return;}
            face(child,HatBoatRoute.surface(level(),harbor));set(Phase.LAUNCH);return;
        }
        child.getNavigation().stop();
        if(phase==Phase.LAUNCH){
            face(child,HatBoatRoute.surface(level(),harbor));
            if(ticks>=36){boat=HatBoatEntity.launchFromBank(child,harbor);if(boat==null){finish();return;}
                harbor=BlockPos.containing(boat.getX(),boat.getY()-.12,boat.getZ());set(Phase.BOARD);}
            return;
        }
        if(phase==Phase.CELEBRATE){
            if(adult!=null)face(child,adult.position());if(ticks>=60||!healthy(child))finish();return;
        }
        if(boat==null||!child.getUUID().equals(boat.ownerYukkuri())||!boat.isAfloat()&&boat.state()!=HatBoatEntity.State.BEACHED){finish();return;}
        if(child.family().flying()){boat.holdPosition();return;}
        if(child.getVehicle()!=boat){interrupt();return;}
        if(phase==Phase.BOARD){boat.holdPosition();set(Phase.BALANCE);return;}
        if(phase==Phase.BALANCE){
            boat.holdPosition();
            if(ticks>=40){
                turn=findTurn(boat);if(turn==null){interrupt();return;}set(Phase.OUT);
            }
            return;
        }
        if(phase!=Phase.RETURN&&(boat.flooding()>0||boat.towed()||!boat.cargo().paddle()||!healthy(child))){interrupt();return;}
        if(phase==Phase.OUT){
            if(turn==null||!HatBoatRoute.quietSurface(level(),turn)||!boat.protects(child)){interrupt();return;}
            if(boat.position().distanceToSqr(HatBoatRoute.surface(level(),turn))<.36&&ticks>20){
                reachedTurn=true;set(Phase.RETURN);child.speak("hello");return;
            }
            if(ticks>220){interrupt();return;}
            if(ticks%20==1&&!sail(boat,turn)){interrupt();}return;
        }
        if(phase==Phase.RETURN){
            if(bank==null){finish();return;}
            if(boat.distanceToSqr(Vec3.atBottomCenterOf(bank))<=3.25&&boat.rescue(bank,null)){
                if(reachedTurn&&healthy(adult)&&child.distanceToSqr(adult)<=25&&!completed){
                    completed=true;set(Phase.CELEBRATE);child.dailyLife().comfort(25);adult.dailyLife().comfort(15);
                    var data=YukkuriWorldData.get(level());data.relate(child.getUUID(),adult.getUUID(),4);data.relate(adult.getUUID(),child.getUUID(),4);
                    child.showReaction("happy","hello",60);adult.showReaction("happy","hello",60);
                    level().sendParticles(net.minecraft.core.particles.ParticleTypes.HAPPY_VILLAGER,child.getX(),child.getY()+child.getBbHeight(),child.getZ(),5,.2,.1,.2,0);
                }else finish();
                return;
            }
            if(ticks%20==1){
                if(!sail(boat,harbor)){boat.stopPaddling();if(ticks%100==1)child.speak("wait_family");}
            }
        }
    }
    private List<Vec3> practicePath(HatBoatEntity boat,BlockPos destination){
        if(destination==null||bank==null)return List.of();
        var path=HatBoatRoute.find(boat,HatBoatRoute.surface(level(),destination));
        if(path.isEmpty()||path.getLast().distanceToSqr(HatBoatRoute.surface(level(),destination))>.25)return List.of();
        return path.stream().allMatch(v->v.distanceToSqr(Vec3.atBottomCenterOf(bank))<=25
                &&(phase==Phase.RETURN?HatBoatRoute.calmSurface(level(),BlockPos.containing(v.x,v.y-.12,v.z))
                        :HatBoatRoute.quietSurface(level(),BlockPos.containing(v.x,v.y-.12,v.z))))?path:List.of();
    }
    private BlockPos findTurn(HatBoatEntity boat){
        var choices=new java.util.ArrayList<BlockPos>();
        for(var p:BlockPos.betweenClosed(harbor.offset(-3,0,-3),harbor.offset(3,0,3)))
            if(p.distSqr(harbor)>=4&&p.distSqr(harbor)<=9&&HatBoatRoute.quietSurface(level(),p))choices.add(p.immutable());
        choices.sort(Comparator.comparingDouble(p->p.distSqr(harbor)));
        for(var p:choices)if(!practicePath(boat,p).isEmpty())return p;
        return null;
    }
    private boolean sail(HatBoatEntity boat,BlockPos destination){return boat.paddleAlong(practicePath(boat,destination));}
    private void finish(){
        var adult=person(mentor);if(adult!=null&&child.getUUID().equals(adult.aquaticLife().firstVoyage().pupil))adult.aquaticLife().firstVoyage().pupil=null;
        var boat=HatBoatEntity.find(child);if(boat!=null&&child.getUUID().equals(boat.ownerYukkuri()))boat.stopPaddling();
        mentor=null;phase=Phase.NONE;cooldown=600;reachedTurn=false;
    }
    public CompoundTag save(){
        var tag=new CompoundTag();tag.putBoolean("Completed",completed);tag.putInt("Cooldown",cooldown);
        tag.putBoolean("InterruptedTrip",phase!=Phase.NONE&&phase!=Phase.CELEBRATE);
        if(bank!=null){tag.putLong("Bank",bank.asLong());tag.putString("Dimension",child.level().dimension().location().toString());}
        if(harbor!=null)tag.putLong("Harbor",harbor.asLong());
        return tag;
    }
    public void load(CompoundTag tag){
        completed=tag.getBoolean("Completed");cooldown=Math.clamp(tag.getInt("Cooldown"),0,600);
        mentor=pupil=null;phase=Phase.NONE;ticks=elapsed=0;reachedTurn=false;
        bank=tag.contains("Bank")&&tag.getString("Dimension").equals(child.level().dimension().location().toString())?BlockPos.of(tag.getLong("Bank")):null;
        harbor=tag.contains("Harbor")?BlockPos.of(tag.getLong("Harbor")):null;
        if(tag.getBoolean("InterruptedTrip")&&bank!=null&&harbor!=null)set(Phase.RETURN);
    }
}
