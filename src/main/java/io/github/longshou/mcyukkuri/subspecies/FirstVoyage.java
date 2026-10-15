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

/** The first short round trip starts and ends on the father's floating hat. No water immunity or spare hats. */
public final class FirstVoyage {
    /** Project balance within the existing .20-.80 juvenile span, not a Wiki age or size. */
    public static final float MIN_SCALE=.35f;
    private enum Phase { NONE, APPROACH, LAUNCH, BOARD, BALANCE, OUT, RETURN, REBOARD, CELEBRATE }
    private final YukkuriEntity child;
    private Phase phase=Phase.NONE;
    private UUID mentor, pupil, homeHat;
    private BlockPos harbor, turn;
    private int ticks, elapsed, cooldown, loadGrace;
    private long nextSearch;
    private boolean completed, reachedTurn;
    public FirstVoyage(YukkuriEntity entity){child=entity;}
    private ServerLevel level(){return (ServerLevel)child.level();}
    private YukkuriEntity person(UUID id){return id!=null&&level().getEntity(id) instanceof YukkuriEntity y?y:null;}
    private HatBoatEntity home(){
        return homeHat!=null&&level().getEntity(homeHat) instanceof HatBoatEntity boat&&boat.isAfloat()
                &&mentor!=null&&mentor.equals(boat.ownerYukkuri())?boat:null;
    }
    public boolean completed(){return completed;}
    public boolean active(){return phase!=Phase.NONE||pupil!=null;}
    public boolean readySize(){return HatVessel.aquatic(child)&&child.family().stage()==FamilyLife.Stage.JUVENILE&&child.family().scale()>=MIN_SCALE;}
    public boolean needsLesson(){return !child.family().adult()&&!completed;}
    public boolean mayLaunch(){return child.family().adult()||readySize()&&(completed||phase==Phase.LAUNCH);}
    public boolean launchingFrom(HatBoatEntity boat){return phase==Phase.LAUNCH&&boat.getUUID().equals(homeHat);}
    public boolean keepsBerth(HatBoatEntity boat){return phase!=Phase.NONE&&boat.getUUID().equals(homeHat);}
    public String status(){return completed?"complete":phase!=Phase.NONE?"practising":child.family().adult()?"adult":readySize()?"ready":"growing";}
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
        return boat!=null&&father!=null&&boat.protects(father)&&boat.flooding()==0&&!boat.towed();
    }
    /** A ready pupil already rides its father; an occupied brim or another lesson makes it wait its turn. */
    public boolean tryStart(){
        if(active())return true;
        if(!needsLesson()||!readySize()||cooldown>0||!healthy(child)||!child.life().hasAccessory()
                ||child.subspecies().boatId()!=null||!child.aquaticLife().hasStick()||level().isNight()
                ||!child.life().activity().equals("idle")||child.dailyLife().active()
                ||!(child.getVehicle() instanceof HatBoatEntity boat)||!boat.onBrim(child)
                ||!boat.ownerYukkuri().equals(child.family().father())||boat.hasIncoming())return false;
        long now=level().getGameTime();if(now<nextSearch)return false;nextSearch=now+80;
        var father=person(child.family().father());
        if(!safeHome(boat,father)||!healthy(father)||father.aquaticLife().firstVoyage().active()
                ||father.aquaticLife().care().active()||!father.life().activity().equals("idle")||father.dailyLife().active()
                ||!father.life().canRecognize(child)||!child.life().canRecognize(father)
                ||!HatBoatRoute.quietSurface(level(),BlockPos.containing(boat.getX(),boat.getY()-.12,boat.getZ()))
                ||boat.getPassengers().stream().anyMatch(p->p instanceof YukkuriEntity y&&!healthy(y))
                ||io.github.longshou.mcyukkuri.event.WildEvents.reserved(child)||io.github.longshou.mcyukkuri.event.WildEvents.reserved(father)
                ||!boat.keepVoyageBerth(child))return false;
        mentor=father.getUUID();homeHat=boat.getUUID();father.aquaticLife().firstVoyage().pupil=child.getUUID();
        child.setPersistenceRequired();father.setPersistenceRequired();elapsed=0;reachedTurn=false;
        boat.holdPosition();set(Phase.APPROACH);child.speak("hello");return true;
    }
    /** Unloading, needs, commands and weather interrupt the milestone, never award a timed success. */
    public void clock(){
        if(cooldown>0)cooldown--;
        if(loadGrace>0){loadGrace--;return;}
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
        var boat=HatBoatEntity.owned(child);
        if(boat!=null&&(boat.isAfloat()||boat.state()==HatBoatEntity.State.BEACHED)){boat.stopPaddling();set(Phase.RETURN);}
        else finish();
    }
    /** The placement authored for taking off one's own hat, measured from the retained front berth. */
    private Vec3 dock(HatBoatEntity home){
        var berth=home.voyageBerth(child);if(berth==null)return null;
        var offset=HatVessel.placement(true,HatVessel.Role.SELF).offset().scale(child.growthScale()*child.getScale()/16)
                .yRot((float)Math.toRadians(180-home.getYRot()));
        var at=home.toWorld(berth).add(offset);return new Vec3(at.x,home.getY(),at.z);
    }
    public void tick(){
        if(loadGrace>0)return;
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
            if(!home.onBrim(child)){finish();return;}
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
        if(child.getVehicle()!=boat){interrupt();return;}
        if(phase==Phase.BOARD){boat.holdPosition();set(Phase.BALANCE);return;}
        if(phase==Phase.BALANCE){
            boat.holdPosition();if(ticks>=40){turn=findTurn(boat);if(turn==null){interrupt();return;}set(Phase.OUT);}return;
        }
        if(phase==Phase.OUT){
            if(boat.flooding()>0||boat.towed()||!boat.cargo().paddle()||turn==null||!HatBoatRoute.quietSurface(level(),turn)||!boat.protects(child)){interrupt();return;}
            if(boat.position().distanceToSqr(HatBoatRoute.surface(level(),turn))<.36&&ticks>20){reachedTurn=true;set(Phase.RETURN);child.speak("hello");return;}
            if(ticks>260){interrupt();return;}if(ticks%20==1&&!sail(boat,HatBoatRoute.surface(level(),turn)))interrupt();return;
        }
        if(phase==Phase.RETURN||phase==Phase.REBOARD){
            // A missing or capsized father releases this goal to emergency landing/call-for-help behavior.
            if(!safeHome(home,father)){reachedTurn=false;if(ticks>100)finish();return;}
            if(home.voyageBerth(child)!=null&&boat.distanceToSqr(home)<=6.25&&boat.protects(child)&&!boat.towed()){
                if(child.family().transferHat(boat,home)){set(Phase.REBOARD);return;}
            }
            if(ticks%20==1&&!sail(boat,dock(home))){boat.stopPaddling();if(ticks%100==1)child.speak("wait_family");}
        }
    }
    private List<Vec3> practicePath(HatBoatEntity boat,Vec3 destination){
        var home=home();if(destination==null||home==null)return List.of();
        var path=HatBoatRoute.find(boat,destination);
        if(path.isEmpty()||path.getLast().distanceToSqr(destination)>1)return List.of();
        return path.stream().allMatch(v->v.distanceToSqr(home.position())<=25
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
    private boolean sail(HatBoatEntity boat,Vec3 destination){return boat.paddleAlong(practicePath(boat,destination));}
    private void finish(){
        var father=person(mentor);if(father!=null&&child.getUUID().equals(father.aquaticLife().firstVoyage().pupil))father.aquaticLife().firstVoyage().pupil=null;
        var home=home();if(home!=null)home.releaseVoyageBerth(child.getUUID());
        var boat=HatBoatEntity.owned(child);if(boat!=null)boat.stopPaddling();
        mentor=homeHat=null;phase=Phase.NONE;cooldown=600;reachedTurn=false;
    }
    public CompoundTag save(){
        var tag=new CompoundTag();tag.putBoolean("Completed",completed);tag.putInt("Cooldown",cooldown);
        tag.putBoolean("InterruptedTrip",phase!=Phase.NONE&&phase!=Phase.CELEBRATE);
        if(mentor!=null)tag.putUUID("Mentor",mentor);if(homeHat!=null)tag.putUUID("HomeHat",homeHat);if(pupil!=null)tag.putUUID("Pupil",pupil);
        if(harbor!=null)tag.putLong("Harbor",harbor.asLong());return tag;
    }
    public void load(CompoundTag tag){
        completed=tag.getBoolean("Completed");cooldown=Math.clamp(tag.getInt("Cooldown"),0,600);
        mentor=tag.hasUUID("Mentor")?tag.getUUID("Mentor"):null;homeHat=tag.hasUUID("HomeHat")?tag.getUUID("HomeHat"):null;
        pupil=tag.hasUUID("Pupil")?tag.getUUID("Pupil"):null;phase=Phase.NONE;ticks=elapsed=0;reachedTurn=false;loadGrace=40;
        harbor=tag.contains("Harbor")?BlockPos.of(tag.getLong("Harbor")):null;
        if(tag.getBoolean("InterruptedTrip"))set(Phase.RETURN);
    }
}
