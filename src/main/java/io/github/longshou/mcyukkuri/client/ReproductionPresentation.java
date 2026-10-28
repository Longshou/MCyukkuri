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

/** Shared adult-contact presentation. The server owns courtship, pinning and every consequence. */
final class ReproductionPresentation {
    private ReproductionPresentation() {}
    private static final int EXTEND_TICKS=5, RETRACT_TICKS=13, HISTORY_TICKS=40;
    private static final Map<YukkuriView,Track> TRACKS=new WeakHashMap<>();
    private static WeakReference<Level> level=new WeakReference<>(null);
    private static long lastPrune=Long.MIN_VALUE;

    record Anatomy(String part,float amount) {}
    private static final class Track {
        String part,target;
        float from;
        long changed,seen;
        Track(String part,String target,float from,long changed,long seen) {
            this.part=part;this.target=target;this.from=from;this.changed=changed;this.seen=seen;
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

    static Anatomy anatomy(YukkuriView entity) {
        long tick=entity.level().getGameTime();prune(entity,tick);
        String wanted=wanted(entity);var old=TRACKS.get(entity);
        if(old==null&&wanted==null)return null;
        if(old!=null&&old.part!=null&&old.target==null&&old.from<=0&&wanted==null) {
            TRACKS.remove(entity);return null;
        }
        if(old==null)old=new Track(wanted,wanted,0,tick,tick);
        if(!Objects.equals(old.target,wanted)) {
            old.from=sample(old,tick);old.target=wanted;old.changed=tick;
        }
        float amount=sample(old,tick);
        if(amount<=.0001f&&old.part!=null&&!Objects.equals(old.part,old.target)) {
            old=new Track(old.target,old.target,0,tick,tick);amount=0;
        }
        old.seen=tick;
        if(old.part==null||amount<=0) {
            if(old.target==null)TRACKS.remove(entity);else TRACKS.put(entity,old);
            return null;
        }
        TRACKS.put(entity,old);return new Anatomy(old.part,amount);
    }

    private static float sample(Track track,long tick) {
        if(track.part==null)return 0;
        float elapsed=Math.max(0,tick-track.changed);
        if(track.target==null||!track.part.equals(track.target))
            return track.from*Mth.clamp(1-elapsed/RETRACT_TICKS,0,1);
        float q=Mth.clamp(elapsed/EXTEND_TICKS,0,1);q=q*q*(3-2*q);
        return Mth.lerp(q,track.from,1);
    }

    /** Small shared-clock body compression; actual positions still come from server navigation. */
    static void pose(YukkuriView entity,float partialTick,PoseStack pose) {
        var court=entity.courtshipState();
        if(!court.getString("Phase").equals("CONTACT")||!entity.getBehaviorId().equals(Behaviors.PAIRING))return;
        float ticks=Math.max(0,entity.level().getGameTime()-court.getLong("Start")+partialTick);
        float[] value=AgitationPresentation.sample(court.getString("Role").equals("PENI")?"pair_peni":"pair_mamu",ticks%20/20);
        if(value==null)return;
        var model=YukkuriModel.get(entity.assetId());
        pose.translate(0,model.presentationLift(value,entity.hasVisibleAccessory(),entity.visibleHairLoss())*entity.growthScale(),0);
        pose.mulPose(Axis.ZP.rotationDegrees(value[2]));
        pose.mulPose(Axis.YP.rotationDegrees(value[1]));
        pose.mulPose(Axis.XP.rotationDegrees(value[0]));
        pose.scale(value[3],value[4],value[5]);
    }

    static String expression(YukkuriView entity,String otherwise) {
        var court=entity.courtshipState();
        if(court.getString("Phase").equals("CONTACT")&&entity.getBehaviorId().equals(Behaviors.PAIRING))
            return court.getString("Role").equals("PENI")?"flushed_tongue":"satisfied";
        return otherwise;
    }

    private static void prune(YukkuriView entity,long tick) {
        if(level.get()!=entity.level()) {TRACKS.clear();level=new WeakReference<>(entity.level());lastPrune=Long.MIN_VALUE;}
        if(lastPrune==tick)return;
        TRACKS.entrySet().removeIf(e->e.getKey().level()!=entity.level()||tick<e.getValue().seen||tick-e.getValue().seen>HISTORY_TICKS);
        lastPrune=tick;
    }
}
