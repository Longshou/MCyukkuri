package io.github.longshou.mcyukkuri.item;

import io.github.longshou.mcyukkuri.house.HouseholdProtection;
import io.github.longshou.mcyukkuri.house.SupplyPiles;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;

/** A finite bottle; old stacked guard items separate only after a successful application. */
public final class HouseholdSprayItem extends Item {
    public static final int USES=16,COOLDOWN=20;
    private static final String REMAINING="HouseholdSprayUses";
    public HouseholdSprayItem(Properties properties){super(properties);}
    public static int uses(ItemStack stack){
        var tag=stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag();
        return tag.contains(REMAINING)?Math.clamp(tag.getInt(REMAINING),0,USES):USES;
    }
    private static void consume(ItemStack stack){
        int remaining=uses(stack)-1;
        if(remaining<=0)stack.shrink(1);
        else CustomData.update(DataComponents.CUSTOM_DATA,stack,tag->tag.putInt(REMAINING,remaining));
    }
    // Run before block interaction so spraying a chest does not open its screen instead.
    @Override public InteractionResult onItemUseFirst(ItemStack stack,UseOnContext context){return useOn(context);}
    @Override public InteractionResult useOn(UseOnContext context){
        var player=context.getPlayer();var level=context.getLevel();var pos=context.getClickedPos();var stack=context.getItemInHand();
        if(player==null||player.isSpectator()||stack.isEmpty()||level.getBlockState(pos).isAir())return InteractionResult.FAIL;
        if(player.isShiftKeyDown()){
            if(level instanceof ServerLevel server){
                long ticks=HouseholdProtection.get(server).remainingTicks(pos,server.getGameTime());
                player.displayClientMessage(ticks>0?Component.translatable("house.mcyukkuri.guard.covered",(ticks+19)/20)
                        :Component.translatable("house.mcyukkuri.guard.uncovered"),true);
            }
            return InteractionResult.SUCCESS;
        }
        if(!player.mayBuild()||!level.mayInteract(player,pos)||!player.mayUseItemAt(pos,context.getClickedFace(),stack)
                ||player.getCooldowns().isOnCooldown(this))return InteractionResult.FAIL;
        if(uses(stack)<=0){
            if(!level.isClientSide)player.displayClientMessage(Component.translatable("house.mcyukkuri.guard.empty"),true);
            return InteractionResult.FAIL;
        }
        if(level instanceof ServerLevel server){
            if(!HouseholdProtection.get(server).spray(pos,server.getGameTime())){
                player.displayClientMessage(Component.translatable("house.mcyukkuri.guard.full"),true);return InteractionResult.FAIL;
            }
            if(!player.getAbilities().instabuild){
                if(stack.getCount()>1){
                    var unused=stack.copyWithCount(stack.getCount()-1);stack.setCount(1);
                    CustomData.update(DataComponents.CUSTOM_DATA,unused,tag->tag.remove(REMAINING));
                    SupplyPiles.give(player,unused);
                }
                consume(stack);
            }
            player.getCooldowns().addCooldown(this,COOLDOWN);
            var point=context.getClickLocation().add(context.getClickedFace().getStepX()*.08,context.getClickedFace().getStepY()*.08,context.getClickedFace().getStepZ()*.08);
            server.sendParticles(ParticleTypes.CLOUD,point.x,point.y,point.z,8,.10,.06,.10,.025);
            server.playSound(null,pos,SoundEvents.FIRE_EXTINGUISH,SoundSource.PLAYERS,.35f,1.6f);
            player.displayClientMessage(Component.translatable("house.mcyukkuri.guard.sprayed",HouseholdProtection.DURATION/1200,stack.isEmpty()?0:uses(stack)),true);
        }
        return InteractionResult.SUCCESS;
    }
    @Override public boolean isBarVisible(ItemStack stack){return uses(stack)<USES;}
    @Override public int getBarWidth(ItemStack stack){return Math.round(13f*uses(stack)/USES);}
    @Override public int getBarColor(ItemStack stack){return 0x65BBB0;}
    @Override public void appendHoverText(ItemStack stack,TooltipContext context,List<Component> tooltip,TooltipFlag flag){
        tooltip.add(Component.translatable("house.mcyukkuri.guard.charges",uses(stack),USES));
        tooltip.add(Component.translatable("house.mcyukkuri.guard.active",HouseholdProtection.RADIUS,HouseholdProtection.HEIGHT,HouseholdProtection.DURATION/1200));
        tooltip.add(Component.translatable("house.mcyukkuri.guard.controls"));
        tooltip.add(Component.translatable("house.mcyukkuri.guard.existing"));
    }
}
