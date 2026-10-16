package io.github.longshou.mcyukkuri.badge;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.human.*;
import io.github.longshou.mcyukkuri.pet.Ability;
import java.util.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;

/** Read-only pet profile. The server keeps a stable subject list; client button packets never supply an animal UUID.
 *  Button ids: 5 detach, 8 cycle subject, 10 attach, 11-13 predator orders; 20-23 start a lesson (manners, academics, wits, poise), 30 cycle breeding partner, 31 permit breeding with it for a day, 32 revoke the current permit.
 *  Data 40-44: lesson state (0 idle, 1-4 running kind+1, 5 awaiting reaction), cooldown seconds, lessons today, precondition bitmask, ready flag.
 *  Data 45-53: breeding. 45 candidate count, 46 candidate index+1, 47/48 candidate entity id (low/high), 49 permit active, 50/51 permit partner entity id (low/high, 0 if not loaded),
 *  52 permit hours left, 53 permit already covers the candidate. */
public final class BadgeMenu extends AbstractContainerMenu {
    private final List<UUID> subjects = new ArrayList<>();
    private final Player viewer;
    private int selected;
    private final ContainerData data = new SimpleContainerData(56);
    private final List<UUID> partners = new ArrayList<>();
    private int partner, scanClock;
    private int maskClock, mask;
    public BadgeMenu(int id, Inventory inventory, int subjectId) {
        super(HumanWorld.BADGE_MENU.get(), id); viewer = inventory.player;
        if (!viewer.level().isClientSide) {
            if (viewer.level().getEntity(subjectId) instanceof YukkuriEntity y) subjects.add(y.getUUID());
            else viewer.level().getEntitiesOfClass(YukkuriEntity.class, viewer.getBoundingBox().inflate(8),
                    y -> y.pet().ownedBy(viewer.getUUID()) && y.isAlive()).stream()
                    .sorted(Comparator.comparing(YukkuriEntity::getUUID)).limit(32).forEach(y -> subjects.add(y.getUUID()));
        }
        addDataSlots(data); refresh();
    }
    public static void open(Player player, YukkuriEntity subject) {
        if (!(player instanceof ServerPlayer p) || subject != null && !subject.pet().near(p)) return;
        p.openMenu(new SimpleMenuProvider((id, inv, owner) -> new BadgeMenu(id, inv, subject == null ? -1 : subject.getId()),
                Component.translatable("badge.mcyukkuri.profile.title")), buf -> buf.writeVarInt(subject == null ? -1 : subject.getId()));
    }
    public int value(int index) { return data.get(index); }
    public int subjectEntityId() { return (data.get(38) & 0xffff) | (data.get(39) << 16); }
    public YukkuriEntity subject() {
        if (!(viewer.level() instanceof net.minecraft.server.level.ServerLevel level) || subjects.isEmpty()) return null;
        return level.getEntity(subjects.get(selected)) instanceof YukkuriEntity y ? y : null;
    }
    @Override public boolean stillValid(Player p) { return p.isAlive() && !p.isSpectator() && (subject() == null || subject().pet().near(p)); }
    @Override public ItemStack quickMoveStack(Player p, int slot) { return ItemStack.EMPTY; }
    private void refresh() {
        if (viewer.level().isClientSide) return;
        var y = subject(); data.set(0, y == null ? 0 : y.getId()); data.set(1, subjects.size()); data.set(2, selected + 1);
        for (int i = 3; i < 56; i++) data.set(i, 0);
        if (y == null) return;
        data.set(38, y.getId() & 0xffff); data.set(39, y.getId() >>> 16);
        var b = y.pet(); data.set(3, b.grade().id());
        data.set(4, b.grade().id() < 2 || b.expiresAt() < 0 ? 0 : b.expired() ? 2 : 1);
        data.set(5, b.expiresAt() < 0 ? 0 : (int)Math.min(9999, Math.max(0, (b.expiresAt() - y.level().getGameTime() + 23999) / 24000)));
        data.set(6, !b.worn() ? 0 : y.life().hasAccessory() ? 1 : 2); data.set(7, b.source());
        data.set(8, b.serial() == null ? 0 : (int)(b.serial().getLeastSignificantBits() & 0xffff));
        for (var a : Ability.values()) data.set(9 + a.ordinal(), b.ability(a));
        for (int i = 0; i < 3; i++) data.set(13 + i, b.routine(i));
        data.set(16, b.pedigree()); data.set(17, b.stress()); data.set(18, b.indulgence());
        var history = b.history();
        if (!history.isEmpty()) {
            var last = history.get(history.size() - 1); data.set(19, last.outcome() + 1); data.set(31, last.type()); data.set(32, last.result().id());
            for (int i = 0; i < 4; i++) data.set(20 + i, last.scores()[i]);
        }
        data.set(24, b.unauthorizedBirths()); data.set(25, b.owned(viewer) ? 1 : 0); data.set(26, b.near(viewer) ? 1 : 0);
        data.set(27, y.getVariant().isPredator() ? 1 : 0); data.set(28, y.predator().obedient() ? 1 : 0); data.set(29, y.predator().order().ordinal());
        data.set(30, b.busy() ? 1 : 0); data.set(34, y.life().hasAccessory() ? 1 : 0);
        var lessons = b.lessons();
        data.set(40, lessons.running() ? 1 + lessons.kind().ordinal() : lessons.awaitingFeedback() ? 5 : 0);
        data.set(41, (b.cooldown() + 19) / 20); data.set(42, lessons.today());
        if (b.owned(viewer) && b.near(viewer)) {
            if (maskClock-- <= 0) { maskClock = 20; mask = 0; for (var a : Ability.values()) if (lessons.preconditionOk(viewer, a)) mask |= 1 << a.ordinal(); }
            data.set(43, mask); data.set(44, b.readyFor(viewer) && b.cooldown() == 0 ? 1 : 0);
        }
        breedingData(y);
    }
    private void scanPartners(YukkuriEntity y) {
        partners.clear();
        if (y != null && y.pet().owned(viewer) && viewer.level() instanceof net.minecraft.server.level.ServerLevel level)
            level.getEntitiesOfClass(YukkuriEntity.class, viewer.getBoundingBox().inflate(8), o -> o != y && o.isAlive() && o.pet().isPet() && o.pet().ownedBy(viewer.getUUID())
                    && y.family().compatibleWith(o)).stream().sorted(Comparator.comparing(YukkuriEntity::getUUID)).limit(32).forEach(o -> partners.add(o.getUUID()));
        partner = partners.isEmpty() ? 0 : Math.min(partner, partners.size() - 1);
    }
    private YukkuriEntity candidate() {
        return partners.isEmpty() || !(viewer.level() instanceof net.minecraft.server.level.ServerLevel level) ? null
                : level.getEntity(partners.get(Math.min(partner, partners.size() - 1))) instanceof YukkuriEntity o && o.isAlive() ? o : null;
    }
    private void breedingData(YukkuriEntity y) {
        if (scanClock-- <= 0) { scanClock = 20; scanPartners(y); }
        var b = y.pet(); var c = candidate();
        data.set(45, partners.size()); data.set(46, c == null ? 0 : partner + 1);
        if (c != null) { data.set(47, c.getId() & 0xffff); data.set(48, c.getId() >>> 16); data.set(53, b.permitUntil(c.getUUID()) >= 0 ? 1 : 0); }
        var pp = b.permitPartner();
        if (pp != null) {
            data.set(49, 1); data.set(52, (int)Math.min(99, (b.permitUntil(pp) - y.level().getGameTime() + 999) / 1000));
            if (viewer.level() instanceof net.minecraft.server.level.ServerLevel level && level.getEntity(pp) instanceof YukkuriEntity o) { data.set(50, o.getId() & 0xffff); data.set(51, o.getId() >>> 16); }
        }
    }
    @Override public void broadcastChanges() { refresh(); super.broadcastChanges(); }
    @Override public boolean clickMenuButton(Player p, int id) {
        if (p != viewer || p.level().isClientSide || !stillValid(p) || id < 0 || id > 32 || id > 23 && id < 30 || id > 13 && id < 20) return false;
        if (id == 8) { if (!subjects.isEmpty()) selected = (selected + 1) % subjects.size(); maskClock = 0; scanClock = 0; partners.clear(); partner = 0; broadcastChanges(); return true; }
        var y = subject(); if (y == null || !y.pet().near(p)) return false;
        var b = y.pet();
        if (id >= 30) {
            boolean done = false;
            if (id == 30) { scanPartners(y); if (!partners.isEmpty()) { partner = (partner + 1) % partners.size(); done = true; } }
            else if (id == 31) {
                scanPartners(y); var c = candidate();
                if (c != null && b.owned(p) && c.pet().near(p) && y.family().compatibleWith(c)) {
                    long until = y.level().getGameTime() + 24000; b.permitBreeding(c.getUUID(), until); c.pet().permitBreeding(y.getUUID(), until); done = true;
                }
            } else {
                var pp = b.permitPartner();
                if (pp != null && b.owned(p)) {
                    b.revokeBreeding(pp);
                    if (p.level() instanceof net.minecraft.server.level.ServerLevel level && level.getEntity(pp) instanceof YukkuriEntity o) o.pet().revokeBreeding(y.getUUID());
                    done = true;
                }
            }
            if (!done) p.displayClientMessage(Component.translatable("badge.mcyukkuri.unavailable"), true);
            broadcastChanges(); return done;
        }
        boolean ok = switch (id) {
            case 5 -> b.detach(p);
            case 10 -> b.attach(p, p.getMainHandItem());
            case 20, 21, 22, 23 -> b.owned(p) && b.lessons().start(p, Ability.values()[id - 20]);
            case 11, 12, 13 -> y.predator().command(p, io.github.longshou.mcyukkuri.predator.PredatorLife.Order.values()[id - 10]);
            default -> false;
        };
        if (ok && id >= 20) { p.closeContainer(); return true; } // the owner watches the lesson in the world
        if (!ok && id < 20) p.displayClientMessage(Component.translatable("badge.mcyukkuri.unavailable"), true);
        broadcastChanges(); return ok;
    }
}
