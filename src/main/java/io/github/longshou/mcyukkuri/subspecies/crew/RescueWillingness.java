package io.github.longshou.mcyukkuri.subspecies.crew;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.social.Bonds;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;

/**
 * Whether a captain will pull a swimmer aboard: one friendliness-led draw per (parent, hull) emergency, remembered on the swimmer so
 * the physical retries of a lift keep an accepted choice and a declined one waits out its cooldown. The decision layer owns this
 * (the water-incident model replaces it); the transfer only receives the answer.
 */
public final class RescueWillingness {
    public record Key(UUID parent, UUID hat) {}
    public record Decision(boolean accepted, long until, long decidedAt) {}
    private static final int MAX_DECISIONS = 16, DECLINED_TICKS = 1200;

    /** True when the parent accepts (a fresh draw, or the one already made for this emergency). */
    public static boolean willing(YukkuriEntity parent, YukkuriEntity child, HatBoatEntity hat) {
        long now = parent.level().getGameTime();
        var decisions = child.family().crew().helpDecisions();
        decisions.entrySet().removeIf(entry -> !entry.getValue().accepted() && entry.getValue().until() <= now);
        var key = new Key(parent.getUUID(), hat.getUUID());
        var prior = decisions.get(key);
        if (prior != null) {
            if (prior.accepted()) return true;
            if (now < prior.until()) return false;
            decisions.remove(key);
        }
        // A saved temporary caregiver is familiar for a later, separate fall, but still takes this new willingness draw.
        boolean temporaryCare = hat.temporaryCaregiver(child);
        boolean familiar = Bonds.careKin(parent, child) || temporaryCare;
        if (!familiar || !parent.life().canRecognize(child)) {
            GameplayLog.limited(parent, "aquatic", "braid.ineligible." + child.getId(), 200,
                    "braid rescue not offered child={} hat={} kin={} temporaryCare={} recognized={}",
                    child.getUUID(), hat.getUUID(), Bonds.careKin(parent, child), temporaryCare, parent.life().canRecognize(child));
            return false;
        }
        float chance = parent.life().personality().helpProbability(true, true);
        float roll = parent.getRandom().nextFloat();
        boolean accepted = roll < chance;
        if (!decisions.containsKey(key) && decisions.size() >= MAX_DECISIONS)
            decisions.entrySet().stream().min(Comparator.comparingLong(entry -> entry.getValue().decidedAt())).ifPresent(entry -> decisions.remove(entry.getKey()));
        decisions.put(key, new Decision(accepted, accepted ? Long.MAX_VALUE : now + DECLINED_TICKS, now));
        GameplayLog.event(parent, "aquatic", "braid rescue decision child={} hat={} chance={} roll={} accepted={} declinedUntil={}",
                child.getUUID(), hat.getUUID(), chance, roll, accepted, accepted ? 0 : now + DECLINED_TICKS);
        return accepted;
    }

    /** The swimmer is safe: accepted choices no longer apply to a later fall. */
    public static void clearAccepted(YukkuriEntity child, String reason) {
        Map<Key, Decision> decisions = child.family().crew().helpDecisions();
        int before = decisions.size();
        decisions.entrySet().removeIf(entry -> entry.getValue().accepted());
        int cleared = before - decisions.size();
        if (cleared > 0) GameplayLog.limited(child, "aquatic", "braid.decision.clear." + reason, 200,
                "braid rescue accepted decisions cleared child={} count={} reason={}", child.getUUID(), cleared, reason);
    }

    private RescueWillingness() {}
}
