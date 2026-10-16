package io.github.longshou.mcyukkuri.shop;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.family.FamilyLife;
import io.github.longshou.mcyukkuri.human.HumanMenu;
import io.github.longshou.mcyukkuri.human.HumanNpc;
import io.github.longshou.mcyukkuri.human.HumanWorld;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import java.util.*;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/** Keeper UI: the shop's studs, nursery and restock status, plus the matchmaking (omiai) service. Button packets never carry an animal id.
 *  Button ids: 0/1 previous/next own animal, 2 book the date, 3/4 next/previous stud.
 *  Data slots (shorts): 0 daytime, 1 stud count, 2 nursery count, 3 shop population, 4 capacity, 5 days to next restock, 6 subject count, 7 subject index+1,
 *  8 stud index+1, 9 booking state (0 ok, 1 night, 2 not enough emeralds, 3 no animal, 4 no stud, 5 cannot mate now, 6 pet has no nest of its own, 7 too crowded where it lives, 8 too hungry), 10 fee, 11 emeralds held,
 *  12-27 four stud rows of (species+1, grade | expired<<2 | pregnant-or-busy<<3, pedigree, selected) for the page holding the selected stud,
 *  28 subject species+1, 29 subject pedigree, 30 subject grade. */
public final class KeeperMenu extends AbstractContainerMenu {
    public static final int FEE = 6;
    private final HumanNpc target;
    private final Player viewer;
    private final List<UUID> mine = new ArrayList<>();
    private final ContainerData data = new SimpleContainerData(32);
    private int selected, stud;

    public KeeperMenu(int id, Inventory inventory, int entityId) {
        super(HumanWorld.KEEPER_MENU.get(), id); viewer = inventory.player;
        target = viewer.level().getEntity(entityId) instanceof HumanNpc n ? n : null;
        addDataSlots(data); rescan(); refresh();
    }
    public static void open(Player p, HumanNpc npc) {
        if (!(p instanceof ServerPlayer server) || !HumanMenu.valid(p, npc)) return;
        if (!npc.beginTrade(p)) { p.displayClientMessage(Component.translatable("human.mcyukkuri.busy"), true); return; }
        server.openMenu(new SimpleMenuProvider((id, inv, owner) -> new KeeperMenu(id, inv, npc.getId()), npc.getDisplayName()), buf -> buf.writeVarInt(npc.getId()));
    }
    public HumanNpc target() { return target; }
    public int value(int index) { return data.get(index); }
    @Override public boolean stillValid(Player p) { return HumanMenu.valid(p, target); }
    @Override public ItemStack quickMoveStack(Player p, int slot) { return ItemStack.EMPTY; }
    @Override public void removed(Player player) { super.removed(player); if (target != null) target.endTrade(player); }

    private static int count(Player p) { return p.getInventory().items.stream().filter(s -> s.is(Items.EMERALD)).mapToInt(ItemStack::getCount).sum(); }
    private static void debit(Player p, int amount) {
        for (ItemStack stack : p.getInventory().items) if (stack.is(Items.EMERALD)) { int used = Math.min(amount, stack.getCount()); stack.shrink(used); amount -= used; if (amount == 0) return; }
    }
    static void refund(ServerLevel level, UUID player, Vec3 at) {
        var stack = new ItemStack(Items.EMERALD, FEE); ServerPlayer sp = level.getServer().getPlayerList().getPlayer(player);
        if (sp != null) { if (!sp.getInventory().add(stack)) sp.drop(stack, false); }
        else { var item = new ItemEntity(level, at.x, at.y + .15, at.z, stack); item.setDefaultPickUpDelay(); level.addFreshEntity(item); }
    }
    private void put(int index, int value) { data.set(index, Math.clamp(value, -32768, 32767)); }

    private void rescan() {
        mine.clear(); if (!(viewer.level() instanceof ServerLevel level)) return;
        level.getEntitiesOfClass(YukkuriEntity.class, viewer.getBoundingBox().inflate(8), y -> y.isAlive() && y.pet().isPet() && y.pet().ownedBy(viewer.getUUID())
                && !y.pet().busy() && !y.isPassenger()).stream().sorted(Comparator.comparing(YukkuriEntity::getUUID)).limit(32).forEach(y -> mine.add(y.getUUID()));
        selected = mine.isEmpty() ? 0 : Math.min(selected, mine.size() - 1);
    }
    private YukkuriEntity subject() {
        if (mine.isEmpty() || !(viewer.level() instanceof ServerLevel level)) return null;
        return level.getEntity(mine.get(Math.min(selected, mine.size() - 1))) instanceof YukkuriEntity y && y.isAlive() && y.pet().ownedBy(viewer.getUUID()) ? y : null;
    }
    private List<YukkuriEntity> studs() { return target == null ? List.of() : target.animals("stud"); }
    private YukkuriEntity stud() { var all = studs(); return all.isEmpty() ? null : all.get(Math.min(stud, all.size() - 1)); }
    private boolean matchable(YukkuriEntity pet, YukkuriEntity s) {
        if (!(pet.level() instanceof ServerLevel level)) return false;
        var family = FamilyData.get(level);
        return pet.family().adult() && s.family().adult() && !pet.pet().busy() && !s.pet().busy() && !family.busyParent(pet.getUUID()) && !family.busyParent(s.getUUID())
                && FamilyLife.arrangedMatch(pet, s);
    }
    private int state(YukkuriEntity pet, YukkuriEntity s) {
        if (!viewer.level().isDay()) return 1;
        if (pet == null) return 3;
        if (s == null) return 4;
        if (count(viewer) < FEE) return 2;
        if (!matchable(pet, s)) return 5;
        if (pet.life().homeStand() == null) return 6;
        if (!(pet.level() instanceof ServerLevel level)) return 5;
        long now = level.getGameTime();
        if (!pet.getUUID().equals(refusalPet) || !s.getUUID().equals(refusalStud) || now - refusalAt >= 20) { // FamilyData.plan is costly: re-ask once a second
            String why = ShopKeeping.refusal(level, pet, s);
            refusalCode = why == null ? 0 : why.equals("crowded") ? 7 : why.startsWith("hungry") ? 8 : 5; refusalPet = pet.getUUID(); refusalStud = s.getUUID(); refusalAt = now;
        }
        return refusalCode;
    }
    private UUID refusalPet, refusalStud; private long refusalAt; private int refusalCode;

    private void refresh() {
        if (viewer.level().isClientSide || target == null || !(viewer.level() instanceof ServerLevel level)) return;
        for (int i = 0; i < 32; i++) data.set(i, 0);
        var studs = studs(); var pet = subject(); var s = stud();
        long left = Math.max(0, target.nextBreed() - level.getGameTime());
        put(0, level.isDay() ? 1 : 0); put(1, studs.size()); put(2, target.animals("shop_nursery").size()); put(3, ShopPens.population(level, target)); put(4, ShopMenu.CAPACITY);
        put(5, target.nextBreed() == 0 ? 3 : (int)((left + 23999) / 24000)); put(6, mine.size()); put(7, mine.isEmpty() ? 0 : selected + 1);
        put(8, s == null ? 0 : Math.min(stud, studs.size() - 1) + 1); put(9, state(pet, s)); put(10, FEE); put(11, count(viewer));
        int first = studs.isEmpty() ? 0 : Math.min(stud, studs.size() - 1) / 4 * 4;
        for (int i = 0; i < 4 && first + i < studs.size(); i++) {
            var y = studs.get(first + i); var b = y.pet(); int k = 12 + i * 4;
            put(k, y.getVariant().ordinal() + 1);
            put(k + 1, b.grade().id() | (b.expired() ? 4 : 0) | (b.busy() || FamilyData.get(level).busyParent(y.getUUID()) ? 8 : 0));
            put(k + 2, b.pedigree()); put(k + 3, y == s ? 1 : 0);
        }
        if (pet != null) { put(28, pet.getVariant().ordinal() + 1); put(29, pet.pet().pedigree()); put(30, pet.pet().grade().id()); }
    }
    @Override public void broadcastChanges() { refresh(); super.broadcastChanges(); }

    /** Nearest free spot beside the centre entity, so the moved animal does not land inside a block. */
    static Vec3 spotBeside(ServerLevel level, YukkuriEntity mover, Vec3 centre) {
        for (double[] d : new double[][] {{1.1, 0}, {-1.1, 0}, {0, 1.1}, {0, -1.1}}) {
            Vec3 c = centre.add(d[0], 0, d[1]);
            if (level.noCollision(mover, mover.getBoundingBox().move(c.subtract(mover.position())))) return c;
        }
        return null;
    }
    static void poof(ServerLevel level, YukkuriEntity y) { level.sendParticles(ParticleTypes.POOF, y.getX(), y.getY() + .3, y.getZ(), 8, .25, .15, .25, .02); }

    /** A free spot the given distance from the centre entity (the shy first meeting), else null. */
    private static Vec3 spotAt(ServerLevel level, YukkuriEntity mover, Vec3 centre, double distance) {
        double[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {.7, .7}, {-.7, .7}, {.7, -.7}, {-.7, -.7}};
        int from = mover.getRandom().nextInt(dirs.length);
        for (int i = 0; i < dirs.length; i++) {
            double[] d = dirs[(from + i) % dirs.length]; Vec3 c = centre.add(d[0] * distance, 0, d[1] * distance);
            if (level.noCollision(mover, mover.getBoundingBox().move(c.subtract(mover.position())))) return c;
        }
        return null;
    }
    private static final Set<String> REFUSALS = Set.of("pair", "busy", "kind", "factory", "shelter", "crowded", "hungry_mother", "hungry_father", "conceive_null");
    /** Player-facing text for a conception refusal code; codes themselves are never shown. */
    static Component failReason(String why) {
        return Component.translatable("shop.mcyukkuri.keeper.fail." + (why != null && REFUSALS.contains(why) ? why : "other"));
    }

    private boolean book(Player p) {
        var pet = subject(); var s = stud();
        if (state(pet, s) != 0 || !pet.pet().near(p) || pet.pet().busy() || !(p.level() instanceof ServerLevel level)) return false;
        Vec3 meet = spotAt(level, pet, s.position(), 2.8);
        if (meet == null) meet = spotBeside(level, pet, s.position());
        if (meet == null) return false;
        if (!pet.pet().lock(ShopDate.LOCK, p.getUUID())) return false;
        if (!s.pet().lock(ShopDate.LOCK, target.getUUID())) { pet.pet().unlock(ShopDate.LOCK); return false; }
        debit(p, FEE);
        ShopPerformance.schedule(level, new ShopDate(level, target.getUUID(), pet, s, p.getUUID(), pet.position(), meet));
        return true;
    }

    @Override public boolean clickMenuButton(Player p, int id) {
        if (p != viewer || p.level().isClientSide || !stillValid(p) || id < 0 || id > 4 || target == null) return false;
        boolean ok = true;
        if (id <= 1) { rescan(); if (!mine.isEmpty()) selected = (selected + (id == 1 ? 1 : mine.size() - 1)) % mine.size(); }
        else if (id == 2) {
            ok = book(p);
            // the precise reason the screen already shows, not the generic shop line
            int why = ok ? 0 : state(subject(), stud());
            p.displayClientMessage(Component.translatable(ok ? "human.mcyukkuri.success" : why != 0 ? "shop.mcyukkuri.keeper.state." + why : "shop.mcyukkuri.keeper.unable", FEE), true);
        }
        else { int n = studs().size(); if (n > 0) stud = (stud + (id == 3 ? 1 : n - 1)) % n; }
        broadcastChanges(); return ok;
    }
}
