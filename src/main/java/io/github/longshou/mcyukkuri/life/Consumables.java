package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.registry.ModItems;
import java.util.EnumMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potions;
import net.neoforged.neoforge.registries.DeferredItem;

/** Real, single-portion consumables. Oral effects commit at the existing swallow step. */
public final class Consumables {
    public static final net.minecraft.resources.ResourceKey<net.minecraft.world.damagesource.DamageType> SPICE_DAMAGE=net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DAMAGE_TYPE,io.github.longshou.mcyukkuri.MCYukkuri.id("spicy_food"));
    public enum Kind {
        YOGURT("yogurt_drink"), STIMULANT("stimulant"), SITATARAN("sitataran"), RAMUNE("sleep_spray"), SPICY("spicy_grain"), REPRODUCTIVE("reproductive_filling");
        public final String id;
        Kind(String id){this.id=id;}
    }
    public static final EnumMap<Kind,DeferredItem<DoseItem>> ITEMS=new EnumMap<>(Kind.class);
    static { for(var kind:Kind.values())ITEMS.put(kind,ModItems.ITEMS.register(kind.id,()->new DoseItem(kind,new Item.Properties().stacksTo(16)))); }
    public static void init(){}
    public static boolean water(ItemStack stack){var potion=stack.get(DataComponents.POTION_CONTENTS);return stack.is(Items.POTION)&&potion!=null&&potion.is(Potions.WATER);}
    public static Kind kind(ItemStack stack){return stack.getItem() instanceof DoseItem item?item.kind:null;}
    public static boolean oral(ItemStack stack){var kind=kind(stack);return water(stack)||kind==Kind.YOGURT||kind==Kind.STIMULANT||kind==Kind.SITATARAN;}
    public static boolean applicable(YukkuriEntity entity,ItemStack stack){
        if(water(stack)||kind(stack)==Kind.YOGURT)return entity.life().dryness()>0||entity.life().spicyTicks()>0;
        return switch(kind(stack)){
            case STIMULANT -> entity.family().canTakeStimulant();
            case SITATARAN -> entity.family().adult()&&!entity.family().babyTalk();
            case null,default -> false;
        };
    }
    public static boolean swallow(YukkuriEntity entity,ItemStack stack){
        if(!applicable(entity,stack))return false;
        if(water(stack)||kind(stack)==Kind.YOGURT){entity.life().drink(kind(stack)==Kind.YOGURT?80:30);return true;}
        return kind(stack)==Kind.STIMULANT?entity.family().takeStimulant():entity.family().applyBabyTalk();
    }
    public static InteractionResult feed(YukkuriEntity entity,Player player,InteractionHand hand){
        var stack=player.getItemInHand(hand);
        if(!player.hasLineOfSight(entity)||player.distanceToSqr(entity)>9)return InteractionResult.FAIL;
        if(!entity.level().isClientSide){
            if(!entity.life().beginDose(stack.copyWithCount(1),player.getUUID(),!player.getAbilities().instabuild))return InteractionResult.FAIL;
            if(!player.getAbilities().instabuild)stack.shrink(1);
        }
        return InteractionResult.sidedSuccess(entity.level().isClientSide);
    }
    public static final class DoseItem extends Item {
        public final Kind kind;
        DoseItem(Kind kind,Properties properties){super(properties);this.kind=kind;}
        @Override public InteractionResult interactLivingEntity(ItemStack stack,Player player,LivingEntity target,InteractionHand hand){
            if(!(target instanceof YukkuriEntity entity))return InteractionResult.PASS;
            if(kind==Kind.SPICY)return InteractionResult.PASS;
            if(kind==Kind.REPRODUCTIVE){
                if(entity.level().isClientSide)return InteractionResult.SUCCESS;
                return entity.operations().beginAssistance(player,hand)?InteractionResult.CONSUME:InteractionResult.FAIL;
            }
            if(kind!=Kind.RAMUNE)return feed(entity,player,hand);
            if(!player.hasLineOfSight(entity)||player.distanceToSqr(entity)>9)return InteractionResult.FAIL;
            if(!player.level().isClientSide){
                if(!entity.life().spray())return InteractionResult.FAIL;
                if(!player.getAbilities().instabuild){stack.shrink(1);var empty=new ItemStack(Items.GLASS_BOTTLE);if(!player.getInventory().add(empty))player.drop(empty,false);}
            }
            return InteractionResult.sidedSuccess(player.level().isClientSide);
        }
    }
    private Consumables(){}
}
