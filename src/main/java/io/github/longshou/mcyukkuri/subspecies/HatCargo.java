package io.github.longshou.mcyukkuri.subspecies;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.season.Season;
import io.github.longshou.mcyukkuri.season.Seasons;
import io.github.longshou.mcyukkuri.subspecies.crew.DeckLayout;
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
import java.util.Locale;

/**
 * The real, finite provisions in the crown larder and the stick: items, spoilage, scatter and delivery. Space is not decided
 * here: whether another portion fits is {@link io.github.longshou.mcyukkuri.subspecies.crew.HatDeck#heapFits}, and the load
 * each portion adds is the deck's. Nothing is ever put aside on the brim; making room for a body is the decision layer's
 * explicit {@link #jettisonCheapest} call, which throws a real portion into the water.
 */
public final class HatCargo {
    /** The larder holds at most this many portions of at most this many kinds (hat_deck_layout.json {@code load.larder_max}). */
    public static final int LARDER_MAX = 12, SLOTS = 4;
    private final HatBoatEntity boat;
    private final List<ItemStack> food = new ArrayList<>();
    /** The captain may display this in her braid, but the boat remains its only inventory owner. */
    private ItemStack paddle = ItemStack.EMPTY;
    private long discardUntil;
    private int decayTicks;

    HatCargo(HatBoatEntity boat) { this.boat = boat; }
    public boolean paddle() { return !paddle.isEmpty(); }
    public ItemStack paddleItem() { return paddle.copy(); }
    public int count() { return food.stream().mapToInt(ItemStack::getCount).sum(); }
    /** How full the larder looks: 0 empty, 1 a little (1-3 portions), 2 half full (4-7), 3 full (8-12). */
    public static int tierFor(int n) { return DeckLayout.tierFor(n); }
    public int tier() { return tierFor(count()); }

    void tick() {
        if (boat.level().isClientSide) return;
        decay();
    }
    /** Loaded-time spoilage follows the existing shelter-store seasonal intervals. */
    private void decay() {
        if (food.isEmpty()) { decayTicks = 0; return; }
        int interval = boat.flooding() > 0 || boat.level().isRainingAt(boat.blockPosition()) ? 1200 : 6000;
        if (Seasons.enabled()) {
            var season = Seasons.at(boat.level(), boat.blockPosition());
            if (season == Season.AUTUMN || season == Season.WINTER) interval = interval * 3 / 2;
            else if (season == Season.SUMMER) interval /= 2;
        }
        if (++decayTicks < interval) return;
        decayTicks = 0;
        int before = count();
        var oldest = food.getFirst(); var item = oldest.getItem();
        oldest.shrink(1); food.removeIf(ItemStack::isEmpty); changed();
        GameplayLog.event(boat, "cargo", "stored food spoiled owner={} item={} food={} -> {} interval={} flooding={}",
                boat.ownerYukkuri(), item, before, count(), interval, boat.flooding());
    }

    /**
     * Push the cheapest portion (the least nourishing, the oldest first) over the rim into the water as a real floating item
     * that can be collected again. This is a decision, never a side effect of booking a seat: callers use it only to make
     * room for a body or a newborn, and the loss is real. Returns false when there is nothing to throw.
     */
    boolean jettisonCheapest(String reason) {
        if (boat.level().isClientSide || food.isEmpty()) return false;
        ItemStack cheapest = null;
        int value = Integer.MAX_VALUE;
        for (var stack : food) {
            var props = stack.getFoodProperties(null);
            int worth = props == null ? 0 : props.nutrition();
            if (worth < value) { value = worth; cheapest = stack; }
        }
        if (cheapest == null) return false;
        int before = count();
        var drop = cheapest.split(1);
        food.removeIf(ItemStack::isEmpty);
        discardUntil = boat.level().getGameTime() + 200;
        changed();
        var rim = boat.cargoSpillOrigin();
        var at = boat.toWorld(rim).add(0, .12, 0);
        var outward = boat.toWorld(new Vec3(HatVessel.brim(boat.mode())[0] + 1, rim.y, rim.z + 2)).subtract(at).multiply(1, 0, 1).normalize();
        var item = new ItemEntity(boat.level(), at.x, at.y, at.z, drop);
        item.setDeltaMovement(outward.scale(.20).add(boat.getDeltaMovement()).add(0, .20, 0));
        item.setPickUpDelay(200);
        boolean spawned = boat.level().addFreshEntity(item);
        GameplayLog.event(boat, "cargo", "food jettisoned owner={} reason={} item={} worth={} spawned={} food={} -> {}",
                boat.ownerYukkuri(), reason, drop.getItem(), value, spawned, before, count());
        return true;
    }

    /** A rolling hull lets the stowed stick slide over the brim: a real floating stick, remembered by the family as its own. */
    boolean slideStickOverboard(float roll) {
        var stow = boat.deckLayout().stow();
        if (boat.level().isClientSide || !paddle() || stow == null) return false;
        var stick = paddle;
        paddle = ItemStack.EMPTY;
        changed();
        var local = stow.middle();
        var at = boat.tiltedWorld(local).add(0, .08, 0);
        var outward = boat.toWorld(local.add(local.x < 0 ? -2 : 2, 0, 0)).subtract(boat.toWorld(local)).multiply(1, 0, 1).normalize();
        var item = new ItemEntity(boat.level(), at.x, at.y, at.z, stick);
        item.setDeltaMovement(boat.getDeltaMovement().add(outward.scale(.07)).add(0, .06, 0));
        item.setDefaultPickUpDelay();
        boolean spawned = boat.level().addFreshEntity(item);
        notePaddleDrop(item);
        GameplayLog.event(boat, "cargo", "stick slid overboard owner={} roll={} spawned={}", boat.ownerYukkuri(), String.format(Locale.ROOT, "%.1f", roll), spawned);
        return true;
    }

    public List<ItemStack> items() { return food.stream().map(ItemStack::copy).toList(); }
    /** Real servings for physical drops, retaining each item's components; the heap renderer only reads counts. */
    public List<ItemStack> portions() {
        var result = new ArrayList<ItemStack>(count());
        for (var stack : food) for (int i=0;i<stack.getCount();i++) result.add(stack.copyWithCount(1));
        return List.copyOf(result);
    }
    /** Portions that still fit: the deck decides (load and the seats the larder would press on). */
    public int remainingCapacity() { return boat.cargoRoom(); }
    public boolean canStore(ItemStack stack) {
        return !stack.isEmpty() && boat.cargoRoom() > 0 && (food.size() < SLOTS
                || food.stream().anyMatch(s -> ItemStack.isSameItemSameComponents(s, stack)));
    }
    public boolean givePaddle(ItemStack source, boolean creative) {
        if (boat.level().isClientSide || paddle() || !source.is(Items.STICK) || !boat.canCarryPaddle()) return false;
        int before=source.getCount();
        paddle = creative ? source.copyWithCount(1) : source.split(1);
        changed();
        GameplayLog.event(boat,"cargo","paddle stored owner={} sourceCount={} -> {} creative={}",
                boat.ownerYukkuri(),before,source.getCount(),creative);
        return true;
    }
    /** The caller has checked owner diet, reach and (for world pickups) mob griefing. */
    public int store(ItemStack source, int limit, boolean creative) {
        if (boat.level().isClientSide || !canStore(source)) return 0;
        int amount = Math.min(Math.min(limit, source.getCount()), boat.cargoRoom());
        if (amount <= 0) return 0;
        int before=count(),sourceBefore=source.getCount(); var item=source.getItem();
        var same = food.stream().filter(s -> ItemStack.isSameItemSameComponents(s, source)).findFirst().orElse(null);
        if (same == null) food.add(source.copyWithCount(amount)); else same.grow(amount);
        if (!creative) source.shrink(amount);
        changed();
        GameplayLog.event(boat,"cargo","food stored owner={} item={} accepted={} food={} -> {} sourceCount={} -> {} creative={}",
                boat.ownerYukkuri(),item,amount,before,count(),sourceBefore,source.getCount(),creative);
        return amount;
    }
    /** Share up to two actual servings with a child's own nearby hat, keeping one serving in the parent hat.
     *  The caller arranges physical docking/launch contact before requesting this inventory handoff. */
    public int transferProvisionsTo(HatCargo receiver,YukkuriEntity eater,int limit) {
        if(receiver==null||receiver==this||eater==null||boat.level().isClientSide
                ||boat.level()!=receiver.boat.level()||!boat.isAfloat()||!receiver.boat.isAfloat()
                ||!boat.young(eater)||!eater.getUUID().equals(receiver.boat.ownerYukkuri())
                ||eater.getVehicle()!=boat&&eater.getVehicle()!=receiver.boat
                ||!eater.childhood().canEatSolid()||boat.distanceToSqr(receiver.boat)>9)return 0;
        int before=count(),receiverBefore=receiver.count();
        int remaining=Math.min(Math.min(2,Math.max(0,limit)),Math.max(0,count()-1)),moved=0;
        for(var stack:food){
            if(remaining==0)break;
            if(!eater.life().acceptsFood(stack))continue;
            // store(false) shrinks this exact source stack only by the amount the receiver accepted.
            int accepted=receiver.store(stack,remaining,false);
            moved+=accepted;remaining-=accepted;
        }
        if(moved>0){
            food.removeIf(ItemStack::isEmpty);changed();
            GameplayLog.event(boat,"cargo","provisions transferred child={} receiverHat={} moved={} food={} -> {} receiverFood={} -> {}",
                    eater.getUUID(),receiver.boat.getUUID(),moved,before,count(),receiverBefore,receiver.count());
        }
        return moved;
    }
    public boolean feed(YukkuriEntity rider) {
        if (boat.level().isClientSide || rider.getVehicle() != boat || !rider.life().canEat()
                || rider.life().isSleeping()) return false;
        for (var stack : food) if (rider.life().acceptsFood(stack)) {
            // beginMeal copies exactly one item, and cannot fail after it has accepted it.
            if (!rider.life().beginMeal(stack.copyWithCount(1), null, true)) return false;
            int before=count(); var item=stack.getItem();
            stack.shrink(1); food.removeIf(ItemStack::isEmpty); changed();
            GameplayLog.event(boat,"cargo","meal reserved rider={} item={} food={} -> {}",rider.getUUID(),item,before,count());
            return true;
        }
        return false;
    }
    /** Reserve one real serving for a biological parent to prepare for a protected child. */
    public ItemStack takeCareMeal(YukkuriEntity parent, YukkuriEntity child) {
        if (boat.level().isClientSide || parent.getVehicle() != boat || child.getVehicle() != boat
                || !boat.protects(parent) || !boat.protects(child) || !boat.careRecipient(child)
                || !parent.family().caresFor(child) && !boat.temporaryCaregiver(child)
                || parent.life().isSleeping() || !child.life().canReceiveCareFood()) return ItemStack.EMPTY;
        for (var stack : food) if (parent.life().acceptsFood(stack) && child.life().canReceiveCareFood(stack)) {
            int before=count();
            var one = stack.split(1); food.removeIf(ItemStack::isEmpty); changed();
            GameplayLog.event(boat,"cargo","care meal reserved parent={} child={} item={} food={} -> {}",
                    parent.getUUID(),child.getUUID(),one.getItem(),before,count());
            return one;
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
            boolean returned=owner.aquaticLife().giveStick(returning,false);
            if (!returned) paddle=returning;
            GameplayLog.event(boat,"cargo","paddle return owner={} accepted={} remainingPaddle={}",owner.getUUID(),returned,paddle());
        }
        if (hatToken != null && !food.isEmpty() && owner.aquaticLife().firstVoyage().canRetainHat(hatToken)) {
            int keptCount=count();
            var kept = save(); kept.putBoolean("Paddle", false); kept.remove("PaddleItem");
            owner.aquaticLife().firstVoyage().retainHat(hatToken, kept);
            food.clear(); changed();
            GameplayLog.event(boat,"cargo","food retained in hat owner={} token={} retained={} remaining={}",
                    owner.getUUID(),hatToken,keptCount,count());
        }
        spillAt(owner.position());
    }
    public void spillAt(Vec3 at) {
        if (boat.level().isClientSide) return;
        var drops = new ArrayList<>(items()); food.clear();
        var droppedPaddle = paddle();
        var paddleStack = droppedPaddle ? paddle.copy() : ItemStack.EMPTY;
        paddle = ItemStack.EMPTY; changed();
        int removed=drops.stream().mapToInt(ItemStack::getCount).sum(),spawned=0;
        for (var stack : drops) {
            var item = new ItemEntity(boat.level(), at.x, at.y+.15, at.z, stack);
            item.setDefaultPickUpDelay(); if(boat.level().addFreshEntity(item))spawned+=stack.getCount();
        }
        boolean paddleSpawned=false;
        if (!paddleStack.isEmpty()) {
            var item = new ItemEntity(boat.level(), at.x, at.y+.15, at.z, paddleStack);
            item.setDefaultPickUpDelay(); paddleSpawned=boat.level().addFreshEntity(item); notePaddleDrop(item);
        }
        if(removed>0||droppedPaddle)GameplayLog.event(boat,"cargo",
                "cargo spilled owner={} at={} removedFood={} spawnedFood={} failedFood={} removedPaddle={} spawnedPaddle={}",
                boat.ownerYukkuri(),at,removed,spawned,removed-spawned,droppedPaddle,paddleSpawned);
    }
    /** The physical object remains preferred by the family paddle route after a landing or spill. */
    public void notePaddleDrop(ItemEntity item) {
        var owner=boat.owner();
        if(item!=null&&owner!=null)owner.aquaticLife().familyPaddle().rememberOwnDroppedPaddle(item.getUUID());
    }
    /** Food keeps its item-list format; version 5 has no side-brim heap (food lives only in the crown larder). */
    public static final int VERSION = 5;
    public CompoundTag save() {
        var tag = new CompoundTag(); tag.putInt("Version", VERSION); tag.putInt("Tier", tier()); tag.putBoolean("Paddle", paddle());
        if (paddle()) tag.put("PaddleItem", paddle.save(boat.registryAccess()));
        var list = new ListTag();
        for (var stack : food) list.add(stack.save(boat.registryAccess()));
        tag.put("Food", list);
        tag.putInt("DecayTicks", decayTicks);
        tag.putLong("DiscardUntil", discardUntil); return tag;
    }
    public void load(CompoundTag tag) {
        decayTicks = Math.clamp(tag.getInt("DecayTicks"), 0, 8999);
        if (tag.contains("PaddleItem", Tag.TAG_COMPOUND)) {
            var stored = ItemStack.parseOptional(boat.registryAccess(), tag.getCompound("PaddleItem"));
            paddle = stored.is(Items.STICK) ? stored.copyWithCount(1) : ItemStack.EMPTY;
        } else paddle = tag.getBoolean("Paddle") ? new ItemStack(Items.STICK) : ItemStack.EMPTY;
        food.clear();
        for (var raw : tag.getList("Food", Tag.TAG_COMPOUND)) {
            var stack = ItemStack.parseOptional(boat.registryAccess(), (CompoundTag) raw);
            // A manually overfilled save remains intact; capacity only rejects new loads until it is reduced.
            if (!stack.isEmpty()) food.add(stack);
        }
        discardUntil = Math.min(boat.level().getGameTime()+200, tag.getLong("DiscardUntil"));
    }
    private void changed() { boat.syncCargo(save()); }
}
