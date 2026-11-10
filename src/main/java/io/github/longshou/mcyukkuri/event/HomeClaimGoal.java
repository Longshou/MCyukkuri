package io.github.longshou.mcyukkuri.event;

import javax.annotation.Nullable;
import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.motor.Action;
import io.github.longshou.mcyukkuri.motor.BodyAbility;
import io.github.longshou.mcyukkuri.motor.MotorOwner;
import io.github.longshou.mcyukkuri.motor.Outcome;
import io.github.longshou.mcyukkuri.motor.action.Bash;
import io.github.longshou.mcyukkuri.motor.action.Face;
import io.github.longshou.mcyukkuri.motor.action.PaneOpening;
import io.github.longshou.mcyukkuri.motor.action.Walk;
import io.github.longshou.mcyukkuri.motor.geom.Effort;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.Tags;

/** Keeps a claimant near its claimed site (or retreating), and lets it eat the claimed crops when hungry. */
public final class HomeClaimGoal extends Goal implements MotorOwner {
    private final YukkuriEntity entity;
    private BlockPos crop;
    private boolean homing;
    private int cooldown, elapsed;
    private UUID consideredClaim;
    // Keep the choice through interruptions; only a real refusal or failed route declines the pane.
    private final Map<BlockPos,Boolean> paneChoices = new HashMap<>();
    private PaneOpening opening;
    private Action action;
    private boolean active;
    @Override public String ownerId() { return Behaviors.HOME_CLAIM_RETURN; }
    @Override public boolean active() { return active; }
    @Override public void start() { active = true; }
    public HomeClaimGoal(YukkuriEntity entity) { this.entity=entity; setFlags(EnumSet.of(Flag.MOVE,Flag.LOOK)); }
    /** Read the already selected crop/return route; a long-lived claim alone never owns a pose. */
    @Nullable public String activity() {
        if (entity.life().mealTicks() > 0) return entity.life().activity();
        if (opening != null) return Behaviors.HOME_CLAIM_RETURN;
        if (crop != null) return Behaviors.HOME_CLAIM_FORAGE;
        if (!homing) return null;
        var claim = HomeClaim.of(entity);
        if (claim == null || !HomeClaim.away(entity, claim, true)) return null;
        return claim.stage == HomeClaimData.Stage.RETREAT ? Behaviors.HOME_CLAIM_RETREAT : Behaviors.HOME_CLAIM_RETURN;
    }

    @Override public boolean canUse() {
        var claim=HomeClaim.of(entity);
        if (claim==null || !HomeClaim.mayAct(entity)) return false;
        if (!claim.id.equals(consideredClaim)) { consideredClaim=claim.id; paneChoices.clear(); }
        if (cooldown<=0 && !entity.motor().heldByOther(ownerId()) && Bash.permission(entity).ok()) {
            boolean entering=claim.stage==HomeClaimData.Stage.GATHER && !entity.life().reachable(claim.site.pos(),false);
            boolean exiting=claim.stage==HomeClaimData.Stage.ENCLOSED || claim.stage==HomeClaimData.Stage.COLLAPSING;
            if (entering || exiting) {
                cooldown=40;
                for (var candidate:PaneOpening.find(entity,claim.site.pos(),exiting,entering)) {
                    Boolean previous=paneChoices.get(candidate.pane());
                    if (Boolean.FALSE.equals(previous)) continue;
                    var permission=Bash.allowed(entity,candidate.pane());
                    double chance=Effort.bashChance(entity.life().personality().boldness(),BodyAbility.bashPower(entity),
                            entity.level().getBlockState(candidate.pane()).getDestroySpeed(entity.level(),candidate.pane()));
                    boolean chosen=permission.ok() && (Boolean.TRUE.equals(previous) || entity.getRandom().nextDouble()<chance);
                    paneChoices.put(candidate.pane(),chosen);
                    GameplayLog.event(entity,"home","pane choice block={} chosen={} chance={} reason={} resumed={}",candidate.pane(),chosen,chance,permission.detail(),Boolean.TRUE.equals(previous));
                    if (chosen) { opening=candidate; crop=null; homing=false; action=null; return true; }
                }
            }
        }
        if (HomeClaim.away(entity,claim,false) && (claim.stage==HomeClaimData.Stage.RETREAT || entity.life().reachable(claim.site.pos(),false))) {
            homing=true; crop=null; return true;
        }
        if (cooldown-->0) return false;
        cooldown=40; elapsed=0; crop=HomeClaim.findCrop(entity,claim);
        return crop!=null;
    }
    @Override public boolean canContinueToUse() {
        var claim=HomeClaim.of(entity);
        if (claim==null || !HomeClaim.mayAct(entity)) return false;
        if (opening!=null) return claim.stage!=HomeClaimData.Stage.RETREAT;
        if (crop!=null) return elapsed<300 && HomeClaim.cropEdible(entity,crop);
        return HomeClaim.away(entity,claim,homing) && (claim.stage==HomeClaimData.Stage.RETREAT
                || entity.life().reachable(claim.site.pos(),false));
    }
    @Override public boolean requiresUpdateEveryTick() { return true; }
    @Override public void tick() {
        var claim=HomeClaim.of(entity);
        if (claim==null) return;
        if (opening!=null) { paneTick(); return; }
        if (crop==null) { HomeClaim.step(entity,claim); return; }
        elapsed++;
        if (entity.distanceToSqr(Vec3.atBottomCenterOf(crop))<1.5) {
            HomeClaim.eat(entity,crop); crop=null; cooldown=20; entity.getNavigation().stop();
        } else if (elapsed%10==1 && !entity.life().navigate(crop,1,false)) { crop=null; cooldown=60; }
    }
    private void paneTick() {
        if (action!=null && entity.motor().current()==action) return;
        Action next;
        if (action==null) next=Walk.stand(opening.stand(),1);
        else if (!(entity.motor().last() instanceof Outcome.Done)) { endPane(entity.motor().last()); return; }
        else if (action instanceof Walk) {
            if (!entity.level().getBlockState(opening.pane()).is(Tags.Blocks.GLASS_PANES)) {
                opening=null; return;
            }
            next=new Face(Bash.squareYaw(entity,opening.pane()));
        } else if (action instanceof Face) next=new Bash(opening.pane(),
                HomeClaim.of(entity).panes.computeIfAbsent(opening.pane(),ignored -> new Bash.Damage(0,0)),
                HomeClaim.of(entity).paneDisplayId(opening.pane()));
        else {
            next=new Walk(Vec3.atBottomCenterOf(opening.beyond()));
        }
        action=next;
        if (!entity.motor().run(this,next)) endPane(entity.motor().refusal());
    }
    @Override public void finished(Action completed, Outcome outcome) {
        if (completed instanceof Bash bash && outcome instanceof Outcome.Done
                && !entity.level().getBlockState(bash.pane()).is(Tags.Blocks.GLASS_PANES))
            GameplayLog.event(entity,"home","pane opened block={} hits={} actor={}",bash.pane(),bash.hits(),entity.getUUID());
    }
    private void endPane(Outcome outcome) {
        if (outcome!=null && !(outcome instanceof Outcome.Interrupted) && !(outcome instanceof Outcome.Done)) {
            paneChoices.put(opening.pane(),false);
            GameplayLog.event(entity,"home","pane declined block={} outcome={}",opening.pane(),outcome);
        }
        opening=null;
    }
    @Override public void stop() {
        entity.motor().cancel(this,"home_claim_stopped");
        if (opening!=null && action!=null) endPane(entity.motor().last());
        active=false; opening=null; action=null; crop=null; homing=false; entity.getNavigation().stop();
    }
}
