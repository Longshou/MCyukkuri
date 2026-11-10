package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.motor.Action;
import io.github.longshou.mcyukkuri.motor.BodyAbility;
import io.github.longshou.mcyukkuri.motor.MotorOwner;
import io.github.longshou.mcyukkuri.motor.Outcome;
import io.github.longshou.mcyukkuri.motor.action.Bash;
import io.github.longshou.mcyukkuri.motor.action.Face;
import io.github.longshou.mcyukkuri.motor.action.PaneOpening;
import io.github.longshou.mcyukkuri.motor.action.Walk;
import io.github.longshou.mcyukkuri.motor.geom.Effort;
import io.github.longshou.mcyukkuri.social.EcologyPresentation;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.Tags;

/** The land family search's one stop at a visible barrier. Only Walk, Face and Bash drive either body. */
final class GlassVigil implements MotorOwner {
    private enum Phase { APPROACH, WAIT, SQUARE, RAM, CROSS, FAREWELL }
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
    boolean finishing() { return active() && (phase==Phase.RAM || phase==Phase.CROSS || phase==Phase.FAREWELL); }
    String activity() {
        if (phase==Phase.FAREWELL) return Behaviors.INDEPENDENCE_FAREWELL;
        return phase==Phase.WAIT ? Behaviors.CALL_LISTEN : Behaviors.SEARCH_FAMILY;
    }

    boolean start(YukkuriEntity target) {
        var hit=FamilySeparation.glassBetween(parent,parent.getEyePosition(),target);
        if (hit==null || parent.life().reachable(target.blockPosition(),false)) return false;
        child=target; glass=hit.getBlockPos().immutable();
        family.call().stop();
        SurfaceApproach.halt(parent);
        Vec3 stand=nearestStand(parent,target,glass);
        if (stand==null) { farewell("no_glass_stand"); return true; }
        var normal=Direction.getNearest(stand.x-glass.getX()-.5,0,stand.z-glass.getZ()-.5);
        opening=parent.level().getBlockState(glass).is(Tags.Blocks.GLASS_PANES) ? PaneOpening.at(parent,glass,normal) : null;
        GameplayLog.event(parent,"family","glass approach child={} block={} stand={}",child.getUUID(),glass,
                GameplayLog.at(opening==null?stand:opening.stand()));
        run(Phase.APPROACH,Walk.stand(opening==null?stand:opening.stand(),1));
        return true;
    }

    Vec3 childStand(YukkuriEntity target) { return nearestStand(target,parent,glass); }

    private static Vec3 nearestStand(YukkuriEntity body,YukkuriEntity kin,BlockPos glass) {
        var centre=Vec3.atBottomCenterOf(glass);
        var bounds=body.level().getBlockState(glass).getCollisionShape(body.level(),glass).bounds();
        Vec3 closest=null;
        double best=Double.POSITIVE_INFINITY;
        for (var normal:Direction.Plane.HORIZONTAL) {
            var out=Vec3.atLowerCornerOf(normal.getNormal());
            if (kin.position().subtract(centre).dot(out)>=0) continue;
            double width=normal.getAxis()==Direction.Axis.X?bounds.getXsize():bounds.getZsize();
            var stand=SurfaceApproach.restingFloor(body,centre.add(out.scale((width+body.getBbWidth())/2+Walk.STAND_ARRIVED)));
            if (stand==null || body.distanceToSqr(stand)>=best) continue;
            var hit=FamilySeparation.glassBetween(body,stand.add(0,body.getEyeY()-body.getY(),0),kin);
            if (hit!=null && glass.equals(hit.getBlockPos()) && SurfaceApproach.canApproach(body,stand)) {
                closest=stand; best=body.distanceToSqr(stand);
            }
        }
        return closest;
    }

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
                if (phase==Phase.SQUARE || phase==Phase.RAM) decline(outcome);
                else if (phase==Phase.CROSS) { release(); return false; }
                else farewell("stand_blocked");
                return true;
            }
            switch (phase) {
                case APPROACH -> arrive(now);
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
        run(Phase.SQUARE,new Face(Bash.squareYaw(parent,glass)));
    }

    private void run(Phase next,Action move) {
        phase=next;
        if (parent.motor().run(this,move)) action=move;
        else if (next==Phase.CROSS) release();
        else if (next==Phase.APPROACH) farewell("stand_refused");
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
