package io.github.longshou.mcyukkuri.aquatic;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.headwear.PlacedHatEntity;
import io.github.longshou.mcyukkuri.motor.action.Pick;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/** Counted food by item kind. Stores have neither a position nor a load; only their floor texture has bounds. */
public final class HullLarder {
    /** Pure count transactions and the single presentation tier table (project design). */
    public static final class Counts {
        public static final int CAPACITY = 8;
        private record Tier(int through, double radius) {}
        private static final Tier[] TIERS = {new Tier(0, 0), new Tier(2, .45), new Tier(5, .70), new Tier(CAPACITY, 1)};
        private final Map<String, Integer> kinds = new LinkedHashMap<>();

        public int total() { return kinds.values().stream().mapToInt(Integer::intValue).sum(); }
        int count(String kind) { return kinds.getOrDefault(kind, 0); }
        int add(String kind, int asked) {
            int accepted = Math.min(asked, CAPACITY - total());
            if (accepted > 0) kinds.merge(kind, accepted, Integer::sum);
            return accepted;
        }
        void take(String kind) {
            int remaining = kinds.get(kind) - 1;
            if (remaining == 0) kinds.remove(kind); else kinds.put(kind, remaining);
        }
        Map<String, Integer> save() { return new LinkedHashMap<>(kinds); }
        void load(Map<String, Integer> saved) { kinds.clear(); kinds.putAll(saved); }
        Map<String, Integer> spill() { var out = save(); kinds.clear(); return out; }
        public static int tier(int count) {
            int tier = 0;
            while (count > TIERS[tier].through()) tier++;
            return tier;
        }
        public static double radius(int count) { return TIERS[tier(count)].radius(); }
    }

    private final Counts stores = new Counts();
    private int eats, refusals;

    static HullLarder of(PlacedHatEntity hat) { return hat != null && hat.behavior() instanceof HullAfloat afloat ? afloat.larder() : null; }
    int count() { return stores.total(); }
    boolean has(Predicate<ItemStack> wanted) { return stores.kinds.keySet().stream().anyMatch(kind -> wanted.test(item(kind))); }
    boolean room(PlacedHatEntity hat) { return !hat.upsideDown() && count() < Counts.CAPACITY; }
    private static ItemStack item(String kind) { return new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(kind))); }

    /** Reach down in place: membership of the upright host is the contact contract, not a path to an individual item. */
    Pick.Source source(PlacedHatEntity hat, YukkuriEntity actor, Predicate<ItemStack> wanted, String use) {
        var kind = stores.kinds.keySet().stream().filter(key -> wanted.test(item(key))).findFirst().orElse(null);
        if (kind == null) return null;
        return new Pick.Source() {
            @Override public ItemStack item() { return stores.count(kind) > 0 ? HullLarder.item(kind) : ItemStack.EMPTY; }
            @Override public Vec3 at() { return hat.frame().toWorld(new Vec3(0, hat.profile().floor(), 0)); }
            @Override public double gap(YukkuriEntity body) { return body.getVehicle() == hat && !hat.upsideDown() ? 0 : Double.MAX_VALUE; }
            @Override public void take() {
                int before = stores.count(kind), total = count();
                stores.take(kind);
                if (use.equals("eat")) eats++;
                sync(hat);
                GameplayLog.event(hat, "hat", "stores taken by={} use={} kind={} count={} -> {} total={} -> {} tier={}",
                        actor.getUUID(), use, kind, before, stores.count(kind), total, count(), Counts.tier(count()));
            }
        };
    }

    boolean store(PlacedHatEntity hat, ItemStack stack, Player by) {
        if (!stack.is(YukkuriEntity.FOOD)) {
            if (!hat.level().isClientSide) refuse(hat, stack, by.getUUID().toString(), "not_food");
            return false;
        }
        if (hat.level().isClientSide) return true;
        int asked = Math.min(stack.getCount(), by.isSecondaryUseActive() ? Counts.CAPACITY : 1);
        int accepted = put(hat, stack, asked, by.getUUID().toString());
        if (!by.getAbilities().instabuild) stack.shrink(accepted);
        by.displayClientMessage(Component.literal(accepted > 0 ? "帽内储粮 " + count() + "/" + Counts.CAPACITY + " 份"
                : hat.upsideDown() ? "帽子倒扣着，放不进粮" : "帽内储粮已满（" + count() + "/" + Counts.CAPACITY + "）"), true);
        return true;
    }

    int stock(PlacedHatEntity hat, ItemStack food, int count) { return put(hat, food, count, "scenario"); }
    boolean keep(PlacedHatEntity hat, ItemStack one, YukkuriEntity by) { return put(hat, one, 1, by.getUUID().toString()) == 1; }

    private int put(PlacedHatEntity hat, ItemStack food, int asked, String actor) {
        String kind = BuiltInRegistries.ITEM.getKey(food.getItem()).toString();
        int before = stores.count(kind), total = count();
        int accepted = hat.upsideDown() ? 0 : stores.add(kind, asked);
        if (accepted == 0) refuse(hat, food, actor, hat.upsideDown() ? "upside_down" : "full");
        else {
            sync(hat);
            GameplayLog.event(hat, "hat", "stores put by={} kind={} accepted={} asked={} count={} -> {} total={} -> {} tier={}",
                    actor, kind, accepted, asked, before, stores.count(kind), total, count(), Counts.tier(count()));
        }
        return accepted;
    }

    private void refuse(PlacedHatEntity hat, ItemStack food, String actor, String reason) {
        refusals++;
        GameplayLog.limited(hat, "hat", "stores." + reason, 20, "stores refused by={} kind={} reason={} total={}",
                actor, food.getItem(), reason, count());
        var owner = hat.owner();
        if (owner != null) owner.showReaction("cheek_puff", "refuse", 40);
    }

    /** A stack per kind, split only at the vanilla stack limit; vanilla items float after the spill. */
    void spill(PlacedHatEntity hat, String cause) {
        if (count() == 0) return;
        var spilled = stores.spill();
        var at = hat.frame().toWorld(new Vec3(0, hat.profile().floor(), 0));
        for (var entry : spilled.entrySet()) {
            var food = item(entry.getKey());
            for (int left = entry.getValue(); left > 0;) {
                int count = Math.min(left, food.getMaxStackSize());
                var drop = new ItemEntity(hat.level(), at.x, at.y, at.z, food.copyWithCount(count));
                drop.setDefaultPickUpDelay();
                hat.level().addFreshEntity(drop);
                left -= count;
            }
        }
        sync(hat);
        GameplayLog.event(hat, "hat", "stores spilled by={} cause={} kinds={} -> {} total={} -> 0",
                hat.ownerYukkuri(), cause, spilled, stores.kinds, spilled.values().stream().mapToInt(Integer::intValue).sum());
    }

    private void sync(PlacedHatEntity hat) {
        var tag = new CompoundTag();
        tag.putInt("FoodCount", count());
        hat.contents(tag);
    }

    CompoundTag save() {
        var tag = new CompoundTag();
        stores.save().forEach(tag::putInt);
        return tag;
    }

    void load(PlacedHatEntity hat, CompoundTag tag) {
        var saved = new LinkedHashMap<String, Integer>();
        for (var kind : tag.getAllKeys()) saved.put(kind, tag.getInt(kind));
        stores.load(saved);
        sync(hat);
    }

    String detail() { return "stores=" + count() + "/" + Counts.CAPACITY + " tier=" + Counts.tier(count()) + " eats=" + eats + " refusals=" + refusals; }
}
