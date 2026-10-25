package io.github.longshou.mcyukkuri.entity;

import io.github.longshou.mcyukkuri.life.EcologyRoutines;
import io.github.longshou.mcyukkuri.body.BodyFood;
import io.github.longshou.mcyukkuri.body.BodyWound;
import io.github.longshou.mcyukkuri.entity.YukkuriSyncState.Field;
import io.github.longshou.mcyukkuri.entity.component.SyncSlot;
import io.github.longshou.mcyukkuri.family.ChildhoodLife;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.family.FamilyLife;
import io.github.longshou.mcyukkuri.subspecies.crew.CrewLegacy;
import io.github.longshou.mcyukkuri.status.Marks;
import io.github.longshou.mcyukkuri.subspecies.SubspeciesLife;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

/** Synchronized schema, values and decoded caches for exactly one entity. */
final class YukkuriSync {
    private static final EntityDataAccessor<String> ANIMATION = YukkuriEntity.defineSyncedValue( EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Long> ANIMATION_START = YukkuriEntity.defineSyncedValue( EntityDataSerializers.LONG);
    private static final EntityDataAccessor<String> EXPRESSION = YukkuriEntity.defineSyncedValue( EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> BEHAVIOR = YukkuriEntity.defineSyncedValue( EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Long> BEHAVIOR_START = YukkuriEntity.defineSyncedValue( EntityDataSerializers.LONG);
    private static final EntityDataAccessor<Integer> NUZZLE_PARTNER = YukkuriEntity.defineSyncedValue( EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> CARE_PARTNER = YukkuriEntity.defineSyncedValue( EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> CARE_PROGRESS = YukkuriEntity.defineSyncedValue( EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Boolean> CRAWLING = YukkuriEntity.defineSyncedValue( EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Long> CRAWL_START = YukkuriEntity.defineSyncedValue( EntityDataSerializers.LONG);
    private static final EntityDataAccessor<String> DIALOGUE = YukkuriEntity.defineSyncedValue( EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Long> SPEECH_START = YukkuriEntity.defineSyncedValue( EntityDataSerializers.LONG);
    private static final EntityDataAccessor<Long> SPEECH_END = YukkuriEntity.defineSyncedValue( EntityDataSerializers.LONG);
    private static final EntityDataAccessor<Integer> SPEECH_SEQUENCE = YukkuriEntity.defineSyncedValue( EntityDataSerializers.INT);
    private static final EntityDataAccessor<ItemStack> MOUTH = YukkuriEntity.defineSyncedValue( EntityDataSerializers.ITEM_STACK);
    /** A body-food bite is display-only transport, deliberately separate from real mouth items. */
    private static final EntityDataAccessor<CompoundTag> MOUTH_BODY_FOOD = YukkuriEntity.defineSyncedValue(EntityDataSerializers.COMPOUND_TAG);
    private static final EntityDataAccessor<ItemStack> AQUATIC_SERVING = YukkuriEntity.defineSyncedValue( EntityDataSerializers.ITEM_STACK);
    private static final EntityDataAccessor<Integer> AQUATIC_CHILD = YukkuriEntity.defineSyncedValue( EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> MEDAL = YukkuriEntity.defineSyncedValue( EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> ACCESSORY = YukkuriEntity.defineSyncedValue( EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Float> GROWTH_SCALE = YukkuriEntity.defineSyncedValue( EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Integer> LIFE_STAGE = YukkuriEntity.defineSyncedValue( EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> BROOD_COUNT = YukkuriEntity.defineSyncedValue( EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> BROOD_BORN = YukkuriEntity.defineSyncedValue( EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> BROOD_TYPES = YukkuriEntity.defineSyncedValue( EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> BROOD_PROGRESS = YukkuriEntity.defineSyncedValue( EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> BROOD_VARIATION = YukkuriEntity.defineSyncedValue( EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Boolean> SPENT_STEM = YukkuriEntity.defineSyncedValue( EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Integer> AFTERMATH_STATE = YukkuriEntity.defineSyncedValue( EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> LIVE_PROGRESS = YukkuriEntity.defineSyncedValue( EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Integer> LIVE_DELIVERY = YukkuriEntity.defineSyncedValue( EntityDataSerializers.INT);
    private static final EntityDataAccessor<CompoundTag> LIVE_CHILDREN = YukkuriEntity.defineSyncedValue( EntityDataSerializers.COMPOUND_TAG);
    private static final EntityDataAccessor<Boolean> PREDATOR_FLIGHT = YukkuriEntity.defineSyncedValue( EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Integer> SUBSPECIES = YukkuriEntity.defineSyncedValue( EntityDataSerializers.INT);
    private static final EntityDataAccessor<CompoundTag> AGITATION = YukkuriEntity.defineSyncedValue( EntityDataSerializers.COMPOUND_TAG);
    private static final EntityDataAccessor<CompoundTag> WATER_DISTRESS = YukkuriEntity.defineSyncedValue( EntityDataSerializers.COMPOUND_TAG);
    /** The hull whose CrewState carries the transfer this body or carrier takes part in (entity id, -1 none); the transfer itself is on the hull. */
    private static final EntityDataAccessor<Integer> CREW_BOAT = YukkuriEntity.defineSyncedValue( EntityDataSerializers.INT);
    private static final EntityDataAccessor<CompoundTag> AQUATIC_ALARM = YukkuriEntity.defineSyncedValue( EntityDataSerializers.COMPOUND_TAG);
    private static final EntityDataAccessor<CompoundTag> AQUATIC_SELF_RESCUE = YukkuriEntity.defineSyncedValue( EntityDataSerializers.COMPOUND_TAG);
    private static final EntityDataAccessor<Integer> HEADWEAR = YukkuriEntity.defineSyncedValue( EntityDataSerializers.INT);
    private static final EntityDataAccessor<CompoundTag> MARKS=YukkuriEntity.defineSyncedValue(EntityDataSerializers.COMPOUND_TAG);
    private List<BodyWound> marksCache=List.of();
    private static final EntityDataAccessor<Integer> WOUND=YukkuriEntity.defineSyncedValue(EntityDataSerializers.INT);
    private static final EntityDataAccessor<CompoundTag> WOUNDS=YukkuriEntity.defineSyncedValue(EntityDataSerializers.COMPOUND_TAG);
    private static final EntityDataAccessor<Integer> BODY_SIGNS=YukkuriEntity.defineSyncedValue(EntityDataSerializers.INT);
    private static final EntityDataAccessor<ItemStack> CARRYING=YukkuriEntity.defineSyncedValue(EntityDataSerializers.ITEM_STACK);
    private static final EntityDataAccessor<String> CONTACT_POSE=YukkuriEntity.defineSyncedValue(EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Float> CONTACT_TURN=YukkuriEntity.defineSyncedValue(EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Optional<BlockPos>> CONTACT_ANCHOR=YukkuriEntity.defineSyncedValue(EntityDataSerializers.OPTIONAL_BLOCK_POS);
    private static final EntityDataAccessor<Integer> HAIR_LOSS=YukkuriEntity.defineSyncedValue(EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> WEAR=YukkuriEntity.defineSyncedValue(EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> KANROKEN=YukkuriEntity.defineSyncedValue(EntityDataSerializers.BOOLEAN);
    private YukkuriSyncState.Agitation agitationSnapshot;
    private YukkuriSyncState.WaterDistress waterDistressSnapshot;
    private YukkuriSyncState.AquaticAlarm aquaticAlarmSnapshot;
    private YukkuriSyncState.BraidRescue braidRescueSnapshot;
    private YukkuriSyncState.Boarding boardingSnapshot;
    private CompoundTag braidRescueTag = new CompoundTag(), boardingTag = new CompoundTag();
    private long legacyTick = Long.MIN_VALUE;
    private YukkuriSyncState.SelfRescue selfRescueSnapshot;
    private YukkuriSyncState.LiveChildren liveChildrenSnapshot;
    private List<BodyWound> woundsSnapshot;
    private List<BodyWound> sentWounds;
    private YukkuriSyncState.LiveChildren sentLiveChildren;

    private final YukkuriEntity owner;
    YukkuriSync(YukkuriEntity owner) { this.owner = owner; }
    /** Called before the entity installs component slots; invoking this forces all legacy IDs first. */
    static void initializeLegacySchema() {}
    int visibleHeadwear() { return owner.getEntityData().get(HEADWEAR); }
    void syncHeadwear(int packed) { if (owner.getEntityData().get(HEADWEAR) != packed) owner.getEntityData().set(HEADWEAR, packed); }
    <T> T componentData(SyncSlot<T> slot) { return slot.copy(owner.getEntityData().get(slot.accessor())); }
    <T> void syncComponentData(SyncSlot<T> slot, T value) {
        if (!owner.level().isClientSide) owner.getEntityData().set(slot.accessor(), slot.copy(value));
    }
    CompoundTag agitationState() { return owner.getEntityData().get(AGITATION); }
    boolean aftermathBurden() { return owner.getEntityData().get(AFTERMATH_STATE) > 0; }
    boolean aftermathWeak() { return owner.getEntityData().get(AFTERMATH_STATE) == 2; }
    String waterDistressPhase() { return waterDistressSync().phase().orElse("IDLE"); }
    long waterDistressStartTick() { return waterDistressSync().start().value(); }
    boolean waterDistressEffort() { return waterDistressSync().effort().value(); }
    /** The legacy braid-contact view of the hull's CrewState (the client's minimal adapter until the contact layer is rebuilt). */
    CompoundTag braidRescueState() { legacy(); return braidRescueTag; }
    String braidRescuePhase() { return braidRescueSync().phase().orElse("IDLE"); }
    long braidRescueStartTick() { return braidRescueSync().start().value(); }
    CompoundTag boardingState() { legacy(); return boardingTag; }
    int crewBoat() { return owner.getEntityData().get(CREW_BOAT); }
    private void legacy() {
        long now = owner.level().getGameTime();
        if (legacyTick == now) return;
        legacyTick = now;
        braidRescueTag = CrewLegacy.braid(owner, crewBoat());
        boardingTag = CrewLegacy.boarding(owner, crewBoat());
        braidRescueSnapshot = YukkuriSyncState.BraidRescue.read(braidRescueTag);
        boardingSnapshot = YukkuriSyncState.Boarding.read(boardingTag);
    }
    CompoundTag aquaticSelfRescueState() { return owner.getEntityData().get(AQUATIC_SELF_RESCUE); }
    int aquaticRescueTargetId() { return aquaticAlarmSync().rescueTargetId().orElse(-1); }
    String aquaticAlarmActivity() { return aquaticAlarmSync().activity().orElse("idle"); }
    long aquaticAlarmStartTick() { return aquaticAlarmSync().start().value(); }
    int aquaticAlarmSourceId() { return aquaticAlarmSync().sourceId().orElse(-1); }
    YukkuriSyncState.Agitation agitationSync() {
        if (agitationSnapshot == null) agitationSnapshot = YukkuriSyncState.Agitation.read(owner.getEntityData().get(AGITATION));
        return agitationSnapshot;
    }
    YukkuriSyncState.WaterDistress waterDistressSync() {
        if (waterDistressSnapshot == null) waterDistressSnapshot = YukkuriSyncState.WaterDistress.read(owner.getEntityData().get(WATER_DISTRESS));
        return waterDistressSnapshot;
    }
    YukkuriSyncState.AquaticAlarm aquaticAlarmSync() {
        if (aquaticAlarmSnapshot == null) aquaticAlarmSnapshot = YukkuriSyncState.AquaticAlarm.read(owner.getEntityData().get(AQUATIC_ALARM));
        return aquaticAlarmSnapshot;
    }
    YukkuriSyncState.BraidRescue braidRescueSync() {
        legacy();
        return braidRescueSnapshot;
    }
    YukkuriSyncState.Boarding boardingSync() {
        legacy();
        return boardingSnapshot;
    }
    YukkuriSyncState.SelfRescue selfRescueSync() {
        if (selfRescueSnapshot == null) selfRescueSnapshot = YukkuriSyncState.SelfRescue.read(owner.getEntityData().get(AQUATIC_SELF_RESCUE));
        return selfRescueSnapshot;
    }
    YukkuriSyncState.LiveChildren liveChildrenSync() {
        if (liveChildrenSnapshot == null) liveChildrenSnapshot = YukkuriSyncState.LiveChildren.read(owner.getEntityData().get(LIVE_CHILDREN));
        return liveChildrenSnapshot;
    }
    void syncAquaticInteraction() {
        int boat=owner.family().crew().boatId();
        if(boat!=owner.getEntityData().get(CREW_BOAT))owner.getEntityData().set(CREW_BOAT,boat);
        var selfRescue=owner.aquaticLife().selfRescue().syncState();
        if(!selfRescue.equals(owner.getEntityData().get(AQUATIC_SELF_RESCUE)))owner.getEntityData().set(AQUATIC_SELF_RESCUE,selfRescue);
        var controller=owner.aquaticLife().familyLife();
        String activity=controller.alarmActivity();
        var alarm = activity.equals(Behaviors.IDLE) ? YukkuriSyncState.NO_AQUATIC_ALARM
                : new YukkuriSyncState.AquaticAlarm(Field.of(activity), Field.of(controller.alarmPhaseStart()),
                        Field.of(controller.alarmSourceId()), Field.of(controller.rescueTargetId()));
        setChanged(AQUATIC_ALARM, alarm.encode());
    }
    void syncWaterDistress() {
        YukkuriSyncState.WaterDistress water;
        if (owner.waterDistress().airbornePlunge()) {
            water = new YukkuriSyncState.WaterDistress(Field.of("FALLING"),
                    Field.of(owner.level().getGameTime() - owner.waterDistress().airbornePlungeTicks()), Field.of(false));
        } else if (owner.waterDistress().active()) {
            water = new YukkuriSyncState.WaterDistress(Field.of(owner.waterDistress().phase().name()),
                    Field.of(owner.level().getGameTime() - owner.waterDistress().phaseTicks()), Field.of(owner.waterDistress().effort()));
        } else water = YukkuriSyncState.NO_WATER_DISTRESS;
        setChanged(WATER_DISTRESS, water.encode());
    }
    boolean predatorFlying() { return owner.getEntityData().get(PREDATOR_FLIGHT); }
    String getAnimationId() { return owner.getEntityData().get(ANIMATION); }
    long getAnimationStartTick() { return owner.getEntityData().get(ANIMATION_START); }
    String getExpressionId() { return owner.getEntityData().get(EXPRESSION); }
    String getBehaviorId() { return owner.getEntityData().get(BEHAVIOR); }
    long getBehaviorStartTick() { return owner.getEntityData().get(BEHAVIOR_START); }
    int nuzzlePartnerId() { return owner.getEntityData().get(NUZZLE_PARTNER); }
    int carePartnerId() { return owner.getEntityData().get(CARE_PARTNER); }
    float careContactProgress() { return owner.getEntityData().get(CARE_PROGRESS); }
    boolean isCrawling() { return owner.getEntityData().get(CRAWLING); }
    long getCrawlStartTick() { return owner.getEntityData().get(CRAWL_START); }
    void syncCrawl(boolean active) {
        if (owner.level().isClientSide) return;
        active &= owner.canCrawlNow();
        if (active && !isCrawling()) owner.getEntityData().set(CRAWL_START, owner.level().getGameTime());
        owner.getEntityData().set(CRAWLING, active);
    }
    String getDialogueKey() { return owner.getEntityData().get(DIALOGUE); }
    long speechStart() { return owner.getEntityData().get(SPEECH_START); }
    long speechEnd() { return owner.getEntityData().get(SPEECH_END); }
    int speechSequence() { return owner.getEntityData().get(SPEECH_SEQUENCE); }
    ItemStack getMouthItem() { return owner.getEntityData().get(MOUTH); }
    BodyFood.Bite getMouthBodyFood() { return BodyFood.Bite.load(owner.getEntityData().get(MOUTH_BODY_FOOD)); }
    void syncMouthBodyFood(BodyFood.Bite bite) {
        if (owner.level().isClientSide) return;
        var value = bite == null || bite.isEmpty() ? new CompoundTag() : bite.save();
        setChanged(MOUTH_BODY_FOOD, value);
    }
    ItemStack aquaticServing() { return owner.getEntityData().get(AQUATIC_SERVING); }
    int aquaticChildId() { return owner.getEntityData().get(AQUATIC_CHILD); }
    int visibleMedal() { return owner.getEntityData().get(MEDAL); }
    boolean hasVisibleAccessory() { return owner.getEntityData().get(ACCESSORY); }
    boolean visibleKanroken() { return owner.getEntityData().get(KANROKEN); }
    int subspeciesFlags() { return owner.getEntityData().get(SUBSPECIES); }
    void syncSubspecies(int flags) { if (!owner.level().isClientSide) owner.getEntityData().set(SUBSPECIES, flags); }
    ItemStack getCarriedItem(){return owner.getEntityData().get(CARRYING);}
    List<BodyWound> visibleMarks(){return marksCache;}
    void syncMarks(CompoundTag marks){if(owner.level().isClientSide)return;owner.getEntityData().set(MARKS,marks);marksCache=Marks.read(marks);}
    String contactPose(){return owner.getEntityData().get(CONTACT_POSE);}
    float contactTurn(){return owner.getEntityData().get(CONTACT_TURN);}
    int visibleHairLoss(){return owner.getEntityData().get(HAIR_LOSS);}
    void syncOperation(String pose,float turn){owner.getEntityData().set(CONTACT_POSE,pose);owner.getEntityData().set(CONTACT_TURN,turn);if(pose.equals("NONE"))syncOperationAnchor(null);}
    void syncOperationAnchor(BlockPos anchor){owner.getEntityData().set(CONTACT_ANCHOR,Optional.ofNullable(anchor));}
    BlockPos contactAnchor(){return owner.getEntityData().get(CONTACT_ANCHOR).orElse(null);}
    int visibleWound(){return owner.getEntityData().get(WOUND);}
    List<BodyWound> visibleWounds() {
        if (woundsSnapshot == null) woundsSnapshot = BodyWound.read(owner.getEntityData().get(WOUNDS));
        return woundsSnapshot;
    }
    /** Legacy display scale only; health remains the sole synchronized survival value. */
    int visibleFilling(){
        float maximum=owner.getMaxHealth();
        return maximum<=0?0:Math.clamp(Math.round(owner.getHealth()/maximum*12),0,12);
    }
    void syncBody() {
        if (owner.body() == null || owner.level().isClientSide) return;
        setChanged(WOUND, Math.max(owner.body().wound(), owner.body().burns()));
        // BodyLife returns an immutable copy; ordered record equality includes emission clocks.
        var wounds = owner.body().wounds();
        if (!wounds.equals(sentWounds)) {
            setChanged(WOUNDS, BodyWound.write(wounds));
            sentWounds = wounds;
        }
        setChanged(HAIR_LOSS, owner.body().hairLoss());
        setChanged(BODY_SIGNS, owner.body().signs());
    }
    <T> void setChanged(EntityDataAccessor<T> key, T value) {
        if (!Objects.equals(owner.getEntityData().get(key), value)) owner.getEntityData().set(key, value);
    }
    int visibleSigns(){return owner.getEntityData().get(BODY_SIGNS);}
    float growthScale() { return owner.getEntityData().get(GROWTH_SCALE)*((subspeciesFlags()&SubspeciesLife.DEIBU_FLAG)!=0?1.5f:1); }
    int lifeStage() { return owner.getEntityData().get(LIFE_STAGE); }
    int broodCount() { return owner.getEntityData().get(BROOD_COUNT); }
    int broodBornMask() { return owner.getEntityData().get(BROOD_BORN); }
    float broodProgress() { return owner.getEntityData().get(BROOD_PROGRESS); }
    float broodVariation() { return owner.getEntityData().get(BROOD_VARIATION); }
    boolean hasSpentStem() { return owner.getEntityData().get(SPENT_STEM); }
    float livePregnancyProgress() { return owner.getEntityData().get(LIVE_PROGRESS); }
    int liveDeliveryTicks() { return owner.getEntityData().get(LIVE_DELIVERY); }
    int liveChildCount() { return liveChildrenSync().count().value(); }
    int liveBornMask() { return liveChildrenSync().born().value(); }
    YukkuriVariant liveChildVariant(int index) {
        int ordinal=(liveChildrenSync().types().value()>>(index*4))&15;
        return YukkuriVariant.values()[Math.min(ordinal,YukkuriVariant.values().length-1)];
    }
    String liveChildAssetId(int index) {
        int flags=liveChildrenSync().morphAt(index);
        return SubspeciesLife.assetId(liveChildVariant(index),flags);
    }
    YukkuriVariant broodVariant(int index) { return YukkuriVariant.values()[(owner.getEntityData().get(BROOD_TYPES)>>(index*4))&15]; }
    void syncFamily() {
        if(owner.family()==null||owner.level().isClientSide)return;
        setChanged(GROWTH_SCALE,owner.family().scale());setChanged(LIFE_STAGE,owner.family().stage().ordinal());
        var brood=owner.isAlive()?owner.family().brood():null;int count=owner.isAlive()?owner.family().spentStemCount():0,born=(1<<count)-1,
                types=0;float progress=1,variation=owner.family().spentStemVariation();
        setChanged(AFTERMATH_STATE,brood!=null&&!brood.cancelled&&!brood.complete()&&brood.incidentSource!=null
                ?owner.life().nutrition()<30||owner.life().energy()<25?2:1:0);
        if(brood!=null&&!brood.cancelled&&brood.kind==FamilyData.BirthKind.STEM) {
            count=brood.children.size();born=0;progress=brood.progress();variation=brood.variation;
            for(int i=0;i<count;i++){var child=brood.children.get(i);if(child.born)born|=1<<i;types|=child.variant.ordinal()<<(i*4);}
        }
        setChanged(BROOD_COUNT,count);setChanged(BROOD_BORN,born);setChanged(BROOD_TYPES,types);
        setChanged(BROOD_PROGRESS,progress);setChanged(BROOD_VARIATION,variation);setChanged(SPENT_STEM,owner.isAlive()&&owner.family().spentStemCount()>0);
        boolean activeLive=brood!=null&&!brood.cancelled&&!brood.complete()&&brood.kind==FamilyData.BirthKind.LIVE;
        int delivery=-1;
        if (activeLive) {
            int liveCount=brood.children.size(),liveBorn=0,liveTypes=0;
            boolean changed=sentLiveChildren==null||!sentLiveChildren.count().present()
                    ||sentLiveChildren.count().value()!=liveCount;
            for(int i=0;i<liveCount;i++){
                var child=brood.children.get(i);
                if(child.born)liveBorn|=1<<i;
                liveTypes|=child.variant.ordinal()<<(i*4);
                if(!changed&&sentLiveChildren.morphAt(i)!=child.subspeciesFlags)changed=true;
            }
            if(changed||sentLiveChildren.born().value()!=liveBorn||sentLiveChildren.types().value()!=liveTypes){
                var morphs=new ArrayList<Integer>(liveCount);
                for(var child:brood.children)morphs.add(child.subspeciesFlags);
                var live=YukkuriSyncState.LiveChildren.active(liveCount,morphs,liveBorn,liveTypes);
                setChanged(LIVE_CHILDREN,live.encode());
                sentLiveChildren=live;
            }
            delivery=Math.max(-1,brood.elapsed-brood.duration);
        } else if(sentLiveChildren==null||sentLiveChildren.count().present()){
            var live=YukkuriSyncState.LiveChildren.inactive();
            setChanged(LIVE_CHILDREN,live.encode());
            sentLiveChildren=live;
        }
        setChanged(LIVE_DELIVERY,delivery);setChanged(LIVE_PROGRESS,owner.isAlive()?owner.family().liveBelly():0f);
    }
    void setAnimation(YukkuriAction action, boolean restart) {
        if (restart || !getAnimationId().equals(action.id())) {
            owner.getEntityData().set(ANIMATION_START, owner.level().getGameTime());
            owner.getEntityData().set(ANIMATION, action.id());
        }
    }
    void setBehavior(String behavior) {
        if (!getBehaviorId().equals(behavior)) {
            owner.getEntityData().set(BEHAVIOR, behavior); owner.getEntityData().set(BEHAVIOR_START, owner.level().getGameTime());
        }
    }
    int visibleWear() { return owner.getEntityData().get(WEAR); }
    void setWear(int bits) { owner.getEntityData().set(WEAR, bits); }
    void syncKanroken() { owner.getEntityData().set(KANROKEN, owner.myon().hasKanroken()); }
    void syncAgitation() { owner.getEntityData().set(AGITATION, owner.agitation().state()); }
    void syncHairLoss() { owner.getEntityData().set(HAIR_LOSS, owner.body().hairLoss()); }
    void syncCarryingAndCare() {
        var familyPaddle=owner.aquaticLife().familyPaddle().carried();
        owner.getEntityData().set(CARRYING,
                (!familyPaddle.isEmpty()?familyPaddle:!owner.family().carrying().isEmpty()?owner.family().carrying():!owner.independence().carrying().isEmpty()
                        ?owner.independence().carrying():!owner.settlement().duties().carrying().isEmpty()?owner.settlement().duties().carrying()
                        :!owner.leafSwaddle().carried().isEmpty()?owner.leafSwaddle().carried():EcologyRoutines.carryingFallback(owner)).copy());
        owner.getEntityData().set(NUZZLE_PARTNER,owner.dailyLife().nuzzlePartnerId());
        int careTarget = owner.family().careTargetId();
        float careProgress = owner.family().careContactProgress() / 20f;
        if (owner.aquaticLife().care().active()) {
            careTarget = owner.aquaticLife().care().childId();
            careProgress = owner.aquaticLife().care().contactProgress() / (owner.aquaticLife().care().activity().equals(Behaviors.FIRST_TOILET_ASSIST)
                    ? (float) ChildhoodLife.FIRST_TOILET_CONTACT_TICKS : 20f);
        }
        if (owner.childhood().active() && owner.childhood().careTargetId() != null) {
            var partner = ((ServerLevel) owner.level()).getEntity(owner.childhood().careTargetId());
            careTarget = partner == null ? -1 : partner.getId();
            careProgress = owner.childhood().careProgress() / (float) ChildhoodLife.FIRST_TOILET_CONTACT_TICKS;
        }
        if (owner.leafSwaddle().caring()) {
            var partner = owner.leafSwaddle().careTarget();
            careTarget = partner == null ? -1 : partner.getId();
            careProgress = owner.leafSwaddle().contactProgress();
        }
        if (owner.woundComfort().licking()) { careTarget = owner.woundComfort().victimId(); careProgress = owner.woundComfort().contactProgress(); }
        owner.getEntityData().set(CARE_PARTNER, careTarget);
        owner.getEntityData().set(CARE_PROGRESS, Mth.clamp(careProgress, 0, 1));
        if (!ItemStack.matches(owner.getEntityData().get(AQUATIC_SERVING),owner.aquaticLife().care().serving()))
            owner.getEntityData().set(AQUATIC_SERVING,owner.aquaticLife().care().serving().copy());
        owner.getEntityData().set(AQUATIC_CHILD,owner.aquaticLife().care().childId());
    }
    void syncAppearance() {
        if (!ItemStack.matches(owner.getEntityData().get(MOUTH), owner.life().mouth())) owner.getEntityData().set(MOUTH, owner.life().mouth().copy());
        owner.getEntityData().set(MEDAL, owner.pet().visibleGrade().id()); owner.getEntityData().set(ACCESSORY, owner.life().hasAccessory());
        owner.getEntityData().set(WEAR, owner.wornBits());
    }
    void resetDeathPresentation() {
        setAnimation(YukkuriAction.IDLE, true);
        owner.getEntityData().set(EXPRESSION, "neutral");
    }
    void resetLoadedPresentation() {
        setAnimation(YukkuriAction.IDLE, true);
        owner.getEntityData().set(EXPRESSION, "neutral");
        owner.getEntityData().set(MEDAL, owner.pet().visibleGrade().id()); owner.getEntityData().set(ACCESSORY,
                owner.life().hasAccessory()); owner.getEntityData().set(MOUTH, owner.life().mouth().copy());
        owner.getEntityData().set(WEAR, owner.wornBits());
    }
    static void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(SUBSPECIES, 0);
        builder.define(ANIMATION, "idle");
        builder.define(ANIMATION_START, 0L);
        builder.define(EXPRESSION, "neutral");
        builder.define(BEHAVIOR, "idle"); builder.define(DIALOGUE, "");
        builder.define(SPEECH_START, 0L); builder.define(SPEECH_END, 0L); builder.define(SPEECH_SEQUENCE, 0);
        builder.define(BEHAVIOR_START, 0L);
        builder.define(NUZZLE_PARTNER, -1); builder.define(CARE_PARTNER, -1); builder.define(CARE_PROGRESS, 0f);
        builder.define(CRAWLING, false); builder.define(CRAWL_START, 0L);
        builder.define(PREDATOR_FLIGHT, false);
        builder.define(MEDAL, 0); builder.define(MOUTH, ItemStack.EMPTY); builder.define(MOUTH_BODY_FOOD, new CompoundTag()); builder.define(ACCESSORY, true);
        builder.define(AQUATIC_SERVING,ItemStack.EMPTY); builder.define(AQUATIC_CHILD,-1);
        builder.define(CARRYING,ItemStack.EMPTY);
        builder.define(WOUND,0);
        builder.define(WOUNDS,new CompoundTag());builder.define(BODY_SIGNS,0);
        builder.define(CONTACT_POSE,"NONE");builder.define(CONTACT_TURN,0f);builder.define(HAIR_LOSS,0);builder.define(CONTACT_ANCHOR,Optional.empty());
        builder.define(GROWTH_SCALE,1f);builder.define(LIFE_STAGE,FamilyLife.Stage.ADULT.ordinal());
        builder.define(BROOD_COUNT,0);builder.define(BROOD_BORN,0);builder.define(BROOD_TYPES,0);
        builder.define(BROOD_PROGRESS,0f);builder.define(BROOD_VARIATION,1f);builder.define(SPENT_STEM,false);
        builder.define(AFTERMATH_STATE,0);
        builder.define(LIVE_PROGRESS,0f);builder.define(LIVE_DELIVERY,-1);builder.define(LIVE_CHILDREN,new CompoundTag());
        builder.define(WATER_DISTRESS,new CompoundTag());
        builder.define(AGITATION,new CompoundTag());
        builder.define(CREW_BOAT,-1);builder.define(AQUATIC_ALARM,new CompoundTag());
        builder.define(AQUATIC_SELF_RESCUE,new CompoundTag());
        builder.define(WEAR,0);builder.define(MARKS,new CompoundTag());builder.define(HEADWEAR,100<<4);builder.define(KANROKEN,false);
    }
    void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        // set() calls this on the server too. Replace only this accessor's decoded value.
        if (AGITATION.equals(key)) agitationSnapshot = YukkuriSyncState.Agitation.read(owner.getEntityData().get(AGITATION));
        else if (WATER_DISTRESS.equals(key)) waterDistressSnapshot = YukkuriSyncState.WaterDistress.read(owner.getEntityData().get(WATER_DISTRESS));
        else if (AQUATIC_ALARM.equals(key)) aquaticAlarmSnapshot = YukkuriSyncState.AquaticAlarm.read(owner.getEntityData().get(AQUATIC_ALARM));
        else if (AQUATIC_SELF_RESCUE.equals(key)) selfRescueSnapshot = YukkuriSyncState.SelfRescue.read(owner.getEntityData().get(AQUATIC_SELF_RESCUE));
        else if (LIVE_CHILDREN.equals(key)) liveChildrenSnapshot = YukkuriSyncState.LiveChildren.read(owner.getEntityData().get(LIVE_CHILDREN));
        else if (WOUNDS.equals(key)) woundsSnapshot = BodyWound.read(owner.getEntityData().get(WOUNDS));
        else if (MARKS.equals(key)) marksCache=Marks.read(owner.getEntityData().get(MARKS));
        if(GROWTH_SCALE.equals(key)||LIVE_PROGRESS.equals(key)||SUBSPECIES.equals(key))owner.refreshDimensions();
    }
    void invalidateSentStructures() {
        sentWounds = null;
        sentLiveChildren = null;
    }
    void animationId(String value) { owner.getEntityData().set(ANIMATION, value); }
    void animationStart(long value) { owner.getEntityData().set(ANIMATION_START, value); }
    void expression(String value) { owner.getEntityData().set(EXPRESSION, value); }
    void predatorFlight(boolean value) { owner.getEntityData().set(PREDATOR_FLIGHT, value); }
    void dialogue(String value) { owner.getEntityData().set(DIALOGUE, value); }
    void speechStart(long value) { owner.getEntityData().set(SPEECH_START, value); }
    void speechEnd(long value) { owner.getEntityData().set(SPEECH_END, value); }
    void speechSequence(int value) { owner.getEntityData().set(SPEECH_SEQUENCE, value); }
}
