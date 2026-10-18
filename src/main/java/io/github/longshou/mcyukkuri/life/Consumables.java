package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.MCYukkuri;
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
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.bus.api.IEventBus;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.damagesource.DamageType;

/** Real, single-portion consumables. Oral effects commit at the existing swallow step. */
public final class Consumables {
    private static final DeferredRegister.Items ITEM_TYPES = DeferredRegister.createItems(MCYukkuri.MOD_ID);
    public static final ResourceKey<DamageType> SPICE_DAMAGE=ResourceKey.create(Registries.DAMAGE_TYPE,MCYukkuri.id("spicy_food"));
    public enum Kind {
        YOGURT("yogurt_drink"), STIMULANT("stimulant"), SITATARAN("sitataran"), RAMUNE("sleep_spray"), SPICY("spicy_grain"), REPRODUCTIVE("reproductive_filling"), FLOUR("wheat_flour");
        public final String id;
        Kind(String id){this.id=id;}
    }
    public static final EnumMap<Kind,DeferredItem<DoseItem>> ITEMS=new EnumMap<>(Kind.class);
    static {
        for(var kind:Kind.values())ITEMS.put(kind,ITEM_TYPES.register(kind.id,()->new DoseItem(kind,new Item.Properties().stacksTo(16))));
        // The mint leaf (2026-10-18) was merged into the marble-soda sleep spray: old saves load their leaves as sprays.
        ITEM_TYPES.addAlias(MCYukkuri.id("mint_leaf"),MCYukkuri.id(Kind.RAMUNE.id));
    }
    public static void register(IEventBus bus) {
        ITEM_TYPES.register(bus);
    }
    public static boolean water(ItemStack stack){var potion=stack.get(DataComponents.POTION_CONTENTS);return stack.is(Items.POTION)&&potion!=null&&potion.is(Potions.WATER);}
    public static Kind kind(ItemStack stack){return stack.getItem() instanceof DoseItem item?item.kind:null;}
    public static boolean oral(ItemStack stack){var kind=kind(stack);return water(stack)||kind==Kind.YOGURT||kind==Kind.STIMULANT||kind==Kind.SITATARAN||kind==Kind.FLOUR;}
    /** Everything but the powder comes in a glass bottle that is handed back. */
    public static boolean bottled(ItemStack stack){var kind=kind(stack);return kind!=Kind.FLOUR;}
    public static boolean applicable(YukkuriEntity entity,ItemStack stack){
        if(water(stack)||kind(stack)==Kind.YOGURT)return entity.life().dryness()>0||entity.life().spicyTicks()>0;
        return switch(kind(stack)){
            case STIMULANT -> entity.family().canTakeStimulant();
            case SITATARAN -> entity.family().adult()&&!entity.family().babyTalk();
            case FLOUR -> entity.life().nutrition()<95&&entity.family().canEatIndependently()&&entity.childhood().canEatSolid();
            case null,default -> false;
        };
    }
    public static boolean swallow(YukkuriEntity entity,ItemStack stack){
        if(!applicable(entity,stack))return false;
        if(water(stack)||kind(stack)==Kind.YOGURT){entity.life().drink(kind(stack)==Kind.YOGURT?80:30);return true;}
        return switch(kind(stack)){case STIMULANT -> entity.family().takeStimulant();case FLOUR -> entity.life().takeFlour();default -> entity.family().applyBabyTalk();};
    }
    public static InteractionResult feed(YukkuriEntity entity,Player player,InteractionHand hand){
        var stack=player.getItemInHand(hand);
        if(!player.hasLineOfSight(entity)||player.distanceToSqr(entity)>9)return InteractionResult.FAIL;
        if(!entity.level().isClientSide){
            if(!entity.life().beginDose(stack.copyWithCount(1),player.getUUID(),!player.getAbilities().instabuild&&bottled(stack)))return InteractionResult.FAIL;
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
