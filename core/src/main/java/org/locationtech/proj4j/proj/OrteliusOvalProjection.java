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
 * Ortelius Oval ({@code +proj=ortel}), {@code 9.8.1:src/projections/bacon.cpp:70-82}.
 *
 * <p><b>This lives in {@code bacon.cpp}, not a file of its own.</b> The commonly-cited
 * one-file-several-names map lists {@code ortel} separately, which would mean writing the
 * globular machinery a second time; it is {@code bacon.cpp}'s third {@code PROJ_HEAD} and
 * inherits the whole forward.
 *
 * <p>{@code ortl = 1} adds the second easting branch for {@code |lam| &gt;= pi/2}, which
 * replaces the circular arc with a straight segment offset by
 * {@code sqrt((pi/2)^2 - phi^2 + 1e-10)} — the epsilon being what keeps the radicand
 * non-negative at the poles.
 *
 * @see BaconFamilyProjection
 */
public class OrteliusOvalProjection extends BaconFamilyProjection {

    private static final long serialVersionUID = 5277454540464272723L;

    public OrteliusOvalProjection() {
        super(false, true);
    }

    public String toString() {
        return "Ortelius Oval";
    }
}
