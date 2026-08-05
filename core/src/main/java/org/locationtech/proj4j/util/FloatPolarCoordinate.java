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

public final class FloatPolarCoordinate implements Serializable {

    private static final long serialVersionUID = 1730033191364185363L;

    public float lam, phi;

    public FloatPolarCoordinate(FloatPolarCoordinate that) {
        this(that.lam, that.phi);
    }

    public FloatPolarCoordinate(float lam, float phi) {
        this.lam = lam;
        this.phi = phi;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The bitwise-OR combiner, the {@code new Float(...)} box and the {@code -0.0f}/{@code 0.0f}
     * disagreement with {@link #equals} are the same three defects documented on
     * {@link PolarCoordinate#hashCode()}, and they matter most here: this is the element type of
     * {@code Grid.ConversionTable.cvs}, so {@code Arrays.hashCode(cvs)} runs this once per grid
     * node — 1,048,576 times for the shipped {@code ntv1_can.dat}.
     */
    @Override
    public int hashCode() {
        int h = 17;
        h = 31 * h + Float.hashCode(lam == 0.0f ? 0.0f : lam);
        h = 31 * h + Float.hashCode(phi == 0.0f ? 0.0f : phi);
        return h;
    }

    @Override
    public boolean equals(Object that) {
        if (that instanceof FloatPolarCoordinate) {
            FloatPolarCoordinate c = (FloatPolarCoordinate) that;
            return lam == c.lam && phi == c.phi;
        } else {
            return false;
        }
    }
}
