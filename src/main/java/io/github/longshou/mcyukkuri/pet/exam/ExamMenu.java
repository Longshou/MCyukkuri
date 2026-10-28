package io.github.longshou.mcyukkuri.pet.exam;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.human.HumanMenu;
import io.github.longshou.mcyukkuri.human.HumanNpc;
import io.github.longshou.mcyukkuri.human.HumanWorld;
import io.github.longshou.mcyukkuri.pet.PetSkill;
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
import io.github.longshou.mcyukkuri.event.WildEvents;

/** Examiner UI. Subjects are fixed server-side (own animals within 8 blocks, plus unowned ones with trust &gt;= 10 for the simple exam); packets never carry an animal id.
 *  Button ids: 0 previous subject, 1 next subject, 2-6 simple / judgment / promotion / renewal / reissue (exam type = id + 1), 7 print the last exam log to chat.
 *  Data slots (shorts): 0 subject count, 1 index+1, 2 daytime, 3 examiner busy, 4 relation (1 own, 2 wild), 5 grade, 6 validity (0 none, 1 valid, 2 expired), 7 validity days left,
 *  8 learned-skill mask, 9 intelligence, 10 friendliness, 11 self-control, 12 cooldown seconds, 13 stress,
 *  14 hall task-area mask (bits 0-2) plus bit 4 when a hall was found, 15 emeralds carried, 16 species+1,
 *  17-20 reserved, 21 last outcome+1 (0 none), 22 last type, 23 last result grade, 24 has log notes, 25 unauthorized births,
 *  26-30 refusal code per button 2-6 (index into ExamSession.REASONS, 0 = allowed), 31 reserved. */
public final class ExamMenu extends AbstractContainerMenu {
    /** Fixed wire values; reserved slots and ranges remain unchanged. */
    public static final class ButtonId {
        public static final int PREVIOUS_SUBJECT = 0;
        public static final int NEXT_SUBJECT = 1;
        public static final int SIMPLE = 2;
        public static final int JUDGMENT = 3;
        public static final int PROMOTION = 4;
        public static final int RENEWAL = 5;
        public static final int REISSUE = 6;
        public static final int LOG = 7;
        public static final int COUNT = 8;
        private ButtonId() {}
    }
    /** Fixed wire values; reserved slots and ranges remain unchanged. */
    public static final class Data {
        public static final int COUNT = 32;
        public static final int SUBJECT_COUNT = 0;
        public static final int SUBJECT_INDEX = 1;
        public static final int DAYTIME = 2;
        public static final int BUSY = 3;
        public static final int RELATION = 4;
        public static final int GRADE = 5;
        public static final int VALIDITY = 6;
        public static final int VALID_DAYS = 7;
        public static final int SKILL_MASK = 8;
        public static final int INTELLIGENCE = 9;
        public static final int FRIENDLINESS = 10;
        public static final int SELF_CONTROL = 11;
        public static final int COOLDOWN_SECONDS = 12;
        public static final int STRESS = 13;
        public static final int HALL_MASK = 14;
        public static final int EMERALDS = 15;
        public static final int SPECIES = 16;
        public static final int RESERVED_TASK_0 = 17;
        public static final int RESERVED_TASK_1 = 18;
        public static final int RESERVED_TASK_2 = 19;
        public static final int RESERVED_TASK_3 = 20;
        public static final int LAST_OUTCOME = 21;
        public static final int LAST_TYPE = 22;
        public static final int LAST_GRADE = 23;
        public static final int HAS_NOTES = 24;
        public static final int UNAUTHORIZED_BIRTHS = 25;
        public static final int REFUSAL_BASE = 26;
        public static final int RESERVED_31 = 31;
        private Data() {}
    }
    public static final int HALL_FOUND = 16, HALL_TASK_AREAS = 7;
    private final HumanNpc target;
    private final Player viewer;
    private final List<UUID> subjects = new ArrayList<>();
    private final ContainerData data = new SimpleContainerData(Data.COUNT);
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
                || WildEvents.canAdopt(y, viewer))).stream()
                .sorted(Comparator.comparing(YukkuriEntity::getUUID)).limit(32).forEach(y -> subjects.add(y.getUUID()));
        selected = subjects.isEmpty() ? 0 : Math.min(selected, subjects.size() - 1);
    }
    private void put(int index, int value) { data.set(index, Math.clamp(value, -32768, 32767)); }
    private static int skills(YukkuriEntity y) {
        int mask = 0;
        for (var skill : PetSkill.values()) if (y.pet().lessons().knows(skill)) mask |= 1 << skill.ordinal();
        return mask;
    }
    private void refresh() {
        if (viewer.level().isClientSide || target == null) return;
        if (++rescanClock >= 40) { rescanClock = 0; var keep = subjects.isEmpty() ? null : subjects.get(Math.min(selected,
                subjects.size() - 1)); rescan(); if (keep != null && subjects.contains(keep)) selected = subjects.indexOf(keep); }
        for (int i = 0; i < Data.COUNT; i++) data.set(i, 0);
        put(Data.SUBJECT_COUNT, subjects.size()); put(Data.SUBJECT_INDEX, subjects.isEmpty() ? 0 : selected + 1); put(Data.DAYTIME,
                target.level().isDay() ? 1 : 0); put(Data.BUSY, ExamSession.active(target) ? 1 : 0);
        var hall = ExamHall.of(target); put(Data.HALL_MASK, hall == null ? 0 : hall.supportMask() | HALL_FOUND); put(Data.EMERALDS, ExamSession.emeralds(viewer));
        var y = subject(); if (y == null) return;
        var b = y.pet();
        put(Data.RELATION, b.owned(viewer) ? 1 : 2); put(Data.GRADE, b.grade().id());
        put(Data.VALIDITY, b.grade().id() < 2 || b.expiresAt() < 0 ? 0 : b.expired() ? 2 : 1);
        put(Data.VALID_DAYS, b.expiresAt() < 0 ? 0 : (int)Math.min(9999, Math.max(0, (b.expiresAt() - y.level().getGameTime() + 23999) / 24000)));
        var personality = y.life().personality();
        put(Data.SKILL_MASK, skills(y)); put(Data.INTELLIGENCE, personality.intelligence());
        put(Data.FRIENDLINESS, personality.friendliness()); put(Data.SELF_CONTROL, personality.selfControl());
        put(Data.COOLDOWN_SECONDS, (b.cooldown() + 19) / 20); put(Data.STRESS, b.stress()); put(Data.SPECIES,
                y.getVariant().ordinal() + 1); put(Data.UNAUTHORIZED_BIRTHS, b.unauthorizedBirths());
        var history = b.history();
        if (!history.isEmpty()) {
            var last = history.get(history.size() - 1); put(Data.LAST_OUTCOME, last.outcome() + 1); put(Data.LAST_TYPE, last.type()); put(Data.LAST_GRADE, last.result().id());
        }
        put(Data.HAS_NOTES, y.getPersistentData().getString("ExamNotes").isEmpty() ? 0 : 1);
        for (int type = ExamSession.SIMPLE; type <= ExamSession.REISSUE; type++) { var why = ExamSession.refusal(target, viewer, y,
                type); put(Data.REFUSAL_BASE + type - ExamSession.SIMPLE, why == null ? 0 : Math.max(0, ExamSession.REASONS.indexOf(why))); }
    }
    @Override public void broadcastChanges() { refresh(); super.broadcastChanges(); }
    @Override public boolean clickMenuButton(Player p, int id) {
        if (p != viewer || p.level().isClientSide || !stillValid(p) || id < ButtonId.PREVIOUS_SUBJECT || id > ButtonId.LOG || target == null) return false;
        if (id < ButtonId.SIMPLE) { rescan(); if (!subjects.isEmpty()) selected = (selected + (id == ButtonId.NEXT_SUBJECT ? 1 : subjects.size() - 1)) % subjects.size();
                broadcastChanges(); return true; }
        var y = subject(); if (y == null) return false;
        if (id == ButtonId.LOG) {
            var notes = y.getPersistentData().getString("ExamNotes");
            if (notes.isEmpty()) return false;
            p.sendSystemMessage(Component.translatable("exam.mcyukkuri.log_title", y.getDisplayName()));
            for (var n : notes.split("\\|")) p.sendSystemMessage(Component.literal("  ").append(Component.translatable(n)));
            return true;
        }
        int type = id + 1; var why = ExamSession.refusal(target, p, y, type);
        // The displayed preview is intentionally inert. A real click repeats every eligibility and fee check.
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
