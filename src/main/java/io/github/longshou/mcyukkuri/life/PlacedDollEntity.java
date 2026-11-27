package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.registry.ModEntities;
import io.github.longshou.mcyukkuri.social.KeepsakeLife;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * A doll set in the world: put down by a player's hand ({@link DollItem}) or by a yukkuri's mouth, standing where it was set.
 * It never moves on its own and no walk-over takes it; a hand click or a blow takes it back as its item, and a yukkuri's mouth
 * takes it with {@link #doll()}. This is the world form every keepsake rule reads, so a doll in the world is one of these, never
 * a loose item entity; only the item in a pocket or in a mouth is a stack.
 */
public final class PlacedDollEntity extends Entity {
    private static final EntityDataAccessor<ItemStack> DOLL =
            SynchedEntityData.defineId(PlacedDollEntity.class, EntityDataSerializers.ITEM_STACK);

    public PlacedDollEntity(EntityType<? extends PlacedDollEntity> type, Level level) { super(type, level); }

    @Override protected void defineSynchedData(SynchedEntityData.Builder builder) { builder.define(DOLL, ItemStack.EMPTY); }

    public ItemStack doll() { return entityData.get(DOLL); }
    public void doll(ItemStack stack) { entityData.set(DOLL, stack); }

    @Override public boolean isPickable() { return !isRemoved(); }

    /** The small prop is easier to reach with a hand than its own box. */
    @Override public float getPickRadius() { return .25f; }

    @Override public InteractionResult interact(Player player, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND || isRemoved() || player.isSpectator() || !player.getItemInHand(hand).isEmpty())
            return InteractionResult.PASS;
        if (level().isClientSide) return InteractionResult.SUCCESS;
        return take(player) ? InteractionResult.CONSUME : InteractionResult.PASS;
    }

    /** A player's blow is the same take as a click; other blows leave it standing. */
    @Override public boolean hurt(DamageSource source, float amount) {
        return !isRemoved() && source.getEntity() instanceof Player player && !player.isSpectator() && take(player);
    }

    /** Fire and lava destroy the doll as they would a loose item; only a hand or a blow takes it back. */
    @Override public void tick() {
        super.tick();
        if (level().isClientSide || isRemoved()) return;
        if (isInLava() || isOnFire() || level().getBlockState(blockPosition()).is(BlockTags.FIRE)) {
            GameplayLog.event(this, "keepsake", "doll destroyed cause={} item={} count={} at={}",
                    isInLava() ? "lava" : "fire", doll().getItem(), doll().getCount(), GameplayLog.at(position()));
            discard();
        }
    }

    /** The player takes the doll back as its item, and whoever kept it answers the take ({@link KeepsakeLife#dollTaken}). */
    public boolean take(Player player) {
        if (level().isClientSide || isRemoved() || player.isSpectator()) return false;
        var stack = doll().copy();
        if (stack.isEmpty()) return false;
        discard();
        if (!player.getInventory().add(stack)) player.drop(stack, false);
        GameplayLog.event(this, "keepsake", "doll taken player={} item={} count={} at={}", player.getUUID(), stack.getItem(),
                stack.getCount(), GameplayLog.at(position()));
        KeepsakeLife.dollTaken(player, this);
        return true;
    }

    @Override protected void addAdditionalSaveData(CompoundTag tag) {
        if (!doll().isEmpty()) tag.put("Item", doll().save(registryAccess()));
    }

    @Override protected void readAdditionalSaveData(CompoundTag tag) {
        doll(ItemStack.parseOptional(registryAccess(), tag.getCompound("Item")));
    }

    /** The one way a placed doll comes into the world, standing on {@code at} (its ground top); the stack is kept whole. */
    public static PlacedDollEntity place(ServerLevel level, ItemStack stack, Vec3 at, float yaw) {
        if (stack.isEmpty()) return null;
        var doll = ModEntities.PLACED_DOLL.get().create(level);
        if (doll == null) return null;
        doll.setPos(at.x, at.y, at.z);
        doll.setYRot(yaw);
        doll.setYHeadRot(yaw);
        doll.doll(stack.copy());
        if (!level.addFreshEntity(doll)) return null;
        GameplayLog.event(doll, "keepsake", "doll placed item={} count={} at={} yaw={}", stack.getItem(), stack.getCount(),
                GameplayLog.at(at), Math.round(yaw));
        return doll;
    }
}
