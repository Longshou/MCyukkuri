package io.github.longshou.mcyukkuri.social;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.PlacedDollEntity;
import java.util.UUID;

/** An ownerless placed doll two adults both came for: wanted by attachment, more when it already lies beside the body. */
record DollPrize(PlacedDollEntity doll) implements Contest.Prize {
    @Override public String kind() { return "doll"; }
    @Override public String where() { return doll.blockPosition().toString(); }
    @Override public boolean present(YukkuriEntity probe) { return doll.isAlive() && !doll.isRemoved(); }
    @Override public int need(YukkuriEntity body) { return body.life().personality().attachment(); }
    @Override public int importance(YukkuriEntity body) {
        double attach = SocialDisposition.ramp(body.life().personality().attachment(), 25, 85);
        int beside = body.distanceToSqr(doll) <= 9 ? 15 : 0;
        return (int) Math.round(35 + 40 * attach + beside);
    }
    @Override public String winLine() { return "contest_won_doll"; }
    @Override public void settle(YukkuriEntity body, boolean won, UUID rival) { body.keepsake().contested(doll, won, rival); }
}
