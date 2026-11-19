package io.github.longshou.mcyukkuri.shop;

import io.github.longshou.mcyukkuri.family.GrowthTiming;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** A breeder's pair has identities but no simulated bodies. Template siblings may bind on different ticks. */
record ShopLitter(UUID mother, UUID father) {
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
