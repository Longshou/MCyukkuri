package io.github.longshou.mcyukkuri.settlement;

/** One terrain result per burrow. The sign records the answer; the magnitude records logical enclosure checks. */
final class EnclosureCache {
    static final int PASS = 1, LIVE_SHAPE = 2, CHECK_SHIFT = 2;
    private Object beds;
    private long tags, nw, ne, sw, se;
    private int checks;

    int lookup(Object beds, long tags, long nw, long ne, long sw, long se) {
        return (nw != 0 && ne != 0 && sw != 0 && se != 0 && this.beds == beds && this.tags == tags
                && this.nw == nw && this.ne == ne && this.sw == sw && this.se == se) ? checks : 0;
    }

    void remember(Object beds, long tags, long nw, long ne, long sw, long se, int result) {
        this.beds = beds;
        this.tags = tags;
        this.nw = nw;
        this.ne = ne;
        this.sw = sw;
        this.se = se;
        int visited = result >> CHECK_SHIFT;
        checks = (result & LIVE_SHAPE) != 0 ? 0 : (result & PASS) != 0 ? visited : -visited;
    }
}
