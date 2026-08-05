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

/**
 * General Sinusoidal Series ({@code +proj=gn_sinu}),
 * {@code 9.8.1:src/projections/gn_sinu.cpp:171-203}. The parameterised member of the family:
 * both {@code +m} and {@code +n} are <b>required</b> and neither is documented.
 *
 * <p>Constructed with {@code m = 0} and {@code n = 0}, which is deliberately invalid — see
 * {@link GeneralSinusoidalSeriesProjection}'s constructor note. Supply the shape through
 * {@link #setM(double)} and {@link #setN(double)} and then call {@link #initialize()};
 * {@code initialize()} rejects {@code n <= 0} and {@code m < 0} exactly as
 * {@code gn_sinu.cpp:186-196} does, so the invalid default cannot be used by accident.
 *
 * @see GeneralSinusoidalSeriesProjection
 */
public class GeneralSinusoidalProjection extends GeneralSinusoidalSeriesProjection {

    private static final long serialVersionUID = 4062207188592435795L;

    public GeneralSinusoidalProjection() {
        super(0.0, 0.0);
    }

    public String toString() {
        return "General Sinusoidal Series";
    }
}
