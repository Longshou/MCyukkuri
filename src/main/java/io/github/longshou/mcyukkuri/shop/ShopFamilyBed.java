package io.github.longshou.mcyukkuri.shop;

import io.github.longshou.mcyukkuri.life.NestLayout;
import io.github.longshou.mcyukkuri.life.NestOccupancy;
import io.github.longshou.mcyukkuri.life.NestResting;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.Function;

enum ShopFamilyBed {
    CUSHION, WOVEN, ADJACENT;

    record Member(int stage, NestLayout.Place body) {}
    record Pad(int x, int z, NestResting.Bed bed, BiPredicate<Integer,NestLayout.Place> supports) {}

    static ShopFamilyBed choose(List<Member> litter, Function<ShopFamilyBed,List<Pad>> preview) {
        for (var layout : values()) {
            var pads = preview.apply(layout);
            if (pads.isEmpty()) continue;
            if (layout == ADJACENT ? separate(pads, litter) : shared(pads.getFirst(), layout == WOVEN, litter)) return layout;
        }
        return null;
    }

    private static boolean shared(Pad pad, boolean large, List<Member> litter) {
        var residents = new LinkedHashMap<UUID, Integer>();
        for (var member : litter) {
            var id = new UUID(0, residents.size());
            if (!NestOccupancy.accepts(large, residents, id, member.stage())) return false;
            residents.put(id, member.stage());
        }
        return NestLayout.fits(pad.bed(), litter.stream().map(Member::body).toList(), SurfaceApproach.ARRIVAL, pad.supports());
    }

    private static boolean separate(List<Pad> pads, List<Member> litter) {
        var places = new ArrayList<NestLayout.Place>();
        for (int i = 0; i < litter.size(); i++) {
            var body = litter.get(i).body();
            var pad = pads.get(i);
            if (!pad.supports().test(i, body)) return false;
            var centre = new NestLayout.Place(pad.x(), pad.z(), body.width(), body.reach());
            if (places.stream().anyMatch(other -> centre.gap(other) < 2 * SurfaceApproach.ARRIVAL)) return false;
            places.add(centre);
        }
        return true;
    }
}
