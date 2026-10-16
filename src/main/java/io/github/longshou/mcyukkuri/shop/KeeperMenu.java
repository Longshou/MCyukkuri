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
    private static void refund(ServerLevel level, UUID player, Vec3 at) {
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
        String why = ShopKeeping.refusal(level, pet, s);
        return why == null ? 0 : why.equals("crowded") ? 7 : why.startsWith("hungry") ? 8 : 5;
    }

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
    private static Vec3 spotBeside(ServerLevel level, YukkuriEntity mover, Vec3 centre) {
        for (double[] d : new double[][] {{1.1, 0}, {-1.1, 0}, {0, 1.1}, {0, -1.1}}) {
            Vec3 c = centre.add(d[0], 0, d[1]);
            if (level.noCollision(mover, mover.getBoundingBox().move(c.subtract(mover.position())))) return c;
        }
        return null;
    }
    private static void poof(ServerLevel level, YukkuriEntity y) { level.sendParticles(ParticleTypes.POOF, y.getX(), y.getY() + .3, y.getZ(), 8, .25, .15, .25, .02); }

    private boolean book(Player p) {
        var pet = subject(); var s = stud();
        if (state(pet, s) != 0 || !pet.pet().near(p) || pet.pet().busy() || !(p.level() instanceof ServerLevel level)) return false;
        Vec3 spot = spotBeside(level, pet, s.position()); if (spot == null) return false;
        if (!pet.pet().lock("date", p.getUUID())) return false;
        if (!s.pet().lock("date", target.getUUID())) { pet.pet().unlock("date"); return false; }
        debit(p, FEE);
        UUID petId = pet.getUUID(), studId = s.getUUID(), buyer = p.getUUID(); Vec3 origin = pet.position(); boolean[] paid = {true};
        ShopPerformance.schedule(level, age -> {
            var a = level.getEntity(petId) instanceof YukkuriEntity y && y.isAlive() ? y : null;
            var b = level.getEntity(studId) instanceof YukkuriEntity y && y.isAlive() ? y : null;
            if (a == null || b == null) {
                if (a != null) a.pet().unlock("date"); if (b != null) b.pet().unlock("date");
                if (paid[0]) refund(level, buyer, origin);
                return true;
            }
            if (age == 1) {
                poof(level, a); a.getNavigation().stop(); a.moveTo(spot.x, spot.y, spot.z, a.getYRot(), 0); poof(level, a);
            }
            if (age % 10 == 0) { a.getLookControl().setLookAt(b, 30, 30); b.getLookControl().setLookAt(a, 30, 30); }
            if (age == 8) { a.playAction("nuzzle"); b.playAction("nuzzle"); }
            if (age == 45) { a.playAction("shiawase"); b.playAction("greet"); }
            if (age == 70) {
                var relations = YukkuriWorldData.get(level); relations.relate(petId, studId, 20); relations.relate(studId, petId, 20);
                // The date's emotes leave a command action running, which makes FamilyLife.ready() fail: end both before the checks.
                a.playAction("idle"); b.playAction("idle");
                String why = ShopKeeping.refusal(level, a, b);
                boolean ok = why == null && ShopKeeping.arrangedConceive(level, a, b);
                if (!ok && why == null) why = "conceive_null";
                if (!ok) io.github.longshou.mcyukkuri.MCYukkuri.LOGGER.warn("[keeper] matchmaking refused for {} x {}: {}", petId, studId, why);
                var sp = level.getServer().getPlayerList().getPlayer(buyer);
                if (ok) {
                    paid[0] = false; long until = level.getGameTime() + 24000;
                    a.pet().permitBreeding(studId, until); b.pet().permitBreeding(petId, until);
                    a.showReaction("happy", "expecting", 100);
                    if (sp != null) io.github.longshou.mcyukkuri.notebook.SurvivalProgress.award(sp, "shop_date");
                    if (sp != null) sp.displayClientMessage(Component.translatable("shop.mcyukkuri.keeper.matched", a.getDisplayName()), true);
                } else if (sp != null) sp.displayClientMessage(Component.translatable("shop.mcyukkuri.keeper.failed_reason", why), true);
                if (!ok) { paid[0] = false; refund(level, buyer, origin); }
            }
            if (age < 90) return false;
            poof(level, a);
            var owner = level.getPlayerByUUID(buyer); Vec3 home = origin;
            if (owner != null) { Vec3 near = spotBeside(level, a, owner.position()); if (near != null) home = near; }
            a.getNavigation().stop(); a.moveTo(home.x, home.y, home.z, a.getYRot(), 0); poof(level, a);
            a.pet().unlock("date"); b.pet().unlock("date");
            return true;
        });
        return true;
    }

    @Override public boolean clickMenuButton(Player p, int id) {
        if (p != viewer || p.level().isClientSide || !stillValid(p) || id < 0 || id > 4 || target == null) return false;
        boolean ok = true;
        if (id <= 1) { rescan(); if (!mine.isEmpty()) selected = (selected + (id == 1 ? 1 : mine.size() - 1)) % mine.size(); }
        else if (id == 2) { ok = book(p); p.displayClientMessage(Component.translatable(ok ? "human.mcyukkuri.success" : "human.mcyukkuri.unavailable"), true); }
        else { int n = studs().size(); if (n > 0) stud = (stud + (id == 3 ? 1 : n - 1)) % n; }
        broadcastChanges(); return ok;
    }
}
