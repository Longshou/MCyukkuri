package io.github.longshou.mcyukkuri.motor.geom;

/** Square grid of support heights over a host's local plane (spec 6); NaN marks a cell with no support. */
public final class HeightGrid {
    /** Support height at a local point, NaN when there is none. */
    @FunctionalInterface
    public interface Sampler { double height(double x, double z); }

    private final double minX, minZ, cell;
    private final int nx, nz;
    private final double[] h;

    /** Samples the cell centres of the box [minX,maxX]x[minZ,maxZ] at spacing {@code cell}. */
    public HeightGrid(double minX, double minZ, double maxX, double maxZ, double cell, Sampler s) {
        this.minX = minX;
        this.minZ = minZ;
        this.cell = cell;
        nx = Math.max(1, (int) Math.ceil((maxX - minX) / cell));
        nz = Math.max(1, (int) Math.ceil((maxZ - minZ) / cell));
        h = new double[nx * nz];
        for (int j = 0; j < nz; j++)
            for (int i = 0; i < nx; i++) h[j * nx + i] = s.height(centreX(i), centreZ(j));
    }

    public int nx() { return nx; }
    public int nz() { return nz; }
    public double cell() { return cell; }
    public boolean inside(int i, int j) { return i >= 0 && j >= 0 && i < nx && j < nz; }
    /** Support height of a cell, NaN outside the grid or without support. */
    public double height(int i, int j) { return inside(i, j) ? h[j * nx + i] : Double.NaN; }
    public boolean supported(int i, int j) { return !Double.isNaN(height(i, j)); }
    /** Cell index of a local coordinate, clamped into the grid. */
    public int cellX(double x) { return Math.min(nx - 1, Math.max(0, (int) Math.floor((x - minX) / cell))); }
    public int cellZ(double z) { return Math.min(nz - 1, Math.max(0, (int) Math.floor((z - minZ) / cell))); }
    public double centreX(int i) { return minX + (i + .5) * cell; }
    public double centreZ(int j) { return minZ + (j + .5) * cell; }
}
