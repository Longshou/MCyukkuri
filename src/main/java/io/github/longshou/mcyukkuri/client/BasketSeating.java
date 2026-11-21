package io.github.longshou.mcyukkuri.client;

/**
 * Seats basket occupants on the cream liner. All three transport baskets share one runtime mesh, so every number here
 * is read off {@code yukkuri_props/yukkuri_carrier*.json} in model units (1/16 block).
 */
final class BasketSeating {
    /** Cream liner floor. */
    static final float LINER_Y = 5.5f;
    /** Oval inside the rolled rim's opening; the rim's inner mesh ring spans 4.25 by 3.5. */
    static final float MOUTH_X = 4.1f;
    static final float MOUTH_Z = 3.35f;
    /** Arched handle: a rod rising from the rim over the z band -0.5..0.5, its underside the headroom. */
    static final float HANDLE_RADIUS = 5.25f;
    static final float HANDLE_BASE_Y = 7.25f;
    static final float HANDLE_RISE = 3.5f;
    static final float HANDLE_HALF_DEPTH = .5f;
    static final float HANDLE_TUBE = .75f;
    /** Clearance between an occupant box and the walls, the handle or a neighbour. */
    static final float GAP = .125f;

    record Box(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {}
    record Seat(float x, float y, float z, float scale) {}

    /** Boxes are the occupant meshes at scale 1; scales are the recorded render scales. Units are 1/16 block. */
    static Seat[] arrange(Box[] meshes, float[] scales) {
        int count = meshes.length;
        int columns = (int) Math.ceil(Math.sqrt(count));
        int rows = (count + columns - 1) / columns;
        // The largest square-cell grid whose rectangle is inscribed in the mouth oval.
        float aspect = (float) columns / rows;
        float halfWidth = 1 / (float) Math.sqrt(1 / (MOUTH_X * MOUTH_X) + 1 / (aspect * aspect * MOUTH_Z * MOUTH_Z));
        float cell = 2 * halfWidth / columns;
        var x = new float[count];
        var z = new float[count];
        for (int i = 0; i < count; i++) {
            int row = i / columns;
            int across = Math.min(columns, count - row * columns);
            x[i] = (i % columns - (across - 1) * .5f) * cell;
            z[i] = ((rows - 1) * .5f - row) * cell;
        }
        float fit = 1;
        for (int i = 0; i < count; i++) {
            var mesh = meshes[i];
            fit = Math.min(fit, (cell - GAP) / (scales[i] * (mesh.maxX - mesh.minX)));
            fit = Math.min(fit, (cell - GAP) / (scales[i] * (mesh.maxZ - mesh.minZ)));
            // Only the row under the arch's z band has the handle above it.
            if (z[i] + cell / 2 > -HANDLE_HALF_DEPTH && z[i] - cell / 2 < HANDLE_HALF_DEPTH) {
                float headroom = handleUnderside(Math.abs(x[i]) + cell / 2) - LINER_Y;
                fit = Math.min(fit, (headroom - GAP) / (scales[i] * (mesh.maxY - mesh.minY)));
            }
        }
        var seats = new Seat[count];
        for (int i = 0; i < count; i++) {
            var mesh = meshes[i];
            float scale = fit * scales[i];
            seats[i] = new Seat(x[i] - (mesh.minX + mesh.maxX) * .5f * scale, LINER_Y - mesh.minY * scale,
                    z[i] - (mesh.minZ + mesh.maxZ) * .5f * scale, scale);
        }
        return seats;
    }

    private static float handleUnderside(float x) {
        float t = x / HANDLE_RADIUS;
        return HANDLE_BASE_Y + HANDLE_RISE * (float) Math.sqrt(1 - t * t) - HANDLE_TUBE;
    }

    private BasketSeating() {}
}
