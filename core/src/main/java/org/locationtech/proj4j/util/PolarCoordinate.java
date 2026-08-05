/*******************************************************************************
 * Copyright 2009, 2017 Martin Davis
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
package org.locationtech.proj4j.util;

import java.io.Serializable;
import java.util.Locale;

public final class PolarCoordinate implements Serializable {

    private static final long serialVersionUID = 3961886979169912470L;

    public double lam, phi;

    public PolarCoordinate(PolarCoordinate that) {
        this(that.lam, that.phi);
    }

    public PolarCoordinate(double lam, double phi) {
        this.lam = lam;
        this.phi = phi;
    }

    @Override
    public String toString() {
        // Locale.ROOT: %f is locale-sensitive. Without it this toString reads "<λ0,500000>" under
        // de_DE/tr_TR and renders in Arabic-Indic digits under ar_EG, so a diagnostic that is
        // supposed to be comparable across runs would depend on the ambient environment.
        return String.format(Locale.ROOT, "<λ%f, φ%f>", lam, phi);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Three things changed here, all of them defects rather than style.
     *
     * <p><b>The combiner was {@code |}.</b> Bitwise OR is monotone in every bit — no later term
     * can clear a bit an earlier one set — so it drives a combination of two 32-bit values towards
     * {@code 0xFFFFFFFF} and throws away most of what each carried. A {@code 31}-chain, as in
     * {@code Projection.hashCode}, does not.
     *
     * <p><b>It allocated.</b> {@code new Double(lam).hashCode()} boxes purely to reach a static
     * method. This type is the node type of {@code Grid.ConversionTable}, so the boxes were minted
     * two per node — about 2.1 million for one hash of the shipped {@code ntv1_can.dat}.
     *
     * <p><b>It disagreed with {@link #equals}.</b> {@code equals} compares with {@code ==}, under
     * which {@code -0.0} and {@code 0.0} are equal, while {@code Double.hashCode} gives them
     * different values — so two equal coordinates could hash differently, which is a broken
     * contract and not merely a bad distribution. Negative zero is normalised first, exactly as
     * {@code Projection.hash(double)} does. {@code NaN} is left alone: {@code NaN != NaN} makes
     * two NaN coordinates unequal, and unequal objects are permitted to share a hash.
     */
    @Override
    public int hashCode() {
        int h = 17;
        h = 31 * h + Double.hashCode(lam == 0.0 ? 0.0 : lam);
        h = 31 * h + Double.hashCode(phi == 0.0 ? 0.0 : phi);
        return h;
    }

    @Override
    public boolean equals(Object that) {
        if (that instanceof PolarCoordinate) {
            PolarCoordinate c = (PolarCoordinate) that;
            return lam == c.lam && phi == c.phi;
        } else {
            return false;
        }
    }
}
