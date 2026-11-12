package io.github.longshou.mcyukkuri.human;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.mind.EmotionEvent;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.status.YukkuriStatus;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Queries and ownership transitions for the existing NeoForgeData.HumanCommerce storage. */
public final class Custody {
    public enum OwnerKind { NONE, UNKNOWN, PLAYER, NPC }
    public enum ReleaseReason { ABANDON, LOST_CONTACT, DELIVERY_FAILED, EXAM_ROLLBACK, OWNER_DIED }
    private static final String PENDING_RELEASE = "PendingCustodyRelease";
    private static final List<String> ACTIVE_KEYS = List.of(
            "Owner", "OwnerKind", "Use", "PendingRole", "FactoryStage", "StageWork", "LastFactoryWork",
            "FactoryYoung", "HoldReason", "ShopCandidate", "RetiredParent", "ForSaleSince", "StaleCuts");
    private static final Set<String> FACTORY_STAFF_ROLES = Set.of("labor_worker", "manju_worker", "bag_worker");
    private static final Set<Role> FACTORY_ROLES = Set.of(Role.BREEDER, Role.RESERVE_PARENT, Role.NURSERY,
            Role.INPUT, Role.LABOR, Role.RESERVE);
    /** Reentrancy guard only; all durable state lives in NBT/SavedData, never in this set. */
    private static final Set<YukkuriEntity> CLEANING = Collections.newSetFromMap(new IdentityHashMap<>());

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
                       String pendingStaffRole, boolean hasPendingStaffRole, String factoryStage) {
        public boolean ownedBy(UUID id) { return owner != null && owner.equals(id); }
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

    private static void store(YukkuriEntity animal, CompoundTag tag) {
        var before = read(animal.getPersistentData().getCompound("HumanCommerce"));
        animal.getPersistentData().put("HumanCommerce", tag.copy());
        var after = read(tag);
        if (!Objects.equals(before.owner(), after.owner()) || !before.rawUse().equals(after.rawUse()))
            GameplayLog.event(animal, "custody", "assignment owner={} -> {} role={} -> {} ownerKind={}",
                    before.owner(), after.owner(), before.rawUse(), after.rawUse(), after.ownerKind());
    }

    /** Only metadata changes here: safe during admission and on authoritative captive NBT. */
    private static boolean normalize(ServerLevel level, CompoundTag tag) {
        if (!tag.contains("Owner") && !tag.contains("Use") && !tag.contains("PendingRole")) return false;
        var custody = read(tag);
        UUID owner = custody.owner();
        var ledger = owner == null ? null : CustodyData.get(level);
        if (owner != null && ledger.deadNpc(owner)) {
            queueRelease(tag, "owner_died", owner);
            return true;
        }
        if (!FactoryProduction.ENABLED && custody.ownerKind() != OwnerKind.PLAYER
                && (FACTORY_ROLES.contains(custody.role()) || FACTORY_STAFF_ROLES.contains(custody.pendingStaffRole()))) {
            queueRelease(tag, "factory_closed", owner);
            return true;
        }
        if (owner == null) return false;
        var kind = custody.ownerKind();
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

    private static void queueRelease(CompoundTag tag, String reason, @Nullable UUID owner) {
        for (var key : ACTIVE_KEYS) tag.remove(key);
        var pending = new CompoundTag();
        pending.putString("Reason", reason);
        if (owner != null) pending.putUUID("FormerOwner", owner);
        tag.put(PENDING_RELEASE, pending);
    }

    private static boolean releaseQueued(CompoundTag pending) {
        String reason = pending.getString("Reason");
        return reason.equals("factory_closed") || reason.equals("owner_died") && pending.hasUUID("FormerOwner");
    }

    /** Idempotent world effects, strictly after successful admission; queries never call this. */
    public static boolean cleanup(YukkuriEntity animal) {
        var tag = data(animal);
        var pending = tag.getCompound(PENDING_RELEASE);
        if (!releaseQueued(pending)) return true;
        if (!(animal.level() instanceof ServerLevel level) || !animal.isAlive() || animal.isRemoved()
                || level.getEntity(animal.getUUID()) != animal || !CLEANING.add(animal)) return false;
        try {
            UUID former = pending.hasUUID("FormerOwner") ? pending.getUUID("FormerOwner") : null;
            if (animal.getVehicle() instanceof FactoryTray tray) tray.unload();
            animal.life().releaseHome();
            if (former != null) animal.pet().ownerReleased(former);
            animal.social().stop(); animal.dailyLife().stop(); animal.getNavigation().stop(); animal.setPersistenceRequired();
            var done = data(animal).copy(); done.remove(PENDING_RELEASE); store(animal, done);
            String transition = pending.getString("Reason").equals("owner_died") ? "owner death" : "factory closure";
            GameplayLog.event(animal, "custody", "{} cleanup completed formerOwner={} identity={}", transition, former, animal.status().identity().id());
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
        var affected = new ArrayList<YukkuriEntity>();
        for (var dimension : level.getServer().getAllLevels()) for (var entity : dimension.getAllEntities())
            if (entity instanceof YukkuriEntity animal) {
                var tag = animal.getPersistentData().getCompound("HumanCommerce");
                if (tag.hasUUID("Owner") && tag.getUUID("Owner").equals(owner.getUUID()))
                    affected.add(animal);
            }
        for (var animal : affected) {
            release(animal, ReleaseReason.OWNER_DIED, owner.getUUID(), null);
            mourn(animal, owner);
        }
        YukkuriWorldData.get(level).normalizeCustody(level);
        GameplayLog.event(owner, "custody", "owner death recorded loadedDependants={}", affected.size());
    }

    /**
     * A released animal that sees its keeper's body within 10 blocks grieves on the spot (moment only, no grave visit): the kin-death
     * unease scaled by its own attachment (provisional amount, the user has not confirmed it).
     */
    private static void mourn(YukkuriEntity animal, HumanNpc owner) {
        if (animal.level() != owner.level() || !animal.isAlive() || animal.distanceToSqr(owner) > 100 || !animal.hasLineOfSight(owner)) return;
        float bond = animal.life().personality().attachment() / 100f;
        animal.mind().add(EmotionEvent.FAMILY_DEATH.delta() * bond, "owner_died");
        animal.mind().perform("bereaved", 100);
        animal.showReaction("teary", "kin_dying", 100);
        GameplayLog.event(animal, "custody", "keeper grieved owner={} attachment={} unease={}", owner.getUUID(),
                animal.life().personality().attachment(), animal.mind().unease());
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
        assignRaw(animal, owner, kind, Objects.requireNonNull(role.id(), "UNKNOWN requires the original Use"));
    }

    /** Unknown legacy/template Use values must pass through unchanged. Identity must come from positive evidence. */
    public static void assignRaw(YukkuriEntity animal, UUID owner, OwnerKind kind, String use) {
        if (!cleanup(animal)) throw new IllegalStateException("Pending custody cleanup before assignment");
        if (animal.level() instanceof ServerLevel level) {
            CustodyData.get(level).remember(owner, kind);
            if (CustodyData.get(level).deadNpc(owner)) {
                GameplayLog.limited(animal, "custody", "assign.dead_owner", 200,
                        "assignment refused owner={} role={} reason=confirmed_dead_owner", owner, use);
                return;
            }
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
            for (String key : List.of("FactoryStage", "StageWork", "LastFactoryWork", "FactoryYoung", "HoldReason")) clean.remove(key);
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
        tag.putString("Use", Objects.requireNonNull(role.id(), "UNKNOWN requires the original Use"));
    }

    /** NPC staff role, deliberately separate from the animal's Use. */
    public static void pendingStaffRole(CompoundTag tag, String staffRole) { tag.putString("PendingRole", staffRole); }

    /** Complete rollback through death validation; only a confirmed death may require additional cleanup. */
    public static void restore(YukkuriEntity animal, CompoundTag previous) { set(animal, previous); }

    /** Callers retain scene creation and exam eligibility guards; each reason keeps its original cleanup order. */
    public static void release(YukkuriEntity animal, ReleaseReason reason, @Nullable UUID formerOwner, @Nullable CompoundTag previous) {
        switch (reason) {
            case ABANDON, LOST_CONTACT -> {
                var before = animal.status().identity();
                if (reason == ReleaseReason.ABANDON) animal.pet().abandonedBy(Objects.requireNonNull(formerOwner));
                animal.settlement().exile();
                var tag = data(animal).copy();
                for (var key : ACTIVE_KEYS) tag.remove(key);
                set(animal, tag);
                if (reason == ReleaseReason.LOST_CONTACT) animal.pet().lostContact();
                animal.habitat().release();
                animal.life().releaseHome(); animal.social().stop(); animal.dailyLife().stop(); animal.setPersistenceRequired();
                YukkuriStatus.identityChanged(animal, before, reason == ReleaseReason.ABANDON ? "abandon" : "lost_contact");
            }
            case DELIVERY_FAILED -> { set(animal, new CompoundTag()); animal.life().releaseHome(); }
            case EXAM_ROLLBACK -> restore(animal, Objects.requireNonNull(previous));
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
                tag.getString("FactoryStage"));
    }

    public static boolean ownedBy(YukkuriEntity animal, UUID id) { return view(animal).ownedBy(id); }

    public static boolean protectedAnimal(YukkuriEntity animal) {
        var tag = data(animal);
        var custody = read(tag);
        if (custody.owner() != null || custody.hasPendingStaffRole()
                || animal.life().home() != null && !animal.life().naturalHome() && !releaseQueued(tag.getCompound(PENDING_RELEASE))) return true;
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
