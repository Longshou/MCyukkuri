package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.season.Season;
import io.github.longshou.mcyukkuri.season.Seasons;
import io.github.longshou.mcyukkuri.social.Temperament;
import net.minecraft.server.level.ServerLevel;

/**
 * The levy (公仓上缴): how the common store of a led community is filled (user 2026-10-18: "the commons is paid in by
 * proportion from every ordinary hunt"). Whoever brings food back from an outing, a family trip ({@link ForageTrip}
 * FATHER / MOTHER) or the communal hunt ({@link CommonHunt}), first carries a share into the common store and takes the
 * rest home to its own pile.
 *
 * <p>Lecture basis: komicolle 40720 No.11518859 (the group hunts together and shares out by the number of yukkuri) and
 * No.11518847 (fathers look for food, mothers raise the young). Neither names a proportion; the rates are project design:
 * <ul>
 * <li>Family trip: one fifth ({@link #TRIP_RATE}). The household gathered it with its own mouths and keeps most of it;
 *     a fifth is enough to build a reserve that can feed a short household without starving the payers (a 6-portion load
 *     pays 1).</li>
 * <li>Communal hunt: one third ({@link #HUNT_RATE}). The leader called it, the party walked out together and the
 *     commons is what it was called for (a 6-portion load pays 2).</li>
 * <li>A selfish leader (selfishness &ge; 65, self-control &lt; 50) asks a tenth more and the payers feel it
 *     ({@code heavy_levy}); a generous one (selfishness &lt; 35, attachment &ge; 60) a twentieth less.</li>
 * <li>Autumn adds a tenth (the winter reserve); a hunt in shortage or famine adds 15 % (relief comes from the commons).</li>
 * <li>Nothing is due when the common store already holds its target ({@link Rations#storeTarget}) and from a family
 *     trip of a household that is itself short ({@link Rations#isShort}): relief would only hand it back.</li>
 * </ul>
 * At most 60 %. A load of three or more portions always pays at least one when anything is due.
 */
public final class Levy {
    public static final float TRIP_RATE = .2f, HUNT_RATE = 1 / 3f, MAX_RATE = .6f;

    /** The share of a returning load owed to the common store, 0..{@link #MAX_RATE}. */
    public static float rate(YukkuriEntity payer, boolean hunt) {
        if (!(payer.level() instanceof ServerLevel level)) return 0;
        var group = payer.settlement().group();
        if (!CommunityRules.ruled(group) || SettlementData.get(level).activeLeader(group) == null || CommonStore.piles(level, group).isEmpty()) return 0;
        if (!hunt) {
            var home = payer.habitat().shelter();
            if (home != null && Rations.isShort(level, group, home)) return 0;
        }
        if (CommonStore.stock(level, group) >= Rations.storeTarget(level, group)) return 0;
        float rate = hunt ? HUNT_RATE : TRIP_RATE;
        var leader = CommunityRules.leader(level, group);
        if (leader != null) {
            var p = leader.life().personality();
            if (greedy(leader)) rate += .1f;
            else if (p.selfishness() < Temperament.SELFISHNESS_AT_35 && p.attachment() >= Temperament.ATTACHMENT_AT_60) rate -= .05f;
        }
        if (Seasons.on(level, group.center, Season.AUTUMN)) rate += .1f;
        if (hunt && group.commons.famine >= 1) rate += .15f;
        return Math.clamp(rate, 0f, MAX_RATE);
    }

    static boolean greedy(YukkuriEntity leader) {
        var p = leader.life().personality();
        return p.selfishness() >= Temperament.SELFISHNESS_AT_65 && p.selfControl() < Temperament.SELF_CONTROL_AT_50;
    }

    /** Portions of a load of {@code load} owed at {@code rate}. */
    public static int portions(int load, float rate) {
        if (rate <= 0 || load <= 0) return 0;
        int owed = Math.round(load * rate);
        if (owed == 0 && load >= 3) owed = 1;
        return Math.min(load, owed);
    }

    /** {@code portions} were really put into the common store. */
    static void paid(YukkuriEntity payer, int portions, boolean hunt) {
        if (portions <= 0 || !(payer.level() instanceof ServerLevel level)) return;
        var group = payer.settlement().group();
        if (group == null) return;
        group.commons.levyToday += portions;
        group.commons.levyTotal += portions;
        var leader = CommunityRules.leader(level, group);
        if (leader != null && leader != payer && greedy(leader)) payer.mind().add(1, "heavy_levy");
        io.github.longshou.mcyukkuri.status.StatusEvents.note(payer, "levy " + portions + (hunt ? " hunt" : " trip"));
        SettlementData.get(level).setDirty();
    }

    /** {@code portions} of an outing's load went to the household's own pile. */
    static void kept(YukkuriEntity payer, int portions) {
        var group = payer.settlement().group();
        if (portions > 0 && group != null && payer.level() instanceof ServerLevel level) {
            group.commons.keptToday += portions;
            SettlementData.get(level).setDirty();
        }
    }

    private Levy() {}
}
