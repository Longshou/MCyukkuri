package io.github.longshou.mcyukkuri.client;

import org.joml.Vector3f;

/** Headless cache lifecycle and deformation-input checks; run by remote CI, without a game. */
public final class SkinningCacheTest {
    public static void main(String[] args) {
        var key = new SkinningCache.Key(1, 2, 3, 0, 1, .25f, 2, .1f, .2f, .3f);
        var keys = new SkinningCache.Key[] {
                key, key, null, null,
                new SkinningCache.Key(2, 2, 3, 0, 1, .25f, 2, .1f, .2f, .3f),
                new SkinningCache.Key(1, 3, 3, 0, 1, .25f, 2, .1f, .2f, .3f),
                new SkinningCache.Key(1, 2, 4, 0, 1, .25f, 2, .1f, .2f, .3f),
                new SkinningCache.Key(1, 2, 3, 1, 1, .25f, 2, .1f, .2f, .3f),
                new SkinningCache.Key(1, 2, 3, 0, 0, .25f, 2, .1f, .2f, .3f),
                new SkinningCache.Key(1, 2, 3, 0, 1, .50f, 2, .1f, .2f, .3f),
                new SkinningCache.Key(1, 2, 3, 0, 1, .25f, 3, .1f, .2f, .3f),
                new SkinningCache.Key(1, 2, 3, 0, 1, .25f, 2, .4f, .2f, .3f),
                new SkinningCache.Key(1, 2, 3, 0, 1, .25f, 2, .1f, .4f, .3f),
                new SkinningCache.Key(1, 2, 3, 0, 1, .25f, 2, .1f, .2f, .4f)
        };
        var cache = new SkinningCache(keys.length, i -> keys[i]);
        var point = new Vector3f(4, 5, 6);
        var result = new Vector3f();
        cache.write(0, point);
        check(!cache.read(0, result), "contact solver must bypass inactive cache");
        cache.begin();
        check(!cache.read(0, result), "inactive write leaked into a draw");
        cache.write(0, point);
        point.zero();
        check(cache.read(1, result) && result.equals(new Vector3f(4, 5, 6)), "identical corners must share a copied position");
        result.zero();
        check(cache.read(0, result) && result.equals(new Vector3f(4, 5, 6)), "pregnancy/lift adjustment must not mutate cached base");
        for (int i = 2; i < keys.length; i++) check(!cache.read(i, result), "different deformation inputs shared slot " + i);
        cache.write(2, new Vector3f(7, 8, 9));
        check(!cache.read(3, result), "expression corners must stay independent");
        cache.end();
        check(!cache.read(0, result), "attachments outside a draw must bypass the cache");
        cache.begin();
        check(!cache.read(1, result) && !cache.read(2, result), "pose leaked to the next actor/frame");
        cache.write(1, new Vector3f(-1, -2, -3));
        check(cache.read(0, result) && result.equals(new Vector3f(-1, -2, -3)), "new pose did not replace old pose");
        cache.end();
        System.out.println("Skinning cache lifecycle and deformation-input checks passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
