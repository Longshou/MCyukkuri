package io.github.longshou.mcyukkuri.entity;

import io.github.longshou.mcyukkuri.random.ModRandom;

import io.github.longshou.mcyukkuri.body.GraveBlock;
import io.github.longshou.mcyukkuri.family.LeafSwaddle;
import io.github.longshou.mcyukkuri.family.RemainsStem;
import io.github.longshou.mcyukkuri.body.GraveBlockEntity;
import io.github.longshou.mcyukkuri.interaction.ItemContact;
import io.github.longshou.mcyukkuri.headwear.JunkHeadwear;
import io.github.longshou.mcyukkuri.headwear.Headwear;
import io.github.longshou.mcyukkuri.registry.ModBlocks;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import io.github.longshou.mcyukkuri.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.syncher.*;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.neoforged.neoforge.common.Tags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.body.BodyFood;
import io.github.longshou.mcyukkuri.body.CorpseFood;
import io.github.longshou.mcyukkuri.body.FillingResource;
import io.github.longshou.mcyukkuri.body.RemainsCause;
import io.github.longshou.mcyukkuri.body.RemainsRot;
import io.github.longshou.mcyukkuri.body.RemainsWater;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.status.Marks;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import java.util.function.BooleanSupplier;

/** Persistent physical remains. Only deliberate collection transfers them to an inventory. */
public final class RemainsEntity extends Entity {
    /** Translation key of the remains name ("X's remains"); its one argument is the name the body had alive. */
    public static final String NAMED="item.mcyukkuri.yukkuri_remains.named";
    private static final String CARRIED_ITEMS = "CarriedItems";
    private static final EntityDataAccessor<ItemStack> REMAINS=SynchedEntityData.defineId(RemainsEntity.class,EntityDataSerializers.ITEM_STACK);
    public RemainsEntity(EntityType<? extends RemainsEntity> type,Level level){super(type,level);ModRandom.construct(this);}
    @Override protected void defineSynchedData(SynchedEntityData.Builder builder){builder.define(REMAINS,ItemStack.EMPTY);}
    /** Game ticks these remains have lain in the world (decay clock, body/RemainsRot); carried, buried or frozen remains do not age. */
    private long rot;
    public long rot(){return rot;}
    public int stage(){return RemainsRot.stage(data());}
    /** Ticks these remains have spent in water (body/RemainsWater); like the decay clock it only runs in the world. */
    private long soak;
    private int waterStage;
    private boolean carriesItems;
    private BlockPos refusedCarriedDrop;
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
            soak=Math.max(0,tag.getLong(RemainsWater.SOAK));waterStage=RemainsWater.stage(tag);
            carriesItems=tag.contains(CARRIED_ITEMS,Tag.TAG_LIST);refreshDimensions();}
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
        return snapshot(source,cause,stemDepletion,sukkiriExhaustion,internalCause,null);
    }
    public static ItemStack snapshot(YukkuriEntity source,DamageSource cause,boolean stemDepletion,boolean sukkiriExhaustion,String internalCause,RemainsStem stem){
        var result=new ItemStack(ModItems.REMAINS.get());
        CustomData.update(DataComponents.CUSTOM_DATA,result,tag->{
            tag.putUUID("Identity",source.getUUID());tag.putString("Variant",source.getVariant().assetId());
            if(stem!=null)tag.put(RemainsStem.KEY,stem.save());
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
            tag.putInt("Headwear",Headwear.nativeVisual(source.visibleHeadwear()));
            var junk=source.life().custody().junk();
            if(junk!=null)tag.putString(JunkHeadwear.KEY,junk.id());
            tag.putLong("DiedAt",source.level().getGameTime());
            tag.putString("Stage",source.family().stageId());
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
            // Died of retention: the hardened leaf stays on with the stool it held and the swelling (LeafSwaddle.died hands it over).
            if(LeafSwaddle.RETENTION.equals(internalCause)&&source.leafSwaddle().wrapped())tag.put(LeafSwaddle.REMAINS_KEY,source.leafSwaddle().remainsWrap());
            tag.putString(RemainsCause.CATEGORY,RemainsCause.category(cause,internalCause,stemDepletion&&cause!=null&&cause.is(DamageTypes.STARVE),sukkiriExhaustion));
        });
        result.set(DataComponents.CUSTOM_NAME,Component.translatable(NAMED,source.getName()));return result;
    }
    public static boolean place(Level level,Vec3 position,float yaw,ItemStack stack,boolean checkCollision){
        if(level.isClientSide||!stack.is(ModItems.REMAINS.get()))return false;
        var corpse=ModEntities.REMAINS.get().create(level);if(corpse==null)return false;
        corpse.moveTo(position.x,position.y,position.z,yaw,0);
        corpse.setRemains(placedStem(level,position,yaw,stack));
        if(checkCollision&&(!level.noCollision(corpse)||!level.getFluidState(corpse.blockPosition()).isEmpty()))return false;
        return level.addFreshEntity(corpse);
    }
    public static ItemStack placedStem(Level level,Vec3 position,float yaw,ItemStack stack) {
        var data=stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag();
        var stem=RemainsStem.visible(data);
        if(stem==null)return stack;
        var placed=stack.copy();
        boolean folded=!level.noCollision(stem.extent(position,yaw));
        CustomData.update(DataComponents.CUSTOM_DATA,placed,tag->tag.putBoolean(RemainsStem.FOLDED,folded));
        return placed;
    }
    @Override public AABB getBoundingBoxForCulling(){
        var data=data();
        var stem=RemainsStem.visible(data);
        var body=super.getBoundingBoxForCulling();
        return stem==null||data.getBoolean(RemainsStem.FOLDED)?body:body.minmax(stem.extent(position(),getYRot()));
    }
    /** The existing physical remains can accept real mouth items when there was no supported release at death. */
    public static void retainCarriedItems(ItemStack remains, ListTag items) {
        if (!items.isEmpty()) CustomData.update(DataComponents.CUSTOM_DATA, remains, tag -> tag.put(CARRIED_ITEMS, items));
    }
    private void putDownCarriedItems() {
        if (!carriesItems || !onGround() || isInWaterOrBubble() || !level().getFluidState(blockPosition()).isEmpty()
                || blockPosition().equals(refusedCarriedDrop)) return;
        var items = data().getList(CARRIED_ITEMS, Tag.TAG_COMPOUND);
        if (items.isEmpty()) return;
        var at = position().add(0, .05, 0);
        if (!ItemContact.hasReleaseSupport(this, at)) return;
        while (!items.isEmpty()) {
            var stack = ItemStack.parseOptional(registryAccess(), items.getCompound(0));
            if (!stack.isEmpty() && ItemContact.release(this, stack, at, Vec3.ZERO) == null) {
                refusedCarriedDrop = blockPosition();
                break;
            }
            items.removeFirst();
        }
        var remains = remains();
        CustomData.update(DataComponents.CUSTOM_DATA, remains, tag -> {
            if (items.isEmpty()) tag.remove(CARRIED_ITEMS); else tag.put(CARRIED_ITEMS, items);
        });
        setRemains(remains);
    }
    @Override public InteractionResult interact(Player player,InteractionHand hand){
        if(isRemoved()||player.isSpectator()||!player.mayBuild()||player.distanceToSqr(this)>36)return InteractionResult.PASS;
        var held=player.getItemInHand(hand);
        if(held.is(ItemTags.SHOVELS))return bury(player,hand);
        if(held.is(ItemTags.SWORDS)||held.is(Tags.Items.TOOLS_SHEAR))return takeCore(player,hand);
        if(!held.isEmpty())return InteractionResult.PASS;
        if(player.isSecondaryUseActive() && JunkHeadwear.read(data())!=null){
            if(!level().isClientSide)releaseJunk(() -> {
                player.setItemInHand(hand,JunkHeadwear.item());
                return true;
            });
            return InteractionResult.sidedSuccess(level().isClientSide);
        }
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
        var core=FillingResource.core(data,getRandom());
        var stack=remains();CustomData.update(DataComponents.CUSTOM_DATA,stack,tag->tag.putBoolean("CoreTaken",true));setRemains(stack);
        return core;
    }
    private InteractionResult bury(Player player,InteractionHand hand){
        BlockPos top=graveTop();
        var tool=player.getItemInHand(hand);
        if(!buryable(top)||!level().mayInteract(player,top)||!level().mayInteract(player,top.below())
                ||!player.mayUseItemAt(top,Direction.UP,tool))return InteractionResult.FAIL;
        if(level().isClientSide)return InteractionResult.SUCCESS;
        if(!bury(player.getGameProfile().getName(),player.getDirection()))return InteractionResult.FAIL;
        if(!player.getAbilities().instabuild)tool.hurtAndBreak(1,player,LivingEntity.getSlotForHand(hand));
        return InteractionResult.CONSUME;
    }
    private BlockPos graveTop(){return BlockPos.containing(getX(),getY()+.05,getZ());}
    /** Remains can be shovelled into a mound where they lie on soil, with room above. */
    private boolean buryable(BlockPos top){return onGround()&&level().getBlockState(top.below()).is(BlockTags.DIRT)&&level().isEmptyBlock(top);}
    /**
     * Server side: shovel these remains into a mound where they lie (a player's shovel, or /yukkuri body bury as a showcase start).
     * The grave keeps the remains record plus its name and burial day; the marker faces {@code burier}, who looks {@code looking}.
     */
    public boolean bury(String burier,Direction looking){
        BlockPos top=graveTop();
        if(level().isClientSide||isRemoved()||!buryable(top))return false;
        putDownCarriedItems();
        if(carriesItems)return false;
        if(!putDownJunk())return false;
        // The marker faces the one who buried the body; the mound's head end is away from them.
        var facing=looking.getOpposite();
        if(!level().setBlockAndUpdate(top,ModBlocks.GRAVE.get().defaultBlockState().setValue(GraveBlock.FACING,facing)))return false;
        if(!(level().getBlockEntity(top) instanceof GraveBlockEntity grave)){
            level().removeBlock(top,false);return false;
        }
        var buried=data();var name=remains().get(DataComponents.CUSTOM_NAME);
        if(name!=null)buried.putString("Name",Component.Serializer.toJson(name,level().registryAccess()));
        buried.putLong(GraveBlockEntity.BURIED_DAY,level().getDayTime()/24000);
        grave.bury(buried);
        GameplayLog.event(this,"remains","buried by {} grave={} facing={} identity={} variant={} stage={} category={} buried_day={} rot={} water={}",
                burier,top.toShortString(),facing.getName(),buried.hasUUID("Identity")?buried.getUUID("Identity"):"none",
                buried.getString("Variant"),buried.contains("Stage")?buried.getString("Stage"):"none",RemainsCause.of(buried),
                buried.getLong(GraveBlockEntity.BURIED_DAY),RemainsRot.id(stage()),RemainsWater.id(waterStage));
        discard();
        return true;
    }
    /** A last processing or burial action leaves one physical item in reach, or waits for a successful release. */
    public boolean putDownJunk(){
        var at=position().add(0,.05,0);
        return releaseJunk(() -> ItemContact.hasReleaseSupport(this,at)
                && ItemContact.release(this,JunkHeadwear.item(),at,Vec3.ZERO)!=null);
    }
    private boolean releaseJunk(BooleanSupplier destination){
        var junk=JunkHeadwear.read(data());
        if(junk==null)return true;
        if(!destination.getAsBoolean())return false;
        var remains=remains();
        CustomData.update(DataComponents.CUSTOM_DATA,remains,tag->tag.remove(JunkHeadwear.KEY));
        setRemains(remains);
        GameplayLog.event(this,"headwear","remains junk released variant={}",junk.id());
        return true;
    }
    @Override public void tick(){
        super.tick();
        if(level().isClientSide){if(tickCount%10==0)rotParticles();return;}
        if(++rot%20==0)refreshStage();
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
        putDownCarriedItems();
    }
    /** A thin haze of spores over stale remains, a heavier one over rotten ones (client only). */
    private void rotParticles(){
        int stage=stage();if(stage==0||random.nextInt(stage==2?2:5)!=0)return;
        level().addParticle(stage==2?ParticleTypes.SMOKE:ParticleTypes.MYCELIUM,
                getX()+(random.nextDouble()-.5)*getBbWidth(),getY()+getBbHeight()*.7,getZ()+(random.nextDouble()-.5)*getBbWidth(),0,.02,0);
    }
    @Override protected void addAdditionalSaveData(CompoundTag tag){if(!remains().isEmpty())tag.put("Remains",remains().save(registryAccess()));}
    @Override protected void readAdditionalSaveData(CompoundTag tag) {
        var stack = ItemStack.parseOptional(registryAccess(), tag.getCompound("Remains"));
        var data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
        if (data.contains("FactoryOwner") || data.contains("FactorySource")) {
            CustomData.update(DataComponents.CUSTOM_DATA, stack, saved -> {
                saved.remove("FactoryOwner");
                saved.remove("FactorySource");
            });
            GameplayLog.event(this, "remains", "legacy factory claim cleared");
        }
        setRemains(stack);
    }
}
