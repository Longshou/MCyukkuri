package io.github.longshou.mcyukkuri.subspecies;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import java.util.UUID;
import net.minecraft.world.phys.Vec3;

/** Real, finite provisions in a hat. Every transfer removes the corresponding source item. */
public final class HatBoatCargo {
    /** Every food portion and the real paddle use one of the twelve hat-load units. */
    public static final int CAPACITY = 12, SLOTS = 4, PADDLE_UNITS = 1;
    private final HatBoatEntity boat;
    private final List<ItemStack> food = new ArrayList<>();
    /** The captain may display this in her braid, but the boat remains its only inventory owner. */
    private ItemStack paddle = ItemStack.EMPTY;
    // The same Food inventory is divided between the cabin and a temporary, occupied brim patch.
    // No second item list exists, so feeding, hat recovery and capsize cannot duplicate set-aside food.
    private int asideCount, targetAside = -1;
    private long handlingAt, keepAsideUntil, discardUntil;

    HatBoatCargo(HatBoatEntity boat) { this.boat = boat; }
    public boolean paddle() { return !paddle.isEmpty(); }
    public ItemStack paddleItem() { return paddle.copy(); }
    public int count() { return food.stream().mapToInt(ItemStack::getCount).sum(); }
    /** How full the hat looks: 0 empty, 1 a little (1-3 portions), 2 half full (4-7), 3 full (8-12). */
    public static int tierFor(int n) { return n <= 0 ? 0 : n <= 3 ? 1 : n <= 7 ? 2 : 3; }
    public int tier() { return tierFor(innerCount()); }
    public int innerCount() { return count() - asideCount(); }
    public int asideCount() { return Math.min(asideCount, count()); }
    public int asideTier() { return tierFor(asideCount()); }
    public boolean handling() { return targetAside >= 0; }
    int targetAsideCount() { return targetAside; }
    public String activity() { return targetAside == 0 ? Behaviors.BOAT_RETRIEVE_FOOD : Behaviors.BOAT_STOW_FOOD; }
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
            var outward = boat.toWorld(new Vec3(HatVessel.BRIM[0]+1, HatVessel.top(boat.mode()), 2+i*.1))
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
    private int foodSpace() { return Math.max(0, boat.cargoCapacity() - (paddle() ? PADDLE_UNITS : 0) - count()); }
    public int usedCapacity() { return count() + (paddle() ? PADDLE_UNITS : 0); }
    public int remainingCapacity() { return foodSpace(); }
    public boolean canStore(ItemStack stack) {
        return !stack.isEmpty() && foodSpace() > 0 && boat.cargoRoom() > 0 && (food.size() < SLOTS
                || food.stream().anyMatch(s -> ItemStack.isSameItemSameComponents(s, stack)));
    }
    public boolean givePaddle(ItemStack source, boolean creative) {
        if (boat.level().isClientSide || paddle() || !source.is(Items.STICK) || foodSpace() < PADDLE_UNITS) return false;
        paddle = creative ? source.copyWithCount(1) : source.split(1);
        changed(); return true;
    }
    /** The caller has checked owner diet, reach and (for world pickups) mob griefing. */
    public int store(ItemStack source, int limit, boolean creative) {
        if (boat.level().isClientSide || !canStore(source)) return 0;
        int amount = Math.min(Math.min(limit, source.getCount()), Math.min(foodSpace(),boat.cargoRoom()));
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
    public void unload(YukkuriEntity owner, UUID hatToken) {
        if (paddle()) {
            var returning=paddle; paddle=ItemStack.EMPTY;
            if (!owner.aquaticLife().giveStick(returning,false)) paddle=returning;
        }
        if (hatToken != null && !food.isEmpty() && owner.aquaticLife().firstVoyage().canRetainHat(hatToken)) {
            var kept = save(); kept.putBoolean("Paddle", false); kept.remove("PaddleItem");
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
    public void spillAt(Vec3 at) {
        if (boat.level().isClientSide) return;
        var drops = new ArrayList<>(items()); food.clear();
        var droppedPaddle = paddle();
        var paddleStack = droppedPaddle ? paddle.copy() : ItemStack.EMPTY;
        paddle = ItemStack.EMPTY; changed();
        for (var stack : drops) {
            var item = new ItemEntity(boat.level(), at.x, at.y+.15, at.z, stack);
            item.setDefaultPickUpDelay(); boat.level().addFreshEntity(item);
        }
        if (!paddleStack.isEmpty()) {
            var item = new ItemEntity(boat.level(), at.x, at.y+.15, at.z, paddleStack);
            item.setDefaultPickUpDelay(); boat.level().addFreshEntity(item); notePaddleDrop(item);
        }
    }
    /** The physical object remains preferred by the family paddle route after a landing or spill. */
    void notePaddleDrop(ItemEntity item) {
        var owner=boat.owner();
        if(item!=null&&owner!=null)owner.aquaticLife().familyPaddle().rememberOwnDroppedPaddle(item.getUUID());
    }
    /** Food keeps its original item-list format. Version 2 added the visual tier; version 3 records the temporary
     *  compartment and handling action; version 4 stores the real paddle. Old side-brim cargo stays aside until it can safely move into the well. */
    public static final int VERSION = 4;
    public CompoundTag save() {
        var tag = new CompoundTag(); tag.putInt("Version", VERSION); tag.putInt("Tier", tier()); tag.putBoolean("Paddle", paddle());
        if (paddle()) tag.put("PaddleItem", paddle.save(boat.registryAccess()));
        var list = new ListTag();
        for (var stack : food) list.add(stack.save(boat.registryAccess()));
        tag.put("Food", list); tag.putInt("AsideCount", asideCount());
        tag.putInt("HandlingTarget", targetAside); tag.putLong("HandlingAt", handlingAt);
        tag.putLong("KeepAsideUntil", keepAsideUntil); tag.putLong("DiscardUntil", discardUntil); return tag;
    }
    public void load(CompoundTag tag) {
        if (tag.contains("PaddleItem", Tag.TAG_COMPOUND)) {
            var stored = ItemStack.parseOptional(boat.registryAccess(), tag.getCompound("PaddleItem"));
            paddle = stored.is(Items.STICK) ? stored.copyWithCount(1) : ItemStack.EMPTY;
        } else paddle = tag.getBoolean("Paddle") ? new ItemStack(Items.STICK) : ItemStack.EMPTY;
        food.clear();
        for (var raw : tag.getList("Food", Tag.TAG_COMPOUND)) {
            var stack = ItemStack.parseOptional(boat.registryAccess(), (CompoundTag) raw);
            // A legacy or manually overfilled save remains intact; capacity only rejects new loads until it is reduced.
            if (!stack.isEmpty()) food.add(stack);
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
