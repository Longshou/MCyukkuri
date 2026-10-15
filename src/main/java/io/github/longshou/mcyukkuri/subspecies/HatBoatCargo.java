package io.github.longshou.mcyukkuri.subspecies;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Real, finite provisions in a hat. Every transfer removes the corresponding source item. */
public final class HatBoatCargo {
    public static final int CAPACITY = 8, SLOTS = 4;
    private final HatBoatEntity boat;
    private final List<ItemStack> food = new ArrayList<>();
    private boolean paddle;

    HatBoatCargo(HatBoatEntity boat) { this.boat = boat; }
    public boolean paddle() { return paddle; }
    public int count() { return food.stream().mapToInt(ItemStack::getCount).sum(); }
    public List<ItemStack> items() { return food.stream().map(ItemStack::copy).toList(); }
    public boolean canStore(ItemStack stack) {
        return !stack.isEmpty() && count() < CAPACITY && (food.size() < SLOTS
                || food.stream().anyMatch(s -> ItemStack.isSameItemSameComponents(s, stack)));
    }
    public boolean givePaddle(ItemStack source, boolean creative) {
        if (boat.level().isClientSide || paddle || !source.is(Items.STICK)) return false;
        paddle = true;
        if (!creative) source.shrink(1);
        changed(); return true;
    }
    /** The caller has checked owner diet, reach and (for world pickups) mob griefing. */
    public int store(ItemStack source, int limit, boolean creative) {
        if (boat.level().isClientSide || !canStore(source)) return 0;
        int amount = Math.min(Math.min(limit, source.getCount()), CAPACITY-count());
        if (amount <= 0) return 0;
        var same = food.stream().filter(s -> ItemStack.isSameItemSameComponents(s, source)).findFirst().orElse(null);
        if (same == null) food.add(source.copyWithCount(amount)); else same.grow(amount);
        if (!creative) source.shrink(amount);
        changed(); return amount;
    }
    public boolean feed(YukkuriEntity rider) {
        if (boat.level().isClientSide || rider.getVehicle() != boat || !rider.life().canEat()
                || rider.life().isSleeping()) return false;
        for (var stack : food) if (rider.life().acceptsFood(stack)) {
            // beginMeal copies exactly one item, and cannot fail after it has accepted it.
            if (!rider.life().beginMeal(stack.copyWithCount(1), null, true)) return false;
            stack.shrink(1); food.removeIf(ItemStack::isEmpty); changed(); return true;
        }
        return false;
    }
    /** Reserve one real serving in the parent's braid; the care session owns it until transfer or refund. */
    public ItemStack takeMeal(YukkuriEntity rider) {
        if (boat.level().isClientSide || rider.getVehicle() != boat || !rider.life().canEat() || rider.life().isSleeping()) return ItemStack.EMPTY;
        for (var stack : food) if (rider.life().acceptsFood(stack)) {
            var one = stack.split(1); food.removeIf(ItemStack::isEmpty); changed(); return one;
        }
        return ItemStack.EMPTY;
    }
    /** Put provisions back on dry land or scatter them on capsize; clear before spawning to prevent duplication. */
    public void spill() {
        spillAt(boat.position());
    }
    public void unload(YukkuriEntity owner) {
        if (paddle && owner.aquaticLife().giveStick(new ItemStack(Items.STICK), false)) paddle = false;
        spillAt(owner.position());
    }
    public void returnToFamily(YukkuriEntity owner, HatBoatEntity home) {
        for (var stack : food) home.cargo().store(stack,stack.getCount(),false);
        food.removeIf(ItemStack::isEmpty);
        // The pupil keeps its actual paddle. Any food that will not fit remains a real dropped item.
        unload(owner);
    }
    public void spillAt(net.minecraft.world.phys.Vec3 at) {
        if (boat.level().isClientSide) return;
        var drops = new ArrayList<>(items()); food.clear();
        if (paddle) drops.add(new ItemStack(Items.STICK));
        paddle = false; changed();
        for (var stack : drops) {
            var item = new ItemEntity(boat.level(), at.x, at.y+.15, at.z, stack);
            item.setDefaultPickUpDelay(); boat.level().addFreshEntity(item);
        }
    }
    public CompoundTag save() {
        var tag = new CompoundTag(); tag.putBoolean("Paddle", paddle); var list = new ListTag();
        for (var stack : food) list.add(stack.save(boat.registryAccess()));
        tag.put("Food", list); return tag;
    }
    public void load(CompoundTag tag) {
        paddle = tag.getBoolean("Paddle"); food.clear();
        for (var raw : tag.getList("Food", Tag.TAG_COMPOUND)) {
            var stack = ItemStack.parseOptional(boat.registryAccess(), (CompoundTag) raw);
            if (stack.isEmpty() || food.size() >= SLOTS || count() >= CAPACITY) continue;
            stack.setCount(Math.min(stack.getCount(), CAPACITY-count())); food.add(stack);
        }
    }
    private void changed() { boat.syncCargo(save()); }
}
