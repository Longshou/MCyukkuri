package io.github.longshou.mcyukkuri.pet.exam;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.event.WildEvents;
import io.github.longshou.mcyukkuri.human.HumanNpc;
import io.github.longshou.mcyukkuri.human.HumanWorld;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;

/** The server owns the subject list, previews the verdict and rechecks each submitted service. */
public final class ExamMenu extends AbstractContainerMenu {
    public static final class ButtonId {
        public static final int PREVIOUS = 0, NEXT = 1, CERTIFY = 2, REISSUE = 3, LOG = 4, COUNT = 5;
        private ButtonId() {}
    }
    public static final class Data {
        public static final int SUBJECT_COUNT = 0, SUBJECT_INDEX = 1, RELATION = 2, GRADE = 3, TARGET = 4, NEED = 5,
                EMERALDS = 6, SPECIES = 7, LAST_OUTCOME = 8, LAST_GRADE = 9, CERTIFY_REFUSAL = 10, REISSUE_REFUSAL = 11,
                COUNT = 12;
        private Data() {}
    }

    private final HumanNpc target;
    private final Player viewer;
    private final List<UUID> subjects = new ArrayList<>();
    private final SimpleContainerData data = new SimpleContainerData(Data.COUNT);
    private int selected, rescanClock;

    public ExamMenu(int id, Inventory inventory, int entityId) {
        super(HumanWorld.EXAM_MENU.get(), id);
        viewer = inventory.player;
        target = viewer.level().getEntity(entityId) instanceof HumanNpc n ? n : null;
        addDataSlots(data);
        rescan();
        refresh();
    }

    public static void open(Player p, HumanNpc npc) {
        if (!(p instanceof ServerPlayer server) || !HumanNpc.canServe(p, npc)) return;
        if (!npc.beginTrade(p)) {
            p.displayClientMessage(Component.translatable("exam.mcyukkuri.refuse.busy"), true);
            return;
        }
        server.openMenu(new SimpleMenuProvider((id, inv, owner) -> new ExamMenu(id, inv, npc.getId()),
                npc.getDisplayName()), buf -> buf.writeVarInt(npc.getId()));
    }

    public HumanNpc target() { return target; }
    public int value(int index) { return data.get(index); }
    @Override public boolean stillValid(Player p) { return HumanNpc.canServe(p, target); }
    @Override public ItemStack quickMoveStack(Player p, int slot) { return ItemStack.EMPTY; }
    @Override public void removed(Player player) { super.removed(player); if (target != null) target.endTrade(player); }
    @Override public void broadcastChanges() { refresh(); super.broadcastChanges(); }

    private YukkuriEntity subject() {
        if (subjects.isEmpty() || !(viewer.level() instanceof ServerLevel level)) return null;
        return level.getEntity(subjects.get(selected)) instanceof YukkuriEntity y && y.isAlive() ? y : null;
    }

    private void rescan() {
        if (!(viewer.level() instanceof ServerLevel level)) return;
        UUID keep = subjects.isEmpty() ? null : subjects.get(selected);
        subjects.clear();
        level.getEntitiesOfClass(YukkuriEntity.class, viewer.getBoundingBox().inflate(8),
                y -> y.isAlive() && (y.pet().owned(viewer) || WildEvents.canAdopt(y, viewer))).stream()
                .sorted(Comparator.comparing(YukkuriEntity::getUUID)).limit(32).forEach(y -> subjects.add(y.getUUID()));
        int previous = subjects.indexOf(keep);
        selected = previous < 0 ? 0 : previous;
    }

    private void refresh() {
        if (viewer.level().isClientSide || target == null) return;
        if (++rescanClock >= 40) { rescanClock = 0; rescan(); }
        for (int i = 0; i < Data.COUNT; i++) data.set(i, 0);
        var y = subject();
        data.set(Data.EMERALDS, Math.min(Short.MAX_VALUE, ExamSession.emeralds(viewer)));
        if (y == null) return;
        var pet = y.pet();
        var next = pet.grade().higher();
        var need = ExamSession.firstMissing(next, y);
        data.set(Data.SUBJECT_COUNT, subjects.size());
        data.set(Data.SUBJECT_INDEX, selected + 1);
        data.set(Data.RELATION, pet.owned(viewer) ? 1 : 2);
        data.set(Data.GRADE, pet.grade().id());
        data.set(Data.TARGET, next.id());
        data.set(Data.NEED, need == null ? 0 : need.ordinal() + 1);
        data.set(Data.SPECIES, y.getVariant().ordinal() + 1);
        var history = pet.history();
        if (!history.isEmpty()) {
            var last = history.getLast();
            data.set(Data.LAST_OUTCOME, last.outcome() + 1);
            data.set(Data.LAST_GRADE, last.result().id());
        }
        data.set(Data.CERTIFY_REFUSAL, refusalCode(ExamSession.refusal(target, viewer, y, false)));
        data.set(Data.REISSUE_REFUSAL, refusalCode(ExamSession.refusal(target, viewer, y, true)));
    }

    private static int refusalCode(String reason) { return reason == null ? 0 : ExamSession.REASONS.indexOf(reason); }

    @Override public boolean clickMenuButton(Player p, int id) {
        if (p != viewer || p.level().isClientSide || !stillValid(p) || id < ButtonId.PREVIOUS || id > ButtonId.LOG) return false;
        if (id < ButtonId.CERTIFY) {
            rescan();
            if (!subjects.isEmpty()) selected = Math.floorMod(selected + (id == ButtonId.NEXT ? 1 : -1), subjects.size());
            broadcastChanges();
            return true;
        }
        var y = subject();
        if (y == null) return false;
        if (id == ButtonId.LOG) {
            var history = y.pet().history();
            if (history.isEmpty()) return false;
            var last = history.getLast();
            p.sendSystemMessage(Component.translatable("exam.mcyukkuri.log_title", y.getDisplayName()));
            p.sendSystemMessage(Component.translatable("exam.mcyukkuri.outcome." + last.outcome(), ExamSession.gradeName(last.result())));
            if (!last.need().isEmpty()) p.sendSystemMessage(Component.translatable("exam.mcyukkuri.need." + last.need()));
            return true;
        }
        if (id == ButtonId.CERTIFY) {
            var reason = ExamSession.start(target, p, y);
            if (reason != null) return refuse(p, reason);
            p.closeContainer();
            return true;
        }
        var reason = ExamSession.refusal(target, p, y, true);
        if (reason != null) return refuse(p, reason);
        var token = y.pet().reissueToken();
        ExamSession.debit(p, ExamSession.REISSUE_FEE);
        ExamSession.giveTo(p, token);
        GameplayLog.event(y, "badge", "reissued examiner={} owner={} serial={} fee={}",
                target.getUUID(), p.getUUID(), y.pet().serial(), ExamSession.REISSUE_FEE);
        p.displayClientMessage(Component.translatable("exam.mcyukkuri.reissued", y.getDisplayName()), true);
        broadcastChanges();
        return true;
    }

    private boolean refuse(Player player, String reason) {
        player.displayClientMessage(Component.translatable("exam.mcyukkuri.refuse." + reason), true);
        broadcastChanges();
        return false;
    }
}
