package io.github.longshou.mcyukkuri.entity;

import io.github.longshou.mcyukkuri.body.BodyWound;
import io.github.longshou.mcyukkuri.body.BodyFood;
import io.github.longshou.mcyukkuri.family.FamilyLife;
import io.github.longshou.mcyukkuri.family.ParentCarry;
import io.github.longshou.mcyukkuri.subspecies.HatBoatView;
import io.github.longshou.mcyukkuri.subspecies.SubspeciesLife;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Client presentation boundary: synchronized values and ordinary entity geometry only.
 * No server component, original entity, navigation or mutator is exposed. */
public interface YukkuriView {
    YukkuriVariant getVariant();
    SpeciesProfile profile();
    int subspeciesFlags();
    default String assetId() { return SubspeciesLife.assetId(getVariant(), subspeciesFlags()); }
    default SubspeciesLife.Kind subspeciesKind() {
        return SubspeciesLife.Kind.values()[Math.min(SubspeciesLife.Kind.values().length - 1, subspeciesFlags() & 15)];
    }
    default SubspeciesLife.Congenital congenitalForm() {
        return SubspeciesLife.Congenital.values()[Math.min(3, (subspeciesFlags() >> 4) & 15)];
    }
    int lifeStage();
    boolean isBaby();
    default boolean adult() { return lifeStage() == FamilyLife.Stage.ADULT.ordinal(); }
    float growthScale();
    float getScale();
    int getId();
    UUID getUUID();
    Level level();
    Vec3 position();
    Vec3 getPosition(float partial);
    Vec3 getDeltaMovement();
    double getX();
    double getY();
    double getZ();
    float getYRot();
    float getYHeadRot();
    float getBbWidth();
    float getBbHeight();
    AABB getBoundingBox();
    AABB getBoundingBoxForCulling();
    BlockPos blockPosition();
    boolean isAlive();
    boolean isRemoved();
    boolean isDeadOrDying();
    float getHealth();
    float getMaxHealth();
    boolean isInvisible();
    boolean isSilent();
    boolean isPassenger();
    boolean isVehicle();
    boolean onGround();
    boolean hasCustomName();
    Component getDisplayName();
    float bodyYaw();
    float previousBodyYaw();
    float previousHeadYaw();
    float previousYaw();
    int ageTicks();
    int hurtTicks();
    int deathTicks();
    /** -1 when there is no vehicle; entity IDs are used only within this view's world. */
    int vehicleId();
    YukkuriView vehicleYukkuri();
    /** The yukkuri riding this one (a carried baby), or null. */
    YukkuriView carriedYukkuri();
    /** The yukkuri riding this one, in seat (boarding) order; several carried babies are laid out from this list. */
    List<YukkuriView> passengerViews();
    /** How this actor holds the baby riding on it (brim, mouth, tail), or null; derived from synchronized size and form. */
    default ParentCarry.Mode carryMode() { return ParentCarry.carryMode(this); }
    HatBoatView vehicleHat();
    boolean hasPassengerId(UUID passenger);
    /** Native line-of-sight query, without exposing the entity behind another view. */
    boolean hasSightTo(YukkuriView other);
    /** COLLIDER/NONE block ray using this actor's original collision context. */
    boolean clearColliderPath(Vec3 from, Vec3 to);
    default YukkuriView yukkuriView(int id) { return level().getEntity(id) instanceof YukkuriView view ? view : null; }
    default HatBoatView hatBoatView(int id) { return level().getEntity(id) instanceof HatBoatView view ? view : null; }
    default double distanceSquaredTo(YukkuriView other) { return position().distanceToSqr(other.position()); }
    default double distanceToSqr(Vec3 at) { return position().distanceToSqr(at); }

    String getAnimationId();
    long getAnimationStartTick();
    String getExpressionId();
    String getBehaviorId();
    long getBehaviorStartTick();
    String getDialogueKey();
    long speechStart();
    long speechEnd();
    int speechSequence();
    ItemStack getMouthItem();
    /** A transient, non-item mouthful from a successful body-food bite. */
    BodyFood.Bite getMouthBodyFood();
    ItemStack getCarriedItem();
    ItemStack aquaticServing();
    int nuzzlePartnerId();
    int carePartnerId();
    float careContactProgress();
    int aquaticChildId();
    boolean isCrawling();
    long getCrawlStartTick();
    boolean predatorFlying();
    boolean hasVisibleAccessory();
    boolean visibleKanroken();
    int visibleMedal();
    int visibleHeadwear();
    int visibleWear();
    int visibleHairLoss();
    int visibleWound();
    int visibleFilling();
    int visibleSigns();
    List<BodyWound> visibleWounds();
    List<BodyWound> visibleMarks();
    String contactPose();
    float contactTurn();
    BlockPos contactAnchor();
    boolean aftermathBurden();
    boolean aftermathWeak();
    CompoundTag agitationState();
    CompoundTag courtshipState();
    /** The hull whose CrewState holds this body's transfer (entity id), or -1. */
    int crewBoat();
    CompoundTag aquaticSelfRescueState();
    String waterDistressPhase();
    long waterDistressStartTick();
    boolean waterDistressEffort();
    int aquaticRescueTargetId();
    String aquaticAlarmActivity();
    long aquaticAlarmStartTick();
    int aquaticAlarmSourceId();
    int broodCount();
    int broodBornMask();
    float broodProgress();
    float broodVariation();
    YukkuriVariant broodVariant(int index);
    boolean hasSpentStem();
    float livePregnancyProgress();
    int liveDeliveryTicks();
    int liveChildCount();
    float birthChildScale(int index);
    int liveBornMask();
    YukkuriVariant liveChildVariant(int index);
    String liveChildAssetId(int index);
}
