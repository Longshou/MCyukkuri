package io.github.longshou.mcyukkuri.subspecies;

import io.github.longshou.mcyukkuri.mind.EmotionEvent;

import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.pathfinder.Path;

/** Delicate soles: short visits to the bank, slower land travel and fatigue after a prolonged visit. */
public final class AquaticShore {
    public static final int VISIT_TICKS=600;
    private final YukkuriEntity entity;
    private int landTicks;
    public AquaticShore(YukkuriEntity entity){this.entity=entity;}
    public int landTicks(){return landTicks;}
    public boolean tired(){return landTicks>=VISIT_TICKS;}
    /** A two-block strip beside open surface water, never unloaded chunks or an underground pool. */
    public boolean coastal(BlockPos stand){
        for(var p:BlockPos.betweenClosed(stand.offset(-2,-2,-2),stand.offset(2,0,2)))
            if(HatBoatRoute.calmSurface(entity.level(),p))return true;
        return false;
    }
    private boolean unrestricted(){
        return !entity.profile().aquatic()||entity.isInWaterOrBubble()||entity.isPanicking()||entity.isOnFire()
                ||entity.isCommandPlaying()||entity.operations().active();
    }
    public boolean allows(Path path){
        if(path==null)return false;if(unrestricted())return true;
        if(path.getNodeCount()==0)return true;
        var end=path.getEndNode();if(end==null||!coastal(new BlockPos(end.x,end.y,end.z)))return false;
        // A forcibly displaced inland animal may take a dry path back to the bank.
        if(!coastal(entity.blockPosition()))return true;
        for(int i=0;i<path.getNodeCount();i++){
            var n=path.getNode(i);if(!coastal(new BlockPos(n.x,n.y,n.z)))return false;
        }
        return true;
    }
    /** Also covers the few direct movement controls that do not submit a path. */
    public boolean mayStepTo(BlockPos next){return unrestricted()||!coastal(entity.blockPosition())||coastal(next);}
    public void tick(){
        boolean aquatic=entity.profile().aquatic();
        boolean land=aquatic&&entity.onGround()&&!entity.isPassenger()&&!entity.family().flying()&&!entity.isInWaterOrBubble();
        if(!aquatic)landTicks=0;
        else if(land)landTicks=Math.min(2400,landTicks+1);
        else if(entity.shelteredInHat())landTicks=Math.max(0,landTicks-4);
        var speed=entity.getAttribute(Attributes.MOVEMENT_SPEED);var id=MCYukkuri.id("aquatic_soles");
        double penalty=land?(tired()?-.65:-.40):0;
        if(speed!=null){
            var modifier=speed.getModifier(id);
            if(modifier!=null&&modifier.amount()!=penalty){speed.removeModifier(id);modifier=null;}
            if(penalty!=0&&modifier==null)speed.addTransientModifier(new AttributeModifier(id,penalty,AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        }
        if(land&&tired()&&entity.tickCount%100==0){
            entity.life().applyEnvironment(0,0,1);
            if(!entity.isNoAi()&&!entity.isCommandPlaying()&&!entity.operations().active()&&entity.tickCount%200==0)
                { entity.mind().felt(EmotionEvent.WOUND_DISTRESS); entity.showReaction("teary","wounded",40); }
        }
    }
    public CompoundTag save(){var tag=new CompoundTag();tag.putInt("LandTicks",landTicks);return tag;}
    public void load(CompoundTag tag){landTicks=Math.clamp(tag.getInt("LandTicks"),0,2400);}
}
