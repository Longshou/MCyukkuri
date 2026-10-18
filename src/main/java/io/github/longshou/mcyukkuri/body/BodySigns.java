package io.github.longshou.mcyukkuri.body;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.nbt.CompoundTag;

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
    /** At or below this filling a deep wound shows the centre filling. */
    public static final int CORE_SHOWS = 6;
    private static final Map<WoundSurface, int[]> SOLE_SITES = new ConcurrentHashMap<>();

    private BodySigns() {}

    public static int pack(int mold, boolean footBurned, boolean coreHurt, boolean dying) {
        return Math.clamp(mold, 0, 15) | (footBurned ? FOOT_BURNED : 0) | (coreHurt ? CORE_HURT : 0) | (dying ? DYING : 0);
    }
    public static int pack(CompoundTag body) {
        return pack(body.getInt("Mold"), body.getBoolean("FootBurned"), body.getBoolean("CoreHurt"), false);
    }
    /** How far a body with little filling sags (0 = full, 1 = empty): it settles lower and spreads a little. */
    public static float sag(int filling) { return Math.clamp((CORE_SHOWS - filling) / (float) CORE_SHOWS, 0f, 1f); }
    public static float sagWidth(int filling) { return 1 + .06f * sag(filling); }
    public static float sagHeight(int filling) { return 1 - .14f * sag(filling); }
    /** Mould stage to drawn tier: 1-2 early, 3-5 middle, 6-7 late, 8 terminal. */
    public static int moldTier(int mold) { return mold <= 0 ? 0 : mold <= 2 ? 1 : mold <= 5 ? 2 : mold <= 7 ? 3 : 4; }
    /** A deep wound: missing skin with filling showing (severity 3 tear, dent or burn). Only flour paste closes it. */
    public static boolean deep(BodyWound w) {
        return w.severity() >= 3 && (w.kind()==WoundKind.TEAR || w.kind()==WoundKind.DENT || w.kind()==WoundKind.SCORCH);
    }
    /** Filling shows: any deep wound, or a tear of severity 2 (the existing leaking cut). */
    public static boolean open(BodyWound w) { return deep(w) || w.kind()==WoundKind.TEAR && w.severity() >= 2; }

    public static List<SkinDecal> of(WoundSurface mesh, UUID seed, int packed, List<BodyWound> wounds, int filling) {
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
                        Math.min(3, tier), size * (.85f + .3f * random.nextFloat())));
            }
        }
        if ((packed & FOOT_BURNED) != 0) {
            int[] sites = SOLE_SITES.computeIfAbsent(mesh, BodySigns::soleSites);
            for (int i = 0; i < sites.length; i++)
                result.add(new SkinDecal(new UUID(seed.getMostSignificantBits() ^ 0x63686172L, i), WoundKind.CHAR, sites[i], .33f, .33f, 3, 1.7f));
        }
        boolean broken = (packed & CORE_HURT) != 0;
        BodyWound deepest = null;
        for (var w : wounds) {
            if (!deep(w) || !mesh.valid(w)) continue;
            var site = mesh.displaySite(w);
            result.add(new SkinDecal(new UUID(w.id().getMostSignificantBits(), w.id().getLeastSignificantBits() ^ 0x70617374L), WoundKind.PASTE,
                    site.triangle(), site.u(), site.v(), 1, w.size()));
            if (deepest == null) deepest = w;
        }
        if (deepest != null && (broken || filling <= CORE_SHOWS)) {
            var site = mesh.displaySite(deepest);
            result.add(new SkinDecal(new UUID(deepest.id().getMostSignificantBits(), deepest.id().getLeastSignificantBits() ^ 0x636f7265L), WoundKind.CORE,
                    site.triangle(), site.u(), site.v(), broken ? 3 : 1, deepest.size()));
        }
        return result;
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
