package io.github.longshou.mcyukkuri.social;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.settlement.Homes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Personal treasures (komicolle 11518880: any small thing, gem or plain stone, may be collected as a treasure; the numbers are project
 * design). A free-living individual with a home is sometimes drawn to a tagged item lying nearby, carries it home and keeps at most
 * {@link #MAX} of them in its own data (they belong to the individual, so a theft from the owner works anywhere). At night near home it
 * looks at them. Taking one away (a player at the home, {@link #confiscate}; a peer that wins a bullying bout, {@link #snatchFrom}) is a
 * loss: fear and distrust of the taker, stress, crying, and between peers a lasting grudge. Kept pets are excluded, so this never overlaps
 * with the {@code PetLife} toys (an owner's gift) and nothing is counted twice.
 */
public final class TreasureLife extends Goal {
    public static final TagKey<Item> TAG = TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath("mcyukkuri", "yukkuri_treasure"));
    public static final int MAX = 3;
    private final YukkuriEntity entity;
    private final List<ItemStack> held = new ArrayList<>();
    private ItemEntity target;
    private ItemStack carried = ItemStack.EMPTY;
    private int cooldown, scanWait, gazeWait, elapsed;

    public TreasureLife(YukkuriEntity entity) {
        this.entity = entity; setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        scanWait = 40 + entity.getRandom().nextInt(100);
    }
    public int count() { return held.size(); }
    public List<ItemStack> items() { return List.copyOf(held); }
    public boolean full() { return held.size() >= MAX; }
    public boolean active() { return target != null || !carried.isEmpty(); }
    public static boolean treasure(ItemStack stack) { return !stack.isEmpty() && stack.is(TAG); }
    /** Direct insertion (status writer). */
    public boolean add(ItemStack stack) {
        if (!treasure(stack) || full()) return false;
        held.add(stack.copyWithCount(1)); return true;
    }
    private ServerLevel level() { return (ServerLevel) entity.level(); }

    /** Why it is or is not looking for finds right now (status observation). */
    public String describe() {
        return "held=" + held.size() + " eligible=" + eligible() + " home=" + (homePos() != null) + " scan=" + scanWait + " cooldown=" + cooldown
                + " carrying=" + !carried.isEmpty() + " target=" + (target != null);
    }
    private boolean eligible() {
        return entity.isAlive() && !entity.isNoAi() && !entity.isPanicking() && !entity.isCommandPlaying() && entity.family().adult()
                && !entity.getVariant().isHouse() && !entity.pet().isPet() && !entity.life().isSleeping() && !entity.life().hasUrgentNeed()
                && !entity.operations().active() && !entity.groupInteraction().active() && !entity.dailyLife().active()
                && !entity.playLife().active() && !entity.family().active() && !entity.isInWaterOrBubble()
                && !io.github.longshou.mcyukkuri.event.WildEvents.reserved(entity) && entity.life().activity().equals("idle");
    }
    private BlockPos homePos() {
        var home = Homes.of(entity);
        return home == null ? null : home.entrance();
    }

    /** Every tick from the entity: cooldowns, and the bedtime look at the treasures. */
    public void clock() {
        if (entity.level().isClientSide) return;
        if (cooldown > 0) cooldown--;
        if (scanWait > 0) scanWait--;
        if (gazeWait > 0) { gazeWait--; return; }
        gazeWait = 100;
        if (held.isEmpty() || !level().isNight() || active() || !eligible() || entity.getRandom().nextInt(6) != 0) return;
        var home = homePos();
        if (home == null || entity.blockPosition().distSqr(home) > 36) return;
        entity.getNavigation().stop();
        entity.showReaction("happy", "thing_san", 100);
    }

    @Override public boolean canUse() {
        if (active()) return true;
        if (scanWait > 0 || cooldown > 0 || full() || !eligible() || homePos() == null) return false;
        scanWait = 200 + entity.getRandom().nextInt(100);
        if (entity.getRandom().nextInt(10) != 0) return false; // one idle spell in ten is spent on a find
        ItemEntity best = null; double bestDist = 1e9;
        for (var item : level().getEntitiesOfClass(ItemEntity.class, entity.getBoundingBox().inflate(8, 3, 8), i -> treasure(i.getItem()) && i.isAlive() && !i.hasPickUpDelay())) {
            double d = entity.distanceToSqr(item);
            if (d < bestDist && entity.life().reachable(item.blockPosition(), false)) { best = item; bestDist = d; }
        }
        if (best == null) return false;
        target = best; elapsed = 0;
        return true;
    }
    @Override public boolean canContinueToUse() {
        if (elapsed > 400 || !entity.isAlive() || entity.isPanicking() || entity.life().isSleeping()) return false;
        return !carried.isEmpty() || target != null && target.isAlive() && treasure(target.getItem());
    }
    @Override public boolean requiresUpdateEveryTick() { return true; }
    @Override public void tick() {
        elapsed++;
        if (carried.isEmpty()) {
            if (target == null) return;
            entity.getLookControl().setLookAt(target, 30, 30);
            if (entity.distanceToSqr(target) < 1.2) {
                var stack = target.getItem();
                carried = stack.copyWithCount(1); stack.shrink(1);
                if (stack.isEmpty()) target.discard(); else target.setItem(stack);
                target = null; elapsed = 0;
                entity.showReaction("happy", "mine", 60);
            } else if (elapsed % 10 == 1 && !entity.life().navigate(target.blockPosition(), 1, false)) stop();
            return;
        }
        var home = homePos();
        if (home == null) { stop(); return; }
        if (entity.blockPosition().distSqr(home) <= 4) {
            if (add(carried)) { entity.showReaction("happy", "thing_san", 100); carried = ItemStack.EMPTY; cooldown = 1200; }
            stop();
        } else if (elapsed % 10 == 1 && !entity.life().navigate(home, 1, false)) stop();
    }
    @Override public void stop() {
        // A find that could not be taken home is put down where the individual stands, never destroyed.
        if (!carried.isEmpty() && entity.isAlive() && !entity.level().isClientSide) {
            level().addFreshEntity(new ItemEntity(level(), entity.getX(), entity.getY() + .2, entity.getZ(), carried));
            cooldown = 1200;
        }
        carried = ItemStack.EMPTY; target = null; entity.getNavigation().stop();
    }

    /** A player takes the treasures from the owner's home. Returns how many were taken. */
    public int confiscate(Player player) {
        if (held.isEmpty()) return 0;
        int taken = held.size();
        for (var stack : held) {
            if (!player.getInventory().add(stack.copy())) player.drop(stack.copy(), false);
        }
        held.clear();
        entity.life().personality().treasureTaken(player.getUUID());
        entity.mind().add(15, "treasure_taken");
        entity.showReaction("sad", "treasure_stolen", 160);
        return taken;
    }
    /** A peer that wins a bullying bout takes one treasure from the loser if it has room; true if it did. */
    public boolean snatchFrom(YukkuriEntity victim) {
        if (victim == entity || victim.treasure().held.isEmpty() || full()) return false;
        held.add(victim.treasure().held.remove(victim.treasure().held.size() - 1));
        victim.mind().add(15, "treasure_stolen");
        victim.showReaction("sad", "treasure_stolen", 160);
        entity.showReaction("happy", "thing_san", 100);
        YukkuriWorldData.get(level()).relate(victim.getUUID(), entity.getUUID(), -12);
        return true;
    }
    /** Treasures drop where the owner dies. */
    public void dropAll() {
        if (entity.level().isClientSide) return;
        for (var stack : held) level().addFreshEntity(new ItemEntity(level(), entity.getX(), entity.getY() + .2, entity.getZ(), stack));
        held.clear();
    }

    public CompoundTag save() {
        var tag = new CompoundTag(); var list = new ListTag();
        for (var stack : held) list.add(stack.save(entity.registryAccess()));
        tag.put("Held", list); tag.putInt("Cooldown", cooldown); return tag;
    }
    public void load(CompoundTag tag) {
        held.clear(); carried = ItemStack.EMPTY; target = null;
        var list = tag.getList("Held", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size() && held.size() < MAX; i++) {
            var stack = ItemStack.parseOptional(entity.registryAccess(), list.getCompound(i));
            if (!stack.isEmpty()) held.add(stack);
        }
        cooldown = Math.clamp(tag.getInt("Cooldown"), 0, 24000);
    }
}
