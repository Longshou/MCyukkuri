package io.github.longshou.mcyukkuri.event;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

/** Keeps a claimant near its claimed site (or retreating), and lets it eat the claimed crops when hungry. */
public final class HomeClaimGoal extends Goal {
    private final YukkuriEntity entity;
    private BlockPos crop;
    private boolean homing;
    private int cooldown, elapsed;
    public HomeClaimGoal(YukkuriEntity entity) { this.entity=entity; setFlags(EnumSet.of(Flag.MOVE,Flag.LOOK)); }
    @Override public boolean canUse() {
        var claim=HomeClaim.of(entity);
        if (claim==null || !HomeClaim.mayAct(entity)) return false;
        if (HomeClaim.away(entity,claim,false)) { homing=true; crop=null; return true; }
        if (cooldown-->0) return false;
        cooldown=40; elapsed=0; crop=HomeClaim.findCrop(entity,claim);
        return crop!=null;
    }
    @Override public boolean canContinueToUse() {
        var claim=HomeClaim.of(entity);
        if (claim==null || !HomeClaim.mayAct(entity)) return false;
        if (crop!=null) return elapsed<300 && HomeClaim.cropEdible(entity,crop);
        return HomeClaim.away(entity,claim,homing);
    }
    @Override public boolean requiresUpdateEveryTick() { return true; }
    @Override public void tick() {
        var claim=HomeClaim.of(entity);
        if (claim==null) return;
        if (crop==null) { HomeClaim.step(entity,claim); return; }
        elapsed++;
        if (entity.distanceToSqr(Vec3.atBottomCenterOf(crop))<1.5) {
            HomeClaim.eat(entity,crop); crop=null; cooldown=20; entity.getNavigation().stop();
        } else if (elapsed%10==1 && !entity.life().navigate(crop,1,false)) { crop=null; cooldown=60; }
    }
    @Override public void stop() { crop=null; homing=false; entity.getNavigation().stop(); }
}
