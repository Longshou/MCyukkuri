package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriView;
import java.lang.ref.WeakReference;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** Shared adult-contact presentation. The server owns courtship, pinning and every consequence. */
final class ReproductionPresentation {
    private ReproductionPresentation() {}
    private static final int EXTEND_TICKS=5, RETRACT_TICKS=13, HISTORY_TICKS=40;
    // Leave .8 model units of rest clearance for the authored pushes. Each side closes at most a quarter block.
    private static final double CONTACT_CLEARANCE=.8/16, MAX_OFFSET=.25;
    private static final Map<YukkuriView,Track> TRACKS=new WeakHashMap<>();
    private static WeakReference<Level> level=new WeakReference<>(null);
    private static long lastPrune=Long.MIN_VALUE;

    record Anatomy(String part,float amount) {}
    record Frame(Anatomy anatomy,double offset) {}
    private record Contact(boolean rear,int partner,String branch,long start,int duration,boolean loop) {
        float[] cue(double now) {
            float ticks=(float)Math.max(0,now-start);
            return AgitationPresentation.sample(branch,(loop?ticks%duration:ticks)/duration);
        }
    }
    private static final class Track {
        String part,target;
        float from;
        long changed,seen;
        boolean closing;
        double offset,fromOffset,contactChanged;
        Track(String part,long tick) {
            this.part=part;target=part;changed=tick;
        }
    }

    private static String wanted(YukkuriView entity) {
        if(!entity.adult()||entity.isDeadOrDying()||entity.isPassenger()||entity.isRemoved())return null;
        String phase=entity.agitationState().getString("Phase");
        if(phase.equals("ALICE_WATCH")||phase.equals("ALICE_APPROACH")||phase.equals("ALICE_ACT"))return "peni";
        if(phase.equals("HELD"))return "mamu";
        var court=entity.courtshipState();
        return court.getString("Phase").equals("CONTACT")&&entity.getBehaviorId().equals(Behaviors.PAIRING)
                ?court.getString("Role").toLowerCase(Locale.ROOT):null;
    }

    private static Contact contact(YukkuriView entity) {
        var court=entity.courtshipState();
        var agitation=entity.agitationState();
        return switch(entity.getBehaviorId()) {
            case Behaviors.PAIRING -> {
                if(!court.getString("Phase").equals("CONTACT"))yield null;
                boolean rear=court.getString("Role").equals("MAMU");
                yield new Contact(rear,court.getInt("TargetId"),rear?"pair_mamu":"pair_peni",court.getLong("Start"),court.getInt("Required"),false);
            }
            case Behaviors.ALICE_ACT -> agitation.getString("Phase").equals("ALICE_ACT")
                    ?new Contact(false,agitation.getInt("TargetId"),"act",agitation.getLong("Start"),AgitationPresentation.PIN_CYCLE_TICKS,true):null;
            case Behaviors.RAPEM_HELD -> agitation.getString("Phase").equals("HELD")
                    ?new Contact(true,agitation.getInt("TargetId"),"pinned",agitation.getLong("Start"),AgitationPresentation.PIN_CYCLE_TICKS,true):null;
            default -> null;
        };
    }

    private static Double contactOffset(YukkuriView entity,float partialTick) {
        var contact=contact(entity);
        if(contact==null)return null;
        var peer=entity.yukkuriView(contact.partner());
        if(peer==null||peer.isDeadOrDying())return null;
        var other=contact(peer);
        if(other==null||other.partner()!=entity.getId()||other.rear()==contact.rear())return null;
        var model=YukkuriModel.get(entity.assetId());var peerModel=YukkuriModel.get(peer.assetId());
        double scale=entity.growthScale()*entity.getScale(),peerScale=peer.growthScale()*peer.getScale();
        double rest=model.bodyDepth(contact.rear())*scale+peerModel.bodyDepth(other.rear())*peerScale;
        var delta=peer.getPosition(partialTick).subtract(entity.getPosition(partialTick)).multiply(1,0,1);
        var toward=delta.normalize();
        double now=entity.level().getGameTime()+(double)partialTick;
        // Keep a separating plane for the widest reaches of the authored poses, including cheek turns.
        double posed=model.bodyReach(contact.cue(now),localBearing(entity,partialTick,toward))*scale
                +peerModel.bodyReach(other.cue(now),localBearing(peer,partialTick,toward.reverse()))*peerScale;
        double close=Math.clamp((delta.horizontalDistance()-Math.max(rest+CONTACT_CLEARANCE,posed))/2,0,MAX_OFFSET);
        return contact.rear()?close:-close;
    }

    private static Vec3 localBearing(YukkuriView entity,float partialTick,Vec3 toward) {
        float yaw=Mth.rotLerp(partialTick,entity.previousBodyYaw(),entity.bodyYaw());
        return toward.yRot((yaw-180)*Mth.DEG_TO_RAD);
    }

    static Frame frame(YukkuriView entity,float partialTick) {
        long tick=entity.level().getGameTime();prune(entity,tick);
        String wanted=wanted(entity);
        Double offset=wanted==null?null:contactOffset(entity,partialTick);
        var track=TRACKS.get(entity);
        if(track==null) {
            if(wanted==null)return new Frame(null,0);
            track=new Track(wanted,tick);
            TRACKS.put(entity,track);
        }
        if(!Objects.equals(track.target,wanted)) {
            track.from=sample(track,tick);track.target=wanted;track.changed=tick;
        }
        float amount=sample(track,tick);
        if(amount<=.0001f&&!Objects.equals(track.part,track.target)) {
            track.part=track.target;track.from=0;track.changed=tick;amount=0;
        }
        track.seen=tick;
        double now=tick+(double)partialTick;
        boolean closing=offset!=null;
        if(closing!=track.closing) {
            track.fromOffset=track.offset;track.closing=closing;track.contactChanged=now;
        }
        double mix=Math.clamp((now-track.contactChanged)/(closing?EXTEND_TICKS:RETRACT_TICKS),0,1);
        track.offset=Mth.lerp(mix*mix*(3-2*mix),track.fromOffset,closing?offset:0);
        if(track.part==null&&!closing&&mix==1)TRACKS.remove(entity);
        return new Frame(amount>0?new Anatomy(track.part,amount):null,track.offset);
    }

    private static float sample(Track track,long tick) {
        if(track.part==null)return 0;
        float elapsed=Math.max(0,tick-track.changed);
        if(track.target==null||!track.part.equals(track.target))
            return track.from*Mth.clamp(1-elapsed/RETRACT_TICKS,0,1);
        float q=Mth.clamp(elapsed/EXTEND_TICKS,0,1);q=q*q*(3-2*q);
        return Mth.lerp(q,track.from,1);
    }

    /** Shared-clock rub, pushes and climax over the whole required contact; actual positions still come from server navigation. */
    static void pose(YukkuriView entity,float partialTick,PoseStack pose) {
        if(!entity.getBehaviorId().equals(Behaviors.PAIRING))return;
        var contact=contact(entity);
        if(contact==null)return;
        float[] value=contact.cue(entity.level().getGameTime()+(double)partialTick);
        var model=YukkuriModel.get(entity.assetId());
        pose.translate(0,model.presentationLift(value,entity.hasVisibleAccessory(),entity.visibleHairLoss())*entity.growthScale(),0);
        pose.mulPose(Axis.ZP.rotationDegrees(value[2]));
        pose.mulPose(Axis.YP.rotationDegrees(value[1]));
        pose.mulPose(Axis.XP.rotationDegrees(value[0]));
        pose.scale(value[3],value[4],value[5]);
    }

    private static void prune(YukkuriView entity,long tick) {
        if(level.get()!=entity.level()) {TRACKS.clear();level=new WeakReference<>(entity.level());lastPrune=Long.MIN_VALUE;}
        if(lastPrune==tick)return;
        TRACKS.entrySet().removeIf(e->e.getKey().level()!=entity.level()||tick<e.getValue().seen||tick-e.getValue().seen>HISTORY_TICKS);
        lastPrune=tick;
    }
}
