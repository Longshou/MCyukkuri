package io.github.longshou.mcyukkuri.social;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.settlement.SettlementData;
import io.github.longshou.mcyukkuri.status.YukkuriStatus;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;

/** Server queries only. Recognition, distance and willingness remain the caller's responsibility. */
public final class Bonds {
    /** BIO includes incident ancestry; CARE is the ledger's care parents and siblings.
     * MATE accepts either recorded direction; HOUSEHOLD means the same registered FAMILY group. */
    public enum Kin { BIO, CARE, MATE, HOUSEHOLD, GROUP }

    public static boolean kin(YukkuriEntity a, YukkuriEntity b, Set<Kin> kinds) {
        for (Kin kind : kinds) {
            boolean matches = switch (kind) {
                case BIO -> biological(a, b);
                case CARE -> careKin(a, b);
                case MATE -> eitherMate(a, b);
                case HOUSEHOLD -> sameHousehold(a, b);
                case GROUP -> sameGroupId(a, b);
            };
            if (matches) return true;
        }
        return false;
    }
    public static boolean biological(YukkuriEntity a, YukkuriEntity b) {
        return FamilyData.get((ServerLevel) a.level()).related(a.getUUID(), b.getUUID());
    }
    public static boolean careKin(YukkuriEntity a, YukkuriEntity b) {
        return FamilyData.get((ServerLevel) a.level()).socialKin(a.getUUID(), b.getUUID());
    }
    /** One direction deliberately differs from eitherMate and mutualMates. */
    public static boolean recordedMate(YukkuriEntity a, YukkuriEntity b) {
        return b.getUUID().equals(a.family().partner());
    }
    public static boolean eitherMate(YukkuriEntity a, YukkuriEntity b) {
        return recordedMate(a, b) || recordedMate(b, a);
    }
    public static boolean mutualMates(YukkuriEntity a, YukkuriEntity b) {
        return recordedMate(a, b) && recordedMate(b, a);
    }
    /**
     * The one severance operation every path ends in (driven out, mould exile, a search given up). The cut is mutual and permanent: the
     * ledger keeps the relation but marks it severed ({@link FamilyData#severed}), so the parent no longer counts the child and the child
     * no longer expects the parent; the child leaves the family household it was in, and a free wild child becomes a stray. {@code reason}
     * is only logged. {@code parent} may be null when she is unloaded or dead.
     */
    public static void sever(ServerLevel level, UUID parentId, YukkuriEntity parent, YukkuriEntity child, String reason) {
        var data = FamilyData.get(level);
        if (parent != null) data.dismissChild(parent, child.getUUID(), reason);
        else data.dismissChild(level, parentId, child.getUUID(), reason);
        var household = child.settlement().recordedGroup();
        if (household != null && household.kind == SettlementData.Kind.FAMILY) child.settlement().exile();
        var before = child.status().identity();
        if (!before.wild()) return;
        child.pet().markTownRoots();
        YukkuriStatus.identityChanged(child, before, "severed");
    }
    /** Recorded entity parents, without the ledger's sibling or incident-source expansion; a child she is driving out is no longer hers. */
    public static boolean parentOf(YukkuriEntity parent, YukkuriEntity child) {
        return (parent.getUUID().equals(child.family().mother()) || parent.getUUID().equals(child.family().father()))
                && FamilyData.get((ServerLevel)parent.level()).acknowledgesChild(parent.getUUID(),child.getUUID())
                && !parent.getUUID().equals(child.drivenOut().driver());
    }
    public static boolean directParentChild(YukkuriEntity a, YukkuriEntity b) {
        return parentOf(a, b) || parentOf(b, a);
    }
    /** FamilyLife.caresFor includes adulthood/childhood gates; CARE does not. */
    public static boolean currentCarePair(YukkuriEntity a, YukkuriEntity b) {
        return a.family().caresFor(b) || b.family().caresFor(a);
    }
    public static boolean careOrMate(YukkuriEntity a, YukkuriEntity b) {
        return careKin(a, b) || eitherMate(a, b);
    }
    public static boolean comfortFamily(YukkuriEntity a, YukkuriEntity b) {
        return mutualMates(a, b) || directParentChild(a, b);
    }
    public static boolean sameGroupId(YukkuriEntity a, YukkuriEntity b) {
        UUID group = a.settlement().groupId();
        return group != null && group.equals(b.settlement().groupId());
    }
    public static boolean sameHousehold(YukkuriEntity a, YukkuriEntity b) {
        var group = a.settlement().group();
        return group != null && group.kind == SettlementData.Kind.FAMILY && sameGroupId(a, b);
    }
    /** The remains' own mate, recorded on either side and read from the corpse when that body is absent. */
    public static boolean corpseMate(YukkuriEntity observer, UUID dead, UUID deadPartner) {
        return dead != null && (dead.equals(observer.family().partner()) || observer.getUUID().equals(deadPartner));
    }
    /** The corpse's partner copy is authoritative even when the other entity is absent. */
    public static boolean corpseKin(YukkuriEntity observer, UUID dead, UUID deadPartner) {
        var data = FamilyData.get((ServerLevel) observer.level());
        return corpseMate(observer, dead, deadPartner) || data.socialKin(observer.getUUID(), dead)
                || data.related(observer.getUUID(), dead);
    }
    public static boolean trustedPlayer(YukkuriEntity y, UUID player) {
        var p = y.life().personality();
        return p.trust(player) >= BondLevels.TRUST_FAMILIAR && p.fear(player) < BondLevels.AFFECTION_FEAR_LIMIT;
    }
    public static boolean trustedForLesson(YukkuriEntity y, UUID player) {
        var p = y.life().personality();
        return p.trust(player) >= BondLevels.TRUST_FAMILIAR && p.fear(player) < BondLevels.SOCIAL_FEAR_LIMIT;
    }
    /** Lifting never required positive trust; do not replace this with trustedPlayer. */
    public static boolean acceptsLift(YukkuriEntity y, UUID player) {
        return y.life().personality().fear(player) < BondLevels.OPERATION_FEAR_LIMIT;
    }
    private Bonds() {}
}
