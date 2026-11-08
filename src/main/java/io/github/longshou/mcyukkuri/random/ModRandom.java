package io.github.longshou.mcyukkuri.random;

import java.util.UUID;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

/** Server-owned random bits for mod world events and initial entity identities. */
public final class ModRandom {
    private static final SeededSource<RandomSource> WORLD = new SeededSource<>(value -> {
        var random = RandomSource.createThreadSafe();
        random.setSeed(value);
        return random;
    });
    private static volatile Long seed;
    private ModRandom() {}
    public static Long runSeed() { return seed; }
    public static void seed(long value) { seed = value; WORLD.seed(value); }
    public static void clear() { seed = null; WORLD.clear(); }
    public static RandomSource world(Level level) {
        return level.isClientSide ? level.random : WORLD.source(level.random);
    }
    /** Generation contexts have a vanilla random source but no live level. */
    public static RandomSource world(RandomSource ordinary) { return WORLD.source(ordinary); }
    public static UUID identity(RandomSource random) { return Mth.createInsecureUUID(random); }
    /** Identity of a new mod-created entity, owned by its creator's stream. */
    public static <T extends Entity> T created(T entity, RandomSource creator) {
        entity.setUUID(identity(creator));
        seedEntity(entity);
        return entity;
    }
    /** Called before mod components are constructed; loaded / scene UUIDs may subsequently replace this identity. */
    public static void construct(Entity entity) {
        if (entity.level().isClientSide) return;
        entity.setUUID(identity(world(entity.level())));
        seedEntity(entity);
    }
    public static void seedEntity(Entity entity) {
        if (seed != null) entity.getRandom().setSeed(mix(seed, entity.getUUID()));
    }
    private static long mix(long seed, UUID id) {
        long z = seed + 0x9e3779b97f4a7c15L + id.getMostSignificantBits();
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return (z ^ (z >>> 31)) ^ id.getLeastSignificantBits();
    }
}
