package io.github.longshou.mcyukkuri.badge;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.human.*;
import java.util.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;

/** The server keeps a stable subject list; client button packets never supply an animal UUID. */
public final class BadgeMenu extends AbstractContainerMenu {
    private final List<UUID> subjects = new ArrayList<>();
    private final Player viewer;
    private final HumanNpc examiner;
    private int selected;
    private final ContainerData data = new SimpleContainerData(34);
    public BadgeMenu(int id, Inventory inventory, int examinerId, int subjectId) {
        super(HumanWorld.BADGE_MENU.get(), id); viewer = inventory.player;
        examiner = viewer.level().getEntity(examinerId) instanceof HumanNpc npc ? npc : null;
        if (!viewer.level().isClientSide) {
            if (viewer.level().getEntity(subjectId) instanceof YukkuriEntity y) subjects.add(y.getUUID());
            else viewer.level().getEntitiesOfClass(YukkuriEntity.class, viewer.getBoundingBox().inflate(8),
                    y -> HumanCommerce.ownedBy(y, viewer.getUUID()) && y.isAlive()).stream()
                    .sorted(Comparator.comparing(YukkuriEntity::getUUID)).limit(32).forEach(y -> subjects.add(y.getUUID()));
        }
        addDataSlots(data); refresh();
    }
    public static void open(Player player, HumanNpc examiner, YukkuriEntity subject) {
        if (!(player instanceof ServerPlayer p) || examiner != null && (examiner.job() != HumanNpc.Job.SHOPKEEPER || !HumanMenu.valid(p, examiner))
                || subject != null && !subject.badges().near(p)) return;
        p.openMenu(new SimpleMenuProvider((id, inv, owner) -> new BadgeMenu(id, inv, examiner == null ? -1 : examiner.getId(), subject == null ? -1 : subject.getId()),
                Component.translatable("badge.mcyukkuri.title")), buf -> { buf.writeVarInt(examiner == null ? -1 : examiner.getId()); buf.writeVarInt(subject == null ? -1 : subject.getId()); });
    }
    public int value(int index) { return data.get(index); }
    public int subjectEntityId() { return (data.get(32) & 0xffff) | (data.get(33) << 16); }
    private YukkuriEntity subject() {
        if (!(viewer.level() instanceof net.minecraft.server.level.ServerLevel level) || subjects.isEmpty()) return null;
        return level.getEntity(subjects.get(selected)) instanceof YukkuriEntity y ? y : null;
    }
    @Override public boolean stillValid(Player p) { return p.isAlive() && !p.isSpectator() && (examiner == null ? subject() != null && subject().badges().near(p) : HumanMenu.valid(p, examiner)); }
    @Override public ItemStack quickMoveStack(Player p, int slot) { return ItemStack.EMPTY; }
    private void refresh() {
        if (viewer.level().isClientSide) return;
        var y = subject(); data.set(0, y == null ? 0 : y.getId()); data.set(1, subjects.size()); data.set(2, selected + 1);
        data.set(3, examiner == null ? 0 : 1);
        for (int i = 4; i < 34; i++) data.set(i, 0);
        if (y == null) return;
        data.set(32,y.getId() & 0xffff); data.set(33,y.getId() >>> 16);
        var b = y.badges(); data.set(4, y.life().medal()); data.set(5, b.worn() ? 1 : 0); data.set(6, b.source());
        for (int i = 0; i < 3; i++) { data.set(7+i, b.skill(i)); data.set(10+i, b.routine(i)); }
        data.set(13, b.session()); data.set(14, b.stage()); data.set(15, b.elapsed()); data.set(16, b.cooldown()/20);
        data.set(17, b.result()); data.set(18, b.attempts()); data.set(19, b.owned(viewer) ? 1 : 0);
        data.set(20, y.life().hasAccessory() ? 1 : 0); data.set(21, y.life().personality().lessons());
        for (int i = 0; i < 5; i++) data.set(22+i, b.score(i));
        data.set(27, b.near(viewer) ? 1 : 0); data.set(28,b.wantedTier());
        data.set(29, y.getVariant().isPredator() ? 1 : 0);
        data.set(30, y.predator().obedient() ? 1 : 0); data.set(31, y.predator().order().ordinal());
    }
    @Override public void broadcastChanges() { refresh(); super.broadcastChanges(); }
    @Override public boolean clickMenuButton(Player p, int id) {
        if (p != viewer || p.level().isClientSide || !stillValid(p) || id < 0 || id > 13) return false;
        if (id == 8) { if (!subjects.isEmpty()) selected = (selected + 1) % subjects.size(); broadcastChanges(); return true; }
        var y = subject(); if (y == null || !y.badges().near(p)) return false;
        var b = y.badges();
        boolean ok = switch (id) {
            case 0, 1, 2 -> b.train(p, id);
            case 3, 4 -> b.exam(p, examiner, id - 2);
            case 5 -> b.detach(p);
            case 6 -> b.reissue(p, examiner);
            case 7 -> b.register(p);
            case 9 -> { b.cancel(p); yield b.owned(p); }
            case 10 -> b.attach(p, p.getMainHandItem());
            case 11, 12, 13 -> y.predator().command(p, io.github.longshou.mcyukkuri.predator.PredatorLife.Order.values()[id - 10]);
            default -> false;
        };
        if (!ok) p.displayClientMessage(Component.translatable("badge.mcyukkuri.unavailable"), true);
        broadcastChanges(); return ok;
    }
}
