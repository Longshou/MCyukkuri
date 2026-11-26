package io.github.longshou.mcyukkuri.badge;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.human.*;
import io.github.longshou.mcyukkuri.interaction.CareAction;
import io.github.longshou.mcyukkuri.interaction.InteractionDispatch;
import io.github.longshou.mcyukkuri.cognition.Ability;
import io.github.longshou.mcyukkuri.pet.exam.ExamSession;
import io.github.longshou.mcyukkuri.predator.PredatorLife;
import java.util.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;

/** Read-only pet profile. The server keeps a stable subject list; client button packets never supply an animal UUID.
 * Button ids: 5 detach, 8 cycle subject, 10 attach, 11-13 predator orders; 20-24 start a concrete lesson. The lesson buttons
 * follow {@link #LESSONS}.
 *  Data 40-44: lesson state (0 idle, ability ordinal+1 while running, {@link #AWAITING_FEEDBACK} after), cooldown seconds,
 *  lessons today, precondition bitmask, ready flag.
 *  Data 56-58: neglect level (0-100), owner-line status (0 none, 1 abandoned, 2 registry void badge), title (0 none,
 *  1 eternal silver, 2 hidden gold). */
public final class BadgeMenu extends AbstractContainerMenu {
    public static final List<Ability> LESSONS = List.of(Ability.WAIT, Ability.TOILET, Ability.FOOD_GUARD, Ability.GREETING, Ability.COUNTING);
    /** Fixed wire values; reserved slots and ranges remain unchanged. */
    public static final class ButtonId {
        public static final int DETACH = 5;
        public static final int NEXT_SUBJECT = 8;
        public static final int ATTACH = 10;
        public static final int ORDER_PEACEFUL = 11;
        public static final int ORDER_HUNT = 12;
        public static final int ORDER_RETURN = 13;
        public static final int ORDER_END = 14;
        public static final int LESSON_START = 20;
        public static final int LESSON_END = LESSON_START + LESSONS.size();
        public static final int COUNT = LESSON_END;
        public static final int ORDER_ORDINAL_OFFSET = 10;
        private ButtonId() {}
    }
    /** Fixed wire values; reserved slots and ranges remain unchanged. */
    public static final class Data {
        public static final int SKILL_POINTS_START = 61;
        public static final int COUNT = SKILL_POINTS_START + Ability.values().length;
        public static final int LEGACY_ENTITY_ID = 0;
        public static final int SUBJECT_COUNT = 1;
        public static final int SUBJECT_INDEX = 2;
        public static final int GRADE = 3;
        public static final int WORN = 6;
        public static final int SOURCE = 7;
        public static final int SERIAL = 8;
        public static final int INTELLIGENCE = 10;
        public static final int STRESS = 17;
        public static final int LAST_OUTCOME = 19;
        public static final int OWNED = 25;
        public static final int NEAR = 26;
        public static final int PREDATOR = 27;
        public static final int OBEDIENT = 28;
        public static final int PREDATOR_ORDER = 29;
        public static final int BUSY = 30;
        public static final int LAST_GRADE = 32;
        public static final int ACCESSORY = 34;
        public static final int ENTITY_ID_LOW = 38;
        public static final int ENTITY_ID_HIGH = 39;
        public static final int LESSON_STATE = 40;
        public static final int COOLDOWN_SECONDS = 41;
        public static final int LESSONS_TODAY = 42;
        public static final int LESSON_MASK = 43;
        public static final int READY = 44;
        public static final int NEGLECT = 56;
        public static final int OWNER_STATE = 57;
        public static final int TITLE = 58;
        public static final int MOTHER_BADGE = 59;
        public static final int FATHER_BADGE = 60;
        private Data() {}
    }
    private final List<UUID> subjects = new ArrayList<>();
    private final Player viewer;
    private int selected;
    private final ContainerData data = new SimpleContainerData(Data.COUNT);
    private int maskClock, mask;
    public static final int AWAITING_FEEDBACK = Ability.values().length + 1;
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
                Component.translatable("badge.mcyukkuri.title")), buf -> buf.writeVarInt(subject == null ? -1 : subject.getId()));
    }
    public int value(int index) { return data.get(index); }
    public int subjectEntityId() { return (data.get(Data.ENTITY_ID_LOW) & 0xffff) | (data.get(Data.ENTITY_ID_HIGH) << 16); }
    public YukkuriEntity subject() {
        if (!(viewer.level() instanceof ServerLevel level) || subjects.isEmpty()) return null;
        return level.getEntity(subjects.get(selected)) instanceof YukkuriEntity y ? y : null;
    }
    @Override public boolean stillValid(Player p) { return p.isAlive() && !p.isSpectator() && (subject() == null || subject().pet().near(p)); }
    @Override public ItemStack quickMoveStack(Player p, int slot) { return ItemStack.EMPTY; }
    private void refresh() {
        if (viewer.level().isClientSide) return;
        var y = subject(); data.set(Data.LEGACY_ENTITY_ID, y == null ? 0 : y.getId()); data.set(Data.SUBJECT_COUNT, subjects.size()); data.set(Data.SUBJECT_INDEX, selected + 1);
        for (int i = Data.GRADE; i < Data.COUNT; i++) data.set(i, 0);
        if (y == null) return;
        data.set(Data.ENTITY_ID_LOW, y.getId() & 0xffff); data.set(Data.ENTITY_ID_HIGH, y.getId() >>> 16);
        var b = y.pet(); data.set(Data.GRADE, b.grade().id());
        data.set(Data.WORN, !b.worn() ? 0 : y.life().hasAccessory() ? 1 : 2);
        data.set(Data.SOURCE, b.source() >= 3 ? ExamSession.CERTIFY : b.source());
        data.set(Data.SERIAL, b.serial() == null ? 0 : (int)(b.serial().getLeastSignificantBits() & 0xffff));
        for (var skill : Ability.values()) data.set(Data.SKILL_POINTS_START + skill.ordinal(), y.skills().points(skill));
        data.set(Data.INTELLIGENCE, y.life().personality().intelligence()); data.set(Data.STRESS, b.stress());
        var history = b.history();
        if (!history.isEmpty()) {
            var last = history.getLast(); data.set(Data.LAST_OUTCOME, last.outcome() + 1); data.set(Data.LAST_GRADE, last.result().id());
        }
        data.set(Data.OWNED, b.owned(viewer) ? 1 : 0); data.set(Data.NEAR, b.near(viewer) ? 1 : 0);
        data.set(Data.PREDATOR, y.profile().dangerToYukkuri() ? 1 : 0); data.set(Data.OBEDIENT, y.predator().obedient() ? 1 : 0); data.set(Data.PREDATOR_ORDER, y.predator().order().ordinal());
        data.set(Data.BUSY, b.busy() ? 1 : 0); data.set(Data.ACCESSORY, y.life().hasAccessory() ? 1 : 0);
        var lessons = b.lessons();
        data.set(Data.LESSON_STATE, lessons.running() ? 1 + lessons.kind().ordinal() : lessons.awaitingFeedback() ? AWAITING_FEEDBACK : 0);
        data.set(Data.COOLDOWN_SECONDS, (b.cooldown() + 19) / 20); data.set(Data.LESSONS_TODAY, lessons.today());
        if (b.owned(viewer) && b.near(viewer)) {
            if (maskClock-- <= 0) {
                maskClock = 20;
                mask = 0;
                for (var skill : LESSONS) if (lessons.preconditionOk(viewer, skill)) mask |= 1 << skill.ordinal();
            }
            data.set(Data.LESSON_MASK, mask); data.set(Data.READY, b.readyFor(viewer) && b.cooldown() == 0 ? 1 : 0);
        }
        data.set(Data.TITLE, b.title()); data.set(Data.NEGLECT, b.neglect());
        data.set(Data.OWNER_STATE, b.disqualified() ? 3 : b.voidBadge() ? 2 : b.abandoned() ? 1 : 0);
        var families = FamilyData.get((ServerLevel)y.level());
        data.set(Data.MOTHER_BADGE, families.badgeAcquired(y.family().mother()));
        data.set(Data.FATHER_BADGE, families.badgeAcquired(y.family().father()));
    }
    @Override public void broadcastChanges() { refresh(); super.broadcastChanges(); }
    @Override public boolean clickMenuButton(Player p, int id) {
        boolean knownButton = id == ButtonId.DETACH || id == ButtonId.NEXT_SUBJECT || id == ButtonId.ATTACH
                || id >= ButtonId.ORDER_PEACEFUL && id < ButtonId.ORDER_END
                || id >= ButtonId.LESSON_START && id < ButtonId.LESSON_END;
        if (p != viewer || p.level().isClientSide || !stillValid(p) || !knownButton) return false;
        if (id == ButtonId.NEXT_SUBJECT) { if (!subjects.isEmpty()) selected = (selected + 1) % subjects.size(); maskClock = 0;
                broadcastChanges(); return true; }
        var y = subject(); if (y == null || !y.pet().near(p)) return false;
        var b = y.pet();
        if (id >= ButtonId.LESSON_START && id < ButtonId.LESSON_END) {
            boolean started = b.owned(p) && b.lessons().start(p, LESSONS.get(id - ButtonId.LESSON_START));
            if (started) p.closeContainer();
            else broadcastChanges();
            return started;
        }
        boolean ok = switch (id) {
            case ButtonId.DETACH -> InteractionDispatch.perform(p, y, CareAction.REMOVE_BADGE);
            case ButtonId.ATTACH -> b.attach(p, p.getMainHandItem());
            case ButtonId.ORDER_PEACEFUL, ButtonId.ORDER_HUNT, ButtonId.ORDER_RETURN -> y.predator().command(p, PredatorLife.Order.values()[id - ButtonId.ORDER_ORDINAL_OFFSET]);
            default -> false;
        };
        if (!ok) p.displayClientMessage(Component.translatable("badge.mcyukkuri.unavailable"), true);
        broadcastChanges(); return ok;
    }
}
