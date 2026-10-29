package io.github.longshou.mcyukkuri.aquatic;

import com.google.gson.JsonObject;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.headwear.HatGeometry;
import io.github.longshou.mcyukkuri.headwear.PlacedHatEntity;
import io.github.longshou.mcyukkuri.motor.geom.Buoyancy;
import io.github.longshou.mcyukkuri.motor.geom.Disc;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * The food a floating hat holds (design 8.2, refactor WP4b). Every portion is one real item lying on its own floor circle in
 * the hollow, never on the brim: it weighs on the hull (draft and heel) and on its riders' pace (ability load L), no foot may
 * share its floor, and it spills as floating items when the hat turns over. It is the hull's own state, saved in its Motion
 * and drawn from the hat's synced contents. Nothing eats from it yet (WP6).
 */
public final class HullLarder {
    private static final JsonObject DATA = HatGeometry.data().getAsJsonObject("afloat");
    /** At most this many portions of at most this many kinds; one portion's floor circle (blocks) and its ability load. */
    public static final int MAX = DATA.get("portions").getAsInt(), KINDS = DATA.get("kinds").getAsInt();
    public static final double RADIUS = DATA.get("portion_radius").getAsDouble(), LOAD = DATA.get("portion_load").getAsDouble();
    /** A portion weighs on the hull as a round lump of a yukkuri body's density. */
    static final double MASS = Buoyancy.bodyMass(2 * RADIUS, 2 * RADIUS, Buoyancy.BODY_DENSITY);
    /** A sneaking click hands over up to this many portions (AQUATIC_MARISA_IMPLEMENTATION); the floor is searched on this
     *  lattice (hat units). */
    private static final int HANDFUL = 8;
    private static final double LATTICE = .5;

    /** One portion: the item and where it lies (hat units, on the floor). */
    record Portion(ItemStack item, double x, double y, double z) {}

    private final List<Portion> portions = new ArrayList<>();
    private boolean dirty = true;

    List<Portion> portions() { return portions; }
    boolean empty() { return portions.isEmpty(); }
    double load() { return portions.size() * LOAD; }

    /** Each portion's floor circle in hat units at this hat's size. */
    List<Disc> discs(PlacedHatEntity hat) {
        double r = RADIUS / hat.unit();
        return portions.stream().map(p -> new Disc(p.x(), p.z(), r)).toList();
    }

    /**
     * Food from a player's click (one portion, a sneaking click a handful) or a scenario's starting stock ({@code by} null, the
     * whole stack): each portion takes its own free floor spot. Anything that is not food is left to the click (false). A
     * refusal keeps the item, logs the real reason and puffs the owner's cheeks; the hat is not rocked.
     */
    boolean store(PlacedHatEntity hat, ItemStack stack, Player by) {
        if (!stack.is(YukkuriEntity.FOOD)) return false;
        if (hat.level().isClientSide) return true;
        int want = by == null ? stack.getCount() : Math.min(stack.getCount(), by.isSecondaryUseActive() ? HANDFUL : 1);
        int before = portions.size(), taken = 0;
        var item = stack.getItem();
        String stop = null;
        while (taken < want && stop == null) {
            stop = hat.upsideDown() ? "upside_down" : portions.size() >= MAX ? "full" : newKind(stack) && kinds() >= KINDS ? "kinds" : null;
            var spot = stop == null ? spot(hat) : null;
            if (stop == null && spot == null) stop = "no_room";
            if (stop == null) { portions.add(new Portion(stack.copyWithCount(1), spot.x, spot.y, spot.z)); taken++; }
        }
        if (taken > 0) {
            if (by == null || !by.getAbilities().instabuild) stack.shrink(taken);
            dirty = true;
            GameplayLog.event(hat, "hat", "stored by={} item={} taken={} asked={} portions={} -> {} kinds={} load={} stop={}",
                    by == null ? "scenario" : by.getUUID(), item, taken, want, before, portions.size(), kinds(), fmt(load()),
                    stop == null ? "none" : stop);
        } else {
            GameplayLog.limited(hat, "hat", "refused." + stop, 20, "refused by={} item={} reason={} portions={}/{} kinds={}/{} owner={}",
                    by == null ? "scenario" : by.getUUID(), item, stop, portions.size(), MAX, kinds(), KINDS, hat.ownerYukkuri());
            var owner = hat.owner();
            if (owner != null) owner.showReaction("cheek_puff", "refuse", 40);
        }
        if (by != null) by.displayClientMessage(Component.literal(taken > 0 ? "帽内储粮 " + portions.size() + "/" + MAX
                : switch (stop) {
                    case "full" -> "帽里的粮已经满了（" + MAX + " 份）";
                    case "kinds" -> "帽里的粮已有 " + KINDS + " 种，放不下新的种类";
                    case "upside_down" -> "帽子倒扣着，放不进粮";
                    default -> "帽底没有空位放这份粮";
                }), true);
        return true;
    }

    /** The hat turned over: every portion falls out where it lies as a real floating item, moving as the hull carried it there. */
    void spill(PlacedHatEntity hat, String cause, Function<Vec3, Vec3> carried) {
        if (portions.isEmpty()) return;
        var frame = hat.frame();
        var items = new ArrayList<String>();
        int count = portions.size(), spawned = 0;
        for (var p : portions) {
            var at = frame.toWorld(new Vec3(p.x(), p.y(), p.z()));
            var drop = new ItemEntity(hat.level(), at.x, at.y, at.z, p.item().copy());
            drop.setDeltaMovement(carried.apply(at));
            drop.setDefaultPickUpDelay();
            if (hat.level().addFreshEntity(drop)) spawned++;
            items.add(p.item().getItem().toString());
        }
        portions.clear();
        dirty = true;
        GameplayLog.event(hat, "hat", "spilled cause={} portions={} spawned={} items={}", cause, count, spawned, items);
    }

    /** The contents slot, written when the portions changed: each one's item and spot, for drawing only. */
    void sync(PlacedHatEntity hat) {
        if (!dirty) return;
        dirty = false;
        var tag = new CompoundTag();
        tag.put("Portions", save(hat));
        hat.contents(tag);
    }

    ListTag save(PlacedHatEntity hat) {
        var list = new ListTag();
        for (var p : portions) {
            var tag = new CompoundTag();
            tag.put("Item", p.item().save(hat.registryAccess()));
            tag.putDouble("X", p.x()); tag.putDouble("Y", p.y()); tag.putDouble("Z", p.z());
            list.add(tag);
        }
        return list;
    }

    void load(PlacedHatEntity hat, ListTag list) {
        portions.clear();
        for (int i = 0; i < list.size(); i++) {
            var tag = list.getCompound(i);
            var item = ItemStack.parseOptional(hat.registryAccess(), tag.getCompound("Item"));
            if (!item.isEmpty()) portions.add(new Portion(item.copyWithCount(1), tag.getDouble("X"), tag.getDouble("Y"), tag.getDouble("Z")));
        }
        dirty = true;
    }

    /** A portion as synced: its item and spot (hat units). */
    public record Shown(ItemStack item, Vec3 at) {}

    /** The portions of a synced contents tag, for the client to draw. */
    public static List<Shown> shown(CompoundTag contents, HolderLookup.Provider registries) {
        var out = new ArrayList<Shown>();
        var list = contents.getList("Portions", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            var tag = list.getCompound(i);
            var item = ItemStack.parseOptional(registries, tag.getCompound("Item"));
            if (!item.isEmpty()) out.add(new Shown(item, new Vec3(tag.getDouble("X"), tag.getDouble("Y"), tag.getDouble("Z"))));
        }
        return out;
    }

    /**
     * The free floor spot for one more portion, or null: in the hollow (its whole circle below the brim), clear of every foot
     * and portion, as far from the middle as it goes (against the hat's wall), the two sides in turn.
     */
    private Vec3 spot(PlacedHatEntity hat) {
        double r = RADIUS / hat.unit(), top = HatGeometry.top(), best = -1;
        float[] o = HatGeometry.opening();
        int side = portions.size() % 2 == 0 ? 1 : -1;
        Vec3 out = null;
        for (double x = o[0] + r; x <= o[1] - r; x += LATTICE)
            for (double z = o[2] + r; z <= o[3] - r; z += LATTICE) {
                var floor = hat.support(new Vec3(x, 0, z), RADIUS);
                if (floor.isEmpty() || floor.getAsDouble() >= top - 1e-6 || !hat.clear(new Disc(x, z, r), null)) continue;
                double score = (x * side > 0 ? 1000 : 0) + Math.hypot(x, z);
                if (score > best) { best = score; out = new Vec3(x, floor.getAsDouble(), z); }
            }
        return out;
    }

    private boolean newKind(ItemStack stack) {
        return portions.stream().noneMatch(p -> ItemStack.isSameItemSameComponents(p.item(), stack));
    }

    private int kinds() {
        var seen = new ArrayList<ItemStack>();
        for (var p : portions) if (seen.stream().noneMatch(s -> ItemStack.isSameItemSameComponents(s, p.item()))) seen.add(p.item());
        return seen.size();
    }

    private static String fmt(double v) { return String.format(Locale.ROOT, "%.2f", v); }
}
