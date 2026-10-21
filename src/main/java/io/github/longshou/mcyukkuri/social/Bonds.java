package io.github.longshou.mcyukkuri.social;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.settlement.SettlementData;
import java.util.ArrayList;
import java.util.List;
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
    /** Recorded entity parents, without the ledger's sibling or incident-source expansion. */
    public static boolean parentOf(YukkuriEntity parent, YukkuriEntity child) {
        return (parent.getUUID().equals(child.family().mother()) || parent.getUUID().equals(child.family().father()))
                && FamilyData.get((ServerLevel)parent.level()).acknowledgesChild(parent.getUUID(),child.getUUID());
    }
    public static boolean directParentChild(YukkuriEntity a, YukkuriEntity b) {
        return parentOf(a, b) || b.getUUID().equals(a.family().mother()) || b.getUUID().equals(a.family().father());
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
    /** The corpse's partner copy is authoritative even when the other entity is absent. */
    public static boolean corpseKin(YukkuriEntity observer, UUID dead, UUID deadPartner) {
        var data = FamilyData.get((ServerLevel) observer.level());
        return data.socialKin(observer.getUUID(), dead) || dead.equals(observer.family().partner())
                || observer.getUUID().equals(deadPartner) || data.related(observer.getUUID(), dead);
    }
    /** Preserve list order and duplicates: this is an event recipient list, not a kinship set. */
    public static List<UUID> exileRelatives(YukkuriEntity offender) {
        var data = FamilyData.get((ServerLevel) offender.level());
        var person = data.person(offender.getUUID());
        var relatives = new ArrayList<UUID>(data.children(offender.getUUID()));
        if (person != null) {
            if (person.mother != null) relatives.add(person.mother);
            if (person.father != null) relatives.add(person.father);
        }
        if (offender.family().partner() != null) relatives.add(offender.family().partner());
        return relatives;
    }
    public static boolean closeAffectionPeer(YukkuriEntity a, YukkuriEntity b) {
        var data = YukkuriWorldData.get((ServerLevel) a.level());
        int first = data.affinity(a.getUUID(), b.getUUID()), second = data.affinity(b.getUUID(), a.getUUID());
        return first >= BondLevels.AFFINITY_NEUTRAL && second >= BondLevels.AFFINITY_NEUTRAL
                && (first >= BondLevels.AFFINITY_CLOSE && second >= BondLevels.AFFINITY_CLOSE || careKin(a, b));
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
