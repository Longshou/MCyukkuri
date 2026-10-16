package io.github.longshou.mcyukkuri.pet.exam;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.human.HumanMenu;
import io.github.longshou.mcyukkuri.human.HumanNpc;
import io.github.longshou.mcyukkuri.human.HumanWorld;
import io.github.longshou.mcyukkuri.pet.Ability;
import java.util.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;

/** Examiner UI. Subjects are fixed server-side (own animals within 8 blocks, plus unowned ones with trust &gt;= 10 for the simple exam); packets never carry an animal id.
 *  Button ids: 0 previous subject, 1 next subject, 2-6 simple / judgment / promotion / renewal / reissue (exam type = id + 1), 7 print the last exam log to chat.
 *  Data slots (shorts): 0 subject count, 1 index+1, 2 daytime, 3 examiner busy, 4 relation (1 own, 2 wild), 5 grade, 6 validity (0 none, 1 valid, 2 expired), 7 validity days left,
 *  8-11 abilities, 12 cooldown seconds, 13 stress, 14 hall mask (bits 0-3 = supports MANNERS..POISE, bit 4 = hall found), 15 emeralds carried, 16 species+1,
 *  17-20 last exam scores (-1 untested), 21 last outcome+1 (0 none), 22 last type, 23 last result grade, 24 has log notes, 25 unauthorized births, 26-30 refusal code per
 *  button 2-6 (index into ExamSession.REASONS, 0 = allowed), 31 pedigree. */
public final class ExamMenu extends AbstractContainerMenu {
    private final HumanNpc target;
    private final Player viewer;
    private final List<UUID> subjects = new ArrayList<>();
    private final ContainerData data = new SimpleContainerData(32);
    private int selected, rescanClock;

    public ExamMenu(int id, Inventory inventory, int entityId) {
        super(HumanWorld.EXAM_MENU.get(), id); viewer = inventory.player;
        target = viewer.level().getEntity(entityId) instanceof HumanNpc n ? n : null;
        addDataSlots(data); rescan(); refresh();
    }
    public static void open(Player p, HumanNpc npc) {
        if (!(p instanceof ServerPlayer server) || !HumanMenu.valid(p, npc)) return;
        if (!npc.beginTrade(p)) { p.displayClientMessage(Component.translatable("exam.mcyukkuri.refuse.busy"), true); return; }
        server.openMenu(new SimpleMenuProvider((id, inv, owner) -> new ExamMenu(id, inv, npc.getId()), npc.getDisplayName()), buf -> buf.writeVarInt(npc.getId()));
    }
    public HumanNpc target() { return target; }
    public int value(int index) { return data.get(index); }
    @Override public boolean stillValid(Player p) { return HumanMenu.valid(p, target); }
    @Override public ItemStack quickMoveStack(Player p, int slot) { return ItemStack.EMPTY; }
    @Override public void removed(Player player) { super.removed(player); if (target != null) target.endTrade(player); }

    private YukkuriEntity subject() {
        if (subjects.isEmpty() || !(viewer.level() instanceof ServerLevel level)) return null;
        return level.getEntity(subjects.get(Math.min(selected, subjects.size() - 1))) instanceof YukkuriEntity y && y.isAlive() ? y : null;
    }
    private void rescan() {
        subjects.clear(); if (!(viewer.level() instanceof ServerLevel level)) return;
        level.getEntitiesOfClass(YukkuriEntity.class, viewer.getBoundingBox().inflate(8), y -> y.isAlive() && (y.pet().owned(viewer)
                || io.github.longshou.mcyukkuri.event.WildEvents.canAdopt(y, viewer))).stream()
                .sorted(Comparator.comparing(YukkuriEntity::getUUID)).limit(32).forEach(y -> subjects.add(y.getUUID()));
        selected = subjects.isEmpty() ? 0 : Math.min(selected, subjects.size() - 1);
    }
    private void put(int index, int value) { data.set(index, Math.clamp(value, -32768, 32767)); }
    private void refresh() {
        if (viewer.level().isClientSide || target == null) return;
        if (++rescanClock >= 40) { rescanClock = 0; var keep = subjects.isEmpty() ? null : subjects.get(Math.min(selected, subjects.size() - 1)); rescan(); if (keep != null && subjects.contains(keep)) selected = subjects.indexOf(keep); }
        for (int i = 0; i < 32; i++) data.set(i, 0);
        put(0, subjects.size()); put(1, subjects.isEmpty() ? 0 : selected + 1); put(2, target.level().isDay() ? 1 : 0); put(3, ExamSession.active(target) ? 1 : 0);
        var hall = ExamHall.of(target); put(14, hall == null ? 0 : hall.supportMask() | 16); put(15, ExamSession.emeralds(viewer));
        var y = subject(); if (y == null) return;
        var b = y.pet();
        put(4, b.owned(viewer) ? 1 : 2); put(5, b.grade().id());
        put(6, b.grade().id() < 2 || b.expiresAt() < 0 ? 0 : b.expired() ? 2 : 1);
        put(7, b.expiresAt() < 0 ? 0 : (int)Math.min(9999, Math.max(0, (b.expiresAt() - y.level().getGameTime() + 23999) / 24000)));
        for (var a : Ability.values()) put(8 + a.ordinal(), b.ability(a));
        put(12, (b.cooldown() + 19) / 20); put(13, b.stress()); put(16, y.getVariant().ordinal() + 1); put(25, b.unauthorizedBirths()); put(31, b.pedigree());
        var history = b.history();
        for (int i = 0; i < 4; i++) put(17 + i, -1);
        if (!history.isEmpty()) {
            var last = history.get(history.size() - 1); put(21, last.outcome() + 1); put(22, last.type()); put(23, last.result().id());
            for (int i = 0; i < 4; i++) put(17 + i, last.scores()[i]);
        }
        put(24, y.getPersistentData().getString("ExamNotes").isEmpty() ? 0 : 1);
        for (int type = ExamSession.SIMPLE; type <= ExamSession.REISSUE; type++) { var why = ExamSession.refusal(target, viewer, y, type); put(26 + type - ExamSession.SIMPLE, why == null ? 0 : Math.max(0, ExamSession.REASONS.indexOf(why))); }
    }
    @Override public void broadcastChanges() { refresh(); super.broadcastChanges(); }
    @Override public boolean clickMenuButton(Player p, int id) {
        if (p != viewer || p.level().isClientSide || !stillValid(p) || id < 0 || id > 7 || target == null) return false;
        if (id < 2) { rescan(); if (!subjects.isEmpty()) selected = (selected + (id == 1 ? 1 : subjects.size() - 1)) % subjects.size(); broadcastChanges(); return true; }
        var y = subject(); if (y == null) return false;
        if (id == 7) {
            var notes = y.getPersistentData().getString("ExamNotes");
            if (notes.isEmpty()) return false;
            p.sendSystemMessage(Component.translatable("exam.mcyukkuri.log_title", y.getDisplayName()));
            for (var n : notes.split("\\|")) p.sendSystemMessage(Component.literal("  ").append(Component.translatable(n)));
            return true;
        }
        int type = id + 1; var why = ExamSession.refusal(target, p, y, type);
        if (why != null) { p.displayClientMessage(Component.translatable("exam.mcyukkuri.refuse." + why), true); broadcastChanges(); return false; }
        if (type == ExamSession.REISSUE) {
            var token = y.pet().reissueToken(); if (token.isEmpty()) return false;
            ExamSession.debit(p, ExamSession.fee(type)); ExamSession.giveTo(p, token);
            p.displayClientMessage(Component.translatable("exam.mcyukkuri.reissued", y.getDisplayName()), true); broadcastChanges(); return true;
        }
        var failed = ExamSession.start(target, p, y, type, true);
        if (failed != null) { p.displayClientMessage(Component.translatable("exam.mcyukkuri.refuse." + failed), true); broadcastChanges(); return false; }
        p.closeContainer(); // the owner watches the exam in the world
        return true;
    }
}
