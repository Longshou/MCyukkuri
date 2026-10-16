package io.github.longshou.mcyukkuri.subspecies;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.Vec3;

/** Contact-driven refuge pressure. A reservation has no force; only bodies actually aboard can push each other. */
final class HatBoatCrowding {
    private static final class Balance {
        long since,until,touched=Long.MIN_VALUE;
        long verticalAt=Long.MIN_VALUE;
        int contacts,mode;
        UUID pusher;
        Vec3 push=Vec3.ZERO;
        double falling;
    }
    private final HatBoatEntity boat;
    private final Map<UUID,Balance> riders=new HashMap<>();
    private boolean contesting,dirty;
    private long guardAt;
    HatBoatCrowding(HatBoatEntity boat){this.boat=boat;}
    private long now(){return boat.level().getGameTime();}
    private Balance entry(Entity rider){return riders.computeIfAbsent(rider.getUUID(),id->{var b=new Balance();b.since=now()-Math.floorMod(id.hashCode(),13);dirty=true;return b;});}
    void emergency(YukkuriEntity child){entry(child).until=now()+100;dirty=true;}
    boolean emergency(UUID child){var b=riders.get(child);return b!=null&&b.until>now();}
    boolean contesting(){return contesting;}
    private Balance display(Entity rider){
        if(!boat.level().isClientSide)return riders.get(rider.getUUID());
        for(var raw:boat.crowdingData().getList("R",Tag.TAG_COMPOUND)){
            var tag=(CompoundTag)raw;
            if(tag.hasUUID("Id")&&tag.getUUID("Id").equals(rider.getUUID()))return read(tag);
        }
        return null;
    }
    String state(Entity rider){
        var b=display(rider);
        if(rider.getUUID().equals(boat.ownerYukkuri()))return (boat.level().isClientSide?boat.crowdingData().getBoolean("Contesting"):contesting)?"boat_crowd_guard":"idle";
        if(b!=null&&b.mode==3)return "hat_slipping";
        if(b!=null&&b.mode==2)return "hat_jostle";
        if(b!=null&&b.mode==4)return "hat_scramble";
        return rider.getVehicle()==boat&&boat.rocking()?"hat_stumble":"idle";
    }
    long phaseStart(Entity rider){
        if(rider.getUUID().equals(boat.ownerYukkuri()))return boat.level().isClientSide?boat.crowdingData().getLong("GuardAt"):guardAt;
        var b=display(rider);return (b==null?boat.motionStartTick():b.since)-Math.floorMod(rider.getUUID().hashCode(),13);
    }
    UUID pusher(Entity rider){var b=display(rider);return b==null?null:b.pusher;}
    float roll(Entity rider,float partial){
        if(rider.getUUID().equals(boat.ownerYukkuri()))return 0;
        var b=display(rider);double age=now()+partial-phaseStart(rider),phase=Math.floorMod(rider.getUUID().hashCode(),97)*Math.PI*2/97;
        double value=rider.getVehicle()==boat&&boat.rocking()?-1.3*boat.motionRoll(partial)
                +Math.sin(age*.72+phase)*(boat.onBrim(rider)?4:2)*boat.rockingAmplitude()/12:0;
        if(b!=null&&b.mode>=2)value+=Math.copySign(Math.min(16,b.contacts*.7),b.push.x==0?1:b.push.x)+Math.sin(age*.9+phase)*2;
        return (float)Math.clamp(value,-24,24);
    }
    float pitch(Entity rider,float partial){
        if(rider.getUUID().equals(boat.ownerYukkuri()))return 0;
        var b=display(rider);double age=now()+partial-phaseStart(rider),phase=Math.floorMod(rider.getUUID().hashCode(),71)*Math.PI*2/71;
        double value=rider.getVehicle()==boat&&boat.rocking()?-boat.motionPitch(partial)+Math.sin(age*.65+phase)*2:0;
        if(b!=null&&b.mode>=2)value+=-4+Math.sin(age*.8+phase)*2;
        return (float)Math.clamp(value,-12,12);
    }
    private void mode(Balance b,int mode){if(b.mode!=mode){b.mode=mode;b.since=now();dirty=true;}}
    private boolean alarm(YukkuriEntity parent){
        if(parent.isPanicking()||boat.flooding()>0)return true;
        var attacker=parent.getLastHurtByMob();
        if(attacker!=null&&attacker.isAlive()&&parent.distanceToSqr(attacker)<=64)return true;
        return !boat.level().getEntitiesOfClass(LivingEntity.class,parent.getBoundingBox().inflate(7,3,7),
                e->e!=parent&&e.isAlive()&&(e instanceof Enemy||e instanceof YukkuriEntity other&&other.getVariant().isPredator())
                        &&parent.hasLineOfSight(e)).isEmpty();
    }
    void tick(){
        boolean wasContesting=contesting;
        contesting=false;
        if(boat.level().isClientSide||!boat.isAfloat()||!(boat.getControllingPassenger() instanceof YukkuriEntity parent))return;
        var children=boat.getPassengers().stream().filter(YukkuriEntity.class::isInstance).map(YukkuriEntity.class::cast)
                .filter(y->y!=parent).toList();
        boolean danger=alarm(parent)||children.stream().anyMatch(y->y.isPanicking()||emergency(y.getUUID()));
        var actual=new ArrayList<>(children);
        // Identity order varies who reaches an actual contact first, without a global age/smallest-child eviction rule.
        actual.sort(java.util.Comparator.comparingInt(y->y.getUUID().hashCode()));
        for(var child:actual){
            var b=entry(child);
            if(b.touched<now()-2){b.contacts=0;b.pusher=null;if(b.mode!=4)mode(b,boat.rocking()?1:0);}
            if(danger&&!child.isNoAi()&&!child.isCommandPlaying()&&!child.operations().active()
                    &&boat.crowdMovable(child)&&(emergency(child.getUUID())||child.isPanicking()||boat.onBrim(child))){
                var from=boat.toLocal(child.position());var target=boat.toLocal(parent.position());
                double safe=boat.crowdRadius(child)+boat.crowdRadius(parent)+.25;
                if(from.subtract(target).horizontalDistance()>safe||from.y>=HatVessel.FREEBOARD-.1){
                    if(b.contacts==0)mode(b,4);
                    var direction=target.subtract(from).multiply(1,0,1).normalize();
                    attempt(child,direction.scale(.035),actual,parent,new HashSet<>(),0,null);
                }else if(b.mode==4)mode(b,boat.rocking()?1:0);
            }
            else if(b.mode==4)mode(b,boat.rocking()?1:0);
            if(b.falling<0&&boat.crowdMovable(child))move(child,Vec3.ZERO,parent,null);
            if(b.contacts>=4)contesting=true;
        }
        if(contesting&&!wasContesting){guardAt=now();dirty=true;parent.showReaction("surprise","wait_family",40);}
        if(contesting!=wasContesting)dirty=true;
        if(riders.entrySet().removeIf(e->children.stream().noneMatch(y->y.getUUID().equals(e.getKey()))&&e.getValue().until<now()-200))dirty=true;
        if(dirty&&boat.tickCount%4==0){boat.syncCrowding();dirty=false;}
    }
    private boolean attempt(YukkuriEntity actor,Vec3 delta,List<YukkuriEntity> children,YukkuriEntity parent,
                            HashSet<UUID> visited,int depth,UUID responsible){
        if(depth>4||!visited.add(actor.getUUID())||!boat.crowdMovable(actor))return false;
        var at=boat.toLocal(actor.position());var proposed=at.add(delta);var centre=boat.toLocal(parent.position());
        double parentClear=boat.crowdRadius(parent)+boat.crowdRadius(actor);
        if(proposed.subtract(centre).horizontalDistance()<parentClear){
            var normal=at.subtract(centre).multiply(1,0,1).normalize();
            if(normal.lengthSqr()<.01)normal=new Vec3(0,0,-1);
            var tangent=new Vec3(-normal.z,0,normal.x);
            double sign=(actor.getUUID().hashCode()&1)==0?1:-1;
            delta=tangent.scale(delta.horizontalDistance()*sign);proposed=at.add(delta);
        }
        for(var other:children)if(other!=actor&&other.getVehicle()==boat){
            var where=boat.toLocal(other.position());
            if(at.y>=where.y+other.getBbHeight()/boat.unit()-.1||at.y+actor.getBbHeight()/boat.unit()<=where.y+.1)continue;
            double distance=proposed.subtract(where).horizontalDistance(),clear=boat.crowdRadius(actor)+boat.crowdRadius(other);
            if(distance>clear+.04)continue;
            double current=at.subtract(where).horizontalDistance();
            if(distance>=current+.002)continue; // Leaving an existing compressed contact is always allowed.
            var direction=where.subtract(at).multiply(1,0,1).normalize();
            if(direction.lengthSqr()<.01)direction=delta.normalize();
            touch(actor,other,direction);touch(other,actor,direction);
            if(entry(other).contacts<12)return false;
            if(!attempt(other,direction.scale(Math.min(.075,.035+entry(other).contacts*.001)),children,parent,visited,depth+1,actor.getUUID()))return false;
        }
        return move(actor,delta,parent,responsible);
    }
    private void touch(YukkuriEntity child,YukkuriEntity pusher,Vec3 force){
        var b=entry(child);
        if(b.touched!=now()){
            b.contacts=b.touched==now()-1?b.contacts+1:1;b.touched=now();
            if(b.contacts==1)child.showReaction("teary","afraid",24);
        }
        b.pusher=pusher.getUUID();b.push=force;mode(b,2);
        dirty=true;
    }
    private boolean move(YukkuriEntity child,Vec3 delta,YukkuriEntity parent,UUID pusher){
        var at=boat.toLocal(child.position());var next=at.add(delta);var b=entry(child);
        double support=HatVessel.surface(boat.mode(),next.x,next.z);
        if(!Double.isFinite(support)){
            // No ordinary growth/shake branch can enter here: a real sustained body contact must be the cause.
            if(pusher==null||b.contacts<12)return false;
            mode(b,3);b.pusher=pusher;b.push=delta;
            boat.crowdFall(child,next,delta,pusher);return true;
        }
        if(support>at.y+.08)return false; // Pressure cannot grant a newborn a step-climbing/jump ability.
        for(var berth:boat.cargoBerths())if(Math.abs(at.y-berth.position().y)<child.getBbHeight()/boat.unit()
                &&next.subtract(berth.position()).horizontalDistance()<boat.crowdRadius(child)+berth.radius())return false;
        if(at.y>support+.04){
            if(b.verticalAt!=now()){
                b.verticalAt=now();b.falling=Math.max(-.18/boat.unit(),b.falling-.04/boat.unit());
                next=new Vec3(next.x,Math.max(support,at.y+b.falling),next.z);
            }else next=new Vec3(next.x,at.y,next.z);
        }
        else {b.falling=0;next=new Vec3(next.x,Math.max(at.y,support),next.z);}
        boat.crowdPosition(child,next);dirty=true;return true;
    }
    CompoundTag save(){
        var tag=new CompoundTag();var list=new ListTag();
        for(var e:riders.entrySet()){
            var b=e.getValue();var r=new CompoundTag();r.putUUID("Id",e.getKey());r.putLong("Since",b.since);r.putLong("Until",b.until);
            r.putLong("Touched",b.touched);r.putInt("Contacts",b.contacts);r.putInt("Mode",b.mode);
            if(b.pusher!=null)r.putUUID("Pusher",b.pusher);r.putDouble("PushX",b.push.x);r.putDouble("PushZ",b.push.z);r.putDouble("Falling",b.falling);list.add(r);
        }
        tag.put("R",list);tag.putBoolean("Contesting",contesting);tag.putLong("GuardAt",guardAt);return tag;
    }
    private Balance read(CompoundTag r){
        var b=new Balance();b.since=r.getLong("Since");b.until=r.getLong("Until");b.touched=r.getLong("Touched");
        b.contacts=Math.clamp(r.getInt("Contacts"),0,200);b.mode=Math.clamp(r.getInt("Mode"),0,4);
        b.pusher=r.hasUUID("Pusher")?r.getUUID("Pusher"):null;b.push=new Vec3(r.getDouble("PushX"),0,r.getDouble("PushZ"));b.falling=r.getDouble("Falling");return b;
    }
    void load(CompoundTag tag){
        riders.clear();contesting=false;dirty=true;guardAt=tag.getLong("GuardAt");
        for(var raw:tag.getList("R",Tag.TAG_COMPOUND)){
            var r=(CompoundTag)raw;if(!r.hasUUID("Id"))continue;
            var b=read(r);b.contacts=0;b.touched=Long.MIN_VALUE;b.mode=0;
            b.until=Math.min(now()+100,b.until);riders.put(r.getUUID("Id"),b);
        }
    }
}
