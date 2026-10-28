package io.github.longshou.mcyukkuri.badge;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.human.*;
import io.github.longshou.mcyukkuri.pet.PetPrivileges;
import io.github.longshou.mcyukkuri.pet.PetScenes;
import io.github.longshou.mcyukkuri.pet.PetSkill;
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
 * Button ids: 5 detach, 8 cycle subject, 10 attach, 11-13 predator orders; 20-25 start a concrete lesson, 30 cycle breeding
 * partner, 31 permit breeding with it for a day, 32 revoke the current permit. The lesson buttons follow {@link PetSkill#ordinal()}.
 *  Data 40-44: lesson state (0 idle, 1-6 running kind+1, 7 awaiting reaction), cooldown seconds, lessons today, precondition bitmask, ready flag.
 *  Data 45-53: breeding. 45 candidate count, 46 candidate index+1, 47/48 candidate entity id (low/high), 49 permit active, 50/51 permit partner entity id (low/high, 0 if not loaded),
 * 52 permit hours left, 53 permit already covers the candidate. 56 neglect level (0-100), 58 title (0 none, 1 eternal silver, 2 hidden gold), 57
 * owner-line status (0 none, 1 abandoned, 2 registry void badge). */
public final class BadgeMenu extends AbstractContainerMenu {
    /** Fixed wire values; reserved slots and ranges remain unchanged. */
    public static final class ButtonId {
        public static final int DETACH = 5;
        public static final int NEXT_SUBJECT = 8;
        public static final int ATTACH = 10;
        public static final int ORDER_PEACEFUL = 11;
        public static final int ORDER_HUNT = 12;
        public static final int ORDER_RETURN = 13;
        public static final int ORDER_END = 14;
        public static final int LESSON_WAIT = 20;
        public static final int LESSON_FOOD_BOWL = 21;
        public static final int LESSON_TOILET = 22;
        public static final int LESSON_BEDTIME = 23;
        public static final int LESSON_READING = 24;
        public static final int LESSON_ARITHMETIC = 25;
        public static final int LESSON_END = 26;
        public static final int NEXT_PARTNER = 30;
        public static final int PERMIT = 31;
        public static final int REVOKE = 32;
        public static final int COUNT = 33;
        public static final int ORDER_ORDINAL_OFFSET = 10;
        private ButtonId() {}
    }
    /** Fixed wire values; reserved slots and ranges remain unchanged. */
    public static final class Data {
        public static final int COUNT = 61;
        public static final int LEGACY_ENTITY_ID = 0;
        public static final int SUBJECT_COUNT = 1;
        public static final int SUBJECT_INDEX = 2;
        public static final int GRADE = 3;
        public static final int VALIDITY = 4;
        public static final int VALID_DAYS = 5;
        public static final int WORN = 6;
        public static final int SOURCE = 7;
        public static final int SERIAL = 8;
        public static final int SKILL_MASK = 9;
        public static final int INTELLIGENCE = 10;
        public static final int STRESS = 17;
        public static final int LAST_OUTCOME = 19;
        public static final int UNAUTHORIZED_BIRTHS = 24;
        public static final int OWNED = 25;
        public static final int NEAR = 26;
        public static final int PREDATOR = 27;
        public static final int OBEDIENT = 28;
        public static final int PREDATOR_ORDER = 29;
        public static final int BUSY = 30;
        public static final int LAST_TYPE = 31;
        public static final int LAST_GRADE = 32;
        public static final int ACCESSORY = 34;
        public static final int ENTITY_ID_LOW = 38;
        public static final int ENTITY_ID_HIGH = 39;
        public static final int LESSON_STATE = 40;
        public static final int COOLDOWN_SECONDS = 41;
        public static final int LESSONS_TODAY = 42;
        public static final int LESSON_MASK = 43;
        public static final int READY = 44;
        public static final int PARTNER_COUNT = 45;
        public static final int PARTNER_INDEX = 46;
        public static final int PARTNER_ID_LOW = 47;
        public static final int PARTNER_ID_HIGH = 48;
        public static final int PERMIT_ACTIVE = 49;
        public static final int PERMIT_ID_LOW = 50;
        public static final int PERMIT_ID_HIGH = 51;
        public static final int PERMIT_HOURS = 52;
        public static final int PARTNER_PERMITTED = 53;
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
        data.set(Data.VALIDITY, b.grade().id() < 2 || b.expiresAt() < 0 ? 0 : b.expired() ? 2 : 1);
        data.set(Data.VALID_DAYS, b.expiresAt() < 0 ? 0 : (int)Math.min(9999, Math.max(0, (b.expiresAt() - y.level().getGameTime() + 23999) / 24000)));
        data.set(Data.WORN, !b.worn() ? 0 : y.life().hasAccessory() ? 1 : 2); data.set(Data.SOURCE, b.source());
        data.set(Data.SERIAL, b.serial() == null ? 0 : (int)(b.serial().getLeastSignificantBits() & 0xffff));
        int skillMask = 0;
        for (var skill : PetSkill.values()) if (b.lessons().knows(skill)) skillMask |= 1 << skill.ordinal();
        data.set(Data.SKILL_MASK, skillMask); data.set(Data.INTELLIGENCE, y.life().personality().intelligence()); data.set(Data.STRESS, b.stress());
        var history = b.history();
        if (!history.isEmpty()) {
            var last = history.get(history.size() - 1); data.set(Data.LAST_OUTCOME, last.outcome() + 1); data.set(Data.LAST_TYPE, last.type()); data.set(Data.LAST_GRADE, last.result().id());
        }
        data.set(Data.UNAUTHORIZED_BIRTHS, b.unauthorizedBirths()); data.set(Data.OWNED, b.owned(viewer) ? 1 : 0); data.set(Data.NEAR, b.near(viewer) ? 1 : 0);
        data.set(Data.PREDATOR, y.profile().dangerToYukkuri() ? 1 : 0); data.set(Data.OBEDIENT, y.predator().obedient() ? 1 : 0); data.set(Data.PREDATOR_ORDER, y.predator().order().ordinal());
        data.set(Data.BUSY, b.busy() ? 1 : 0); data.set(Data.ACCESSORY, y.life().hasAccessory() ? 1 : 0);
        var lessons = b.lessons();
        data.set(Data.LESSON_STATE, lessons.running() ? 1 + lessons.kind().ordinal() : lessons.awaitingFeedback() ? 7 : 0);
        data.set(Data.COOLDOWN_SECONDS, (b.cooldown() + 19) / 20); data.set(Data.LESSONS_TODAY, lessons.today());
        if (b.owned(viewer) && b.near(viewer)) {
            if (maskClock-- <= 0) { maskClock = 20; mask = 0; for (var skill : PetSkill.values()) if (lessons.preconditionOk(viewer, skill)) mask |= 1 << skill.ordinal(); }
            data.set(Data.LESSON_MASK, mask); data.set(Data.READY, b.readyFor(viewer) && b.cooldown() == 0 ? 1 : 0);
        }
        data.set(Data.TITLE, b.title()); data.set(Data.NEGLECT, b.neglect()); data.set(Data.OWNER_STATE, b.voidBadge() ? 2 : b.abandoned() ? 1 : 0);
        var families = FamilyData.get((ServerLevel)y.level());
        data.set(Data.MOTHER_BADGE, families.badgeAcquired(y.family().mother()));
        data.set(Data.FATHER_BADGE, families.badgeAcquired(y.family().father()));
        breedingData(y);
    }
    private void scanPartners(YukkuriEntity y) {
        partners.clear();
        if (y != null && y.pet().owned(viewer) && viewer.level() instanceof ServerLevel level)
            level.getEntitiesOfClass(YukkuriEntity.class, viewer.getBoundingBox().inflate(8), o -> o != y && o.isAlive() && o.pet().isPet() && o.pet().ownedBy(viewer.getUUID())
                    && y.family().compatibleWith(o)).stream().sorted(Comparator.comparing(YukkuriEntity::getUUID)).limit(32).forEach(o -> partners.add(o.getUUID()));
        partner = partners.isEmpty() ? 0 : Math.min(partner, partners.size() - 1);
    }
    private YukkuriEntity candidate() {
        return partners.isEmpty() || !(viewer.level() instanceof ServerLevel level) ? null
                : level.getEntity(partners.get(Math.min(partner, partners.size() - 1))) instanceof YukkuriEntity o && o.isAlive() ? o : null;
    }
    private void breedingData(YukkuriEntity y) {
        if (scanClock-- <= 0) { scanClock = 20; scanPartners(y); }
        var b = y.pet(); var c = candidate();
        data.set(Data.PARTNER_COUNT, partners.size()); data.set(Data.PARTNER_INDEX, c == null ? 0 : partner + 1);
        if (c != null) { data.set(Data.PARTNER_ID_LOW, c.getId() & 0xffff); data.set(Data.PARTNER_ID_HIGH, c.getId() >>> 16);
                data.set(Data.PARTNER_PERMITTED, b.permitUntil(c.getUUID()) >= 0 ? 1 : 0); }
        var pp = b.permitPartner();
        if (pp != null) {
            data.set(Data.PERMIT_ACTIVE, 1); data.set(Data.PERMIT_HOURS, (int)Math.min(99, (b.permitUntil(pp) - y.level().getGameTime() + 999) / 1000));
            if (viewer.level() instanceof ServerLevel level && level.getEntity(pp) instanceof YukkuriEntity o) { data.set(Data.PERMIT_ID_LOW,
                    o.getId() & 0xffff); data.set(Data.PERMIT_ID_HIGH, o.getId() >>> 16); }
        }
    }
    @Override public void broadcastChanges() { refresh(); super.broadcastChanges(); }
    @Override public boolean clickMenuButton(Player p, int id) {
        boolean knownButton = id == ButtonId.DETACH || id == ButtonId.NEXT_SUBJECT || id == ButtonId.ATTACH
                || id >= ButtonId.ORDER_PEACEFUL && id < ButtonId.ORDER_END
                || id >= ButtonId.LESSON_WAIT && id < ButtonId.LESSON_END
                || id >= ButtonId.NEXT_PARTNER && id <= ButtonId.REVOKE;
        if (p != viewer || p.level().isClientSide || !stillValid(p) || !knownButton) return false;
        if (id == ButtonId.NEXT_SUBJECT) { if (!subjects.isEmpty()) selected = (selected + 1) % subjects.size(); maskClock = 0; scanClock = 0;
                partners.clear(); partner = 0; broadcastChanges(); return true; }
        var y = subject(); if (y == null || !y.pet().near(p)) return false;
        var b = y.pet();
        if (id >= ButtonId.NEXT_PARTNER) {
            boolean done = false;
            if (id == ButtonId.NEXT_PARTNER) { scanPartners(y); if (!partners.isEmpty()) { partner = (partner + 1) % partners.size(); done = true; } }
            else if (id == ButtonId.PERMIT) {
                scanPartners(y); var c = candidate();
                if (c != null && b.owned(p) && c.pet().near(p) && y.family().compatibleWith(c) && !PetPrivileges.mayPermitBreeding(y, c)) {
                    p.displayClientMessage(Component.translatable("badge.mcyukkuri.permit_need_badge"), true);
                    broadcastChanges(); return false; // both pets need a badge: no badge, no permit
                }
                if (c != null && b.owned(p) && c.pet().near(p) && y.family().compatibleWith(c)) {
                    long until = y.level().getGameTime() + 24000; b.permitBreeding(c.getUUID(), until); c.pet().permitBreeding(y.getUUID(), until); done = true;
                    PetScenes.permitGranted(y, c, p);
                }
            } else {
                var pp = b.permitPartner();
                if (pp != null && b.owned(p)) {
                    b.revokeBreeding(pp); YukkuriEntity other = null;
                    if (p.level() instanceof ServerLevel level && level.getEntity(pp) instanceof YukkuriEntity o) { o.pet().revokeBreeding(y.getUUID()); other = o; }
                    PetScenes.permitRevoked(y, other, p);
                    done = true;
                }
            }
            if (!done) p.displayClientMessage(Component.translatable("badge.mcyukkuri.unavailable"), true);
            broadcastChanges(); return done;
        }
        boolean ok = switch (id) {
            case ButtonId.DETACH -> b.detach(p);
            case ButtonId.ATTACH -> b.attach(p, p.getMainHandItem());
            case ButtonId.LESSON_WAIT, ButtonId.LESSON_FOOD_BOWL, ButtonId.LESSON_TOILET, ButtonId.LESSON_BEDTIME,
                    ButtonId.LESSON_READING, ButtonId.LESSON_ARITHMETIC -> b.owned(p) && b.lessons().start(p, PetSkill.values()[id - ButtonId.LESSON_WAIT]);
            case ButtonId.ORDER_PEACEFUL, ButtonId.ORDER_HUNT, ButtonId.ORDER_RETURN -> y.predator().command(p, PredatorLife.Order.values()[id - ButtonId.ORDER_ORDINAL_OFFSET]);
            default -> false;
        };
        if (ok && id >= ButtonId.LESSON_WAIT && id < ButtonId.LESSON_END) { p.closeContainer(); return true; } // the owner watches the lesson in the world
        if (!ok && !(id >= ButtonId.LESSON_WAIT && id < ButtonId.LESSON_END)) p.displayClientMessage(Component.translatable("badge.mcyukkuri.unavailable"), true);
        broadcastChanges(); return ok;
    }
}
