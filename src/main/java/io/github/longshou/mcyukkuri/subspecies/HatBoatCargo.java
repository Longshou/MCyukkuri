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
    // The same Food inventory is divided between the cabin and a temporary, occupied brim patch.
    // No second item list exists, so feeding, hat recovery and capsize cannot duplicate set-aside food.
    private int asideCount, targetAside = -1;
    private long handlingAt, keepAsideUntil, discardUntil;

    HatBoatCargo(HatBoatEntity boat) { this.boat = boat; }
    public boolean paddle() { return paddle; }
    public int count() { return food.stream().mapToInt(ItemStack::getCount).sum(); }
    /** How full the hat looks: 0 empty, 1 a little (1-2 portions), 2 half full (3-5), 3 full (6-8). Food kind never matters. */
    public static int tierFor(int n) { return n <= 0 ? 0 : n <= 2 ? 1 : n <= 5 ? 2 : 3; }
    public int tier() { return tierFor(innerCount()); }
    public int innerCount() { return count() - asideCount(); }
    public int asideCount() { return Math.min(asideCount, count()); }
    public int asideTier() { return tierFor(asideCount()); }
    public boolean handling() { return targetAside >= 0; }
    int targetAsideCount() { return targetAside; }
    public String activity() { return targetAside == 0 ? "boat_retrieve_food" : "boat_stow_food"; }
    boolean discardCooling() { return boat.level().getGameTime() < discardUntil; }
    void keepAside() { keepAsideUntil = boat.level().getGameTime() + 100; }

    /** Reserve a safe destination before the parent's short handling action. Recheck before committing. */
    boolean moveAside(int target) {
        if (boat.level().isClientSide || target < 0 || target > count()) return false;
        keepAside();
        if (handling()) return false;
        if (target == asideCount()) return true;
        if (!boat.canHandleCargo() || !boat.cargoFits(count()-target, target)) return false;
        targetAside = target; handlingAt = boat.level().getGameTime();
        boat.holdPosition(); changed(); return false;
    }

    void tick() {
        if (boat.level().isClientSide) return;
        if (handling()) {
            if (!boat.canHandleCargo() || targetAside > count() || !boat.cargoFits(count()-targetAside, targetAside)) {
                targetAside = -1; changed(); return;
            }
            boat.holdPosition();
            if (boat.level().getGameTime()-handlingAt >= 20) {
                asideCount = targetAside; targetAside = -1; keepAside(); changed();
            }
            return;
        }
        if (asideCount() == 0 || boat.tickCount % 20 != 0 || boat.level().getGameTime() < keepAsideUntil
                || !boat.canRestoreCargo()) return;
        for (int target = 0; target < asideCount(); target++) if (boat.cargoFits(count()-target, target)) {
            moveAside(target); return;
        }
    }

    /** A chosen rescue outranks stores. Throw real food out past the rim; the paddle stays aboard. */
    void jettison() {
        if (boat.level().isClientSide || food.isEmpty()) return;
        var drops = new ArrayList<>(portions());
        var origin = boat.cargoSpillOrigin();
        food.clear(); asideCount = 0; targetAside = -1;
        discardUntil = boat.level().getGameTime() + 200;
        changed(); boat.holdPosition();
        for (int i = 0; i < drops.size(); i++) {
            var at = boat.toWorld(origin).add(0, .12, 0);
            var outward = boat.toWorld(new net.minecraft.world.phys.Vec3(HatVessel.BRIM[0]+1, HatVessel.top(boat.mode()), 2+i*.1))
                    .subtract(at).multiply(1,0,1).normalize();
            var item = new ItemEntity(boat.level(), at.x, at.y, at.z, drops.get(i));
            item.setDeltaMovement(outward.scale(.20+i*.008).add(boat.getDeltaMovement()).add(0,.20,0));
            item.setPickUpDelay(200); boat.level().addFreshEntity(item);
        }
    }
    public List<ItemStack> items() { return food.stream().map(ItemStack::copy).toList(); }
    /** Real servings for physical drops, retaining each item's components; the heap renderer only reads counts. */
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
    /** The owner puts her own hat back on: the paddle returns to her hand and the provisions stay with that hat.
     *  They are kept under the hat's token (FirstVoyage.retainHat) and come back when it is set down as a boat again;
     *  anything the hat cannot carry for her is dropped as a real item. */
    public void unload(YukkuriEntity owner, java.util.UUID hatToken) {
        if (paddle && owner.aquaticLife().giveStick(new ItemStack(Items.STICK), false)) paddle = false;
        if (hatToken != null && !food.isEmpty() && owner.aquaticLife().firstVoyage().canRetainHat(hatToken)) {
            var kept = save(); kept.putBoolean("Paddle", false);
            owner.aquaticLife().firstVoyage().retainHat(hatToken, kept);
            food.clear(); changed();
        }
        spillAt(owner.position());
    }
    public void returnToFamily(YukkuriEntity owner, HatBoatEntity home) {
        for (var stack : food) home.cargo().store(stack,stack.getCount(),false);
        food.removeIf(ItemStack::isEmpty);
        // The pupil keeps its actual paddle. Any food that will not fit remains a real dropped item.
        unload(owner, null);
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
    /** Food keeps its original item-list format. Version 2 added the visual tier; version 3 records the temporary
     *  compartment and handling action. Old side-brim cargo stays aside until it can safely move into the well. */
    public static final int VERSION = 3;
    public CompoundTag save() {
        var tag = new CompoundTag(); tag.putInt("Version", VERSION); tag.putInt("Tier", tier()); tag.putBoolean("Paddle", paddle); var list = new ListTag();
        for (var stack : food) list.add(stack.save(boat.registryAccess()));
        tag.put("Food", list); tag.putInt("AsideCount", asideCount());
        tag.putInt("HandlingTarget", targetAside); tag.putLong("HandlingAt", handlingAt);
        tag.putLong("KeepAsideUntil", keepAsideUntil); tag.putLong("DiscardUntil", discardUntil); return tag;
    }
    public void load(CompoundTag tag) {
        paddle = tag.getBoolean("Paddle"); food.clear();
        for (var raw : tag.getList("Food", Tag.TAG_COMPOUND)) {
            var stack = ItemStack.parseOptional(boat.registryAccess(), (CompoundTag) raw);
            if (stack.isEmpty() || count() >= CAPACITY) continue; // legacy saves keep every portion, whatever the kind count
            stack.setCount(Math.min(stack.getCount(), CAPACITY-count())); food.add(stack);
        }
        // Older saves really displayed their stores on the side brim. Do not materialize a new heap on a saved child.
        asideCount = tag.getInt("Version") < 3 ? count() : Math.clamp(tag.getInt("AsideCount"), 0, count());
        // A saved unfinished action owned no moved items. Resume from the committed compartment only.
        targetAside = boat.level().isClientSide && tag.contains("HandlingTarget") ? tag.getInt("HandlingTarget") : -1;
        handlingAt = tag.getLong("HandlingAt");
        keepAsideUntil = Math.min(boat.level().getGameTime()+100, tag.getLong("KeepAsideUntil"));
        discardUntil = Math.min(boat.level().getGameTime()+200, tag.getLong("DiscardUntil"));
    }
    private void changed() {
        asideCount = Math.min(asideCount, count());
        if (targetAside > count() || count() == 0) targetAside = -1;
        boat.syncCargo(save());
    }
}
