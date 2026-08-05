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

public final class IntPolarCoordinate implements Serializable {

    private static final long serialVersionUID = 7434875552387864261L;

    public int lam, phi;

    public IntPolarCoordinate(IntPolarCoordinate that) {
        this(that.lam, that.phi);
    }

    public IntPolarCoordinate(int lam, int phi) {
        this.lam = lam;
        this.phi = phi;
    }

    @Override
    public String toString() {
        return String.format("ILP %x:%x", lam, phi);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The combiner was {@code |}; see {@link org.locationtech.proj4j.util.PolarCoordinate#hashCode()}.
     * A grid's {@code lim} is a small pair of positive ints, so the old expression's high bits were
     * always zero and its low bits saturated: {@code 100|17*100} and {@code 101|17*101} both come
     * out with almost every bit of the smaller operand absorbed.
     */
    @Override
    public int hashCode() {
        int h = 17;
        h = 31 * h + lam;
        h = 31 * h + phi;
        return h;
    }

    @Override
    public boolean equals(Object that) {
        if (that instanceof IntPolarCoordinate) {
            IntPolarCoordinate c = (IntPolarCoordinate) that;
            return lam == c.lam && phi == c.phi;
        } else {
            return false;
        }
    }
}
