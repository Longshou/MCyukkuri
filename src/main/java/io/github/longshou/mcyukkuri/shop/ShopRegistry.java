package io.github.longshou.mcyukkuri.shop;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.human.Custody;
import io.github.longshou.mcyukkuri.human.HumanNpc;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.random.ModRandom;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Register of the pet shops (docs/FEATURES.md, 宠物店). A shop is its site: it has its own id, which owns its stock (custody owner, NPC kind,
 * no entity), so the shop outlives any one staff member and closes only when its last staff member dies ({@link #staffDied}). Staff belong
 * to the shop whose footprint holds their post. The template's marker entities give the footprint (two corners), the counter cell where
 * bought animals are handed over, the arrivals barrel and the waste barrel; the item-frame markers become fixed vanilla frames the first time
 * the shop registers. Deposits of reservations and refunds owed for reserved animals that died are saved here. The sale roster itself is
 * never stored: it is every stock animal physically inside the footprint ({@link #liveStock}). Server only; the overworld storage holds it
 * for every dimension.
 */
public final class ShopRegistry extends SavedData {
    private static final String NAME = "mcyukkuri_shop_register";
    static final String OWNER_ROLE = "shopkeeper";
    private static final String CORNER = "mcy_shop_corner", COUNTER = "mcy_shop_counter", DOCK = "mcy_shop_dock", TRASH = "mcy_shop_trash",
            FRAME = "mcy_shop_frame";
    /** Footprint around the founding post when a shop has no authored corners (a staff member placed by egg or command). */
    private static final int LOOSE_RADIUS = 12;
    /** A reservation: who paid, the locked price, the deposit, and when (it lapses one restock period later, ShopRestock). */
    record Deposit(UUID buyer, int price, int paid, long since) {}
    record Order(String kind, UUID customer, int price, UUID token) {}
    public static final class Entry {
        public final UUID id;
        public final String dimension;
        public final BlockPos pos;
        private BlockPos first, second, counter, dock, trash;
        private AABB protection;
        private boolean legacy, closed;
        private final Set<UUID> staff = new LinkedHashSet<>();
        private final Set<UUID> releases = new LinkedHashSet<>();
        long nextRestock;
        final Map<UUID, Deposit> deposits = new LinkedHashMap<>();
        final Map<UUID, Integer> refunds = new LinkedHashMap<>();
        final Map<UUID, Order> orders = new LinkedHashMap<>();
        Entry(UUID id, String dimension, BlockPos pos) { this.id = id; this.dimension = dimension; this.pos = pos; }
        public AABB bounds() {
            return first == null ? new AABB(pos).inflate(LOOSE_RADIUS, 4, LOOSE_RADIUS) : AABB.encapsulatingFullBlocks(first, second);
        }
        public boolean contains(Vec3 at) { return bounds().contains(at); }
        /** The staff side of the counter: where a bought animal is handed over. */
        public BlockPos counter() { return counter == null ? pos : counter; }
        BlockPos dock() { return dock; }
        BlockPos trash() { return trash; }
        public boolean authored() { return first != null; }
        boolean legacy() { return legacy; }
        boolean closed() { return closed; }
        boolean staff(UUID id) { return staff.contains(id); }
        Deposit deposit(UUID animal) { return deposits.get(animal); }
        long nextRestock() { return nextRestock; }
    }
    private final Map<UUID, Entry> shops = new LinkedHashMap<>();
    private final Map<UUID, List<Component>> notices = new LinkedHashMap<>();
    private final Map<String, Set<BlockPos>> openedDoors = new LinkedHashMap<>();

    public static ShopRegistry get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(ShopRegistry::new, ShopRegistry::load), NAME);
    }

    /** A new shop founded at this staff member's post (the template's counter clerk, or a lone staff member), its markers read once. */
    private Entry found(ServerLevel level, HumanNpc founder) {
        var dim = level.dimension().location().toString();
        var shop = new Entry(founder.getUUID(), dim, founder.home().immutable());
        if (!founder.siteRole().equals(OWNER_ROLE) || !readLegacyBounds(level, shop))
            shop = new Entry(ModRandom.identity(ModRandom.world(level)), dim, founder.home().immutable());
        shops.put(shop.id, shop);
        shop.staff.add(founder.getUUID());
        setDirty();
        if (!shop.legacy) readMarkers(level, shop);
        GameplayLog.event(founder, "shop", "action=found shop={} authored={}", shop.id, shop.authored());
        return shop;
    }

    /** The shop a staff member works in: the one whose footprint holds its post; a clerk or lone staff member with none founds it. */
    public Entry of(HumanNpc staff) {
        if (!(staff.level() instanceof ServerLevel level) || staff.job() != HumanNpc.Job.SHOPKEEPER) return null;
        for (var entry : shops.values()) if (entry.staff(staff.getUUID())) {
            if (entry.closed) return null;
            if (entry.legacy && entry.first == null) readLegacyBounds(level, entry);
            return entry;
        }
        var shop = shopAt(level, staff.home().getCenter());
        if (shop != null) { enroll(shop, staff); return shop; }
        if (staff.siteRole().isEmpty() || staff.siteRole().equals(OWNER_ROLE)) return found(level, staff);
        for (var clerk : level.getEntitiesOfClass(HumanNpc.class, new AABB(staff.home()).inflate(32),
                n -> n.isAlive() && n.job() == HumanNpc.Job.SHOPKEEPER && n.siteRole().equals(OWNER_ROLE))) of(clerk);
        shop = shopAt(level, staff.home().getCenter());
        if (shop != null) enroll(shop, staff);
        return shop;
    }

    private void enroll(Entry shop, HumanNpc npc) {
        if (shop.staff.add(npc.getUUID())) setDirty();
    }

    /** Old site roles are saved on the NPC, so conversion also works when a staff chunk loads later. */
    public void adoptLegacy(ServerLevel level, HumanNpc npc) {
        if (!npc.siteRole().equals("shop_keeper") && !npc.siteRole().equals("shop_sister")) return;
        if (npc.job() == HumanNpc.Job.SHOPKEEPER) return;
        for (var entry : shops.values())
            if (entry.legacy && !entry.closed && entry.first == null && entry.pos.distSqr(npc.home()) < 32 * 32
                    && entry.dimension.equals(level.dimension().location().toString())) readLegacyBounds(level, entry);
        var shop = shopAt(level, npc.home().getCenter());
        if (shop == null || !shop.legacy) return;
        npc.setJob(HumanNpc.Job.SHOPKEEPER);
        enroll(shop, npc);
    }

    /**
     * A staff member died: the shop goes on with the others. When none is left the shop closes like a dead owner: its stock and the baskets
     * holding stock lose their owner (the custody death record of the site id), and reservations end.
     */
    public void staffDied(ServerLevel level, HumanNpc dead) {
        var shop = of(dead);
        if (shop == null) { Custody.ownerDied(dead); return; }
        for (var npc : level.getEntitiesOfClass(HumanNpc.class, shop.bounds().inflate(8), n -> n != dead && n.isAlive())) {
            adoptLegacy(level, npc);
            if (npc.job() == HumanNpc.Job.SHOPKEEPER && shop.contains(npc.home().getCenter())) enroll(shop, npc);
        }
        shop.releases.addAll(YukkuriWorldData.get(level).captiveTokens(view -> view.role() == Custody.Role.STOCK && view.ownedBy(dead.getUUID())
                && !view.ownedBy(shop.id)));
        if (!dead.getUUID().equals(shop.id)) Custody.ownerDied(dead);
        shop.staff.remove(dead.getUUID());
        setDirty();
        boolean staffed = !shop.staff.isEmpty();
        GameplayLog.event(dead, "shop", "action=staff_died shop={} shop_closes={}", shop.id, !staffed);
        if (staffed) return;
        shop.closed = true;
        for (var animal : List.copyOf(shop.deposits.keySet())) refund(shop, animal);
        for (var animal : List.copyOf(shop.orders.keySet()))
            if (shop.orders.get(animal).token() == null) ShopTrade.cancel(level, shop, animal, "closed");
        shop.releases.addAll(stockBaskets(level, shop));
        Custody.ownerGone(level, shop.id);
        recoverClosed(level, shop);
    }

    /** The shop whose footprint holds this point, in this dimension. */
    public Entry shopAt(ServerLevel level, Vec3 at) {
        String dim = level.dimension().location().toString();
        for (var shop : shops.values()) if (!shop.closed && shop.dimension.equals(dim) && shop.contains(at)) return shop;
        return null;
    }

    /** Inside an authored shop footprint: no player breaks, places or uses anything there (ShopProtection). */
    public boolean protectedAt(ServerLevel level, BlockPos pos) {
        var dim = level.dimension().location().toString();
        for (var shop : shops.values()) if (!shop.closed && shop.dimension.equals(dim)
                && (shop.protection != null ? shop.protection.contains(pos.getCenter()) : shop.legacy && shop.contains(pos.getCenter()))) return true;
        return false;
    }

    /** Every stock animal of this shop that is physically inside its footprint: the sale roster. */
    public static List<YukkuriEntity> liveStock(ServerLevel level, Entry shop) {
        return level.getEntitiesOfClass(YukkuriEntity.class, shop.bounds(), y -> y.isAlive() && shop.contains(y.position())
                && ShopTrade.stockOf(y, shop.id));
    }

    static List<UUID> stockBaskets(ServerLevel level, Entry shop) {
        return YukkuriWorldData.get(level).captiveTokens(view -> view.role() == Custody.Role.STOCK && view.ownedBy(shop.id));
    }

    static void releaseStockBasket(ServerLevel level, UUID token, BlockPos near) {
        releaseBasketNear(level, token, near, true);
    }

    static YukkuriEntity releaseBasketNear(ServerLevel level, UUID token, BlockPos near, boolean releaseCustody) {
        var data = YukkuriWorldData.get(level);
        for (var cell : BlockPos.withinManhattan(near, 4, 2, 4)) {
            if (!level.hasChunkAt(cell) || !level.getBlockState(cell).getCollisionShape(level, cell).isEmpty()
                    || !level.getBlockState(cell.below()).isFaceSturdy(level, cell.below(), Direction.UP)) continue;
            var animal = data.releaseOnGround(token, level, Vec3.atBottomCenterOf(cell), 0);
            if (animal == null) continue;
            if (releaseCustody && Custody.view(animal).role() == Custody.Role.STOCK)
                Custody.release(animal, Custody.ReleaseReason.DELIVERY_FAILED, Custody.view(animal).owner(), null);
            return animal;
        }
        return null;
    }

    private void recoverClosed(ServerLevel level, Entry shop) {
        if (shop.releases.isEmpty() || !level.hasChunkAt(shop.counter())) return;
        var data = YukkuriWorldData.get(level);
        for (var token : List.copyOf(shop.releases)) {
            if (data.hasToken(token)) releaseStockBasket(level, token, shop.counter());
            if (!data.hasToken(token)) {
                ShopStaff.emptyStockBasket(level, shop, token);
                shop.releases.remove(token);
                setDirty();
            }
        }
    }

    public static void levelTick(net.neoforged.neoforge.event.tick.LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.getGameTime() % 20 != 0) return;
        var registry = get(level);
        var doors = registry.openedDoors.get(level.dimension().location().toString());
        if (doors != null) ShopDoors.closeUnattended(level, doors);
        for (var shop : registry.shops.values()) if (shop.dimension.equals(level.dimension().location().toString())) {
            if (shop.closed || !shop.releases.isEmpty()) registry.recoverClosed(level, shop);
            else if (!shop.orders.isEmpty()) ShopStaff.reviewOrders(level, shop);
            ShopTrade.recoverPaid(level, shop);
        }
    }

    void openedDoors(ServerLevel level, List<BlockPos> leaves) {
        if (openedDoors.computeIfAbsent(level.dimension().location().toString(), key -> new LinkedHashSet<>()).addAll(leaves)) setDirty();
    }

    void closedDoor(ServerLevel level, BlockPos leaf) {
        var leaves = openedDoors.get(level.dimension().location().toString());
        if (leaves != null && leaves.remove(leaf)) setDirty();
    }

    public static void playerTick(net.neoforged.neoforge.event.tick.PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer player && player.tickCount % 20 == 0) get(player.serverLevel()).payRefunds(player);
    }

    void payRefunds(ServerPlayer player) {
        var pending = notices.remove(player.getUUID());
        if (pending != null) {
            pending.forEach(player::sendSystemMessage);
            setDirty();
        }
        for (var shop : shops.values()) {
            int owed = collectRefund(shop, player.getUUID());
            if (owed <= 0) continue;
            ShopTrade.pay(player, owed);
            player.sendSystemMessage(net.minecraft.network.chat.Component.translatable("shop.mcyukkuri.reserve.refunded", owed));
            GameplayLog.event(player, "shop", "action=reserve result=refunded shop={} emeralds_received={}", shop.id, owed);
        }
    }

    void tell(ServerLevel level, UUID customer, String key, Object... args) {
        if (customer == null) return;
        var message = Component.translatable("shop.mcyukkuri." + key, args);
        var player = level.getServer().getPlayerList().getPlayer(customer);
        if (player != null) player.sendSystemMessage(message);
        else {
            notices.computeIfAbsent(customer, id -> new ArrayList<>()).add(message);
            setDirty();
        }
    }

    public static void animalDied(net.neoforged.neoforge.event.entity.living.LivingDeathEvent event) {
        if (!(event.getEntity() instanceof YukkuriEntity animal) || !(animal.level() instanceof ServerLevel level)) return;
        var registry = get(level);
        for (var shop : registry.shops.values()) {
            registry.refund(shop, animal.getUUID());
            var order = shop.orders.get(animal.getUUID());
            if (order != null && order.token() == null) ShopTrade.cancel(level, shop, animal.getUUID(), "gone");
        }
    }

    private void readMarkers(ServerLevel level, Entry shop) {
        var markers = level.getEntitiesOfClass(Marker.class, new AABB(shop.pos).inflate(32, 16, 32), m -> m.getTags().stream().anyMatch(t -> t.startsWith("mcy_shop_")));
        var corners = new ArrayList<BlockPos>();
        for (var m : markers) {
            var at = m.blockPosition();
            if (m.getTags().contains(CORNER)) corners.add(at);
            else if (m.getTags().contains(COUNTER)) shop.counter = at;
            else if (m.getTags().contains(DOCK)) shop.dock = at;
            else if (m.getTags().contains(TRASH)) shop.trash = at;
        }
        if (corners.size() != 2) return;
        shop.first = corners.getFirst(); shop.second = corners.getLast();
        shop.protection = authoredProtection(shop);
        for (var m : markers) if (m.getTags().contains(FRAME) && shop.contains(m.position())) hangFrame(level, m);
        GameplayLog.world(level, "shop", "action=register shop={} corners={}..{} counter={} dock={} trash={}", shop.id,
                shop.first.toShortString(), shop.second.toShortString(), shop.counter, shop.dock, shop.trash);
        setDirty();
    }

    /** Both templates share the shell. In a legacy world the old street door fixes its rotation. */
    private boolean readLegacyBounds(ServerLevel level, Entry shop) {
        for (var rotation : Rotation.values()) {
            var door = shop.pos.offset(new BlockPos(1, 0, -11).rotate(rotation));
            if (!level.hasChunkAt(door)) continue;
            var state = level.getBlockState(door);
            var examDoor = shop.pos.offset(new BlockPos(16, 0, -11).rotate(rotation));
            if (!state.is(Blocks.OAK_DOOR) || state.getValue(DoorBlock.FACING) != rotation.rotate(Direction.NORTH)
                    || !level.hasChunkAt(examDoor) || !level.getBlockState(examDoor).is(Blocks.OAK_DOOR)) continue;
            shop.legacy = true;
            shop.first = shop.pos.offset(new BlockPos(-10, -3, -11).rotate(rotation));
            shop.second = shop.pos.offset(new BlockPos(12, 12, 18).rotate(rotation));
            shop.protection = shell(shop.pos, rotation, 11);
            setDirty();
            return true;
        }
        return false;
    }

    private static AABB authoredProtection(Entry shop) {
        for (var rotation : Rotation.values()) {
            var first = shop.pos.offset(new BlockPos(-11, -3, -11).rotate(rotation));
            if (first.equals(shop.first) || first.equals(shop.second)) return shell(shop.pos, rotation, 12);
        }
        return shop.bounds();
    }

    private static AABB shell(BlockPos clerk, Rotation rotation, int clerkX) {
        return AABB.encapsulatingFullBlocks(clerk.offset(new BlockPos(-clerkX, -3, -14).rotate(rotation)),
                clerk.offset(new BlockPos(40 - clerkX, 12, 18).rotate(rotation)));
    }

    /** A fixed vanilla item frame on the wall behind the marker's cell, showing the marker's good; the marker is used up. */
    private static void hangFrame(ServerLevel level, Marker marker) {
        var at = marker.blockPosition();
        Direction facing = null;
        for (var d : Direction.Plane.HORIZONTAL)
            if (level.getBlockState(at.relative(d.getOpposite())).isSolid() && !level.getBlockState(at.relative(d)).isSolid()) { facing = d; break; }
        var data = marker.saveWithoutId(new CompoundTag()).getCompound("data");
        if (facing == null || !data.contains("Item", 10)) return;
        var tag = new CompoundTag();
        tag.putString("id", "minecraft:item_frame");
        var pos = new ListTag(); pos.add(DoubleTag.valueOf(at.getX() + .5)); pos.add(DoubleTag.valueOf(at.getY() + .5)); pos.add(DoubleTag.valueOf(at.getZ() + .5));
        tag.put("Pos", pos);
        tag.putInt("TileX", at.getX()); tag.putInt("TileY", at.getY()); tag.putInt("TileZ", at.getZ());
        tag.putByte("Facing", (byte) facing.get3DDataValue());
        tag.put("Item", data.getCompound("Item"));
        tag.putBoolean("Fixed", true);
        tag.putBoolean("Invulnerable", true);
        var frame = EntityType.loadEntityRecursive(tag, level, e -> e);
        if (frame != null && level.addFreshEntity(frame)) marker.discard();
    }

    // ---- reservations ------------------------------------------------------------------------------------------------

    void reserve(Entry shop, UUID animal, UUID buyer, int price, int paid, long now) { shop.deposits.put(animal, new Deposit(buyer, price, paid, now)); setDirty(); }
    /** A reservation not taken up within one restock period lapses: the animal is on sale again and the shop keeps the deposit. */
    Deposit lapse(Entry shop, UUID animal) {
        var deposit = shop.deposits.remove(animal);
        if (deposit != null) setDirty();
        return deposit;
    }
    void sold(Entry shop, UUID animal) { if (shop.deposits.remove(animal) != null) setDirty(); }
    /** A reserved animal that is gone (died, or left the shop other than by its sale): its deposit is owed back to the buyer. */
    void refund(Entry shop, UUID animal) {
        var deposit = shop.deposits.remove(animal);
        if (deposit == null) return;
        shop.refunds.merge(deposit.buyer(), deposit.paid(), Integer::sum);
        setDirty();
    }
    /** Deposits owed to this player, paid out now (the menu opening). */
    int collectRefund(Entry shop, UUID player) {
        Integer owed = shop.refunds.remove(player);
        if (owed != null) setDirty();
        return owed == null ? 0 : owed;
    }
    void restocked(Entry shop, long next) { shop.nextRestock = next; setDirty(); }
    void order(Entry shop, UUID animal, Order order) { shop.orders.put(animal, order); setDirty(); }
    Order endOrder(Entry shop, UUID animal) {
        var order = shop.orders.remove(animal);
        if (order != null) setDirty();
        return order;
    }

    /**
     * Playground field clear only (command/PlaygroundTools): shops whose post stands in {@code box} leave the register (their discarded
     * staff are not dead, so {@link #staffDied} never runs for them). Returns the forgotten shop ids.
     */
    public Set<UUID> forget(ServerLevel level, AABB box, Set<UUID> removed) {
        String dimension = level.dimension().location().toString();
        var gone = new LinkedHashSet<UUID>();
        for (var shop : shops.values())
            if (shop.dimension.equals(dimension) && box.contains(shop.pos.getCenter())) gone.add(shop.id);
        if (shops.keySet().removeAll(gone)) setDirty();
        return gone;
    }
    public Entry shop(UUID id) { return shops.get(id); }
    public List<Entry> shops() { return new ArrayList<>(shops.values()); }

    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        var list = new ListTag();
        for (var shop : shops.values()) {
            var c = new CompoundTag(); c.putUUID("Id", shop.id); c.putString("Dim", shop.dimension); c.put("Pos", NbtUtils.writeBlockPos(shop.pos));
            c.putLong("NextRestock", shop.nextRestock);
            c.putBoolean("Legacy", shop.legacy);
            c.putBoolean("Closed", shop.closed);
            c.put("Staff", ids(shop.staff));
            c.put("Releases", ids(shop.releases));
            var orders = new ListTag();
            shop.orders.forEach((animal, order) -> {
                var e = new CompoundTag();
                e.putUUID("Animal", animal); e.putString("Kind", order.kind()); e.putInt("Price", order.price());
                if (order.customer() != null) e.putUUID("Customer", order.customer());
                if (order.token() != null) e.putUUID("Token", order.token());
                orders.add(e);
            });
            c.put("Orders", orders);
            if (shop.first != null) { c.put("First", NbtUtils.writeBlockPos(shop.first)); c.put("Second", NbtUtils.writeBlockPos(shop.second)); }
            if (shop.protection != null) {
                c.put("ProtectedMin", NbtUtils.writeBlockPos(BlockPos.containing(shop.protection.minX, shop.protection.minY, shop.protection.minZ)));
                c.put("ProtectedMax", NbtUtils.writeBlockPos(BlockPos.containing(shop.protection.maxX - 1, shop.protection.maxY - 1, shop.protection.maxZ - 1)));
            }
            if (shop.counter != null) c.put("Counter", NbtUtils.writeBlockPos(shop.counter));
            if (shop.dock != null) c.put("Dock", NbtUtils.writeBlockPos(shop.dock));
            if (shop.trash != null) c.put("Trash", NbtUtils.writeBlockPos(shop.trash));
            var deposits = new ListTag();
            shop.deposits.forEach((id, d) -> {
                var e = new CompoundTag();
                e.putUUID("Animal", id); e.putUUID("Buyer", d.buyer()); e.putInt("Price", d.price()); e.putInt("Paid", d.paid()); e.putLong("Since", d.since());
                deposits.add(e);
            });
            c.put("Deposits", deposits);
            var refunds = new ListTag();
            shop.refunds.forEach((player, owed) -> { var e = new CompoundTag(); e.putUUID("Player", player); e.putInt("Owed", owed); refunds.add(e); });
            c.put("Refunds", refunds);
            list.add(c);
        }
        tag.put("Shops", list);
        var messages = new ListTag();
        notices.forEach((player, pending) -> {
            var e = new CompoundTag(); e.putUUID("Player", player);
            var contents = new ListTag();
            for (var message : pending) contents.add(net.minecraft.nbt.StringTag.valueOf(Component.Serializer.toJson(message, registries)));
            e.put("Contents", contents); messages.add(e);
        });
        tag.put("Notices", messages);
        var doors = new ListTag();
        openedDoors.forEach((dim, leaves) -> leaves.forEach(leaf -> {
            var e = new CompoundTag(); e.putString("Dim", dim); e.putLong("Pos", leaf.asLong()); doors.add(e);
        }));
        tag.put("Doors", doors);
        return tag;
    }
    public static ShopRegistry load(CompoundTag tag, HolderLookup.Provider registries) {
        var result = new ShopRegistry();
        for (var raw : tag.getList("Doors", 10)) {
            var e = (CompoundTag) raw;
            result.openedDoors.computeIfAbsent(e.getString("Dim"), key -> new LinkedHashSet<>()).add(BlockPos.of(e.getLong("Pos")));
        }
        for (var raw : tag.getList("Notices", 10)) {
            var e = (CompoundTag) raw;
            if (!e.hasUUID("Player")) continue;
            var contents = e.getList("Contents", 8);
            for (int i = 0; i < contents.size(); i++) {
                var message = Component.Serializer.fromJson(contents.getString(i), registries);
                if (message != null) result.notices.computeIfAbsent(e.getUUID("Player"), id -> new ArrayList<>()).add(message);
            }
        }
        for (var item : tag.getList("Shops", 10)) {
            var c = (CompoundTag) item;
            boolean converted = !c.hasUUID("Id") && c.hasUUID("Keeper");
            if (!converted && !c.hasUUID("Id")) continue;
            var shop = new Entry(c.getUUID(converted ? "Keeper" : "Id"), c.getString("Dim"), NbtUtils.readBlockPos(c, "Pos").orElse(BlockPos.ZERO));
            shop.legacy = converted || c.getBoolean("Legacy");
            shop.closed = c.getBoolean("Closed");
            if (converted) { shop.staff.add(shop.id); result.setDirty(); }
            readIds(c, "Staff", shop.staff);
            readIds(c, "Releases", shop.releases);
            for (var raw : c.getList("Orders", 10)) {
                var e = (CompoundTag) raw;
                if (e.hasUUID("Animal")) shop.orders.put(e.getUUID("Animal"), new Order(e.getString("Kind"),
                        e.hasUUID("Customer") ? e.getUUID("Customer") : null, e.getInt("Price"), e.hasUUID("Token") ? e.getUUID("Token") : null));
            }
            shop.nextRestock = c.getLong("NextRestock");
            shop.first = NbtUtils.readBlockPos(c, "First").orElse(null);
            shop.second = shop.first == null ? null : NbtUtils.readBlockPos(c, "Second").orElse(null);
            if (shop.second == null) shop.first = null;
            var protectedMin = NbtUtils.readBlockPos(c, "ProtectedMin");
            var protectedMax = NbtUtils.readBlockPos(c, "ProtectedMax");
            if (protectedMin.isPresent() && protectedMax.isPresent()) shop.protection = AABB.encapsulatingFullBlocks(protectedMin.get(), protectedMax.get());
            else if (shop.first != null && !shop.legacy) shop.protection = authoredProtection(shop);
            shop.counter = NbtUtils.readBlockPos(c, "Counter").orElse(null);
            shop.dock = NbtUtils.readBlockPos(c, "Dock").orElse(null);
            shop.trash = NbtUtils.readBlockPos(c, "Trash").orElse(null);
            for (var raw : c.getList("Deposits", 10)) {
                var e = (CompoundTag) raw;
                if (e.hasUUID("Animal") && e.hasUUID("Buyer") && e.getInt("Paid") > 0 && e.getInt("Price") >= e.getInt("Paid"))
                    shop.deposits.put(e.getUUID("Animal"), new Deposit(e.getUUID("Buyer"), e.getInt("Price"), e.getInt("Paid"), e.getLong("Since")));
            }
            for (var raw : c.getList("Refunds", 10)) {
                var e = (CompoundTag) raw;
                if (e.hasUUID("Player") && e.getInt("Owed") > 0) shop.refunds.put(e.getUUID("Player"), e.getInt("Owed"));
            }
            result.shops.put(shop.id, shop);
        }
        return result;
    }

    private static ListTag ids(Set<UUID> ids) {
        var list = new ListTag();
        for (var id : ids) list.add(NbtUtils.createUUID(id));
        return list;
    }

    private static void readIds(CompoundTag tag, String key, Set<UUID> ids) {
        for (var id : tag.getList(key, 11)) ids.add(NbtUtils.loadUUID(id));
    }
}
