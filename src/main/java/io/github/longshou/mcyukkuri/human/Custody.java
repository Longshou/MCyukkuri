package io.github.longshou.mcyukkuri.human;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/** Queries and ownership transitions for the existing NeoForgeData.HumanCommerce storage. */
public final class Custody {
    public enum OwnerKind { NONE, UNKNOWN, PLAYER, NPC }
    public enum ReleaseReason { ABANDON, DELIVERY_FAILED, EXAM_ROLLBACK, OWNER_DIED }
    private static final String PENDING_RELEASE = "PendingCustodyRelease";
    private static final java.util.List<String> ACTIVE_KEYS = java.util.List.of(
            "Owner", "OwnerKind", "Use", "PendingRole", "FactoryStage", "StageWork", "LastFactoryWork",
            "FactoryYoung", "HoldReason", "ShopCandidate", "RetiredParent", "ForSaleSince", "StaleCuts");
    /** Reentrancy guard only; all durable state lives in NBT/SavedData, never in this set. */
    private static final java.util.Set<YukkuriEntity> CLEANING = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

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

    /** Live-tag compatibility access; server reads backfill positive identity and normalize confirmed deaths, without world effects. */
    public static CompoundTag data(YukkuriEntity animal) {
        var tag = animal.getPersistentData().getCompound("HumanCommerce");
        if (animal.level() instanceof ServerLevel level && normalize(level, tag))
            animal.getPersistentData().put("HumanCommerce", tag);
        return tag;
    }

    /** Copy the full tag, preserving unknown fields, through death validation and pending cleanup. */
    public static void set(YukkuriEntity animal, CompoundTag tag) {
        var next = tag.copy();
        if (animal.level() instanceof ServerLevel level) normalize(level, next);
        var pending = data(animal).getCompound(PENDING_RELEASE).copy();
        if (!pending.isEmpty() && !cleanup(animal))
            throw new IllegalStateException("Pending custody cleanup requires an admitted live entity before replacement");
        // A business copy may have been taken before the current pending cleanup completed.
        if (!pending.isEmpty() && pending.equals(next.getCompound(PENDING_RELEASE))) next.remove(PENDING_RELEASE);
        store(animal, next);
        cleanup(animal);
    }

    private static void store(YukkuriEntity animal, CompoundTag tag) { animal.getPersistentData().put("HumanCommerce", tag.copy()); }

    /** Only metadata changes here: safe during admission and on authoritative captive NBT. */
    private static boolean normalize(ServerLevel level, CompoundTag tag) {
        if (!tag.hasUUID("Owner")) return false;
        var ledger = CustodyData.get(level); UUID owner = tag.getUUID("Owner");
        var kind = read(tag).ownerKind();
        ledger.remember(owner, kind);
        if (kind == OwnerKind.UNKNOWN) {
            kind = ledger.kind(owner);
            if (kind == OwnerKind.UNKNOWN) {
                if (level.getServer().getPlayerList().getPlayer(owner) != null) kind = OwnerKind.PLAYER;
                else for (var dimension : level.getServer().getAllLevels())
                    if (dimension.getEntity(owner) instanceof HumanNpc) { kind = OwnerKind.NPC; break; }
                ledger.remember(owner, kind);
            }
        }
        if (ledger.deadNpc(owner)) {
            for (var key : ACTIVE_KEYS) tag.remove(key);
            if (!pendingDeath(tag)) {
                var pending = new CompoundTag(); pending.putString("Reason", "owner_died"); pending.putUUID("FormerOwner", owner);
                tag.put(PENDING_RELEASE, pending);
            }
            return true;
        }
        String id = kind == OwnerKind.PLAYER ? "player" : kind == OwnerKind.NPC ? "npc" : "";
        if (!id.isEmpty() && !id.equals(tag.getString("OwnerKind"))) { tag.putString("OwnerKind", id); return true; }
        return false;
    }

    /** Mutates only the supplied full entity snapshot; its owning ledger must mark itself dirty. */
    public static boolean normalizeSnapshot(ServerLevel level, CompoundTag snapshot) {
        var forge = snapshot.getCompound("NeoForgeData"); var tag = forge.getCompound("HumanCommerce");
        if (!normalize(level, tag)) return false;
        forge.put("HumanCommerce", tag); snapshot.put("NeoForgeData", forge); return true;
    }

    private static boolean pendingDeath(CompoundTag tag) {
        var pending = tag.getCompound(PENDING_RELEASE);
        return pending.getString("Reason").equals("owner_died") && pending.hasUUID("FormerOwner");
    }

    /** Idempotent world effects, strictly after successful admission; queries never call this. */
    public static boolean cleanup(YukkuriEntity animal) {
        var tag = data(animal);
        if (!pendingDeath(tag)) return true;
        if (!(animal.level() instanceof ServerLevel level) || !animal.isAlive() || animal.isRemoved()
                || level.getEntity(animal.getUUID()) != animal || !CLEANING.add(animal)) return false;
        try {
            UUID former = tag.getCompound(PENDING_RELEASE).getUUID("FormerOwner");
            if (animal.getVehicle() instanceof FactoryTray tray) tray.unload();
            animal.life().releaseHome();
            animal.pet().ownerDied(former);
            animal.social().stop(); animal.dailyLife().stop(); animal.getNavigation().stop(); animal.setPersistenceRequired();
            var done = data(animal).copy(); done.remove(PENDING_RELEASE); store(animal, done);
            return true;
        } finally { CLEANING.remove(animal); }
    }

    public static void tick(YukkuriEntity animal) { if (animal.level() instanceof ServerLevel && animal.isAlive()) cleanup(animal); }

    public static void joining(EntityJoinLevelEvent event) {
        if (event.isCanceled() || !(event.getLevel() instanceof ServerLevel level)) return;
        if (event.getEntity() instanceof HumanNpc npc) CustodyData.get(level).remember(npc.getUUID(), OwnerKind.NPC);
        else if (event.getEntity() instanceof Player player) CustodyData.get(level).remember(player.getUUID(), OwnerKind.PLAYER);
        else if (event.getEntity() instanceof YukkuriEntity animal) data(animal);
    }

    /** A real NPC death, never unload/remove or a player's death. */
    public static void ownerDied(HumanNpc owner) {
        if (!(owner.level() instanceof ServerLevel level)) return;
        CustodyData.get(level).died(owner.getUUID(), level.getGameTime());
        var affected = new java.util.ArrayList<YukkuriEntity>();
        for (var dimension : level.getServer().getAllLevels()) for (var entity : dimension.getAllEntities())
            if (entity instanceof YukkuriEntity animal) {
                var tag = animal.getPersistentData().getCompound("HumanCommerce");
                if (tag.hasUUID("Owner") && tag.getUUID("Owner").equals(owner.getUUID()))
                    affected.add(animal);
            }
        for (var animal : affected) release(animal, ReleaseReason.OWNER_DIED, owner.getUUID(), null);
        io.github.longshou.mcyukkuri.life.YukkuriWorldData.get(level).normalizeCustody(level);
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
        if (!cleanup(animal)) throw new IllegalStateException("Pending custody cleanup before assignment");
        if (animal.level() instanceof ServerLevel level) {
            CustodyData.get(level).remember(owner, kind);
            if (CustodyData.get(level).deadNpc(owner)) return;
        }
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

    /** Complete rollback through death validation; only a confirmed death may require additional cleanup. */
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
            case OWNER_DIED -> {
                // Only the durable confirmed-death record can authorize this release.
                if (animal.level() instanceof ServerLevel level && CustodyData.get(level).deadNpc(formerOwner)) {
                    data(animal); cleanup(animal);
                }
            }
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
        if (custody.owner() != null || custody.hasPendingStaffRole()
                || animal.life().home() != null && !animal.life().naturalHome() && !pendingDeath(data(animal))) return true;
        if (animal.pet().abandoned() || animal.pet().voidBadge()) return false; // abandoned or registry-void: no keeper stands behind it
        for (var v : animal.life().personality().save().getList("Players", 10)) {
            var memory = (CompoundTag)v;
            if (memory.getInt("Trust") >= 10 && !(memory.hasUUID("Player") && animal.level() instanceof ServerLevel level
                    && CustodyData.get(level).deadNpc(memory.getUUID("Player")))) return true;
        }
        return false;
    }

    public static boolean mayCapture(Player player, YukkuriEntity animal) {
        var custody = view(animal);
        return !custody.hasPendingStaffRole() && (custody.owner() == null || custody.owner().equals(player.getUUID())
                || custody.role() == Role.LOST);
    }

    private Custody() {}
}
