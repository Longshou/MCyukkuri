package io.github.longshou.mcyukkuri.life;

/** World quadrant and quadrant of the block entity, numbered west/east + 2 * north/south. */
record NestQuarter(int cell, int core) {
    static NestQuarter of(int part, int core) { return new NestQuarter(part ^ core, core); }
    int part() { return cell ^ core; }
    int dx() { return (cell & 1) - (core & 1); }
    int dz() { return (cell >> 1) - (core >> 1); }
    private static int clockwise(int q) { return 1 - (q >> 1) + 2 * (q & 1); }
    NestQuarter rotate(int turns) {
        var q = this;
        for (int i = 0; i < turns; i++) q = new NestQuarter(clockwise(q.cell), clockwise(q.core));
        return q;
    }
    NestQuarter mirror(int mask) { return new NestQuarter(cell ^ mask, core ^ mask); }
}
