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

package org.locationtech.proj4j.util;

/**
 * Fixed-order-6 Clenshaw summation of {@code sum(F[k] * sin((2k+2) * zeta), k, 0, 5)},
 * a port of PROJ 9.8.1's {@code pj_clenshaw} and the three {@code pj_auxlat_convert}
 * overloads ({@code src/latitudes.cpp:372-410}).
 *
 * <p>This is the hottest arithmetic in the auxiliary-latitude framework: it evaluates
 * the Fourier series from {@code sin(zeta)} and {@code cos(zeta)} with <em>no
 * trigonometric calls at all</em>.
 *
 * <p>Two deliberate implementation choices:
 *
 * <ul>
 * <li>The six coefficients are held as six {@code final double} fields, <b>not an
 * array</b>. The order is fixed at {@value AuxLat#ORDER} by upstream, so the loop is
 * fully unrolled; the JIT then keeps the coefficients in registers and no array bounds
 * check is executed per row of a bulk transform.
 * <li>{@code X = 2 * (c - s) * (c + s)} rather than {@code 2 * cos(2 * zeta)} or
 * {@code 2 * (2 * c * c - 1)}. It is one multiply instead of a trig call, <em>and</em>
 * more accurate than the {@code 2*c*c - 1} form near {@code zeta = pi/4}, where that
 * form suffers cancellation.
 * </ul>
 *
 * <p>Instances are immutable and therefore thread safe.
 *
 * <p><b>Math vs StrictMath:</b> {@link #delta} and {@link #convert(double, double, double)}
 * use only {@code + - *} and are exactly reproducible everywhere. The two methods that
 * must produce a sine and cosine from an angle — {@link #convert(double)} and
 * {@link #convertSinCos} — use {@link FastStrictTrig#sin} and {@link FastStrictTrig#cos}
 * because HotSpot ships architecture-specific intrinsics for {@code Math.sin/cos} on
 * x86-64 and AArch64 that genuinely differ between them; fdlibm is identical on every
 * platform. That buys determinism, not bit parity with PROJ (which links the system
 * libm); 1 ulp of a radian is about 2 pm on the ground.
 *
 * <p>{@link FastStrictTrig} rather than {@link StrictMath} because the two are
 * <em>bit-identical</em> and only the latter allocates: from <b>JDK 21</b> — not 17, where
 * they are still {@code native} JNI calls into compiled fdlibm and allocate nothing —
 * {@code StrictMath.sin/cos/tan} each allocate a {@code double[2]} argument-reduction
 * carrier that escape analysis does not remove, 62 B/op over {@code [-pi, pi]}. This is
 * the single highest-traffic trig site in the library — {@code convert(zeta)} is reached
 * from {@code utm tmerc lcc cass poly aea laea} — and the {@code etmerc} forward alone
 * was leaking 124 B/point through it.
 *
 * @see AuxLat
 */
public final strictfp class Clenshaw6 implements java.io.Serializable {

    private static final long serialVersionUID = 1L;

    private final double f0;
    private final double f1;
    private final double f2;
    private final double f3;
    private final double f4;
    private final double f5;

    /**
     * Wraps six Fourier coefficients.
     *
     * @param f0 coefficient of {@code sin(2*zeta)}
     * @param f1 coefficient of {@code sin(4*zeta)}
     * @param f2 coefficient of {@code sin(6*zeta)}
     * @param f3 coefficient of {@code sin(8*zeta)}
     * @param f4 coefficient of {@code sin(10*zeta)}
     * @param f5 coefficient of {@code sin(12*zeta)}
     */
    public Clenshaw6(double f0, double f1, double f2, double f3, double f4, double f5) {
        this.f0 = f0;
        this.f1 = f1;
        this.f2 = f2;
        this.f3 = f3;
        this.f4 = f4;
        this.f5 = f5;
    }

    /**
     * Copies six coefficients out of an array. The array is not retained.
     *
     * @param f coefficients starting at {@code offset}, at least
     *          {@value AuxLat#ORDER} usable elements
     * @param offset index of the first coefficient
     */
    public Clenshaw6(double[] f, int offset) {
        this(f[offset], f[offset + 1], f[offset + 2], f[offset + 3], f[offset + 4],
                f[offset + 5]);
    }

    /**
     * Copies six coefficients out of the start of an array. The array is not retained.
     *
     * @param f coefficients, at least {@value AuxLat#ORDER} long
     */
    public Clenshaw6(double[] f) {
        this(f, 0);
    }

    /**
     * Builds the evaluator for one auxiliary-latitude conversion.
     *
     * @param n      the third flattening
     * @param auxin  the input auxiliary latitude, an {@link AuxLat} constant
     * @param auxout the output auxiliary latitude
     * @return an evaluator for {@code auxin -> auxout}
     * @throws IllegalArgumentException if the conversion is not tabulated
     */
    public static Clenshaw6 forConversion(double n, int auxin, int auxout) {
        return new Clenshaw6(AuxLat.coeffs(n, auxin, auxout));
    }

    /**
     * The Fourier sum {@code sum(F[k] * sin((2k+2) * zeta))}, i.e. the correction to be
     * added to {@code zeta} to obtain the target latitude.
     *
     * <p>Upstream's loop starts with {@code u0 = u1 = 0}, so its first trip reduces to
     * {@code u0 = F[5]}; that step is folded into the initialisation here. The
     * remaining five trips are unrolled.
     *
     * @param s {@code sin(zeta)}
     * @param c {@code cos(zeta)}
     * @return the correction, in the same units as {@code zeta} (radians)
     */
    public double delta(double s, double c) {
        final double x = 2.0 * (c - s) * (c + s); // == 2 * cos(2 * zeta)
        double u0 = f5, u1 = 0.0, t;
        t = x * u0 - u1 + f4; u1 = u0; u0 = t;
        t = x * u0 - u1 + f3; u1 = u0; u0 = t;
        t = x * u0 - u1 + f2; u1 = u0; u0 = t;
        t = x * u0 - u1 + f1; u1 = u0; u0 = t;
        t = x * u0 - u1 + f0; u1 = u0; u0 = t;
        return 2.0 * s * c * u0; // sin(2 * zeta) * u0
    }

    /**
     * Converts {@code zeta} to the target auxiliary latitude, given its sine and
     * cosine. Port of the three-argument {@code pj_auxlat_convert}.
     *
     * @param zeta the source latitude, radians
     * @param s    {@code sin(zeta)}
     * @param c    {@code cos(zeta)}
     * @return the target latitude, radians
     */
    public double convert(double zeta, double s, double c) {
        return zeta + delta(s, c);
    }

    /**
     * Converts {@code zeta} to the target auxiliary latitude, computing its sine and
     * cosine internally. Port of the one-argument {@code pj_auxlat_convert}.
     *
     * @param zeta the source latitude, radians
     * @return the target latitude, radians
     */
    public double convert(double zeta) {
        return convert(zeta, FastStrictTrig.sin(zeta), FastStrictTrig.cos(zeta));
    }

    /**
     * The sine/cosine-preserving conversion, port of
     * {@code pj_auxlat_convert(szeta, czeta, &seta, &ceta, F)}
     * ({@code 9.8.1:src/latitudes.cpp:404-410}).
     *
     * <p>Rather than forming the target angle and taking its sine and cosine — which
     * loses relative accuracy as {@code cos} approaches zero — this rotates the input
     * pair by the small correction {@code delta}. That preserves full relative accuracy
     * near the poles, and is what 9.8.1's {@code etmerc} inverse uses.
     *
     * @param s   {@code sin(zeta)}
     * @param c   {@code cos(zeta)}
     * @param out destination of length at least 2; receives {@code sin(eta)} at index
     *            0 and {@code cos(eta)} at index 1
     */
    public void convertSinCos(double s, double c, double[] out) {
        final double d = delta(s, c);
        final double sd = FastStrictTrig.sin(d);
        final double cd = FastStrictTrig.cos(d);
        out[0] = s * cd + c * sd;
        out[1] = c * cd - s * sd;
    }

    /**
     * The coefficient of {@code sin((2k+2) * zeta)}.
     *
     * @param k index, 0 through 5
     * @return the coefficient
     * @throws IndexOutOfBoundsException if {@code k} is out of range
     */
    public double coefficient(int k) {
        switch (k) {
            case 0: return f0;
            case 1: return f1;
            case 2: return f2;
            case 3: return f3;
            case 4: return f4;
            case 5: return f5;
            default: throw new IndexOutOfBoundsException("k=" + k);
        }
    }
}
