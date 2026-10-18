package io.github.longshou.mcyukkuri.human;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;

/** Queries and ownership transitions for the existing NeoForgeData.HumanCommerce storage. */
public final class Custody {
    public enum OwnerKind { NONE, UNKNOWN, PLAYER, NPC }
    public enum ReleaseReason { ABANDON, DELIVERY_FAILED, EXAM_ROLLBACK }

    /** Stable Use IDs. UNKNOWN has no replacement ID: retain View.rawUse when preserving old data. */
    public enum Role {
        NONE(""), UNKNOWN(null), COMPANION("companion"), STOCK("stock"), BREEDER("breeder"),
        RESERVE_PARENT("reserve_parent"), NURSERY("nursery"), INPUT("input"), LABOR("labor"),
        RESERVE("reserve"), LOST("lost"), STUD("stud"), SHOP_NURSERY("shop_nursery"),
        TRAINEE("trainee"), EXAM_ACTOR("exam_actor");

        private final String id;

        Role(@Nullable String id) { this.id = id; }

        @Nullable public String id() { return id; }

        public static Role parse(String raw) {
            for (var role : values()) if (role.id != null && role.id.equals(raw)) return role;
            return UNKNOWN;
        }
    }

    /** Immutable values read at the call site, not a mutable tag or a live entity handle. */
    public record View(@Nullable UUID owner, OwnerKind ownerKind, Role role, String rawUse,
                       String pendingStaffRole, boolean hasPendingStaffRole,
                       String factoryStage, boolean hasFactoryStage) {
        public boolean ownedBy(UUID id) { return owner != null && owner.equals(id); }
        public boolean unassigned() { return owner == null && !hasPendingStaffRole; }
        public boolean wild() { return unassigned() && !hasFactoryStage; }
    }

    /** Compatibility access: preserve getCompound's live-tag/missing-key behavior without copying or filling keys. */
    public static CompoundTag data(YukkuriEntity animal) {
        return animal.getPersistentData().getCompound("HumanCommerce");
    }

    /** Compatibility write: copy the whole supplied tag, including unknown fields, exactly as before. */
    public static void set(YukkuriEntity animal, CompoundTag tag) {
        animal.getPersistentData().put("HumanCommerce", tag.copy());
    }

    public static View view(YukkuriEntity animal) { return read(data(animal)); }

    /** Reads a bare commerce tag, including saved rollback data. */
    public static View fromData(CompoundTag tag) { return read(tag); }

    public static void assign(YukkuriEntity animal, Player owner, Role role) {
        assign(animal, owner.getUUID(), OwnerKind.PLAYER, role);
    }

    public static void assign(YukkuriEntity animal, HumanNpc owner, Role role) {
        assign(animal, owner.getUUID(), OwnerKind.NPC, role);
    }

    public static void assign(YukkuriEntity animal, UUID owner, OwnerKind kind, Role role) {
        assignRaw(animal, owner, kind, java.util.Objects.requireNonNull(role.id(), "UNKNOWN requires the original Use"));
    }

    /** Unknown legacy/template Use values must pass through unchanged. Identity must come from positive evidence. */
    public static void assignRaw(YukkuriEntity animal, UUID owner, OwnerKind kind, String use) {
        var tag = data(animal).copy(); tag.remove("PendingRole");
        if (!use.equals("stock")) { tag.remove("ForSaleSince"); tag.remove("StaleCuts"); }
        tag.putUUID("Owner", owner); tag.putString("Use", use);
        if (kind == OwnerKind.PLAYER) tag.putString("OwnerKind", "player");
        else if (kind == OwnerKind.NPC) tag.putString("OwnerKind", "npc");
        else tag.remove("OwnerKind");
        set(animal, tag);
        if (use.equals("companion") || use.equals("stock") || use.equals("reserve_parent")) {
            var clean = data(animal).copy();
            for (String key : java.util.List.of("FactoryStage", "StageWork", "LastFactoryWork", "FactoryYoung", "HoldReason")) clean.remove(key);
            set(animal, clean);
        }
        animal.setPersistenceRequired(); animal.pet().newOwner(owner);
    }

    /** UUID-only compatibility callers preserve known identity only when retaining the same owner. */
    public static void assignLegacy(YukkuriEntity animal, UUID owner, String use) {
        var current = view(animal);
        assignRaw(animal, owner, current.ownedBy(owner) ? current.ownerKind() : OwnerKind.UNKNOWN, use);
    }

    /** Changes only Use in an existing batch; the caller commits its business fields at the original write point. */
    public static void role(CompoundTag tag, Role role) {
        tag.putString("Use", java.util.Objects.requireNonNull(role.id(), "UNKNOWN requires the original Use"));
    }

    /** NPC staff role, deliberately separate from the animal's Use. */
    public static void pendingStaffRole(CompoundTag tag, String staffRole) { tag.putString("PendingRole", staffRole); }

    /** Complete rollback, including unknown fields; no newOwner or care side effects. */
    public static void restore(YukkuriEntity animal, CompoundTag previous) { set(animal, previous); }

    /** Callers retain scene creation and exam eligibility guards; each reason keeps its original cleanup order. */
    public static void release(YukkuriEntity animal, ReleaseReason reason, @Nullable UUID formerOwner, @Nullable CompoundTag previous) {
        switch (reason) {
            case ABANDON -> {
                animal.pet().abandonedBy(java.util.Objects.requireNonNull(formerOwner));
                var tag = data(animal).copy(); tag.remove("Owner"); tag.remove("Use"); tag.remove("OwnerKind"); set(animal, tag);
                animal.life().releaseHome(); animal.social().stop(); animal.dailyLife().stop(); animal.setPersistenceRequired();
            }
            case DELIVERY_FAILED -> { set(animal, new CompoundTag()); animal.life().releaseHome(); }
            case EXAM_ROLLBACK -> restore(animal, java.util.Objects.requireNonNull(previous));
        }
    }

    /** Accepts a full saved entity, such as YukkuriWorldData.captiveSnapshot, never a bare commerce tag. */
    public static View fromSnapshot(CompoundTag snapshot) {
        return read(snapshot.getCompound("NeoForgeData").getCompound("HumanCommerce"));
    }

    private static View read(CompoundTag tag) {
        UUID owner = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
        OwnerKind kind = owner == null ? OwnerKind.NONE : switch (tag.getString("OwnerKind")) {
            case "player" -> OwnerKind.PLAYER;
            case "npc" -> OwnerKind.NPC;
            default -> OwnerKind.UNKNOWN;
        };
        String use = tag.getString("Use");
        return new View(owner, kind, Role.parse(use), use, tag.getString("PendingRole"), tag.contains("PendingRole"),
                tag.getString("FactoryStage"), tag.contains("FactoryStage"));
    }

    public static boolean ownedBy(YukkuriEntity animal, UUID id) { return view(animal).ownedBy(id); }

    public static boolean protectedAnimal(YukkuriEntity animal) {
        var custody = view(animal);
        if (custody.owner() != null || custody.hasPendingStaffRole() || animal.life().home() != null && !animal.life().naturalHome()) return true;
        if (animal.pet().abandoned() || animal.pet().voidBadge()) return false; // abandoned or registry-void: no keeper stands behind it
        for (var v : animal.life().personality().save().getList("Players", 10))
            if (((CompoundTag)v).getInt("Trust") >= 10) return true;
        return false;
    }

    public static boolean mayCapture(Player player, YukkuriEntity animal) {
        var custody = view(animal);
        return !custody.hasPendingStaffRole() && (custody.owner() == null || custody.owner().equals(player.getUUID())
                || custody.role() == Role.LOST);
    }

    private Custody() {}
}
