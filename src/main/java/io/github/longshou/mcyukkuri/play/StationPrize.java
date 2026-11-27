package io.github.longshou.mcyukkuri.play;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.social.Contest;
import java.util.UUID;

/** A free play station two bored bodies both want: wanted as much as its claimant is bored. */
record StationPrize(PlayBlockEntity station) implements Contest.Prize {
    @Override public String kind() { return "toy"; }
    @Override public String where() { return station.kind() + "@" + station.getBlockPos(); }
    @Override public boolean present(YukkuriEntity probe) { return !station.isRemoved() && !station.occupiedByOther(probe); }
    @Override public int need(YukkuriEntity body) { return body.dailyLife().boredom(); }
    @Override public String winLine() { return "mine"; }
    @Override public void settle(YukkuriEntity body, boolean won, UUID rival) { body.playLife().contested(station, won, rival); }
}
