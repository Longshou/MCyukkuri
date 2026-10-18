package io.github.longshou.mcyukkuri.body;

import java.util.Objects;
import java.util.UUID;

/** Drawing parameters only: derived signs never enter the body's injury or filling-release lists. */
public record SkinDecal(UUID id, WoundKind kind, int triangle, float u, float v, int severity, float size) {
    public SkinDecal {
        Objects.requireNonNull(kind);
        // Match the former BodyWound drawing parameters, including the final size clamp for mould.
        triangle = Math.max(0, triangle / 3 * 3);
        u = Float.isFinite(u) ? Math.clamp(u, 0, 1) : 0;
        v = Float.isFinite(v) ? Math.clamp(v, 0, 1 - u) : 0;
        severity = Math.clamp(severity, 1, 3);
        size = Float.isFinite(size) ? Math.clamp(size, .3f, 2f) : 1;
    }

    /** One-way projection for the renderer; there is deliberately no conversion back to an injury. */
    public static SkinDecal of(BodyWound wound) {
        return of(wound, wound.displaySite());
    }
    public static SkinDecal of(BodyWound wound, BodyWound.Site site) {
        return new SkinDecal(wound.id(), wound.kind(), site.triangle(), site.u(), site.v(), wound.severity(), wound.size());
    }
}
