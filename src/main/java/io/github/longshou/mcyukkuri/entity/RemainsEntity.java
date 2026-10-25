package io.github.longshou.mcyukkuri.entity;

import io.github.longshou.mcyukkuri.body.GraveBlockEntity;
import io.github.longshou.mcyukkuri.human.FactoryProduction;
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
import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.body.BodyFood;
import io.github.longshou.mcyukkuri.body.CorpseFood;
import io.github.longshou.mcyukkuri.body.FillingResource;
import io.github.longshou.mcyukkuri.body.RemainsCause;
import io.github.longshou.mcyukkuri.body.RemainsRot;
import io.github.longshou.mcyukkuri.body.RemainsWater;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.item.OperationToolItem;
import io.github.longshou.mcyukkuri.status.Marks;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;

/** Persistent physical remains. Only deliberate collection transfers them to an inventory. */
public final class RemainsEntity extends Entity {
    private static final EntityDataAccessor<ItemStack> REMAINS=SynchedEntityData.defineId(RemainsEntity.class,EntityDataSerializers.ITEM_STACK);
    public RemainsEntity(EntityType<? extends RemainsEntity> type,Level level){super(type,level);}
    @Override protected void defineSynchedData(SynchedEntityData.Builder builder){builder.define(REMAINS,ItemStack.EMPTY);}
    /** Game ticks these remains have lain in the world (decay clock, body/RemainsRot); carried, buried or frozen remains do not age. */
    private long rot;
    public long rot(){return rot;}
    public int stage(){return RemainsRot.stage(data());}
    /** Ticks these remains have spent in water (body/RemainsWater); like the decay clock it only runs in the world. */
    private long soak;
    private int waterStage;
    public long soak(){return soak;}
    public int waterStage(){return waterStage;}
    /** The remains as an item stack, carrying the current decay and soaking clocks. */
    public ItemStack remains(){var stack=entityData.get(REMAINS).copy();if(!stack.isEmpty()&&(rot>0||soak>0))CustomData.update(DataComponents.CUSTOM_DATA,stack,tag->{
            if(rot>0)tag.putLong("Rot",rot);if(soak>0)tag.putLong(RemainsWater.SOAK,soak);});return stack;}
    /** Set the decay clock (tests, /yukkuri body rot). */
    public void setRot(long ticks){rot=Math.max(0,ticks);refreshStage();}
    private void refreshStage(){
        int now=RemainsRot.stageFor(rot);if(now==stage())return;
        var stack=remains();CustomData.update(DataComponents.CUSTOM_DATA,stack,tag->tag.putInt("RotStage",now));setRemains(stack);
        MCYukkuri.LOGGER.info("[remains] #{} is now {} (rot {})",getId(),RemainsRot.id(now),rot);
    }
    /**
     * Water softens and then dissolves the body; a stage never reverses. Dissolving takes the remaining filling into the water:
     * the shared food balance becomes zero and the centre filling is gone, which the log records with the real before value.
     */
    private void refreshWater(){
        int now=RemainsWater.stageFor(soak),before=waterStage;if(now<=before)return;
        var stack=remains();var old=data();float food=CorpseFood.remaining(old);boolean core=hasCore(old);
        CustomData.update(DataComponents.CUSTOM_DATA,stack,tag->{tag.putInt(RemainsWater.STAGE,now);if(now==RemainsWater.DISSOLVING)tag.putFloat(CorpseFood.REMAINING,0);});
        setRemains(stack);var after=data();
        // At most two transitions per body, each a real resource change: never throttled.
        GameplayLog.event(this,"body","remains water stage {} -> {}; soak={} food {} -> {} core {} -> {} category={} identity={}",
                RemainsWater.id(before),RemainsWater.id(now),soak,food,CorpseFood.remaining(after),core,hasCore(after),RemainsCause.of(after),
                after.hasUUID("Identity")?after.getUUID("Identity"):"none");
    }
    /** Set the soaking clock (/yukkuri body soak); the stage still only moves forward. */
    public void setSoak(long ticks){soak=Math.max(0,ticks);refreshWater();}
    /** One real bite from this corpse's shared food balance. It does not create a loose item. */
    public BodyFood.Bite biteFood(){return CorpseFood.take(this,1);}
    /** The synced stack itself, replaced (never edited in place) whenever the remains data changes; clients use it to detect change. */
    public ItemStack syncedRemains(){return entityData.get(REMAINS);}
    public CompoundTag data(){return entityData.get(REMAINS).getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag();}
    public void setRemains(ItemStack stack){CorpseFood.migrate(stack);entityData.set(REMAINS,stack.copyWithCount(1));rot=stack.getOrDefault(DataComponents.CUSTOM_DATA,
            CustomData.EMPTY).copyTag().getLong("Rot");var tag=stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag();
            soak=Math.max(0,tag.getLong(RemainsWater.SOAK));waterStage=RemainsWater.stage(tag);refreshDimensions();}
    @Override public void onSyncedDataUpdated(EntityDataAccessor<?> key){super.onSyncedDataUpdated(key);if(REMAINS.equals(key))refreshDimensions();}
    public static float dimension(CompoundTag data,String key,float fallback){float v=data.contains(key)?data.getFloat(key):fallback;return Float.isFinite(v)?Math.clamp(v,.04f,8):fallback;}
    @Override public EntityDimensions getDimensions(Pose pose){var d=data();return EntityDimensions.scalable(dimension(d,"Width",.75f),dimension(d,"Height",.35f));}
    @Override public boolean isPickable(){return !isRemoved();}
    public static ItemStack snapshot(YukkuriEntity source,DamageSource cause){
        return snapshot(source,cause,false);
    }
    public static ItemStack snapshot(YukkuriEntity source,DamageSource cause,boolean stemDepletion){
        return snapshot(source,cause,stemDepletion,false);
    }
    public static ItemStack snapshot(YukkuriEntity source,DamageSource cause,boolean stemDepletion,boolean sukkiriExhaustion){
        return snapshot(source,cause,stemDepletion,sukkiriExhaustion,"");
    }
    /** {@code internalCause} is the body's own cause when the killing damage is only a generic carrier (body/RemainsCause). */
    public static ItemStack snapshot(YukkuriEntity source,DamageSource cause,boolean stemDepletion,boolean sukkiriExhaustion,String internalCause){
        var result=new ItemStack(ModItems.REMAINS.get());
        CustomData.update(DataComponents.CUSTOM_DATA,result,tag->{
            tag.putUUID("Identity",source.getUUID());tag.putString("Variant",source.getVariant().assetId());
            tag.putInt("SubspeciesFlags",source.subspeciesFlags());
            tag.putString("Model",source.subspecies().assetId());tag.put("Body",source.body().save());
            tag.put("Marks",Marks.write(source.status().marks().visible()));
            tag.putFloat(CorpseFood.CAPACITY,BodyFood.capacity(source));
            tag.putFloat(CorpseFood.REMAINING,source.body().remainsFood());
            tag.putInt("FoodSweetness",source.mind().fillingSweetness());
            tag.putBoolean("FoodFoul",source.mind().foulFilling());
            tag.putFloat("RenderScale",source.growthScale()*source.getScale());
            tag.putFloat("SourceScale",source.growthScale()*source.getScale()*(source.profile().modelScale()));
            tag.putFloat("Width",source.getBbWidth());tag.putFloat("Height",source.getBbHeight()*.65f);
            tag.putBoolean("Accessory",source.life().hasAccessory());tag.putInt("Medal",source.pet().visibleGrade().id());
            tag.putInt("Headwear",source.visibleHeadwear());
            tag.putLong("DiedAt",source.level().getGameTime());
            // Who the body belonged to for the living: kept individuals are never eaten, and a partner remembers it was theirs.
            tag.putBoolean("Kept",source.pet().isPet()||source.pet().owner()!=null||source.profile().houseBody());
            if(source.family().partner()!=null)tag.putUUID("Partner",source.family().partner());
            // "Please eat me" (social/SelfOffering): a body given to one child, which eats it without disgust.
            if(source.offering().recipient()!=null){tag.putBoolean("Offered",true);tag.putUUID("OfferedTo",source.offering().recipient());}
            if(cause!=null)tag.putString("Cause",cause.typeHolder().unwrapKey().map(key->key.location().toString()).orElse(cause.getMsgId()));
            // Context supplements the original damage ID; it never replaces another cause of death.
            if(stemDepletion&&cause!=null&&cause.is(DamageTypes.STARVE))
                tag.putString("CauseDetail","stem_nutrition_depletion");
            // 1.12 すっき死: a body that spent its last strength finishing the act.
            if(sukkiriExhaustion)tag.putBoolean("SukkiriExhaustion",true);
            // The remains form follows the real cause; the internal cause survives a generic killing carrier.
            if(internalCause!=null&&!internalCause.isEmpty())tag.putString(RemainsCause.INTERNAL,internalCause);
            tag.putString(RemainsCause.CATEGORY,RemainsCause.category(cause,internalCause,stemDepletion&&cause!=null&&cause.is(DamageTypes.STARVE),sukkiriExhaustion));
        });
        result.set(DataComponents.CUSTOM_NAME,Component.translatable("item.mcyukkuri.yukkuri_remains.named",source.getName()));return result;
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
        // A manju line has explicitly accepted this exact corpse. It is released
        // when the shared balance is exhausted or no longer factory-eligible.
        if(data().hasUUID("FactoryOwner"))return InteractionResult.FAIL;
        var held=player.getItemInHand(hand);
        if(held.is(ItemTags.SHOVELS))return bury(player,hand);
        if(held.is(ItemTags.SWORDS)||held.getItem() instanceof OperationToolItem tool&&tool.tool()==OperationToolItem.Tool.CUTTER)return takeCore(player,hand);
        if(!held.isEmpty())return InteractionResult.PASS;
        if(!level().isClientSide){player.setItemInHand(hand,remains());discard();}
        return InteractionResult.sidedSuccess(level().isClientSide);
    }
    /** Whether these remains still hold a centre filling: the core was never hurt and nobody has taken it yet. */
    public static boolean hasCore(CompoundTag data){return !data.getCompound("Body").getBoolean("CoreHurt")&&!data.getBoolean("CoreTaken")&&RemainsRot.stage(data)==RemainsRot.FRESH
            &&RemainsWater.stage(data)<RemainsWater.DISSOLVING;}
    /** Cut out the centre filling (komicolle 11518802: the darkest, sweetest part). Only once, and only from an unhurt core. */
    private InteractionResult takeCore(Player player,InteractionHand hand){
        if(level().isClientSide)return InteractionResult.SUCCESS;
        var data=data();
        if(!hasCore(data)){
            player.displayClientMessage(Component.translatable(data.getBoolean("CoreTaken")?"remains.mcyukkuri.core_taken":RemainsWater.stage(data)==RemainsWater.DISSOLVING
                    &&!data.getCompound("Body").getBoolean("CoreHurt")?"remains.mcyukkuri.core_dissolved":data.getCompound("Body")
                    .getBoolean("CoreHurt")?"remains.mcyukkuri.core_broken":"remains.mcyukkuri.core_rotten"),true);
            return InteractionResult.FAIL;
        }
        var core=cutCore();
        if(!player.getInventory().add(core))player.drop(core,false);
        if(!player.getAbilities().instabuild)player.getItemInHand(hand).hurtAndBreak(1,player,LivingEntity.getSlotForHand(hand));
        player.displayClientMessage(Component.translatable("remains.mcyukkuri.core_cut"),true);
        level().playSound(null,blockPosition(),SoundEvents.HONEY_BLOCK_BREAK,SoundSource.PLAYERS,.6f,1.2f);
        return InteractionResult.CONSUME;
    }
    /** Server side: take the centre filling out (EMPTY when there is none) and mark the remains. Also used by /yukkuri body. */
    public ItemStack cutCore(){
        var data=data();if(level().isClientSide||!hasCore(data))return ItemStack.EMPTY;
        var core=FillingResource.core(data);
        var stack=remains();CustomData.update(DataComponents.CUSTOM_DATA,stack,tag->tag.putBoolean("CoreTaken",true));setRemains(stack);
        return core;
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
        if(name!=null)buried.putString("Name",Component.Serializer.toJson(name,level().registryAccess()));
        grave.bury(buried);discard();
        if(!player.getAbilities().instabuild)tool.hurtAndBreak(1,player,LivingEntity.getSlotForHand(hand));
        return InteractionResult.CONSUME;
    }
    @Override public void tick(){
        super.tick();
        if(level().isClientSide){if(tickCount%10==0)rotParticles();return;}
        if(++rot%20==0){refreshStage();FactoryProduction.releaseDeadOwner(this);}
        if(isInWater()&&++soak%20==0)refreshWater();
        boolean afloat=isInWaterOrBubble();
        var motion=getDeltaMovement();
        // Dissolved residue has lost its body and settles to the bottom.
        if(afloat&&waterStage<RemainsWater.DISSOLVING) {
            // A water death stays recoverable where it happened.  This is physical
            // buoyancy for the corpse entity, not an inventory transfer or a rescue.
            if(motion.y<.025)motion=motion.add(0,Math.min(.04,.025-motion.y),0);
        } else if(!isNoGravity())motion=motion.add(0,-.04,0);
        setDeltaMovement(motion);
        move(MoverType.SELF,getDeltaMovement());
        setDeltaMovement(getDeltaMovement().multiply(.8,afloat?.72:onGround()?.0:.98,.8));
    }
    /** A thin haze of spores over stale remains, a heavier one over rotten ones (client only). */
    private void rotParticles(){
        int stage=stage();if(stage==0||random.nextInt(stage==2?2:5)!=0)return;
        level().addParticle(stage==2?ParticleTypes.SMOKE:ParticleTypes.MYCELIUM,
                getX()+(random.nextDouble()-.5)*getBbWidth(),getY()+getBbHeight()*.7,getZ()+(random.nextDouble()-.5)*getBbWidth(),0,.02,0);
    }
    @Override protected void addAdditionalSaveData(CompoundTag tag){if(!remains().isEmpty())tag.put("Remains",remains().save(registryAccess()));}
    @Override protected void readAdditionalSaveData(CompoundTag tag){setRemains(ItemStack.parseOptional(registryAccess(),tag.getCompound("Remains")));}
}
