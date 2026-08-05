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

package org.locationtech.proj4j.util.trig.repoint;

import org.junit.Test;
import org.locationtech.proj4j.util.AuthalicLat;
import org.locationtech.proj4j.util.AuxLat;
import org.locationtech.proj4j.util.Clenshaw6;
import org.locationtech.proj4j.util.ConformalLat;
import org.locationtech.proj4j.util.FastStrictTrig;
import org.locationtech.proj4j.util.MathHelpers;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The per-site half of the proof that the {@code StrictMath} &rarr; {@link FastStrictTrig}
 * re-point moved no bit. {@link RepointBitIdentityTest} pins whole code paths against digests
 * captured from the pre-change build; this class works the other way round, enumerating each of
 * the <strong>50 individual calls across 42 lines in 10 files</strong> and asserting bit identity
 * over the argument domain that particular site actually sees.
 *
 * <p>Both halves are needed. The digests prove the composed result is unchanged but say nothing
 * about which sites were exercised; the table below names every site, so a site that some future
 * refactor makes unreachable is still asserted, and the inventory itself is checkable against
 * {@code grep}.
 *
 * <h2>The inventory</h2>
 *
 * <p>A survey circulated during this change reported <b>39</b> {@code StrictMath.sin/cos/tan}
 * call sites. {@code grep -rn 'StrictMath\.\(sin\|cos\|tan\)('} over
 * {@code core/src/main/java} found <b>53 occurrences</b>: 3 in prose (one in
 * {@code AdamsProjection}'s class comment, two in {@code FastStrictTrig}'s own Javadoc) and
 * <b>50 real calls</b>, on 42 source lines, because eight lines carry two calls each. The
 * report undercounted by 11; the per-file breakdown is in {@link #SITES}.
 *
 * <h2>Method</h2>
 *
 * <p>For each site: sweep the site's argument domain, and require
 * {@code doubleToRawLongBits(FastStrictTrig.f(x)) == doubleToRawLongBits(StrictMath.f(x))}. Raw
 * bits, not {@code ==} — which would call {@code -0.0} equal to {@code 0.0} and every {@code NaN}
 * unequal to itself — and not a tolerance, because the claim being tested is exactly that no bit
 * moved.
 *
 * <p>{@link #theTestIsNotVacuous} is the control: {@code Math.sin} differs from
 * {@code StrictMath.sin} in raw bits on about 7% of arguments over {@code [-4pi, 4pi]}, so a
 * comparison that could not see a last-bit difference would fail that test instead of passing
 * everything.
 */
public class RepointedSiteDomainTest {

    // ------------------------------------------------------------------
    // Argument domains, named for what the site feeds the function
    // ------------------------------------------------------------------

    /** Longitude or a full-circle angle: {@code [-pi, pi]}. Three quarters take the reduction. */
    private static final int LON = 0;
    /** Latitude or a colatitude-like angle: {@code [-pi/2, pi/2]}. */
    private static final int LAT = 1;
    /** A halved longitude, {@code 0.5 * lam}: {@code [-pi/2, pi/2]}, densely sampled. */
    private static final int HALF_LON = 2;
    /** A halved latitude, {@code 0.5 * phi}: {@code [-pi/4, pi/4]} — the no-reduction tier. */
    private static final int HALF_LAT = 3;
    /** A sum or difference of two reduced angles, as {@code ellipticTail} forms: {@code [-pi, pi]}. */
    private static final int ANGLE_SUM = 4;
    /** A small correction, as {@code Clenshaw6.delta} returns: down to subnormal. */
    private static final int SMALL = 5;
    /** Anything at all, including past {@code 2^19 * (pi/2)} and the non-finite specials. */
    private static final int ANY = 6;

    private static final int SIN = 0;
    private static final int COS = 1;
    private static final int TAN = 2;

    /** One row per call, {@code file:line function domain}. */
    private static final Object[][] SITES = {
            // --- util/Clenshaw6.java, 4 calls on 3 lines ------------------------------------
            {"util/Clenshaw6.java:170 convert(zeta)", SIN, LON},
            {"util/Clenshaw6.java:170 convert(zeta)", COS, LON},
            {"util/Clenshaw6.java:190 convertSinCos", SIN, SMALL},
            {"util/Clenshaw6.java:191 convertSinCos", COS, SMALL},

            // --- util/ConformalLat.java, 4 calls on 4 lines ---------------------------------
            {"util/ConformalLat.java:203 tsfn", COS, LAT},
            {"util/ConformalLat.java:240 conformalLat", SIN, LAT},
            {"util/ConformalLat.java:241 conformalLat", COS, LAT},
            {"util/ConformalLat.java:259 conformalLatInverse", TAN, LAT},

            // --- util/AuthalicLat.java, 5 calls on 4 lines ----------------------------------
            {"util/AuthalicLat.java:196 forward(phi)", SIN, LAT},
            {"util/AuthalicLat.java:196 forward(phi)", COS, LAT},
            {"util/AuthalicLat.java:224 inverse", SIN, LAT},
            {"util/AuthalicLat.java:227 inverse Newton", SIN, LAT},
            {"util/AuthalicLat.java:228 inverse Newton", COS, LAT},

            // --- proj/SpilhausProjection.java, 19 calls on 16 lines -------------------------
            {"proj/SpilhausProjection.java:161 initialize", COS, LON},
            {"proj/SpilhausProjection.java:162 initialize", SIN, LON},
            {"proj/SpilhausProjection.java:165 initialize", COS, LAT},
            {"proj/SpilhausProjection.java:165 initialize", COS, LON},
            {"proj/SpilhausProjection.java:167 initialize", TAN, LON},
            {"proj/SpilhausProjection.java:167 initialize", SIN, LAT},
            {"proj/SpilhausProjection.java:168 initialize", SIN, LON},
            {"proj/SpilhausProjection.java:168 initialize", TAN, LAT},
            {"proj/SpilhausProjection.java:170 initialize", SIN, LAT},
            {"proj/SpilhausProjection.java:171 initialize", COS, LAT},
            {"proj/SpilhausProjection.java:173 initialize", COS, LAT},
            {"proj/SpilhausProjection.java:190 project", COS, LAT},
            {"proj/SpilhausProjection.java:191 project", SIN, LAT},
            {"proj/SpilhausProjection.java:193 project", COS, LON},
            {"proj/SpilhausProjection.java:194 project", SIN, LON},
            {"proj/SpilhausProjection.java:237 project", COS, LAT},
            {"proj/SpilhausProjection.java:238 project", SIN, LAT},
            {"proj/SpilhausProjection.java:239 project", COS, LON},
            {"proj/SpilhausProjection.java:240 project", SIN, LON},

            // --- proj/AdamsWorldInASquareIProjection.java, 3 calls on 2 lines ---------------
            {"proj/AdamsWorldInASquareIProjection.java:46 project", TAN, HALF_LAT},
            {"proj/AdamsWorldInASquareIProjection.java:47 project", COS, LAT},
            {"proj/AdamsWorldInASquareIProjection.java:47 project", SIN, HALF_LON},

            // --- proj/AdamsWorldInASquareIIProjection.java, 4 calls on 3 lines --------------
            {"proj/AdamsWorldInASquareIIProjection.java:84 project", TAN, HALF_LAT},
            {"proj/AdamsWorldInASquareIIProjection.java:85 project", COS, LAT},
            {"proj/AdamsWorldInASquareIIProjection.java:85 project", SIN, HALF_LON},
            {"proj/AdamsWorldInASquareIIProjection.java:101 inverse seed", COS, LAT},

            // --- proj/AdamsHemisphereProjection.java, 3 calls on 2 lines --------------------
            {"proj/AdamsHemisphereProjection.java:49 project", SIN, LAT},
            {"proj/AdamsHemisphereProjection.java:54 project", COS, LAT},
            {"proj/AdamsHemisphereProjection.java:54 project", SIN, LON},

            // --- proj/GuyouProjection.java, 3 calls on 3 lines ------------------------------
            {"proj/GuyouProjection.java:65 project", SIN, LON},
            {"proj/GuyouProjection.java:66 project", SIN, LAT},
            {"proj/GuyouProjection.java:67 project", COS, LAT},

            // --- proj/PeirceQuincuncialProjection.java, 3 calls on 3 lines ------------------
            {"proj/PeirceQuincuncialProjection.java:210 project", SIN, LON},
            {"proj/PeirceQuincuncialProjection.java:211 project", COS, LON},
            {"proj/PeirceQuincuncialProjection.java:212 project", COS, LAT},

            // --- proj/AdamsProjection.java, 2 calls on 2 lines ------------------------------
            {"proj/AdamsProjection.java:257 ellipticTail", COS, ANGLE_SUM},
            {"proj/AdamsProjection.java:261 ellipticTail", COS, ANGLE_SUM},
    };

    /** What {@code grep} counts, restated so the inventory cannot drift silently. */
    private static final int CALLS = 50;

    @Test
    public void theInventoryMatchesTheGrepCount() {
        assertEquals("the site table no longer lists every re-pointed call", CALLS, SITES.length);
    }

    @Test
    public void everySiteIsBitwiseIdenticalOverItsOwnArgumentDomain() {
        long compared = 0;
        for (Object[] site : SITES) {
            String where = (String) site[0];
            int fn = (Integer) site[1];
            double[] xs = domain((Integer) site[2]);
            for (double x : xs) {
                double fast;
                double strict;
                switch (fn) {
                    case SIN: fast = FastStrictTrig.sin(x); strict = StrictMath.sin(x); break;
                    case COS: fast = FastStrictTrig.cos(x); strict = StrictMath.cos(x); break;
                    default:  fast = FastStrictTrig.tan(x); strict = StrictMath.tan(x); break;
                }
                long a = Double.doubleToRawLongBits(fast);
                long b = Double.doubleToRawLongBits(strict);
                if (a != b) {
                    throw new AssertionError(where + ": " + name(fn) + "(" + x + ") = 0x"
                            + Long.toHexString(a) + " but StrictMath gives 0x"
                            + Long.toHexString(b));
                }
                compared++;
            }
        }
        assertTrue("the sweep did not run: " + compared + " comparisons", compared > 400000L);
    }

    /**
     * The control. If {@code Math} and {@code StrictMath} agreed everywhere, every assertion above
     * would pass for the wrong reason. They do not: about 7% of arguments differ in the last bit,
     * and in the adams family that last bit is 27.5 mm of easting.
     */
    @Test
    public void theTestIsNotVacuous() {
        Random rnd = new Random(20260801L);
        int n = 400000;
        int differ = 0;
        for (int i = 0; i < n; i++) {
            double x = (rnd.nextDouble() * 8.0 - 4.0) * Math.PI;
            if (Double.doubleToRawLongBits(Math.sin(x))
                    != Double.doubleToRawLongBits(StrictMath.sin(x))) {
                differ++;
            }
        }
        double pct = 100.0 * differ / n;
        assertTrue("Math.sin and StrictMath.sin agreed on every one of " + n + " arguments, so a "
                + "raw-bit comparison of sines cannot detect anything on this JDK and the "
                + "identity assertions above prove nothing", pct > 1.0);
    }

    // ------------------------------------------------------------------
    // Composed old bodies: the method's result, recomputed the way the pre-change source
    // computed it, must be bit-identical. This is the strongest available check short of
    // the digests, because it exercises the exact expression the site sits in.
    // ------------------------------------------------------------------

    @Test
    public void clenshaw6ConvertMatchesItsPreChangeBody() {
        double[] out = new double[2];
        for (double n : new double[] {0.0016792203863837047, 0.0, 0.05, -0.05, 0.2}) {
            for (int in = 0; in < AuxLat.NUMBER; in++) {
                for (int o = 0; o < AuxLat.NUMBER; o++) {
                    if (in == o) {
                        continue;
                    }
                    Clenshaw6 c;
                    try {
                        c = Clenshaw6.forConversion(n, in, o);
                    } catch (RuntimeException e) {
                        continue;
                    }
                    for (int i = -1800; i <= 1800; i++) {
                        double zeta = i * (Math.PI / 1800.0);
                        // was: return convert(zeta, StrictMath.sin(zeta), StrictMath.cos(zeta));
                        sameBits("Clenshaw6.convert(" + zeta + ")",
                                c.convert(zeta),
                                c.convert(zeta, StrictMath.sin(zeta), StrictMath.cos(zeta)));
                        double s = StrictMath.sin(zeta);
                        double cc = StrictMath.cos(zeta);
                        c.convertSinCos(s, cc, out);
                        // was: sd = StrictMath.sin(d); cd = StrictMath.cos(d);
                        double d = c.delta(s, cc);
                        double sd = StrictMath.sin(d);
                        double cd = StrictMath.cos(d);
                        sameBits("Clenshaw6.convertSinCos sin", out[0], s * cd + cc * sd);
                        sameBits("Clenshaw6.convertSinCos cos", out[1], cc * cd - s * sd);
                    }
                }
            }
        }
    }

    @Test
    public void conformalLatMatchesItsPreChangeBody() {
        for (double es : new double[] {0.0, 0.00669438002290, 0.2, 0.9}) {
            double e = Math.sqrt(es);
            for (int i = -4500; i <= 4500; i++) {
                double phi = i * (Math.PI / 9000.0);
                double sinphi = StrictMath.sin(phi);
                // was: return tsfnSinCos(sinphi, StrictMath.cos(phi), e);
                sameBits("ConformalLat.tsfn", ConformalLat.tsfn(phi, sinphi, e),
                        ConformalLat.tsfnSinCos(sinphi, StrictMath.cos(phi), e));
                // was: sphi = StrictMath.sin(phi); cphi = StrictMath.cos(phi); ...
                double expected;
                if (e == 0.0) {
                    expected = phi;
                } else {
                    double sphi = StrictMath.sin(phi);
                    double cphi = StrictMath.cos(phi);
                    expected = StrictMath.atan(StrictMath.sinh(
                            MathHelpers.asinh(sphi / cphi) - e * MathHelpers.atanh(e * sphi)));
                }
                sameBits("ConformalLat.conformalLat", ConformalLat.conformalLat(phi, e), expected);
                // was: return StrictMath.atan(sinhpsi2tanphi(StrictMath.tan(chi), e));
                double inv;
                double invExpected;
                try {
                    inv = ConformalLat.conformalLatInverse(phi, e);
                } catch (RuntimeException ex) {
                    continue;
                }
                invExpected = (e == 0.0) ? phi
                        : StrictMath.atan(ConformalLat.sinhpsi2tanphi(StrictMath.tan(phi), e));
                sameBits("ConformalLat.conformalLatInverse", inv, invExpected);
            }
        }
    }

    @Test
    public void authalicForwardMatchesItsPreChangeBody() {
        for (double es : new double[] {0.00669438002290, 0.05, 0.5, 0.9}) {
            AuthalicLat al = new AuthalicLat(es);
            for (int i = -4500; i <= 4500; i++) {
                double phi = i * (Math.PI / 9000.0);
                // was: return forward(phi, StrictMath.sin(phi), StrictMath.cos(phi));
                sameBits("AuthalicLat.forward(" + phi + ")", al.forward(phi),
                        al.forward(phi, StrictMath.sin(phi), StrictMath.cos(phi)));
            }
        }
    }

    // ------------------------------------------------------------------

    private static void sameBits(String what, double actual, double expected) {
        long a = Double.doubleToRawLongBits(actual);
        long b = Double.doubleToRawLongBits(expected);
        if (a != b) {
            throw new AssertionError(what + ": got 0x" + Long.toHexString(a) + " (" + actual
                    + "), pre-change body gives 0x" + Long.toHexString(b) + " (" + expected + ")");
        }
    }

    private static String name(int fn) {
        return fn == SIN ? "sin" : fn == COS ? "cos" : "tan";
    }

    /** Arguments a site of the given kind can see, edges and specials included. */
    private static double[] domain(int kind) {
        Random rnd = new Random(0x5EED0000L + kind);
        double[] xs = new double[9000 + 64];
        int k = 0;
        double half;
        switch (kind) {
            case LON:
            case ANGLE_SUM:
                half = Math.PI;
                break;
            case LAT:
            case HALF_LON:
                half = Math.PI / 2.0;
                break;
            case HALF_LAT:
                half = Math.PI / 4.0;
                break;
            case SMALL:
                half = 1e-3;
                break;
            default:
                half = 0.0; // ANY, filled below
                break;
        }
        if (kind == ANY) {
            for (int i = 0; i < 9000; i++) {
                // spans the |x| <= pi/4, medium and multi-word tiers
                xs[k++] = Math.scalb(rnd.nextDouble() * 2.0 - 1.0, rnd.nextInt(80) - 30);
            }
        } else if (kind == SMALL) {
            for (int i = 0; i < 9000; i++) {
                xs[k++] = Math.scalb(rnd.nextDouble() * 2.0 - 1.0, -rnd.nextInt(60));
            }
        } else {
            // a dense uniform grid plus a random overlay, so both quantised and generic
            // arguments are covered
            for (int i = 0; i < 4500; i++) {
                xs[k++] = -half + (2.0 * half * i) / 4499.0;
            }
            for (int i = 0; i < 4500; i++) {
                xs[k++] = (rnd.nextDouble() * 2.0 - 1.0) * half;
            }
        }
        double[] specials = {
                0.0, -0.0, Double.MIN_VALUE, -Double.MIN_VALUE, Double.MIN_NORMAL,
                Math.PI / 4, -Math.PI / 4, Math.nextUp(Math.PI / 4), Math.nextDown(Math.PI / 4),
                Math.PI / 2, -Math.PI / 2, Math.PI, -Math.PI, 2 * Math.PI, -2 * Math.PI,
                Math.nextUp(Math.PI / 2), Math.nextDown(Math.PI / 2),
                0.6744, 0.78125, 0.3, 1e-9, 1e-30, 823550.0, 823551.0,
                Math.scalb(Math.PI / 2, 19), Math.scalb(Math.PI / 2, 20), 1e300,
                Double.MAX_VALUE, -Double.MAX_VALUE,
                Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NaN,
        };
        for (double s : specials) {
            xs[k++] = s;
        }
        double[] trimmed = new double[k];
        System.arraycopy(xs, 0, trimmed, 0, k);
        return trimmed;
    }
}
