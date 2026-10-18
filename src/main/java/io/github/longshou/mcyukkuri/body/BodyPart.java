package io.github.longshou.mcyukkuri.body;

import java.util.Locale;
import org.joml.Vector3f;

/**
 * The five coarse regions of a round body (docs/INJURIES_AND_REMAINS.md, "受伤模型"). A wound belongs to the region of its
 * rest-surface site; the core can only be reached through a region that already has a deep wound (or by a blow strong enough
 * to go straight through). Regions come from the selected skin mesh: the face looks along -z.
 */
public enum BodyPart {
    TOP, FRONT, SIDE, BACK, BOTTOM;

    /** Lowest share of the body height that counts as the sole (the same band the burnt-sole rule uses). */
    public static final float SOLE = BodyConstants.SOLE_HEIGHT_BAND;

    public String id() { return name().toLowerCase(Locale.ROOT); }

    public static BodyPart parse(String raw) {
        for (var part : values()) if (part.id().equals(raw.trim().toLowerCase(Locale.ROOT))) return part;
        return null;
    }

    public static BodyPart of(WoundSurface mesh, BodyWound wound) {
        return of(mesh, wound.triangle(), mesh.anchor(wound));
    }

    public static BodyPart of(WoundSurface mesh, int triangle, Vector3f anchor) {
        var b = mesh.bounds();
        float height = Math.max(1e-4f, b[4] - b[1]), h = (anchor.y - b[1]) / height;
        if (h < SOLE) return BOTTOM;
        var n = mesh.normal(triangle);
        if (!n.isFinite()) return SIDE;
        if (n.y > .55f || h > .82f) return TOP;
        if (n.z < -.5f) return FRONT;
        if (n.z > .5f) return BACK;
        return SIDE;
    }

    /** A random skin site on this region; null when the mesh has none. Used by scenarios and the status tool. */
    public WoundSurface.Hit site(WoundSurface mesh, java.util.Random random) {
        for (int attempt = 0; attempt < 400; attempt++) {
            int triangle = random.nextInt(mesh.skinCount / 3) * 3;
            float u = random.nextFloat() * .8f + .1f, v = random.nextFloat() * (1 - u) * .8f;
            var anchor = mesh.anchor(triangle, u, v);
            // Keep scenario sites off the eyes and mouth: the front region means a cheek or the lower face.
            if (this == FRONT && Math.abs(anchor.x) < (mesh.bounds()[3] - mesh.bounds()[0]) * .12f) continue;
            if (of(mesh, triangle, anchor) == this) return new WoundSurface.Hit(triangle, u, v, 0);
        }
        return null;
    }
}
