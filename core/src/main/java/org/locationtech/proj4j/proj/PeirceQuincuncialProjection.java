/*******************************************************************************
 * Copyright 2026
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/

package org.locationtech.proj4j.proj;

import org.locationtech.proj4j.InvalidValueException;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.ProjectionException;
import org.locationtech.proj4j.util.EllipticIntegral;
import org.locationtech.proj4j.util.FastStrictTrig;
import org.locationtech.proj4j.util.GenericInverse2D;

/**
 * Peirce Quincuncial. The {@code PEIRCE_Q} branch of
 * {@code 9.8.1:src/projections/adams.cpp:134-156} plus the arrangement stage at
 * {@code adams.cpp:204-285} and the two seeded inverses at {@code adams.cpp:319-385}.
 *
 * <p>Six arrangements of the same conformal map, selected by {@code +shape}. <b>The prose
 * documentation for this operator is stale in several places; {@code adams.cpp} is the
 * authority and this class follows it.</b> Specifically:
 *
 * <table>
 * <caption>{@code +shape} values, from {@code adams.cpp:405-453}</caption>
 * <tr><th>{@code +shape}</th><th>inverse?</th><th>notes</th></tr>
 * <tr><td><i>absent</i></td><td>yes</td><td><b>defaults to {@code diamond}</b>, not
 *     {@code square}</td></tr>
 * <tr><td>{@code square}</td><td><b>yes</b></td><td></td></tr>
 * <tr><td>{@code diamond}</td><td><b>yes</b></td><td></td></tr>
 * <tr><td>{@code nhemisphere}</td><td>no</td><td>rejects {@code phi < -1e-9}</td></tr>
 * <tr><td>{@code shemisphere}</td><td>no</td><td>rejects {@code phi > -1e-9}</td></tr>
 * <tr><td>{@code horizontal}</td><td>no</td><td>the only shape that reads
 *     {@code +scrollx}</td></tr>
 * <tr><td>{@code vertical}</td><td>no</td><td>the only shape that reads
 *     {@code +scrolly}</td></tr>
 * <tr><td>anything else</td><td>—</td><td>setup error</td></tr>
 * </table>
 *
 * <p><b>Only {@code square} and {@code diamond} have an inverse.</b> The other four install
 * none, so asking for one must fail rather than return the input.
 *
 * <h2>The hemisphere guards are asymmetric, and that is not a typo upstream</h2>
 *
 * <p>{@code nhemisphere} rejects {@code phi < -TOL}, so the equator and a {@code 1e-9} sliver
 * south of it are <em>accepted</em>. {@code shemisphere} rejects {@code phi > -TOL}, so the
 * equator itself is <em>rejected</em>. Over the same point grid that is 37 rejections for the
 * north and 19 for the south in {@code peirce_q.gie} — the asymmetry is directly observable in
 * the corpus and is reproduced here deliberately.
 *
 * <p>The guards run <b>before</b> any arithmetic, so a point that is both out of hemisphere and
 * would trip an {@code aacos} reports the hemisphere failure.
 *
 * <h2>{@code +scrollx} and {@code +scrolly} are silently ignored off their own shape</h2>
 *
 * <p>{@code adams.cpp} reads {@code scrollx} only inside the {@code horizontal} branch of the
 * setup and {@code scrolly} only inside the {@code vertical} branch. So
 * {@code +shape=vertical +scrollx=0.5} ignores {@code scrollx} — it is not an error, and it is
 * not applied. Range is {@code [-1, 1]} inclusive for both, and a value of exactly {@code 0.0}
 * skips the scroll block entirely.
 */
public class PeirceQuincuncialProjection extends AdamsProjection {

    private static final long serialVersionUID = 4278033229674516303L;

    /**
     * The arrangements of {@code adams.cpp}'s {@code peirce_shape} enum, in its order.
     */
    public enum Shape {
        SQUARE("square"),
        DIAMOND("diamond"),
        NHEMISPHERE("nhemisphere"),
        SHEMISPHERE("shemisphere"),
        HORIZONTAL("horizontal"),
        VERTICAL("vertical");

        private final String parameterValue;

        Shape(String parameterValue) {
            this.parameterValue = parameterValue;
        }

        /** The {@code +shape=} spelling. */
        public String parameterValue() {
            return parameterValue;
        }

        /**
         * @param value a {@code +shape=} value
         * @return the shape, or null if {@code value} is not one of the six
         */
        public static Shape forParameterValue(String value) {
            for (Shape s : values()) {
                if (s.parameterValue.equals(value)) {
                    return s;
                }
            }
            return null;
        }
    }

    /**
     * {@code shd} — the distance the southern hemisphere is shifted when it is folded out.
     * Built from the <em>accurate</em> {@code K(1/2)} literal, not from
     * {@code ellInt5(pi/2)}; the two differ in the eighth decimal and both appear in the same
     * upstream function. See {@link EllipticIntegral#K_HALF}.
     */
    private static final double SHD = EllipticIntegral.PEIRCE_SHIFT;

    /** The diamond seed's out-of-square threshold, {@code adams.cpp:381-382}. */
    private static final double DIAMOND_LIMIT = EllipticIntegral.K_HALF + 1e-3;

    /** {@code adams.cpp:325}, in the square inverse's {@code x == 0 && y < 0} seed. */
    private static final double SQUARE_SEED_A = 2.622057580396;

    /** {@code adams.cpp:331}, in the square inverse's {@code x < 0} axis-band seed. */
    private static final double SQUARE_SEED_B = 2.622057574224;

    /** The axis band width used by the square inverse's seed cascade. */
    private static final double AXIS_BAND = 1e-7;

    private static final double DELTA_XY_TOLERANCE = 1e-10;

    private Shape shape = Shape.DIAMOND;
    private double scrollx = 0.0;
    private double scrolly = 0.0;

    private final GenericInverse2D.Forward2D rawForward = new GenericInverse2D.Forward2D() {
        @Override
        public void forward(double lam, double phi, ProjCoordinate dst) {
            projectRaw(lam, phi, dst);
        }
    };

    /**
     * Sets {@code +shape}. Absent, the shape is {@link Shape#DIAMOND}.
     *
     * @throws InvalidValueException for any value outside the six, matching
     *         {@code adams.cpp:448-453}, which fails the whole setup with
     *         {@code PROJ_ERR_INVALID_OP_ILLEGAL_ARG_VALUE}
     */
    public void setShape(String value) {
        Shape resolved = Shape.forParameterValue(value);
        if (resolved == null) {
            throw new InvalidValueException(
                    "peirce_q: invalid value for 'shape' parameter: " + value);
        }
        this.shape = resolved;
    }

    public Shape getShape() {
        return shape;
    }

    /**
     * Sets {@code +scrollx}. Read by {@link Shape#HORIZONTAL} only; stored but never applied
     * for the other five, exactly as upstream stores nothing for them.
     *
     * @throws InvalidValueException if {@code |scrollx| > 1} ({@code adams.cpp:425-431})
     */
    public void setScrollX(double scrollx) {
        if (scrollx > 1 || scrollx < -1) {
            throw new InvalidValueException(
                    "Invalid value for scrollx: |scrollx| should between -1 and 1");
        }
        this.scrollx = scrollx;
    }

    public double getScrollX() {
        return scrollx;
    }

    /**
     * Sets {@code +scrolly}. Read by {@link Shape#VERTICAL} only.
     *
     * @throws InvalidValueException if {@code |scrolly| > 1} ({@code adams.cpp:439-445})
     */
    public void setScrollY(double scrolly) {
        if (scrolly > 1 || scrolly < -1) {
            throw new InvalidValueException(
                    "Invalid value for scrolly: |scrolly| should between -1 and 1");
        }
        this.scrolly = scrolly;
    }

    public double getScrollY() {
        return scrolly;
    }

    @Override
    protected ProjCoordinate projectRaw(double lam, double phi, ProjCoordinate dst) {
        if (shape == Shape.NHEMISPHERE && phi < -TOL) {
            throw new ProjectionException(
                    "peirce_q +shape=nhemisphere: phi is south of the equator");
        }
        if (shape == Shape.SHEMISPHERE && phi > -TOL) {
            throw new ProjectionException(
                    "peirce_q +shape=shemisphere: phi is not south of the equator");
        }

        final double sl = FastStrictTrig.sin(lam);
        final double cl = FastStrictTrig.cos(lam);
        final double cp = FastStrictTrig.cos(phi);
        final double a = aacos(cp * (sl + cl) * RSQRT2);
        final double b = aacos(cp * (sl - cl) * RSQRT2);
        ellipticTail(a, b, sl < 0., cl > 0., dst);

        // Fold the southern hemisphere out into the four triangles of the quincunx. The five
        // branches are independent ifs in C over disjoint half-open ranges, so an else-if
        // chain is equivalent - but the < / >= boundaries must stay exactly where they are.
        if (shape == Shape.SQUARE || shape == Shape.DIAMOND) {
            if (phi < 0.) {
                if (lam < (-0.75 * Math.PI)) {
                    dst.y = SHD - dst.y;                     // top left
                } else if (lam < (-0.25 * Math.PI)) {
                    dst.x = -SHD - dst.x;                    // left
                } else if (lam < (0.25 * Math.PI)) {
                    dst.y = -SHD - dst.y;                    // bottom
                } else if (lam < (0.75 * Math.PI)) {
                    dst.x = SHD - dst.x;                     // right
                } else {
                    dst.y = SHD - dst.y;                     // top right
                }
            }
        }

        if (shape == Shape.SQUARE) {
            rotate45(dst);
        }

        // The rectangular arrangements spin the southern hemisphere out sideways instead,
        // after the rotation, and then re-centre on the join between the two hemispheres.
        if (shape == Shape.HORIZONTAL) {
            if (phi < 0.) {
                dst.x = SHD - dst.x;
            }
            dst.x = dst.x - (SHD / 2);
        }
        if (shape == Shape.VERTICAL) {
            if (phi < 0.) {
                dst.y = SHD - dst.y;
            }
            dst.y = dst.y - (SHD / 2);
        }

        // Upstream's scale/threshold locals are folded away here: xscale == yscale == 2.0 and
        // xthresh == ythresh == shd/2, so `xthresh * 2 * xscale` is `shd * 2` and
        // `xthresh * xscale` is `shd`. The `!= 0.0` test is upstream's, and it is what makes a
        // scroll of exactly zero a no-op rather than a wrap through the same arithmetic.
        if (scrollx != 0.0 && shape == Shape.HORIZONTAL) {
            dst.x = dst.x + scrollx * (SHD * 2);
            if (dst.x >= SHD) {
                dst.x = dst.x - SHD * 2;
            } else if (dst.x < -SHD) {
                dst.x = dst.x + SHD * 2;
            }
        }
        if (scrolly != 0.0 && shape == Shape.VERTICAL) {
            dst.y = dst.y + scrolly * (SHD * 2);
            if (dst.y >= SHD) {
                dst.y = dst.y - SHD * 2;
            } else if (dst.y < -SHD) {
                dst.y = dst.y + SHD * 2;
            }
        }
        return dst;
    }

    /**
     * {@code peirce_q_square_inverse} / {@code peirce_q_diamond_inverse}
     * ({@code adams.cpp:319-385}).
     *
     * <p>The two seed cascades are quadrant heuristics — upstream calls them "based on trial and
     * repeat" — tuned jointly to {@link GenericInverse2D}'s 15-iteration budget and its
     * {@code +/-0.3} step clamp. They are transcribed literally, {@code 1e-7} axis bands,
     * immediate {@code (0, pi/2)} return at the origin, diamond {@code -pi/4} fallback and all.
     * {@code peirce_q.gie}'s inverse blocks use a 150 mm tolerance precisely because the
     * combination is fragile; the 47 roundtrips there are what catch a transcription slip.
     *
     * @throws ProjectionException for the four shapes that install no inverse
     */
    @Override
    protected ProjCoordinate projectInverse(double x, double y, ProjCoordinate dst) {
        if (shape == Shape.SQUARE) {
            return squareInverse(x, y, dst);
        }
        if (shape == Shape.DIAMOND) {
            return diamondInverse(x, y, dst);
        }
        throw new ProjectionException("peirce_q +shape=" + shape.parameterValue()
                + " has no inverse; only square and diamond do");
    }

    private ProjCoordinate squareInverse(double x, double y, ProjCoordinate dst) {
        double phi = 0;
        double lam;
        if (x == 0 && y < 0) {
            lam = -Math.PI / 4;
            if (Math.abs(y) < SQUARE_SEED_A) {
                phi = Math.PI / 4;
            }
        } else if (x > 0 && Math.abs(y) < AXIS_BAND) {
            lam = Math.PI / 4;
        } else if (x < 0 && Math.abs(y) < AXIS_BAND) {
            lam = -3 * Math.PI / 4;
            phi = Math.PI / 2 / SQUARE_SEED_B * x + Math.PI / 2;
        } else if (Math.abs(x) < AXIS_BAND && y > 0) {
            lam = 3 * Math.PI / 4;
        } else if (x >= 0 && y <= 0) {
            lam = 0;
            if (x == 0 && y == 0) {
                dst.x = 0;
                dst.y = HALF_PI;
                return dst;
            }
        } else if (x >= 0 && y >= 0) {
            lam = Math.PI / 2;
        } else if (x <= 0 && y >= 0) {
            lam = Math.abs(x) < Math.abs(y) ? Math.PI * 0.9 : -Math.PI * 0.9;
        } else {
            lam = -Math.PI / 2;
        }
        return GenericInverse2D.solve(x, y, rawForward, lam, phi, DELTA_XY_TOLERANCE, dst);
    }

    private ProjCoordinate diamondInverse(double x, double y, ProjCoordinate dst) {
        double phi = 0;
        double lam;
        if (x >= 0 && y <= 0) {
            lam = Math.PI / 4;
            if (x > 0 && y == 0) {
                lam = Math.PI / 2;
                phi = 0;
            } else if (x == 0 && y == 0) {
                dst.x = 0;
                dst.y = HALF_PI;
                return dst;
            } else if (x == 0 && y < 0) {
                lam = 0;
                phi = Math.PI / 4;
            }
        } else if (x >= 0 && y >= 0) {
            lam = 3 * Math.PI / 4;
        } else if (x <= 0 && y >= 0) {
            lam = -3 * Math.PI / 4;
        } else {
            lam = -Math.PI / 4;
        }

        if (Math.abs(x) > DIAMOND_LIMIT || Math.abs(y) > DIAMOND_LIMIT) {
            phi = -Math.PI / 4;
        }
        return GenericInverse2D.solve(x, y, rawForward, lam, phi, DELTA_XY_TOLERANCE, dst);
    }

    /**
     * Shape-dependent, because {@code pj_adams_setup} assigns {@code P->inv} only in the
     * {@code square} and {@code diamond} branches.
     */
    @Override
    public boolean hasInverse() {
        return shape == Shape.SQUARE || shape == Shape.DIAMOND;
    }

    /**
     * {@code shape}, {@code scrollx} and {@code scrolly} all change what
     * {@link #projectRaw} computes, so they belong in equality — otherwise
     * {@code +shape=square} and {@code +shape=diamond} would collide in
     * {@code CoordinateTransformFactory}'s cache and one would silently project as the
     * other.
     */
    @Override
    public boolean equals(Object that) {
        if (this == that) {
            return true;
        }
        if (!(that instanceof PeirceQuincuncialProjection) || !super.equals(that)) {
            return false;
        }
        PeirceQuincuncialProjection p = (PeirceQuincuncialProjection) that;
        return shape == p.shape && scrollx == p.scrollx && scrolly == p.scrolly;
    }

    @Override
    public int hashCode() {
        int h = super.hashCode();
        h = 31 * h + shape.hashCode();
        h = 31 * h + Double.hashCode(scrollx == 0.0 ? 0.0 : scrollx);
        h = 31 * h + Double.hashCode(scrolly == 0.0 ? 0.0 : scrolly);
        return h;
    }

    @Override
    public String toString() {
        return "Peirce Quincuncial";
    }
}
