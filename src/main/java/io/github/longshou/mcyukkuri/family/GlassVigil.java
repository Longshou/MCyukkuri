package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.GlassSight;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.motor.Action;
import io.github.longshou.mcyukkuri.motor.BodyAbility;
import io.github.longshou.mcyukkuri.motor.MotorOwner;
import io.github.longshou.mcyukkuri.motor.Outcome;
import io.github.longshou.mcyukkuri.motor.action.Bash;
import io.github.longshou.mcyukkuri.motor.action.Face;
import io.github.longshou.mcyukkuri.motor.action.GlassPress;
import io.github.longshou.mcyukkuri.motor.action.PaneOpening;
import io.github.longshou.mcyukkuri.motor.action.Walk;
import io.github.longshou.mcyukkuri.motor.geom.Effort;
import io.github.longshou.mcyukkuri.social.EcologyPresentation;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.Tags;

/** The land family search's one stop at a visible barrier. The motor owns the press and the ram. */
final class GlassVigil implements MotorOwner {
    private enum Phase { APPROACH, PRESS, WAIT, RAM_APPROACH, SQUARE, RAM, CROSS, FAREWELL }
    private final FamilyLife family;
    private final YukkuriEntity parent;
    private YukkuriEntity child;
    private BlockPos glass;
    private PaneOpening opening;
    private Phase phase;
    private Action action;
    private long deadline, ramAt;
    private String farewellReason;

    GlassVigil(FamilyLife family) { this.family=family; parent=family.entity(); }
    @Override public String ownerId() { return Behaviors.WAIT_CHILD; }
    @Override public boolean active() { return child!=null; }
    boolean tracks(YukkuriEntity target) { return child==target; }
    boolean callingTo(YukkuriEntity target) { return child==target && phase==Phase.WAIT; }
    boolean finishing() { return active() && (phase==Phase.RAM_APPROACH || phase==Phase.RAM || phase==Phase.CROSS || phase==Phase.FAREWELL); }
    String activity() {
        if (phase==Phase.FAREWELL) return Behaviors.INDEPENDENCE_FAREWELL;
        return phase==Phase.WAIT ? Behaviors.CALL_LISTEN : Behaviors.SEARCH_FAMILY;
    }

    boolean start(YukkuriEntity target) {
        var hit=GlassSight.glassBetween(parent,parent.getEyePosition(),target);
        if (hit==null || parent.life().reachable(target.blockPosition(),false)) return false;
        child=target; glass=hit.getBlockPos().immutable();
        family.call().stop();
        SurfaceApproach.halt(parent);
        Vec3 stand=GlassSight.stand(parent,target,glass);
        if (stand==null) { farewell("no_glass_stand"); return true; }
        var normal=Direction.getNearest(stand.x-glass.getX()-.5,0,stand.z-glass.getZ()-.5);
        opening=parent.level().getBlockState(glass).is(Tags.Blocks.GLASS_PANES) ? PaneOpening.at(parent,glass,normal) : null;
        GameplayLog.event(parent,"family","glass approach child={} block={} stand={}",child.getUUID(),glass,GameplayLog.at(stand));
        run(Phase.APPROACH,new Walk(stand));
        return true;
    }

    Vec3 childStand(YukkuriEntity target) { return GlassSight.stand(target,parent,glass); }

    /** True while this stop owns the search. On an open route the original search takes over. */
    boolean tick() {
        long now=family.level().getGameTime();
        if (phase==Phase.FAREWELL) {
            if (now>=deadline) family.stop();
            return true;
        }
        if (action!=null && parent.motor().current()==action) return true;
        if (phase!=Phase.CROSS && parent.hasLineOfSight(child) && parent.life().reachable(child.blockPosition(),false)
                && (phase!=Phase.RAM || !(parent.motor().last() instanceof Outcome.Done))) {
            GameplayLog.event(parent,"family","glass route cleared child={} block={}",child.getUUID(),glass);
            release();
            return false;
        }
        if (action!=null) {
            var outcome=parent.motor().last();
            action=null;
            if (!(outcome instanceof Outcome.Done)) {
                if (phase==Phase.RAM_APPROACH || phase==Phase.SQUARE || phase==Phase.RAM) decline(outcome);
                else if (phase==Phase.CROSS) { release(); return false; }
                else if (phase==Phase.PRESS) arrive(now);
                else farewell("stand_blocked");
                return true;
            }
            switch (phase) {
                case APPROACH -> {
                    // At its stand with the child just beyond the pane: it squashes its face on the glass once, then calls.
                    var press=GlassPress.toward(parent,child);
                    if (press==null) arrive(now);
                    else run(Phase.PRESS,press);
                }
                case PRESS -> arrive(now);
                case RAM_APPROACH -> run(Phase.SQUARE,new Face(Bash.squareYaw(parent,glass)));
                case SQUARE -> run(Phase.RAM,new Bash(glass,new Bash.Damage(0,0),parent.getId()));
                case RAM -> {
                    GameplayLog.event(parent,"family","glass pane opened child={} block={}",child.getUUID(),glass);
                    run(Phase.CROSS,new Walk(Vec3.atBottomCenterOf(opening.beyond())));
                }
                case CROSS -> {
                    GameplayLog.event(parent,"family","glass crossed child={} block={}",child.getUUID(),glass);
                    release();
                    return false;
                }
                default -> throw new IllegalStateException("Glass action finished in "+phase);
            }
            return true;
        }
        if (now>=deadline || family.level().isNight()) {
            farewell(now>=deadline?"vigil_elapsed":"night");
        } else if (opening!=null && now>=ramAt) {
            chooseRam();
        } else {
            family.call().tickGlass(child);
        }
        return true;
    }

    private void arrive(long now) {
        phase=Phase.WAIT;
        var p=parent.life().personality();
        // Project design: 20–70 seconds. Bonds and composure add up to 40 seconds, with up to 10 seconds of individual variation.
        int duration=400+3*p.attachment()+3*p.friendliness()+2*p.selfControl()+parent.getRandom().nextInt(201);
        deadline=now+duration;
        // Leave a speaking turn and an approach for the child. Boldness moves the decision between 10 and 30 seconds.
        ramAt=now+200+4*(100-p.boldness());
        GameplayLog.event(parent,"family","glass vigil started child={} block={} duration={} attachment={} friendliness={} selfControl={}",
                child.getUUID(),glass,duration,p.attachment(),p.friendliness(),p.selfControl());
        family.call().beginGlass(child);
    }

    private void chooseRam() {
        var permission=Bash.allowed(parent,glass);
        var state=parent.level().getBlockState(glass);
        double chance=Effort.bashChance(parent.life().personality().boldness(),BodyAbility.bashPower(parent),
                state.getDestroySpeed(parent.level(),glass));
        boolean chosen=permission.ok() && parent.getRandom().nextDouble()<chance;
        GameplayLog.event(parent,"family","glass pane choice child={} block={} chosen={} chance={} reason={}",
                child.getUUID(),glass,chosen,chance,permission.detail());
        if (!chosen) { opening=null; return; }
        family.call().stop();
        parent.speak("glass_ram");
        run(Phase.RAM_APPROACH,Walk.stand(opening.stand(),1));
    }

    private void run(Phase next,Action move) {
        phase=next;
        if (parent.motor().run(this,move)) action=move;
        else if (next==Phase.CROSS) release();
        else if (next==Phase.APPROACH) farewell("stand_refused");
        else if (next==Phase.PRESS) arrive(family.level().getGameTime());
        else decline(parent.motor().refusal());
    }

    private void decline(Outcome outcome) {
        GameplayLog.event(parent,"family","glass pane declined child={} block={} outcome={}",child.getUUID(),glass,outcome);
        opening=null; phase=Phase.WAIT;
    }

    private void farewell(String reason) {
        family.call().stop();
        phase=Phase.FAREWELL; farewellReason=reason;
        int length=EcologyPresentation.duration(Behaviors.INDEPENDENCE_FAREWELL);
        deadline=family.level().getGameTime()+length;
        parent.forceReaction("teary","",length);
        boolean spoken=parent.speak("give_up_search");
        GameplayLog.event(parent,"family","glass farewell child={} block={} reason={} spoken={}",child.getUUID(),glass,reason,spoken);
    }

    void stop() {
        if (!active()) return;
        if (child.isAlive() && parent.isAlive() && !parent.hasLineOfSight(child) && phase!=Phase.CROSS) {
            if (phase!=Phase.FAREWELL) farewell(parent.isPanicking()?"danger":parent.life().hasUrgentNeed()?"urgent_need":"interrupted");
            family.dismissAtGlass(child,farewellReason);
        }
        release();
    }

    private void release() {
        parent.motor().cancel(this,"glass_vigil_ended");
        family.call().stop();
        child=null; action=null; opening=null;
    }
}
