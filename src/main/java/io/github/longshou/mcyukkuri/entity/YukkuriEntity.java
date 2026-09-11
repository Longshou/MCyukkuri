package io.github.longshou.mcyukkuri.entity;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

/** Common, server-safe behaviour shared by the two separately registered species. */
public final class YukkuriEntity extends PathfinderMob implements GeoEntity {
    private final AnimatableInstanceCache animationCache = GeckoLibUtil.createInstanceCache(this);
    private final RawAnimation idleAnimation;
    private final RawAnimation walkAnimation;

    public YukkuriEntity(EntityType<? extends YukkuriEntity> type, Level level, String species) {
        super(type, level);
        this.idleAnimation = RawAnimation.begin().thenLoop("animation.mcyukkuri." + species + ".idle");
        this.walkAnimation = RawAnimation.begin().thenLoop("animation.mcyukkuri." + species + ".walk");
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 10.0)
                .add(Attributes.MOVEMENT_SPEED, 0.18)
                .add(Attributes.FOLLOW_RANGE, 16.0)
                .add(Attributes.STEP_HEIGHT, 1.0);
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(1, new WaterAvoidingRandomStrollGoal(this, 1.0));
        goalSelector.addGoal(2, new LookAtPlayerGoal(this, Player.class, 6.0F));
        goalSelector.addGoal(3, new RandomLookAroundGoal(this));
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "locomotion", 4, state -> {
            // The renderer derives limb motion from interpolated entity movement, including remote mobs.
            boolean walking = state.isMoving() && onGround();
            state.getController().setAnimationSpeed(walking
                    ? Mth.clamp(state.getLimbSwingAmount() / 0.35F, 0.5F, 2.0F) : 1.0);
            return state.setAndContinue(walking ? walkAnimation : idleAnimation);
        }));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return animationCache;
    }
}
