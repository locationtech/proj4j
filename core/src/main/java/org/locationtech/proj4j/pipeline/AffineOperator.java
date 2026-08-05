/*
 * Copyright 2026 The Proj4J Contributors.
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
 */
package org.locationtech.proj4j.pipeline;

import org.locationtech.proj4j.gie.GieIoUnits;

/**
 * {@code +proj=affine} — a 3&times;3 matrix, a translation and an independent time
 * scale, ported from {@code 9.8.1:src/transformations/affine.cpp}.
 *
 * <pre>
 * x' = xoff + s11&middot;x + s12&middot;y + s13&middot;z
 * y' = yoff + s21&middot;x + s22&middot;y + s23&middot;z
 * z' = zoff + s31&middot;x + s32&middot;y + s33&middot;z
 * t' = toff + tscale&middot;t
 * </pre>
 *
 * <h2>Four things worth stating, all of them upstream's</h2>
 *
 * <ol>
 * <li><b>The diagonal defaults to 1 but is read with {@code 't'} first.</b>
 *     {@code if (pj_param(..., "ts11").i) Q->forward.s11 = pj_param(..., "ds11").f;} —
 *     so an explicit {@code +s11=0} really is zero, while an <em>absent</em>
 *     {@code +s11} is one. The off-diagonal terms have no such guard and simply
 *     default to {@code pj_param}'s 0. Same treatment for {@code s22}, {@code s33} and
 *     {@code tscale}. Reading the diagonal with a plain "default 1 if unparseable"
 *     would silently turn {@code +s11=0} into the identity.</li>
 * <li><b>The inverse subtracts the offset first and then applies the inverse matrix</b>
 *     ({@code reverse_4d}), which is not the same as applying an inverse affine with a
 *     negated offset unless the matrix is the identity.</li>
 * <li><b>A singular matrix is not an error: it removes the inverse.</b>
 *     {@code computeReverseParameters} nulls {@code P->inv4d}/{@code inv3d}/{@code inv}
 *     when {@code det == 0} <em>or</em> {@code tscale == 0}, and the forward direction
 *     keeps working. That is reported here through {@link #hasInverse()}, which is what
 *     makes the enclosing pipeline one-way rather than making construction fail.</li>
 * <li><b>Both sides are {@link GieIoUnits#WHATEVER}</b>, so a bare
 *     {@code +step +proj=affine} is a legal no-op filler — which is exactly how
 *     {@code 4D-API_cs2cs-style.gie:328} uses it, as a "dummy step".</li>
 * </ol>
 *
 * <p>Upstream's cofactor expansion is reproduced term for term, including the sign
 * pattern and the division of every cofactor by the same determinant, because the
 * corpus's {@code affine} rows are 50 cm-tolerance datum-shift simulations and a
 * rearranged inverse would agree to the last digit only by luck.
 *
 * <p>Immutable apart from the overridable unit sides; safe to share.
 *
 * @since 1.5
 */
final class AffineOperator implements PipelineOperator {

    private final double xoff;
    private final double yoff;
    private final double zoff;
    private final double toff;

    /** {@code Q->forward}: {@code s11 s12 s13 s21 s22 s23 s31 s32 s33}, row major. */
    private final double[] fwd;
    private final double fwdTScale;

    /** {@code Q->reverse}, the inverted matrix; meaningless when {@link #invertible} is false. */
    private final double[] rev;
    private final double revTScale;

    private final boolean invertible;

    private GieIoUnits left = GieIoUnits.WHATEVER;
    private GieIoUnits right = GieIoUnits.WHATEVER;

    AffineOperator(final ProjParams params) {
        this.xoff = params.doubleValue("xoff", 0.0);
        this.yoff = params.doubleValue("yoff", 0.0);
        this.zoff = params.doubleValue("zoff", 0.0);
        this.toff = params.doubleValue("toff", 0.0);

        this.fwd = new double[] {
            diagonal(params, "s11"), params.doubleValue("s12", 0.0), params.doubleValue("s13", 0.0),
            params.doubleValue("s21", 0.0), diagonal(params, "s22"), params.doubleValue("s23", 0.0),
            params.doubleValue("s31", 0.0), params.doubleValue("s32", 0.0), diagonal(params, "s33"),
        };
        this.fwdTScale = diagonal(params, "tscale");

        // computeReverseParameters (affine.cpp:135-180), verbatim including the sign
        // pattern: the reverse matrix is the adjugate transposed, divided by det.
        final double a = fwd[0];
        final double b = fwd[1];
        final double c = fwd[2];
        final double d = fwd[3];
        final double e = fwd[4];
        final double f = fwd[5];
        final double g = fwd[6];
        final double h = fwd[7];
        final double i = fwd[8];
        final double ca = e * i - f * h;
        final double cb = -(d * i - f * g);
        final double cc = d * h - e * g;
        final double cd = -(b * i - c * h);
        final double ce = a * i - c * g;
        final double cf = -(a * h - b * g);
        final double cg = b * f - c * e;
        final double ch = -(a * f - c * d);
        final double ci = a * e - b * d;
        final double det = a * ca + b * cb + c * cc;
        if (det == 0.0 || fwdTScale == 0.0) {
            this.invertible = false;
            this.rev = new double[] {1, 0, 0, 0, 1, 0, 0, 0, 1};
            this.revTScale = 1.0;
        } else {
            this.invertible = true;
            this.rev = new double[] {
                ca / det, cd / det, cg / det,
                cb / det, ce / det, ch / det,
                cc / det, cf / det, ci / det,
            };
            this.revTScale = 1.0 / fwdTScale;
        }
    }

    /**
     * A diagonal coefficient: 1 when the key is <em>absent</em>, its value otherwise —
     * {@code if (pj_param(..., "ts11").i)}.
     *
     * @param params the step's parameters
     * @param key    {@code s11}, {@code s22}, {@code s33} or {@code tscale}
     * @return the coefficient
     */
    private static double diagonal(final ProjParams params, final String key) {
        return params.has(key) ? params.doubleValue(key, 0.0) : 1.0;
    }

    /** {@code forward_4d}. */
    @Override
    public void forward(final double[] coord) {
        final double x = coord[0];
        final double y = coord[1];
        final double z = coord[2];
        coord[0] = xoff + fwd[0] * x + fwd[1] * y + fwd[2] * z;
        coord[1] = yoff + fwd[3] * x + fwd[4] * y + fwd[5] * z;
        coord[2] = zoff + fwd[6] * x + fwd[7] * y + fwd[8] * z;
        coord[3] = toff + fwdTScale * coord[3];
    }

    /** {@code reverse_4d}: de-offset, then the inverse matrix. */
    @Override
    public void inverse(final double[] coord) {
        if (!invertible) {
            throw new PipelineDefinitionException(PipelineErrorCode.NO_INVERSE_OP,
                    "+proj=affine: the matrix is singular (or +tscale=0), so upstream removes "
                            + "the inverse rather than failing construction");
        }
        final double x = coord[0] - xoff;
        final double y = coord[1] - yoff;
        final double z = coord[2] - zoff;
        coord[0] = rev[0] * x + rev[1] * y + rev[2] * z;
        coord[1] = rev[3] * x + rev[4] * y + rev[5] * z;
        coord[2] = rev[6] * x + rev[7] * y + rev[8] * z;
        coord[3] = revTScale * (coord[3] - toff);
    }

    @Override
    public GieIoUnits declaredLeft() {
        return left;
    }

    @Override
    public GieIoUnits declaredRight() {
        return right;
    }

    @Override
    public void overrideUnits(final GieIoUnits newLeft, final GieIoUnits newRight) {
        this.left = newLeft;
        this.right = newRight;
    }

    @Override
    public boolean hasInverse() {
        return invertible;
    }

    @Override
    public String description() {
        return "affine xoff=" + xoff + " yoff=" + yoff + " zoff=" + zoff
                + (invertible ? "" : " (singular: no inverse)");
    }

    @Override
    public String toString() {
        return "AffineOperator[" + description() + ", left=" + left + ", right=" + right + "]";
    }
}
