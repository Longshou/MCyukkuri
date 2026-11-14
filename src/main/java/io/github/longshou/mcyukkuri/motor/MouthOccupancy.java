package io.github.longshou.mcyukkuri.motor;

import io.github.longshou.mcyukkuri.body.BodyEffects;
import io.github.longshou.mcyukkuri.body.BodyFood;
import io.github.longshou.mcyukkuri.body.MouthPlacement;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.ParentCarry;
import io.github.longshou.mcyukkuri.headwear.PlacedHatEntity;
import io.github.longshou.mcyukkuri.interaction.ItemContact;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/** Item and bite payloads share one oral purpose. Live holds and the kit retain their physical owners. */
public final class MouthOccupancy {
    public enum Purpose { MEAL, CARE, DUTY, SWADDLE, HOUSE, FIND, KINDNESS, JUNK_FIT }

    private record Load(Purpose purpose, List<ItemStack> items, BodyFood.Bite bite) {}

    private final YukkuriEntity owner;
    private final CarrierSlot<Load> held = new CarrierSlot<>();
    // Real excess from an older save. It is never presented or available for another mouth action.
    private final List<ItemStack> displaced = new ArrayList<>();
    private boolean importing;
    private boolean reconcileExternal;
    private boolean reconcileOnTick;
    private BlockPos refusedAt;

    public MouthOccupancy(YukkuriEntity owner) { this.owner = owner; }

    public Purpose purpose() { var load = load(); return load == null ? null : load.purpose(); }
    private Load load() { return held.contents().orElse(null); }

    public List<ItemStack> items(Purpose purpose) {
        var load = load();
        return load != null && load.purpose() == purpose ? Collections.unmodifiableList(load.items()) : List.of();
    }

    /** Item payload at the single mouth authority, for inventory observation. */
    public List<ItemStack> items() {
        var load = load();
        return load == null ? List.of() : Collections.unmodifiableList(load.items());
    }

    public ItemStack stack(Purpose purpose) {
        var load = load();
        return load == null || load.purpose() != purpose || load.items().isEmpty() ? ItemStack.EMPTY : load.items().getFirst();
    }

    public ItemStack displayStack() {
        var load = load();
        return load == null || load.items().isEmpty() ? ItemStack.EMPTY : load.items().getFirst();
    }

    public BodyFood.Bite bite() {
        var load = load();
        return load == null ? BodyFood.Bite.EMPTY : load.bite();
    }

    public boolean kitInMouth() {
        if (owner.hatKit().isEmpty()) return false;
        return owner.life().custody().kitPlace() == (ContactGeometry.braided(owner) ? Held.Place.MOUTH : Held.Place.GRIP);
    }

    public boolean canHold(Purpose incoming) {
        return canHoldAfterPuttingDownYoung(incoming) && !ParentCarry.mouthCarrying(owner);
    }

    public boolean canHoldAfterPuttingDownYoung(Purpose incoming) {
        var current = purpose();
        return (current == null || current == incoming) && !kitInMouth();
    }

    /** An existing tool can be cleared by Stow before a grab. The child capacity is checked by ParentCarry. */
    public boolean canCarryYoung() {
        return held.isEmpty() && owner.habitat().carrying().isEmpty() && owner.forageTrip().load().isEmpty();
    }

    public boolean freeForKit() { return canCarryYoung() && !ParentCarry.mouthCarrying(owner); }

    public boolean freeForExternalWork() { return held.isEmpty() && !kitInMouth() && !ParentCarry.mouthCarrying(owner); }

    /** Use after the source and part have both been checked, in the same server step as the source debit. */
    public void set(Purpose purpose, ItemStack stack) {
        if (stack.isEmpty()) {
            clear(purpose);
        } else {
            requireCompatible(purpose);
            var next = new Load(purpose, new ArrayList<>(List.of(stack)), BodyFood.Bite.EMPTY);
            if (!held.acquire(next)) held.replace(load(), next);
        }
    }

    public void add(Purpose purpose, ItemStack stack) {
        requireCompatible(purpose);
        if (held.isEmpty()) set(purpose, stack);
        else load().items().add(stack);
    }

    private void requireCompatible(Purpose purpose) {
        if (!canHold(purpose)) throw new IllegalStateException("Occupied mouth cannot acquire " + purpose);
    }

    public ItemStack takeFirst(Purpose purpose) {
        var items = load().items();
        var first = items.removeFirst();
        if (items.isEmpty()) clear(purpose);
        return first;
    }

    public void clear(Purpose purpose) {
        if (purpose() == purpose) held.consume();
    }

    public boolean acquireMeal(ItemStack stack) {
        return acquireMeal(new Load(Purpose.MEAL, List.of(stack), BodyFood.Bite.EMPTY));
    }

    public boolean acquireMeal(BodyFood.Bite bite) {
        return acquireMeal(new Load(Purpose.MEAL, List.of(), bite));
    }

    private boolean acquireMeal(Load meal) {
        return canHold(Purpose.MEAL) && held.acquire(meal);
    }

    /** A whole one-portion load is chewed in place. No world drop or second source is involved. */
    public boolean chewCarried(Purpose source) {
        var before = load();
        if (before == null || before.purpose() != source || kitInMouth() || ParentCarry.mouthCarrying(owner)) return false;
        var stack = stack(source);
        if (items(source).size() != 1 || stack.getCount() != 1) return false;
        return held.replace(before, new Load(Purpose.MEAL, List.of(stack), BodyFood.Bite.EMPTY));
    }

    public boolean releaseMeal(Predicate<ItemStack> item, Predicate<BodyFood.Bite> bite) {
        return purpose() == Purpose.MEAL
                && held.release(load -> load.bite().isEmpty() ? item.test(load.items().getFirst()) : bite.test(load.bite()));
    }

    public boolean offerKindnessFood(Predicate<ItemStack> accepted) {
        return purpose() == Purpose.KINDNESS && held.release(load -> accepted.test(load.items().getFirst()));
    }

    /** The recipient takes one piece before the original serving is replaced by the piece that stays here. */
    public boolean splitKindnessFood(ItemStack piece, Predicate<ItemStack> accepted) {
        var before = load();
        return before != null && before.purpose() == Purpose.KINDNESS && accepted.test(piece.copy())
                && held.replace(before, new Load(Purpose.KINDNESS, List.of(piece), BodyFood.Bite.EMPTY));
    }

    public boolean prepareKindnessFood() {
        var before = load();
        return before != null && before.purpose() == Purpose.MEAL && !before.items().isEmpty()
                && held.replace(before, new Load(Purpose.KINDNESS, before.items(), BodyFood.Bite.EMPTY));
    }

    /** Release one real stack at a time so a later refusal cannot repeat an already accepted drop. */
    public boolean releaseItems(Purpose purpose, int delay) {
        while (!items(purpose).isEmpty()) {
            var stack = stack(purpose);
            var dropped = ItemContact.releaseSupported(owner, stack, delay);
            if (dropped == null) return false;
            takeFirst(purpose);
            GameplayLog.event(owner, "body", "mouth drop item={} count={} purpose={} drop={}", stack.getItem(),
                    stack.getCount(), purpose, dropped.getUUID());
        }
        return true;
    }

    public void importItems(Purpose purpose, List<ItemStack> items) {
        if (!importing || items.isEmpty()) return;
        if (held.isEmpty()) held.acquire(new Load(purpose, new ArrayList<>(items), BodyFood.Bite.EMPTY));
        else displaced.addAll(items);
    }

    public void importItem(Purpose purpose, ItemStack stack) {
        if (!stack.isEmpty()) importItems(purpose, List.of(stack));
    }

    public void importMeal(ItemStack item, BodyFood.Bite bite, boolean usableItem) {
        if (!importing) return;
        if (bite.isEmpty()) {
            if (usableItem && !item.isEmpty()) importItem(Purpose.MEAL, item.split(1));
            if (!item.isEmpty()) displaced.add(item);
        }
        else {
            held.acquire(new Load(Purpose.MEAL, List.of(), bite));
            if (!item.isEmpty()) displaced.add(item);
        }
    }

    public CompoundTag save() {
        var tag = new CompoundTag();
        var load = load();
        if (load != null) {
            tag.putString("Purpose", load.purpose().name());
            tag.put("Items", saveItems(load.items()));
            if (!load.bite().isEmpty()) tag.put("Bite", load.bite().save());
        }
        if (!displaced.isEmpty()) tag.put("Displaced", saveItems(displaced));
        if (reconcileExternal) tag.putBoolean("LegacyLoads", true);
        return tag;
    }

    public void load(CompoundTag root) {
        importing = !root.contains("YukkuriMouth", Tag.TAG_COMPOUND);
        reconcileOnTick = true;
        refusedAt = null;
        held.restore(null);
        displaced.clear();
        var tag = root.getCompound("YukkuriMouth");
        reconcileExternal = importing || tag.getBoolean("LegacyLoads");
        displaced.addAll(readItems(tag, "Displaced"));
        if (tag.contains("Purpose", Tag.TAG_STRING)) {
            var purpose = Purpose.valueOf(tag.getString("Purpose"));
            var stacks = readItems(tag, "Items");
            var bite = BodyFood.Bite.load(tag.getCompound("Bite"));
            if (!stacks.isEmpty() || !bite.isEmpty()) held.restore(new Load(purpose, stacks, bite));
        }
    }

    private ListTag saveItems(List<ItemStack> stacks) {
        var tag = new ListTag();
        for (var stack : stacks) if (!stack.isEmpty()) tag.add(stack.save(owner.registryAccess()));
        return tag;
    }

    private List<ItemStack> readItems(CompoundTag tag, String key) {
        var stacks = new ArrayList<ItemStack>();
        for (var entry : tag.getList(key, Tag.TAG_COMPOUND)) {
            var stack = ItemStack.parseOptional(owner.registryAccess(), (CompoundTag) entry);
            if (!stack.isEmpty()) stacks.add(stack);
        }
        return stacks;
    }

    /** Vanilla has attached restored passengers by the first entity tick. They keep their real holds. */
    public void settleLegacy() {
        if ((!reconcileOnTick && displaced.isEmpty()) || owner.blockPosition().equals(refusedAt)) return;
        if (reconcileOnTick) {
            reconcileOnTick = false;
            boolean legacy = reconcileExternal;
            importing = false;
            reconcileExternal = false;
            boolean child = ParentCarry.mouthCarrying(owner);
            boolean kit = kitInMouth();
            if (legacy && (child || kit || !held.isEmpty())) {
                var trip = owner.forageTrip().takeLegacyLoad();
                if (!trip.isEmpty()) displaced.add(trip);
                displaced.addAll(owner.habitat().takeLegacyLoads());
            }
            if ((child || kit) && !held.isEmpty()) {
                boolean meal = purpose() == Purpose.MEAL;
                displaced.addAll(load().items());
                if (!bite().isEmpty()) BodyEffects.emit(owner, "spit", bite().portions(), MouthPlacement.position(owner));
                held.consume();
                if (meal) owner.life().clearDisplacedMeal();
            }
            if (child && kit) {
                var custody = owner.life().custody();
                boolean released = !custody.kit().isEmpty() ? custody.releaseKit(owner.position())
                        : PlacedHatEntity.find(owner).releaseKit(owner.position(), Vec3.ZERO, "legacy_mouth");
                if (!released) {
                    reconcileOnTick = true;
                    refusedAt = owner.blockPosition();
                    return;
                }
            }
        }
        while (!displaced.isEmpty()) {
            var stack = displaced.getFirst();
            var drop = ItemContact.release(owner, stack, owner.position(), Vec3.ZERO);
            if (drop == null) {
                refusedAt = owner.blockPosition();
                GameplayLog.event(owner, "body", "legacy mouth drop refused item={} count={}", stack.getItem(), stack.getCount());
                return;
            }
            displaced.removeFirst();
            GameplayLog.event(owner, "body", "legacy mouth drop item={} count={} drop={}", stack.getItem(), stack.getCount(), drop.getUUID());
        }
        refusedAt = null;
    }

    public void interruptWork(String reason) {
        var purpose = purpose();
        if (purpose == Purpose.CARE) owner.family().releaseCareFood(reason);
        else if (purpose == Purpose.DUTY) owner.settlement().duties().putDownLoad();
        else if (purpose == Purpose.SWADDLE) owner.leafSwaddle().putDownLeaves();
        else if (purpose == Purpose.HOUSE) owner.house().dropCarrying();
        else if (purpose == Purpose.FIND) owner.treasure().stop();
        else if (purpose == Purpose.KINDNESS) releaseItems(purpose, 20);
    }

    public void releaseAtDeath() {
        if (purpose() == Purpose.MEAL) owner.life().releaseMouthForDeath();
        else if (purpose() == Purpose.SWADDLE) owner.leafSwaddle().putDownLeaves();
    }

    public ListTag itemsForRemains() {
        var tag = saveItems(displaced);
        var load = load();
        if (load != null) tag.addAll(saveItems(load.items()));
        return tag;
    }

    public void remainsAcceptedItems() {
        var load = load();
        if (load != null && !load.items().isEmpty()) held.consume();
        displaced.clear();
    }
}
