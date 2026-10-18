package io.github.longshou.mcyukkuri.entity;

import io.github.longshou.mcyukkuri.body.GraveBlockEntity;
import io.github.longshou.mcyukkuri.registry.ModBlocks;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import io.github.longshou.mcyukkuri.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.*;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** Persistent physical remains. Only deliberate collection transfers them to an inventory. */
public final class RemainsEntity extends Entity {
    private static final EntityDataAccessor<ItemStack> REMAINS=SynchedEntityData.defineId(RemainsEntity.class,EntityDataSerializers.ITEM_STACK);
    public RemainsEntity(EntityType<? extends RemainsEntity> type,Level level){super(type,level);}
    @Override protected void defineSynchedData(SynchedEntityData.Builder builder){builder.define(REMAINS,ItemStack.EMPTY);}
    public ItemStack remains(){return entityData.get(REMAINS).copy();}
    public CompoundTag data(){return entityData.get(REMAINS).getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag();}
    public void setRemains(ItemStack stack){entityData.set(REMAINS,stack.copyWithCount(1));refreshDimensions();}
    @Override public void onSyncedDataUpdated(EntityDataAccessor<?> key){super.onSyncedDataUpdated(key);if(REMAINS.equals(key))refreshDimensions();}
    public static float dimension(CompoundTag data,String key,float fallback){float v=data.contains(key)?data.getFloat(key):fallback;return Float.isFinite(v)?Math.clamp(v,.04f,8):fallback;}
    @Override public EntityDimensions getDimensions(Pose pose){var d=data();return EntityDimensions.scalable(dimension(d,"Width",.75f),dimension(d,"Height",.35f));}
    @Override public boolean isPickable(){return !isRemoved();}
    public static ItemStack snapshot(YukkuriEntity source,net.minecraft.world.damagesource.DamageSource cause){
        return snapshot(source,cause,false);
    }
    public static ItemStack snapshot(YukkuriEntity source,net.minecraft.world.damagesource.DamageSource cause,boolean stemDepletion){
        var result=new ItemStack(ModItems.REMAINS.get());
        CustomData.update(DataComponents.CUSTOM_DATA,result,tag->{
            tag.putUUID("Identity",source.getUUID());tag.putString("Variant",source.getVariant().assetId());
            tag.putInt("SubspeciesFlags",source.subspeciesFlags());
            tag.putString("Model",source.subspecies().assetId());tag.put("Body",source.body().save());
            tag.putInt("Filling",source.body().filling());tag.putFloat("RenderScale",source.growthScale()*source.getScale());
            tag.putFloat("SourceScale",source.growthScale()*source.getScale()*(source.getVariant().isHouse()?.32f:1));
            tag.putFloat("Width",source.getBbWidth());tag.putFloat("Height",source.getBbHeight()*.65f);
            tag.putBoolean("Accessory",source.life().hasAccessory());tag.putInt("Medal",source.pet().visibleGrade().id());
            tag.putLong("DiedAt",source.level().getGameTime());
            if(cause!=null)tag.putString("Cause",cause.typeHolder().unwrapKey().map(key->key.location().toString()).orElse(cause.getMsgId()));
            // Context supplements the original damage ID; it never replaces another cause of death.
            if(stemDepletion&&cause!=null&&cause.is(net.minecraft.world.damagesource.DamageTypes.STARVE))
                tag.putString("CauseDetail","stem_nutrition_depletion");
        });
        result.set(DataComponents.CUSTOM_NAME,net.minecraft.network.chat.Component.translatable("item.mcyukkuri.yukkuri_remains.named",source.getName()));return result;
    }
    public static boolean place(Level level,Vec3 position,float yaw,ItemStack stack,boolean checkCollision){
        if(level.isClientSide||!stack.is(ModItems.REMAINS.get()))return false;
        var corpse=ModEntities.REMAINS.get().create(level);if(corpse==null)return false;
        corpse.setRemains(stack);corpse.moveTo(position.x,position.y,position.z,yaw,0);
        if(checkCollision&&(!level.noCollision(corpse)||!level.getFluidState(corpse.blockPosition()).isEmpty()))return false;
        return level.addFreshEntity(corpse);
    }
    @Override public InteractionResult interact(Player player,InteractionHand hand){
        if(isRemoved()||player.isSpectator()||!player.mayBuild()||player.distanceToSqr(this)>36)return InteractionResult.PASS;
        var held=player.getItemInHand(hand);
        if(held.is(ItemTags.SHOVELS))return bury(player,hand);
        if(!held.isEmpty())return InteractionResult.PASS;
        if(!level().isClientSide){player.setItemInHand(hand,remains());discard();}
        return InteractionResult.sidedSuccess(level().isClientSide);
    }
    private InteractionResult bury(Player player,InteractionHand hand){
        BlockPos top=BlockPos.containing(getX(),getY()+.05,getZ()),soil=top.below();
        var tool=player.getItemInHand(hand);
        if(!onGround()||!level().getBlockState(soil).is(BlockTags.DIRT)||!level().isEmptyBlock(top)
                ||!level().mayInteract(player,top)||!level().mayInteract(player,soil)
                ||!player.mayUseItemAt(top,Direction.UP,tool))return InteractionResult.FAIL;
        if(level().isClientSide)return InteractionResult.SUCCESS;
        if(!level().setBlockAndUpdate(top,ModBlocks.GRAVE.get().defaultBlockState()))return InteractionResult.FAIL;
        if(!(level().getBlockEntity(top) instanceof GraveBlockEntity grave)){
            level().removeBlock(top,false);return InteractionResult.FAIL;
        }
        var buried=data();var name=remains().get(DataComponents.CUSTOM_NAME);
        if(name!=null)buried.putString("Name",net.minecraft.network.chat.Component.Serializer.toJson(name,level().registryAccess()));
        grave.bury(buried);discard();
        if(!player.getAbilities().instabuild)tool.hurtAndBreak(1,player,LivingEntity.getSlotForHand(hand));
        return InteractionResult.CONSUME;
    }
    @Override public void tick(){
        super.tick();if(level().isClientSide)return;
        if(!isNoGravity())setDeltaMovement(getDeltaMovement().add(0,-.04,0));
        move(MoverType.SELF,getDeltaMovement());
        setDeltaMovement(getDeltaMovement().multiply(.8,onGround()?0:.98,.8));
    }
    @Override protected void addAdditionalSaveData(CompoundTag tag){if(!remains().isEmpty())tag.put("Remains",remains().save(registryAccess()));}
    @Override protected void readAdditionalSaveData(CompoundTag tag){setRemains(ItemStack.parseOptional(registryAccess(),tag.getCompound("Remains")));}
}
