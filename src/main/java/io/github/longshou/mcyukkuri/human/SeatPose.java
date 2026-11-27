package io.github.longshou.mcyukkuri.human;

/**
 * The human sit pose on a bench board and the eased settle between the standing spot in front of the seat and the seat itself.
 * Pure arithmetic shared by the server (log of the real geometry) and the client (drawing); no Minecraft types.
 */
public final class SeatPose {
    /** Ticks of the settle (0.6 s): the turn is done before, so this is the one visible sit or rise. */
    public static final int SETTLE = 12;
    /** The drawn body hangs this far below the entity standing on the board top: the vanilla riding legs lie flat at this drop. */
    public static final double DROP = .6;
    /** The board top above the cell floor the drop is made for (a bench piece and a slab seat are both half a block). */
    public static final double BOARD = .5;
    /** Hip pivot of the human model above its feet (12/16), half the leg thickness (4/16 / 2) and the leg length (12/16). */
    public static final double HIP = .75, THIGH_HALF = .125, LEG = .75;

    private SeatPose() {}

    /** Smoothstep: the settle starts and ends slowly. */
    public static double ease(double t) {
        double c = Math.max(0, Math.min(1, t));
        return c * c * (3 - 2 * c);
    }

    /** World height of the drawn hip pivot when the entity stands on a board whose top is at {@code boardTop}. */
    public static double hipY(double boardTop) { return boardTop - DROP + HIP; }

    /** Gap between the underside of the drawn thighs and the board top; zero or more means the legs do not go through the board. */
    public static double thighGap(double boardTop) { return hipY(boardTop) - THIGH_HALF - boardTop; }

    /** How far the drawn feet reach beyond the board's front edge, given the edge's distance from the seated body's centre. */
    public static double footPastEdge(double edgeFromCentre) { return LEG - edgeFromCentre; }

    /**
     * Where the body is drawn, as an offset from the entity's own position {@code e}: a sitting entity is at the seat and the body
     * eases from {@code anchor} (the spot it sat down from) to the seated drop; a rising entity is already at that spot and the body
     * eases from the seat ({@code anchor}) to it. {@code s} is the eased progress, 1 fully seated.
     */
    public static double[] offset(boolean sitting, double s, double[] e, double[] anchor) {
        double[] out = new double[3];
        for (int i = 0; i < 3; i++) {
            double drop = i == 1 ? DROP : 0;
            out[i] = sitting ? (anchor[i] - e[i]) * (1 - s) - drop * s : (anchor[i] - drop - e[i]) * s;
        }
        return out;
    }
}
