package io.github.longshou.mcyukkuri.interaction;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.human.Custody;
import io.github.longshou.mcyukkuri.mind.Naming;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import io.github.longshou.mcyukkuri.settlement.SettlementData;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

/** Read-only menu snapshot of the records the client cannot see. */
public record CareLineage(String stage, String roleKey, String groupKey, List<Entry> entries) {
    // Two lists of 48 names at up to 192 UTF-8 bytes each fit NeoForge's 32,600-byte opening buffer.
    private static final int MAX_PER_LIST = 48, MAX_NAME = 64;
    public static final String KEY = "care.mcyukkuri.";

    public enum Relation { MOTHER, FATHER, SOURCE, MATE, CHILDREN, SIBLINGS, LEADER }

    public record Entry(Relation relation, String name, YukkuriVariant variant, String stage, FamilyData.Status status, int omitted) {
        public Component label() {
            if (omitted > 0) return Component.translatable(KEY + "more", omitted);
            Component species = variant == null ? Component.translatable(KEY + "unknown") : ModEntities.forVariant(variant).getDescription();
            var label = Component.empty();
            if (!name.isEmpty()) label.append(name).append(" · ");
            label.append(species);
            if (!stage.isEmpty()) label.append(" · ").append(Component.translatable("stage.mcyukkuri." + stage));
            return status == null ? label : Component.translatable(KEY + "relative_status", label,
                    Component.translatable(KEY + "status." + id(status)));
        }

        private void write(FriendlyByteBuf buf) {
            buf.writeEnum(relation);
            buf.writeUtf(name, MAX_NAME);
            buf.writeVarInt(variant == null ? 0 : variant.ordinal() + 1);
            buf.writeUtf(stage);
            buf.writeVarInt(status == null ? 0 : status.ordinal() + 1);
            buf.writeVarInt(omitted);
        }

        private static Entry read(FriendlyByteBuf buf) {
            var relation = buf.readEnum(Relation.class);
            String name = buf.readUtf(MAX_NAME);
            int variant = buf.readVarInt();
            String stage = buf.readUtf();
            int status = buf.readVarInt();
            return new Entry(relation, name, variant == 0 ? null : YukkuriVariant.values()[variant - 1], stage,
                    status == 0 ? null : FamilyData.Status.values()[status - 1], buf.readVarInt());
        }
    }

    static CareLineage of(ServerLevel level, YukkuriEntity target) {
        var family = FamilyData.get(level);
        UUID subject = target.getUUID();
        var person = family.person(subject);
        var entries = new ArrayList<Entry>();
        if (person != null) {
            add(level, family, entries, Relation.MOTHER, person.mother);
            add(level, family, entries, Relation.FATHER, person.father);
            add(level, family, entries, Relation.SOURCE, person.incidentSource);
            switch (person.partnerState()) {
                case PARTNERED, WIDOWED -> add(level, family, entries, Relation.MATE, person.partner());
                default -> { }
            }
        }
        addMany(level, family, entries, Relation.CHILDREN, new TreeSet<>(family.children(subject)));
        if (person != null) {
            var siblings = new TreeSet<UUID>();
            if (person.mother != null) siblings.addAll(family.children(person.mother));
            if (person.father != null) siblings.addAll(family.children(person.father));
            siblings.remove(subject);
            addMany(level, family, entries, Relation.SIBLINGS, siblings);
        }
        var group = target.settlement().recordedGroup();
        var groupRole = group == null ? SettlementData.Role.FREE : group.role(subject);
        String groupKey = "";
        if (groupRole != SettlementData.Role.FREE && groupRole != SettlementData.Role.OUTCAST) {
            groupKey = KEY + "group." + id(group.kind);
            add(level, family, entries, Relation.LEADER, group.leader);
        }
        var custody = Custody.view(target).role();
        String roleKey = groupRole != SettlementData.Role.FREE ? "settlement.mcyukkuri.role." + id(groupRole)
                : custody == Custody.Role.NONE || custody == Custody.Role.UNKNOWN ? "" : KEY + "role." + custody.id();
        return new CareLineage(target.family().stageId(), roleKey, groupKey, List.copyOf(entries));
    }

    private static void addMany(ServerLevel level, FamilyData family, List<Entry> entries,
                                Relation relation, Collection<UUID> relatives) {
        int count = 0;
        for (UUID id : relatives) {
            if (count++ == MAX_PER_LIST) {
                entries.add(new Entry(relation, "", null, "", null, relatives.size() - MAX_PER_LIST));
                break;
            }
            add(level, family, entries, relation, id);
        }
    }

    private static void add(ServerLevel level, FamilyData family, List<Entry> entries, Relation relation, UUID id) {
        if (id == null) return;
        var person = family.person(id);
        var knownLevel = person == null || person.position == null ? level : level.getServer().getLevel(person.position.dimension());
        var loaded = level.getEntity(id);
        if (loaded == null && knownLevel != null && knownLevel != level) loaded = knownLevel.getEntity(id);
        var relative = loaded instanceof YukkuriEntity yukkuri ? yukkuri : null;
        String name = relative != null ? Naming.displayName(relative) : person == null ? "" : person.lastName();
        String stage = relative != null ? relative.family().stageId() : person == null ? "" : person.lastStage();
        if (name.length() > MAX_NAME) {
            int end = MAX_NAME - 1;
            if (Character.isHighSurrogate(name.charAt(end - 1))) end--;
            name = name.substring(0, end) + "…";
        }
        YukkuriVariant variant = relative == null ? null : relative.getVariant();
        if (relative == null && person != null) {
            for (var candidate : YukkuriVariant.values()) {
                if (candidate.assetId().equals(person.variant)) {
                    variant = candidate;
                    break;
                }
            }
        }
        var status = person == null ? null : person.status;
        if (relative != null) status = relative.isAlive() ? FamilyData.Status.ALIVE : FamilyData.Status.DEAD;
        entries.add(new Entry(relation, name, variant, stage, status, 0));
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(stage);
        buf.writeUtf(roleKey);
        buf.writeUtf(groupKey);
        buf.writeCollection(entries, (out, entry) -> entry.write(out));
    }

    public static CareLineage read(FriendlyByteBuf buf) {
        return new CareLineage(buf.readUtf(), buf.readUtf(), buf.readUtf(), List.copyOf(buf.readList(Entry::read)));
    }

    public static String id(Enum<?> value) { return value.name().toLowerCase(Locale.ROOT); }
}
