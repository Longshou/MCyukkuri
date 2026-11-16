package io.github.longshou.mcyukkuri.pose;

import java.util.List;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Procedural folded-newspaper tent fitted directly against a live posed Yukkuri mesh (body skin, hair, sidelocks,
 * braids after {@link WallYield} limb turns, and worn headwear) so no visible sheet intersects the body or appendages,
 * while yielding to nearby wall faces alongside {@code client/WallClearance}. Shared by the client and offline preview.
 */
public final class PaperTent {
    /** Emitted quad grids in caller units: {@code pts[row][col] = {x, y, z}} for outer ({@code top}) and inner ({@code under}) faces. */
    public record Grid(int rows, int cols, float[][][] top, float[][][] under) {}

    private static final float[] BARY_U = {
            0f, 1f, 0f, .5f, .5f, 0f, .25f, .75f, .25f, 0f, .75f, 0f, 1f / 3f, .2f, .6f, .2f
    };
    private static final float[] BARY_V = {
            0f, 0f, 1f, 0f, .5f, .5f, 0f, 0f, .75f, .25f, .25f, .75f, 1f / 3f, .2f, .2f, .6f
    };

    private PaperTent() {}

    /**
     * Fit one folded newspaper sheet over the posed mesh.
     *
     * @param bodyVerts  posed body-skin vertices ({@code x, y, z} triples; first {@code bodyLen} floats valid)
     * @param meshTris   posed non-headwear triangles ({@code body + hair + braid}, 9 floats per triangle; {@code meshLen} valid)
     * @param hatTris    posed headwear triangles (skinned accessories + prop/junk hats, 9 floats per triangle; {@code hatLen} valid)
     * @param wallFaces  nearby collision faces in block units (null or empty when none)
     * @param toWall     transforms caller units to {@code wallFaces}' block frame (null when {@code wallFaces} is empty)
     */
    public static Grid build(float[] bodyVerts, int bodyLen,
                             float[] meshTris, int meshLen,
                             float[] hatTris, int hatLen,
                             List<WallYield.Face> wallFaces,
                             Matrix4f toWall) {
        if (bodyLen < 9) return null;
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        float minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (int i = 0; i < bodyLen; i += 3) {
            float x = bodyVerts[i], y = bodyVerts[i + 1], z = bodyVerts[i + 2];
            if (x < minX) minX = x;
            if (x > maxX) maxX = x;
            if (y < minY) minY = y;
            if (y > maxY) maxY = y;
            if (z < minZ) minZ = z;
            if (z > maxZ) maxZ = z;
        }
        float bodyH = maxY - minY;
        if (bodyH < 1e-4f) return null;
        float cx = (minX + maxX) * .5f, cz = (minZ + maxZ) * .5f;

        float maxHatY = -Float.MAX_VALUE;
        for (int i = 1; i < hatLen; i += 3) if (hatTris[i] > maxHatY) maxHatY = hatTris[i];
        boolean tallHat = hatLen >= 9 && (maxHatY - minY) > 1.52f * bodyH;

        Grid grid = tallHat
                ? buildUnderBrim(cx, cz, minY, bodyH, meshTris, meshLen, hatTris, hatLen)
                : buildOverCrown(cx, cz, minY, minZ, maxZ, bodyH, meshTris, meshLen, hatTris, hatLen);

        if (wallFaces != null && !wallFaces.isEmpty() && toWall != null) {
            Matrix4f fromWall = new Matrix4f(toWall).invert();
            if (tallHat) {
                pressGrid(grid.under, grid.rows, grid.cols, wallFaces, toWall, fromWall, 1f / 4096f);
                pressGrid(grid.top, grid.rows, grid.cols, wallFaces, toWall, fromWall, 3f / 4096f);
            } else {
                pressOverCrown(grid.under, grid.rows, grid.cols, wallFaces, toWall, fromWall, 1f / 4096f);
                pressOverCrown(grid.top, grid.rows, grid.cols, wallFaces, toWall, fromWall, 3f / 4096f);
            }
        }
        return grid;
    }

    /** Press one point in caller coordinates against {@code faces} (with optional outward offset in blocks). */
    public static void pressPoint(Vector3f point, Matrix4f toWall, Matrix4f fromWall,
                                  List<WallYield.Face> faces, float wallOffsetBlocks) {
        toWall.transformPosition(point);
        float fraction = 1f;
        for (var face : faces) {
            if (point.y < face.low() || point.y > face.high()) continue;
            float toward = face.x() * point.x + face.z() * point.z;
            float limit = Math.max(0f, face.distance() + wallOffsetBlocks);
            if (toward > limit && toward > 1e-6f) fraction = Math.min(fraction, limit / toward);
        }
        point.x *= fraction;
        point.z *= fraction;
        fromWall.transformPosition(point);
    }

    /**
     * Compact headwear or bare crown (Reimu, Patchouli, Alice, Myon, bare Marisa, babies): an A-frame newspaper creased
     * along the top spine above the live crown/ribbon/mobcap and draped outside side hair and braids down toward the floor,
     * leaving the front (-Z) face and bangs open.
     */
    private static Grid buildOverCrown(float cx, float cz, float minY, float frontBodyZ, float backBodyZ, float bodyH,
                                       float[] meshTris, int meshLen, float[] hatTris, int hatLen) {
        final int roofRows = 6, rows = roofRows + 2, halfCols = 7, cols = halfCols * 2;
        float maxAllZ = backBodyZ;
        for (int i = 2; i < meshLen; i += 3) if (meshTris[i] > maxAllZ) maxAllZ = meshTris[i];
        for (int i = 2; i < hatLen; i += 3) if (hatTris[i] > maxAllZ) maxAllZ = hatTris[i];

        float clearance = .055f * bodyH;
        float thickness = .008f * bodyH;
        float zFront = cz - .36f * (cz - frontBodyZ);
        float zBackRoof = maxAllZ + clearance;
        float yEave = minY + .06f * bodyH;
        float dPhi = (float) (Math.PI * .5 / halfCols);
        float chordBoost = 1f / (float) Math.cos(dPhi * .5);

        float[] zRow = new float[roofRows + 1];
        for (int r = 0; r <= roofRows; r++) zRow[r] = zFront + (zBackRoof - zFront) * (r / (float) roofRows);
        float dzSlab = (zBackRoof - zFront) / roofRows + .04f * bodyH;

        float[][] reqLeft = new float[roofRows + 1][halfCols + 1];
        float[][] reqRight = new float[roofRows + 1][halfCols + 1];

        float baseTop = 1.10f * bodyH;
        float baseWidth = .86f * bodyH;
        for (int r = 0; r <= roofRows; r++) {
            float s = r / (float) roofRows;
            float hTent = baseTop * (1f - .04f * s * s);
            float wTent = baseWidth * (1f + .08f * s);
            for (int k = 0; k <= halfCols; k++) {
                float u = k / (float) halfCols;
                float xRel = u <= .5f ? (u / .5f) * (.84f * wTent) : (.84f + (u - .5f) / .5f * .16f) * wTent;
                float yRel = u <= .5f ? hTent * (1f - .16f * (u / .5f)) : hTent * .84f * (1f - (u - .5f) / .5f);
                float rho = (float) Math.hypot(xRel, yRel);
                reqLeft[r][k] = rho;
                reqRight[r][k] = rho;
            }
        }

        accumulatePolar(meshTris, meshLen, cx, yEave, zRow, roofRows, dzSlab, halfCols, dPhi, clearance, chordBoost, reqLeft, reqRight);
        accumulatePolar(hatTris, hatLen, cx, yEave, zRow, roofRows, dzSlab, halfCols, dPhi, clearance, chordBoost, reqLeft, reqRight);

        float[][] smoothL = new float[roofRows + 1][halfCols + 1];
        float[][] smoothR = new float[roofRows + 1][halfCols + 1];
        float sin1 = (float) Math.sin(Math.PI * .5 * (1.0 - 1.0 / halfCols));
        for (int r = 0; r <= roofRows; r++) {
            int r0 = Math.max(0, r - 1), r1 = Math.min(roofRows, r + 1);
            for (int k = 0; k <= halfCols; k++) {
                float ml = reqLeft[r][k], mr = reqRight[r][k];
                for (int nr = r0; nr <= r1; nr++) {
                    if (reqLeft[nr][k] > ml) ml = reqLeft[nr][k];
                    if (reqRight[nr][k] > mr) mr = reqRight[nr][k];
                }
                smoothL[r][k] = ml;
                smoothR[r][k] = mr;
            }
            float shoulderY = Math.max(smoothL[r][1], smoothR[r][1]) * sin1;
            float spine = Math.max(Math.max(smoothL[r][0], smoothR[r][0]), shoulderY / .93f);
            smoothL[r][0] = smoothR[r][0] = spine;
        }
        float peakSpine = 0f;
        for (int r = 0; r <= roofRows; r++) if (smoothL[r][0] > peakSpine) peakSpine = smoothL[r][0];
        for (int r = 0; r <= roofRows; r++) {
            float target = Math.max(smoothL[r][0], peakSpine * (.94f + .06f * (float) Math.sin(Math.PI * r / roofRows)));
            smoothL[r][0] = smoothR[r][0] = target;
        }

        float[][][] under = new float[rows + 1][cols + 1][3];
        float[][][] top = new float[rows + 1][cols + 1][3];
        for (int r = 0; r <= roofRows; r++) {
            float z = zRow[r];
            for (int c = 0; c <= cols; c++) {
                int k = Math.abs(c - halfCols);
                float sign = c < halfCols ? -1f : (c > halfCols ? 1f : 0f);
                float phi = (float) (Math.PI * .5 * (1.0 - k / (double) halfCols));
                float cos = (float) Math.cos(phi), sin = (float) Math.sin(phi);
                float rhoIn = c < halfCols ? smoothL[r][k] : smoothR[r][k];
                float rhoOut = rhoIn + thickness;
                under[r][c][0] = cx + sign * rhoIn * cos;
                under[r][c][1] = yEave + rhoIn * sin;
                under[r][c][2] = z;
                top[r][c][0] = cx + sign * rhoOut * cos;
                top[r][c][1] = yEave + rhoOut * sin;
                top[r][c][2] = z;
            }
        }
        // Drape the rear hood behind maxAllZ down to yEave so the back (+Z) is sheltered rather than an open tunnel.
        for (int tail = 1; tail <= 2; tail++) {
            int r = roofRows + tail;
            float drop = tail == 1 ? .52f : 1f;
            float zIn = zBackRoof + (.04f + .06f * tail) * bodyH;
            float zOut = zIn + thickness;
            for (int c = 0; c <= cols; c++) {
                float u = (c - halfCols) / (float) halfCols;
                float baseXIn = under[roofRows][c][0];
                float eaveXIn = cx + u * (under[roofRows][cols][0] - under[roofRows][0][0]) * .5f;
                under[r][c][0] = baseXIn + (eaveXIn - baseXIn) * drop * .35f;
                under[r][c][1] = under[roofRows][c][1] + (yEave - under[roofRows][c][1]) * drop;
                under[r][c][2] = zIn;

                float baseXOut = top[roofRows][c][0];
                float eaveXOut = cx + u * (top[roofRows][cols][0] - top[roofRows][0][0]) * .5f;
                top[r][c][0] = baseXOut + (eaveXOut - baseXOut) * drop * .35f;
                top[r][c][1] = top[roofRows][c][1] + (yEave - top[roofRows][c][1]) * drop;
                top[r][c][2] = zOut;
            }
        }
        return new Grid(rows, cols, top, under);
    }

    private static void accumulatePolar(float[] tris, int len, float cx, float yEave,
                                        float[] zRow, int rows, float dzSlab, int halfCols,
                                        float dPhi, float clearance, float chordBoost,
                                        float[][] reqLeft, float[][] reqRight) {
        float xMargin = .03f * (zRow[rows] - zRow[0]);
        for (int t = 0; t + 8 < len; t += 9) {
            float ax = tris[t], ay = tris[t + 1], az = tris[t + 2];
            float bx = tris[t + 3], by = tris[t + 4], bz = tris[t + 5];
            float cxT = tris[t + 6], cy = tris[t + 7], czT = tris[t + 8];
            if (Math.max(az, Math.max(bz, czT)) < zRow[0] - dzSlab) continue;
            for (int s = 0; s < BARY_U.length; s++) {
                float u = BARY_U[s], v = BARY_V[s], w = 1f - u - v;
                float px = ax * w + bx * u + cxT * v;
                float py = ay * w + by * u + cy * v;
                float pz = az * w + bz * u + czT * v;
                if (pz < zRow[0] - dzSlab || pz > zRow[rows] + dzSlab) continue;
                float dy = Math.max(0f, py - yEave);
                float dx = px - cx;
                float absX = Math.abs(dx);
                float rho = ((float) Math.hypot(absX, dy) + clearance) * chordBoost;
                float phi = (float) Math.atan2(dy, Math.max(1e-5f, absX));
                float fk = (float) ((Math.PI * .5 - phi) / dPhi);
                int k0 = Math.clamp((int) Math.floor(fk) - 1, 0, halfCols);
                int k1 = Math.clamp((int) Math.ceil(fk) + 1, 0, halfCols);
                for (int r = 0; r <= rows; r++) {
                    if (Math.abs(pz - zRow[r]) > dzSlab) continue;
                    for (int k = k0; k <= k1; k++) {
                        if (dx <= xMargin && rho > reqLeft[r][k]) reqLeft[r][k] = rho;
                        if (dx >= -xMargin && rho > reqRight[r][k]) reqRight[r][k] = rho;
                    }
                }
            }
        }
    }

    /**
     * Tall-crowned / wide-brimmed headwear (Marisa's witch hat, tall cone hats): a rear-creased folded newspaper shawl
     * wrapping around the back (+Z) and both flanks (-X/+X, enclosing Marisa's braid), tucked strictly below the posed
     * hat brim's underside and outside all body, hair, and braid triangles while leaving the front (-Z) face open.
     */
    private static Grid buildUnderBrim(float cx, float cz, float minY, float bodyH,
                                       float[] meshTris, int meshLen, float[] hatTris, int hatLen) {
        final int rows = 6, cols = 16, mid = cols / 2;
        final float thetaStart = (float) (1.22 * Math.PI), thetaEnd = (float) (-0.22 * Math.PI);
        final float dTheta = (thetaStart - thetaEnd) / cols;
        final float chordBoost = 1f / (float) Math.cos(dTheta * .5);
        final float clearance = .055f * bodyH;
        final float brimGap = .085f * bodyH;
        final float thickness = .008f * bodyH;
        final float yBot = minY + .06f * bodyH;

        float[] theta = new float[cols + 1];
        for (int c = 0; c <= cols; c++) theta[c] = thetaStart + (thetaEnd - thetaStart) * (c / (float) cols);

        float[] hatFloor = new float[cols + 1];
        for (int c = 0; c <= cols; c++) hatFloor[c] = minY + 1.05f * bodyH;
        for (int t = 0; t + 8 < hatLen; t += 9) {
            float ax = hatTris[t], ay = hatTris[t + 1], az = hatTris[t + 2];
            float bx = hatTris[t + 3], by = hatTris[t + 4], bz = hatTris[t + 5];
            float cxT = hatTris[t + 6], cy = hatTris[t + 7], czT = hatTris[t + 8];
            for (int s = 0; s < BARY_U.length; s++) {
                float u = BARY_U[s], v = BARY_V[s], w = 1f - u - v;
                float px = ax * w + bx * u + cxT * v;
                float py = ay * w + by * u + cy * v;
                float pz = az * w + bz * u + czT * v;
                if (py < minY + .50f * bodyH) continue;
                float ang = unwrapAroundBack((float) Math.atan2(pz - cz, px - cx));
                for (int c = 0; c <= cols; c++) {
                    if (Math.abs(ang - theta[c]) <= dTheta * 1.35f && py < hatFloor[c]) hatFloor[c] = py;
                }
            }
        }
        float[] yTop = new float[cols + 1];
        for (int c = 0; c <= cols; c++) {
            float floorMin = hatFloor[c];
            if (c > 0 && hatFloor[c - 1] < floorMin) floorMin = hatFloor[c - 1];
            if (c < cols && hatFloor[c + 1] < floorMin) floorMin = hatFloor[c + 1];
            float edge = Math.abs(c - mid) / (float) mid;
            float arch = minY + (.88f - .15f * edge * edge) * bodyH;
            yTop[c] = Math.clamp(Math.min(arch, floorMin - brimGap), minY + .45f * bodyH, minY + .90f * bodyH);
        }

        float[][] rad = new float[rows + 1][cols + 1];
        for (int c = 0; c <= cols; c++) {
            float spineCrease = c == mid ? .05f * bodyH : 0f;
            for (int r = 0; r <= rows; r++) {
                float u = r / (float) rows;
                rad[r][c] = (.68f + .16f * u) * bodyH + spineCrease;
            }
        }
        float dyBand = bodyH / rows + .05f * bodyH;
        for (int t = 0; t + 8 < meshLen; t += 9) {
            float ax = meshTris[t], ay = meshTris[t + 1], az = meshTris[t + 2];
            float bx = meshTris[t + 3], by = hatOrMesh(meshTris, t + 4), bz = meshTris[t + 5];
            float cxT = meshTris[t + 6], cy = meshTris[t + 7], czT = meshTris[t + 8];
            for (int s = 0; s < BARY_U.length; s++) {
                float u = BARY_U[s], v = BARY_V[s], w = 1f - u - v;
                float px = ax * w + bx * u + cxT * v;
                float py = ay * w + by * u + cy * v;
                float pz = az * w + bz * u + czT * v;
                float dx = px - cx, dz = pz - cz;
                float dist = (float) Math.hypot(dx, dz);
                if (dist < 1e-5f) continue;
                float ang = unwrapAroundBack((float) Math.atan2(dz, dx));
                float needed = (dist + clearance) * chordBoost;
                for (int c = 0; c <= cols; c++) {
                    if (Math.abs(ang - theta[c]) > dTheta * 1.35f) continue;
                    if (py > yTop[c] + .02f * bodyH) continue;
                    for (int r = 0; r <= rows; r++) {
                        float yCell = yTop[c] + (yBot - yTop[c]) * (r / (float) rows);
                        if (Math.abs(py - yCell) <= dyBand && needed > rad[r][c]) rad[r][c] = needed;
                    }
                }
            }
        }

        float[][] smooth = new float[rows + 1][cols + 1];
        for (int r = 0; r <= rows; r++) {
            for (int c = 0; c <= cols; c++) {
                float m = rad[r][c];
                if (c > 0 && rad[r][c - 1] > m) m = rad[r][c - 1];
                if (c < cols && rad[r][c + 1] > m) m = rad[r][c + 1];
                smooth[r][c] = m + (c == mid ? .045f * bodyH : 0f);
            }
            if (r > 0) {
                for (int c = 0; c <= cols; c++) {
                    smooth[r][c] = Math.max(smooth[r][c], smooth[r - 1][c] + .018f * bodyH);
                }
            }
        }

        float[][][] under = new float[rows + 1][cols + 1][3];
        float[][][] top = new float[rows + 1][cols + 1][3];
        for (int r = 0; r <= rows; r++) {
            float u = r / (float) rows;
            for (int c = 0; c <= cols; c++) {
                float y = yTop[c] + (yBot - yTop[c]) * u;
                float cos = (float) Math.cos(theta[c]), sin = (float) Math.sin(theta[c]);
                float rIn = smooth[r][c], rOut = rIn + thickness;
                under[r][c][0] = cx + rIn * cos;
                under[r][c][1] = y;
                under[r][c][2] = cz + rIn * sin;
                top[r][c][0] = cx + rOut * cos;
                top[r][c][1] = y;
                top[r][c][2] = cz + rOut * sin;
            }
        }
        return new Grid(rows, cols, top, under);
    }

    private static float hatOrMesh(float[] a, int idx) { return a[idx]; }

    private static float unwrapAroundBack(float angle) {
        return angle < (float) (-0.5 * Math.PI) ? angle + (float) (2.0 * Math.PI) : angle;
    }

    private static void pressOverCrown(float[][][] pts, int rows, int cols, List<WallYield.Face> faces,
                                       Matrix4f toWall, Matrix4f fromWall, float wallOffsetBlocks) {
        int mid = cols / 2;
        Vector3f spine = new Vector3f(), pt = new Vector3f();
        for (int r = 0; r <= rows; r++) {
            // First press the spine horizontally if a rear/front wall meets it.
            toWall.transformPosition(spine.set(pts[r][mid][0], pts[r][mid][1], pts[r][mid][2]));
            for (var face : faces) {
                if (spine.y < face.low() || spine.y > face.high()) continue;
                float toward = face.x() * spine.x + face.z() * spine.z;
                float limit = Math.max(0f, face.distance() + wallOffsetBlocks);
                if (toward > limit) {
                    float excess = toward - limit;
                    spine.x -= excess * face.x();
                    spine.z -= excess * face.z();
                }
            }
            Vector3f localSpine = fromWall.transformPosition(new Vector3f(spine));
            pts[r][mid][0] = localSpine.x;
            pts[r][mid][2] = localSpine.z;

            // Truncate each wing ray at the wall: upper vertices rise onto the roof-wall crease, lower ones hang down the wall.
            for (int c = 0; c <= cols; c++) {
                if (c == mid) continue;
                toWall.transformPosition(pt.set(pts[r][c][0], pts[r][c][1], pts[r][c][2]));
                int k = Math.abs(c - mid);
                for (int pass = 0; pass < 2; pass++) {
                    for (var face : faces) {
                        if (pt.y < face.low() || pt.y > face.high()) continue;
                        float toward = face.x() * pt.x + face.z() * pt.z;
                        float limit = Math.max(0f, face.distance() + wallOffsetBlocks);
                        if (toward <= limit) continue;
                        float spineToward = face.x() * spine.x + face.z() * spine.z;
                        if (k < mid && toward > spineToward + 1e-5f) {
                            float f = Math.clamp((limit - spineToward) / (toward - spineToward), 0f, 1f);
                            pt.x = spine.x + (pt.x - spine.x) * f;
                            pt.y = spine.y + (pt.y - spine.y) * f;
                            pt.z = spine.z + (pt.z - spine.z) * f;
                        } else {
                            float excess = toward - limit;
                            pt.x -= excess * face.x();
                            pt.z -= excess * face.z();
                        }
                    }
                }
                fromWall.transformPosition(pt);
                pts[r][c][0] = pt.x;
                pts[r][c][1] = pt.y;
                pts[r][c][2] = pt.z;
            }
        }
    }

    private static void pressGrid(float[][][] pts, int rows, int cols, List<WallYield.Face> faces,
                                  Matrix4f toWall, Matrix4f fromWall, float wallOffsetBlocks) {
        Vector3f pt = new Vector3f();
        for (int r = 0; r <= rows; r++) {
            for (int c = 0; c <= cols; c++) {
                toWall.transformPosition(pt.set(pts[r][c][0], pts[r][c][1], pts[r][c][2]));
                for (int pass = 0; pass < 3; pass++) {
                    for (var face : faces) {
                        if (pt.y < face.low() || pt.y > face.high()) continue;
                        float toward = face.x() * pt.x + face.z() * pt.z;
                        float limit = Math.max(0f, face.distance() + wallOffsetBlocks);
                        if (toward > limit) {
                            float excess = toward - limit;
                            if (pass == 0) {
                                float tx = -face.z(), tz = face.x();
                                float tangentSign = Math.signum(pt.x * tx + pt.z * tz);
                                pt.x = pt.x - excess * face.x() + excess * .55f * tangentSign * tx;
                                pt.z = pt.z - excess * face.z() + excess * .55f * tangentSign * tz;
                            } else {
                                pt.x -= excess * face.x();
                                pt.z -= excess * face.z();
                            }
                        }
                    }
                }
                fromWall.transformPosition(pt);
                pts[r][c][0] = pt.x;
                pts[r][c][1] = pt.y;
                pts[r][c][2] = pt.z;
            }
        }
    }
}
