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
    /** Read-only render snapshot: one actual serving per supported berth, retaining its item/components. */
    public List<ItemStack> portions() {
        var result = new ArrayList<ItemStack>(count());
        for (var stack : food) for (int i=0;i<stack.getCount();i++) result.add(stack.copyWithCount(1));
        return List.copyOf(result);
    }
    public boolean canStore(ItemStack stack) {
        return !stack.isEmpty() && count() < CAPACITY && boat.cargoRoom() > 0 && (food.size() < SLOTS
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
        int amount = Math.min(Math.min(limit, source.getCount()), Math.min(CAPACITY-count(),boat.cargoRoom()));
        if (amount <= 0) return 0;
        var same = food.stream().filter(s -> ItemStack.isSameItemSameComponents(s, source)).findFirst().orElse(null);
        if (same == null) food.add(source.copyWithCount(amount)); else same.grow(amount);
        if (!creative) source.shrink(amount);
        changed(); return amount;
    }
    /** Share up to two actual servings with a child's own nearby hat, keeping one serving in the parent hat.
     *  The caller arranges physical docking/launch contact before requesting this inventory handoff. */
    public int transferProvisionsTo(HatBoatCargo receiver,YukkuriEntity eater,int limit) {
        if(receiver==null||receiver==this||eater==null||boat.level().isClientSide
                ||boat.level()!=receiver.boat.level()||!boat.isAfloat()||!receiver.boat.isAfloat()
                ||!boat.young(eater)||!eater.getUUID().equals(receiver.boat.ownerYukkuri())
                ||eater.getVehicle()!=boat&&eater.getVehicle()!=receiver.boat
                ||!eater.childhood().canEatSolid()||boat.distanceToSqr(receiver.boat)>9)return 0;
        int remaining=Math.min(Math.min(2,Math.max(0,limit)),Math.max(0,count()-1)),moved=0;
        for(var stack:food){
            if(remaining==0)break;
            if(!eater.life().acceptsFood(stack))continue;
            // store(false) shrinks this exact source stack only by the amount the receiver accepted.
            int accepted=receiver.store(stack,remaining,false);
            moved+=accepted;remaining-=accepted;
        }
        if(moved>0){food.removeIf(ItemStack::isEmpty);changed();}
        return moved;
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
    /** Reserve one real serving for a biological parent to prepare for a protected child. */
    public ItemStack takeCareMeal(YukkuriEntity parent, YukkuriEntity child) {
        if (boat.level().isClientSide || parent.getVehicle() != boat || child.getVehicle() != boat
                || !boat.protects(parent) || !boat.protects(child) || !parent.family().caresFor(child)
                || parent.life().isSleeping() || !child.life().canReceiveCareFood()) return ItemStack.EMPTY;
        for (var stack : food) if (parent.life().acceptsFood(stack) && child.life().canReceiveCareFood(stack)) {
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
