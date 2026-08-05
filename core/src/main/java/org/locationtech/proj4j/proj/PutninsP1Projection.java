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
 * Putnins P1 ({@code +proj=putp1}), the fourth {@code PROJ_HEAD} of
 * {@code 9.8.1:src/projections/eck3.cpp}.
 *
 * <p><b>Not in {@code putp3.cpp}, {@code putp5.cpp} or {@code putp6.cpp}</b> — it shares the
 * Eckert III forward/inverse pair, not any Putnins-specific one. The commonly-cited
 * one-file-several-names map gets this wrong, and grouping it with the other {@code putp*}
 * means reimplementing something that comes for free here.
 *
 * <p>{@code A = -0.5}, uniquely negative in the family, which pinches the meridians inward
 * near the poles. It also makes {@code putp1} the only member whose inverse denominator can
 * actually vanish at a reachable latitude — {@code sqrt(1 - B phi^2) = 0.5} at
 * {@code |phi| = 87.7}&deg;. See {@link Eckert3FamilyProjection#projectInverse}.
 *
 * @see Eckert3FamilyProjection
 */
public class PutninsP1Projection extends Eckert3FamilyProjection {

    private static final long serialVersionUID = -7815796434674308416L;

    public PutninsP1Projection() {
        super(1.89490, 0.94745, -0.5, 0.30396355092701331433);
    }

    public String toString() {
        return "Putnins P1";
    }
}
