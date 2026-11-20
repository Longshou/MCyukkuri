package io.github.longshou.mcyukkuri.shop;

import io.github.longshou.mcyukkuri.life.NestLayout;
import io.github.longshou.mcyukkuri.life.NestOccupancy;
import io.github.longshou.mcyukkuri.life.NestResting;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

enum ShopFamilyBed {
    CUSHION, WOVEN, TOO_SMALL;

    record Member(int stage, NestLayout.Place body) {}

    static ShopFamilyBed choose(NestResting.Bed current, boolean woven, List<Member> litter) {
        if (fits(current, woven, litter)) return woven ? WOVEN : CUSHION;
        return !woven && fits(NestResting.LARGE, true, litter) ? WOVEN : TOO_SMALL;
    }

    private static boolean fits(NestResting.Bed bed, boolean large, List<Member> litter) {
        var residents = new LinkedHashMap<UUID, Integer>();
        for (var member : litter) {
            var id = new UUID(0, residents.size());
            if (!NestOccupancy.accepts(large, residents, id, member.stage())) return false;
            residents.put(id, member.stage());
        }
        return NestLayout.fits(bed, litter.stream().map(Member::body).toList(), SurfaceApproach.ARRIVAL);
    }
}
