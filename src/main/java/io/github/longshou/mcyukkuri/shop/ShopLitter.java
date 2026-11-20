package io.github.longshou.mcyukkuri.shop;

import io.github.longshou.mcyukkuri.family.GrowthTiming;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

/** A breeder's pair has identities but no simulated bodies. Template siblings may bind on different ticks. */
record ShopLitter(UUID mother, UUID father) {
    record Members<T>(List<T> live, List<T> inTransit) {
        List<T> all() { return Stream.concat(live.stream(), inTransit.stream()).toList(); }
    }
    /** Basket location never affects membership. Only the live members can take their places now. */
    static <T> Map<ShopLitter, Members<T>> groups(List<T> live, List<T> inTransit, Function<T, ShopLitter> parents) {
        var groups = new LinkedHashMap<ShopLitter, Members<T>>();
        for (var body : live) groups.computeIfAbsent(parents.apply(body), k -> new Members<>(new ArrayList<>(), new ArrayList<>())).live.add(body);
        for (var body : inTransit) groups.computeIfAbsent(parents.apply(body), k -> new Members<>(new ArrayList<>(), new ArrayList<>())).inTransit.add(body);
        return groups;
    }
    static ShopLitter template(UUID shop, int group) {
        String prefix = shop + "/litter/" + group + "/";
        return new ShopLitter(parent(prefix + "mother"), parent(prefix + "father"));
    }

    private static UUID parent(String name) {
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    }

    static int size(long age, boolean thirdBaby) {
        return age < GrowthTiming.BABY_END && thirdBaby ? 3 : 2;
    }
}
