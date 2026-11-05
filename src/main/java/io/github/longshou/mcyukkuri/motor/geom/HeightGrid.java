package io.github.longshou.mcyukkuri.motor.geom;

/** Square grid of support heights over a host's local plane; NaN marks a cell with no support. */
public final class HeightGrid {
    /** Support height at a local point, NaN when there is none. */
    @FunctionalInterface
    public interface Sampler { double height(double x, double z); }

    private final double minX, minZ, cell;
    private final int nx, nz;
    private final double[] h;
    /** A lazy grid's sampler and which cells it has sampled; both null when every cell was sampled up front. */
    private final Sampler lazy;
    private final boolean[] sampled;

    /** Samples the cell centres of the box [minX,maxX]x[minZ,maxZ] at spacing {@code cell}. */
    public HeightGrid(double minX, double minZ, double maxX, double maxZ, double cell, Sampler s) {
        this(minX, minZ, maxX, maxZ, cell, s, false);
    }

    /** The same grid, each cell sampled when first read: for a grid asked once and dropped (a route search touches only the cells
     *  round its way, not all of them). The sampler must read a state that does not change while the grid is in use. */
    public static HeightGrid lazy(double minX, double minZ, double maxX, double maxZ, double cell, Sampler s) {
        return new HeightGrid(minX, minZ, maxX, maxZ, cell, s, true);
    }

    private HeightGrid(double minX, double minZ, double maxX, double maxZ, double cell, Sampler s, boolean lazy) {
        this.minX = minX;
        this.minZ = minZ;
        this.cell = cell;
        nx = Math.max(1, (int) Math.ceil((maxX - minX) / cell));
        nz = Math.max(1, (int) Math.ceil((maxZ - minZ) / cell));
        h = new double[nx * nz];
        this.lazy = lazy ? s : null;
        sampled = lazy ? new boolean[nx * nz] : null;
        if (!lazy)
            for (int j = 0; j < nz; j++)
                for (int i = 0; i < nx; i++) h[j * nx + i] = s.height(centreX(i), centreZ(j));
    }

    public int nx() { return nx; }
    public int nz() { return nz; }
    public double cell() { return cell; }
    public boolean inside(int i, int j) { return i >= 0 && j >= 0 && i < nx && j < nz; }
    /** Support height of a cell, NaN outside the grid or without support. */
    public double height(int i, int j) {
        if (!inside(i, j)) return Double.NaN;
        int k = j * nx + i;
        if (sampled != null && !sampled[k]) {
            h[k] = lazy.height(centreX(i), centreZ(j));
            sampled[k] = true;
        }
        return h[k];
    }
    public boolean supported(int i, int j) { return !Double.isNaN(height(i, j)); }
    /** Cell index of a local coordinate, clamped into the grid. */
    public int cellX(double x) { return Math.min(nx - 1, Math.max(0, (int) Math.floor((x - minX) / cell))); }
    public int cellZ(double z) { return Math.min(nz - 1, Math.max(0, (int) Math.floor((z - minZ) / cell))); }
    public double centreX(int i) { return minX + (i + .5) * cell; }
    public double centreZ(int j) { return minZ + (j + .5) * cell; }

    /** The supported cell nearest (x, z) among itself and the eight around it, as {@code {i, j}}, or null: a body between cell centres starts and ends on its real floor. */
    public int[] nearest(double x, double z) {
        int ci = cellX(x), cj = cellZ(z);
        int[] best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (int j = cj - 1; j <= cj + 1; j++)
            for (int i = ci - 1; i <= ci + 1; i++) {
                double dx = centreX(i) - x, dz = centreZ(j) - z, d = dx * dx + dz * dz;
                if (supported(i, j) && d < bestDistance) { bestDistance = d; best = new int[]{i, j}; }
            }
        return best;
    }
}
