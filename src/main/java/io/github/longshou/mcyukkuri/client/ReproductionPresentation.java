package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriView;
import io.github.longshou.mcyukkuri.headwear.HatScale;
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
    // Allow for the idle and walking clips around the seam.
    private static final double ACCESSORY_CLEARANCE=.4/16;
    private static final float[] REST_POSE={0,0,0,1,1,1};
    private static final Map<YukkuriView,Track> TRACKS=new WeakHashMap<>();
    private static WeakReference<Level> level=new WeakReference<>(null);
    private static long lastPrune=Long.MIN_VALUE;

    record Anatomy(String part,float amount) {}
    record Frame(Anatomy anatomy,Vec3 offset) {}
    private record Spacing(Vec3 toward, double desired, double limit, boolean touching) {
        Vec3 keepClear(Vec3 offset) {
            return offset.subtract(toward.scale(Math.max(0,offset.dot(toward)-limit)));
        }
    }
    private record Contact(boolean rear,int partner,String branch,boolean attack) {
        boolean posed() { return branch!=null; }
        float[] cue() { return posed()?AgitationPresentation.sample(branch,0):REST_POSE; }
    }
    private static final class Track {
        String part,target;
        float from;
        long changed,seen;
        boolean closing;
        Vec3 offset=Vec3.ZERO,fromOffset=Vec3.ZERO;
        double contactChanged,accessoryAt;
        int accessoryPeer=-1;
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
            case Behaviors.PAIRING, Behaviors.PAIRING_ACCEPT -> {
                String phase=court.getString("Phase");
                if(phase.isEmpty())yield null;
                boolean rear=court.getString("Role").equals("MAMU");
                String branch=phase.equals("CONTACT")?(rear?"pair_mamu":"pair_peni"):null;
                yield new Contact(rear,court.getInt("TargetId"),branch,false);
            }
            case Behaviors.ALICE_ACT -> agitation.getString("Phase").equals("ALICE_ACT")
                    ?new Contact(false,agitation.getInt("TargetId"),"act",true):null;
            case Behaviors.RAPEM_HELD -> agitation.getString("Phase").equals("HELD")
                    ?new Contact(true,agitation.getInt("TargetId"),"pinned",true):null;
            default -> null;
        };
    }

    private static boolean brimAndBow(YukkuriView entity,YukkuriView peer,Contact contact) {
        if (contact.attack() || !entity.hasVisibleAccessory() || !peer.hasVisibleAccessory()
                || entity.subspeciesKind().hatProp()!=null || peer.subspeciesKind().hatProp()!=null) return false;
        var mother=contact.rear()?entity:peer;
        var father=contact.rear()?peer:entity;
        return mother.assetId().equals("reimu")&&father.assetId().equals("marisa");
    }

    private static Spacing spacing(YukkuriView entity,Track track,Contact contact,float partialTick) {
        int peerId=contact==null?track.accessoryPeer:contact.partner();
        if(peerId<0)return null;
        var peer=entity.yukkuriView(peerId);
        if(peer==null||peer.isDeadOrDying()||peer.isPassenger()||entity.isDeadOrDying()||entity.isPassenger())return null;
        var other=contact(peer);
        boolean paired=contact!=null&&other!=null&&other.partner()==entity.getId()&&other.rear()!=contact.rear();
        boolean touching=paired&&contact.posed()&&other.posed();
        double now=entity.level().getGameTime()+(double)partialTick;
        boolean ornaments;
        if(paired) {
            ornaments=brimAndBow(entity,peer,contact);
            track.accessoryPeer=ornaments?peerId:-1;
            if(ornaments)track.accessoryAt=now;
        } else {
            // Courtship sync can end before the bodies finish separating or their relief poses become visible.
            var peerTrack=TRACKS.get(peer);
            ornaments=peerTrack!=null&&peerTrack.accessoryPeer==entity.getId()
                    &&entity.hasVisibleAccessory()&&peer.hasVisibleAccessory()
                    &&(now-track.accessoryAt<=RETRACT_TICKS||relief(entity)||relief(peer));
        }
        if(!touching&&!ornaments)return null;
        var model=YukkuriModel.get(entity.assetId());var peerModel=YukkuriModel.get(peer.assetId());
        double scale=entity.growthScale()*entity.getScale(),peerScale=peer.growthScale()*peer.getScale();
        var delta=peer.getPosition(partialTick).subtract(entity.getPosition(partialTick)).multiply(1,0,1);
        var toward=delta.normalize();
        var bearing=localBearing(entity,partialTick,toward);
        var peerBearing=localBearing(peer,partialTick,toward.reverse());
        var cue=contact==null?releasePose(entity,now):contact.cue();
        var peerCue=other==null?releasePose(peer,now):other.cue();
        var reach=contactReach(model,entity,now,cue,bearing,touching,ornaments);
        var peerReach=contactReach(peerModel,peer,now,peerCue,peerBearing,touching,ornaments);
        double distance=delta.horizontalDistance();
        double limit=Double.POSITIVE_INFINITY;
        if(ornaments) {
            double accessories=reach.accessory()*scale+peerReach.accessory()*peerScale;
            limit=(distance-accessories-ACCESSORY_CLEARANCE)/2;
        }
        if(!touching)return new Spacing(toward,0,limit,false);
        double rest=model.bodyDepth(contact.rear())*scale+peerModel.bodyDepth(other.rear())*peerScale;
        // Keep a separating plane for the widest reaches of the authored poses, including cheek turns.
        double posed=reach.body()*scale+peerReach.body()*peerScale;
        double close=Math.clamp((distance-Math.max(rest+CONTACT_CLEARANCE,posed))/2,0,MAX_OFFSET);
        return new Spacing(toward,close,limit,true);
    }

    private static YukkuriModel.ContactReach contactReach(YukkuriModel model,YukkuriView entity,double now,float[] cue,
                                                         Vec3 bearing,boolean body,boolean accessory) {
        float elapsed=(float)Math.max(0,now-entity.getAnimationStartTick())/20;
        return model.contactReach(entity.getAnimationId(),elapsed,cue,bearing,HatScale.factor(entity.subspeciesFlags()),body,accessory);
    }

    private static boolean relief(YukkuriView entity) {
        return entity.getBehaviorId().equals(Behaviors.HEAT_RELIEF)
                && !entity.agitationState().getBoolean("ReliefBeatsDone");
    }

    private static float[] releasePose(YukkuriView entity,double now) {
        return relief(entity)?AgitationPresentation.sample("relief",(float)(now-entity.agitationState().getLong("Start"))/120):REST_POSE;
    }

    private static Vec3 localBearing(YukkuriView entity,float partialTick,Vec3 toward) {
        float yaw=Mth.rotLerp(partialTick,entity.previousBodyYaw(),entity.bodyYaw());
        return toward.yRot((yaw-180)*Mth.DEG_TO_RAD);
    }

    static Frame frame(YukkuriView entity,float partialTick) {
        long tick=entity.level().getGameTime();prune(entity,tick);
        String wanted=wanted(entity);
        var contact=contact(entity);
        var track=TRACKS.get(entity);
        if(track==null) {
            if(wanted==null&&contact==null)return new Frame(null,Vec3.ZERO);
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
        var spacing=spacing(entity,track,contact,partialTick);
        boolean closing=spacing!=null&&spacing.touching();
        if(closing!=track.closing) {
            track.fromOffset=track.offset;track.closing=closing;track.contactChanged=now;
        }
        double mix=Math.clamp((now-track.contactChanged)/(closing?EXTEND_TICKS:RETRACT_TICKS),0,1);
        Vec3 offset=closing?spacing.toward().scale(spacing.desired()):Vec3.ZERO;
        track.offset=track.fromOffset.lerp(offset,mix*mix*(3-2*mix));
        if(spacing!=null)track.offset=spacing.keepClear(track.offset);
        if(track.part==null&&spacing==null&&mix==1)TRACKS.remove(entity);
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

    /** The authored contact plays over the shared constant lean. */
    static void pose(YukkuriView entity,float partialTick,PoseStack pose) {
        if(!entity.getBehaviorId().equals(Behaviors.PAIRING))return;
        var contact=contact(entity);
        if(contact==null||!contact.posed())return;
        float[] value=contact.cue();
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
