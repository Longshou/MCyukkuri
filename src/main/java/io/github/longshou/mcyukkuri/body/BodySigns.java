package io.github.longshou.mcyukkuri.body;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.nbt.CompoundTag;
import org.joml.Vector3f;

/**
 * Lasting body states drawn on the skin: mould patches by stage, the charred sole, filling showing in deep wounds and the
 * centre filling when it lies bare or is broken. The server owns the states; this class only derives stable decal sites from
 * them, so the live body, the remains and the previews draw the same picture. No client classes.
 *
 * <p>Mark kinds (rendered by {@code client/WoundRenderer}, colours from {@code body_marks.png}): {@code mold} (severity =
 * visual tier), {@code char} (baked sole), {@code paste} (filling in a deep wound), {@code core} (severity 1 bare, 3 broken).
 */
public final class BodySigns {
    public static final int MOLD_MASK = 15, FOOT_BURNED = 16, CORE_HURT = 32, DYING = 64;
    /** The legacy 0-12 display value at which a deep wound can show the centre (30% health, rounded up). */
    public static final int CORE_SHOWS = 4;
    private static final Map<WoundSurface, int[]> SOLE_SITES = new ConcurrentHashMap<>();

    private BodySigns() {}

    public static int pack(int mold, boolean footBurned, boolean coreHurt, boolean dying) {
        return Math.clamp(mold, 0, 15) | (footBurned ? FOOT_BURNED : 0) | (coreHurt ? CORE_HURT : 0) | (dying ? DYING : 0);
    }
    public static int pack(CompoundTag body) {
        return pack(body.getInt("Mold"), body.getBoolean("FootBurned"), body.getBoolean("CoreHurt"), false);
    }
    /**
     * Low health only changes the silhouette when skin is actually open.  The curve starts below
     * 60% health and reaches the agreed 8% spread / 20% height loss at zero, so intact sick bodies
     * can use posture and expression without acquiring an invented hole.
     */
    public static float sag(float healthFraction, boolean openSkin) {
        if (!openSkin) return 0;
        return Math.clamp((.6f - healthFraction) / .6f, 0f, 1f);
    }
    public static float sagWidth(float healthFraction, boolean openSkin) { return 1 + .08f * sag(healthFraction, openSkin); }
    public static float sagHeight(float healthFraction, boolean openSkin) { return 1 - .20f * sag(healthFraction, openSkin); }
    /** Compatibility for old callers that only have the derived 0-12 display value. */
    public static float sag(int filling) { return sag(filling / 12f, true); }
    public static float sagWidth(int filling) { return sagWidth(filling / 12f, true); }
    public static float sagHeight(int filling) { return sagHeight(filling / 12f, true); }
    /** Mould stage to drawn tier: 1-2 early, 3-5 middle, 6-7 late, 8 terminal. */
    public static int moldTier(int mold) { return mold <= 0 ? 0 : mold <= 2 ? 1 : mold <= 5 ? 2 : mold <= 7 ? 3 : 4; }
    /** A deep wound: missing skin with filling showing (severity 3 tear, dent or burn). Orange juice closes it. */
    public static boolean deep(BodyWound w) {
        return w.severity() >= 3 && (w.kind()==WoundKind.TEAR || w.kind()==WoundKind.DENT || w.kind()==WoundKind.SCORCH);
    }
    /** Filling shows: any deep wound, or a tear of severity 2 (the existing leaking cut). */
    public static boolean open(BodyWound w) { return deep(w) || w.kind()==WoundKind.TEAR && w.severity() >= 2; }

    /** The same visible core opening is used for drawing and pre-hit core vulnerability. */
    public static BodyWound coreWound(WoundSurface mesh, List<BodyWound> wounds, boolean broken, float healthFraction) {
        if (mesh == null || !broken && healthFraction > BodyLife.WEAK_HEALTH_RATIO) return null;
        for (var wound : wounds) if (deep(wound) && mesh.valid(wound) && BodyCavity.opening(mesh, wound) != null) return wound;
        return null;
    }

    /** The intact core's existing drawn footprint, in model units. Shared with its renderer. */
    public static float coreRadius(float size, int severity) { return size * (.6f + severity * .16f) * .46f; }

    /** Project the real skin hit into the same tangent plane and 24-sided outline used by the core decal. */
    public static boolean hitsCore(WoundSurface mesh, BodyWound core, WoundSurface.Hit hit) {
        var normal = mesh.normal(core.triangle());
        if (mesh.normal(hit.triangle()).dot(normal) < .5f) return false;
        var delta = mesh.anchor(hit).sub(mesh.anchor(core));
        if (Math.abs(delta.dot(normal)) > 1.6f) return false;
        var tangent = WoundSurface.tangent(normal);
        var bitangent = new Vector3f(normal).cross(tangent);
        float radius = coreRadius(core.size(), 1);
        double x = delta.dot(tangent) / radius, y = delta.dot(bitangent) / (.8f * radius);
        boolean inside = false;
        for (int i = 0; i < 24; i++) {
            double a = i * Math.PI * 2 / 24, b = (i + 1) * Math.PI * 2 / 24;
            double ra = 1 + .09 * Math.sin(a * 7 + 36) + .04 * Math.cos(a * 13);
            double rb = 1 + .09 * Math.sin(b * 7 + 36) + .04 * Math.cos(b * 13);
            double ax = Math.cos(a) * ra, ay = Math.sin(a) * ra;
            double bx = Math.cos(b) * rb, by = Math.sin(b) * rb;
            if ((ay > y) != (by > y) && x < (bx - ax) * (y - ay) / (by - ay) + ax) inside = !inside;
        }
        return inside;
    }

    public static List<SkinDecal> of(WoundSurface mesh, UUID seed, int packed, List<BodyWound> wounds, float healthFraction) {
        var result = new ArrayList<SkinDecal>();
        if (mesh == null || mesh.skinCount < 3) return result;
        int tier = moldTier(packed & MOLD_MASK);
        if (tier > 0) {
            var random = new Random(seed.getLeastSignificantBits() ^ 0x6d6f6c64L);
            int count = new int[]{0, 3, 4, 6, 9}[tier];
            float size = new float[]{0, .95f, 1.1f, 1.4f, 1.75f}[tier];
            for (int i = 0; i < count; i++) {
                var site = moldSite(mesh, random);
                if (site == null) break;
                // Each patch keeps its place as the mould advances; only the size and the tier grow.
                result.add(new SkinDecal(new UUID(seed.getMostSignificantBits() ^ 0x6d6f6c64L, i), WoundKind.MOLD, site.triangle(), site.u(), site.v(),
                        tier, size * (.85f + .3f * random.nextFloat())));
            }
        }
        if ((packed & FOOT_BURNED) != 0) {
            int[] sites = SOLE_SITES.computeIfAbsent(mesh, BodySigns::soleSites);
            if (sites.length > 0)
                result.add(new SkinDecal(new UUID(seed.getMostSignificantBits() ^ 0x63686172L, 0), WoundKind.CHAR, sites[0], .33f, .33f, 3, 1.7f));
        }
        boolean broken = (packed & CORE_HURT) != 0;
        BodyWound deepest = coreWound(mesh, wounds, broken, healthFraction);
        // CORE carries the chosen real opening and damage state to the renderer; it is no longer a surface dot.
        if (deepest != null) {
            var site = mesh.displaySite(deepest);
            result.add(new SkinDecal(new UUID(deepest.id().getMostSignificantBits(), deepest.id().getLeastSignificantBits() ^ 0x636f7265L), WoundKind.CORE,
                    site.triangle(), site.u(), site.v(), broken ? 3 : 1, deepest.size()));
        }
        return result;
    }

    /** Compatibility for remains migrated from the former 0-12 filling presentation. */
    public static List<SkinDecal> of(WoundSurface mesh, UUID seed, int packed, List<BodyWound> wounds, int filling) {
        return of(mesh, seed, packed, wounds, Math.clamp(filling / 12f, 0f, 1f));
    }

    private static WoundSurface.Hit moldSite(WoundSurface mesh, Random random) {
        var b = mesh.bounds();
        for (int attempt = 0; attempt < 64; attempt++) {
            int triangle = random.nextInt(mesh.skinCount / 3) * 3;
            var n = mesh.normal(triangle);
            if (!n.isFinite() || n.y < -.45f) continue;
            float u = random.nextFloat() * .8f + .1f, v = random.nextFloat() * (1 - u) * .8f;
            var anchor = mesh.anchor(triangle, u, v);
            if ((anchor.y - b[1]) / Math.max(1e-4f, b[4] - b[1]) < .12f) continue;
            return new WoundSurface.Hit(triangle, u, v, 0);
        }
        return null;
    }

    /** Six evenly spaced points on the lower rim, low enough to be the sole yet visible from the side. */
    private static int[] soleSites(WoundSurface mesh) {
        var b = mesh.bounds();
        float limit = b[1] + (b[4] - b[1]) * .1f, cx = (b[0] + b[3]) / 2, cz = (b[2] + b[5]) / 2;
        int[] best = new int[6];
        float[] score = new float[6];
        Arrays.fill(best, -1);
        Arrays.fill(score, Float.MAX_VALUE);
        for (int t = 0; t + 2 < mesh.skinCount; t += 3) {
            var c = mesh.rest(t).add(mesh.rest(t + 1)).add(mesh.rest(t + 2)).div(3);
            if (c.y > limit) continue;
            var n = mesh.normal(t);
            if (!n.isFinite() || n.y < -.85f) continue;
            double angle = Math.atan2(c.z - cz, c.x - cx);
            float radial = (float) Math.hypot(c.x - cx, c.z - cz);
            for (int k = 0; k < 6; k++) {
                double target = -Math.PI + k * Math.PI / 3 + Math.PI / 6;
                float d = (float) Math.abs(Math.atan2(Math.sin(angle - target), Math.cos(angle - target))) - radial * .02f + c.y * .05f;
                if (d < score[k]) { score[k] = d; best[k] = t; }
            }
        }
        return Arrays.stream(best).filter(t -> t >= 0).toArray();
    }
}
